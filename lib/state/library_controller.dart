import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart' show ThemeMode;
import 'package:shared_preferences/shared_preferences.dart';

import '../models/collection.dart';
import '../models/track.dart';
import '../services/audius_api.dart';
import '../services/telegram_bot.dart';
import '../services/deezer_api.dart';
import '../services/device_library.dart';
import '../services/radio_api.dart';

/// Everything the user owns or that's personalised: likes, playlists,
/// followed artists, phone music, history and the home feed.
class LibraryController extends ChangeNotifier {
  LibraryController(this.api) {
    _restore().then((_) {
      loadHome();
      if (channelConnected) _startSync();
      if (deviceScanEnabled) scanDevice(askPermission: false);
    });
  }

  final DeezerApi api;
  final AudiusApi audius = AudiusApi();
  final RadioApi radio = RadioApi();

  ThemeMode themeMode = ThemeMode.light;

  final List<Track> favorites = [];
  final List<Track> recent = [];
  final List<Track> files = []; // picked with the file picker
  List<Track> deviceTracks = []; // scanned from the phone
  final List<UserPlaylist> playlists = [];
  final List<Artist> following = [];

  /// Plays per Deezer artist id, with the name kept for display.
  final Map<String, int> _plays = {};
  final Map<String, String> _artistNames = {};

  // Telegram channel, read directly with the Bot API (no server)
  TelegramBot? bot;
  String? botUsername;
  int? channelId;
  String? channelName;
  List<Track> channelTracks = [];
  bool channelLoading = false;
  String? channelError;
  int _updateOffset = 0;
  int?
  _userChatId; // the user's private chat with the bot, for importing history
  int _latestPostId = 0;
  int tooBigSkipped = 0;
  bool importing = false;
  int importScanned = 0;
  int importFound = 0;
  bool _cancelImport = false;
  Timer? _syncTimer;
  bool get channelConnected => bot != null && channelId != null;
  bool get canImport => _userChatId != null;

  bool deviceScanEnabled = false;
  bool scanning = false;
  String? scanError;

  // Home feed
  List<Track> chart = [];
  List<Collection> topAlbums = [];
  List<Artist> topArtists = [];
  List<Collection> topPlaylists = [];
  List<Genre> genres = [];
  List<Track> forYou = [];
  List<Track> freeSongs = []; // full-length, from Audius
  List<Track> stations = []; // live radio, local first
  String? countryCode;
  String? forYouReason;
  Artist? becauseArtist;
  List<Artist> becauseRelated = [];
  bool homeLoading = false;
  String? homeError;

  bool isFavorite(Track t) => favorites.any((f) => f.id == t.id);

  List<Track> get likedSongs => favorites.where((t) => !t.isRadio).toList();
  List<Track> get savedStations => favorites.where((t) => t.isRadio).toList();
  bool isFollowing(String artistId) => following.any((a) => a.id == artistId);

  List<Track> get localTracks => [...deviceTracks, ...files];

  // ---- History & recommendations -----------------------------------------

  void recordPlay(Track t) {
    recent.removeWhere((r) => r.id == t.id);
    recent.insert(0, t);
    if (recent.length > 50) recent.removeLast();
    if (t.isRadio) radio.countClick(t.id);
    if (t.artistId != null && t.source == TrackSource.deezer) {
      _plays[t.artistId!] = (_plays[t.artistId!] ?? 0) + 1;
      _artistNames[t.artistId!] = t.artist;
    }
    _save();
    notifyListeners();
  }

  /// Artists ranked by plays, likes and follows.
  List<(String, String)> topSeedArtists([int n = 3]) {
    final score = <String, double>{};
    _plays.forEach((id, c) => score[id] = (score[id] ?? 0) + c.toDouble());
    for (final f in favorites) {
      if (f.artistId != null && f.source == TrackSource.deezer) {
        score[f.artistId!] = (score[f.artistId!] ?? 0) + 3;
        _artistNames[f.artistId!] = f.artist;
      }
    }
    for (final a in following) {
      score[a.id] = (score[a.id] ?? 0) + 6;
      _artistNames[a.id] = a.name;
    }
    final ids = score.keys.toList()
      ..sort((a, b) => score[b]!.compareTo(score[a]!));
    return ids.take(n).map((id) => (id, _artistNames[id] ?? '')).toList();
  }

