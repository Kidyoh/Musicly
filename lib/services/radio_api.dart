import '../models/track.dart';
import 'json_fetch.dart';

/// Radio Browser: a free, community directory of 50,000+ live internet
/// radio stations (https://www.radio-browser.info). No key needed.
class RadioApi {
  static const _servers = [
    'https://de1.api.radio-browser.info',
    'https://de2.api.radio-browser.info',
    'https://fi1.api.radio-browser.info',
  ];
  int _server = 0;

  static const tags = [
    ('pop', 'Pop'),
    ('hiphop', 'Hip hop'),
    ('afrobeats', 'Afrobeats'),
    ('rnb', 'R&B'),
    ('dance', 'Dance'),
    ('rock', 'Rock'),
    ('jazz', 'Jazz'),
    ('lofi', 'Lo-fi'),
    ('reggae', 'Reggae'),
    ('gospel', 'Gospel'),
    ('classical', 'Classical'),
    ('country', 'Country'),
    ('80s', '80s'),
    ('news', 'News'),
  ];

  Future<List<Map>> _get(String path, Map<String, String> q) async {
    // Only secure streams: phones block plain-http audio by default.
    final query = {'hidebroken': 'true', 'is_https': 'true', ...q};
    Object? lastError;
    for (var i = 0; i < _servers.length; i++) {
      final base = _servers[(_server + i) % _servers.length];
      try {
        final body = await fetchJson(
          Uri.parse('$base$path').replace(queryParameters: query),
        );
        _server = (_server + i) % _servers.length;
        return (body as List).cast<Map>();
      } catch (e) {
        lastError = e;
      }
    }
    throw Exception('Radio directory unreachable: $lastError');
  }

  List<Track> _stations(List<Map> l) {
    final seen = <String>{};
    return l
        .where(
          (m) => (m['url_resolved'] as String? ?? '').startsWith('https://'),
        )
        .where(
          (m) => seen.add((m['name'] as String? ?? '').trim().toLowerCase()),
        )
        .map((m) {
          final tags = (m['tags'] as String? ?? '')
              .split(',')
              .map((t) => t.trim())
              .where((t) => t.isNotEmpty)
              .take(2)
              .join(' · ');
          final country = (m['country'] as String? ?? '').trim();
          final fav = (m['favicon'] as String? ?? '').trim();
          return Track(
            id: 'rb:${m['stationuuid']}',
            title: (m['name'] as String? ?? 'Radio').trim(),
            artist: [
              if (tags.isNotEmpty) tags,
              if (country.isNotEmpty) country,
            ].join(' • '),
            source: TrackSource.radio,
            uri: m['url_resolved'] as String,
            artworkUrl: fav.startsWith('https://') ? fav : null,
            link: (m['homepage'] as String?)?.startsWith('http') == true
                ? m['homepage'] as String
                : null,
          );
        })
        .toList();
  }

  Future<List<Track>> top({int limit = 40}) async => _stations(
    await _get('/json/stations/search', {
      'order': 'clickcount',
      'reverse': 'true',
      'limit': '$limit',
    }),
  );

  Future<List<Track>> byTag(String tag, {int limit = 40}) async => _stations(
    await _get('/json/stations/search', {
      'tag': tag,
      'order': 'clickcount',
      'reverse': 'true',
      'limit': '$limit',
    }),
  );

  Future<List<Track>> byCountry(String code, {int limit = 40}) async =>
      _stations(
        await _get('/json/stations/search', {
          'countrycode': code,
          'order': 'clickcount',
          'reverse': 'true',
          'limit': '$limit',
        }),
      );

  Future<List<Track>> search(String name, {int limit = 20}) async => _stations(
    await _get('/json/stations/search', {
      'name': name,
      'order': 'clickcount',
      'reverse': 'true',
      'limit': '$limit',
    }),
  );

  /// Tells the directory a station was played (helps its popularity ranking).
  Future<void> countClick(String trackId) async {
    try {
      await fetchJson(
        Uri.parse('${_servers[_server]}/json/url/${trackId.substring(3)}'),
      );
    } catch (_) {}
  }
}
