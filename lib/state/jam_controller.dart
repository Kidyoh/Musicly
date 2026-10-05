import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/track.dart';
import '../services/device_library.dart';
import '../services/jam_guest.dart';
import '../services/jam_host.dart';
import '../services/jam_online.dart';
import '../services/jam_protocol.dart';
import '../services/jam_session.dart';
import '../services/telegram_bot.dart';
import 'library_controller.dart';
import 'player_controller.dart';

enum JamRole { none, host, guest }

/// Listening together.
///
/// In the same room, the host's phone plays and friends on the same Wi-Fi
/// add songs. Online, friends anywhere hear the host's songs on their own
/// phones, in step: messages go through the ntfy.sh relay and songs are
/// shared as temporary links.
class JamController extends ChangeNotifier {
  JamController(this.library, this.player) {
    _loadName();
  }

  final LibraryController library;
  final PlayerController player;

  static bool get supported => !kIsWeb;

  JamRole role = JamRole.none;
  bool get active => role != JamRole.none;

  /// An online Jam (rather than one in the same room).
  bool online = false;

  /// What friends see you as.
  String myName = '';
  bool get hasName => myName.isNotEmpty;

  Future<void> _loadName() async {
    try {
      final p = await SharedPreferences.getInstance();
      myName = p.getString('jam_name') ?? '';
      notifyListeners();
    } catch (_) {}
  }

  Future<void> setName(String name) async {
    myName = name.trim();
    notifyListeners();
    try {
      final p = await SharedPreferences.getInstance();
      await p.setString('jam_name', myName);
    } catch (_) {}
  }

  /// The latest thing that happened, e.g. "Abel added Blinding Lights".
  String? event;
  String? error;

  // ---- Hosting -----------------------------------------------------------

  JamHosting? _host;

  /// Same room: the address code. Online: the Jam's code.
  String? joinCode;
  Timer? _tick;
  Timer? _debounce;
  bool _guestsControl = false;

  /// Who added which song (track id -> name), for "Added by".
  final Map<String, String> _addedBy = {};
  int _uploads = 0;

  bool get guestsControl => _guestsControl;
  String? addedBy(Track t) => _addedBy[t.id];
  List<JamPerson> get guests => _host?.guests ?? const [];
  String get jamName => role == JamRole.guest
      ? (_session?.jamName ?? 'Jam')
      : hasName
      ? '$myName\'s Jam'
      : 'My Jam';

  JamHostCallbacks get _callbacks => JamHostCallbacks(
    state: _hostState,
    onAdd: _guestAdded,
    onUpload: _guestUploaded,
    onControl: _guestControl,
    search: _search,
    art: _artFor,
  );

  /// Starts a Jam in this room, or online for friends anywhere.
  Future<bool> startHosting({bool online = false}) async {
    if (active) return false;
    error = null;
    this.online = online;
    try {
      final tmp = await getTemporaryDirectory();
      // Songs friends sent to an earlier Jam aren't needed any more.
      for (final old in tmp.listSync().whereType<Directory>()) {
        if (old.path.split('/').last.startsWith('jam-')) {
          try {
            old.deleteSync(recursive: true);
          } catch (_) {}
        }
      }
      if (online) {
        final code = OnlineJamCode.create();
        final h = OnlineJamHost(
          code: code,
          jamName: jamName,
          hostName: hasName ? myName : 'Host',
          app: _callbacks,
        )..onPeopleChanged = _peopleChanged;
        _host = h;
        joinCode = code;
        role = JamRole.host;
        notifyListeners();
        await h.start();
        unawaited(_shareLibrary());
        unawaited(_shareUpcoming());
      } else {
        final h = JamHost(
          jamName: jamName,
          hostName: hasName ? myName : 'Host',
          uploadsDir: Directory(
            '${tmp.path}/jam-${DateTime.now().millisecondsSinceEpoch}',
          ),
          app: _callbacks,
        )..onPeopleChanged = _peopleChanged;
        await h.start();
        _host = h;
        final ip = await JamHost.localAddress();
        joinCode = ip == null ? null : JamCode.encode(ip, h.port);
        role = JamRole.host;
        // Keeps everyone's progress bar honest even when nothing changes.
        _tick = Timer.periodic(
          const Duration(seconds: 5),
          (_) => h.pushState(),
        );
      }
      player.addListener(_playerChanged);
      notifyListeners();
      return true;
    } catch (_) {
      role = JamRole.none;
      await _endHosting();
      error = online
          ? 'Could not start an online Jam. Check your internet connection.'
          : 'Could not start a Jam. Check that Wi-Fi or your hotspot is on.';
      notifyListeners();
      return false;
    }
  }

