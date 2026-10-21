import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:youtube_explode_dart/youtube_explode_dart.dart';

import '../models/track.dart';

/// Public Invidious instances for fallback audio streaming.
/// Source: https://docs.invidious.io/instances/
const _invidiousInstances = [
  'https://yewtu.be',
  'https://inv.riverside.rocks',
  'https://inv.tux.pizza',
  'https://invidious.snopyta.org',
  'https://invidious.nerdvpn.de',
  'https://invidious.lunar.icu',
  'https://invidious.projectsegfau.lt',
  'https://invidious.jasonsavage.net',
  'https://invidious.exonip.de',
  'https://invidious.fdn.fr',
];

/// Shuffled once per session for load distribution.
final _shuffledInstances = List.of(_invidiousInstances)..shuffle(Random());

/// A YouTube channel shown as an artist. Followed artists are saved.
class YouTubeArtist {
  const YouTubeArtist({required this.id, required this.name, this.avatarUrl});

  /// Channel id (UC…).
  final String id;
  final String name;
  final String? avatarUrl;

  /// A stand-in track so the artist's picture can use [Artwork].
  Track get cover => Track(
    id: 'ytc:$id',
    title: name,
    artist: name,
    source: TrackSource.youtube,
    artworkUrl: avatarUrl,
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'avatarUrl': avatarUrl,
  };

  factory YouTubeArtist.fromJson(Map<String, dynamic> j) => YouTubeArtist(
    id: j['id'] as String,
    name: j['name'] as String,
    avatarUrl: j['avatarUrl'] as String?,
  );
}

/// A playlist on YouTube. Ones the user saves are kept as bookmarks.
class YouTubePlaylist {
  const YouTubePlaylist({
    required this.id,
    required this.title,
    this.author,
    this.count,
    this.coverUrl,
  });

  final String id;
  final String title;
  final String? author;
  final int? count;
  final String? coverUrl;

  Track get cover => Track(
    id: 'ytp:$id',
    title: title,
    artist: author ?? 'YouTube',
    source: TrackSource.youtube,
    artworkUrl: coverUrl,
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'title': title,
    'author': author,
    'count': count,
    'coverUrl': coverUrl,
  };

  factory YouTubePlaylist.fromJson(Map<String, dynamic> j) => YouTubePlaylist(
    id: j['id'] as String,
    title: j['title'] as String,
    author: j['author'] as String?,
    count: (j['count'] as num?)?.toInt(),
    coverUrl: j['coverUrl'] as String?,
  );
}

/// Where a YouTube song's audio can be fetched from, for a few hours.
class YouTubeAudio {
  const YouTubeAudio({
    required this.videoId,
    required this.url,
    required this.length,
    required this.mime,
    required this.expires,
  });

  /// The upload actually streamed; another upload of the same song when the
  /// original can't be played.
  final String videoId;
  final Uri url;
  final int length;
  final String mime;
  final DateTime expires;
}

/// YouTube wants the user to prove they're not a bot before it hands out
/// audio. It flags networks that make many requests, and lifts the check
/// again after a while.
class YouTubeBlocked implements Exception {
  const YouTubeBlocked();
  @override
  String toString() => 'YouTube is asking to confirm this isn\'t a bot';
}

/// Search, artists, playlists, suggestions and audio streams from YouTube,
/// read directly from the site (no key, no server). Not available on web,
/// where the browser blocks these requests.
class YouTubeApi {
  YouTubeApi._();
  static final instance = YouTubeApi._();

  static bool get supported => !kIsWeb;

  final _yt = YoutubeExplode();
  final _http = http.Client();

  final Map<String, YouTubeAudio> _audio = {};
  final Map<String, Future<YouTubeAudio>> _pending = {};

  /// Video id -> another upload of the same song that does play.
  final Map<String, String> _standIn = {};

  /// Songs whose audio couldn't be found lately, so a retry fails fast
  /// instead of searching all over again.
  final Map<String, (Object, DateTime)> _failed = {};
  final Map<String, List<Track>> _related = {};

  DateTime? _blockedAt;

