import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:http/http.dart' as http;

import 'jam_protocol.dart';
import 'jam_session.dart';

/// A channel on ntfy.sh, a free public message relay: phones in an online
/// Jam publish small JSON messages to it and listen for each other's.
///
/// ntfy.sh allows about 250 messages a day per phone, so the Jam only sends
/// a message when something actually changes.
class NtfyRelay {
  NtfyRelay(this.topic, {String? base, http.Client? client})
    : base = base ?? defaultBase,
      _client = client ?? http.Client();

  static String defaultBase = const String.fromEnvironment(
    'JAM_RELAY',
    defaultValue: 'https://ntfy.sh',
  );

  final String topic;
  final String base;
  final http.Client _client;

  /// Messages over this size would be turned into attachments by ntfy.
  static const maxBytes = 3900;

  Future<void> publish(Map<String, dynamic> msg) async {
    final res = await _client
        .post(Uri.parse('$base/$topic'), body: utf8.encode(jsonEncode(msg)))
        .timeout(const Duration(seconds: 15));
    if (res.statusCode == 429) throw const JamError('busy');
    if (res.statusCode != 200) throw JamError('relay ${res.statusCode}');
  }

  /// Messages kept by the relay since [since] (e.g. "12h"), oldest first.
  Future<List<(String, Map)>> history(String since) async {
    final res = await _client
        .get(Uri.parse('$base/$topic/json?poll=1&since=$since'))
        .timeout(const Duration(seconds: 15));
    if (res.statusCode != 200) throw JamError('relay ${res.statusCode}');
    return [
      for (final line in const LineSplitter().convert(
        utf8.decode(res.bodyBytes),
      ))
        ?_parse(line),
    ];
  }

  static (String, Map)? _parse(String line) {
    if (line.trim().isEmpty) return null;
    try {
      final e = jsonDecode(line) as Map;
      if (e['event'] != 'message') return null;
      return (e['id'] as String, jsonDecode(e['message'] as String) as Map);
    } catch (_) {
      return null;
    }
  }

  /// New messages as they arrive, reconnecting by itself after drops.
  Stream<Map> listen({String? since}) {
    var last = since;
    var stopped = false;
    late StreamController<Map> out;
    Future<void> loop() async {
      var wait = 1;
      while (!stopped) {
        try {
          final q = last == null ? '' : '?since=$last';
          final res = await _client.send(
            http.Request('GET', Uri.parse('$base/$topic/json$q')),
          );
          if (res.statusCode != 200) throw JamError('${res.statusCode}');
          wait = 1;
          await for (final line
              in res.stream
                  .transform(utf8.decoder)
                  .transform(const LineSplitter())) {
            if (stopped) break;
            final m = _parse(line);
            if (m == null) continue;
            last = m.$1;
            out.add(m.$2);
          }
        } catch (_) {}
        if (stopped) break;
        // Dropped (or never connected): try again, backing off.
        await Future.delayed(Duration(seconds: wait));
        wait = min(wait * 2, 30);
        last ??= '1m';
      }
    }

    out = StreamController<Map>(onListen: loop, onCancel: () => stopped = true);
    return out.stream;
  }

  void close() => _client.close();
}

/// A free temporary file host: where to upload and how to read the reply.
class FileHost {
  const FileHost({
    required this.name,
    required this.endpoint,
    required this.field,
    required this.lasts,
    this.fields = const {},
    required this.linkFrom,
  });

  final String name;
  final String endpoint;

  /// Form field that carries the file.
  final String field;
  final Map<String, String> fields;

  /// How long the host keeps files.
  final Duration lasts;

  /// The direct file link from the host's reply.
  final String Function(String body) linkFrom;

  String get origin {
    final u = Uri.parse(endpoint);
    return '${u.scheme}://${u.authority}';
  }
}

