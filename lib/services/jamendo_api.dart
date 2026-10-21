import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/track.dart';
import '../state/library_controller.dart';

/// Jamendo API - Creative Commons music.
/// Requires free client_id from https://devportal.jamendo.com/
/// Docs: https://devportal.jamendo.com/v3.0
class JamendoApi {
  JamendoApi._();
  static final instance = JamendoApi._();

  static const _baseUrl = 'https://api.jamendo.com/v3.0';
  final _http = http.Client();

  String? get clientId => LibraryController.instance?.jamendoClientId;

  bool get configured => clientId != null && clientId!.isNotEmpty;

  Future<List<Track>> search(String query, {int limit = 20}) async {
    if (!configured) return [];

    try {
      final uri = Uri.parse('$_baseUrl/tracks/')
          .replace(queryParameters: {
        'client_id': clientId,
        'format': 'json',
        'search': query,
        'limit': limit.toString(),
        'include': 'musicinfo',
        'audioformat': 'mp32',
        'order': 'popularity_total',
      });
      final res = await _http.get(uri, headers: {'Accept': 'application/json'})
          .timeout(const Duration(seconds: 10));
      if (res.statusCode != 200) return [];

      final data = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
      final tracks = (data['results'] as List?) ?? [];

      return tracks.map((t) => _toTrack(t)).whereType<Track>().toList();
    } catch (_) {
      return [];
    }
  }

  Future<Uri?> getStreamUrl(String trackId) async {
    if (!configured) return null;

    try {
      final uri = Uri.parse('$_baseUrl/tracks/')
          .replace(queryParameters: {
        'client_id': clientId,
        'format': 'json',
        'ids': trackId,
        'include': 'musicinfo',
        'audioformat': 'mp32',
      });
      final res = await _http.get(uri, headers: {'Accept': 'application/json'})
          .timeout(const Duration(seconds: 10));
      if (res.statusCode != 200) return null;

      final data = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
      final results = (data['results'] as List?) ?? [];
      if (results.isEmpty) return null;

      final track = results.first;
      final audio = track['audiodownload'] as String? ?? track['audio'] as String?;
      return audio != null ? Uri.parse(audio) : null;
    } catch (_) {
      return null;
    }
  }

  Track? _toTrack(Map<String, dynamic> t) {
    final id = t['id'] as int?;
    final title = t['name'] as String?;
    final artist = t['artist_name'] as String?;
    final durationSec = t['duration'] as int?;
    final image = t['image'] as String?;
    final audio = t['audio'] as String? ?? t['audiodownload'] as String?;
    final album = t['album_name'] as String?;
    final license = t['license_ccurl'] as String?;

    if (id == null || title == null || artist == null) return null;

    return Track(
      id: 'jamendo:$id',
      title: title,
      artist: artist,
      source: TrackSource.jamendo,
      uri: id.toString(),
      artworkUrl: image,
      duration: durationSec != null ? Duration(seconds: durationSec) : null,
      album: album,
      link: 'https://www.jamendo.com/track/$id',
    );
  }
}