  /// YouTube recently refused audio with a bot check; songs that need a
  /// fresh link will likely fail until it lifts.
  bool get blocked =>
      _blockedAt != null &&
      DateTime.now().difference(_blockedAt!) < const Duration(minutes: 3);

  // ---- YouTube's own web API ----------------------------------------------------

  static const _client = {
    'clientName': 'WEB',
    'clientVersion': '2.20251015.01.00',
    'hl': 'en',
  };
  static const _agent =
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/130.0 Safari/537.36';

  /// The app YouTube makes for VR headsets; it answers simply whether a
  /// video can be played, and why not.
  static const _vrClient = {
    'clientName': 'ANDROID_VR',
    'clientVersion': '1.65.10',
    'deviceMake': 'Oculus',
    'deviceModel': 'Quest 3',
    'androidSdkVersion': 32,
    'osName': 'Android',
    'osVersion': '12L',
    'hl': 'en',
  };
  static const _vrAgent =
      'com.google.android.apps.youtube.vr.oculus/1.65.10 '
      '(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip';

  Future<Map<String, dynamic>> _call(
    String endpoint,
    Map<String, dynamic> body, {
    bool vr = false,
  }) async {
    final res = await _http
        .post(
          Uri.parse(
            'https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false',
          ),
          headers: {
            'Content-Type': 'application/json',
            if (!vr) 'Origin': 'https://www.youtube.com',
            'User-Agent': vr ? _vrAgent : _agent,
          },
          body: jsonEncode({
            'context': {'client': vr ? _vrClient : _client},
            ...body,
          }),
        )
        .timeout(const Duration(seconds: 15));
    if (res.statusCode != 200) {
      throw HttpException('YouTube $endpoint: HTTP ${res.statusCode}');
    }
    return jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
  }

  /// Every value stored under [key], anywhere in [o].
  static Iterable<Map> _all(Object? o, String key) sync* {
    if (o is Map) {
      for (final e in o.entries) {
        if (e.key == key && e.value is Map) {
          yield e.value as Map;
        } else {
          yield* _all(e.value, key);
        }
      }
    } else if (o is List) {
      for (final v in o) {
        yield* _all(v, key);
      }
    }
  }

  /// "3:49" or "1:02:03".
  static Duration? _clock(String? s) {
    if (s == null || !RegExp(r'^\d+(:\d+){1,2}$').hasMatch(s)) return null;
    var secs = 0;
    for (final part in s.split(':')) {
      secs = secs * 60 + int.parse(part);
    }
    return Duration(seconds: secs);
  }

  /// A video in YouTube's current list layout (related, playlists).
  static Track? _fromLockup(Map l) {
    if (l['contentType'] != 'LOCKUP_CONTENT_TYPE_VIDEO') return null;
    final id = l['contentId'] as String?;
    final md = (l['metadata'] as Map?)?['lockupMetadataViewModel'] as Map?;
    final title = (md?['title'] as Map?)?['content'] as String?;
    if (id == null || title == null) return null;
    final parts = _all(md, 'contentMetadataViewModel')
        .expand((m) => (m['metadataRows'] as List?) ?? const [])
        .map((r) => ((r as Map)['metadataParts'] as List?) ?? const [])
        .toList();
    final channel = parts.isEmpty || parts.first.isEmpty
        ? ''
        : ((parts.first.first as Map)['text'] as Map?)?['content'] as String? ??
              '';
    final channelId =
        _all(md, 'browseEndpoint').firstOrNull?['browseId'] as String? ?? '';
    final badge = _all(
      l['contentImage'],
      'thumbnailBadgeViewModel',
    ).map((b) => b['text'] as String?).firstOrNull;
    final duration = _clock(badge);
    if (!_isSong(duration, badge == 'LIVE')) return null;
    return trackFrom(id, title, channel, channelId, duration);
  }

