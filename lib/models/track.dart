import 'dart:typed_data';

/// radio: live stations · telegram: your channel · device/file: music on the phone.
enum TrackSource { radio, telegram, device, file }

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
    this.mime,
  });

  /// `rb:` radio, `tg:` Telegram, `ms:` phone library, `file:` picked files.
  final String id;
  final String title;
  final String artist;
  final TrackSource source;

  /// File path or content:// URI for phone songs, the stream link for radio,
  /// and the Telegram file id for channel songs.
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

  /// Web page (radio station homepage).
  final String? link;

  /// File type, e.g. audio/mpeg (Telegram songs; used when saving them).
  final String? mime;

  bool get isLocal =>
      source == TrackSource.device || source == TrackSource.file;
  bool get isRadio => source == TrackSource.radio;

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
    'mime': mime,
  };

  /// Null for songs from sources Musicly no longer has (old 30s previews).
  static Track? tryFromJson(Map<String, dynamic> j) {
    if (TrackSource.values.asNameMap()[j['source']] == null) return null;
    try {
      return Track.fromJson(j);
    } catch (_) {
      return null;
    }
  }

  factory Track.fromJson(Map<String, dynamic> j) => Track(
    id: j['id'] as String,
    title: j['title'] as String,
    artist: j['artist'] as String,
    source: TrackSource.values.byName(j['source'] as String),
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
    mime: j['mime'] as String?,
  );
}
