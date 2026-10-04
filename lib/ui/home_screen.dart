import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/collection.dart';
import '../state/player_controller.dart';
import 'collection_screen.dart';
import 'theme.dart';
import 'widgets.dart';

void openCollection(BuildContext context, Collection col) {
  final c = context.read<PlayerController>();
  Navigator.of(context).push(MaterialPageRoute(
    builder: (_) => CollectionScreen(
      title: col.title,
      owner: col.owner,
      kind: col.isAlbum ? 'Album' : 'Playlist',
      year: col.year,
      cover: col.coverTrack,
      load: () => c.api.playlistTracks(col.id),
    ),
  ));
}

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
    final c = context.watch<PlayerController>();
    final p = Palette.of(context);

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: RefreshIndicator(
          onRefresh: c.loadHome,
          child: ListView(padding: const EdgeInsets.only(bottom: 24), children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 12, 0),
              child: Row(children: [
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(_greeting(), style: TextStyle(color: p.sub, fontSize: 14)),
                    const SizedBox(height: 2),
                    const Text('Musicly',
                        style: TextStyle(fontSize: 30, fontWeight: FontWeight.w700, letterSpacing: -0.8)),
                  ]),
                ),
                IconButton(
                  tooltip: 'Toggle theme',
                  onPressed: c.toggleTheme,
                  icon: Icon(Theme.of(context).brightness == Brightness.dark
                      ? Icons.light_mode_outlined
                      : Icons.dark_mode_outlined),
                ),
              ]),
            ),
            if (c.recent.isNotEmpty) ...[
              const SectionHeader('Jump back in'),
              SizedBox(
                height: 196,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  itemCount: c.recent.length.clamp(0, 12),
                  separatorBuilder: (_, _) => const SizedBox(width: 14),
                  itemBuilder: (_, i) => _Card(
                    title: c.recent[i].title,
                    subtitle: c.recent[i].artist,
                    art: Artwork(c.recent[i], size: 140, radius: 18),
                    onTap: () => c.playQueue(c.recent, i),
                  ),
                ),
              ),
            ],
            const SectionHeader('Featured playlists'),
            if (c.homeLoading && c.featured.isEmpty)
              const SizedBox(height: 196, child: Center(child: CircularProgressIndicator()))
            else if (c.homeError != null && c.featured.isEmpty)
              EmptyState(
                icon: Icons.cloud_off_rounded,
                text: c.homeError!,
                action: OutlinedButton(onPressed: c.loadHome, child: const Text('Retry')),
              )
            else
              SizedBox(
                height: 196,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  itemCount: c.featured.length,
                  separatorBuilder: (_, _) => const SizedBox(width: 14),
                  itemBuilder: (_, i) {
                    final col = c.featured[i];
                    return _Card(
                      title: col.title,
                      subtitle: '${col.owner} • ${col.trackCount ?? 0} songs',
                      art: Artwork(col.coverTrack, size: 140, radius: 18),
                      onTap: () => openCollection(context, col),
                    );
                  },
                ),
              ),
            SectionHeader('Trending now', action: 'See all', onAction: () => onOpenTab(3)),
            for (var i = 0; i < c.trending.length.clamp(0, 8); i++)
              ArtTrackRow(
                track: c.trending[i],
                onTap: () => c.playQueue(c.trending, i),
              ),
          ]),
        ),
      ),
    );
  }
}

class _Card extends StatelessWidget {
  const _Card({required this.title, required this.subtitle, required this.art, required this.onTap});
  final String title;
  final String subtitle;
  final Widget art;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: onTap,
        child: SizedBox(
          width: 140,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            art,
            const SizedBox(height: 10),
            Text(title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
            const SizedBox(height: 2),
            Text(subtitle,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(color: Palette.of(context).sub, fontSize: 12)),
          ]),
        ),
      );
}