  /// A video in YouTube's older playlist layout.
  static Track? _fromPlaylistVideo(Map v) {
    final id = v['videoId'] as String?;
    final title = (((v['title'] as Map?)?['runs'] as List?)?.firstOrNull
        as Map?)?['text'] as String?;
    final by = (((v['shortBylineText'] as Map?)?['runs'] as List?)
        ?.firstOrNull as Map?);
    if (id == null || title == null) return null;
    final secs = int.tryParse('${v['lengthSeconds']}');
    final duration = secs == null ? null : Duration(seconds: secs);
    if (!_isSong(duration, false)) return null;
    return trackFrom(
      id,
      title,
      by?['text'] as String? ?? '',
      _all(by, 'browseEndpoint').firstOrNull?['browseId'] as String? ?? '',
      duration,
    );
  }

  static List<Track> _videosIn(Object? json) {
    final seen = <String>{};
    return [
      for (final l in _all(json, 'lockupViewModel'))
        if (_fromLockup(l) case final t? when seen.add(t.id)) t,
      for (final v in _all(json, 'playlistVideoRenderer'))
        if (_fromPlaylistVideo(v) case final t? when seen.add(t.id)) t,
    ];
  }

  static String? _continuation(Object? json) =>
      _all(json, 'continuationCommand')
          .map((c) => c['token'] as String?)
          .where((t) => t != null && t.isNotEmpty)
          .firstOrNull;

  // ---- Songs ------------------------------------------------------------------

  /// Songs only: no live streams, shorts or hour-long mixes.
  static bool _isSong(Duration? d, bool live) =>
      !live &&
      (d == null ||
          (d >= const Duration(seconds: 45) &&
              d <= const Duration(minutes: 20)));

  static final _noise = RegExp(
    r'\s*[\(\[【]\s*(official|lyric|lyrics|audio|video|visuali[sz]er|hd|hq|4k|music video|mv|explicit|clean|color coded|clip officiel|videoclip)\b[^\)\]】]*[\)\]】]',
    caseSensitive: false,
  );
  static final _tail = RegExp(
    r'\s+(official\s+)?(music\s+)?(video|audio|lyrics?|visuali[sz]er)\s*$',
    caseSensitive: false,
  );
  static final _dash = RegExp(r'^(.+?)\s+[-–—]\s+(.+)$');

  static String _squash(String s) =>
      s.toLowerCase().replaceAll(RegExp(r'[^a-z0-9]'), '');

  /// The channel name without YouTube's decorations.
  static String artistName(String channel) => channel
      .replaceAll(RegExp(r'\s*-\s*Topic$'), '')
      .replaceAll(RegExp(r'\s*(VEVO|Vevo)$'), '')
      .replaceAll(RegExp(r'\s*Official$', caseSensitive: false), '')
      .trim();

  /// "Artist - Song (Official Video)" by "ArtistVEVO" → (Artist, Song).
  /// Also says whether the uploading channel is the artist's own.
  static (String, String, bool) tidy(String raw, String channel) {
    var title = raw
        .replaceAll(_noise, '')
        .replaceAll(RegExp(r'\s*[|｜].*$'), '')
        .replaceAll(_tail, '')
        .trim();
    final owner = artistName(channel);
    var artist = owner;
    final m = _dash.firstMatch(title);
    if (m != null) {
      artist = m[1]!.trim();
      title = m[2]!.trim();
    }
    title = title.replaceAllMapped(
      RegExp(r'^["“”](.+)["“”]$'),
      (x) => x[1]!,
    );
    if (title.isEmpty) title = raw;
    if (artist.isEmpty) artist = owner.isEmpty ? 'Unknown artist' : owner;
    final own =
        _squash(owner).isNotEmpty &&
        (_squash(artist).startsWith(_squash(owner)) ||
            _squash(owner).startsWith(_squash(artist)));
    return (artist, title, own);
  }

  static Track trackFrom(
    String id,
    String rawTitle,
    String channel,
    String channelId,
    Duration? duration,
  ) {
    final (artist, title, own) = tidy(rawTitle, channel);
    return Track(
      id: 'yt:$id',
      title: title,
      artist: artist,
      source: TrackSource.youtube,
      uri: id,
      artworkUrl: 'https://i.ytimg.com/vi/$id/hqdefault.jpg',
      duration: duration,
      artistId: own ? channelId : null,
      link: 'https://www.youtube.com/watch?v=$id',
    );
  }

