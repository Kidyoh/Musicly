import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../services/youtube_api.dart';
import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'icons.dart';
import 'sheets.dart';
import 'theme.dart';
import 'widgets.dart';

/// An artist: their songs in your library (channel, phone, YouTube) and,
/// where YouTube is available, their most popular songs there.
class ArtistScreen extends StatefulWidget {
  const ArtistScreen({super.key, required this.name, this.channelId});
  final String name;

  /// Their YouTube channel, when already known.
  final String? channelId;

  @override
  State<ArtistScreen> createState() => _ArtistScreenState();
}

class _ArtistScreenState extends State<ArtistScreen> {
  Future<(YouTubeArtist?, List<Track>)>? _youtube;

  @override
  void initState() {
    super.initState();
    if (YouTubeApi.supported && widget.name != 'Unknown artist') {
      _youtube = _loadYouTube();
    }
  }

  Future<(YouTubeArtist?, List<Track>)> _loadYouTube() async {
    final api = YouTubeApi.instance;
    final id = widget.channelId;
    final artist = id != null
        ? await api
              .artist(id)
              .catchError((_) => YouTubeArtist(id: id, name: widget.name))
        : await api.findArtist(widget.name).catchError((_) => null);
    final songs = await api.artistSongs(
      artist ?? YouTubeArtist(id: '', name: widget.name),
    );
    return (artist, songs);
  }

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryController>();
    final c = context.read<PlayerController>();
    final p = Palette.of(context);
    final songs = lib.songsBy(widget.name)
      ..sort((a, b) => lib.plays(b).compareTo(lib.plays(a)));
    final cover =
        songs
            .where((t) => t.artworkUrl != null || t.mediaId != null)
            .firstOrNull ??
        songs.firstOrNull;
    final liked = songs.where(lib.isFavorite).length;

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(AppIcons.back),
          onPressed: () => Navigator.maybePop(context),
        ),
      ),
      body: FutureBuilder<(YouTubeArtist?, List<Track>)>(
        future: _youtube,
        builder: (context, snap) {
          final artist = snap.data?.$1;
          final popular = snap.data?.$2 ?? const <Track>[];
          final loading =
              _youtube != null && snap.connectionState != ConnectionState.done;
          final mine = {for (final t in songs) t.id};
          final everything = [
            ...songs,
            ...popular.where((t) => !mine.contains(t.id)),
          ];

          return ListView(
            padding: const EdgeInsets.only(bottom: 24),
            children: [
              Center(
                child: artist?.avatarUrl != null
                    ? Artwork(
                        artist!.cover,
                        size: 168,
                        radius: 84,
                        icon: AppIcons.artist,
                      )
                    : ArtistAvatar(cover: cover, size: 168),
              ),
              const SizedBox(height: 18),
              Text(
                widget.name,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 30,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.8,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                [
                  if (songs.isNotEmpty) '${count(songs.length, 'song')} in your library',
                  if (liked > 0) '$liked liked',
                  if (songs.isEmpty && popular.isNotEmpty) 'On YouTube',
                ].join('  •  '),
                textAlign: TextAlign.center,
                style: TextStyle(color: p.sub),
              ),
              if (artist != null || everything.isNotEmpty) ...[
                const SizedBox(height: 12),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    if (artist != null)
                      _Chip(
                        icon: lib.isFollowing(artist.id)
                            ? AppIcons.following
                            : AppIcons.follow,
                        label: lib.isFollowing(artist.id)
                            ? 'Following'
                            : 'Follow',
                        onTap: () => lib.toggleFollow(artist),
                      ),
                    if (artist != null && everything.isNotEmpty)
                      const SizedBox(width: 10),
                    if (everything.isNotEmpty && YouTubeApi.supported)
                      _Chip(
                        icon: AppIcons.radio,
                        label: 'Artist radio',
                        onTap: () async {
                          toast(context, 'Starting radio…');
                          await c.startRadio(everything.first);
                        },
                      ),
                  ],
                ),
              ],
              const SizedBox(height: 18),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Row(
                  children: [
                    Expanded(
                      child: PillButton(
                        icon: AppIcons.playCircle,
                        label: 'Play',
                        onPressed: everything.isEmpty
                            ? null
                            : () => c.playQueue(everything, 0),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: PillButton(
                        icon: AppIcons.shuffle,
                        label: 'Shuffle',
                        filled: false,
                        onPressed: everything.isEmpty
                            ? null
                            : () => c.playShuffled(everything),
                      ),
                    ),
                  ],
                ),
              ),
              if (songs.isNotEmpty) ...[
                SectionHeader(
                  lib.plays(songs.first) > 0 ? 'Most played' : 'Your songs',
                ),
                for (var i = 0; i < songs.length; i++)
                  NumberedTrackRow(
                    index: i,
                    track: songs[i],
                    onTap: () => c.playQueue(songs, i),
                  ),
              ],
              if (popular.isNotEmpty) ...[
                const SectionHeader(
                  'Popular on YouTube',
                  subtitle: 'Their most-played songs',
                ),
                for (var i = 0; i < popular.length; i++)
                  NumberedTrackRow(
                    index: i,
                    track: popular[i],
                    onTap: () => c.playQueue(popular, i),
                  ),
              ],
              if (loading)
                const Padding(
                  padding: EdgeInsets.all(32),
                  child: Center(child: CircularProgressIndicator()),
                )
              else if (everything.isEmpty)
                const EmptyState(
                  icon: AppIcons.music,
                  text: 'No songs by this artist yet.',
                ),
            ],
          );
        },
      ),
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip({required this.icon, required this.label, required this.onTap});
  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Material(
      color: p.card,
      borderRadius: BorderRadius.circular(20),
      child: InkWell(
        borderRadius: BorderRadius.circular(20),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 16, color: p.ink),
              const SizedBox(width: 6),
              Text(
                label,
                style: TextStyle(fontWeight: FontWeight.w600, color: p.ink),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
