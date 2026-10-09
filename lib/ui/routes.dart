import 'package:flutter/material.dart';

import '../models/collection.dart';
import '../models/track.dart';
import '../state/library_controller.dart';
import 'artist_screen.dart';
import 'collection_screen.dart';
import 'nav.dart';

void openArtist(BuildContext context, String name) =>
    openPage(context, ArtistScreen(name: name));

/// Album of a song on the phone.
void openAlbumOf(BuildContext context, Track t) {
  if (t.albumId == null) return;
  openLive(
    context,
    t.album ?? 'Album',
    t.artist,
    (l) => l.deviceTracks.where((x) => x.albumId == t.albumId).toList(),
    kind: 'Album',
    cover: t,
  );
}

void openUserPlaylist(BuildContext context, UserPlaylist p) =>
    openPage(context, CollectionScreen.user(playlistId: p.id));

void openLive(
  BuildContext context,
  String title,
  String owner,
  List<Track> Function(LibraryController) live, {
  String kind = 'Collection',
  Track? cover,
  bool removableFiles = false,
}) => openPage(
  context,
  CollectionScreen(
    title: title,
    owner: owner,
    kind: kind,
    cover: cover,
    live: live,
    removableFiles: removableFiles,
  ),
);