  Future<void> buildForYou() async {
    var seeds = topSeedArtists();
    final personal = seeds.isNotEmpty;
    if (!personal && topArtists.isNotEmpty) {
      seeds = topArtists.take(3).map((a) => (a.id, a.name)).toList();
    }
    if (seeds.isEmpty) return;
    try {
      final radios = await Future.wait(
        seeds.map((s) => api.artistRadio(s.$1, limit: 20)),
      );
      final seen = <String>{};
      final mix = <Track>[];
      // Interleave so the mix isn't one artist after another.
      for (var i = 0; i < 20; i++) {
        for (final r in radios) {
          if (i < r.length && seen.add(r[i].id)) mix.add(r[i]);
        }
      }
      forYou = mix.take(40).toList();
      forYouReason = personal
          ? 'Based on ${seeds.map((s) => s.$2).where((n) => n.isNotEmpty).take(2).join(' & ')}'
          : 'Popular right now. Like songs to personalise';
      final top = seeds.first;
      becauseArtist = Artist(id: top.$1, name: top.$2);
      becauseRelated = personal ? await api.relatedArtists(top.$1) : [];
      notifyListeners();
    } catch (_) {}
  }

  Future<void> loadHome() async {
    homeLoading = true;
    homeError = null;
    notifyListeners();
    try {
      final r = await Future.wait([
        api.chartTracks(limit: 30),
        api.chartAlbums(limit: 15),
        api.chartArtists(limit: 15),
        api.chartPlaylists(limit: 12),
        api.genres(),
      ]);
      chart = r[0] as List<Track>;
      topAlbums = r[1] as List<Collection>;
      topArtists = r[2] as List<Artist>;
      topPlaylists = r[3] as List<Collection>;
      genres = r[4] as List<Genre>;
    } catch (_) {
      homeError = 'Could not load music. Check your connection.';
    }
    homeLoading = false;
    notifyListeners();
    await Future.wait([buildForYou(), _loadExtras()]);
  }

  /// Free full songs and live radio load alongside the main feed; either can
  /// fail without hiding the rest of Home.
  Future<void> _loadExtras() async {
    countryCode ??= PlatformDispatcher.instance.locale.countryCode;
    await Future.wait([
      audius
          .trending(limit: 20)
          .then((v) => freeSongs = v)
          .catchError((_) => freeSongs),
      (() async {
        var local = <Track>[];
        if (countryCode != null) {
          local = await radio
              .byCountry(countryCode!, limit: 12)
              .catchError((_) => <Track>[]);
        }
        final top = await radio.top(limit: 20).catchError((_) => <Track>[]);
        final seen = <String>{};
        stations = [
          ...local,
          ...top,
        ].where((t) => seen.add(t.id)).take(20).toList();
      })(),
    ]);
    notifyListeners();
  }

  // ---- Likes & follows -----------------------------------------------------

  void toggleFavorite(Track t) {
    if (isFavorite(t)) {
      favorites.removeWhere((f) => f.id == t.id);
    } else {
      favorites.insert(0, t);
    }
    _save();
    notifyListeners();
  }

  void favoriteAll(List<Track> tracks) {
    for (final t in tracks.reversed) {
      if (!isFavorite(t)) favorites.insert(0, t);
    }
    _save();
    notifyListeners();
  }

  void toggleFollow(Artist a) {
    if (isFollowing(a.id)) {
      following.removeWhere((x) => x.id == a.id);
    } else {
      following.insert(0, a);
    }
    _save();
    notifyListeners();
  }

  // ---- Playlists -------------------------------------------------------------

  UserPlaylist createPlaylist(String name, [List<Track> tracks = const []]) {
    final p = UserPlaylist(
      id: '${DateTime.now().microsecondsSinceEpoch}${Random().nextInt(999)}',
      name: name.trim().isEmpty ? 'My playlist' : name.trim(),
      tracks: List.of(tracks),
    );
    playlists.insert(0, p);
    _save();
    notifyListeners();
    return p;
  }

  UserPlaylist? playlist(String id) {
    for (final p in playlists) {
      if (p.id == id) return p;
    }
    return null;
  }

  /// Returns how many songs were actually added (duplicates are skipped).
  int addToPlaylist(UserPlaylist p, List<Track> tracks) {
    var n = 0;
    for (final t in tracks) {
      if (!t.isPersistable || p.tracks.any((x) => x.id == t.id)) continue;
      p.tracks.add(t);
      n++;
    }
    _save();
    notifyListeners();
    return n;
  }

