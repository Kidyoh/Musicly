import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

class LyricLine {
  const LyricLine(this.time, this.text);
  final Duration time;
  final String text;
}

class Lyrics {
  Lyrics({
    required this.id,
    required this.trackName,
    required this.artistName,
    this.duration,
    this.synced = const [],
    this.plain,
    this.instrumental = false,
  });

  final int id;
  final String trackName;
  final String artistName;
  final Duration? duration;
  final List<LyricLine> synced;
  final String? plain;
  final bool instrumental;

  bool get isSynced => synced.isNotEmpty;

  static final _tag = RegExp(r'\[(\d+):(\d+(?:\.\d+)?)\]');

  /// Parses LRC text. Lines may carry several timestamps.
  static List<LyricLine> parseLrc(String lrc) {
    final out = <LyricLine>[];
    for (final raw in const LineSplitter().convert(lrc)) {
      final tags = _tag.allMatches(raw).toList();
      if (tags.isEmpty) continue;
      final text = raw.substring(tags.last.end).trim();
      for (final t in tags) {
        final ms =
            (int.parse(t.group(1)!) * 60000 + double.parse(t.group(2)!) * 1000)
                .round();
        out.add(LyricLine(Duration(milliseconds: ms), text));
      }
    }
    out.sort((a, b) => a.time.compareTo(b.time));
    return out;
  }

  factory Lyrics.fromJson(Map j) => Lyrics(
    id: j['id'] as int,
    trackName: (j['trackName'] ?? '') as String,
    artistName: (j['artistName'] ?? '') as String,
    duration: j['duration'] == null
        ? null
        : Duration(milliseconds: ((j['duration'] as num) * 1000).round()),
    synced: j['syncedLyrics'] == null
        ? const []
        : parseLrc(j['syncedLyrics'] as String),
    plain: j['plainLyrics'] as String?,
    instrumental: j['instrumental'] == true,
  );
}

/// Free synced-lyrics database (https://lrclib.net).
class LyricsApi {
  static const _host = 'https://lrclib.net/api';
  static const _headers = {
    'User-Agent': 'Musicly/1.0 (https://github.com/kidyoh/musicly)',
  };

  Future<List<Lyrics>> search(String query) async {
    final res = await http
        .get(
          Uri.parse('$_host/search?q=${Uri.encodeQueryComponent(query)}'),
          headers: kIsWeb ? null : _headers,
        )
        .timeout(const Duration(seconds: 15));
    if (res.statusCode != 200) throw Exception('Lyrics ${res.statusCode}');
    return (jsonDecode(res.body) as List)
        .cast<Map>()
        .map(Lyrics.fromJson)
        .toList();
  }

  Future<Lyrics?> byId(int id) async {
    final res = await http
        .get(Uri.parse('$_host/get/$id'), headers: kIsWeb ? null : _headers)
        .timeout(const Duration(seconds: 15));
    if (res.statusCode != 200) return null;
    return Lyrics.fromJson(jsonDecode(res.body) as Map);
  }

  /// Best guess for a track: prefers synced lyrics with a close duration.
  Future<Lyrics?> find(String title, String artist, Duration? duration) async {
    final q = artist.isEmpty ? title : '$title $artist';
    var results = await search(q);
    if (results.isEmpty && artist.isNotEmpty) results = await search(title);
    if (results.isEmpty) return null;
    int score(Lyrics l) {
      var s = 0;
      if (l.isSynced) s += 10;
      if (duration != null && l.duration != null) {
        final diff = (l.duration!.inSeconds - duration.inSeconds).abs();
        if (diff <= 3) s += 6;
        if (diff > 20) s -= 6;
      }
      if (l.artistName.toLowerCase() == artist.toLowerCase()) s += 4;
      return s;
    }

    // Only trust a match whose artist or length lines up; otherwise let the
    // user pick from search rather than showing the wrong song's words.
    bool plausible(Lyrics l) {
      final a = artist.toLowerCase(), la = l.artistName.toLowerCase();
      final artistOk = a.isNotEmpty && (la.contains(a) || a.contains(la));
      final durOk =
          duration != null &&
          duration > Duration.zero &&
          l.duration != null &&
          (l.duration!.inSeconds - duration.inSeconds).abs() <= 4;
      final titleOk =
          l.trackName.toLowerCase().contains(title.toLowerCase()) ||
          title.toLowerCase().contains(l.trackName.toLowerCase());
      return titleOk && (artistOk || durOk || (a.isEmpty && duration == null));
    }

    results = results.where(plausible).toList();
    if (results.isEmpty) return null;
    results.sort((a, b) => score(b).compareTo(score(a)));
    return results.first;
  }
}
