import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../models/track.dart';

/// Reads the phone's music library (Android MediaStore) through a small
/// platform channel implemented in MainActivity.kt.
class DeviceLibrary {
  static const _ch = MethodChannel('musicly/media');
  static final Map<int, Future<Uint8List?>> _art = {};

  static bool get supported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  static Future<bool> requestPermission() async {
    if (!supported) return false;
    return await _ch.invokeMethod<bool>('requestPermission') ?? false;
  }

  /// Asks once (Android 13+) so the playback notification and lock-screen
  /// controls are allowed.
  static Future<void> requestNotifications() async {
    if (!supported) return;
    try {
      await _ch.invokeMethod<bool>('requestNotifications');
    } catch (_) {}
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
        artist: artist.isEmpty || artist == '<unknown>'
            ? 'Unknown artist'
            : artist,
        source: TrackSource.device,
        uri: m['uri'] as String,
        duration: Duration(milliseconds: (m['duration'] as num?)?.toInt() ?? 0),
        album: m['album'] as String?,
        albumId: 'ms:${m['albumId']}',
        mediaId: id,
      );
    }).toList();
  }

  /// Saves a song into Music/Musicly (or finds the copy saved earlier).
  /// Returns it as a phone track.
  static Future<Track?> saveAudio({
    required String url,
    required String fileName,
    required Track like,
    String mime = 'audio/mpeg',
  }) async {
    if (!supported) return null;
    final m = await _ch.invokeMapMethod<String, dynamic>('saveAudio', {
      'url': url,
      'name': fileName,
      'mime': mime,
    });
    return m == null ? null : _copyOf(like, m);
  }

  /// A copy saved by an earlier install, so it isn't downloaded twice.
  static Future<Track?> findAudio(String fileName, Track like) async {
    if (!supported) return null;
    final m = await _ch.invokeMapMethod<String, dynamic>('findAudio', {
      'name': fileName,
    });
    return m == null ? null : _copyOf(like, m);
  }

  static Track _copyOf(Track t, Map<String, dynamic> m) {
    final id = (m['id'] as num).toInt();
    return Track(
      id: 'ms:$id',
      title: t.title,
      artist: t.artist,
      source: TrackSource.device,
      uri: m['uri'] as String,
      duration: t.duration,
      album: t.album,
      mediaId: id,
    );
  }

  /// Writes Download/Musicly/musicly-backup.json (kept after uninstalling).
  static Future<bool> saveBackupFile(String json) async {
    if (!supported) return false;
    try {
      return await _ch.invokeMethod<bool>('saveBackup', {'json': json}) ??
          false;
    } catch (_) {
      return false;
    }
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
