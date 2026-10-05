import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'collection_screen.dart';
import 'icons.dart';
import 'nav.dart';
import 'radio_screen.dart';
import 'routes.dart';
import 'sheets.dart';
import 'theme.dart';
import 'widgets.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.onOpenTab});
  final ValueChanged<int> onOpenTab;

  String _greeting() {
    final h = DateTime.now().hour;
    if (h < 5) return 'Up late?';
    if (h < 12) return 'Good morning';
    if (h < 18) return 'Good afternoon';
    return 'Good evening';
  }

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryController>();
    final c = context.read<PlayerController>();
    final p = Palette.of(context);
    final dark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: RefreshIndicator(
          onRefresh: lib.loadHome,
          child: ListView(
            padding: const EdgeInsets.only(bottom: 28),
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 16, 8, 0),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            _greeting(),
                            style: TextStyle(color: p.sub, fontSize: 14),
                          ),
                          const SizedBox(height: 2),
                          const Text(
                            'Musicly',
                            style: TextStyle(
                              fontSize: 30,
                              fontWeight: FontWeight.w700,
                              letterSpacing: -0.8,
                            ),
                          ),
                        ],
                      ),
                    ),
                    IconButton(
                      tooltip: 'Sound',
                      onPressed: () => showSound(context),
                      icon: const Icon(AppIcons.sound),
                    ),
                    IconButton(
                      tooltip: 'Theme',
                      onPressed: lib.toggleTheme,
                      icon: Icon(dark ? AppIcons.sun : AppIcons.moon),
                    ),
                  ],
                ),
              ),
              if (lib.homeError != null && lib.chart.isEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 40),
                  child: EmptyState(
                    icon: AppIcons.offline,
                    text: lib.homeError!,
                    action: OutlinedButton(
                      onPressed: lib.loadHome,
                      child: const Text('Retry'),
                    ),
                  ),
                )
              else if (lib.homeLoading && lib.chart.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(80),
                  child: Center(child: CircularProgressIndicator()),
                )
              else ...[
                const SizedBox(height: 18),
                _ForYouCard(lib: lib),
                if (lib.recent.isNotEmpty) ...[
                  const SectionHeader('Jump back in'),
                  HRow(
                    height: 196,
                    children: [
                      for (var i = 0; i < lib.recent.length.clamp(0, 12); i++)
                        CoverCard(
                          title: lib.recent[i].title,
                          subtitle: lib.recent[i].artist,
                          art: Artwork(lib.recent[i], size: 148, radius: 16),
                          onTap: () => c.playQueue(lib.recent, i),
                        ),
                    ],
                  ),
                ],
                if (lib.becauseRelated.isNotEmpty) ...[
                  SectionHeader(
                    lib.becauseArtist!.name,
                    subtitle: 'Because you like',
                  ),
                  HRow(
                    height: 130,
                    children: [
                      for (final a in lib.becauseRelated)
                        ArtistBubble(
                          artist: a,
                          onTap: () => openArtist(context, a.id, a.name),
                        ),
                    ],
                  ),
                ],
                SectionHeader(
                  'Top songs',
                  subtitle: 'Worldwide chart',
                  action: 'See all',
                  onAction: () => onOpenTab(3),
                ),
                for (var i = 0; i < lib.chart.length.clamp(0, 5); i++)
                  ArtTrackRow(
                    track: lib.chart[i],
                    onTap: () => c.playQueue(lib.chart, i),
                    leading: SizedBox(
                      width: 22,
                      child: Text(
                        '${i + 1}',
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          fontWeight: FontWeight.w800,
                          fontSize: 16,
                        ),
                      ),
                    ),
                  ),
                if (lib.stations.isNotEmpty) ...[
                  SectionHeader(
                    'Live radio',
                    subtitle: 'Real stations, streaming now',
                    action: 'See all',
                    onAction: () => openPage(context, const RadioScreen()),
                  ),
                  HRow(
                    height: 178,
                    children: [
                      for (var i = 0; i < lib.stations.length; i++)
                        StationCard(
                          station: lib.stations[i],
                          onTap: () => c.playQueue(lib.stations, i),
                        ),
                    ],
                  ),
                ],
                if (lib.freeSongs.isNotEmpty) ...[
                  SectionHeader(
                    'Free full songs',
                    subtitle: 'Independent artists on Audius',
                    action: 'See all',
                    onAction: () => openLive(
                      context,
                      'Free full songs',
                      'Audius',
                      (l) => l.freeSongs,
                      kind: 'Full-length',
                    ),
                  ),
                  for (var i = 0; i < lib.freeSongs.length.clamp(0, 5); i++)
                    ArtTrackRow(
                      track: lib.freeSongs[i],
                      onTap: () => c.playQueue(lib.freeSongs, i),
                    ),
                ],
                if (lib.topArtists.isNotEmpty) ...[
                  const SectionHeader('Popular artists'),
                  HRow(
                    height: 130,
                    children: [
                      for (final a in lib.topArtists)
                        ArtistBubble(
                          artist: a,
                          onTap: () => openArtist(context, a.id, a.name),
                        ),
                    ],
                  ),
                ],
                if (lib.topAlbums.isNotEmpty) ...[
                  const SectionHeader('Top albums'),
                  HRow(
                    height: 206,
                    children: [
                      for (final a in lib.topAlbums)
                        CoverCard(
                          title: a.title,
                          subtitle: a.owner,
                          art: Artwork(a.coverTrack, size: 148, radius: 16),
                          onTap: () => openCollection(context, a),
                        ),
                    ],
                  ),
                ],
                if (lib.topPlaylists.isNotEmpty) ...[
                  const SectionHeader('Playlists we love'),
                  HRow(
                    height: 206,
                    children: [
                      for (final pl in lib.topPlaylists)
                        CoverCard(
                          title: pl.title,
                          subtitle: '${pl.trackCount ?? 0} songs',
                          art: Artwork(pl.coverTrack, size: 148, radius: 16),
                          onTap: () => openCollection(context, pl),
                        ),
                    ],
                  ),
                ],
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// Big dark card for the personalised mix.
class _ForYouCard extends StatelessWidget {
  const _ForYouCard({required this.lib});
  final LibraryController lib;