  static Track _fromVideo(Video v) => trackFrom(
    v.id.value,
    v.title,
    v.author,
    v.channelId.value,
    v.duration,
  );

  /// With [anything], every video that isn't live: talks, podcasts and
  /// long mixes as well as songs.
  static List<Track> _songs(Iterable<Video> videos, {bool anything = false}) {
    final seen = <String>{};
    return [
      for (final v in videos)
        if ((anything ? !v.isLive : _isSong(v.duration, v.isLive)) &&
            seen.add(v.id.value))
          _fromVideo(v),
    ];
  }

  static String? _thumb(List<Thumbnail> t) {
    if (t.isEmpty) return null;
    final best = t.reduce((a, b) => a.width >= b.width ? a : b);
    var url = best.url.toString().replaceFirst('https:https://', 'https://');
    if (url.startsWith('//')) url = 'https:$url';
    return url;
  }

  /// Songs matching [q], best match first (or newest first, with [latest]).
  /// With [anything], any kind of video, not just songs.
  Future<List<Track>> songs(
    String q, {
    int limit = 30,
    bool latest = false,
    bool anything = false,
  }) async {
    var page = await _yt.search.search(
      q,
      filter: latest ? SortFilters.uploadDate : TypeFilters.video,
    );
    final out = _songs(page, anything: anything);
    while (out.length < limit) {
      final next = await page.nextPage();
      if (next == null || next.isEmpty) break;
      page = next;
      final seen = {for (final t in out) t.id};
      out.addAll(
        _songs(next, anything: anything).where((t) => seen.add(t.id)),
      );
    }
    return out.take(limit).toList();
  }

  Future<List<String>> suggestions(String q) async {
    if (q.trim().isEmpty) return const [];
    try {
      return await _yt.search
          .getQuerySuggestions(q)
          .timeout(const Duration(seconds: 6));
    } catch (_) {
      return const [];
    }
  }

  // ---- Artists --------------------------------------------------------------------

  /// Channels matching [q]. Auto-generated "- Topic" channels are kept only
  /// when the artist has no channel of their own in the results.
  Future<List<YouTubeArtist>> artists(String q) async {
    final res = await _yt.search.searchContent(q, filter: TypeFilters.channel);
    final byName = <String, YouTubeArtist>{};
    for (final c in res.whereType<SearchChannel>()) {
      final topic = c.name.endsWith(' - Topic');
      final key = _squash(artistName(c.name));
      if (key.isEmpty || (topic && byName.containsKey(key))) continue;
      final a = YouTubeArtist(
        id: c.id.value,
        name: artistName(c.name),
        avatarUrl: _thumb(c.thumbnails),
      );
      if (!topic || !byName.containsKey(key)) byName[key] = a;
    }
    return byName.values.toList();
  }

  /// The channel with an artist's name, if there is one.
  Future<YouTubeArtist?> findArtist(String name) async {
    final want = _squash(name);
    if (want.isEmpty) return null;
    final found = await artists(name);
    return found.where((a) => _squash(a.name) == want).firstOrNull;
  }

  Future<YouTubeArtist> artist(String channelId) async {
    final c = await _yt.channels.get(channelId);
    return YouTubeArtist(
      id: channelId,
      name: artistName(c.title),
      avatarUrl: c.logoUrl.isEmpty ? null : c.logoUrl,
    );
  }

  /// An artist's best-known songs (or newest, with [latest]): their own
  /// uploads and others' uploads of their songs. Channel upload lists are
  /// mostly Shorts these days, so this searches instead.
  Future<List<Track>> artistSongs(
    YouTubeArtist a, {
    bool latest = false,
    int limit = 30,
  }) async {
    final want = _squash(a.name);
    final found = await songs(a.name, limit: limit * 2, latest: latest);
    final seen = <String>{};
    return found
        .where(
          (t) =>
              (t.artistId == a.id || _squash(t.artist).contains(want)) &&
              seen.add(_squash(t.title)),
        )
        .take(limit)
        .toList();
  }

  // ---- Playlists --------------------------------------------------------------------

