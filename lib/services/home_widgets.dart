import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:home_widget/home_widget.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';

import '../models/track.dart';
import 'device_library.dart';
import 'telegram_bot.dart';

/// Keeps the Android home-screen widgets (MusiclyWidgets.kt) in step with
/// what's playing: title, artist, cover and play/pause.
class HomeWidgets {
  static bool get supported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  static const _providers = [
    'com.musicly.musicly.NowPlayingWidget',
    'com.musicly.musicly.MiniPlayerWidget',
  ];

  static Timer? _debounce;
  static String? _artKey;
  static String? _lastSignature;

  /// Called on every player change; writes are debounced and skipped when
  /// nothing visible changed.
  static void update(Track? t, {required bool playing, String? onAir}) {
    if (!supported) return;
    _debounce?.cancel();
    _debounce = Timer(
      const Duration(milliseconds: 300),
      () => _write(t, playing, onAir),
    );
  }

  static Future<void> _write(Track? t, bool playing, String? onAir) async {
    final subtitle = t == null
        ? null
        : (t.isRadio ? (onAir ?? t.artist) : t.artist);
    final signature = '${t?.id}|$playing|$subtitle';
    if (signature == _lastSignature) return;
    _lastSignature = signature;
    try {
      await HomeWidget.saveWidgetData('has_track', t != null);
      await HomeWidget.saveWidgetData('playing', playing);
      await HomeWidget.saveWidgetData('title', t?.title);
      await HomeWidget.saveWidgetData('artist', subtitle);
      await HomeWidget.saveWidgetData('badge', t == null ? null : _badge(t));
      if (t != null) await _saveArt(t);
      for (final p in _providers) {
        await HomeWidget.updateWidget(qualifiedAndroidName: p);
      }
    } catch (_) {
      // Widgets are a nicety; never let them break playback.
    }
  }

  static String _badge(Track t) => switch (t.source) {
    TrackSource.radio => '● LIVE',
    TrackSource.deezer => 'PREVIEW',
    TrackSource.telegram => 'TELEGRAM',
    TrackSource.audius => 'FREE',
    TrackSource.device || TrackSource.file => 'ON THIS PHONE',
  };

  static Future<void> _saveArt(Track t) async {
    final key = t.artworkUrl ?? (t.mediaId == null ? null : 'ms:${t.mediaId}');
    if (key == _artKey) return;
    _artKey = key;
    Uint8List? bytes;
    if (t.mediaId != null) {
      bytes = await DeviceLibrary.artwork(t.mediaId!);
    } else if (t.artworkUrl != null) {
      var url = t.artworkUrl!;
      if (TelegramFiles.isThumb(url)) {
        url = await TelegramFiles.resolveThumb(url) ?? '';
      }
      if (url.startsWith('http')) {
        final res = await http
            .get(Uri.parse(url))
            .timeout(const Duration(seconds: 15));
        if (res.statusCode == 200) bytes = res.bodyBytes;
      }
    }
    if (bytes == null) {
      await HomeWidget.saveWidgetData('art_path', null, deleteFile: false);
      return;
    }
    final dir = await getApplicationSupportDirectory();
    // A new name per song so the launcher never shows a stale cover.
    final file = File(
      '${dir.path}/widget_art_${key.hashCode.toUnsigned(32)}.img',
    );
    await file.writeAsBytes(bytes, flush: true);
    for (final old in dir.listSync().whereType<File>()) {
      if (old.path.contains('widget_art_') && old.path != file.path) {
        try {
          old.deleteSync();
        } catch (_) {}
      }
    }
    await HomeWidget.saveWidgetData('art_path', file.path, deleteFile: false);
  }

  static Future<bool> canPin() async {
    if (!supported) return false;
    try {
      return await HomeWidget.isRequestPinWidgetSupported() ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Asks the launcher to place a widget (Android 8+ launchers that support it).
  static Future<void> pin({bool mini = false}) => HomeWidget.requestPinWidget(
    qualifiedAndroidName: _providers[mini ? 1 : 0],
  );
}
