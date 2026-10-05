import 'track.dart';

/// A Telegram channel the bot reads songs from.
class TelegramChannel {
  TelegramChannel({
    required this.id,
    required this.name,
    List<Track>? tracks,
    this.latestPostId = 0,
  }) : tracks = tracks ?? [];

  final int id;
  String name;

  /// Newest first.
  final List<Track> tracks;

  /// Highest post id seen, so "Import older songs" knows where to stop.
  int latestPostId;

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'latest': latestPostId,
    'tracks': tracks.map((t) => t.toJson()).toList(),
  };

  static TelegramChannel fromJson(Map<String, dynamic> j) => TelegramChannel(
    id: (j['id'] as num).toInt(),
    name: (j['name'] as String?) ?? 'Telegram',
    latestPostId: (j['latest'] as num?)?.toInt() ?? 0,
    tracks: _tracks(j['tracks']),
  );

  /// Channels from the saved Telegram settings. Versions before 1.1 kept a
  /// single channel in top-level fields; that one becomes the first channel.
  static List<TelegramChannel> listFromSettings(Map<String, dynamic> j) {
    final list = j['channels'] as List?;
    if (list != null) {
      return [
        for (final c in list)
          TelegramChannel.fromJson(c as Map<String, dynamic>),
      ];
    }
    final id = (j['channelId'] as num?)?.toInt();
    if (id == null) return [];
    return [
      TelegramChannel(
        id: id,
        name: (j['name'] as String?) ?? 'Telegram',
        latestPostId: (j['latest'] as num?)?.toInt() ?? 0,
        tracks: _tracks(j['tracks']),
      ),
    ];
  }

  static List<Track> _tracks(Object? list) => [
    for (final e in (list as List?) ?? [])
      ?Track.tryFromJson(e as Map<String, dynamic>),
  ];

  /// Combines a backed-up channel list with the one on this phone: every
  /// current channel keeps its place and gains the songs the backup knew
  /// about. Channels only in the backup come back too when [sameBot].
  static List<TelegramChannel> merge(
    List<TelegramChannel> current,
    List<TelegramChannel> backup, {
    required bool sameBot,
  }) {
    final out = <TelegramChannel>[];
    final old = {for (final c in backup) c.id: c};
    for (final c in current) {
      final b = old.remove(c.id);
      if (b == null) {
        out.add(c);
        continue;
      }
      final seen = {for (final t in c.tracks) t.id};
      out.add(
        TelegramChannel(
          id: c.id,
          name: c.name,
          latestPostId: c.latestPostId > b.latestPostId
              ? c.latestPostId
              : b.latestPostId,
          tracks: [...c.tracks, ...b.tracks.where((t) => !seen.contains(t.id))],
        ),
      );
    }
    if (sameBot) out.addAll(old.values);
    return out;
  }
}
