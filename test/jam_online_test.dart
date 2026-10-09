import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:musicly/services/jam_online.dart';
import 'package:musicly/services/jam_protocol.dart';
import 'package:musicly/services/jam_session.dart';

/// Just enough of ntfy.sh and Litterbox to run an online Jam locally.
class FakeCloud {
  late HttpServer server;
  final Map<String, List<Map<String, dynamic>>> topics = {};
  final Map<String, List<HttpResponse>> listeners = {};
  final Map<String, List<int>> files = {};
  int _seq = 0;
  int published = 0;

  String get base => 'http://127.0.0.1:${server.port}';

  Future<void> start() async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen(_handle);
  }

  Future<void> _handle(HttpRequest req) async {
    final seg = req.uri.pathSegments;
    if (seg.first == 'fail') {
      await req.drain<void>();
      req.response.statusCode = 503;
      return req.response.close();
    }
    if (seg.first == 'uguu') {
      final body = await req.fold<List<int>>([], (a, b) => a..addAll(b));
      final name = 'u${_seq++}';
      files[name] = body;
      req.response.write(
        jsonEncode({
          'success': true,
          'files': [
            {'url': '$base/files/$name', 'filename': name},
          ],
        }),
      );
      return req.response.close();
    }
    if (seg.first == 'tmpapi' || seg.first == 'tmpweb') {
      // tmpweb: like tmpfiles when its /dl/ links give a web page.
      final web = seg.first == 'tmpweb';
      final body = await req.fold<List<int>>([], (a, b) => a..addAll(b));
      final name = 't${_seq++}';
      files[name] = body;
      req.response.write(
        jsonEncode({
          'status': 'success',
          'data': {'url': '$base/${web ? 'page' : '123'}/$name'},
        }),
      );
      return req.response.close();
    }
    if (seg.first == 'page') {
      req.response.headers.contentType = ContentType.html;
      req.response.write('<!DOCTYPE html><html>Download page</html>');
      return req.response.close();
    }
    if (seg.first == 'dl' && seg[1] == 'page') {
      req.response.headers.contentType = ContentType.html;
      req.response.write('<!DOCTYPE html><html>Click to download</html>');
      return req.response.close();
    }
    if (seg.first == 'dl') {
      req.response.add(files[seg.last] ?? const []);
      return req.response.close();
    }
    if (seg.first == 'files') {
      if (req.method == 'POST') {
        final body = await req.fold<List<int>>([], (a, b) => a..addAll(b));
        final name = 'f${_seq++}';
        files[name] = body;
        req.response.write('$base/files/$name');
      } else {
        req.response.add(_fileBytes(seg[1]));
      }
      return req.response.close();
    }
    final topic = seg.first;
    final list = topics.putIfAbsent(topic, () => []);
    if (req.method == 'POST') {
      final body = await utf8.decodeStream(req);
      final e = {
        'id': 'm${_seq++}',
        'time': DateTime.now().millisecondsSinceEpoch ~/ 1000,
        'event': 'message',
        'topic': topic,
        'message': body,
      };
      list.add(e);
      published++;
      for (final r in listeners[topic] ?? <HttpResponse>[]) {
        r.write('${jsonEncode(e)}\n');
        await r.flush();
      }
      return req.response.close();
    }
    req.response.bufferOutput = false;
    final since = req.uri.queryParameters['since'];
    Iterable<Map> past = const [];
    if (since != null) {
      final i = list.indexWhere((e) => e['id'] == since);
      final ts = int.tryParse(since);
      past = i >= 0
          ? list.skip(i + 1)
          : ts != null
          ? list.where((e) => (e['time'] as int) >= ts)
          : list;
    }
    for (final e in past) {
      req.response.write('${jsonEncode(e)}\n');
    }
    if (req.uri.queryParameters['poll'] == '1') return req.response.close();
    await req.response.flush();
    listeners.putIfAbsent(topic, () => []).add(req.response);
  }

  /// Multipart body as uploaded: pull the file part back out.
  List<int> _fileBytes(String name) => files[name] ?? const [];

  Future<void> stop() async {
    for (final l in listeners.values.expand((x) => x)) {
      try {
        await l.close();
      } catch (_) {}
    }
    await server.close(force: true);
  }
}

