import 'package:http/http.dart' as http;
import 'package:xml/xml.dart';

import '../models/rss_feed.dart';
import '../models/track.dart';

/// A feed with its songs/episodes.
class RssResult {
  const RssResult(this.feed, this.tracks);
  final RssFeed feed;
  final List<Track> tracks;
}

/// Reads RSS feeds whose items carry an audio file (`<enclosure>`), such as
/// podcasts and music or mix feeds, so they can be streamed.
class RssApi {
  static const _audioExt = [
    '.mp3',
    '.m4a',
    '.aac',
    '.ogg',
    '.opus',
    '.wav',
    '.flac',
  ];

  /// Accepts "example.com/feed", "feed://…" and "https://…".
  static String normalizeUrl(String input) {
    var u = input.trim();
    final lower = u.toLowerCase();
    if (lower.startsWith('feed://') || lower.startsWith('itpc://')) {
      u = 'https://${u.substring(7)}';
    } else if (!lower.startsWith('http://') && !lower.startsWith('https://')) {
      u = 'https://$u';
    }
    return u;
  }

  Future<RssResult> load(String input, {http.Client? client}) async {
    final url = normalizeUrl(input);
    final Uri uri;
    try {
      uri = Uri.parse(url);
      if (uri.host.isEmpty) throw const FormatException();
    } catch (_) {
      throw Exception('That does not look like a web address.');
    }
    final c = client ?? http.Client();
    final http.Response res;
    try {
      res = await c.get(uri).timeout(const Duration(seconds: 20));
    } catch (_) {
      throw Exception(
        'Could not reach that feed. Check the link and your connection.',
      );
    } finally {
      if (client == null) c.close();
    }
    if (res.statusCode != 200) {
      throw Exception('The feed answered with error ${res.statusCode}.');
    }
    return parse(res.body, url);
  }

  /// Parses RSS 2.0 text. Throws a readable error when it is not a feed or
  /// has nothing playable.
  static RssResult parse(String body, String url) {
    final XmlDocument doc;
    try {
      doc = XmlDocument.parse(body.trimLeft());
    } catch (_) {
      throw Exception('That link is not an RSS feed.');
    }
    final channel = doc.findAllElements('channel').firstOrNull;
    if (channel == null) throw Exception('That link is not an RSS feed.');

    final title = _text(channel, 'title') ?? Uri.parse(url).host;
    final author = _text(channel, 'author') ?? title;
    final image =
        _attr(channel, 'image', 'href') ??
        _text(_child(channel, 'image'), 'url');
    final feedImage = _https(image);

    final tracks = <Track>[];
    final seen = <String>{};
    for (final item in channel.childElements.where(
      (e) => e.name.local == 'item',
    )) {
      final audio = _audioUrl(item);
      if (audio == null || !seen.add(audio)) continue;
      tracks.add(
        Track(
          id: 'rss:$audio',
          title: _text(item, 'title') ?? 'Untitled',
          artist: _text(item, 'author') ?? author,
          album: title,
          source: TrackSource.feed,
          uri: audio,
          artworkUrl: _https(_attr(item, 'image', 'href')) ?? feedImage,
          duration: _duration(_text(item, 'duration')),
          link: _https(_text(item, 'link')),
        ),
      );
    }
    if (tracks.isEmpty) {
      throw Exception('This feed has no audio to play.');
    }
    return RssResult(
      RssFeed(url: url, title: title, imageUrl: feedImage),
      tracks,
    );
  }

  static XmlElement? _child(XmlElement? e, String local) =>
      e?.childElements.where((c) => c.name.local == local).firstOrNull;

  static String? _text(XmlElement? e, String local) {
    final t = _child(e, local)?.innerText.trim();
    return (t == null || t.isEmpty) ? null : t;
  }

  static String? _attr(XmlElement e, String local, String attr) {
    final v = _child(e, local)?.getAttribute(attr)?.trim();
    return (v == null || v.isEmpty) ? null : v;
  }

  /// Phones block plain-http audio by default, so prefer https links.
  static String? _https(String? u) {
    if (u == null || u.isEmpty) return null;
    final host = Uri.tryParse(u)?.host;
    if (host == 'localhost' || host == '127.0.0.1') return u;
    if (u.startsWith('http://')) return 'https://${u.substring(7)}';
    return u.startsWith('http') ? u : null;
  }

  static String? _audioUrl(XmlElement item) {
    for (final e in item.childElements) {
      if (e.name.local != 'enclosure' && e.name.local != 'content') continue;
      final url = e.getAttribute('url')?.trim();
      if (url == null || url.isEmpty) continue;
      final type = (e.getAttribute('type') ?? '').toLowerCase();
      final path = (Uri.tryParse(url)?.path ?? url).toLowerCase();
      if (type.startsWith('audio/') ||
          type == 'application/octet-stream' && _audioExt.any(path.endsWith) ||
          type.isEmpty && _audioExt.any(path.endsWith)) {
        return _https(url);
      }
    }
    return null;
  }

  /// "1:02:03", "12:30" or "754" (seconds).
  static Duration? _duration(String? s) {
    if (s == null) return null;
    final parts = s.split(':').map((p) => double.tryParse(p.trim())).toList();
    if (parts.isEmpty || parts.any((p) => p == null) || parts.length > 3) {
      return null;
    }
    var secs = 0.0;
    for (final p in parts) {
      secs = secs * 60 + p!;
    }
    return secs <= 0 ? null : Duration(milliseconds: (secs * 1000).round());
  }
}
