import 'track.dart';

/// A playlist the user made. Stored on the device (and in backups).
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
    tracks: [
      for (final e in (j['tracks'] as List?) ?? [])
        ?Track.tryFromJson(e as Map<String, dynamic>),
    ],
  );
}
