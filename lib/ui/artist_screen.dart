import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'icons.dart';
import 'theme.dart';
import 'widgets.dart';

/// An artist from your own music: their songs from the channel and phone.
class ArtistScreen extends StatelessWidget {
  const ArtistScreen({super.key, required this.name});
  final String name;

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryController>();
    final c = context.read<PlayerController>();
    final p = Palette.of(context);
    final songs = lib.songsBy(name)
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
      body: ListView(
        padding: const EdgeInsets.only(bottom: 24),
        children: [
          Center(child: ArtistAvatar(cover: cover, size: 168)),
          const SizedBox(height: 18),
          Text(
            name,
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
              count(songs.length, 'song'),
              if (liked > 0) '$liked liked',
            ].join('  •  '),
            textAlign: TextAlign.center,
            style: TextStyle(color: p.sub),
          ),
          const SizedBox(height: 18),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Row(
              children: [
                Expanded(
                  child: PillButton(
                    icon: AppIcons.playCircle,
                    label: 'Play',
                    onPressed: songs.isEmpty
                        ? null
                        : () => c.playQueue(songs, 0),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: PillButton(
                    icon: AppIcons.shuffle,
                    label: 'Shuffle',
                    filled: false,
                    onPressed: songs.isEmpty
                        ? null
                        : () => c.playShuffled(songs),
                  ),
                ),
              ],
            ),
          ),
          if (songs.isEmpty)
            const EmptyState(
              icon: AppIcons.music,
              text: 'No songs by this artist yet.',
            )
          else
            SectionHeader(lib.plays(songs.first) > 0 ? 'Most played' : 'Songs'),
          for (var i = 0; i < songs.length; i++)
            NumberedTrackRow(
              index: i,
              track: songs[i],
              onTap: () => c.playQueue(songs, i),
            ),
        ],
      ),
    );
  }
}
