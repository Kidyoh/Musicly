// ignore_for_file: experimental_member_use
import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:just_audio/just_audio.dart';
import 'package:just_audio_background/just_audio_background.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/track.dart';
import '../services/device_library.dart';
import '../services/telegram_bot.dart';

/// Picked files on web live only in memory.
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

/// Audio whose download link is looked up only when it's needed, because
/// Telegram file links expire after about an hour.
/// Queued and saved songs never go stale.
class _LazySource extends StreamAudioSource {
  _LazySource(this.key, this.resolve, {super.tag});
  final String key;
  final Future<(String, DateTime)> Function() resolve;

  static final Map<String, (String, DateTime)> _cache = {};

  /// Drop a cached link, e.g. after it failed.
  static void forget(String key) => _cache.remove(key);

  /// Look the link up ahead of time so the next song starts instantly.
  static Future<void> warm(
    String key,
    Future<(String, DateTime)> Function() resolve,
  ) async {
    final hit = _cache[key];
    if (hit != null &&
        hit.$2.isAfter(DateTime.now().add(const Duration(minutes: 2)))) {
      return;
    }
    try {
      _cache[key] = await resolve();
    } catch (_) {}
  }

  Future<String> _url() async {
    final hit = _cache[key];
    if (hit != null &&
        hit.$2.isAfter(DateTime.now().add(const Duration(seconds: 45)))) {
      return hit.$1;
    }
    final fresh = await resolve();
    _cache[key] = fresh;
    return fresh.$1;
  }

