/// What Musicly phones say to each other in a Jam on the same Wi-Fi.
///
/// The host runs a small web server; guests talk to it over one WebSocket
/// and find it through a broadcast "beacon" on [beaconPort].
library;

import 'dart:math';

const beaconPort = 47474;
const beaconTag = 'musicly-jam';

/// A song as guests see it: enough to show it, plus who added it.
class JamTrack {
  const JamTrack({
    required this.id,
    required this.title,
    required this.artist,
    this.durationMs,
    this.by,
    this.hasArt = false,
    this.url,
    this.artUrl,
  });

  final String id;
  final String title;
  final String artist;
  final int? durationMs;

  /// Name of the person who added it to the Jam, if not the host.
  final String? by;
  final bool hasArt;

  /// Online Jams: where the song and its cover can be downloaded.
  final String? url;
  final String? artUrl;

  Map<String, dynamic> toJson() => {
    'id': id,
    'title': title,
    'artist': artist,
    if (durationMs != null) 'dur': durationMs,
    if (by != null) 'by': by,
    if (hasArt) 'art': true,
    if (url != null) 'url': url,
    if (artUrl != null) 'artUrl': artUrl,
  };

  static JamTrack fromJson(Map j) => JamTrack(
    id: j['id'] as String,
    title: (j['title'] as String?) ?? 'Unknown',
    artist: (j['artist'] as String?) ?? 'Unknown artist',
    durationMs: (j['dur'] as num?)?.toInt(),
    by: j['by'] as String?,
    hasArt: j['art'] == true,
    url: j['url'] as String?,
    artUrl: j['artUrl'] as String?,
  );
}

/// The Jam as the host last described it.
class JamState {
  const JamState({
    this.now,
    this.playing = false,
    this.positionMs = 0,
    this.at = 0,
    this.queue = const [],
    this.people = const [],
    this.guestsControl = false,
    this.event,
    this.library,
  });

  final JamTrack? now;
  final bool playing;
  final int positionMs;

  /// Host clock (ms since epoch) when [positionMs] was read.
  final int at;
  final List<JamTrack> queue;

  /// Everyone in the Jam, host first.
  final List<String> people;
  final bool guestsControl;

  /// The latest thing that happened, e.g. "Abel added Blinding Lights".
  final String? event;

  /// Online Jams: link to the host's song list, for friends to browse.
  final String? library;

  Map<String, dynamic> toJson() => {
    't': 'state',
    if (now != null) 'now': now!.toJson(),
    'playing': playing,
    'pos': positionMs,
    'at': at,
    'queue': queue.map((t) => t.toJson()).toList(),
    'people': people,
    'control': guestsControl,
    if (event != null) 'event': event,
    if (library != null) 'lib': library,
  };

  static JamState fromJson(Map j) => JamState(
    now: j['now'] == null ? null : JamTrack.fromJson(j['now'] as Map),
    playing: j['playing'] == true,
    positionMs: (j['pos'] as num?)?.toInt() ?? 0,
    at: (j['at'] as num?)?.toInt() ?? 0,
    queue: [for (final t in (j['queue'] as List?) ?? []) JamTrack.fromJson(t)],
    people: [for (final p in (j['people'] as List?) ?? []) '$p'],
    guestsControl: j['control'] == true,
    event: j['event'] as String?,
    library: j['lib'] as String?,
  );

  JamState copyWith({List<String>? people, List<JamTrack>? queue}) => JamState(
    now: now,
    playing: playing,
    positionMs: positionMs,
    at: at,
    queue: queue ?? this.queue,
    people: people ?? this.people,
    guestsControl: guestsControl,
    event: event,
    library: library,
  );
}

/// Join codes: the host's IPv4 address and port packed into 10 letters and
/// digits, e.g. "B8M2-K4Q9-XA". Look-alike characters (0/O, 1/I/L) aren't used.
abstract final class JamCode {
  static const _abc = '23456789ABCDEFGHJKMNPQRSTUVWXYZ'; // 31 symbols
  static const _len = 10;

  static String encode(String ip, int port) {
    final parts = ip.split('.').map(int.parse).toList();
    if (parts.length != 4) throw ArgumentError('IPv4 only');
    var n = BigInt.zero;
    for (final p in parts) {
      n = (n << 8) | BigInt.from(p);
    }
    n = (n << 16) | BigInt.from(port);
    final base = BigInt.from(_abc.length);
    final out = StringBuffer();
    for (var i = 0; i < _len; i++) {
      out.write(_abc[(n % base).toInt()]);
      n = n ~/ base;
    }
    final s = out.toString().split('').reversed.join();
    return '${s.substring(0, 4)}-${s.substring(4, 8)}-${s.substring(8)}';
  }

  /// The address in a code, or null if it isn't a valid code.
  static (String, int)? decode(String code) {
    final s = code.toUpperCase().replaceAll(RegExp(r'[\s-]'), '');
    if (s.length != _len) return null;
    var n = BigInt.zero;
    final base = BigInt.from(_abc.length);
    for (final ch in s.split('')) {
      final v = _abc.indexOf(ch);
      if (v < 0) return null;
      n = n * base + BigInt.from(v);
    }
    final port = (n & BigInt.from(0xFFFF)).toInt();
    n = n >> 16;
    if (n >= BigInt.one << 32 || port == 0) return null;
    final ip = [
      for (var shift = 24; shift >= 0; shift -= 8)
        ((n >> shift) & BigInt.from(0xFF)).toInt(),
    ].join('.');
    return (ip, port);
  }
}

/// Codes for online Jams: 12 random letters and digits, e.g.
/// "K7QM-2XRB-9TFA". They name the Jam's channel on the relay, so they're
/// long enough that nobody stumbles onto it.
abstract final class OnlineJamCode {
  static const _abc = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';

  static String create() {
    final r = Random.secure();
    final s = List.generate(12, (_) => _abc[r.nextInt(_abc.length)]).join();
    return '${s.substring(0, 4)}-${s.substring(4, 8)}-${s.substring(8)}';
  }

  /// The code in its standard form, or null if it isn't an online code.
  static String? normalise(String code) {
    final s = code.toUpperCase().replaceAll(RegExp(r'[\s-]'), '');
    if (s.length != 12 || s.split('').any((c) => !_abc.contains(c))) {
      return null;
    }
    return '${s.substring(0, 4)}-${s.substring(4, 8)}-${s.substring(8)}';
  }

  /// The relay channel for a code.
  static String topic(String code) =>
      'musicly-jam-${code.replaceAll('-', '').toLowerCase()}';
}
