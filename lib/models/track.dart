import 'dart:typed_data';

enum TrackSource { deezer, device, file }

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
    this.artistId,
    this.album,
    this.albumId,
    this.mediaId,
    this.link,
  });

  /// `dz:<id>` for Deezer, `ms:<id>` for the phone library, `file:<path>` for picked files.
  final String id;
  final String title;
  final String artist;
  final TrackSource source;

  /// File path or content:// URI for local songs; for Deezer, the signed
  /// preview link from when the song was listed (refreshed when it expires).
  final String? uri;

  /// In-memory audio for files picked on web, where no path exists.
  final Uint8List? bytes;
  final String? artworkUrl;
  final Duration? duration;
  final String? artistId;
  final String? album;
  final String? albumId;

  /// MediaStore id, used to load embedded album art on Android.
  final int? mediaId;

  /// Deezer web page for the full song.
  final String? link;

  Track withUri(String? newUri) => Track(
        id: id,
        title: title,
        artist: artist,
        source: source,
        uri: newUri,
        bytes: bytes,
        artworkUrl: artworkUrl,
        duration: duration,
        artistId: artistId,
        album: album,
        albumId: albumId,
        mediaId: mediaId,
        link: link,
      );

  bool get isPreview => source == TrackSource.deezer;
  bool get isLocal => source != TrackSource.deezer;
  int? get deezerId => source == TrackSource.deezer ? int.tryParse(id.substring(3)) : null;

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
        'artistId': artistId,
        'album': album,
        'albumId': albumId,
        'mediaId': mediaId,
        'link': link,
      };

  factory Track.fromJson(Map<String, dynamic> j) => Track(
        id: j['id'] as String,
        title: j['title'] as String,
        artist: j['artist'] as String,
        source: TrackSource.values.asNameMap()[j['source']] ?? TrackSource.file,
        uri: j['uri'] as String?,
        artworkUrl: j['artworkUrl'] as String?,
        duration: j['durationMs'] == null
            ? null
            : Duration(milliseconds: (j['durationMs'] as num).toInt()),
        artistId: j['artistId'] as String?,
        album: j['album'] as String?,
        albumId: j['albumId'] as String?,
        mediaId: (j['mediaId'] as num?)?.toInt(),
        link: j['link'] as String?,
      );
}