/// Songs in an online Jam are uploaded to free temporary file hosts as
/// unlisted links that delete themselves. Hosts are tried in turn (some are
/// blocked on some networks), every link is checked before anyone gets it,
/// and the host that worked is tried first next time.
abstract final class FileDrop {
  /// Tried in this order at first.
  static final List<FileHost> defaults = [
    FileHost(
      name: 'Litterbox',
      endpoint: 'https://litterbox.catbox.moe/resources/internals/api.php',
      field: 'fileToUpload',
      fields: const {'reqtype': 'fileupload', 'time': '12h'},
      lasts: const Duration(hours: 12),
      linkFrom: (body) => body.trim(),
    ),
    FileHost(
      name: 'Uguu',
      endpoint: 'https://uguu.se/upload',
      field: 'files[]',
      lasts: const Duration(hours: 3),
      linkFrom: (body) =>
          (((jsonDecode(body) as Map)['files'] as List).first as Map)['url']
              as String,
    ),
    FileHost(
      name: 'tmpfiles',
      endpoint: 'https://tmpfiles.org/api/v1/upload',
      field: 'file',
      fields: const {'expire': '43200'}, // 12 hours
      lasts: const Duration(hours: 12),
      linkFrom: (body) {
        final page = Uri.parse(
          ((jsonDecode(body) as Map)['data'] as Map)['url'] as String,
        );
        // The page link shows a download page; /dl/ is the file itself.
        return page
            .replace(
              scheme: page.host == 'tmpfiles.org' ? 'https' : page.scheme,
              pathSegments: ['dl', ...page.pathSegments],
            )
            .toString();
      },
    ),
  ];

  /// Tried in turn; the one that last worked comes first.
  static List<FileHost> hosts = List.of(defaults);

  /// Whether [url] is a link we accept: https, or one of the hosts.
  static bool isLink(String? url) {
    if (url == null) return false;
    if (url.startsWith('https://')) return true;
    return hosts.any((h) => url.startsWith('${h.origin}/'));
  }

  /// Uploads [bytes] (or the file at [file]); returns the link and how long
  /// it lasts.
  ///
  /// With [verify], the start of the uploaded file is fetched back to make
  /// sure the link really serves it, before anyone is given the link.
  static Future<(String, Duration)> upload({
    File? file,
    List<int>? bytes,
    required String name,
    bool verify = false,
    void Function(double done)? onProgress,
  }) async {
    final problems = <String>[];
    for (final host in List.of(hosts)) {
      try {
        final body = await _post(
          host.endpoint,
          host.fields,
          host.field,
          file,
          bytes,
          name,
          onProgress,
        );
        final link = host.linkFrom(body).trim();
        if (!isLink(link)) throw const JamError('unexpected reply');
        if (verify) await check(link);
        // Start with this host next time.
        hosts
          ..remove(host)
          ..insert(0, host);
        return (link, host.lasts);
      } catch (e) {
        problems.add('${host.name}: ${describe(e)}');
      }
    }
    throw JamError('Could not share the song (${problems.join('; ')})');
  }

  /// Makes sure [link] serves a file (not an error or web page).
  static Future<void> check(String link) async {
    final req = http.Request('GET', Uri.parse(link))
      ..headers['Range'] = 'bytes=0-1023'
      ..headers['User-Agent'] = 'Musicly/1.4 (Flutter)';
    final client = http.Client();
    try {
      final res = await client.send(req).timeout(const Duration(seconds: 20));
      final head = await res.stream
          .take(1)
          .fold<List<int>>([], (a, b) => a..addAll(b))
          .timeout(const Duration(seconds: 20));
      if (res.statusCode != 200 && res.statusCode != 206) {
        throw JamError('link gives HTTP ${res.statusCode}');
      }
      if (looksLikePage(head, res.headers['content-type'])) {
        throw const JamError('link gives a web page, not the song');
      }
    } finally {
      client.close();
    }
  }

  /// An HTML page (error, captcha, download page) rather than a file.
  static bool looksLikePage(List<int> head, String? contentType) {
    if (contentType?.contains('text/html') ?? false) return true;
    final start = utf8
        .decode(head.take(64).toList(), allowMalformed: true)
        .trimLeft()
        .toLowerCase();
    return start.startsWith('<!doctype') || start.startsWith('<html');
  }

  /// A short, readable reason for a network problem.
  static String describe(Object e) {
    if (e is JamError) return e.message;
    if (e is SocketException) {
      final host = e.address?.host;
      return host == null ? 'no connection' : 'can\'t reach $host';
    }
    if (e is HandshakeException) return 'secure connection failed';
    if (e is TimeoutException) return 'timed out';
    if (e is http.ClientException) {
      return 'connection failed (${e.uri?.host ?? e.message})';
    }
    final text = '$e';
    return text.length > 80 ? '${text.substring(0, 80)}…' : text;
  }

