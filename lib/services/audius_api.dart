import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/track.dart';

/// Audius API - decentralized music streaming, no API key for read-only.
/// Docs: https://docs.audius.co/api
class AudiusApi {
  AudiusApi._();
  static final instance = AudiusApi._();

  static const _baseUrl = 'https://api.audius.co/v1';
  final _http = http.Client();

  Future<List<Track>> search(String query, {int limit = 20}) async {
    try {
      final uri = Uri.parse('$_baseUrl/tracks/search')
          .replace(queryParameters: {'query': query, 'limit': limit.toString()});
      final res = await _http.get(uri, headers: {'Accept': 'application/json'})
          .timeout(const Duration(seconds: 10));
      if (res.statusCode != 200) return [];

      final data = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
      final tracks = (data['data'] as List?) ?? [];

      return tracks
          .where((t) => t['is_streamable'] == true && t['is_stream_gated'] != true)
          .map((t) => _toTrack(t))
          .whereType<Track>()
          .take(limit)
          .toList();
    } catch (_) {
      return [];
    }
  }

  Future<Track?> getTrack(String trackId) async {
    try {
      final uri = Uri.parse('$_baseUrl/tracks/$trackId');
      final res = await _http.get(uri, headers: {'Accept': 'application/json'})
          .timeout(const Duration(seconds: 10));
      if (res.statusCode != 200) return null;

      final data = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
      final track = data['data'] as Map<String, dynamic>?;
      if (track == null) return null;
      return _toTrack(track);
    } catch (_) {
      return null;
    }
  }

  Future<Uri?> getStreamUrl(String trackId) async {
    try {
      final uri = Uri.parse('$_baseUrl/tracks/$trackId/stream');
      final res = await _http.get(uri, headers: {'Accept': 'audio/mpeg'})
          .timeout(const Duration(seconds: 10));
      if (res.statusCode == 200) return uri;
      if (res.statusCode == 302) {
        final location = res.headers['location'];
        if (location != null) return Uri.parse(location);
      }
      return null;
    } catch (_) {
      return null;
    }
  }

  Track? _toTrack(Map<String, dynamic> t) {
    final id = t['id'] as String?;
    final title = t['title'] as String?;
    final user = t['user'] as Map<String, dynamic>?;
    final artist = user?['name'] as String? ?? user?['handle'] as String? ?? 'Unknown artist';
    final durationSec = t['duration'] as int?;
    final artwork = t['artwork'] as Map<String, dynamic>?;
    final artworkUrl = artwork?['480x480'] as String? ?? artwork?['150x150'] as String?;
    final streamUrl = t['stream']?['url'] as String?;

    if (id == null || title == null) return null;

    return Track(
      id: 'audius:$id',
      title: title,
      artist: artist,
      source: TrackSource.audius,
      uri: id,
      artworkUrl: artworkUrl,
      duration: durationSec != null ? Duration(seconds: durationSec) : null,
      link: 'https://audius.co/${user?['handle']}/$id',
    );
  }
}