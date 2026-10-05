import '../models/collection.dart';
import '../models/track.dart';
import 'json_fetch.dart';

/// Deezer's public catalogue: real charts, artists and albums with free
/// 30-second previews. No account or API key needed.
class DeezerApi {
  static const _host = 'https://api.deezer.com';

  Future<dynamic> _get(String path, [Map<String, String>? q]) async {
    final uri = Uri.parse('$_host$path').replace(queryParameters: q);
    dynamic body;
    // Up to three attempts with a short backoff, for patchy mobile networks.
    for (var attempt = 0; ; attempt++) {
      try {
        body = await fetchJson(uri, jsonp: true);
        break;
      } catch (_) {
        if (attempt >= 2) rethrow;
        await Future.delayed(
          Duration(milliseconds: 300 * (attempt + 1) * (attempt + 1)),
        );
      }
    }
    if (body is Map && body['error'] != null) {
      throw Exception('Deezer: ${body['error']['message'] ?? body['error']}');
    }
    return body;
  }

  Future<List<Map>> _list(String path, [Map<String, String>? q]) async {
    final body = await _get(path, q);
    return ((body as Map)['data'] as List? ?? const []).cast<Map>();
  }

  // ---- Parsing --------------------------------------------------------------

  static Track track(Map m, {Map? album}) {
    final al = (m['album'] as Map?) ?? album;
    final ar = m['artist'] as Map?;
    return Track(
      id: 'dz:${m['id']}',
      title: (m['title_short'] ?? m['title'] ?? 'Unknown') as String,
      artist: (ar?['name'] ?? 'Unknown artist') as String,
      source: TrackSource.deezer,
      uri: m['preview'] as String?,
      artworkUrl:
          (al?['cover_xl'] ?? al?['cover_big'] ?? al?['cover_medium'])
              as String?,
      duration: Duration(seconds: ((m['duration'] ?? 0) as num).toInt()),
      artistId: ar?['id']?.toString(),
      album: al?['title'] as String?,
      albumId: al?['id']?.toString(),
      link: m['link'] as String?,
    );
  }

  static Collection album(Map m) => Collection(
    id: m['id'].toString(),
    title: (m['title'] ?? 'Untitled') as String,
    owner: ((m['artist'] as Map?)?['name'] ?? '') as String,
    ownerId: (m['artist'] as Map?)?['id']?.toString(),
    kind: CollectionKind.album,
    artworkUrl: (m['cover_xl'] ?? m['cover_big']) as String?,
    trackCount: (m['nb_tracks'] as num?)?.toInt(),
    year: DateTime.tryParse((m['release_date'] ?? '') as String)?.year,
  );

  static Collection playlist(Map m) => Collection(
    id: m['id'].toString(),
    title: (m['title'] ?? 'Untitled') as String,
    owner:
        ((m['user'] as Map?)?['name'] ??
                (m['creator'] as Map?)?['name'] ??
                'Deezer')
            as String,
    kind: CollectionKind.playlist,
    artworkUrl: (m['picture_xl'] ?? m['picture_big']) as String?,
    trackCount: (m['nb_tracks'] as num?)?.toInt(),
  );

  static Artist artist(Map m) => Artist(
    id: m['id'].toString(),
    name: (m['name'] ?? '') as String,
    pictureUrl: (m['picture_xl'] ?? m['picture_big']) as String?,
    fans: (m['nb_fan'] as num?)?.toInt(),
    albums: (m['nb_album'] as num?)?.toInt(),
  );

  List<Track> _tracks(List<Map> l) => l
      .where((m) => (m['preview'] ?? '') != '' && m['readable'] != false)
      .map((m) => track(m))
      .toList();

  // ---- Endpoints ------------------------------------------------------------

  /// [genreId] 0 is "all genres".
  Future<List<Track>> chartTracks({int genreId = 0, int limit = 50}) async =>
      _tracks(await _list('/chart/$genreId/tracks', {'limit': '$limit'}));

  Future<List<Collection>> chartAlbums({
    int genreId = 0,
    int limit = 20,
  }) async => (await _list('/chart/$genreId/albums', {
    'limit': '$limit',
  })).map(album).toList();

  Future<List<Artist>> chartArtists({int genreId = 0, int limit = 20}) async =>
      (await _list('/chart/$genreId/artists', {
        'limit': '$limit',
      })).map(artist).toList();

  Future<List<Collection>> chartPlaylists({int limit = 20}) async =>
      (await _list('/chart/0/playlists', {
        'limit': '$limit',
      })).map(playlist).toList();

  Future<List<Genre>> genres() async =>
      (await _list('/genre'))
          .where((m) => m['id'] != 0)
          .map(
            (m) => Genre(
              id: (m['id'] as num).toInt(),
              name: m['name'] as String,
              pictureUrl: (m['picture_xl'] ?? m['picture_big']) as String?,
            ),
          )
          .toList();

  Future<List<Track>> search(String q, {int limit = 30}) async =>
      _tracks(await _list('/search', {'q': q, 'limit': '$limit'}));

  Future<List<Artist>> searchArtists(String q, {int limit = 10}) async =>
      (await _list('/search/artist', {
        'q': q,
        'limit': '$limit',
      })).map(artist).toList();

  Future<List<Collection>> searchAlbums(String q, {int limit = 10}) async =>
      (await _list('/search/album', {
        'q': q,
        'limit': '$limit',
      })).map(album).toList();

  Future<List<Collection>> searchPlaylists(String q, {int limit = 10}) async =>
      (await _list('/search/playlist', {
        'q': q,
        'limit': '$limit',
      })).map(playlist).toList();

  Future<Artist> artistInfo(String id) async =>
      artist(await _get('/artist/$id') as Map);

  Future<List<Track>> artistTop(String id, {int limit = 20}) async =>
      _tracks(await _list('/artist/$id/top', {'limit': '$limit'}));

  Future<List<Collection>> artistAlbums(String id, {int limit = 30}) async =>
      (await _list('/artist/$id/albums', {
        'limit': '$limit',
      })).map(album).toList();

  Future<List<Artist>> relatedArtists(String id, {int limit = 12}) async =>
      (await _list('/artist/$id/related', {
        'limit': '$limit',
      })).map(artist).toList();

  /// Endless-style mix of songs like this artist's.
  Future<List<Track>> artistRadio(String id, {int limit = 25}) async =>
      _tracks(await _list('/artist/$id/radio', {'limit': '$limit'}));

  Future<List<Track>> albumTracks(String id) async {
    final body = await _get('/album/$id') as Map;
    final list = ((body['tracks'] as Map?)?['data'] as List? ?? const [])
        .cast<Map>();
    return list
        .where((m) => (m['preview'] ?? '') != '')
        .map((m) => track(m, album: body))
        .toList();
  }

  Future<List<Track>> playlistTracks(String id) async =>
      _tracks(await _list('/playlist/$id/tracks', {'limit': '100'}));

  /// Fresh preview URL. Deezer signs them for ~15 minutes.
  Future<String?> previewUrl(int trackId) async {
    final m = await _get('/track/$trackId') as Map;
    final p = m['preview'] as String?;
    return p == null || p.isEmpty ? null : p;
  }
}