  static Future<String> _post(
    String url,
    Map<String, String> fields,
    String field,
    File? file,
    List<int>? bytes,
    String name,
    void Function(double done)? onProgress,
  ) async {
    final length = file != null ? await file.length() : bytes!.length;
    var sent = 0;
    final body = (file != null ? file.openRead() : Stream.value(bytes!)).map((
      chunk,
    ) {
      sent += chunk.length;
      onProgress?.call(length == 0 ? 1 : sent / length);
      return chunk;
    });
    final req = http.MultipartRequest('POST', Uri.parse(url))
      ..headers['User-Agent'] = 'Musicly/1.4 (Flutter)'
      ..fields.addAll(fields)
      ..files.add(http.MultipartFile(field, body, length, filename: name));
    final res = await http.Response.fromStream(await req.send())
        .timeout(const Duration(minutes: 5));
    if (res.statusCode != 200) throw JamError('HTTP ${res.statusCode}');
    return res.body;
  }
}

String _sessionId() {
  final r = Random.secure();
  return List.generate(10, (_) => r.nextInt(36).toRadixString(36)).join();
}

/// Hosting an online Jam: friends anywhere follow along through the relay.
class OnlineJamHost implements JamHosting {
  OnlineJamHost({
    required this.code,
    required this.jamName,
    required this.hostName,
    required this.app,
    NtfyRelay? relay,
  }) : relay = relay ?? NtfyRelay(OnlineJamCode.topic(code));

  final String code;
  final String jamName;
  final String hostName;
  final JamHostCallbacks app;
  final NtfyRelay relay;
  final String _me = _sessionId();

  final Map<String, JamPerson> _guests = {};
  bool _guestsControl = false;
  void Function()? _onPeopleChanged;
  StreamSubscription<Map>? _sub;
  Timer? _send;
  Timer? _sweep;
  DateTime _lastSent = DateTime(2000);
  String? _lastBody;

  /// Gap between messages, to stay within the relay's daily allowance.
  static Duration minGap = const Duration(seconds: 4);

  @override
  List<JamPerson> get guests => _guests.values.toList();
  @override
  set guestsControl(bool on) => _guestsControl = on;
  @override
  set onPeopleChanged(void Function()? f) => _onPeopleChanged = f;

  Future<void> start() async {
    // Only messages from now on; anything older belongs to an earlier Jam.
    _sub = relay
        .listen(since: '${DateTime.now().millisecondsSinceEpoch ~/ 1000}')
        .listen(_message);
    // People who closed the app without leaving drop off after a while.
    _sweep = Timer.periodic(const Duration(minutes: 2), (_) {
      final before = _guests.length;
      _guests.removeWhere(
        (_, g) => DateTime.now().difference(g.seen).inMinutes >= 25,
      );
      if (_guests.length != before) {
        _onPeopleChanged?.call();
        pushState();
      }
    });
    await relay.publish(_stateMessage());
    _lastSent = DateTime.now();
  }

  Map<String, dynamic> _stateMessage() {
    var s = app.state().copyWith(
      people: [hostName, ...guests.map((g) => g.name)],
    );
    Map<String, dynamic> msg() => {
      'src': _me,
      'host': true,
      'name': jamName,
      'hostName': hostName,
      ...s.toJson(),
    };
    // Keep it under the relay's message size by showing less of the queue.
    while (utf8.encode(jsonEncode(msg())).length > NtfyRelay.maxBytes &&
        s.queue.isNotEmpty) {
      s = s.copyWith(queue: s.queue.sublist(0, s.queue.length - 1));
    }
    return msg();
  }

  /// Sends the Jam's state soon, but never more often than [minGap], and
  /// skips it when nothing a friend would notice has changed.
  @override
  void pushState() {
    if (_send?.isActive ?? false) return;
    final wait = minGap - DateTime.now().difference(_lastSent);
    _send = Timer(wait.isNegative ? Duration.zero : wait, _sendNow);
  }