  @override
  Future<StreamAudioResponse> request([int? start, int? end]) async {
    final req = http.Request('GET', Uri.parse(await _url()));
    if (start != null || end != null) {
      req.headers['Range'] =
          'bytes=${start ?? 0}-${end == null ? '' : end - 1}';
    }
    final res = await http.Client().send(req);
    if (res.statusCode != 200 && res.statusCode != 206) {
      _cache.remove(key);
      throw Exception('Audio HTTP ${res.statusCode}');
    }
    int? total = res.contentLength;
    final range = res.headers['content-range'];
    if (range != null && range.contains('/')) {
      total = int.tryParse(range.split('/').last);
    }
    Stream<List<int>> stream = res.stream;
    final from = start ?? 0;
    // A server that ignores Range sends the whole file; skip to the offset.
    final ignoredRange = res.statusCode == 200 && from > 0;
    if (ignoredRange) stream = _skip(stream, from);
    return StreamAudioResponse(
      rangeRequestsSupported: true,
      sourceLength: total,
      contentLength: ignoredRange && total != null
          ? total - from
          : res.contentLength,
      offset: from,
      stream: stream,
      contentType: 'audio/mpeg',
    );
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

enum EqPreset {
  flat,
  bassBoost,
  vocal,
  treble,
  electronic,
  rock,
  acoustic,
  custom,
}

extension EqPresetInfo on EqPreset {
  String get label => switch (this) {
    EqPreset.flat => 'Flat',
    EqPreset.bassBoost => 'Bass boost',
    EqPreset.vocal => 'Vocal',
    EqPreset.treble => 'Treble',
    EqPreset.electronic => 'Electronic',
    EqPreset.rock => 'Rock',
    EqPreset.acoustic => 'Acoustic',
    EqPreset.custom => 'Custom',
  };

  /// Gains in dB at 60, 230, 910, 3.6k, 14k Hz; resampled to the device's bands.
  List<double> get curve => switch (this) {
    EqPreset.flat => [0, 0, 0, 0, 0],
    EqPreset.bassBoost => [7, 4.5, 0, 0, 0],
    EqPreset.vocal => [-2, 0, 4, 3, 0],
    EqPreset.treble => [0, 0, 0, 4, 7],
    EqPreset.electronic => [5, 2, -1, 2, 5],
    EqPreset.rock => [4, 2, -2, 2, 4],
    EqPreset.acoustic => [3, 1, 1, 2, 3],
    EqPreset.custom => [0, 0, 0, 0, 0],
  };
}

class PlayerController extends ChangeNotifier {
  PlayerController() {
    final android = !kIsWeb && defaultTargetPlatform == TargetPlatform.android;
    equalizer = android ? AndroidEqualizer() : null;
    loudness = android ? AndroidLoudnessEnhancer() : null;
    player = AudioPlayer(
      audioPipeline: AudioPipeline(
        androidAudioEffects: [?equalizer, ?loudness],
      ),
    );
    _wire();
    _restoreSettings();
  }

  late final AudioPlayer player;
  late final AndroidEqualizer? equalizer;
  late final AndroidLoudnessEnhancer? loudness;

  /// The downloaded phone copy of a channel song, if any (set up in main.dart).
  Track? Function(Track t)? localCopy;

  /// Turns a Telegram file id into a download link (set up in main.dart).
  Future<String> Function(String fileId)? telegramUrl;
  final Map<String, String> _webUrls = {};

  /// Called whenever a new song starts, for history and recommendations.
  void Function(Track t)? onTrackStarted;

  String? playError;

  /// "Artist - Song" announced by a live radio stream, when it sends one.
  String? nowOnAir;

  // Playback
  List<Track> queue = [];
  int currentIndex = 0;
  bool shuffle = false;
  LoopMode loopMode = LoopMode.off;
  double speed = 1.0;

  // Sound
  bool get effectsSupported => equalizer != null;
  bool eqEnabled = false;
  EqPreset eqPreset = EqPreset.flat;
  List<double> customGains = [0, 0, 0, 0, 0];
  bool loudnessOn = false;
  double loudnessGain = 4; // dB
  bool smoothFade = true;
  double fadeSeconds = 3;

  // Sleep timer
  Timer? _sleepTimer;
  DateTime? _sleepEndsAt;
  bool sleepAtTrackEnd = false;
  bool _sleepFading = false;
  Duration? get sleepRemaining => _sleepEndsAt?.difference(DateTime.now());
  bool get sleepActive => _sleepEndsAt != null || sleepAtTrackEnd;

  /// Playing and not finished (just_audio keeps `playing` true at the end).
  bool get isPlaying =>
      player.playing && player.processingState != ProcessingState.completed;

  Track? get current => queue.isEmpty || currentIndex >= queue.length
      ? null
      : queue[currentIndex];

  void _wire() {
    player.currentIndexStream.listen((i) {
      if (i == null) return;
      final changed = i != currentIndex;
      if (changed && sleepAtTrackEnd) {
        sleepAtTrackEnd = false;
        player.pause();
      }
      currentIndex = i;
      if (changed) {
        nowOnAir = null;
        _retries = 0;
      }
      if (changed && current != null) onTrackStarted?.call(current!);
      _prefetchNext();
      notifyListeners();
    });
    player.playerStateStream.listen((_) => notifyListeners());
    player.icyMetadataStream.listen((m) {
      final title = m?.info?.title?.trim();
      if (title != null && title.isNotEmpty && title != nowOnAir) {
        nowOnAir = title;
        notifyListeners();
      }
    });
    player.playbackEventStream.listen(
      null,
      onError: (Object e, StackTrace _) => _recover(),
    );
    player.positionStream.listen(_fade);
  }

  /// Fades each song in at the start and out at the end.
  void _fade(Duration pos) {
    if (_sleepFading) return;
    double v = 1;
    if (smoothFade) {
      final total = player.duration;
      final f = fadeSeconds * 1000;
      final p = pos.inMilliseconds.toDouble();
      if (p < f * 0.6) v = (p / (f * 0.6)).clamp(0.15, 1.0);
      if (total != null && total.inMilliseconds > f * 3) {
        final left = (total.inMilliseconds - p);
        if (left < f) v = (left / f).clamp(0.0, 1.0);
      }
    }
    if ((player.volume - v).abs() > 0.03 || (v == 1 && player.volume != 1)) {
      player.setVolume(v);
    }
  }

  // ---- Playback -----------------------------------------------------------

  /// Key and resolver for a Telegram song's download link.
  (String, Future<(String, DateTime)> Function()) _telegramLink(Track t) {
    final fileId = t.uri!;
    return (
      'tg:$fileId',
      () async => (
        await telegramUrl!(fileId),
        DateTime.now().add(const Duration(minutes: 50)),
      ),
    );
  }

  AudioSource _sourceFor(Track t) {
    // Channel songs already saved on the phone play from there, offline.
    final copy = t.source == TrackSource.telegram ? localCopy?.call(t) : null;
    final tag = kIsWeb ? null : _mediaItem(t, art: copy);
    if (t.bytes != null) return _BytesSource(t.bytes!, tag: tag);
    if (copy != null) return AudioSource.uri(Uri.parse(copy.uri!), tag: tag);
    switch (t.source) {
      case TrackSource.telegram:
        if (kIsWeb) {
          return AudioSource.uri(Uri.parse(_webUrls[t.uri!]!), tag: tag);
        }
        final (key, resolve) = _telegramLink(t);
        return _LazySource(key, resolve, tag: tag);
      case TrackSource.radio:
      case TrackSource.device:
        return AudioSource.uri(Uri.parse(t.uri!), tag: tag);
      case TrackSource.file:
        return kIsWeb
            ? AudioSource.uri(Uri.parse(t.uri!), tag: tag)
            : AudioSource.file(t.uri!, tag: tag);
    }
  }

  /// Fetches the next song's link while this one plays.
  void _prefetchNext() {
    final order = player.effectiveIndices;
    final pos = order.indexOf(currentIndex);
    if (pos < 0 || pos + 1 >= order.length) return;
    final next = queue[order[pos + 1]];
    if (next.source != TrackSource.telegram ||
        localCopy?.call(next) != null ||
        kIsWeb) {
      return;
    }
    final (key, resolve) = _telegramLink(next);
    _LazySource.warm(key, resolve);
  }

  int _retries = 0;
  bool _askedNotifications = false;

  /// A song that fails to load (expired link, network blip) is retried with a
  /// fresh link; after two failures playback moves on instead of stopping.
  Future<void> _recover() async {
    final t = current;
    if (t == null) return;
    if (t.source == TrackSource.telegram) {
      _LazySource.forget(_telegramLink(t).$1);
    }
    _retries++;
    await Future.delayed(Duration(milliseconds: 600 * _retries));
    try {
      if (_retries <= 2) {
        await player.seek(player.position, index: currentIndex);
      } else if (player.hasNext) {
        _retries = 0;
        await player.seekToNext();
      } else {
        playError = 'Could not play this song';
        notifyListeners();
        return;
      }
      await player.play();
    } catch (_) {}
  }

  /// What the lock screen, notification, headphones and car show.
  MediaItem _mediaItem(Track t, {Track? art}) {
    final src = art ?? t;
    Uri? artUri;
    final extras = <String, dynamic>{if (t.isRadio) 'live': true};
    if (src.mediaId != null) {
      // Phone songs: Android reads the embedded cover straight from the file.
      artUri = Uri.parse('content://media/external/audio/media/${src.mediaId}');
      extras['loadThumbnailUri'] = 'true';
    } else if (TelegramFiles.isThumb(t.artworkUrl)) {
      final url = TelegramFiles.cachedThumb(t.artworkUrl!);
      if (url != null) artUri = Uri.parse(url);
    } else if (t.artworkUrl?.startsWith('/') ?? false) {
      artUri = Uri.file(t.artworkUrl!); // a cover a Jam friend sent
    } else if (t.artworkUrl != null) {
      artUri = Uri.parse(t.artworkUrl!);
    }
    return MediaItem(
      id: t.id,
      title: t.title,
      artist: t.artist,
      album: t.album,
      duration: t.isRadio ? null : t.duration,
      extras: extras.isEmpty ? null : extras,
      artUri: artUri,
    );
  }

  /// Telegram covers need a link lookup before the lock screen can show them.
  Future<void> _resolveCovers(List<Track> tracks, int from) async {
    final refs = [
      for (final t in tracks.skip(from).take(40))
        if (TelegramFiles.isThumb(t.artworkUrl) &&
            TelegramFiles.cachedThumb(t.artworkUrl!) == null)
          t.artworkUrl!,
    ];
    for (var k = 0; k < refs.length; k += 8) {
      await Future.wait(refs.skip(k).take(8).map(TelegramFiles.resolveThumb));
    }
  }

  /// Web only: browsers need real links up front for Telegram songs.
  Future<List<Track>> _fresh(List<Track> tracks) async {
    if (!kIsWeb) return tracks;
    final tg = tracks
        .where((t) => t.source == TrackSource.telegram)
        .where((t) => !_webUrls.containsKey(t.uri))
        .toList();
    for (var k = 0; k < tg.length; k += 8) {
      await Future.wait(
        tg.skip(k).take(8).map((t) async {
          try {
            _webUrls[t.uri!] = await telegramUrl!(t.uri!);
          } catch (_) {}
        }),
      );
    }
    return tracks
        .where(
          (t) =>
              t.source != TrackSource.telegram || _webUrls.containsKey(t.uri),
        )
        .toList();
  }

  Future<void> playQueue(
    List<Track> tracks,
    int index, {
    bool shuffled = false,
  }) async {
    if (tracks.isEmpty) return;
    if (kIsWeb) {
      final target = tracks[index].id;
      tracks = await _fresh(tracks);
      index = tracks
          .indexWhere((t) => t.id == target)
          .clamp(0, tracks.length - 1);
      if (tracks.isEmpty) return;
    }
    if (!_askedNotifications) {
      _askedNotifications = true;
      unawaited(DeviceLibrary.requestNotifications());
    }
    queue = List.of(tracks);
    currentIndex = index;
    _retries = 0;
    playError = null;
    nowOnAir = null;
    onTrackStarted?.call(queue[index]);
    notifyListeners();
    try {
      if (shuffled != shuffle) {
        shuffle = shuffled;
        await player.setShuffleModeEnabled(shuffle);
      }
      await _resolveCovers(queue, index);
      await player.setAudioSources(
        queue.map(_sourceFor).toList(),
        initialIndex: index,
      );
      if (shuffled) {
        await player.shuffle();
        await player.seek(Duration.zero, index: player.effectiveIndices.first);
      }
      unawaited(_applyEffects());
      await player.play();
    } catch (_) {
      playError = 'Could not play this song';
      notifyListeners();
    }
  }

  Future<void> playShuffled(List<Track> tracks) =>
      playQueue(tracks, 0, shuffled: true);

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
    tracks = await _fresh(tracks);
    if (tracks.isEmpty) return;
    if (queue.isEmpty) return playQueue(tracks, 0);
    queue.addAll(tracks);
    notifyListeners();
    await player.addAudioSources(tracks.map(_sourceFor).toList());
  }

  Future<void> playNext(Track t) async {
    if (queue.isEmpty) return playQueue([t], 0);
    final fresh = await _fresh([t]);
    if (fresh.isEmpty) return;
    t = fresh.first;
    queue.insert(currentIndex + 1, t);
    notifyListeners();
    await player.insertAudioSource(currentIndex + 1, _sourceFor(t));
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
        _sleepFading = true;
        for (var v = player.volume; v > 0; v -= 0.08) {
          await player.setVolume(v.clamp(0, 1));
          await Future.delayed(const Duration(milliseconds: 300));
        }
        await player.pause();
        await player.setVolume(1);
        _sleepFading = false;
      });
    }
    notifyListeners();
  }

  // ---- Sound effects (Android) -------------------------------------------

  /// Device bands as (centre Hz, min dB, max dB). Null until audio has loaded.
  Future<AndroidEqualizerParameters?> eqParameters() async {
    if (equalizer == null) return null;
    try {
      return await equalizer!.parameters.timeout(const Duration(seconds: 3));
    } catch (_) {
      return null;
    }
  }

  List<double> get activeCurve =>
      eqPreset == EqPreset.custom ? customGains : eqPreset.curve;

  /// Interpolates a 5-point curve (log-spaced) onto the device's bands.
  static double _curveAt(List<double> curve, double hz) {
    const anchors = [60.0, 230.0, 910.0, 3600.0, 14000.0];
    if (hz <= anchors.first) return curve.first;
    if (hz >= anchors.last) return curve.last;
    for (var i = 0; i < anchors.length - 1; i++) {
      if (hz <= anchors[i + 1]) {
        final a = _log(anchors[i]), b = _log(anchors[i + 1]), x = _log(hz);
        final t = (x - a) / (b - a);
        return curve[i] + (curve[i + 1] - curve[i]) * t;
      }
    }
    return 0;
  }

  static double _log(double v) => math.log(v);

  Future<void> _applyEffects() async {
    if (equalizer == null) return;
    try {
      await equalizer!.setEnabled(eqEnabled);
      if (eqEnabled) {
        final params = await eqParameters();
        if (params != null) {
          final curve = activeCurve;
          for (final band in params.bands) {
            final g = _curveAt(curve, band.centerFrequency);
            await band.setGain(g.clamp(params.minDecibels, params.maxDecibels));
          }
        }
      }
      await loudness!.setEnabled(loudnessOn);
      if (loudnessOn) await loudness!.setTargetGain(loudnessGain);
    } catch (_) {}
  }

  Future<void> setEq({
    bool? enabled,
    EqPreset? preset,
    List<double>? custom,
  }) async {
    if (enabled != null) eqEnabled = enabled;
    if (preset != null) eqPreset = preset;
    if (custom != null) {
      customGains = custom;
      eqPreset = EqPreset.custom;
    }
    notifyListeners();
    await _applyEffects();
    await _saveSettings();
  }

  Future<void> setLoudness({bool? on, double? gain}) async {
    if (on != null) loudnessOn = on;
    if (gain != null) loudnessGain = gain;
    notifyListeners();
    await _applyEffects();
    await _saveSettings();
  }

  Future<void> setFade({bool? on, double? seconds}) async {
    if (on != null) smoothFade = on;
    if (seconds != null) fadeSeconds = seconds;
    notifyListeners();
    await _saveSettings();
  }

  Future<void> _saveSettings() async {
    try {
      final p = await SharedPreferences.getInstance();
      await p.setString(
        'sound',
        jsonEncode({
          'eqEnabled': eqEnabled,
          'eqPreset': eqPreset.name,
          'customGains': customGains,
          'loudnessOn': loudnessOn,
          'loudnessGain': loudnessGain,
          'smoothFade': smoothFade,
          'fadeSeconds': fadeSeconds,
        }),
      );
    } catch (_) {}
  }

  /// Re-reads sound settings, e.g. after a library restore.
  Future<void> reloadSettings() => _restoreSettings();

  Future<void> _restoreSettings() async {
    try {
      final p = await SharedPreferences.getInstance();
      final j =
          jsonDecode(p.getString('sound') ?? '{}') as Map<String, dynamic>;
      eqEnabled = j['eqEnabled'] as bool? ?? false;
      eqPreset = EqPreset.values.asNameMap()[j['eqPreset']] ?? EqPreset.flat;
      customGains = ((j['customGains'] as List?) ?? [0, 0, 0, 0, 0])
          .map((e) => (e as num).toDouble())
          .toList();
      loudnessOn = j['loudnessOn'] as bool? ?? false;
      loudnessGain = (j['loudnessGain'] as num?)?.toDouble() ?? 4;
      smoothFade = j['smoothFade'] as bool? ?? true;
      fadeSeconds = (j['fadeSeconds'] as num?)?.toDouble() ?? 3;
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