  @override
  Widget build(BuildContext context) {
    final c = context.read<PlayerController>();
    final mix = lib.forYou;
    final p = Palette.of(context);
    final covers = <Track>[];
    final seen = <String>{};
    for (final t in mix) {
      if (t.artworkUrl != null && seen.add(t.artworkUrl!)) covers.add(t);
      if (covers.length == 3) break;
    }

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      child: Material(
        color: const Color(0xFF1C1D22),
        borderRadius: BorderRadius.circular(24),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: mix.isEmpty
              ? null
              : () => openPage(
                  context,
                  CollectionScreen(
                    title: 'Your mix',
                    owner: lib.forYouReason ?? 'Musicly',
                    kind: 'Made for you',
                    cover: covers.isEmpty ? null : covers.first,
                    live: (l) => l.forYou,
                  ),
                ),
          child: SizedBox(
            height: 176,
            child: Stack(
              children: [
                // Fanned covers on the right.
                for (var i = covers.length - 1; i >= 0; i--)
                  Positioned(
                    right: 18.0 + i * 34,
                    top: 26.0 + i * 8,
                    child: Transform.rotate(
                      angle: (i - 1) * 0.08,
                      child: Opacity(
                        opacity: 1 - i * 0.22,
                        child: Artwork(
                          covers[i],
                          size: 112 - i * 10.0,
                          radius: 14,
                          shadow: true,
                        ),
                      ),
                    ),
                  ),
                Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Icon(
                            AppIcons.sparkle,
                            color: Colors.white,
                            size: 16,
                          ),
                          const SizedBox(width: 6),
                          Text(
                            'MADE FOR YOU',
                            style: TextStyle(
                              color: Colors.white.withValues(alpha: 0.7),
                              fontSize: 11,
                              fontWeight: FontWeight.w700,
                              letterSpacing: 1.2,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      const Text(
                        'Your mix',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 26,
                          fontWeight: FontWeight.w700,
                          letterSpacing: -0.6,
                        ),
                      ),
                      const SizedBox(height: 4),
                      SizedBox(
                        width: 170,
                        child: Text(
                          lib.forYouReason ?? 'Building your mix…',
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: Colors.white.withValues(alpha: 0.6),
                            fontSize: 13,
                          ),
                        ),
                      ),
                      const Spacer(),
                      GestureDetector(
                        onTap: mix.isEmpty ? null : () => c.playShuffled(mix),
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 14,
                            vertical: 9,
                          ),
                          decoration: BoxDecoration(
                            color: Colors.white,
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(AppIcons.play, size: 14, color: p.dock),
                              const SizedBox(width: 6),
                              Text(
                                'Play mix',
                                style: TextStyle(
                                  color: p.dock,
                                  fontWeight: FontWeight.w700,
                                  fontSize: 13,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