  Future<void> _sendNow() async {
    final msg = _stateMessage();
    // Position and time always change; compare the rest, and resend for a
    // jump (seek) that the friends' clocks wouldn't predict.
    final body = jsonEncode(
      {...msg}
        ..remove('at')
        ..remove('pos'),
    );
    if (body == _lastBody && !_jumped(msg)) return;
    try {
      await relay.publish(msg);
      _lastBody = body;
      _lastSent = DateTime.now();
      _lastPos = (
        (msg['pos'] as int),
        (msg['at'] as int),
        msg['playing'] == true,
      );
    } catch (_) {
      // Busy or offline: try again a little later.
      _send = Timer(const Duration(seconds: 15), _sendNow);
    }
  }

  (int, int, bool)? _lastPos;

  bool _jumped(Map msg) {
    final last = _lastPos;
    if (last == null) return true;
    final expected = last.$3
        ? last.$1 + ((msg['at'] as int) - last.$2)
        : last.$1;
    return ((msg['pos'] as int) - expected).abs() > 2500;
  }

  JamPerson _person(Map m) {
    final id = '${m['g']}';
    final p = _guests[id];
    if (p != null) {
      p.seen = DateTime.now();
      return p;
    }
    final name = '${m['gname'] ?? 'Guest'}'.trim();
    final added = JamPerson(
      id,
      name.isEmpty ? 'Guest' : name.substring(0, min(name.length, 30)),
    );
    _guests[id] = added;
    _onPeopleChanged?.call();
    pushState();
    return added;
  }

  Future<void> _message(Map m) async {
    if (m['src'] == _me || m['g'] == null) return;
    switch (m['t']) {
      case 'hello':
        _person(m);
        _lastBody = null; // make sure the newcomer gets the state
        pushState();
      case 'bye':
        if (_guests.remove('${m['g']}') != null) {
          _onPeopleChanged?.call();
          pushState();
        }
      case 'add':
        await app.onAdd('${m['ref']}', _person(m), m['next'] == true);
      case 'link':
        final url = '${m['url']}';
        if (!FileDrop.isLink(url)) return;
        final art = m['art'] as String?;
        await app.onUpload(
          JamUpload(
            source: url,
            art: FileDrop.isLink(art) ? art : null,
            title: (m['title'] as String?) ?? 'Unknown',
            artist: (m['artist'] as String?) ?? 'Unknown artist',
            durationMs: (m['dur'] as num?)?.toInt(),
          ),
          _person(m),
          m['next'] == true,
        );
      case 'ctl':
        final who = _person(m);
        if (_guestsControl) {
          app.onControl('${m['a']}', (m['ms'] as num?)?.toInt(), who);
        }
    }
  }

  @override
  void remove(String guestId) {
    if (_guests.remove(guestId) == null) return;
    relay.publish({
      'src': _me,
      'host': true,
      't': 'kick',
      'g': guestId,
    }).ignore();
    _onPeopleChanged?.call();
    pushState();
  }

  @override
  Future<void> stop({bool deleteUploads = true}) async {
    _send?.cancel();
    _sweep?.cancel();
    await _sub?.cancel();
    try {
      await relay.publish({'src': _me, 'host': true, 't': 'end'});
    } catch (_) {}
    relay.close();
  }
}

/// Being in an online Jam: following the host's messages on the relay.
class OnlineJamGuest implements JamSession {
  OnlineJamGuest(this.code, {NtfyRelay? relay})
    : relay = relay ?? NtfyRelay(OnlineJamCode.topic(code));

  final String code;
  final NtfyRelay relay;
  final String _me = _sessionId();
  String _myName = 'Guest';

  @override
  String jamName = 'Jam';
  @override
  String hostName = 'Host';
  @override
  JamState state = const JamState();

  final _states = StreamController<JamState>.broadcast();
  final _closed = Completer<String>();
  StreamSubscription<Map>? _sub;
  Timer? _hello;

  @override
  Stream<JamState> get states => _states.stream;
  @override
  Future<String> get closed => _closed.future;

