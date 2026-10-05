import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart' show ThemeMode;
import 'package:shared_preferences/shared_preferences.dart';

import '../models/collection.dart';
import '../models/track.dart';
import '../services/telegram_backup.dart';
import '../services/telegram_bot.dart';
import '../services/device_library.dart';
import '../services/radio_api.dart';

/// Everything the user owns or that's personalised: likes, playlists,
/// the Telegram channel, phone music, history, downloads and the home feed.
/// Every song here plays in full; there are no previews.
class LibraryController extends ChangeNotifier {
  LibraryController() {
    _restore().then((_) {
      loadHome();
      if (channelConnected) _startSync();
      if (deviceScanEnabled) scanDevice(askPermission: false);
    });
  }

  final RadioApi radio = RadioApi();

  ThemeMode themeMode = ThemeMode.light;

  final List<Track> favorites = [];
  final List<Track> recent = [];
  final List<Track> files = []; // picked with the file picker
  List<Track> deviceTracks = []; // scanned from the phone
  final List<UserPlaylist> playlists = [];

  /// Plays per song id and per artist name (lower-case), for "Your mix".
  final Map<String, int> _trackPlays = {};
  final Map<String, int> _artistPlays = {};

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

  // Channel songs saved to Music/Musicly on the phone
  bool autoDownload = true;
  final Map<String, Track> _downloads = {}; // channel track id -> phone copy
  bool downloading = false;
  int downloadDone = 0;
  int downloadTotal = 0;
  String? downloadError;

  // Backups: a pinned file in the bot chat, and a file in the phone's Download folder
  DateTime? lastBackup;
  DateTime? lastLocalBackup;
  bool backingUp = false;
  String? backupNote; // e.g. "Restored 120 songs and 4 playlists"
  int? _backupMsgId;
  Timer? _backupTimer;
  bool _restoreChecked = false;
  bool get backupReady => bot != null && _userChatId != null;

  /// Lets the player reload its sound settings after a restore.
  VoidCallback? onRestored;

  bool deviceScanEnabled = false;
  bool scanning = false;
  String? scanError;

  // Home feed
  List<Track> forYou = [];
  String? forYouReason;
  List<Track> stations = []; // live radio, local first
  String? countryCode;

  bool isFavorite(Track t) => favorites.any((f) => f.id == t.id);

  List<Track> get likedSongs => favorites.where((t) => !t.isRadio).toList();
  List<Track> get savedStations => favorites.where((t) => t.isRadio).toList();

  /// Phone songs, minus the copies Musicly downloaded from the channel
  /// (those show up as channel songs instead).
  List<Track> get localTracks {
    final copies = {for (final d in _downloads.values) d.id};
    return [...deviceTracks.where((t) => !copies.contains(t.id)), ...files];
  }

  /// Every full song the user has: channel first, then phone.
  List<Track> get allSongs => [...channelTracks, ...localTracks];

  /// The phone copy of a channel song, when it has been downloaded.
  Track? downloadedCopy(Track t) => _downloads[t.id];
  bool isDownloaded(Track t) => _downloads.containsKey(t.id);

  // ---- History & recommendations -----------------------------------------

  void recordPlay(Track t) {
    recent.removeWhere((r) => r.id == t.id);
    recent.insert(0, t);
    if (recent.length > 50) recent.removeLast();
    if (t.isRadio) {
      radio.countClick(t.id);
    } else {
      _trackPlays[t.id] = (_trackPlays[t.id] ?? 0) + 1;
      final a = t.artist.toLowerCase();
      if (a.isNotEmpty && a != 'unknown artist') {
        _artistPlays[a] = (_artistPlays[a] ?? 0) + 1;
      }
    }
    _save();
    notifyListeners();
  }

