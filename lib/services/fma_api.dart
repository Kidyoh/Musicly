import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import '../models/track.dart';

/// Free Music Archive API - curated Creative Commons music.
/// No API key required for basic search.
/// Docs: https://freemusicarchive.org/api
class FmaApi {
  FmaApi._();
  static final instance = FmaApi._();

  static const _baseUrl = 'https://freemusicarchive.org/api';
  final _http = http.Client();

  Future<List<Track>> search(String query, {int limit = 20}) async {
    try {
      final uri = Uri.parse('$_baseUrl/track/')
          .replace(queryParameters: {
        'api_key': '60BLHNQCAOUFPIBZ', // Public demo key
        'format': 'json',
        'limit': limit.toString(),
        'q': query,
      });
      final res = await _http.get(uri, headers: {'Accept': 'application/json'})
          .timeout(const Duration(seconds: 15));
      if (res.statusCode != 200) return [];

      final data = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
      final dataset = data['dataset'] as List?;

      if (dataset == null) return [];

      return dataset
          .map((t) => _toTrack(t))
          .whereType<Track>()
          .toList();
    } catch (_) {
      return [];
    }
  }

  Future<Uri?> getStreamUrl(String trackId) async {
    try {
      final uri = Uri.parse('$_baseUrl/track/')
          .replace(queryParameters: {
        'api_key': '60BLHNQCAOUFPIBZ',
        'format': 'json',
        'id': trackId,
      });
      final res = await _http.get(uri, headers: {'Accept': 'application/json'})
          .timeout(const Duration(seconds: 10));
      if (res.statusCode != 200) return null;

      final data = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
      final dataset = data['dataset'] as List?;
      if (dataset == null || dataset.isEmpty) return null;

      final track = dataset.first;
      final url = track['track_file'] as String? ?? track['track_url'] as String?;
      return url != null ? Uri.parse(url) : null;
    } catch (_) {
      return null;
    }
  }

  Track? _toTrack(Map<String, dynamic> t) {
    final id = t['track_id'] as int?;
    final title = t['track_title'] as String?;
    final artist = t['artist_name'] as String?;
    final durationSec = t['track_duration'] as int?;
    final image = t['track_image_file'] as String?;
    final album = t['album_title'] as String?;
    final genre = t['track_genre_title'] as String?;
    final url = t['track_url'] as String?;

    if (id == null || title == null || artist == null) return null;

    return Track(
      id: 'fma:$id',
      title: title,
      artist: artist,
      source: TrackSource.fma,
      uri: id.toString(),
      artworkUrl: image,
      duration: durationSec != null ? Duration(seconds: durationSec) : null,
      album: album,
      genre: genre,
      link: url,
    );
  }
}