  /// Finds the Jam's latest state on the relay, then follows it.
  Future<void> join(String myName) async {
    _myName = myName;
    final past = await relay.history('12h');
    String? lastId;
    Map? latest;
    for (final (id, m) in past) {
      if (m['host'] != true) continue;
      if (m['t'] == 'state') latest = m;
      if (m['t'] == 'end') latest = null;
      lastId = id;
    }
    if (latest == null) {
      throw const JamError('No Jam is running with that code.');
    }
    _apply(latest);
    _sub = relay.listen(since: lastId).listen(_message);
    await relay.publish(_mine('hello'));
    // Lets the host know we're still here (people who vanish are dropped).
    _hello = Timer.periodic(
      const Duration(minutes: 10),
      (_) => relay.publish(_mine('hello')).ignore(),
    );
  }

  Map<String, dynamic> _mine(String type, [Map<String, dynamic>? more]) => {
    'src': _me,
    't': type,
    'g': _me,
    'gname': _myName,
    ...?more,
  };

  void _apply(Map m) {
    jamName = '${m['name'] ?? jamName}';
    hostName = '${m['hostName'] ?? hostName}';
    state = JamState.fromJson(m);
    if (!_states.isClosed) _states.add(state);
  }

  void _message(Map m) {
    if (m['host'] != true) return;
    switch (m['t']) {
      case 'state':
        _apply(m);
      case 'end':
        _finish('ended');
      case 'kick':
        if (m['g'] == _me) _finish('removed');
    }
  }

  void _finish(String reason) {
    _hello?.cancel();
    _sub?.cancel();
    if (!_closed.isCompleted) _closed.complete(reason);
  }

  /// Uses this phone's clock, which phones keep in sync with network time.
  @override
  Duration get position {
    var ms = state.positionMs;
    if (state.playing) ms += DateTime.now().millisecondsSinceEpoch - state.at;
    final dur = state.now?.durationMs;
    if (dur != null && ms > dur) ms = dur;
    return Duration(milliseconds: max(0, ms));
  }

  String? _libraryUrl;
  List<JamTrack> _library = const [];

  @override
  Future<List<JamTrack>> search(String query) async {
    final url = state.library;
    if (url != null && url != _libraryUrl) {
      try {
        final res = await http
            .get(Uri.parse(url))
            .timeout(const Duration(seconds: 20));
        final j = jsonDecode(utf8.decode(res.bodyBytes)) as Map;
        _library = [
          for (final row in (j['songs'] as List))
            JamTrack(
              id: '${row[0]}',
              title: '${row[1]}',
              artist: '${row[2]}',
              durationMs: (row[3] as num?)?.toInt(),
            ),
        ];
        _libraryUrl = url;
      } catch (_) {}
    }
    final q = query.trim().toLowerCase();
    return _library
        .where(
          (t) =>
              q.isEmpty ||
              t.title.toLowerCase().contains(q) ||
              t.artist.toLowerCase().contains(q),
        )
        .take(200)
        .toList();
  }

  /// The host's song list, as uploaded for friends to browse.
  static String libraryJson(List<JamTrack> songs) => jsonEncode({
    'v': 1,
    'songs': [
      for (final t in songs) [t.id, t.title, t.artist, t.durationMs],
    ],
  });

  @override
  void add(String trackId, {bool next = false}) =>
      relay.publish(_mine('add', {'ref': trackId, 'next': next})).ignore();

  @override
  void control(String action, [int? ms]) =>
      relay.publish(_mine('ctl', {'a': action, 'ms': ?ms})).ignore();

  @override
  String? artFor(JamTrack t) => t.artUrl;

  @override
  Future<void> sendSong({
    required File audio,
    required String ext,
    required String title,
    required String artist,
    List<int>? art,
    int? durationMs,
    bool next = false,
    void Function(double done)? onProgress,
  }) async {
    String? artUrl;
    if (art != null && art.isNotEmpty) {
      try {
        artUrl = (await FileDrop.upload(bytes: art, name: 'cover.jpg')).$1;
      } catch (_) {}
    }
    final (url, _) = await FileDrop.upload(
      file: audio,
      name: 'song.$ext',
      onProgress: onProgress,
    );
    await relay.publish(
      _mine('link', {
        'url': url,
        'art': ?artUrl,
        'title': title,
        'artist': artist,
        'dur': ?durationMs,
        'next': next,
      }),
    );
  }

  @override
  Future<void> leave() async {
    try {
      await relay.publish(_mine('bye'));
    } catch (_) {}
    _finish('left');
    await _states.close();
    relay.close();
  }
}
