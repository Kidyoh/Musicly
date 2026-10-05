import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/collection.dart';
import '../models/track.dart';
import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'icons.dart';
import 'routes.dart';
import 'theme.dart';
import 'widgets.dart';

enum _Tab { songs, albums, artists }

/// Live Deezer charts, filterable by genre.
class HotlistScreen extends StatefulWidget {
  const HotlistScreen({super.key});
  @override
  State<HotlistScreen> createState() => _HotlistScreenState();
}

class _HotlistScreenState extends State<HotlistScreen> {
  int _genre = 0;
  _Tab _tab = _Tab.songs;
  late Future<Object> _future = _load();

  Future<Object> _load() {
    final api = context.read<LibraryController>().api;
    return switch (_tab) {
      _Tab.songs => api.chartTracks(genreId: _genre, limit: 100),
      _Tab.albums => api.chartAlbums(genreId: _genre, limit: 50),
      _Tab.artists => api.chartArtists(genreId: _genre, limit: 50),
    };
  }

  void _reload() => setState(() => _future = _load());

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryController>();
    final c = context.read<PlayerController>();
    final p = Palette.of(context);

    Widget rank(int i) => SizedBox(
      width: 28,
      child: Text(
        '${i + 1}',
        textAlign: TextAlign.center,
        style: TextStyle(
          fontSize: i < 3 ? 20 : 16,
          fontWeight: FontWeight.w800,
          color: i < 3 ? p.ink : p.sub,
        ),
      ),
    );

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 12),
              child: Row(
                children: [
                  const Expanded(
                    child: Text(
                      'Hotlist',
                      style: TextStyle(
                        fontSize: 30,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.8,
                      ),
                    ),
                  ),
                  SegmentedButton<_Tab>(
                    showSelectedIcon: false,
                    style: SegmentedButton.styleFrom(
                      visualDensity: VisualDensity.compact,
                      selectedBackgroundColor: p.ink,
                      selectedForegroundColor: p.onInk,
                      side: BorderSide(color: p.line),
                    ),
                    segments: const [
                      ButtonSegment(value: _Tab.songs, label: Text('Songs')),
                      ButtonSegment(value: _Tab.albums, label: Text('Albums')),
                      ButtonSegment(
                        value: _Tab.artists,
                        label: Text('Artists'),
                      ),
                    ],
                    selected: {_tab},
                    onSelectionChanged: (s) {
                      _tab = s.first;
                      _reload();
                    },
                  ),
                ],
              ),
            ),
            SizedBox(
              height: 40,
              child: ListView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 20),
                children: [
                  for (final g in [Genre(id: 0, name: 'All'), ...lib.genres])
                    Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: ChoiceChip(
                        label: Text(g.name),
                        selected: _genre == g.id,
                        onSelected: (_) {
                          _genre = g.id;
                          _reload();
                        },
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 8),
            Expanded(
              child: FutureBuilder<Object>(
                future: _future,
                builder: (context, snap) {
                  if (snap.connectionState != ConnectionState.done) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snap.hasError) {
                    return EmptyState(
                      icon: AppIcons.offline,
                      text: 'Could not load the charts.',
                      action: OutlinedButton(
                        onPressed: _reload,
                        child: const Text('Retry'),
                      ),
                    );
                  }
                  final data = snap.data!;
                  Widget list;
                  if (data is List<Track>) {
                    list = ListView.builder(
                      padding: const EdgeInsets.only(bottom: 24),
                      itemCount: data.length,
                      itemBuilder: (_, i) => ArtTrackRow(
                        track: data[i],
                        onTap: () => c.playQueue(data, i),
                        leading: rank(i),
                      ),
                    );
                  } else if (data is List<Collection>) {
                    list = ListView.builder(
                      padding: const EdgeInsets.only(bottom: 24),
                      itemCount: data.length,
                      itemBuilder: (_, i) => ListTile(
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 20,
                          vertical: 4,
                        ),
                        leading: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            rank(i),
                            const SizedBox(width: 12),
                            Artwork(data[i].coverTrack, size: 52, radius: 12),
                          ],
                        ),
                        title: Text(
                          data[i].title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontWeight: FontWeight.w600),
                        ),
                        subtitle: Text(
                          data[i].owner,
                          style: TextStyle(color: p.sub),
                        ),
                        trailing: Icon(
                          AppIcons.chevron,
                          color: p.sub,
                          size: 18,
                        ),
                        onTap: () => openCollection(context, data[i]),
                      ),
                    );
                  } else {
                    final artists = data as List<Artist>;
                    list = ListView.builder(
                      padding: const EdgeInsets.only(bottom: 24),
                      itemCount: artists.length,
                      itemBuilder: (_, i) => ListTile(
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 20,
                          vertical: 4,
                        ),
                        leading: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            rank(i),
                            const SizedBox(width: 12),
                            ArtistAvatar(artists[i], size: 52),
                          ],
                        ),
                        title: Text(
                          artists[i].name,
                          style: const TextStyle(fontWeight: FontWeight.w600),
                        ),
                        trailing: Icon(
                          AppIcons.chevron,
                          color: p.sub,
                          size: 18,
                        ),
                        onTap: () =>
                            openArtist(context, artists[i].id, artists[i].name),
                      ),
                    );
                  }
                  if ((data as List).isEmpty) {
                    return const EmptyState(
                      icon: AppIcons.trend,
                      text: 'Nothing charting here yet.',
                    );
                  }
                  return RefreshIndicator(
                    onRefresh: () async {
                      _reload();
                      await _future;
                    },
                    child: list,
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}