void main() {
  late FakeCloud cloud;

  /// The real hosts' formats, pointed at the fake cloud's [routes].
  List<FileHost> hosts({
    required String litterbox,
    required String uguu,
    required String tmp,
  }) {
    final real = {for (final h in FileDrop.defaults) h.name: h};
    FileHost at(String name, String route) => FileHost(
      name: name,
      endpoint: '${cloud.base}/$route',
      field: real[name]!.field,
      fields: real[name]!.fields,
      lasts: real[name]!.lasts,
      linkFrom: real[name]!.linkFrom,
    );
    return [at('Litterbox', litterbox), at('Uguu', uguu), at('tmpfiles', tmp)];
  }

  final added = <(String, String, bool)>[];
  final uploads = <(JamUpload, String)>[];
  final controls = <String>[];
  var now = const JamTrack(
    id: 'tg:a',
    title: 'Blinding Lights',
    artist: 'The Weeknd',
    durationMs: 200000,
    url: 'https://files/a.mp3',
  );
  var playing = true;

  JamHostCallbacks app() => JamHostCallbacks(
    state: () => JamState(
      now: now,
      playing: playing,
      positionMs: 30000,
      at: DateTime.now().millisecondsSinceEpoch,
      queue: [
        for (var i = 0; i < 80; i++)
          JamTrack(id: 'q$i', title: 'Queued song $i', artist: 'Artist $i'),
      ],
      library: '${cloud.base}/files/lib',
    ),
    onAdd: (id, by, next) async => added.add((id, by.name, next)),
    onUpload: (song, by, next) async => uploads.add((song, by.name)),
    onControl: (a, ms, by) => controls.add(a),
    search: (_) => const [],
    art: (_) async => null,
  );

  setUp(() async {
    cloud = FakeCloud();
    await cloud.start();
    FileDrop.hosts = hosts(litterbox: 'files', uguu: 'uguu', tmp: 'tmpapi');
    OnlineJamHost.minGap = const Duration(milliseconds: 200);
    added.clear();
    uploads.clear();
    controls.clear();
    cloud.files['lib'] = utf8.encode(
      OnlineJamGuest.libraryJson(const [
        JamTrack(id: 'tg:a', title: 'Blinding Lights', artist: 'The Weeknd'),
        JamTrack(id: 'dev:7', title: 'Lose Yourself', artist: 'Eminem'),
      ]),
    );
  });

  tearDown(() => cloud.stop());

  test('online codes', () {
    final c = OnlineJamCode.create();
    expect(c, matches(RegExp(r'^[2-9A-Z]{4}-[2-9A-Z]{4}-[2-9A-Z]{4}$')));
    expect(OnlineJamCode.normalise(c.toLowerCase().replaceAll('-', '')), c);
    expect(OnlineJamCode.normalise('ABCD-EFGH-JK'), isNull);
    expect(OnlineJamCode.topic('K7QM-2XRB-9TFA'), 'musicly-jam-k7qm2xrb9tfa');
  });

  test('a friend joins, follows along and adds songs', () async {
    const code = 'K7QM-2XRB-9TFA';
    final topic = OnlineJamCode.topic(code);
    final host = OnlineJamHost(
      code: code,
      jamName: "Kidyoh's Jam",
      hostName: 'Kidyoh',
      app: app(),
      relay: NtfyRelay(topic, base: cloud.base),
    );
    await host.start();

    // Every message fits in the relay's size limit.
    final first = cloud.topics[topic]!.first['message'] as String;
    expect(utf8.encode(first).length, lessThanOrEqualTo(NtfyRelay.maxBytes));

    final guest = OnlineJamGuest(
      code,
      relay: NtfyRelay(topic, base: cloud.base),
    );
    final sawAbel = guest.states.firstWhere((s) => s.people.contains('Abel'));
    await guest.join('Abel');
    expect(guest.jamName, "Kidyoh's Jam");
    expect(guest.hostName, 'Kidyoh');
    expect(guest.state.now?.url, 'https://files/a.mp3');
    expect(guest.position.inMilliseconds, greaterThanOrEqualTo(30000));

    // The host hears hello and tells everyone who's here.
    final withAbel = await sawAbel.timeout(const Duration(seconds: 5));
    expect(withAbel.people, ['Kidyoh', 'Abel']);
    expect(host.guests.map((g) => g.name), ['Abel']);

    // Browsing the host's library.
    final found = await guest.search('lose');
    expect(found.map((t) => t.id), ['dev:7']);

    guest.add('dev:7', next: true);
    await _until(() => added.isNotEmpty);
    expect(added.single, ('dev:7', 'Abel', true));

    // Sending a song from the friend's phone goes through the file host.
    final dir = await Directory.systemTemp.createTemp('jamonline');
    final song = File('${dir.path}/s.mp3')..writeAsBytesSync([1, 2, 3, 4]);
    var progress = 0.0;
    await guest.sendSong(
      audio: song,
      ext: 'mp3',
      title: 'My Song',
      artist: 'Me',
      art: [7, 7],
      durationMs: 1000,
      onProgress: (p) => progress = p,
    );
    await _until(() => uploads.isNotEmpty);
    expect(progress, 1.0);
    final (up, by) = uploads.single;
    expect(by, 'Abel');
    expect(up.title, 'My Song');
    expect(up.source, startsWith('${cloud.base}/files/'));
    // (The fake file host stores the raw multipart body.)
    expect(up.art, isNot(isNull));

    // Control only once the host allows it.
    guest.control('next');
    await Future.delayed(const Duration(milliseconds: 300));
    expect(controls, isEmpty);
    host.guestsControl = true;
    guest.control('next');
    await _until(() => controls.isNotEmpty);

    // Nothing changed: no new message. A change: exactly one more.
    await Future.delayed(const Duration(milliseconds: 400));
    final before = cloud.published;
    host.pushState();
    await Future.delayed(const Duration(milliseconds: 400));
    expect(cloud.published, before);
    now = const JamTrack(
      id: 'dev:7',
      title: 'Lose Yourself',
      artist: 'Eminem',
      url: 'https://files/b.mp3',
    );
    final changed = guest.states.firstWhere((s) => s.now?.id == 'dev:7');
    host.pushState();
    host.pushState();
    expect(
      (await changed.timeout(const Duration(seconds: 5))).now?.url,
      'https://files/b.mp3',
    );
    await Future.delayed(const Duration(milliseconds: 400));
    expect(cloud.published, before + 1);

    // Ending the Jam reaches the friend.
    await host.stop();
    expect(await guest.closed.timeout(const Duration(seconds: 5)), 'ended');

    // And a late joiner is told there's no Jam.
    final late = OnlineJamGuest(
      code,
      relay: NtfyRelay(topic, base: cloud.base),
    );
    await expectLater(late.join('Sara'), throwsA(isA<JamError>()));
    await dir.delete(recursive: true);
  });

  test('uploads move on to a host that works, and remember it', () async {
    final (good, lasts) = await FileDrop.upload(bytes: [1, 2, 3], name: 'a');
    expect(good, startsWith('${cloud.base}/files/f'));
    expect(lasts, const Duration(hours: 12));

    // Like on Kidus's network: Litterbox refuses uploads.
    FileDrop.hosts = hosts(litterbox: 'fail', uguu: 'uguu', tmp: 'tmpweb');
    var progress = 0.0;
    final (link, lasts2) = await FileDrop.upload(
      bytes: List.filled(5000, 7),
      name: 'b.mp3',
      verify: true,
      onProgress: (p) => progress = p,
    );
    expect(link, startsWith('${cloud.base}/files/u'));
    expect(lasts2, const Duration(hours: 3));
    expect(progress, 1.0);
    expect(FileDrop.isLink(link), isTrue);
    // Uguu worked, so it goes first next time.
    expect(FileDrop.hosts.first.name, 'Uguu');

    // tmpfiles' direct link is still built right when it's the one used.
    FileDrop.hosts = hosts(litterbox: 'fail', uguu: 'fail', tmp: 'tmpapi');
    final (tmp, _) = await FileDrop.upload(bytes: [5], name: 'c', verify: true);
    expect(tmp, matches(RegExp(r'/dl/123/t\d+$')));

    // Nothing works: the error says why for each host.
    FileDrop.hosts = hosts(litterbox: 'fail', uguu: 'fail', tmp: 'tmpweb');
    await expectLater(
      FileDrop.upload(bytes: [1], name: 'd', verify: true),
      throwsA(
        isA<JamError>().having(
          (e) => e.message,
          'message',
          allOf(
            contains('Litterbox: HTTP 503'),
            contains('Uguu: HTTP 503'),
            contains('tmpfiles: link gives a web page'),
          ),
        ),
      ),
    );
  });

  test('shared links are checked before anyone gets them', () async {
    final (link, _) = await FileDrop.upload(
      bytes: [0x49, 0x44, 0x33, 4, 0],
      name: 'song.mp3',
      verify: true,
    );
    expect(link, startsWith('${cloud.base}/files/'));
    await FileDrop.check(link);
    await expectLater(
      FileDrop.check('${cloud.base}/page/x'),
      throwsA(isA<JamError>()),
    );
    expect(FileDrop.looksLikePage(utf8.encode('  <html>'), null), isTrue);
    expect(FileDrop.looksLikePage([0x49, 0x44, 0x33], 'audio/mpeg'), isFalse);
    expect(
      FileDrop.describe(const SocketException('x', address: null)),
      'no connection',
    );
  });

  test('the host can remove a friend', () async {
    const code = 'ABCD-EFGH-JKMN';
    final topic = OnlineJamCode.topic(code);
    final host = OnlineJamHost(
      code: code,
      jamName: 'Jam',
      hostName: 'Kidyoh',
      app: app(),
      relay: NtfyRelay(topic, base: cloud.base),
    );
    await host.start();
    final guest = OnlineJamGuest(
      code,
      relay: NtfyRelay(topic, base: cloud.base),
    );
    await guest.join('Abel');
    await _until(() => host.guests.isNotEmpty);
    host.remove(host.guests.single.id);
    expect(await guest.closed.timeout(const Duration(seconds: 5)), 'removed');
    await host.stop();
  });
}

Future<void> _until(bool Function() ok) async {
  for (var i = 0; i < 50 && !ok(); i++) {
    await Future.delayed(const Duration(milliseconds: 100));
  }
  expect(ok(), isTrue);
}
