import '../models/track.dart';
import 'json_fetch.dart';

/// Online World Radio API - free alternative radio directory
/// https://onlineworldradio.com/api/v1
class OnlineWorldRadioApi {
  static const _baseUrl = 'https://onlineworldradio.com/api/v1';

  Future<List<Track>> search({
    String? name,
    String? country,
    String? language,
    String? category,
    int limit = 40,
  }) async {
    try {
      final query = <String, String>{
        'limit': limit.toString(),
        if (name != null) 'q': name,
        if (country != null) 'country': country,
        if (language != null) 'language': language,
        if (category != null) 'category': category,
      };
      final uri = Uri.parse('$_baseUrl/stations/search').replace(queryParameters: query);
      final res = await fetchJson(uri).timeout(const Duration(seconds: 10));
      final data = (res as Map?)?['data'] as List?;
      if (data == null || data.isEmpty) return [];

      return data
          .where((m) => (m['stream_url'] as String?)?.startsWith('https://') ?? false)
          .map((m) => Track(
                id: 'owr:${m['id']}',
                title: (m['title'] as String? ?? 'Radio').trim(),
                artist: [
                  if ((m['category'] as String?)?.isNotEmpty == true) m['category'] as String,
                  if ((m['language'] as String?)?.isNotEmpty == true) m['language'] as String,
                  if ((m['country'] as String?)?.isNotEmpty == true) m['country'] as String,
                ].join(' · '),
                source: TrackSource.radio,
                uri: m['stream_url'] as String,
                artworkUrl: (m['favicon'] as String?)?.startsWith('https://') == true
                    ? m['favicon'] as String
                    : null,
                link: (m['website'] as String?)?.startsWith('http') == true
                    ? m['website'] as String
                    : null,
              ))
          .toList();
    } catch (_) {
      return [];
    }
  }

  Future<List<Track>> top({int limit = 40}) async =>
      search(limit: limit);
}