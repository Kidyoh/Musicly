import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../state/player_controller.dart';
import 'mini_player.dart';
import 'widgets.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.onToggleTheme});
  final VoidCallback onToggleTheme;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int _tab = 0;
  final _search = TextEditingController();

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final titles = ['Library', 'Discover', 'Favorites'];
    return Scaffold(
      appBar: AppBar(
        title: Text(titles[_tab],
            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 28)),
        centerTitle: false,
        actions: [
          if (_tab == 0)
            IconButton(
                tooltip: 'Add music from device',
                onPressed: c.pickLocalFiles,
                icon: const Icon(Icons.library_add_rounded)),
          IconButton(
              tooltip: 'Toggle theme',
              onPressed: widget.onToggleTheme,
              icon: const Icon(Icons.contrast_rounded)),
        ],
      ),
      body: IndexedStack(index: _tab, children: [
        _LibraryTab(c),
        _DiscoverTab(c, _search),
        _FavoritesTab(c),
      ]),
      bottomNavigationBar: Column(mainAxisSize: MainAxisSize.min, children: [
        const MiniPlayer(),
        NavigationBar(
          selectedIndex: _tab,
          onDestinationSelected: (i) => setState(() => _tab = i),
          destinations: const [
            NavigationDestination(
                icon: Icon(Icons.library_music_outlined),
                selectedIcon: Icon(Icons.library_music_rounded),
                label: 'Library'),
            NavigationDestination(
                icon: Icon(Icons.explore_outlined),
                selectedIcon: Icon(Icons.explore_rounded),
                label: 'Discover'),
            NavigationDestination(
                icon: Icon(Icons.favorite_border_rounded),
                selectedIcon: Icon(Icons.favorite_rounded),
                label: 'Favorites'),
          ],
        ),
      ]),
    );
  }
}

class _LibraryTab extends StatefulWidget {
  const _LibraryTab(this.c);
  final PlayerController c;
  @override
  State<_LibraryTab> createState() => _LibraryTabState();
}

class _LibraryTabState extends State<_LibraryTab> {
  String _q = '';

  @override
  Widget build(BuildContext context) {
    final c = widget.c;
    if (c.localTracks.isEmpty && c.recent.isEmpty) {
      return EmptyState(
        icon: Icons.folder_open_rounded,
        text: 'Your library is empty.\nAdd songs from your device to start listening.',
        action: FilledButton.icon(
            onPressed: c.pickLocalFiles,
            icon: const Icon(Icons.add_rounded),
            label: const Text('Add music')),
      );
    }
    final tracks = c.localTracks
        .where((t) => t.title.toLowerCase().contains(_q.toLowerCase()))
        .toList();
    return ListView(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
        child: SearchBar(
          hintText: 'Search your library',
          leading: const Icon(Icons.search_rounded),
          elevation: const WidgetStatePropertyAll(0),
          onChanged: (v) => setState(() => _q = v),
        ),
      ),
      if (c.recent.isNotEmpty && _q.isEmpty) ...[
        const _Header('Recently played'),
        SizedBox(
          height: 150,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16),
            itemCount: c.recent.length,
            separatorBuilder: (_, _) => const SizedBox(width: 12),
            itemBuilder: (_, i) => GestureDetector(
              onTap: () => c.playQueue(c.recent, i),
              child: SizedBox(
                width: 104,
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Artwork(c.recent[i], size: 104, radius: 16),
                  const SizedBox(height: 6),
                  Text(c.recent[i].title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                ]),
              ),
            ),
          ),
        ),
      ],
      if (tracks.isNotEmpty) ...[
        _Header('Songs · ${tracks.length}'),
        for (var i = 0; i < tracks.length; i++)
          TrackTile(
            track: tracks[i],
            onTap: () => c.playQueue(tracks, i),
            onRemove: () => c.removeLocal(tracks[i]),
          ),
      ],
    ]);
  }
}

class _DiscoverTab extends StatelessWidget {
  const _DiscoverTab(this.c, this.search);
  final PlayerController c;
  final TextEditingController search;

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
        child: SearchBar(
          controller: search,
          hintText: 'Search songs, artists',
          leading: const Icon(Icons.search_rounded),
          elevation: const WidgetStatePropertyAll(0),
          onSubmitted: c.searchOnline,
          trailing: [
            if (search.text.isNotEmpty)
              IconButton(
                  icon: const Icon(Icons.close_rounded),
                  onPressed: () {
                    search.clear();
                    c.loadTrending();
                  }),
          ],
        ),
      ),
      Expanded(
        child: c.onlineLoading
            ? const Center(child: CircularProgressIndicator())
            : c.onlineError != null && c.onlineTracks.isEmpty
                ? EmptyState(
                    icon: Icons.cloud_off_rounded,
                    text: c.onlineError!,
                    action: OutlinedButton(
                        onPressed: c.loadTrending, child: const Text('Retry')))
                : ListView.builder(
                    itemCount: c.onlineTracks.length,
                    itemBuilder: (_, i) => TrackTile(
                      track: c.onlineTracks[i],
                      onTap: () => c.playQueue(c.onlineTracks, i),
                    ),
                  ),
      ),
    ]);
  }
}

class _FavoritesTab extends StatelessWidget {
  const _FavoritesTab(this.c);
  final PlayerController c;

  @override
  Widget build(BuildContext context) {
    if (c.favorites.isEmpty) {
      return const EmptyState(
          icon: Icons.favorite_border_rounded,
          text: 'Songs you favorite show up here.\nUse the ⋯ menu on any song.');
    }
    return ListView.builder(
      itemCount: c.favorites.length,
      itemBuilder: (_, i) => TrackTile(
          track: c.favorites[i], onTap: () => c.playQueue(c.favorites, i)),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
        child: Text(text,
            style: Theme.of(context)
                .textTheme
                .titleMedium
                ?.copyWith(fontWeight: FontWeight.w700)),
      );
}
