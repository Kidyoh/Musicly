import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/track.dart';
import '../services/lyrics_api.dart';
import 'player_controller.dart';

/// [off] means online lookup is turned off and nothing was fetched for this
/// song; the user can still look it up by hand.
enum LyricsStatus { idle, loading, found, notFound, error, off }

/// Fetches and caches lyrics for whatever is playing. Users can pick a
/// different match and nudge the timing; both choices are remembered per track.
///
/// Lookups send the song's title and artist to LRCLIB, so they only happen on
/// their own while [online] says so. Otherwise lyrics are fetched only when
/// the user asks ([lookUp], or picking a result from search).
class LyricsController extends ChangeNotifier {
  LyricsController(
    PlayerController player, {
    required bool Function() online,
    Listenable? settings,
    Future<void>? ready,
    LyricsApi? api,
  }) : this.custom(
         player,
         () => player.current,
         online: online,
         settings: settings,
         ready: ready,
         api: api,
       );

  /// For tests: the first argument notifies when the track returned by the
  /// second may have changed.
  @visibleForTesting
  LyricsController.custom(
    this._player,
    this._current, {
    required this._online,
    this._settings,
    this._ready,
    LyricsApi? api,
  }) : api = api ?? LyricsApi() {
    _wasOnline = _online();
    _player.addListener(_onPlayer);
    _settings?.addListener(_onSettings);
    _onPlayer();
  }

  final Listenable _player;
  final Track? Function() _current;
  final bool Function() _online;
  final Listenable? _settings;
  final Future<void>? _ready;
  final LyricsApi api;
  final Map<String, Lyrics?> _cache = {};

  String? _trackId;
  late bool _wasOnline;

  /// Every song in Musicly is full length, so lyrics always sync.
  bool get canSync => true;
  LyricsStatus status = LyricsStatus.idle;
  Lyrics? lyrics;
  Duration offset = Duration.zero;

  void _onPlayer() {
    final t = _current();
    if (t == null || t.id == _trackId) return;
    _trackId = t.id;
    _load(t);
  }

  /// Turning online lyrics back on fetches them for the song playing now.
  void _onSettings() {
    final on = _online();
    if (on == _wasOnline) return;
    _wasOnline = on;
    final t = _current();
    if (on && t != null && status == LyricsStatus.off) _load(t);
  }

  /// [manual] is a lookup the user asked for, made even when online lyrics
  /// are off.
  Future<void> _load(Track t, {bool manual = false}) async {
    if (t.isRadio) {
      lyrics = null;
      status = LyricsStatus.idle;
      notifyListeners();
      return;
    }
    final prefs = await SharedPreferences.getInstance();
    offset = Duration(milliseconds: prefs.getInt('lyr_off:${t.id}') ?? 0);
    if (_cache.containsKey(t.id)) {
      lyrics = _cache[t.id];
      status = lyrics == null ? LyricsStatus.notFound : LyricsStatus.found;
      notifyListeners();
      return;
    }
    if (!manual) {
      // Wait for saved settings so a lookup never slips out at startup.
      try {
        await _ready;
      } catch (_) {}
      if (_trackId != t.id) return;
      if (!_online()) {
        lyrics = null;
        status = LyricsStatus.off;
        notifyListeners();
        return;
      }
    }
    lyrics = null;
    status = LyricsStatus.loading;
    notifyListeners();
    try {
      final chosen = prefs.getInt('lyr_id:${t.id}');
      final artist = t.artist == 'Unknown artist' ? '' : t.artist;
      final result = chosen != null
          ? await api.byId(chosen)
          : await api.find(t.title, artist, t.duration);
      if (_trackId != t.id) return;
      _cache[t.id] = result;
      lyrics = result;
      status = result == null ? LyricsStatus.notFound : LyricsStatus.found;
    } catch (_) {
      if (_trackId != t.id) return;
      status = LyricsStatus.error;
    }
    notifyListeners();
  }

  /// Looks up lyrics for the song playing now, because the user asked: to try
  /// again after an error, or once when online lyrics are off.
  Future<void> lookUp() async {
    final t = _current();
    if (t == null) return;
    _cache.remove(t.id);
    await _load(t, manual: true);
  }

  /// Use a lyrics result the user picked from search.
  Future<void> choose(Lyrics l) async {
    final t = _current();
    if (t == null) return;
    _cache[t.id] = l;
    lyrics = l;
    status = LyricsStatus.found;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('lyr_id:${t.id}', l.id);
  }

  Future<void> nudge(Duration d) async {
    offset += d;
    notifyListeners();
    final t = _current();
    if (t == null) return;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('lyr_off:${t.id}', offset.inMilliseconds);
  }

  /// Index of the line being sung at [position], or -1 before the first line.
  int lineAt(Duration position) {
    final lines = lyrics?.synced ?? const [];
    final p = position + offset;
    var lo = 0, hi = lines.length - 1, ans = -1;
    while (lo <= hi) {
      final mid = (lo + hi) >> 1;
      if (lines[mid].time <= p) {
        ans = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return ans;
  }

  @override
  void dispose() {
    _player.removeListener(_onPlayer);
    _settings?.removeListener(_onSettings);
    super.dispose();
  }
}
