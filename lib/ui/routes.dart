import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/collection.dart';
import '../models/track.dart';
import '../state/library_controller.dart';
import 'artist_screen.dart';
import 'collection_screen.dart';
import 'nav.dart';

void openArtist(BuildContext context, String id, String name) =>
    openPage(context, ArtistScreen(id: id, name: name));

void openCollection(BuildContext context, Collection col) {
  final api = context.read<LibraryController>().api;
  openPage(
    context,
    CollectionScreen(
      title: col.title,
      owner: col.owner,
      ownerArtistId: col.kind == CollectionKind.album ? col.ownerId : null,
      kind: col.kindLabel,
      year: col.year,
      cover: col.coverTrack,
      load: () => col.kind == CollectionKind.album
          ? api.albumTracks(col.id)
          : api.playlistTracks(col.id),
    ),
  );
}

void openAlbumOf(BuildContext context, Track t) {
  if (t.albumId == null) return;
  openCollection(
    context,
    Collection(
      id: t.albumId!,
      title: t.album ?? 'Album',
      owner: t.artist,
      ownerId: t.artistId,
      kind: CollectionKind.album,
      artworkUrl: t.artworkUrl,
    ),
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