  /// Artist names ranked by plays and likes.
  List<String> topArtists([int n = 5]) {
    final score = <String, double>{};
    final display = <String, String>{};
    for (final t in allSongs) {
      display[t.artist.toLowerCase()] = t.artist;
    }
    _artistPlays.forEach((a, c) => score[a] = (score[a] ?? 0) + c);
    for (final f in likedSongs) {
      final a = f.artist.toLowerCase();
      score[a] = (score[a] ?? 0) + 3;
    }
    score.removeWhere(
      (a, _) => a == 'unknown artist' || !display.containsKey(a),
    );
    final ranked = score.keys.toList()
      ..sort((a, b) => score[b]!.compareTo(score[a]!));
    return ranked.take(n).map((a) => display[a]!).toList();
  }

  /// Songs ordered by how much you play them.
  List<Track> get mostPlayed {
    final songs = allSongs.where((t) => (_trackPlays[t.id] ?? 0) > 0).toList()
      ..sort(
        (a, b) => (_trackPlays[b.id] ?? 0).compareTo(_trackPlays[a.id] ?? 0),
      );
    return songs;
  }

  int plays(Track t) => _trackPlays[t.id] ?? 0;

  /// Your artists (channel + phone), most songs first.
  List<(String, List<Track>)> get artists {
    final map = <String, List<Track>>{};
    final names = <String, String>{};
    for (final t in allSongs) {
      final k = t.artist.toLowerCase();
      if (k == 'unknown artist') continue;
      names[k] ??= t.artist;
      map.putIfAbsent(k, () => []).add(t);
    }
    final out = [for (final e in map.entries) (names[e.key]!, e.value)]
      ..sort((a, b) => b.$2.length.compareTo(a.$2.length));
    return out;
  }

  List<Track> songsBy(String artist) {
    final k = artist.toLowerCase();
    return allSongs.where((t) => t.artist.toLowerCase() == k).toList();
  }

  /// "Your mix": a fresh blend of your own channel and phone songs, weighted
  /// toward what you like and play, mixed with songs you haven't heard lately.
  void buildForYou() {
    final pool = allSongs;
    if (pool.isEmpty) {
      forYou = [];
      forYouReason = null;
      return;
    }
    final rng = Random(DateTime.now().day * 31 + DateTime.now().hour ~/ 6);
    final top = topArtists(3).map((a) => a.toLowerCase()).toSet();
    final liked = {for (final t in likedSongs) t.id};
    final lately = {for (final t in recent.take(8)) t.id};
    double weight(Track t) {
      var w = 1.0;
      if (liked.contains(t.id)) w += 3;
      if (top.contains(t.artist.toLowerCase())) w += 2;
      w += min(_trackPlays[t.id] ?? 0, 10) * 0.3;
      if ((_trackPlays[t.id] ?? 0) == 0) w += 0.8; // something new
      if (lately.contains(t.id)) w *= 0.2; // just heard it
      return w;
    }

    // Weighted shuffle (Efraimidis–Spirakis): higher weight, earlier.
    final keyed = [
      for (final t in pool)
        (pow(rng.nextDouble(), 1 / weight(t)).toDouble(), t),
    ]..sort((a, b) => b.$1.compareTo(a.$1));
    // Avoid the same artist twice in a row where possible.
    final mix = <Track>[];
    final rest = keyed.map((e) => e.$2).toList();
    while (rest.isNotEmpty && mix.length < 40) {
      final i = rest.indexWhere(
        (t) =>
            mix.isEmpty ||
            t.artist.toLowerCase() != mix.last.artist.toLowerCase(),
      );
      mix.add(rest.removeAt(i < 0 ? 0 : i));
    }
    forYou = mix;
    final names = topArtists(2);
    final sources = [
      if (channelTracks.isNotEmpty) 'your channel',
      if (localTracks.isNotEmpty) 'your phone',
    ].join(' & ');
    forYouReason = names.isEmpty
        ? 'A mix from $sources'
        : 'From $sources · ${names.join(' & ')} and more';
  }

