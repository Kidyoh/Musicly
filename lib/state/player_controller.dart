// ignore_for_file: experimental_member_use
import 'dart:async';
import 'dart:convert';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart' show ThemeMode;
import 'package:just_audio/just_audio.dart';
import 'package:just_audio_background/just_audio_background.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/collection.dart';
import '../models/track.dart';
import '../services/audius_api.dart';

class _BytesSource extends StreamAudioSource {
  _BytesSource(this.data, {super.tag});
  final Uint8List data;

  @override
  Future<StreamAudioResponse> request([int? start, int? end]) async {
    start ??= 0;
    end ??= data.length;
    return StreamAudioResponse(
      sourceLength: data.length,
      contentLength: end - start,
      offset: start,
      stream: Stream.value(data.sublist(start, end)),
      contentType: 'audio/mpeg',
    );
  }
}

class PlayerController extends ChangeNotifier {
  PlayerController() {
    _wire();
    _restore();
    loadHome();
  }

  final AudioPlayer player = AudioPlayer();
  final AudiusApi api = AudiusApi();

  ThemeMode themeMode = ThemeMode.light;

  // Library
  final List<Track> localTracks = [];
  final List<Track> favorites = [];
  final List<Track> recent = [];

  // Home feed
  List<Track> trending = [];
  List<Collection> featured = [];
  bool homeLoading = false;
  String? homeError;
  String? playError;

  // Playback
  List<Track> queue = [];
  int currentIndex = 0;
  bool shuffle = false;
  LoopMode loopMode = LoopMode.off;
  double speed = 1.0;

  // Sleep timer
  Timer? _sleepTimer;
  DateTime? _sleepEndsAt;
  bool sleepAtTrackEnd = false;
  Duration? get sleepRemaining => _sleepEndsAt?.difference(DateTime.now());
  bool get sleepActive => _sleepEndsAt != null || sleepAtTrackEnd;

  Track? get current =>
      queue.isEmpty || currentIndex >= queue.length ? null : queue[currentIndex];

  bool isFavorite(Track t) => favorites.any((f) => f.id == t.id);

  void _wire() {
    player.currentIndexStream.listen((i) {
      if (i == null) return;
      final changed = i != currentIndex;
      if (changed && sleepAtTrackEnd) {
        sleepAtTrackEnd = false;
        player.pause();
      }
      currentIndex = i;
      if (changed && current != null) _addRecent(current!);
      notifyListeners();
    });
    player.playerStateStream.listen((s) {
      if (s.processingState == ProcessingState.completed && sleepAtTrackEnd) {
        sleepAtTrackEnd = false;
      }
      notifyListeners();
    });
  }

  // ---- Playback -----------------------------------------------------------

  AudioSource _sourceFor(Track t) {
    final tag = kIsWeb
        ? null
        : MediaItem(
            id: t.id,
            title: t.title,
            artist: t.artist,
            duration: t.duration,
            artUri: t.artworkUrl == null ? null : Uri.parse(t.artworkUrl!),
          );
    if (t.bytes != null) return _BytesSource(t.bytes!, tag: tag);
    if (t.source == TrackSource.local && !kIsWeb) {
      return AudioSource.file(t.uri!, tag: tag);
    }
    return AudioSource.uri(Uri.parse(t.uri!), tag: tag);
  }

  Future<void> playQueue(List<Track> tracks, int index, {bool shuffled = false}) async {
    if (tracks.isEmpty) return;
    queue = List.of(tracks);
    currentIndex = index;
    playError = null;
    _addRecent(queue[index]);
    notifyListeners();
    try {
      await player.setAudioSources(
        queue.map(_sourceFor).toList(),
        initialIndex: index,
      );
      if (shuffled != shuffle) await toggleShuffle();
      if (shuffled) await player.seek(Duration.zero, index: player.effectiveIndices.first);
      await player.play();
    } catch (e) {
      playError = 'Could not play this track';
      notifyListeners();
    }
  }

  Future<void> playShuffled(List<Track> tracks) async {
    if (tracks.isEmpty) return;
    await playQueue(tracks, 0, shuffled: true);
  }

  Future<void> togglePlay() async =>
      player.playing ? player.pause() : player.play();

  Future<void> next() => player.seekToNext();

  Future<void> previous() async {
    if (player.position > const Duration(seconds: 3)) {
      await player.seek(Duration.zero);
    } else {
      await player.seekToPrevious();
    }
  }

  Future<void> seek(Duration d) => player.seek(d);

  Future<void> jumpTo(int index) => player.seek(Duration.zero, index: index);

  Future<void> toggleShuffle() async {
    shuffle = !shuffle;
    if (shuffle) await player.shuffle();
    await player.setShuffleModeEnabled(shuffle);
    notifyListeners();
  }

  Future<void> cycleLoop() async {
    loopMode = switch (loopMode) {
      LoopMode.off => LoopMode.all,
      LoopMode.all => LoopMode.one,
      LoopMode.one => LoopMode.off,
    };
    await player.setLoopMode(loopMode);
    notifyListeners();
  }

  Future<void> setSpeed(double s) async {
    speed = s;
    await player.setSpeed(s);
    notifyListeners();
  }

