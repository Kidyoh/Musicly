import 'dart:io';

import 'jam_protocol.dart';

/// Someone in the Jam other than the host.
class JamPerson {
  JamPerson(this.id, this.name);
  final String id;
  final String name;

  /// Last time we heard from them (online Jams drop people who go quiet).
  DateTime seen = DateTime.now();
}

/// A song a friend added from their own phone: a file saved on the host
/// (same room) or a download link (online).
class JamUpload {
  JamUpload({
    required this.source,
    required this.title,
    required this.artist,
    this.art,
    this.durationMs,
  });

  /// File path or https link.
  final String source;

  /// Cover: file path or https link.
  final String? art;
  final String title;
  final String artist;
  final int? durationMs;
}

/// What the host's side needs from the app.
class JamHostCallbacks {
  const JamHostCallbacks({
    required this.state,
    required this.onAdd,
    required this.onUpload,
    required this.onControl,
    required this.search,
    required this.art,
  });

  /// The Jam right now (people are filled in by the host).
  final JamState Function() state;

  /// A friend picked one of the host's songs (by track id).
  final Future<void> Function(String trackId, JamPerson by, bool next) onAdd;

  /// A friend sent a song from their phone.
  final Future<void> Function(JamUpload song, JamPerson by, bool next) onUpload;

  /// toggle, next, previous or seek (with ms); only when friends may control.
  final void Function(String action, int? ms, JamPerson by) onControl;

  /// The host's songs matching a search (all when empty).
  final List<JamTrack> Function(String query) search;

  /// Cover image bytes for a track id.
  final Future<List<int>?> Function(String trackId) art;
}

/// Hosting a Jam, in the same room or online.
abstract class JamHosting {
  List<JamPerson> get guests;
  set guestsControl(bool on);
  set onPeopleChanged(void Function()? f);

  /// Tells everyone what's going on now.
  void pushState();
  void remove(String guestId);
  Future<void> stop({bool deleteUploads = true});
}

/// Being in someone else's Jam, in the same room or online.
abstract class JamSession {
  String get jamName;
  String get hostName;
  JamState get state;
  Stream<JamState> get states;

  /// Why the Jam ended for us: 'ended', 'removed', 'lost' or 'left'.
  Future<String> get closed;

  /// Where the host's song is right now.
  Duration get position;

  Future<List<JamTrack>> search(String query);
  void add(String trackId, {bool next = false});
  void control(String action, [int? ms]);

  /// Cover image link for a song, if there is one.
  String? artFor(JamTrack t);

  /// Sends one of this phone's songs and adds it to the Jam.
  Future<void> sendSong({
    required File audio,
    required String ext,
    required String title,
    required String artist,
    List<int>? art,
    int? durationMs,
    bool next = false,
    void Function(double done)? onProgress,
  });

  Future<void> leave();
}

class JamError implements Exception {
  const JamError(this.message);
  final String message;
  @override
  String toString() => message;
}
