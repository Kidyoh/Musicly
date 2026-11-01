import 'package:flutter_test/flutter_test.dart';
import 'package:musicly/models/track.dart';
import 'package:musicly/services/rss_api.dart';

const _feed = '''<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
  <channel>
    <title>Night Mixes</title>
    <itunes:author>DJ Example</itunes:author>
    <itunes:image href="http://img.example.com/show.jpg"/>
    <item>
      <title>Mix 1</title>
      <enclosure url="http://cdn.example.com/mix1.mp3" type="audio/mpeg" length="1"/>
      <itunes:duration>1:02:03</itunes:duration>
      <itunes:image href="https://img.example.com/mix1.jpg"/>
    </item>
    <item>
      <title>Mix 2</title>
      <itunes:author>Guest</itunes:author>
      <enclosure url="https://cdn.example.com/mix2.m4a?x=1" type="application/octet-stream"/>
      <itunes:duration>754</itunes:duration>
    </item>
    <item><title>Video</title><enclosure url="https://cdn.example.com/v.mp4" type="video/mp4"/></item>
    <item><title>Dupe</title><enclosure url="https://cdn.example.com/mix2.m4a?x=1" type="audio/mp4"/></item>
    <item><title>No file</title></item>
  </channel>
</rss>''';

void main() {
  test('reads audio items from an RSS feed', () {
    final r = RssApi.parse(_feed, 'https://example.com/feed.xml');
    expect(r.feed.title, 'Night Mixes');
    expect(r.feed.imageUrl, 'https://img.example.com/show.jpg');
    expect(r.tracks.map((t) => t.title), ['Mix 1', 'Mix 2']);
    final a = r.tracks[0];
    expect(a.uri, 'https://cdn.example.com/mix1.mp3');
    expect(a.source, TrackSource.feed);
    expect(a.artist, 'DJ Example');
    expect(a.album, 'Night Mixes');
    expect(a.duration, const Duration(hours: 1, minutes: 2, seconds: 3));
    expect(a.artworkUrl, 'https://img.example.com/mix1.jpg');
    final b = r.tracks[1];
    expect(b.artist, 'Guest');
    expect(b.duration, const Duration(seconds: 754));
    expect(b.artworkUrl, 'https://img.example.com/show.jpg');
  });

  test('tracks survive being saved', () {
    final t = RssApi.parse(_feed, 'u').tracks.first;
    final back = Track.fromJson(t.toJson());
    expect(back.id, t.id);
    expect(back.source, TrackSource.feed);
  });

  test('says what is wrong with a bad link', () {
    expect(() => RssApi.parse('<html></html>', 'u'), throwsA(isA<Exception>()));
    expect(() => RssApi.parse('not xml', 'u'), throwsA(isA<Exception>()));
    expect(
      () => RssApi.parse('<rss><channel><title>x</title></channel></rss>', 'u'),
      throwsA(predicate((e) => '$e'.contains('no audio'))),
    );
  });

  test('tidies pasted links', () {
    expect(RssApi.normalizeUrl(' feed://a.com/x '), 'https://a.com/x');
    expect(RssApi.normalizeUrl('a.com/x'), 'https://a.com/x');
    expect(RssApi.normalizeUrl('Https://a.com/X'), 'Https://a.com/X');
  });
}
