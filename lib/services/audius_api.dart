import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/collection.dart';
import '../models/track.dart';

/// Free, key-less streaming catalogue (https://audius.co).
class AudiusApi {
  static const _host = 'https://api.audius.co/v1';
  static const _app = 'app_name=Musicly';

  static const genres = [
    'Electronic', 'Hip-Hop/Rap', 'Pop', 'Alternative', 'R&B/Soul',
    'Lo-Fi', 'House', 'Ambient', 'Rock', 'Jazz', 'Acoustic', 'Latin',
  ];

  /// [time] is one of `week`, `month`, `year`, `allTime`.
  Future<List<Track>> trending({int limit = 30, String? genre, String time = 'week'}) {
    final g = genre == null ? '' : '&genre=${Uri.encodeQueryComponent(genre)}';
    return _tracks('$_host/tracks/trending?$_app&limit=$limit&time=$time$g');
  }

  Future<List<Track>> search(String query, {int limit = 30}) => _tracks(
      '$_host/tracks/search?$_app&limit=$limit&query=${Uri.encodeQueryComponent(query)}');

  Future<List<Collection>> trendingPlaylists({int limit = 12}) =>
      _collections('$_host/playlists/trending?$_app&limit=$limit');

  Future<List<Collection>> searchPlaylists(String query, {int limit = 10}) =>
      _collections(
          '$_host/playlists/search?$_app&limit=$limit&query=${Uri.encodeQueryComponent(query)}');

  Future<List<Track>> playlistTracks(String id) =>
      _tracks('$_host/playlists/$id/tracks?$_app');

  Future<List<Map>> _get(String url) async {
    http.Response res;
    try {
      res = await http.get(Uri.parse(url)).timeout(const Duration(seconds: 12));
    } catch (_) {
      // One retry: Audius routes through many community nodes and the
      // occasional request is dropped.
      res = await http.get(Uri.parse(url)).timeout(const Duration(seconds: 12));
    }
    if (res.statusCode != 200) {
      throw Exception('Streaming service returned ${res.statusCode}');
    }
    return (jsonDecode(res.body)['data'] as List).cast<Map>();
  }

  Future<List<Track>> _tracks(String url) async {
    final data = await _get(url);
    return data.where((m) => m['is_streamable'] != false).map((m) {
      final art = m['artwork'] as Map?;
      return Track(
        id: 'audius:${m['id']}',
        title: (m['title'] ?? 'Unknown') as String,
        artist: ((m['user'] as Map?)?['name'] ?? 'Unknown artist') as String,
        source: TrackSource.online,
        uri: '$_host/tracks/${m['id']}/stream?$_app',
        artworkUrl: (art?['480x480'] ?? art?['150x150']) as String?,
        duration: Duration(seconds: (m['duration'] ?? 0) as int),
      );
    }).toList();
  }

  Future<List<Collection>> _collections(String url) async {
    final data = await _get(url);
    return data.map((m) {
      final art = m['artwork'] as Map?;
      final created = DateTime.tryParse((m['release_date'] ?? m['created_at'] ?? '') as String);
      return Collection(
        id: m['id'] as String,
        title: (m['playlist_name'] ?? 'Untitled') as String,
        owner: ((m['user'] as Map?)?['name'] ?? 'Unknown') as String,
        artworkUrl: (art?['480x480'] ?? art?['150x150']) as String?,
        trackCount: m['track_count'] as int?,
        year: created?.year,
        isAlbum: m['is_album'] == true,
      );
    }).toList();
  }
}
