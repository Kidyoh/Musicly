import 'dart:typed_data';

enum TrackSource { local, online }

class Track {
  Track({
    required this.id,
    required this.title,
    required this.artist,
    required this.source,
    this.uri,
    this.bytes,
    this.artworkUrl,
    this.duration,
  });

  final String id;
  final String title;
  final String artist;
  final TrackSource source;

  /// File path (local, native) or stream URL (online).
  final String? uri;

  /// In-memory audio, used for local files on web where no path exists.
  final Uint8List? bytes;
  final String? artworkUrl;
  final Duration? duration;

  /// Bytes-only tracks cannot be restored after a restart.
  bool get isPersistable => bytes == null;

  Map<String, dynamic> toJson() => {
        'id': id,
        'title': title,
        'artist': artist,
        'source': source.name,
        'uri': uri,
        'artworkUrl': artworkUrl,
        'durationMs': duration?.inMilliseconds,
      };

  factory Track.fromJson(Map<String, dynamic> j) => Track(
        id: j['id'] as String,
        title: j['title'] as String,
        artist: j['artist'] as String,
        source: TrackSource.values.byName(j['source'] as String),
        uri: j['uri'] as String?,
        artworkUrl: j['artworkUrl'] as String?,
        duration: j['durationMs'] == null
            ? null
            : Duration(milliseconds: j['durationMs'] as int),
      );
}
