import 'track.dart';

/// An album or playlist, shown on the collection screen.
class Collection {
  Collection({
    required this.id,
    required this.title,
    required this.owner,
    this.artworkUrl,
    this.trackCount,
    this.year,
    this.isAlbum = false,
  });

  final String id;
  final String title;
  final String owner;
  final String? artworkUrl;
  final int? trackCount;
  final int? year;
  final bool isAlbum;

  /// A placeholder track so collection art can reuse the Artwork widget.
  Track get coverTrack => Track(
        id: id,
        title: title,
        artist: owner,
        source: TrackSource.online,
        artworkUrl: artworkUrl,
      );
}