  Future<List<YouTubePlaylist>> playlists(String q) async {
    final res = await _yt.search.searchContent(q, filter: TypeFilters.playlist);
    return [
      for (final p in res.whereType<SearchPlaylist>())
        if (p.videoCount > 0)
          YouTubePlaylist(
            id: p.id.value,
            title: p.title,
            count: p.videoCount,
            coverUrl: _thumb(p.thumbnails),
          ),
    ];
  }

  Future<List<Track>> playlistSongs(String id, {int max = 200}) async {
    final out = <Track>[];
    var page = await _call('browse', {'browseId': 'VL$id'});
    for (var pages = 0; pages < 10; pages++) {
      final seen = {for (final t in out) t.id};
      out.addAll(_videosIn(page).where((t) => seen.add(t.id)));
      final next = _continuation(page);
      if (out.length >= max || next == null) break;
      page = await _call('browse', {'continuation': next});
    }
    if (out.isEmpty) {
      return _songs(await _yt.playlists.getVideos(id).take(max).toList());
    }
    return out.take(max).toList();
  }

  // ---- Suggestions ------------------------------------------------------------------

  /// Songs that go well after [t]: YouTube's "up next" for it. Songs from
  /// elsewhere (phone, Telegram) are looked up on YouTube first.
  Future<List<Track>> related(Track t) async {
    final key = t.isYouTube ? t.uri! : '${t.artist} ${t.title}';
    final hit = _related[key];
    if (hit != null) return hit;
    var id = t.isYouTube ? t.uri : null;
    id ??= (await songs('${t.artist} ${t.title}', limit: 1)).firstOrNull?.uri;
    if (id == null) return const [];
    var out = _videosIn(await _call('next', {'videoId': id}));
    if (out.isEmpty) {
      final video = await _yt.videos.get(id);
      out = _songs(await _yt.videos.getRelatedVideos(video) ?? const []);
    }
    out = out.where((x) => x.uri != id).toList();
    if (_related.length > 100) _related.remove(_related.keys.first);
    return _related[key] = out;
  }

  // ---- Audio ------------------------------------------------------------------------

  /// The audio link for [t], looked up once and reused until it expires.
  Future<YouTubeAudio> audioFor(Track t) {
    final id = t.uri!;
    final hit = _audio[id];
    if (hit != null &&
        hit.expires.isAfter(DateTime.now().add(const Duration(minutes: 5)))) {
      return Future.value(hit);
    }
    return _pending[id] ??= _resolve(t).whenComplete(() {
      _pending.remove(id);
    });
  }

  /// Looks the link up ahead of time so the next song starts quickly.
  void warm(Track t) => unawaited(audioFor(t).then((_) {}, onError: (_) {}));

  /// Drops a link that stopped working, so the next request gets a fresh one.
  void forget(Track t) {
    _audio.remove(t.uri);
    _failed.remove(t.uri);
  }

  Future<YouTubeAudio> _resolve(Track t) async {
    final id = t.uri!;
    final failed = _failed[id];
    if (failed != null &&
        DateTime.now().difference(failed.$2) < const Duration(minutes: 3)) {
      throw failed.$1;
    }
    try {
      return await _lookUp(t);
    } catch (e) {
      _failed[id] = (e, DateTime.now());
      rethrow;
    }
  }

  Future<YouTubeAudio> _lookUp(Track t) async {
    final id = t.uri!;
    final standIn = _standIn[id];
    if (standIn != null) {
      return _audio[id] = await _manifest(standIn).catchError((Object _) {
        _standIn.remove(id);
        return _manifest(id);
      });
    }
    try {
      return _audio[id] = await _manifest(id);
    } catch (e) {
      // Try Invidious directly for this video before bot check
      try {
        return _audio[id] = await _manifestFromInvidious(id);
      } catch (_) {}
      // Behind a bot check every upload fails, so don't go looking.
      if (await _botCheck(id)) {
        _blockedAt = DateTime.now();
        throw const YouTubeBlocked();
      }
      // Label uploads are often blocked outside the YouTube app; the same
      // song is nearly always up elsewhere (lyric videos, "Topic" channels).
      final alt = await _alternative(t);
      if (alt == null) rethrow;
      _standIn[id] = alt.videoId;
      return _audio[id] = alt;
    }
  }