  void removeFromPlaylist(UserPlaylist p, Track t) {
    p.tracks.removeWhere((x) => x.id == t.id);
    _save();
    notifyListeners();
  }

  void reorderPlaylist(UserPlaylist p, int from, int to) {
    final t = p.tracks.removeAt(from);
    p.tracks.insert(to, t);
    _save();
    notifyListeners();
  }

  void renamePlaylist(UserPlaylist p, String name) {
    if (name.trim().isEmpty) return;
    p.name = name.trim();
    _save();
    notifyListeners();
  }

  void deletePlaylist(UserPlaylist p) {
    playlists.removeWhere((x) => x.id == p.id);
    _save();
    notifyListeners();
  }

  // ---- Telegram channel --------------------------------------------------------

  /// Checks the bot and channel. Returns an error message, or null when connected.
  Future<String?> connectTelegram(String token, String channel) async {
    final b = TelegramBot(token.trim());
    try {
      final me = await b.getMe();
      final chat = await b.getChat(channel);
      final id = (chat['id'] as num).toInt();
      try {
        final member = await b.call('getChatMember', {
          'chat_id': id,
          'user_id': me['id'],
        }) as Map;
        if (member['status'] != 'administrator' &&
            member['status'] != 'creator') {
          return 'Add @${me['username']} to the channel as an admin first.';
        }
      } on TelegramError {
        return 'Add @${me['username']} to the channel as an admin first.';
      }
      bot = b;
      TelegramFiles.bot = b;
      botUsername = me['username'] as String?;
      channelId = id;
      channelName = (chat['title'] as String?) ?? channel;
      channelTracks = [];
      _updateOffset = 0;
      _latestPostId = 0;
      tooBigSkipped = 0;
      await _save();
      _startSync();
      return null;
    } on TelegramError catch (e) {
      if (e.code == 401 || e.code == 404) {
        return 'That bot token isn\'t valid. Copy it again from @BotFather.';
      }
      if (e.description.contains('chat not found')) {
        return 'Channel not found. Use its @username, or add the bot to the private channel first.';
      }
      return e.description;
    } catch (_) {
      return 'Could not reach Telegram. Check your connection.';
    }
  }

  void _startSync() {
    TelegramFiles.bot = bot;
    _syncTimer?.cancel();
    _syncTimer = Timer.periodic(
      const Duration(minutes: 2),
      (_) => syncTelegram(),
    );
    syncTelegram();
  }

  /// Picks up new channel posts and songs forwarded to the bot.
  Future<void> syncTelegram() async {
    final b = bot;
    if (b == null || channelLoading) return;
    channelLoading = true;
    channelError = null;
    notifyListeners();
    try {
      while (true) {
        final updates = await b.getUpdates(_updateOffset);
        if (updates.isEmpty) break;
        for (final u in updates) {
          _updateOffset = (u['update_id'] as num).toInt() + 1;
          final post = (u['channel_post'] ?? u['edited_channel_post']) as Map?;
          final msg = u['message'] as Map?;
          if (post != null && (post['chat'] as Map)['id'] == channelId) {
            final id = (post['message_id'] as num).toInt();
            if (id > _latestPostId) _latestPostId = id;
            _addChannelMessage(post);
          } else if (msg != null && (msg['chat'] as Map)['type'] == 'private') {
            _userChatId = ((msg['chat'] as Map)['id'] as num).toInt();
            _addChannelMessage(msg); // songs forwarded to the bot
          }
        }
      }
      await _save();
    } on TelegramError catch (e) {
      channelError = e.code == 409
          ? 'Another app is reading this bot\'s updates. Use a bot just for Musicly.'
          : 'Telegram: ${e.description}';
    } catch (_) {
      channelError = 'Could not reach Telegram. Pull down to retry.';
    }
    channelLoading = false;
    notifyListeners();
  }

  /// Returns true if it was a new song.
  bool _addChannelMessage(Map m) {
    final t = TelegramBot.trackFrom(m, channelName ?? 'Telegram');
    if (t == null) {
      if (TelegramBot.isTooBig(m)) tooBigSkipped++;
      return false;
    }
    final i = channelTracks.indexWhere((x) => x.id == t.id);
    if (i >= 0) {
      channelTracks[i] = t;
      return false;
    }
    channelTracks.insert(0, t);
    return true;
  }

