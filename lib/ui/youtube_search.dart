import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../services/youtube_api.dart';
import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'icons.dart';
import 'routes.dart';
import 'theme.dart';
import 'widgets.dart';

/// Before typing in YouTube mode: recent searches and genres to explore.
class YouTubeBrowse extends StatelessWidget {
  const YouTubeBrowse({super.key, required this.onSearch});
  final ValueChanged<String> onSearch;

  static const genres = [
    'Afrobeats',
    'Amapiano',
    'Hip hop',
    'R&B',
    'Pop',
    'Dance',
    'Rock',
    'Jazz',
    'Lo-fi',
    'Gospel',
    'Reggae',
    'Latin',
    'K-pop',
    'Classical',
    'Workout',
    'Chill',
  ];

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryController>();
    final p = Palette.of(context);
    const shades = [1.0, 0.88, 0.76, 0.64];
    return CustomScrollView(
      slivers: [
        if (lib.ytSearches.isNotEmpty) ...[
          SliverToBoxAdapter(
            child: SectionHeader(
              'Recent searches',
              action: 'Clear',
              onAction: lib.clearYtSearches,
            ),
          ),
          SliverList.builder(
            itemCount: lib.ytSearches.length.clamp(0, 8),
            itemBuilder: (_, i) {
              final q = lib.ytSearches[i];
              return ListTile(
                contentPadding: const EdgeInsets.only(left: 20, right: 8),
                leading: Icon(AppIcons.history, color: p.sub, size: 20),
                title: Text(q),
                onTap: () => onSearch(q),
                trailing: IconButton(
                  icon: Icon(AppIcons.close, color: p.sub, size: 16),
                  onPressed: () => lib.removeYtSearch(q),
                ),
              );
            },
          ),
        ],
        const SliverPadding(
          padding: EdgeInsets.fromLTRB(20, 24, 20, 12),
          sliver: SliverToBoxAdapter(
            child: Text(
              'Explore genres',
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
            itemCount: genres.length,
            itemBuilder: (_, i) => Material(
              color: p.ink.withValues(alpha: shades[i % shades.length]),
              borderRadius: BorderRadius.circular(18),
              clipBehavior: Clip.antiAlias,
              child: InkWell(
                onTap: () => onSearch('${genres[i]} hits'),
                child: Stack(
                  children: [
                    Positioned(
                      right: -8,
                      bottom: -12,
                      child: Icon(
                        AppIcons.youtube,
                        size: 64,
                        color: p.onInk.withValues(alpha: 0.12),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.all(14),
                      child: Text(
                        genres[i],
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
            ),
          ),
        ),
      ],
    );
  }
}

/// Songs, artists and playlists on YouTube for [query], with search
/// suggestions as you type.
class YouTubeResults extends StatefulWidget {
  const YouTubeResults({
    super.key,
    required this.query,
    required this.onSearch,
  });
  final String query;
  final ValueChanged<String> onSearch;

  @override
  State<YouTubeResults> createState() => _YouTubeResultsState();
}

class _Found {
  _Found(this.songs, this.artists, this.playlists);
  final List<Track> songs;
  final List<YouTubeArtist> artists;
  final List<YouTubePlaylist> playlists;
  bool get isEmpty => songs.isEmpty && artists.isEmpty && playlists.isEmpty;
}

class _YouTubeResultsState extends State<YouTubeResults> {
  final _api = YouTubeApi.instance;
  Timer? _debounce;
  Timer? _suggestDebounce;
  _Found? _found;
  String? _foundFor;
  bool _loading = false;
  bool _failed = false;
  List<String> _suggestions = [];

  @override
  void initState() {
    super.initState();
    _schedule();
  }

  @override
  void didUpdateWidget(YouTubeResults old) {
    super.didUpdateWidget(old);
    if (old.query != widget.query) _schedule();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _suggestDebounce?.cancel();
    super.dispose();
  }

  void _schedule() {
    final q = widget.query;
    _suggestDebounce?.cancel();
    _suggestDebounce = Timer(const Duration(milliseconds: 200), () async {
      final s = await _api.suggestions(q);
      if (mounted && q == widget.query) setState(() => _suggestions = s);
    });
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 650), () => _load(q));
  }

  Future<void> _load(String q) async {
    if (q == _foundFor) return;
    setState(() {
      _loading = true;
      _failed = false;
    });
    try {
      final r = await Future.wait([
        _api.songs(q, anything: true),
        _api.artists(q).catchError((_) => <YouTubeArtist>[]),
        _api.playlists(q).catchError((_) => <YouTubePlaylist>[]),
      ]);
      if (!mounted || q != widget.query) return;
      setState(() {
        _found = _Found(
          r[0] as List<Track>,
          (r[1] as List<YouTubeArtist>).take(10).toList(),
          (r[2] as List<YouTubePlaylist>).take(12).toList(),
        );
        _foundFor = q;
        _loading = false;
      });
    } catch (_) {
      if (!mounted || q != widget.query) return;
      setState(() {
        _loading = false;
        _failed = true;
      });
    }
  }

  void _remember() =>
      context.read<LibraryController>().addYtSearch(widget.query);

  @override
  Widget build(BuildContext context) {
    final c = context.read<PlayerController>();
    final p = Palette.of(context);
    final found = _found;
    final suggestions = _suggestions
        .where((s) => s.toLowerCase() != widget.query.toLowerCase())
        .take(8)
        .toList();

    return Column(
      children: [
        SizedBox(
          height: 3,
          child: _loading ? const LinearProgressIndicator(minHeight: 3) : null,
        ),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.only(bottom: 24),
            children: [
              if (suggestions.isNotEmpty)
                SizedBox(
                  height: 52,
                  child: ListView(
                    scrollDirection: Axis.horizontal,
                    padding: const EdgeInsets.fromLTRB(20, 10, 20, 2),
                    children: [
                      for (final s in suggestions)
                        Padding(
                          padding: const EdgeInsets.only(right: 8),
                          child: ActionChip(
                            avatar: Icon(AppIcons.search, size: 14, color: p.sub),
                            label: Text(s),
                            onPressed: () => widget.onSearch(s),
                          ),
                        ),
                    ],
                  ),
                ),
              if (_failed && found == null)
                EmptyState(
                  icon: AppIcons.offline,
                  text: 'Could not reach YouTube.',
                  action: OutlinedButton(
                    onPressed: () {
                      _foundFor = null;
                      _load(widget.query);
                    },
                    child: const Text('Retry'),
                  ),
                )
              else if (found != null && found.isEmpty && !_loading)
                EmptyState(
                  icon: AppIcons.youtube,
                  text: 'Nothing on YouTube for "${widget.query}"',
                ),
              if (found != null) ...[
                if (found.artists.isNotEmpty) ...[
                  const SectionHeader('Artists'),
                  HRow(
                    height: 132,
                    children: [
                      for (final a in found.artists)
                        ArtistBubble(
                          name: a.name,
                          cover: a.cover,
                          onTap: () {
                            _remember();
                            openYouTubeArtist(context, a);
                          },
                        ),
                    ],
                  ),
                ],
                if (found.songs.isNotEmpty) ...[
                  SectionHeader(
                    'Videos',
                    action: 'Play all',
                    onAction: () {
                      _remember();
                      c.playQueue(found.songs, 0);
                    },
                  ),
                  for (var i = 0; i < found.songs.length; i++)
                    ArtTrackRow(
                      track: found.songs[i],
                      onTap: () {
                        _remember();
                        c.playQueue(found.songs, i);
                      },
                    ),
                ],
                if (found.playlists.isNotEmpty) ...[
                  const SectionHeader('Playlists'),
                  HRow(
                    height: 206,
                    children: [
                      for (final pl in found.playlists)
                        CoverCard(
                          title: pl.title,
                          subtitle: pl.count == null
                              ? 'Playlist'
                              : count(pl.count!, 'song'),
                          art: Artwork(pl.cover, size: 148, radius: 16),
                          onTap: () {
                            _remember();
                            openYouTubePlaylist(context, pl);
                          },
                        ),
                    ],
                  ),
                ],
              ],
            ],
          ),
        ),
      ],
    );
  }
}
