import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'collection_screen.dart';
import 'icons.dart';
import 'nav.dart';
import 'radio_screen.dart';
import 'telegram_screen.dart';
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
    final songs = lib.allSongs;
    final most = lib.mostPlayed.take(5).toList();

    Widget songRows(List<Track> list, {int max = 5}) => Column(
      children: [
        for (var i = 0; i < list.length.clamp(0, max); i++)
          ArtTrackRow(track: list[i], onTap: () => c.playQueue(list, i)),
      ],
    );

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: RefreshIndicator(
          onRefresh: () async {
            await Future.wait([lib.loadHome(), lib.syncTelegram()]);
          },
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
              const SizedBox(height: 18),
              _ForYouCard(lib: lib),
              if (songs.isEmpty) const _GetStarted(),
              if (lib.recent.isNotEmpty) ...[
                const SectionHeader('Jump back in'),
                HRow(
                  height: 196,
                  children: [
                    for (var i = 0; i < lib.recent.length.clamp(0, 12); i++)
                      CoverCard(
                        title: lib.recent[i].title,
                        subtitle: lib.recent[i].artist,
                        art: lib.recent[i].isRadio
                            ? StationArt(lib.recent[i], size: 148, radius: 16)
                            : Artwork(lib.recent[i], size: 148, radius: 16),
                        onTap: () => c.playQueue(lib.recent, i),
                      ),
                  ],
                ),
              ],
              if (lib.channelTracks.isNotEmpty) ...[
                SectionHeader(
                  lib.channelName ?? 'Your channel',
                  subtitle: 'New in your Telegram channel',
                  action: 'See all',
                  onAction: () => openPage(context, const TelegramScreen()),
                ),
                songRows(lib.channelTracks),
              ],
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
              if (lib.artists.isNotEmpty) ...[
                const SectionHeader('Your artists'),
                HRow(
                  height: 148,
                  children: [
                    for (final (name, list) in lib.artists.take(15))
                      ArtistBubble(
                        name: name,
                        cover: list.firstWhere(
                          (t) => t.artworkUrl != null || t.mediaId != null,
                          orElse: () => list.first,
                        ),
                        subtitle: count(list.length, 'song'),
                        onTap: () => openArtist(context, name),
                      ),
                  ],
                ),
              ],
              if (most.isNotEmpty) ...[
                SectionHeader(
                  'On repeat',
                  subtitle: 'Your most played',
                  action: 'See all',
                  onAction: () => onOpenTab(3),
                ),
                songRows(most),
              ],
              if (lib.localTracks.isNotEmpty) ...[
                SectionHeader(
                  'On this phone',
                  action: 'See all',
                  onAction: () => openLive(
                    context,
                    'On this phone',
                    'Your music',
                    (l) => l.localTracks,
                    removableFiles: true,
                  ),
                ),
                songRows(lib.localTracks),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// Shown until there's music: connect Telegram or add phone songs.
class _GetStarted extends StatelessWidget {
  const _GetStarted();

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
      child: Container(
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: p.card,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Bring your music',
              style: TextStyle(fontWeight: FontWeight.w700, fontSize: 17),
            ),
            const SizedBox(height: 6),
            Text(
              'Connect your Telegram channel or add the songs on this phone. '
              'Your mix, artists and hotlist are built from them.',
              style: TextStyle(color: p.sub, height: 1.5),
            ),
            const SizedBox(height: 14),
            PillButton(
              icon: AppIcons.telegram,
              label: 'Connect Telegram',
              onPressed: () => openPage(context, const TelegramScreen()),
            ),
          ],
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
      final key =
          t.artworkUrl ?? (t.mediaId == null ? null : 'ms:${t.mediaId}');
      if (key != null && seen.add(key)) covers.add(t);
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
                          lib.forYouReason ?? 'Connect Telegram or add phone music to get your mix',
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
