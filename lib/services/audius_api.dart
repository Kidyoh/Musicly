import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/track.dart';

/// Free, key-less streaming catalogue (https://audius.co).
class AudiusApi {
  static const _host = 'https://api.audius.co/v1';
  static const _app = 'app_name=Musicly';

  Future<List<Track>> trending({int limit = 30}) =>
      _fetch('$_host/tracks/trending?$_app&limit=$limit');

  Future<List<Track>> search(String query, {int limit = 30}) => _fetch(
      '$_host/tracks/search?$_app&limit=$limit&query=${Uri.encodeQueryComponent(query)}');

  Future<List<Track>> _fetch(String url) async {
    final res =
        await http.get(Uri.parse(url)).timeout(const Duration(seconds: 15));
    if (res.statusCode != 200) {
      throw Exception('Streaming service returned ${res.statusCode}');
    }
    final data = (jsonDecode(res.body)['data'] as List).cast<Map>();
    return data.map((m) {
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
}
