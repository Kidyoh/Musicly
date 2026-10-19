import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:musicly/models/track.dart';
import 'package:musicly/services/lyrics_api.dart';
import 'package:musicly/services/telegram_backup.dart';
import 'package:musicly/state/library_controller.dart';
import 'package:musicly/state/lyrics_controller.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _FakePlayer extends ChangeNotifier {
  Track? current;
  void play(Track t) {
    current = t;
    notifyListeners();
  }
}

class _Settings extends ChangeNotifier {
  bool online = false;
  void set(bool on) {
    online = on;
    notifyListeners();
  }
}

/// Records every request instead of calling LRCLIB.
class _FakeApi extends LyricsApi {
  final calls = <String>[];

  @override
  Future<List<Lyrics>> search(String query) async {
    calls.add('search $query');
    return [];
  }

  @override
  Future<Lyrics?> byId(int id) async {
    calls.add('byId $id');
    return null;
  }

  @override
  Future<Lyrics?> find(String title, String artist, Duration? duration) async {
    calls.add('find $title $artist');
    return Lyrics(id: 1, trackName: title, artistName: artist, plain: 'la');
  }
}

Track _song(String id) => Track(
  id: id,
  title: 'Song $id',
  artist: 'Tems',
  source: TrackSource.telegram,
  uri: 'file-$id',
);

Future<void> _settle() =>
    Future<void>.delayed(const Duration(milliseconds: 20));

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() => SharedPreferences.setMockInitialValues({}));

  test('no lyrics lookup while "Find lyrics online" is off', () async {
    final player = _FakePlayer(), settings = _Settings(), api = _FakeApi();
    final c = LyricsController.custom(
      player,
      () => player.current,
      online: () => settings.online,
      settings: settings,
      api: api,
    );

    player.play(_song('tg:1'));
    await _settle();
    player.play(_song('tg:2'));
    await _settle();
    expect(api.calls, isEmpty);
    expect(c.status, LyricsStatus.off);

    // The user asks once for this song.
    await c.lookUp();
    expect(api.calls, ['find Song tg:2 Tems']);
    expect(c.status, LyricsStatus.found);

    // The next song is not looked up on its own.
    player.play(_song('tg:3'));
    await _settle();
    expect(api.calls, hasLength(1));
    expect(c.status, LyricsStatus.off);

    // Turning the setting on fetches lyrics for the song playing now.
    settings.set(true);
    await _settle();
    expect(api.calls.last, 'find Song tg:3 Tems');
    expect(c.status, LyricsStatus.found);
    c.dispose();
  });

  test('waits for saved settings before looking anything up', () async {
    final player = _FakePlayer()..current = _song('tg:1');
    final api = _FakeApi();
    var online = true; // the default, before saved settings load
    final c = LyricsController.custom(
      player,
      () => player.current,
      online: () => online,
      ready: Future<void>.delayed(
        const Duration(milliseconds: 5),
        () => online = false,
      ),
      api: api,
    );
    await _settle();
    expect(api.calls, isEmpty);
    expect(c.status, LyricsStatus.off);
    c.dispose();
  });

  test('the setting is saved, restored and backed up', () async {
    final lib = LibraryController();
    await lib.ready;
    expect(lib.onlineLyrics, isTrue);
    lib.setOnlineLyrics(false);
    await _settle();

    final snap = await TelegramBackup.snapshot();
    expect((snap['prefs'] as Map)['onlineLyrics'], isFalse);

    final again = LibraryController();
    await again.ready;
    expect(again.onlineLyrics, isFalse);

    // Restoring that backup on a fresh install brings the choice back.
    SharedPreferences.setMockInitialValues({});
    await TelegramBackup.apply(snap.cast<String, dynamic>());
    final restored = LibraryController();
    await restored.ready;
    expect(restored.onlineLyrics, isFalse);
  });
}
