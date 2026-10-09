import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'jam_protocol.dart';
import 'jam_session.dart';

/// A Jam another phone on this Wi-Fi is hosting.
class JamFound {
  JamFound({
    required this.address,
    required this.port,
    required this.name,
    required this.host,
    this.people = 1,
  });
  final String address;
  final int port;
  final String name;
  final String host;
  final int people;
  DateTime seen = DateTime.now();

  String get key => '$address:$port';
}

/// The guest side of a Jam in the same room: one WebSocket to the host's
/// phone.
class JamGuest implements JamSession {
  JamGuest(this.address, this.port);
  final String address;
  final int port;

  WebSocket? _ws;
  String? guestId;
  @override
  String jamName = 'Jam';
  @override
  String hostName = 'Host';
  @override
  JamState state = const JamState();

  /// Host clock minus this phone's clock, in ms.
  int clockOffset = 0;

  final _states = StreamController<JamState>.broadcast();
  final Map<int, Completer<List<JamTrack>>> _searches = {};
  int _rid = 0;
  Timer? _pinger;

  @override
  Stream<JamState> get states => _states.stream;

  final _closed = Completer<String>();
  @override
  Future<String> get closed => _closed.future;

  /// Listens for Jam beacons on the Wi-Fi. Cancel the subscription to stop.
  static Stream<JamFound> discover() {
    late StreamController<JamFound> out;
    RawDatagramSocket? socket;
    out = StreamController<JamFound>(
      onListen: () async {
        try {
          socket = await RawDatagramSocket.bind(
            InternetAddress.anyIPv4,
            beaconPort,
            reuseAddress: true,
          );
          socket!.broadcastEnabled = true;
          socket!.listen((e) {
            if (e != RawSocketEvent.read) return;
            final d = socket?.receive();
            if (d == null) return;
            try {
              final j = jsonDecode(utf8.decode(d.data)) as Map;
              if (j['tag'] != beaconTag) return;
              out.add(
                JamFound(
                  address: d.address.address,
                  port: (j['port'] as num).toInt(),
                  name: '${j['name']}',
                  host: '${j['host']}',
                  people: (j['people'] as num?)?.toInt() ?? 1,
                ),
              );
            } catch (_) {}
          });
        } catch (e) {
          out.addError(e);
        }
      },
      onCancel: () => socket?.close(),
    );
    return out.stream;
  }

  Uri _http(String path, [Map<String, String>? q]) => Uri(
    scheme: 'http',
    host: address,
    port: port,
    path: path,
    queryParameters: q,
  );

  @override
  String? artFor(JamTrack t) =>
      t.hasArt ? _http('/art/${Uri.encodeComponent(t.id)}').toString() : null;

  /// Connects and waits for the host's welcome.
  Future<void> join(String myName) async {
    final ws = await WebSocket.connect(
      Uri(
        scheme: 'ws',
        host: address,
        port: port,
        path: '/ws',
        queryParameters: {'name': myName},
      ).toString(),
    ).timeout(const Duration(seconds: 8));
    ws.pingInterval = const Duration(seconds: 10);
    _ws = ws;
    final welcome = Completer<void>();
    ws.listen(
      (raw) {
        if (raw is! String) return;
        final m = jsonDecode(raw) as Map;
        switch (m['t']) {
          case 'welcome':
            guestId = m['you'] as String?;
            jamName = '${m['name']}';
            hostName = '${m['host']}';
            if (!welcome.isCompleted) welcome.complete();
          case 'state':
            state = JamState.fromJson(m);
            _states.add(state);
          case 'pong':
            final sent = (m['c'] as num).toInt();
            final now = DateTime.now().millisecondsSinceEpoch;
            final host = (m['h'] as num).toInt();
            clockOffset = host - (sent + now) ~/ 2;
          case 'results':
            _searches.remove((m['rid'] as num).toInt())?.complete([
              for (final t in (m['items'] as List?) ?? [])
                JamTrack.fromJson(t as Map),
            ]);
          case 'bye':
            _finish('${m['reason'] ?? 'ended'}');
        }
      },
      onDone: () => _finish('lost'),
      onError: (_) => _finish('lost'),
      cancelOnError: true,
    );
    await welcome.future.timeout(const Duration(seconds: 8));
    _ping();
    _pinger = Timer.periodic(const Duration(seconds: 20), (_) => _ping());
  }

  void _finish(String reason) {
    _pinger?.cancel();
    for (final s in _searches.values) {
      s.complete(const []);
    }
    _searches.clear();
    if (!_closed.isCompleted) _closed.complete(reason);
  }

  void _send(Map<String, dynamic> m) {
    try {
      _ws?.add(jsonEncode(m));
    } catch (_) {} // the Jam just ended; [closed] says so
  }

  void _ping() =>
      _send({'t': 'ping', 'c': DateTime.now().millisecondsSinceEpoch});

  /// Worked out from the last update and the clock difference.
  @override
  Duration get position {
    var ms = state.positionMs;
    if (state.playing) {
      ms += DateTime.now().millisecondsSinceEpoch + clockOffset - state.at;
    }
    final dur = state.now?.durationMs;
    if (dur != null && ms > dur) ms = dur;
    return Duration(milliseconds: ms < 0 ? 0 : ms);
  }

  @override
  Future<List<JamTrack>> search(String query) {
    final id = ++_rid;
    final c = Completer<List<JamTrack>>();
    _searches[id] = c;
    _send({'t': 'search', 'q': query, 'rid': id});
    return c.future.timeout(
      const Duration(seconds: 10),
      onTimeout: () {
        _searches.remove(id);
        return const [];
      },
    );
  }

  @override
  void add(String trackId, {bool next = false}) =>
      _send({'t': 'add', 'ref': trackId, 'next': next});

  @override
  void control(String action, [int? ms]) =>
      _send({'t': 'ctl', 'a': action, 'ms': ?ms});

  /// Uploads the song to the host's phone.
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
    final id = '${DateTime.now().microsecondsSinceEpoch}';
    final client = HttpClient();
    try {
      Future<void> post(String kind, Stream<List<int>> body, int length) async {
        final req = await client.postUrl(
          _http('/upload', {
            'g': guestId ?? '',
            'id': id,
            'kind': kind,
            'ext': ext,
          }),
        );
        req.contentLength = length;
        await req.addStream(body);
        final res = await req.close();
        await res.drain<void>();
        if (res.statusCode == 413) throw const JamError('That song is too big');
        if (res.statusCode != 200) throw const JamError('Could not send it');
      }

      if (art != null && art.isNotEmpty) {
        await post('art', Stream.value(art), art.length);
      }
      final total = await audio.length();
      var sent = 0;
      await post(
        'audio',
        audio.openRead().map((chunk) {
          sent += chunk.length;
          onProgress?.call(total == 0 ? 1 : sent / total);
          return chunk;
        }),
        total,
      );
      _send({
        't': 'addUpload',
        'upload': id,
        'title': title,
        'artist': artist,
        'dur': ?durationMs,
        'next': next,
      });
    } finally {
      client.close();
    }
  }

  @override
  Future<void> leave() async {
    _finish('left');
    await _ws?.close();
    await _states.close();
  }
}
