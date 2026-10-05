import 'track.dart';

enum CollectionKind { album, playlist, chart, genre, mix, user }

/// An album or playlist, shown on the collection screen.
class Collection {
  Collection({
    required this.id,
    required this.title,
    required this.owner,
    required this.kind,
    this.ownerId,
    this.artworkUrl,
    this.trackCount,
    this.year,
  });

  final String id;
  final String title;
  final String owner;
  final String? ownerId;
  final CollectionKind kind;
  final String? artworkUrl;
  final int? trackCount;
  final int? year;

  String get kindLabel => switch (kind) {
    CollectionKind.album => 'Album',
    CollectionKind.playlist => 'Playlist',
    CollectionKind.chart => 'Chart',
    CollectionKind.genre => 'Genre',
    CollectionKind.mix => 'Mix',
    CollectionKind.user => 'Your playlist',
  };

  /// A placeholder track so collection art can reuse the Artwork widget.
  Track get coverTrack => Track(
    id: id,
    title: title,
    artist: owner,
    source: TrackSource.deezer,
    artworkUrl: artworkUrl,
  );
}

class Artist {
  Artist({
    required this.id,
    required this.name,
    this.pictureUrl,
    this.fans,
    this.albums,
  });
  final String id;
  final String name;
  final String? pictureUrl;
  final int? fans;
  final int? albums;
}

class Genre {
  Genre({required this.id, required this.name, this.pictureUrl});
  final int id;
  final String name;
  final String? pictureUrl;
}

/// A playlist the user made. Stored on the device.
class UserPlaylist {
  UserPlaylist({
    required this.id,
    required this.name,
    List<Track>? tracks,
    DateTime? created,
  }) : tracks = tracks ?? [],
       created = created ?? DateTime.now();

  final String id;
  String name;
  final List<Track> tracks;
  final DateTime created;

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'created': created.toIso8601String(),
    'tracks': tracks
        .where((t) => t.isPersistable)
        .map((t) => t.toJson())
        .toList(),
  };

  factory UserPlaylist.fromJson(Map<String, dynamic> j) => UserPlaylist(
    id: j['id'] as String,
    name: j['name'] as String,
    created: DateTime.tryParse(j['created'] as String? ?? ''),
    tracks: ((j['tracks'] as List?) ?? [])
        .map((e) => Track.fromJson(e as Map<String, dynamic>))
        .toList(),
  );
}
