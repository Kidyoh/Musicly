import 'package:flutter/material.dart';

import '../models/collection.dart';
import '../models/track.dart';
import '../services/youtube_api.dart';
import '../state/library_controller.dart';
import 'artist_screen.dart';
import 'collection_screen.dart';
import 'nav.dart';

/// An artist's page: their songs in your library, and on YouTube.
/// [channelId] is their YouTube channel, when known.
void openArtist(BuildContext context, String name, {String? channelId}) =>
    openPage(context, ArtistScreen(name: name, channelId: channelId));

/// The artist of [t], going straight to their YouTube channel if it's known.
void openArtistOf(BuildContext context, Track t) => openArtist(
  context,
  t.artist,
  channelId: t.isYouTube ? t.artistId : null,
);

void openYouTubeArtist(BuildContext context, YouTubeArtist a) =>
    openArtist(context, a.name, channelId: a.id);

void openYouTubePlaylist(BuildContext context, YouTubePlaylist p) => openPage(
  context,
  CollectionScreen(
    title: p.title,
    owner: p.author ?? 'YouTube',
    kind: 'YouTube playlist',
    cover: p.cover,
    load: () => YouTubeApi.instance.playlistSongs(p.id),
    youtubePlaylist: p,
  ),
);

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