  Future<void> loadHome() async {
    buildForYou();
    notifyListeners();
    countryCode ??= PlatformDispatcher.instance.locale.countryCode;
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
    notifyListeners();
  }

  // ---- Likes ------------------------------------------------------------------

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
            _userChatId ??= ((msg['chat'] as Map)['id'] as num).toInt();
            _addChannelMessage(msg); // songs forwarded to the bot
          }
        }
      }
      await _save();
      if (_userChatId != null && !_restoreChecked) {
        _restoreChecked = true;
        await restoreFromTelegram(onlyIfFresh: true);
      }
      buildForYou();
      if (autoDownload && canDownload && notDownloaded > 0) {
        unawaited(downloadSongs(channelTracks));
      }
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
    buildForYou();
    await _save();
    notifyListeners();
    if (autoDownload && canDownload && notDownloaded > 0) {
      unawaited(downloadSongs(channelTracks));
    }
  }

  void cancelImport() => _cancelImport = true;

  bool get _isFresh =>
      favorites.isEmpty && playlists.isEmpty && recent.length < 3;

  void _scheduleBackup() {
    if (!backupReady && !DeviceLibrary.supported) return;
    _backupTimer?.cancel();
    _backupTimer = Timer(const Duration(seconds: 20), backupNow);
  }

  /// Called when the app goes to the background: save pending changes now.
  void flushBackup() {
    if (_backupTimer?.isActive ?? false) backupNow();
  }

  /// Saves the library to Download/Musicly on the phone, and to the pinned
  /// backup file in the bot chat when Telegram is set up.
  Future<bool> backupNow() async {
    if (backingUp) return false;
    _backupTimer?.cancel();
    backingUp = true;
    notifyListeners();
    var ok = false;
    try {
      final snap = await TelegramBackup.snapshot();
      final p = await SharedPreferences.getInstance();
      if (await DeviceLibrary.saveBackupFile(jsonEncode(snap))) {
        lastLocalBackup = DateTime.now();
        await p.setString(
          'local_backup_at',
          lastLocalBackup!.toIso8601String(),
        );
        ok = true;
      }
      final b = bot, chat = _userChatId;
      if (b != null && chat != null) {
        _backupMsgId = await TelegramBackup(
          b,
          chat,
        ).upload(snap, existing: _backupMsgId);
        lastBackup = DateTime.now();
        await p.setInt('backup_msg', _backupMsgId!);
        await p.setString('backup_at', lastBackup!.toIso8601String());
        ok = true;
      }
    } catch (_) {
    } finally {
      backingUp = false;
      notifyListeners();
    }
    return ok;
  }

  /// Restores from a musicly-backup.json the user picks (e.g. from
  /// Download/Musicly after reinstalling). Returns an error message or null.
  Future<String?> restoreFromFile() async {
    final picked = await FilePicker.pickFiles(type: FileType.any);
    if (picked.isEmpty) return 'No file chosen.';
    try {
      final j = jsonDecode(
        utf8.decode(await picked.first.readAsBytes()),
      ) as Map<String, dynamic>;
      if (j['app'] != 'musicly') return 'That isn\'t a Musicly backup file.';
      await _applyBackup(j);
      return null;
    } catch (_) {
      return 'Could not read that file.';
    }
  }

  Future<void> _applyBackup(Map<String, dynamic> backup) async {
    await TelegramBackup.apply(backup);
    await _reloadFromPrefs();
    onRestored?.call();
    String n(int c, String w) => '$c ${c == 1 ? w : '${w}s'}';
    backupNote =
        'Restored ${n(likedSongs.length, 'liked song')}, ${n(playlists.length, 'playlist')} and ${n(channelTracks.length, 'channel song')}';
    restoreDismissed = true;
    notifyListeners();
  }

  /// Hides the "restore your library" card on a fresh install.
  bool restoreDismissed = false;
  bool get showRestoreCard =>
      !restoreDismissed && _isFresh && lastLocalBackup == null;
  void dismissRestore() {
    restoreDismissed = true;
    notifyListeners();
  }

  /// Brings back a backup from the bot chat. With [onlyIfFresh], only on a
  /// new install, so it never overwrites a library that's in use.
  Future<bool> restoreFromTelegram({bool onlyIfFresh = false}) async {
    final b = bot, chat = _userChatId;
    if (b == null || chat == null) return false;
    if (onlyIfFresh && !_isFresh) return false;
    try {
      final backup = await TelegramBackup(b, chat).latest();
      if (backup == null) return false;
      _backupMsgId = (backup['_messageId'] as num?)?.toInt();
      await _applyBackup(backup);
      return true;
    } catch (_) {
      return false;
    }
  }

  Future<void> _reloadFromPrefs() async {
    final keepBot = bot, keepChat = _userChatId;
    favorites.clear();
    recent.clear();
    files.clear();
    playlists.clear();
    _trackPlays.clear();
    _artistPlays.clear();
    _downloads.clear();
    await _restore();
    bot ??= keepBot;
    _userChatId ??= keepChat;
    TelegramFiles.bot = bot;
    await _save();
    loadHome();
  }

  void disconnectTelegram() {
    _syncTimer?.cancel();
    // Songs already saved stay on the phone and show up as phone music.
    _downloads.clear();
    if (deviceScanEnabled) scanDevice(askPermission: false);
    _backupTimer?.cancel();
    _cancelImport = true;
    bot = null;
    TelegramFiles.bot = null;
    channelId = null;
    channelName = null;
    channelTracks = [];
    _save();
    notifyListeners();
  }

  // ---- Saving channel songs to the phone ---------------------------------------

  bool get canDownload => DeviceLibrary.supported && channelConnected;
  int get notDownloaded =>
      channelTracks.where((t) => !_downloads.containsKey(t.id)).length;

  /// "Artist - Title.mp3", safe for every file system.
  static String _fileName(Track t) {
    final ext = switch (t.mime) {
      'audio/mp4' || 'audio/x-m4a' || 'audio/m4a' || 'audio/aac' => 'm4a',
      'audio/flac' || 'audio/x-flac' => 'flac',
      'audio/ogg' || 'audio/opus' => 'ogg',
      'audio/wav' || 'audio/x-wav' => 'wav',
      _ => 'mp3',
    };
    final base =
        (t.artist == 'Unknown artist' ? t.title : '${t.artist} - ${t.title}')
            .replaceAll(RegExp(r'[\\/:*?"<>|\x00-\x1f]'), '')
            .trim();
    return '${base.isEmpty ? t.id.substring(3) : base.substring(0, min(base.length, 120))}.$ext';
  }

  void setAutoDownload(bool on) {
    autoDownload = on;
    _save();
    notifyListeners();
    if (on) downloadSongs(channelTracks);
  }

  /// Saves channel songs into Music/Musicly, one at a time. Songs already
  /// there (e.g. from before a reinstall) are linked instead of downloaded.
  Future<void> downloadSongs(List<Track> tracks) async {
    final b = bot;
    if (!canDownload || b == null) return;
    final todo = tracks
        .where(
          (t) =>
              t.source == TrackSource.telegram && !_downloads.containsKey(t.id),
        )
        .toList();
    if (todo.isEmpty) return;
    if (downloading) {
      _pendingDownloads.addAll(todo);
      return;
    }
    if (!await DeviceLibrary.requestPermission()) {
      downloadError = 'Allow access to music to save songs on this phone.';
      notifyListeners();
      return;
    }
    downloading = true;
    downloadError = null;
    downloadDone = 0;
    downloadTotal = todo.length;
    notifyListeners();
    var failed = 0;
    for (var i = 0; i < todo.length; i++) {
      final t = todo[i];
      if (bot == null) break; // disconnected
      if (_downloads.containsKey(t.id)) {
        downloadDone++;
        continue;
      }
      try {
        final name = _fileName(t);
        final copy =
            await DeviceLibrary.findAudio(name, t) ??
            await DeviceLibrary.saveAudio(
              url: await b.fileUrl(t.uri!),
              fileName: name,
              like: t,
              mime: t.mime ?? 'audio/mpeg',
            );
        if (copy != null) _downloads[t.id] = copy;
      } catch (_) {
        failed++;
      }
      downloadDone++;
      if (i % 3 == 0 || i == todo.length - 1) {
        notifyListeners();
        await _saveDownloads();
      }
      if (_pendingDownloads.isNotEmpty) {
        todo.addAll(_pendingDownloads.where((x) => !todo.contains(x)));
        downloadTotal = todo.length;
        _pendingDownloads.clear();
      }
    }
    downloading = false;
    if (failed > 0) {
      downloadError =
          '${count(failed, 'song')} couldn\'t be saved. They\'ll be retried next sync.';
    }
    await _save();
    notifyListeners();
  }

  final List<Track> _pendingDownloads = [];

  Future<void> _saveDownloads() async {
    try {
      final p = await SharedPreferences.getInstance();
      await p.setString(
        'downloads',
        jsonEncode({
          for (final e in _downloads.entries) e.key: e.value.toJson(),
        }),
      );
    } catch (_) {}
  }

  static String count(int n, String word) => '$n ${n == 1 ? word : '${word}s'}';

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
      buildForYou();
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
        'plays',
        jsonEncode({'tracks': _trackPlays, 'artists': _artistPlays}),
      );
      await p.setString(
        'downloads',
        jsonEncode({
          for (final e in _downloads.entries) e.key: e.value.toJson(),
        }),
      );
      await p.setBool('autoDownload', autoDownload);
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
    _scheduleBackup();
  }

  Future<void> _restore() async {
    try {
      final p = await SharedPreferences.getInstance();
      _backupMsgId = p.getInt('backup_msg');
      lastBackup = DateTime.tryParse(p.getString('backup_at') ?? '');
      lastLocalBackup = DateTime.tryParse(p.getString('local_backup_at') ?? '');
      // Songs from sources Musicly no longer has (old previews) are dropped.
      List<Track> dec(String k) => [
        for (final e in (jsonDecode(p.getString(k) ?? '[]')) as List)
          ?Track.tryFromJson(e as Map<String, dynamic>),
      ];
      files.addAll(dec('files'));
      favorites.addAll(dec('favorites'));
      recent.addAll(dec('recent'));
      playlists.addAll(
        ((jsonDecode(p.getString('playlists') ?? '[]')) as List).map(
          (e) => UserPlaylist.fromJson(e as Map<String, dynamic>),
        ),
      );
      final plays =
          jsonDecode(p.getString('plays') ?? '{}') as Map<String, dynamic>;
      (plays['tracks'] as Map? ?? {}).forEach(
        (k, v) => _trackPlays[k as String] = (v as num).toInt(),
      );
      (plays['artists'] as Map? ?? {}).forEach(
        (k, v) => _artistPlays[k as String] = (v as num).toInt(),
      );
      final dl =
          jsonDecode(p.getString('downloads') ?? '{}') as Map<String, dynamic>;
      dl.forEach((k, v) {
        final t = Track.tryFromJson(v as Map<String, dynamic>);
        if (t != null) _downloads[k] = t;
      });
      autoDownload = p.getBool('autoDownload') ?? true;
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
        channelTracks = [
          for (final e in (j['tracks'] as List?) ?? [])
            ?Track.tryFromJson(e as Map<String, dynamic>),
        ];
      }
      themeMode = p.getString('theme') == 'dark'
          ? ThemeMode.dark
          : ThemeMode.light;
      notifyListeners();
    } catch (_) {}
  }
}
