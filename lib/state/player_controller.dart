// ignore_for_file: experimental_member_use
import 'dart:async';
import 'dart:convert';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart';
import 'package:just_audio/just_audio.dart';
import 'package:just_audio_background/just_audio_background.dart';
import 'package:shared_preferences/shared_preferences.dart';

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
    loadTrending();
  }

  final AudioPlayer player = AudioPlayer();
  final AudiusApi _api = AudiusApi();

  // Library state
  final List<Track> localTracks = [];
  final List<Track> favorites = [];
  final List<Track> recent = [];
  List<Track> onlineTracks = [];
  bool onlineLoading = false;
  String? onlineError;

  // Playback state
  List<Track> queue = [];
  int currentIndex = 0;
  bool shuffle = false;
  LoopMode loopMode = LoopMode.off;
  double speed = 1.0;

  // Sleep timer
  Timer? _sleepTimer;
  DateTime? _sleepEndsAt;
  Duration? get sleepRemaining => _sleepEndsAt?.difference(DateTime.now());

  Track? get current =>
      queue.isEmpty || currentIndex >= queue.length ? null : queue[currentIndex];

  bool isFavorite(Track t) => favorites.any((f) => f.id == t.id);

  void _wire() {
    player.currentIndexStream.listen((i) {
      if (i == null || i == currentIndex && current != null) return;
      currentIndex = i;
      final t = current;
      if (t != null) _addRecent(t);
      notifyListeners();
    });
    player.playerStateStream.listen((_) => notifyListeners());
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

  Future<void> playQueue(List<Track> tracks, int index) async {
    queue = List.of(tracks);
    currentIndex = index;
    _addRecent(queue[index]);
    notifyListeners();
    try {
      await player.setAudioSources(
        queue.map(_sourceFor).toList(),
        initialIndex: index,
      );
      await player.play();
    } catch (e) {
      onlineError = 'Could not play this track: $e';
      notifyListeners();
    }
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

  Future<void> addToQueue(Track t) async {
    if (queue.isEmpty) return playQueue([t], 0);
    queue.add(t);
    await player.addAudioSource(_sourceFor(t));
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
    await player.removeAudioSourceAt(i);
    if (i < currentIndex) currentIndex--;
    notifyListeners();
  }

  // ---- Sleep timer --------------------------------------------------------

  void setSleepTimer(Duration? d) {
    _sleepTimer?.cancel();
    _sleepEndsAt = null;
    if (d != null) {
      _sleepEndsAt = DateTime.now().add(d);
      _sleepTimer = Timer(d, () {
        player.pause();
        _sleepEndsAt = null;
        notifyListeners();
      });
    }
    notifyListeners();
  }

  // ---- Library ------------------------------------------------------------

  Future<void> pickLocalFiles() async {
    final files = await FilePicker.pickFiles(type: FileType.audio);
    for (final f in files) {
      final id = 'local:${kIsWeb ? '${f.name}:${f.lengthSync()}' : f.path}';
      if (localTracks.any((t) => t.id == id)) continue;
      localTracks.add(Track(
        id: id,
        title: _cleanName(f.name),
        artist: 'On this device',
        source: TrackSource.local,
        uri: kIsWeb ? null : f.path,
        bytes: kIsWeb ? await f.readAsBytes() : null,
      ));
    }
    await _save();
    notifyListeners();
  }

  void removeLocal(Track t) {
    localTracks.removeWhere((x) => x.id == t.id);
    _save();
    notifyListeners();
  }

  String _cleanName(String n) {
    final dot = n.lastIndexOf('.');
    return (dot > 0 ? n.substring(0, dot) : n).replaceAll('_', ' ');
  }

  Future<void> loadTrending() async {
    onlineLoading = true;
    onlineError = null;
    notifyListeners();
    try {
      onlineTracks = await _api.trending();
    } catch (e) {
      onlineError = 'Could not load online music. Check your connection.';
    }
    onlineLoading = false;
    notifyListeners();
  }

  Future<void> searchOnline(String q) async {
    if (q.trim().isEmpty) return loadTrending();
    onlineLoading = true;
    onlineError = null;
    notifyListeners();
    try {
      onlineTracks = await _api.search(q.trim());
    } catch (e) {
      onlineError = 'Search failed. Check your connection.';
    }
    onlineLoading = false;
    notifyListeners();
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

  void _addRecent(Track t) {
    recent.removeWhere((r) => r.id == t.id);
    recent.insert(0, t);
    if (recent.length > 20) recent.removeLast();
    _save();
  }

  // ---- Persistence --------------------------------------------------------

  Future<void> _save() async {
    try {
      final p = await SharedPreferences.getInstance();
      String enc(List<Track> l) =>
          jsonEncode(l.where((t) => t.isPersistable).map((t) => t.toJson()).toList());
      await p.setString('local', enc(localTracks));
      await p.setString('favorites', enc(favorites));
      await p.setString('recent', enc(recent));
    } catch (_) {}
  }

  Future<void> _restore() async {
    try {
      final p = await SharedPreferences.getInstance();
      List<Track> dec(String k) =>
          ((jsonDecode(p.getString(k) ?? '[]')) as List)
              .map((e) => Track.fromJson(e as Map<String, dynamic>))
              .toList();
      localTracks.addAll(dec('local'));
      favorites.addAll(dec('favorites'));
      recent.addAll(dec('recent'));
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