  /// Whether YouTube is refusing [id] until the user proves they're human.
  Future<bool> _botCheck(String id) async {
    try {
      final r = await _call('player', {'videoId': id}, vr: true);
      final status = r['playabilityStatus'] as Map?;
      return status?['status'] == 'LOGIN_REQUIRED' &&
          '${status?['reason']}'.contains('bot');
    } catch (_) {
      return false;
    }
  }

  Future<YouTubeAudio?> _alternative(Track t) async {
    final found = await songs('${t.artist} ${t.title} audio', limit: 8);
    final candidates = found.where((x) {
      if (x.uri == t.uri) return false;
      if (t.duration == null || x.duration == null) return true;
      return (x.duration! - t.duration!).abs() < const Duration(seconds: 25);
    });
    for (final c in candidates.take(2)) {
      try {
        return await _manifest(c.uri!);
      } catch (_) {}
    }
    return null;
  }

  Future<YouTubeAudio> _manifest(String id) async {
    Object last = Exception('No audio for $id');
    for (final client in [
      YoutubeApiClient.androidSdkless,
      YoutubeApiClient.androidVr,
      YoutubeApiClient.ios,
    ]) {
      try {
        return await _manifestWith(id, client);
      } catch (e) {
        last = e;
      }
    }
    // Fallback to Invidious instances when all InnerTube clients fail
    try {
      return await _manifestFromInvidious(id);
    } catch (e) {
      last = e;
    }
    throw last;
  }

  /// Tries to get audio URL from Invidious instances.
  Future<YouTubeAudio> _manifestFromInvidious(String id) async {
    Object? lastError;
    for (final instance in _shuffledInstances) {
      try {
        final url = await _fetchInvidiousAudioUrl(instance, id);
        if (url != null) {
          _blockedAt = null;
          return _toAudio(id, url, 0, 'audio/mp4');
        }
      } catch (e) {
        lastError = e;
        continue; // Try next instance
      }
    }
    throw lastError ?? Exception('No Invidious instance available');
  }

  Future<Uri?> _fetchInvidiousAudioUrl(String instance, String videoId) async {
    final uri = Uri.parse('$instance/api/v1/videos/$videoId');
    final res = await _http.get(uri, headers: {'Accept': 'application/json'})
        .timeout(const Duration(seconds: 15));
    if (res.statusCode != 200) return null;
    final data = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
    final adaptive = data['adaptiveFormats'] as List?;
    if (adaptive == null) return null;
    // Prefer audio-only, high bitrate, mp4 (skip HLS/m3u8)
    final audioFormats = adaptive
        .where((f) => f['type']?.toString().startsWith('audio/') ?? false)
        .where((f) => f['container'] == 'mp4' || f['container'] == 'm4a')
        .where((f) => !(f['url'] as String? ?? '').contains('.m3u8'))
        .toList();
    if (audioFormats.isEmpty) return null;
    audioFormats.sort((a, b) => ((b['bitrate'] as num?) ?? 0).compareTo((a['bitrate'] as num?) ?? 0));
    final best = audioFormats.first;
    final urlStr = best['url'] as String?;
    return urlStr != null ? Uri.parse(urlStr) : null;
  }

  Future<YouTubeAudio> _manifestWith(String id, YoutubeApiClient client) async {
    final m = await _yt.videos.streams
        .getManifest(id, ytClients: [client], requireWatchPage: false)
        .timeout(const Duration(seconds: 20));
    _blockedAt = null;
    final audio = m.audioOnly.toList();
    final mp4 = audio.where((a) => a.container == StreamContainer.mp4).toList();
    final audioPool = mp4.isEmpty ? audio : mp4;
    if (audioPool.isNotEmpty) {
      final best = audioPool.withHighestBitrate();
      return _toAudio(
        id,
        best.url,
        best.size.totalBytes,
        best.container == StreamContainer.mp4 ? 'audio/mp4' : 'audio/webm',
      );
    }
    // Audio-only can be missing; a muxed MP4 still plays in just_audio.
    final muxed = m.muxed.toList();
    final muxMp4 = muxed
        .where((s) => s.container == StreamContainer.mp4)
        .toList();
    final muxPool = muxMp4.isEmpty ? muxed : muxMp4;
    if (muxPool.isEmpty) throw Exception('No audio for $id');
    final best = muxPool.withHighestBitrate();
    return _toAudio(id, best.url, best.size.totalBytes, 'video/mp4');
  }