  Future<void> addToQueue(List<Track> tracks) async {
    if (tracks.isEmpty) return;
    if (queue.isEmpty) return playQueue(tracks, 0);
    queue.addAll(tracks);
    await player.addAudioSources(tracks.map(_sourceFor).toList());
    notifyListeners();
  }

  Future<void> playNext(Track t) async {
    if (queue.isEmpty) return playQueue([t], 0);
    queue.insert(currentIndex + 1, t);
    await player.insertAudioSource(currentIndex + 1, _sourceFor(t));
    notifyListeners();
  }

  Future<void> removeFromQueue(int i) async {
    if (i == currentIndex) return;
    queue.removeAt(i);
    if (i < currentIndex) currentIndex--;
    notifyListeners();
    await player.removeAudioSourceAt(i);
  }

  Future<void> moveInQueue(int from, int to) async {
    if (from == to) return;
    final t = queue.removeAt(from);
    queue.insert(to, t);
    if (from == currentIndex) {
      currentIndex = to;
    } else if (from < currentIndex && to >= currentIndex) {
      currentIndex--;
    } else if (from > currentIndex && to <= currentIndex) {
      currentIndex++;
    }
    notifyListeners();
    await player.moveAudioSource(from, to);
  }

  // ---- Sleep timer --------------------------------------------------------

  void setSleepTimer(Duration? d, {bool atTrackEnd = false}) {
    _sleepTimer?.cancel();
    _sleepEndsAt = null;
    sleepAtTrackEnd = atTrackEnd;
    if (d != null) {
      _sleepEndsAt = DateTime.now().add(d);
      _sleepTimer = Timer(d, () async {
        _sleepEndsAt = null;
        notifyListeners();
        // Fade out gently instead of cutting the song off.
        for (var v = 1.0; v > 0; v -= 0.1) {
          await player.setVolume(v);
          await Future.delayed(const Duration(milliseconds: 300));
        }
        await player.pause();
        await player.setVolume(1);
      });
    }
    notifyListeners();
  }

  // ---- Feeds --------------------------------------------------------------

  Future<void> loadHome() async {
    homeLoading = true;
    homeError = null;
    notifyListeners();
    try {
      final r = await Future.wait([api.trending(limit: 20), api.trendingPlaylists()]);
      trending = r[0] as List<Track>;
      featured = r[1] as List<Collection>;
    } catch (_) {
      homeError = 'Could not load online music. Check your connection.';
    }
    homeLoading = false;
    notifyListeners();
  }

  // ---- Library ------------------------------------------------------------

  Future<int> pickLocalFiles() async {
    final files = await FilePicker.pickFiles(type: FileType.audio);
    var added = 0;
    for (final f in files) {
      final id = 'local:${kIsWeb ? '${f.name}:${f.lengthSync()}' : f.path}';
      if (localTracks.any((t) => t.id == id)) continue;
      final (artist, title) = _splitName(f.name);
      localTracks.add(Track(
        id: id,
        title: title,
        artist: artist,
        source: TrackSource.local,
        uri: kIsWeb ? null : f.path,
        bytes: kIsWeb ? await f.readAsBytes() : null,
      ));
      added++;
    }
    await _save();
    notifyListeners();
    return added;
  }

  void removeLocal(Track t) {
    localTracks.removeWhere((x) => x.id == t.id);
    _save();
    notifyListeners();
  }

  /// "Artist - Title.mp3" → (Artist, Title); otherwise (Unknown artist, name).
  (String, String) _splitName(String n) {
    final dot = n.lastIndexOf('.');
    final base = (dot > 0 ? n.substring(0, dot) : n).replaceAll('_', ' ').trim();
    final dash = base.indexOf(' - ');
    if (dash > 0) {
      return (base.substring(0, dash).trim(), base.substring(dash + 3).trim());
    }
    return ('Unknown artist', base);
  }

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

  void _addRecent(Track t) {
    recent.removeWhere((r) => r.id == t.id);
    recent.insert(0, t);
    if (recent.length > 30) recent.removeLast();
    _save();
  }

  void toggleTheme() {
    themeMode = themeMode == ThemeMode.dark ? ThemeMode.light : ThemeMode.dark;
    _save();
    notifyListeners();
  }

  // ---- Persistence --------------------------------------------------------

  Future<void> _save() async {
    try {
      final p = await SharedPreferences.getInstance();
      String enc(List<Track> l) => jsonEncode(
          l.where((t) => t.isPersistable).map((t) => t.toJson()).toList());
      await p.setString('local', enc(localTracks));
      await p.setString('favorites', enc(favorites));
      await p.setString('recent', enc(recent));
      await p.setString('theme', themeMode.name);
    } catch (_) {}
  }

  Future<void> _restore() async {
    try {
      final p = await SharedPreferences.getInstance();
      List<Track> dec(String k) => ((jsonDecode(p.getString(k) ?? '[]')) as List)
          .map((e) => Track.fromJson(e as Map<String, dynamic>))
          .toList();
      localTracks.addAll(dec('local'));
      favorites.addAll(dec('favorites'));
      recent.addAll(dec('recent'));
      themeMode = p.getString('theme') == 'dark' ? ThemeMode.dark : ThemeMode.light;
      notifyListeners();
    } catch (_) {}
  }

  @override
  void dispose() {
    _sleepTimer?.cancel();
    player.dispose();
    super.dispose();
  }
}