  /// Bots can't list a channel's history, so each older post is briefly
  /// forwarded to the user's private chat with the bot (silently), read, and
  /// deleted again.
  Future<void> importTelegramHistory() async {
    final b = bot;
    final to = _userChatId;
    if (b == null || to == null || importing) return;
    importing = true;
    _cancelImport = false;
    importScanned = 0;
    importFound = 0;
    notifyListeners();
    var misses = 0;
    // Without a known latest post, stop after a long run of missing ids.
    final last = _latestPostId;
    var id = 1;
    while (!_cancelImport) {
      if (last > 0 && id > last) break;
      if (last == 0 && misses >= 150) break;
      try {
        final fwd = await b.call('forwardMessage', {
          'chat_id': to,
          'from_chat_id': channelId,
          'message_id': id,
          'disable_notification': true,
        }) as Map;
        misses = 0;
        if (_addChannelMessage(fwd)) importFound++;
        try {
          await b.call('deleteMessage', {
            'chat_id': to,
            'message_id': fwd['message_id'],
          });
        } catch (_) {}
        await Future.delayed(const Duration(milliseconds: 350));
      } on TelegramError catch (e) {
        if (e.retryAfter != null) {
          await Future.delayed(Duration(seconds: e.retryAfter! + 1));
          continue; // same post again
        }
        misses++; // deleted post or a service message
      } catch (_) {
        await Future.delayed(const Duration(seconds: 2));
        continue;
      }
      importScanned = id;
      if (id % 10 == 0) notifyListeners();
      id++;
    }
    importing = false;
    await _save();
    notifyListeners();
  }

  void cancelImport() => _cancelImport = true;

  void disconnectTelegram() {
    _syncTimer?.cancel();
    _cancelImport = true;
    bot = null;
    TelegramFiles.bot = null;
    channelId = null;
    channelName = null;
    channelTracks = [];
    _save();
    notifyListeners();
  }

  // ---- Music on the device ----------------------------------------------------

  Future<bool> scanDevice({bool askPermission = true}) async {
    if (!DeviceLibrary.supported) return false;
    scanning = true;
    scanError = null;
    notifyListeners();
    try {
      final ok = askPermission ? await DeviceLibrary.requestPermission() : true;
      if (!ok) {
        scanError = 'Permission needed to read your music.';
        return false;
      }
      deviceTracks = await DeviceLibrary.scan();
      deviceScanEnabled = true;
      await _save();
      return true;
    } catch (_) {
      scanError = 'Could not read music on this phone.';
      return false;
    } finally {
      scanning = false;
      notifyListeners();
    }
  }

  /// Phone songs grouped by album: (album name, artist, tracks).
  List<(String, String, List<Track>)> get deviceAlbums {
    final map = <String, List<Track>>{};
    for (final t in deviceTracks) {
      map.putIfAbsent(t.albumId ?? t.album ?? '?', () => []).add(t);
    }
    final out =
        map.values
            .map((l) => (l.first.album ?? 'Unknown album', l.first.artist, l))
            .toList()
          ..sort((a, b) => a.$1.toLowerCase().compareTo(b.$1.toLowerCase()));
    return out;
  }

  Future<int> pickFiles() async {
    final picked = await FilePicker.pickFiles(type: FileType.audio);
    var added = 0;
    for (final f in picked) {
      final id = 'file:${kIsWeb ? '${f.name}:${f.lengthSync()}' : f.path}';
      if (files.any((t) => t.id == id)) continue;
      final (artist, title) = _splitName(f.name);
      files.add(
        Track(
          id: id,
          title: title,
          artist: artist,
          source: TrackSource.file,
          uri: kIsWeb ? null : f.path,
          bytes: kIsWeb ? await f.readAsBytes() : null,
        ),
      );
      added++;
    }
    await _save();
    notifyListeners();
    return added;
  }

  void removeFile(Track t) {
    files.removeWhere((x) => x.id == t.id);
    _save();
    notifyListeners();
  }

  /// "Artist - Title.mp3" → (Artist, Title).
  (String, String) _splitName(String n) {
    final dot = n.lastIndexOf('.');
    final base = (dot > 0 ? n.substring(0, dot) : n)
        .replaceAll('_', ' ')
        .trim();
    final dash = base.indexOf(' - ');
    if (dash > 0) {
      return (base.substring(0, dash).trim(), base.substring(dash + 3).trim());
    }
    return ('Unknown artist', base);
  }