  YouTubeAudio _toAudio(String id, Uri url, int length, String mime) {
    final expire = int.tryParse(url.queryParameters['expire'] ?? '');
    final clen = int.tryParse(url.queryParameters['clen'] ?? '') ?? 0;
    return YouTubeAudio(
      videoId: id,
      url: url,
      length: length > 0 ? length : clen,
      mime: mime,
      expires: expire == null
          ? DateTime.now().add(const Duration(hours: 4))
          : DateTime.fromMillisecondsSinceEpoch(expire * 1000),
    );
  }

  /// YouTube slows down or refuses long single downloads, so audio is read
  /// in pieces.
  static const chunkSize = 1 << 20;

  /// Streams bytes [from, to) of [t]'s audio. A piece that fails is retried
  /// with a fresh link, carrying on from the same byte.
  Stream<List<int>> bytes(Track t, int from, int to) async* {
    var pos = from;
    var fails = 0;
    while (pos < to) {
      final audio = await audioFor(t);
      final last = min(pos + chunkSize, to) - 1;
      try {
        final req = http.Request('GET', audio.url)
          ..headers['Range'] = 'bytes=$pos-$last'
          ..headers['User-Agent'] = _agent
          ..headers['Referer'] = 'https://www.youtube.com/';
        final res = await _http.send(req).timeout(const Duration(seconds: 20));
        if (res.statusCode == 416) break;
        if (res.statusCode != 206 && res.statusCode != 200) {
          throw HttpException('Audio HTTP ${res.statusCode}');
        }
        Stream<List<int>> stream = res.stream;
        final contentLength = res.contentLength;
        // Server ignored Range header (returned 200 instead of 206)
        if (res.statusCode == 200) {
          if (pos > 0) {
            stream = _skip(stream, pos);
          }
        }
        final before = pos;
        var received = 0;
        await for (final chunk in stream.timeout(
          const Duration(seconds: 15),
        )) {
          final left = to - pos;
          if (left <= 0) break;
          if (chunk.isEmpty) continue;
          received += chunk.length;
          if (chunk.length <= left) {
            pos += chunk.length;
            yield chunk;
          } else {
            yield chunk.sublist(0, left);
            pos = to;
            break;
          }
        }
        // If server returned 200 with full content but we only read part,
        // the contentLength might be the full file size, not the chunk.
        if (pos == before) {
          // No data received - might be HLS/m3u8 or empty response
          if (audio.url.toString().contains('.m3u8')) {
            throw HttpException('HLS stream not supported directly');
          }
          throw HttpException('Audio empty at $pos');
        }
        // If we got a 200 but received less than expected, the stream ended early
        if (res.statusCode == 200 && contentLength != null && received < contentLength && pos < to) {
          // Stream ended prematurely, will retry with fresh link
          throw HttpException('Stream ended early (got $received of $contentLength)');
        }
        fails = 0;
      } catch (_) {
        if (++fails > 3) rethrow;
        forget(t);
        await Future.delayed(Duration(milliseconds: 500 * fails));
      }
    }
  }

  /// Saves [t]'s audio to [file] (to pass a YouTube song to Jam friends).
  /// Returns the file extension.
  Future<String> download(Track t, File file) async {
    final audio = await audioFor(t);
    final sink = file.openWrite();
    try {
      await sink.addStream(bytes(t, 0, audio.length));
    } finally {
      await sink.close();
    }
    return audio.mime == 'audio/mp4' || audio.mime == 'video/mp4'
        ? 'm4a'
        : 'webm';
  }

  static Stream<List<int>> _skip(Stream<List<int>> s, int n) async* {
    var left = n;
    await for (final chunk in s) {
      if (left >= chunk.length) {
        left -= chunk.length;
        continue;
      }
      yield left == 0 ? chunk : chunk.sublist(left);
      left = 0;
    }
  }
}
