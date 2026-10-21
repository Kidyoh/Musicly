import 'package:flutter_test/flutter_test.dart';
import 'package:musicly/models/track.dart';
import 'package:musicly/services/youtube_api.dart';

void main() {
  group('tidy', () {
    test('splits "Artist - Song" and drops video decorations', () {
      final (artist, title, own) = YouTubeApi.tidy(
        'Burna Boy - Last Last [Official Music Video]',
        'Burna Boy',
      );
      expect(artist, 'Burna Boy');
      expect(title, 'Last Last');
      expect(own, isTrue);
    });

    test('lyric channels are not the artist', () {
      final (artist, title, own) = YouTubeApi.tidy(
        'Burna Boy - Last Last (Lyrics)',
        'Vibe Music',
      );
      expect(artist, 'Burna Boy');
      expect(title, 'Last Last');
      expect(own, isFalse);
    });

    test('Topic and VEVO channels are named after the artist', () {
      expect(YouTubeApi.tidy('Last Last', 'Burna Boy - Topic').$1, 'Burna Boy');
      final (artist, title, own) = YouTubeApi.tidy(
        'Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)',
        'RickAstleyVEVO',
      );
      expect(artist, 'Rick Astley');
      expect(title, 'Never Gonna Give You Up');
      expect(own, isTrue);
    });

    test('keeps features and trims trailers after a bar', () {
      final (artist, title, _) = YouTubeApi.tidy(
        'Tems - Love Me JeJe (feat. Someone) | Official Audio',
        'Tems',
      );
      expect(artist, 'Tems');
      expect(title, 'Love Me JeJe (feat. Someone)');
    });
  });

  test('bot-check errors are readable', () {
    expect(
      const YouTubeBlocked().toString(),
      contains('bot'),
    );
  });

  test('YouTube tracks survive a save and restore', () {
    final t = YouTubeApi.trackFrom(
      'dQw4w9WgXcQ',
      'Rick Astley - Never Gonna Give You Up',
      'Rick Astley',
      'UCuAXFkgsw1L7xaCfnd5JJOw',
      const Duration(minutes: 3, seconds: 33),
    );
    final back = Track.fromJson(t.toJson());
    expect(back.isYouTube, isTrue);
    expect(back.uri, 'dQw4w9WgXcQ');
    expect(back.artistId, 'UCuAXFkgsw1L7xaCfnd5JJOw');
    expect(back.isPersistable, isTrue);
  });
}