  void setGuestsControl(bool on) {
    _guestsControl = on;
    _host?.guestsControl = on;
    _host?.pushState();
    notifyListeners();
  }

  void removeGuest(String id) => _host?.remove(id);

  void _peopleChanged() => notifyListeners();

  void _playerChanged() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 250), () {
      if (online) unawaited(_shareUpcoming());
      _host?.pushState();
    });
  }

  List<Track> get _upcoming {
    final order = player.player.effectiveIndices;
    final at = order.indexOf(player.currentIndex);
    return [
      if (at >= 0)
        for (final i in order.skip(at + 1).take(30))
          if (i < player.queue.length) player.queue[i],
    ];
  }

  JamTrack _jamTrack(Track t) {
    final link = _links[t.id];
    return JamTrack(
      id: t.id,
      title: t.title,
      artist: t.artist,
      durationMs: t.duration?.inMilliseconds,
      by: _addedBy[t.id],
      hasArt:
          t.artworkUrl != null ||
          t.mediaId != null ||
          library.downloadedCopy(t)?.mediaId != null,
      url: link?.$1,
      artUrl: link?.$2,
    );
  }

  JamState _hostState() {
    final now = player.current;
    return JamState(
      now: now == null ? null : _jamTrack(now),
      playing: player.isPlaying,
      positionMs: player.player.position.inMilliseconds,
      at: DateTime.now().millisecondsSinceEpoch,
      queue: _upcoming.map(_jamTrack).toList(),
      guestsControl: _guestsControl,
      event: event,
      library: _libraryLink,
    );
  }

  List<JamTrack> _search(String q) {
    final query = q.trim().toLowerCase();
    final songs = query.isEmpty
        ? [...library.forYou, ...library.allSongs]
        : library.allSongs.where(
            (t) =>
                t.title.toLowerCase().contains(query) ||
                t.artist.toLowerCase().contains(query),
          );
    final seen = <String>{};
    return [
      for (final t in songs)
        if (seen.add(t.id)) _jamTrack(t),
    ];
  }

  Track? _find(String id) {
    for (final t in player.queue) {
      if (t.id == id) return t;
    }
    for (final t in library.allSongs) {
      if (t.id == id) return t;
    }
    return null;
  }

  Future<void> _queue(Track t, bool next, String by) async {
    _addedBy[t.id] = by;
    if (player.current == null) {
      await player.playQueue([t], 0);
    } else if (next) {
      await player.playNext(t);
    } else {
      await player.addToQueue([t]);
    }
    _announce('$by added ${t.title}');
  }

  Future<void> _guestAdded(String id, JamPerson by, bool next) async {
    final t = _find(id);
    if (t != null) await _queue(t, next, by.name);
  }

  Future<void> _guestUploaded(JamUpload song, JamPerson by, bool next) {
    final t = Track(
      id: 'jam:${_uploads++}:${song.title}',
      title: song.title,
      artist: song.artist,
      source: TrackSource.file,
      uri: song.source,
      artworkUrl: song.art,
      duration: song.durationMs == null
          ? null
          : Duration(milliseconds: song.durationMs!),
      album: 'From ${by.name} in the Jam',
    );
    // Online, the friend's link can be passed on as it is.
    if (song.source.startsWith('http')) {
      _links[t.id] = (song.source, song.art, DateTime.now());
    }
    return _queue(t, next, by.name);
  }

  void _guestControl(String action, int? ms, JamPerson by) {
    switch (action) {
      case 'toggle':
        player.togglePlay();
      case 'next':
        player.next();
        _announce('${by.name} skipped');
      case 'previous':
        player.previous();
      case 'seek':
        if (ms != null) player.seek(Duration(milliseconds: ms));
    }
  }

  void _announce(String what) {
    event = what;
    notifyListeners();
    _host?.pushState();
  }

  final Map<String, List<int>> _artCache = {};

  Future<List<int>?> _artFor(String id) async {
    final hit = _artCache[id];
    if (hit != null) return hit;
    final t = _find(id);
    if (t == null) return null;
    final bytes = await _coverBytes(t);
    if (bytes != null && bytes.isNotEmpty) {
      if (_artCache.length > 120) _artCache.remove(_artCache.keys.first);
      _artCache[id] = bytes;
    }
    return bytes;
  }

  Future<List<int>?> _coverBytes(Track t) async {
    try {
      final media = t.mediaId ?? library.downloadedCopy(t)?.mediaId;
      final url = t.artworkUrl;
      if (url != null && url.startsWith('/')) {
        return await File(url).readAsBytes();
      }
      if (url != null && TelegramFiles.isThumb(url)) {
        final link = await TelegramFiles.resolveThumb(url);
        if (link != null) return (await http.get(Uri.parse(link))).bodyBytes;
      } else if (url != null && url.startsWith('http')) {
        return (await http.get(Uri.parse(url))).bodyBytes;
      } else if (media != null) {
        return await DeviceLibrary.artwork(media);
      }
    } catch (_) {}
    return null;
  }

  // ---- Sharing songs in an online Jam ----------------------------------------

  /// Track id -> (song link, cover link, when shared).
  final Map<String, (String, String?, DateTime)> _links = {};
  String? _libraryLink;
  bool _sharing = false;

  /// The song being uploaded for friends right now, for the host's screen.
  String? sharingTitle;

  /// Uploads the current and next song, so friends can play them.
  Future<void> _shareUpcoming() async {
    if (_sharing || !online || role != JamRole.host) return;
    _sharing = true;
    try {
      final todo = [?player.current, ..._upcoming.take(1)];
      for (final t in todo) {
        final link = _links[t.id];
        // Links last 12 hours; share again well before then.
        if (link != null && DateTime.now().difference(link.$3).inHours < 10) {
          continue;
        }
        if (t.isRadio) continue;
        sharingTitle = t.title;
        notifyListeners();
        try {
          final (file, ext) = await _fileFor(t);
          final cover = await _coverBytes(t);
          String? art;
          if (cover != null && cover.isNotEmpty) {
            try {
              art = await FileDrop.upload(bytes: cover, name: 'cover.jpg');
            } catch (_) {}
          }
          final url = await FileDrop.upload(file: file, name: 'song.$ext');
          if (role != JamRole.host) return;
          _links[t.id] = (url, art, DateTime.now());
          _host?.pushState();
        } catch (_) {
          // Tried again on the next change.
        }
      }
    } finally {
      _sharing = false;
      sharingTitle = null;
      if (active) notifyListeners();
    }
    // The song may have changed while uploading.
    final now = player.current;
    if (role == JamRole.host &&
        online &&
        now != null &&
        !now.isRadio &&
        !_links.containsKey(now.id)) {
      unawaited(_shareUpcoming());
    }
  }

  /// Uploads the host's song list so friends can browse it.
  Future<void> _shareLibrary() async {
    try {
      final url = await FileDrop.upload(
        bytes: utf8.encode(OnlineJamGuest.libraryJson(_search(''))),
        name: 'library.json',
      );
      if (role != JamRole.host) return;
      _libraryLink = url;
      _host?.pushState();
    } catch (_) {}
  }

  // ---- Joining -----------------------------------------------------------

  JamSession? _session;
  JamState get state => _session?.state ?? const JamState();
  String get hostName => _session?.hostName ?? '';
  Duration get position => _session?.position ?? Duration.zero;
  StreamSubscription<JamState>? _stateSub;

  /// Jams seen on this Wi-Fi recently, while [startLooking] runs.
  final Map<String, JamFound> nearby = {};
  StreamSubscription<JamFound>? _looking;
  Timer? _prune;
  bool lookingFailed = false;

  void startLooking() {
    if (_looking != null || !supported) return;
    lookingFailed = false;
    DeviceLibrary.multicastLock(true);
    _looking = JamGuest.discover().listen(
      (f) {
        final isNew = !nearby.containsKey(f.key);
        nearby[f.key] = f;
        if (isNew) notifyListeners();
      },
      onError: (_) {
        lookingFailed = true;
        notifyListeners();
      },
    );
    _prune = Timer.periodic(const Duration(seconds: 2), (_) {
      final before = nearby.length;
      nearby.removeWhere(
        (_, f) => DateTime.now().difference(f.seen).inSeconds > 7,
      );
      if (nearby.length != before) notifyListeners();
    });
  }

  void stopLooking() {
    _looking?.cancel();
    _looking = null;
    _prune?.cancel();
    nearby.clear();
    DeviceLibrary.multicastLock(false);
  }

  /// Joins a Jam found on this Wi-Fi. Returns an error message or null.
  Future<String?> join(String address, int port) async {
    if (active) return 'You\'re already in a Jam.';
    final g = JamGuest(address, port);
    try {
      await g.join(hasName ? myName : 'Guest');
    } catch (_) {
      return 'Could not reach that Jam. Make sure you\'re on the same Wi-Fi.';
    }
    _joined(g, isOnline: false);
    return null;
  }

  /// Joins with a code: an online Jam's code, or a same-room address code.
  Future<String?> joinWithCode(String code) async {
    final onlineCode = OnlineJamCode.normalise(code);
    if (onlineCode != null) {
      if (active) return 'You\'re already in a Jam.';
      final g = OnlineJamGuest(onlineCode);
      try {
        await g.join(hasName ? myName : 'Guest');
      } on JamError catch (e) {
        return e.message;
      } catch (_) {
        return 'Could not reach the Jam. Check your internet connection.';
      }
      _joined(g, isOnline: true);
      return null;
    }
    final at = JamCode.decode(code);
    if (at == null) return 'That code doesn\'t look right.';
    return join(at.$1, at.$2);
  }

  void _joined(JamSession g, {required bool isOnline}) {
    stopLooking();
    _session = g;
    online = isOnline;
    role = JamRole.guest;
    error = null;
    event = g.state.event; // the first update can arrive before we listen
    listenHere = isOnline;
    _stateSub = g.states.listen((s) {
      if (s.event != null) event = s.event;
      notifyListeners();
      _follow();
    });
    g.closed.then((reason) {
      if (_session != g) return;
      _stopFollowing();
      _clearGuest();
      error = switch (reason) {
        'ended' => 'The host ended the Jam.',
        'removed' => 'The host removed you from the Jam.',
        'lost' => 'Lost connection to the Jam.',
        _ => null,
      };
      notifyListeners();
    });
    if (isOnline) {
      _followTimer = Timer.periodic(
        const Duration(seconds: 4),
        (_) => _follow(),
      );
      _follow();
    }
    notifyListeners();
  }

  // ---- Listening along (online) ------------------------------------------

  /// Plays the host's songs on this phone, in step with the host.
  bool listenHere = false;
  String? _followingId;
  bool _loading = false;
  Timer? _followTimer;

  /// The host's song is still being shared.
  bool get waitingForSong =>
      online &&
      role == JamRole.guest &&
      state.now != null &&
      state.now!.url == null;

  void setListenHere(bool on) {
    listenHere = on;
    if (on) {
      _followingId = null;
      if (_followTimer == null && online) {
        _followTimer = Timer.periodic(
          const Duration(seconds: 4),
          (_) => _follow(),
        );
      }
      _follow();
    } else {
      _followTimer?.cancel();
      _followTimer = null;
      if (_followingId != null && (player.current?.isJam ?? false)) {
        player.player.pause();
      }
      _followingId = null;
    }
    notifyListeners();
  }

  Track _followTrack(JamTrack t) => Track(
    id: 'jam:online:${t.id}',
    title: t.title,
    artist: t.artist,
    source: TrackSource.file,
    uri: t.url,
    artworkUrl: t.artUrl,
    duration: t.durationMs == null
        ? null
        : Duration(milliseconds: t.durationMs!),
    album: '${_session?.jamName ?? 'Jam'} with ${_session?.hostName}',
  );

  Future<void> _follow() async {
    final s = _session;
    if (s == null || !online || !listenHere || _loading) return;
    final now = s.state.now;
    final mine = player.current;
    // Playing something else on this phone means "stop listening along".
    if (_followingId != null && mine != null && !mine.isJam) {
      listenHere = false;
      _followingId = null;
      notifyListeners();
      return;
    }
    if (now == null || now.url == null) {
      if (_followingId != null && player.isPlaying) await player.player.pause();
      return;
    }
    _loading = true;
    try {
      if (_followingId != now.id) {
        _followingId = now.id;
        await player.playQueue([_followTrack(now)], 0);
        if (!s.state.playing) await player.player.pause();
        await player.seek(s.position + const Duration(milliseconds: 300));
        return;
      }
      if (s.state.playing && !player.isPlaying) {
        await player.seek(s.position);
        await player.player.play();
      } else if (!s.state.playing && player.isPlaying) {
        await player.player.pause();
      } else if (s.state.playing) {
        final drift = (player.player.position - s.position).inMilliseconds;
        if (drift.abs() > 1500) await player.seek(s.position);
      }
    } catch (_) {
    } finally {
      _loading = false;
    }
  }

  void _stopFollowing() {
    _followTimer?.cancel();
    _followTimer = null;
    if (_followingId != null && (player.current?.isJam ?? false)) {
      player.player.pause();
    }
    _followingId = null;
    listenHere = false;
  }

  // ---- Adding songs as a guest ---------------------------------------------

  /// A song in the Jam as a [Track], for showing it with the usual widgets.
  Track viewTrack(JamTrack t) => Track(
    id: 'jamview:${t.id}',
    title: t.title,
    artist: t.artist,
    source: TrackSource.file,
    duration: t.durationMs == null
        ? null
        : Duration(milliseconds: t.durationMs!),
    artworkUrl: _session?.artFor(t),
  );

  Future<List<JamTrack>> searchHost(String q) =>
      _session?.search(q) ?? Future.value(const []);

  void addFromHost(JamTrack t, {bool next = false}) =>
      _session?.add(t.id, next: next);

  void control(String action, [int? ms]) => _session?.control(action, ms);

  /// Songs being sent right now (track id -> progress 0..1).
  final Map<String, double> sending = {};

  /// Sends one of my songs to the Jam. Returns an error message or null.
  Future<String?> send(Track t, {bool next = false}) async {
    final g = _session;
    if (g == null || sending.containsKey(t.id)) return null;
    sending[t.id] = 0;
    notifyListeners();
    try {
      final (file, ext) = await _fileFor(t);
      await g.sendSong(
        audio: file,
        ext: ext,
        title: t.title,
        artist: t.artist,
        art: await _coverBytes(t),
        durationMs: t.duration?.inMilliseconds,
        next: next,
        onProgress: (p) {
          sending[t.id] = p;
          notifyListeners();
        },
      );
      return null;
    } on JamError catch (e) {
      return e.message;
    } catch (_) {
      return 'Could not send ${t.title}.';
    } finally {
      sending.remove(t.id);
      notifyListeners();
    }
  }

  /// A local file with the song's audio, fetching or copying it if needed.
  Future<(File, String)> _fileFor(Track t) async {
    String extOf(String? mime, String fallback) => switch (mime) {
      'audio/mp4' || 'audio/x-m4a' || 'audio/m4a' || 'audio/aac' => 'm4a',
      'audio/flac' || 'audio/x-flac' => 'flac',
      'audio/ogg' || 'audio/opus' => 'ogg',
      'audio/wav' || 'audio/x-wav' => 'wav',
      _ => fallback,
    };
    final copy = library.downloadedCopy(t);
    final src = copy ?? t;
    final uri = src.uri;
    final dir = await getTemporaryDirectory();

    Future<File> fetch(String url, String ext) async {
      final file = File(
        '${dir.path}/jam-out-${DateTime.now().microsecondsSinceEpoch}.$ext',
      );
      final res = await http.Client().send(http.Request('GET', Uri.parse(url)));
      if (res.statusCode != 200) throw const JamError('Could not get it.');
      await res.stream.pipe(file.openWrite());
      return file;
    }

    if (uri != null && uri.startsWith('http')) {
      // A song a friend shared, being passed along.
      return (await fetch(uri, 'mp3'), 'mp3');
    }
    if (src.source == TrackSource.file && uri != null) {
      final dot = uri.lastIndexOf('.');
      return (File(uri), dot > 0 ? uri.substring(dot + 1) : 'mp3');
    }
    if (uri != null && uri.startsWith('content://')) {
      final path = await DeviceLibrary.copyToCache(uri, 'song');
      if (path == null) throw const JamError('Could not read that song.');
      return (File(path), extOf(t.mime, 'mp3'));
    }
    if (t.source == TrackSource.telegram && library.bot != null) {
      final ext = extOf(t.mime, 'mp3');
      return (await fetch(await library.bot!.fileUrl(t.uri!), ext), ext);
    }
    throw const JamError('That song can\'t be shared.');
  }

  // ---- Leaving -----------------------------------------------------------

  void _clearGuest() {
    _stateSub?.cancel();
    _stateSub = null;
    _session = null;
    sending.clear();
    role = JamRole.none;
  }

  Future<void> _endHosting() async {
    player.removeListener(_playerChanged);
    _tick?.cancel();
    _debounce?.cancel();
    final h = _host;
    _host = null;
    joinCode = null;
    _addedBy.clear();
    _artCache.clear();
    _links.clear();
    _libraryLink = null;
    // Friends' songs stay until the next Jam, so the queue keeps playing.
    await h?.stop(deleteUploads: false);
  }

  /// Ends the Jam for everyone (host) or leaves it (guest).
  Future<void> leave() async {
    if (role == JamRole.host) {
      role = JamRole.none;
      await _endHosting();
    } else if (role == JamRole.guest) {
      _stopFollowing();
      final g = _session;
      _clearGuest();
      await g?.leave();
    }
    role = JamRole.none;
    event = null;
    online = false;
    notifyListeners();
  }
}