  void toggleTheme() {
    themeMode = themeMode == ThemeMode.dark ? ThemeMode.light : ThemeMode.dark;
    _save();
    notifyListeners();
  }

  // ---- Persistence ---------------------------------------------------------------

  Future<void> _save() async {
    try {
      final p = await SharedPreferences.getInstance();
      String enc(List<Track> l) => jsonEncode(
        l.where((t) => t.isPersistable).map((t) => t.toJson()).toList(),
      );
      await p.setString('files', enc(files));
      await p.setString('favorites', enc(favorites));
      await p.setString('recent', enc(recent));
      await p.setString(
        'playlists',
        jsonEncode(playlists.map((x) => x.toJson()).toList()),
      );
      await p.setString(
        'following',
        jsonEncode(
          following
              .map((a) => {'id': a.id, 'name': a.name, 'pic': a.pictureUrl})
              .toList(),
        ),
      );
      await p.setString(
        'plays',
        jsonEncode({'plays': _plays, 'names': _artistNames}),
      );
      await p.setBool('deviceScan', deviceScanEnabled);
      if (bot == null) {
        await p.remove('telegram');
      } else {
        await p.setString(
          'telegram',
          jsonEncode({
            'token': bot!.token,
            'bot': botUsername,
            'channelId': channelId,
            'name': channelName,
            'offset': _updateOffset,
            'userChat': _userChatId,
            'latest': _latestPostId,
            'tooBig': tooBigSkipped,
            'tracks': channelTracks.map((t) => t.toJson()).toList(),
          }),
        );
      }
      await p.setString('theme', themeMode.name);
    } catch (_) {}
  }

  Future<void> _restore() async {
    try {
      final p = await SharedPreferences.getInstance();
      List<Track> dec(String k) =>
          ((jsonDecode(p.getString(k) ?? '[]')) as List)
              .map((e) => Track.fromJson(e as Map<String, dynamic>))
              .where((t) => t.id.contains(':'))
              .toList();
      files.addAll(dec('files'));
      // Older versions stored Audius songs; those no longer play.
      favorites.addAll(
        dec('favorites').where((t) => !t.id.startsWith('audius')),
      );
      recent.addAll(dec('recent').where((t) => !t.id.startsWith('audius')));
      playlists.addAll(
        ((jsonDecode(p.getString('playlists') ?? '[]')) as List).map(
          (e) => UserPlaylist.fromJson(e as Map<String, dynamic>),
        ),
      );
      following.addAll(
        ((jsonDecode(p.getString('following') ?? '[]')) as List).map(
          (e) => Artist(
            id: e['id'] as String,
            name: e['name'] as String,
            pictureUrl: e['pic'] as String?,
          ),
        ),
      );
      final plays =
          jsonDecode(p.getString('plays') ?? '{}') as Map<String, dynamic>;
      (plays['plays'] as Map? ?? {}).forEach(
        (k, v) => _plays[k as String] = (v as num).toInt(),
      );
      (plays['names'] as Map? ?? {}).forEach(
        (k, v) => _artistNames[k as String] = v as String,
      );
      deviceScanEnabled = p.getBool('deviceScan') ?? false;
      final tg = p.getString('telegram');
      if (tg != null) {
        final j = jsonDecode(tg) as Map<String, dynamic>;
        bot = TelegramBot(j['token'] as String);
        TelegramFiles.bot = bot;
        botUsername = j['bot'] as String?;
        channelId = (j['channelId'] as num?)?.toInt();
        channelName = j['name'] as String?;
        _updateOffset = (j['offset'] as num?)?.toInt() ?? 0;
        _userChatId = (j['userChat'] as num?)?.toInt();
        _latestPostId = (j['latest'] as num?)?.toInt() ?? 0;
        tooBigSkipped = (j['tooBig'] as num?)?.toInt() ?? 0;
        channelTracks = ((j['tracks'] as List?) ?? [])
            .map((e) => Track.fromJson(e as Map<String, dynamic>))
            .toList();
      }
      themeMode = p.getString('theme') == 'dark'
          ? ThemeMode.dark
          : ThemeMode.light;
      notifyListeners();
    } catch (_) {}
  }
}
