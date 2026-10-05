import '../models/track.dart';
import 'json_fetch.dart';

/// Audius: independent artists who publish full-length songs for free.
/// No key needed (https://audius.co).
class AudiusApi {
  static const _host = 'https://api.audius.co/v1';
  static const _app = 'app_name=Musicly';

  Future<List<Track>> trending({
    int limit = 30,
    String time = 'week',
    String? genre,
  }) {
    final g = genre == null ? '' : '&genre=${Uri.encodeQueryComponent(genre)}';
    return _tracks('$_host/tracks/trending?$_app&limit=$limit&time=$time$g');
  }

  Future<List<Track>> search(String q, {int limit = 15}) => _tracks(
    '$_host/tracks/search?$_app&limit=$limit&query=${Uri.encodeQueryComponent(q)}',
  );

  Future<List<Track>> _tracks(String url) async {
    dynamic body;
    for (var attempt = 0; ; attempt++) {
      try {
        body = await fetchJson(Uri.parse(url));
        break;
      } catch (_) {
        if (attempt >= 2) rethrow;
        await Future.delayed(Duration(milliseconds: 300 * (attempt + 1)));
      }
    }
    final data = ((body as Map)['data'] as List? ?? const []).cast<Map>();
    return data
        .where(
          (m) => m['is_streamable'] != false && m['is_stream_gated'] != true,
        )
        .map((m) {
          final art = m['artwork'] as Map?;
          return Track(
            id: 'au:${m['id']}',
            title: (m['title'] ?? 'Unknown') as String,
            artist:
                ((m['user'] as Map?)?['name'] ?? 'Unknown artist') as String,
            source: TrackSource.audius,
            uri: '$_host/tracks/${m['id']}/stream?$_app',
            artworkUrl: (art?['480x480'] ?? art?['150x150']) as String?,
            duration: Duration(seconds: ((m['duration'] ?? 0) as num).toInt()),
            link: m['permalink'] == null
                ? null
                : 'https://audius.co${m['permalink']}',
          );
        })
        .toList();
  }
}
