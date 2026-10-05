import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/track.dart';
import '../services/device_library.dart';
import '../services/jam_guest.dart';
import '../services/jam_host.dart';
import '../services/jam_protocol.dart';
import '../services/telegram_bot.dart';
import 'library_controller.dart';
import 'player_controller.dart';

enum JamRole { none, host, guest }

/// Listening together in the same room. The host's phone plays the music;
/// friends on the same Wi-Fi join from their Musicly app, see what's on and
/// add songs (from the host's library or their own phone).
class JamController extends ChangeNotifier {
  JamController(this.library, this.player) {
    _loadName();
  }

  final LibraryController library;
  final PlayerController player;

  static bool get supported => !kIsWeb;

  JamRole role = JamRole.none;
  bool get active => role != JamRole.none;

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

  /// The latest thing that happened, shown briefly in the Jam screen.
  String? event;
  String? error;

  // ---- Hosting -----------------------------------------------------------

  JamHost? _host;
  String? joinCode;
  Timer? _tick;
  Timer? _debounce;
  bool _guestsControl = false;

  /// Who added which song (track id -> name), for "Added by".
  final Map<String, String> _addedBy = {};
  int _uploads = 0;

  bool get guestsControl => _guestsControl;

  /// Who added [t] to the Jam, if a friend did.
  String? addedBy(Track t) => _addedBy[t.id];
  List<JamGuestInfo> get guests => _host?.guests.values.toList() ?? const [];
  String get jamName => role == JamRole.guest
      ? (_guest?.jamName ?? 'Jam')
      : '${hasName ? myName : 'My'}${hasName ? '\'s' : ''} Jam';

  Future<bool> startHosting() async {
    if (active) return false;
    error = null;
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
      final dir = Directory(
        '${tmp.path}/jam-${DateTime.now().millisecondsSinceEpoch}',
      );
      final h = JamHost(
        jamName: jamName,
        hostName: hasName ? myName : 'Host',
        uploadsDir: dir,
        state: _hostState,
        onAdd: _guestAdded,
        onUpload: _guestUploaded,
        onControl: _guestControl,
        search: _search,
        art: _artFor,
      )..onPeopleChanged = _peopleChanged;
      await h.start();
      _host = h;
      final ip = await JamHost.localAddress();
      joinCode = ip == null ? null : JamCode.encode(ip, h.port);
      role = JamRole.host;
      player.addListener(_playerChanged);
      // Keeps everyone's progress bar honest even when nothing changes.
      _tick = Timer.periodic(const Duration(seconds: 5), (_) => h.pushState());
      notifyListeners();
      return true;
    } catch (_) {
      error = 'Could not start a Jam. Check that Wi-Fi or your hotspot is on.';
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
      _host?.pushState();
    });
  }

  JamTrack _jamTrack(Track t) => JamTrack(
    id: t.id,
    title: t.title,
    artist: t.artist,
    durationMs: t.duration?.inMilliseconds,
    by: _addedBy[t.id],
    hasArt:
        t.artworkUrl != null ||
        t.mediaId != null ||
        library.downloadedCopy(t)?.mediaId != null,
  );

  JamState _hostState() {
    final now = player.current;
    final order = player.player.effectiveIndices;
    final at = order.indexOf(player.currentIndex);
    final upcoming = <Track>[
      if (at >= 0)
        for (final i in order.skip(at + 1).take(30))
          if (i < player.queue.length) player.queue[i],
    ];
    return JamState(
      now: now == null ? null : _jamTrack(now),
      playing: player.isPlaying,
      positionMs: player.player.position.inMilliseconds,
      at: DateTime.now().millisecondsSinceEpoch,
      queue: upcoming.map(_jamTrack).toList(),
      guestsControl: _guestsControl,
      event: event,
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

  Future<void> _guestAdded(String id, JamGuestInfo by, bool next) async {
    final t = _find(id);
    if (t != null) await _queue(t, next, by.name);
  }

  Future<void> _guestUploaded(JamUpload song, JamGuestInfo by, bool next) =>
      _queue(
        Track(
          id: 'jam:${_uploads++}:${song.title}',
          title: song.title,
          artist: song.artist,
          source: TrackSource.file,
          uri: song.path,
          artworkUrl: song.artPath,
          duration: song.durationMs == null
              ? null
              : Duration(milliseconds: song.durationMs!),
          album: 'From ${by.name} in the Jam',
        ),
        next,
        by.name,
      );

  void _guestControl(String action, int? ms, JamGuestInfo by) {
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
    List<int>? bytes;
    try {
      final media = t.mediaId ?? library.downloadedCopy(t)?.mediaId;
      final url = t.artworkUrl;
      if (url != null && url.startsWith('/')) {
        bytes = await File(url).readAsBytes();
      } else if (url != null && TelegramFiles.isThumb(url)) {
        final link = await TelegramFiles.resolveThumb(url);
        if (link != null) bytes = (await http.get(Uri.parse(link))).bodyBytes;
      } else if (url != null && url.startsWith('http')) {
        bytes = (await http.get(Uri.parse(url))).bodyBytes;
      } else if (media != null) {
        bytes = await DeviceLibrary.artwork(media);
      }
    } catch (_) {}
    if (bytes != null && bytes.isNotEmpty) {
      if (_artCache.length > 120) _artCache.remove(_artCache.keys.first);
      _artCache[id] = bytes;
    }
    return bytes;
  }

  // ---- Joining -----------------------------------------------------------

  JamGuest? _guest;
  JamState get state => _guest?.state ?? const JamState();
  String get hostName => _guest?.hostName ?? '';
  Duration get position => _guest?.position ?? Duration.zero;
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

  /// Joins a Jam found nearby or typed in as a code. Returns an error or null.
  Future<String?> join(String address, int port) async {
    if (active) return 'You\'re already in a Jam.';
    final g = JamGuest(address, port);
    try {
      await g.join(hasName ? myName : 'Guest');
    } catch (_) {
      return 'Could not reach that Jam. Make sure you\'re on the same Wi-Fi.';
    }
    stopLooking();
    _guest = g;
    role = JamRole.guest;
    error = null;
    event = g.state.event; // the first update can arrive before we listen
    _stateSub = g.states.listen((s) {
      if (s.event != null) event = s.event;
      notifyListeners();
    });
    g.closed.then((reason) {
      if (_guest != g) return;
      _clearGuest();
      error = switch (reason) {
        'ended' => 'The host ended the Jam.',
        'removed' => 'The host removed you from the Jam.',
        'lost' => 'Lost connection to the Jam.',
        _ => null,
      };
      notifyListeners();
    });
    notifyListeners();
    return null;
  }

  Future<String?> joinWithCode(String code) async {
    final at = JamCode.decode(code);
    if (at == null) return 'That code doesn\'t look right.';
    return join(at.$1, at.$2);
  }

  /// A song in the Jam as a [Track], for showing it with the usual widgets.
  Track viewTrack(JamTrack t) => Track(
    id: 'jamview:${t.id}',
    title: t.title,
    artist: t.artist,
    source: TrackSource.file,
    duration: t.durationMs == null
        ? null
        : Duration(milliseconds: t.durationMs!),
    artworkUrl: t.hasArt ? _guest?.artUrl(t.id) : null,
  );

  Future<List<JamTrack>> searchHost(String q) =>
      _guest?.search(q) ?? Future.value(const []);

  void addFromHost(JamTrack t, {bool next = false}) =>
      _guest?.add(t.id, next: next);

  void control(String action, [int? ms]) => _guest?.control(action, ms);

  /// Songs being sent right now (track id -> progress 0..1).
  final Map<String, double> sending = {};

  /// Sends one of my songs to the host. Returns an error message or null.
  Future<String?> send(Track t, {bool next = false}) async {
    final g = _guest;
    if (g == null || sending.containsKey(t.id)) return null;
    sending[t.id] = 0;
    notifyListeners();
    try {
      final (file, ext) = await _fileFor(t);
      List<int>? art;
      final media = t.mediaId ?? library.downloadedCopy(t)?.mediaId;
      if (media != null) {
        art = await DeviceLibrary.artwork(media);
      } else if (TelegramFiles.isThumb(t.artworkUrl)) {
        final link = await TelegramFiles.resolveThumb(t.artworkUrl!);
        if (link != null) art = (await http.get(Uri.parse(link))).bodyBytes;
      }
      await g.upload(
        audio: file,
        ext: ext,
        title: t.title,
        artist: t.artist,
        art: art,
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
      final url = await library.bot!.fileUrl(t.uri!);
      final dir = await getTemporaryDirectory();
      final file = File('${dir.path}/jam-send.${extOf(t.mime, 'mp3')}');
      final res = await http.Client().send(http.Request('GET', Uri.parse(url)));
      if (res.statusCode != 200) throw const JamError('Could not get it.');
      await res.stream.pipe(file.openWrite());
      return (file, extOf(t.mime, 'mp3'));
    }
    throw const JamError('That song can\'t be sent.');
  }

  // ---- Leaving -----------------------------------------------------------

  void _clearGuest() {
    _stateSub?.cancel();
    _stateSub = null;
    _guest = null;
    sending.clear();
    role = JamRole.none;
  }

  /// Ends the Jam for everyone (host) or leaves it (guest).
  Future<void> leave() async {
    if (role == JamRole.host) {
      player.removeListener(_playerChanged);
      _tick?.cancel();
      _debounce?.cancel();
      final h = _host;
      _host = null;
      joinCode = null;
      _addedBy.clear();
      _artCache.clear();
      // Friends' songs stay until the next Jam, so the queue keeps playing.
      await h?.stop(deleteUploads: false);
    } else if (role == JamRole.guest) {
      final g = _guest;
      _clearGuest();
      await g?.leave();
    }
    role = JamRole.none;
    event = null;
    notifyListeners();
  }
}
