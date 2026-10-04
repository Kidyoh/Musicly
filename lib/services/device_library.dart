import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../models/track.dart';

/// Reads the phone's music library (Android MediaStore) through a small
/// platform channel implemented in MainActivity.kt.
class DeviceLibrary {
  static const _ch = MethodChannel('musicly/media');
  static final Map<int, Future<Uint8List?>> _art = {};

  static bool get supported => !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  static Future<bool> requestPermission() async {
    if (!supported) return false;
    return await _ch.invokeMethod<bool>('requestPermission') ?? false;
  }

  static Future<List<Track>> scan() async {
    if (!supported) return [];
    final raw = await _ch.invokeListMethod<Map>('scan') ?? [];
    return raw.map((m) {
      final id = (m['id'] as num).toInt();
      final artist = (m['artist'] as String?) ?? '';
      return Track(
        id: 'ms:$id',
        title: (m['title'] as String?) ?? 'Unknown',
        artist: artist.isEmpty || artist == '<unknown>' ? 'Unknown artist' : artist,
        source: TrackSource.device,
        uri: m['uri'] as String,
        duration: Duration(milliseconds: (m['duration'] as num?)?.toInt() ?? 0),
        album: m['album'] as String?,
        albumId: 'ms:${m['albumId']}',
        mediaId: id,
      );
    }).toList();
  }

  /// Embedded album art, cached for the session.
  static Future<Uint8List?> artwork(int mediaId) =>
      _art.putIfAbsent(mediaId, () async {
        try {
          return await _ch.invokeMethod<Uint8List>('artwork', {'id': mediaId});
        } catch (_) {
          return null;
        }
      });
}
