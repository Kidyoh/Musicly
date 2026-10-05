import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../services/radio_api.dart';
import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'icons.dart';
import 'nav.dart';
import 'radio_screen.dart';
import 'routes.dart';
import 'theme.dart';
import 'widgets.dart';

/// Searches your own music (channel + phone), artists, playlists and radio.
class SearchScreen extends StatefulWidget {
  const SearchScreen({super.key});
  @override
  State<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends State<SearchScreen> {
  final _ctl = TextEditingController();
  Timer? _debounce;
  String _q = '';
  List<Track> _stations = [];

  @override
  void dispose() {
    _debounce?.cancel();
    _ctl.dispose();
    super.dispose();
  }

  void _onChanged(String v) {
    setState(() => _q = v);
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 450), () async {
      final q = v.trim();
      if (q.isEmpty) return;
      final found = await context
          .read<LibraryController>()
          .radio
          .search(q, limit: 12)
          .catchError((_) => <Track>[]);
      if (mounted && q == _q.trim()) setState(() => _stations = found);
    });
  }

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryController>();
    final c = context.read<PlayerController>();
    final p = Palette.of(context);
    final q = _q.trim().toLowerCase();
    bool hit(Track t) =>
        t.title.toLowerCase().contains(q) ||
        t.artist.toLowerCase().contains(q) ||
        (t.album?.toLowerCase().contains(q) ?? false);
    final songs = q.isEmpty ? <Track>[] : lib.allSongs.where(hit).toList();
    final artists = q.isEmpty
        ? <(String, List<Track>)>[]
        : lib.artists.where((a) => a.$1.toLowerCase().contains(q)).toList();
    final playlists = q.isEmpty
        ? []
        : lib.playlists
              .where((pl) => pl.name.toLowerCase().contains(q))
              .toList();
    final stations = q.isEmpty ? <Track>[] : _stations;
    final nothing =
        songs.isEmpty &&
        artists.isEmpty &&
        playlists.isEmpty &&
        stations.isEmpty;

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 16, 20, 12),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  'Search',
                  style: TextStyle(
                    fontSize: 30,
                    fontWeight: FontWeight.w700,
                    letterSpacing: -0.8,
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: TextField(
                controller: _ctl,
                onChanged: _onChanged,
                textInputAction: TextInputAction.search,
                decoration: InputDecoration(
                  hintText: 'Your songs, artists, playlists, radio',
                  prefixIcon: const Icon(AppIcons.search, size: 20),
                  suffixIcon: _q.isEmpty
                      ? null
                      : IconButton(
                          icon: const Icon(AppIcons.close, size: 18),
                          onPressed: () {
                            _ctl.clear();
                            _onChanged('');
                          },
                        ),
                ),
              ),
            ),
            Expanded(
              child: q.isEmpty
                  ? _browse(p)
                  : nothing
                  ? EmptyState(
                      icon: AppIcons.search,
                      text: 'Nothing matches "$_q"',
                    )
                  : ListView(
                      padding: const EdgeInsets.only(bottom: 24),
                      children: [
                        if (artists.isNotEmpty) ...[
                          const SectionHeader('Artists'),
                          HRow(
                            height: 148,
                            children: [
                              for (final (name, list) in artists)
                                ArtistBubble(
                                  name: name,
                                  cover: list.firstWhere(
                                    (t) =>
                                        t.artworkUrl != null ||
                                        t.mediaId != null,
                                    orElse: () => list.first,
                                  ),
                                  subtitle: count(list.length, 'song'),
                                  onTap: () => openArtist(context, name),
                                ),
                            ],
                          ),
                        ],
                        if (songs.isNotEmpty) ...[
                          SectionHeader(
                            'Songs',
                            subtitle: count(songs.length, 'match'),
                          ),
                          for (var i = 0; i < songs.length.clamp(0, 30); i++)
                            ArtTrackRow(
                              track: songs[i],
                              onTap: () => c.playQueue(songs, i),
                            ),
                        ],
                        if (playlists.isNotEmpty) ...[
                          const SectionHeader('Your playlists'),
                          for (final pl in playlists)
                            ListTile(
                              contentPadding: const EdgeInsets.symmetric(
                                horizontal: 20,
                                vertical: 4,
                              ),
                              leading: Mosaic(pl.tracks, size: 52),
                              title: Text(
                                pl.name,
                                style: const TextStyle(
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                              subtitle: Text(
                                count(pl.tracks.length, 'song'),
                                style: TextStyle(color: p.sub),
                              ),
                              trailing: Icon(
                                AppIcons.chevron,
                                color: p.sub,
                                size: 18,
                              ),
                              onTap: () => openUserPlaylist(context, pl),
                            ),
                        ],
                        if (stations.isNotEmpty) ...[
                          const SectionHeader('Radio stations'),
                          HRow(
                            height: 178,
                            children: [
                              for (var i = 0; i < stations.length; i++)
                                StationCard(
                                  station: stations[i],
                                  onTap: () => c.playQueue(stations, i),
                                ),
                            ],
                          ),
                        ],
                      ],
                    ),
            ),
          ],
        ),
      ),
    );
  }

  /// Before typing: live radio and radio genres.
  Widget _browse(Palette p) {
    final banner = Material(
      color: p.ink,
      borderRadius: BorderRadius.circular(18),
      child: InkWell(
        borderRadius: BorderRadius.circular(18),
        onTap: () => openPage(context, const RadioScreen()),
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Row(
            children: [
              Icon(AppIcons.radio, color: p.onInk, size: 28),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Live radio',
                      style: TextStyle(
                        color: p.onInk,
                        fontWeight: FontWeight.w700,
                        fontSize: 17,
                      ),
                    ),
                    Text(
                      'Full songs, streaming now from stations worldwide',
                      style: TextStyle(
                        color: p.onInk.withValues(alpha: 0.65),
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
              Icon(AppIcons.chevron, color: p.onInk),
            ],
          ),
        ),
      ),
    );
    const shades = [1.0, 0.88, 0.76, 0.64];
    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 24, 20, 0),
          sliver: SliverToBoxAdapter(child: banner),
        ),
        const SliverPadding(
          padding: EdgeInsets.fromLTRB(20, 24, 20, 12),
          sliver: SliverToBoxAdapter(
            child: Text(
              'Radio by genre',
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
            ),
          ),
        ),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
          sliver: SliverGrid.builder(
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 2,
              mainAxisSpacing: 12,
              crossAxisSpacing: 12,
              childAspectRatio: 1.9,
            ),
            itemCount: RadioApi.tags.length,
            itemBuilder: (_, i) {
              final (tag, label) = RadioApi.tags[i];
              return Material(
                color: p.ink.withValues(alpha: shades[i % shades.length]),
                borderRadius: BorderRadius.circular(18),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  onTap: () =>
                      openPage(context, RadioScreen(initialFilter: tag)),
                  child: Stack(
                    children: [
                      Positioned(
                        right: -8,
                        bottom: -12,
                        child: Icon(
                          AppIcons.radio,
                          size: 64,
                          color: p.onInk.withValues(alpha: 0.12),
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.all(14),
                        child: Text(
                          label,
                          style: TextStyle(
                            color: p.onInk,
                            fontWeight: FontWeight.w700,
                            fontSize: 16,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}
