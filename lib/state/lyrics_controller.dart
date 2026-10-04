import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/track.dart';
import '../services/lyrics_api.dart';
import 'player_controller.dart';

enum LyricsStatus { idle, loading, found, notFound, error }

/// Fetches and caches lyrics for whatever is playing. Users can pick a
/// different match and nudge the timing; both choices are remembered per track.
class LyricsController extends ChangeNotifier {
  LyricsController(this._player) {
    _player.addListener(_onPlayer);
    _onPlayer();
  }

  final PlayerController _player;
  final LyricsApi api = LyricsApi();
  final Map<String, Lyrics?> _cache = {};

  String? _trackId;

  /// Previews are a 30s clip from somewhere in the song, so line timings
  /// can't line up. Full songs on the device sync properly.
  bool get canSync => !(_player.current?.isPreview ?? false);
  LyricsStatus status = LyricsStatus.idle;
  Lyrics? lyrics;
  Duration offset = Duration.zero;

  void _onPlayer() {
    final t = _player.current;
    if (t == null || t.id == _trackId) return;
    _trackId = t.id;
    _load(t);
  }

  Future<void> _load(Track t) async {
    final prefs = await SharedPreferences.getInstance();
    offset = Duration(milliseconds: prefs.getInt('lyr_off:${t.id}') ?? 0);
    if (_cache.containsKey(t.id)) {
      lyrics = _cache[t.id];
      status = lyrics == null ? LyricsStatus.notFound : LyricsStatus.found;
      notifyListeners();
      return;
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

  Future<void> retry() async {
    final t = _player.current;
    if (t == null) return;
    _cache.remove(t.id);
    await _load(t);
  }

  /// Use a lyrics result the user picked from search.
  Future<void> choose(Lyrics l) async {
    final t = _player.current;
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
    final t = _player.current;
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
    super.dispose();
  }
}
