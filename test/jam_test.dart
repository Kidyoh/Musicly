import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:musicly/services/jam_guest.dart';
import 'package:musicly/services/jam_host.dart';
import 'package:musicly/services/jam_protocol.dart';

void main() {
  test('join codes round-trip and reject junk', () {
    for (final (ip, port) in [
      ('192.168.1.23', 40213),
      ('10.0.0.5', 1),
      ('255.255.255.255', 65535),
      ('192.168.43.1', 8080),
    ]) {
      final code = JamCode.encode(ip, port);
      expect(code, matches(RegExp(r'^[2-9A-Z]{4}-[2-9A-Z]{4}-[2-9A-Z]{2}$')));
      expect(JamCode.decode(code), (ip, port));
      expect(JamCode.decode(code.toLowerCase().replaceAll('-', ' ')), (
        ip,
        port,
      ));
    }
    expect(JamCode.decode('hello'), isNull);
    expect(JamCode.decode('0000-0000-00'), isNull);
  });

  group('host and guest', () {
    late Directory dir;
    late JamHost host;
    final added = <(String, String, bool)>[];
    final uploads = <(JamUpload, String)>[];
    final controls = <(String, int?)>[];
    const songs = [
      JamTrack(id: 'tg:a', title: 'Blinding Lights', artist: 'The Weeknd'),
      JamTrack(id: 'dev:1', title: 'Lose Yourself', artist: 'Eminem'),
    ];
    var now = const JamTrack(id: 'tg:a', title: 'Blinding Lights', artist: 'W');

    setUp(() async {
      dir = await Directory.systemTemp.createTemp('jam');
      added.clear();
      uploads.clear();
      controls.clear();
      host = JamHost(
        jamName: "Kidyoh's Jam",
        hostName: 'Kidyoh',
        uploadsDir: Directory('${dir.path}/up'),
        state: () => JamState(
          now: now,
          playing: true,
          positionMs: 1000,
          at: DateTime.now().millisecondsSinceEpoch,
        ),
        onAdd: (id, by, next) async => added.add((id, by.name, next)),
        onUpload: (song, by, next) async => uploads.add((song, by.name)),
        onControl: (a, ms, by) => controls.add((a, ms)),
        search: (q) => songs
            .where((t) => t.title.toLowerCase().contains(q.toLowerCase()))
            .toList(),
        art: (id) async => id == 'tg:a' ? [1, 2, 3] : null,
      );
      await host.start(beacon: false);
    });

    tearDown(() async {
      await host.stop();
      try {
        await dir.delete(recursive: true);
      } catch (_) {}
    });

    test('joins, sees the Jam, searches and adds', () async {
      final g = JamGuest('127.0.0.1', host.port);
      final firstState = g.states.first;
      await g.join('Abel');
      expect(g.jamName, "Kidyoh's Jam");
      expect(g.hostName, 'Kidyoh');
      final s = await firstState;
      expect(s.now?.title, 'Blinding Lights');
      expect(s.people, ['Kidyoh', 'Abel']);
      expect(g.position.inMilliseconds, greaterThanOrEqualTo(1000));

      final found = await g.search('lose');
      expect(found.map((t) => t.id), ['dev:1']);

      g.add('dev:1', next: true);
      await Future.delayed(const Duration(milliseconds: 150));
      expect(added, [('dev:1', 'Abel', true)]);

      // Playback control is off until the host allows it.
      g.control('next');
      await Future.delayed(const Duration(milliseconds: 150));
      expect(controls, isEmpty);
      host.guestsControl = true;
      g.control('seek', 5000);
      await Future.delayed(const Duration(milliseconds: 150));
      expect(controls, [('seek', 5000)]);
      await g.leave();
    });

    test('a guest sends a song from their phone', () async {
      final g = JamGuest('127.0.0.1', host.port);
      await g.join('Abel');
      final song = File('${dir.path}/song.mp3')
        ..writeAsBytesSync(List.generate(300000, (i) => i % 256));
      var progress = 0.0;
      await g.upload(
        audio: song,
        ext: 'mp3',
        title: 'My Song',
        artist: 'Me',
        art: [9, 9, 9],
        durationMs: 180000,
        onProgress: (p) => progress = p,
      );
      await Future.delayed(const Duration(milliseconds: 200));
      expect(progress, 1.0);
      expect(uploads, hasLength(1));
      final (up, by) = uploads.single;
      expect(by, 'Abel');
      expect(up.title, 'My Song');
      expect(up.durationMs, 180000);
      expect(File(up.path).lengthSync(), 300000);
      expect(File(up.artPath!).readAsBytesSync(), [9, 9, 9]);
      await g.leave();
    });

    test('art, people updates, and the host ending the Jam', () async {
      final res = await (await HttpClient().getUrl(
        Uri.parse(
          'http://127.0.0.1:${host.port}/art/${Uri.encodeComponent('tg:a')}',
        ),
      )).close();
      expect(await res.fold<List<int>>([], (a, b) => a..addAll(b)), [1, 2, 3]);

      final a = JamGuest('127.0.0.1', host.port);
      await a.join('Abel');
      final b = JamGuest('127.0.0.1', host.port);
      final sawBoth = a.states.firstWhere((s) => s.people.length == 3);
      await b.join('Sara');
      expect((await sawBoth).people, ['Kidyoh', 'Abel', 'Sara']);

      now = const JamTrack(id: 'dev:1', title: 'Lose Yourself', artist: 'E');
      final changed = b.states.firstWhere((s) => s.now?.id == 'dev:1');
      host.pushState();
      expect((await changed).now?.title, 'Lose Yourself');

      await host.stop();
      expect(await a.closed.timeout(const Duration(seconds: 3)), 'ended');
      expect(await b.closed.timeout(const Duration(seconds: 3)), 'ended');
    });

    test('uploads from strangers are refused', () async {
      final req = await HttpClient().postUrl(
        Uri.parse('http://127.0.0.1:${host.port}/upload?g=nobody&id=x'),
      );
      req.add([1, 2, 3]);
      final res = await req.close();
      expect(res.statusCode, 403);
    });
  });
}
