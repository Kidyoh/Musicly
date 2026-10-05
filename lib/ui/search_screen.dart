import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/collection.dart';
import '../models/track.dart';
import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'collection_screen.dart';
import 'icons.dart';
import 'nav.dart';
import 'radio_screen.dart';
import 'routes.dart';
import 'theme.dart';
import 'widgets.dart';

class SearchScreen extends StatefulWidget {
  const SearchScreen({super.key});
  @override
  State<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends State<SearchScreen> {
  final _ctl = TextEditingController();
  Timer? _debounce;
  String _q = '';
  bool _loading = false;
  String? _error;
  List<Track> _songs = [];
  List<Artist> _artists = [];
  List<Collection> _albums = [];
  List<Collection> _playlists = [];
  List<Track> _free = [];
  List<Track> _stations = [];

  @override
  void dispose() {
    _debounce?.cancel();
    _ctl.dispose();
    super.dispose();
  }

  void _onChanged(String v) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 400), () => _run(v));
    setState(() => _q = v);
  }

  Future<void> _run(String q) async {
    q = q.trim();
    if (q.isEmpty) return;
    final lib = context.read<LibraryController>();
    final api = lib.api;
    // Full songs and radio load on their own so Deezer results aren't held up.
    lib.audius
        .search(q, limit: 10)
        .then((v) {
          if (mounted && q == _q.trim()) setState(() => _free = v);
        })
        .catchError((_) {});
    lib.radio
        .search(q, limit: 10)
        .then((v) {
          if (mounted && q == _q.trim()) setState(() => _stations = v);
        })
        .catchError((_) {});
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      // Each section loads on its own; only a failed song search counts as an error.
      final r = await Future.wait<List<Object>>([
        api.search(q, limit: 25),
        api.searchArtists(q, limit: 8).catchError((_) => <Artist>[]),
        api.searchAlbums(q, limit: 10).catchError((_) => <Collection>[]),
        api.searchPlaylists(q, limit: 8).catchError((_) => <Collection>[]),
      ]);
      if (!mounted || q != _q.trim()) return;
      setState(() {
        _songs = r[0] as List<Track>;
        _artists = r[1] as List<Artist>;
        _albums = r[2] as List<Collection>;
        _playlists = r[3] as List<Collection>;
      });
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'Search failed. Check your connection.');
      }
    }
    if (mounted) setState(() => _loading = false);
  }

  void _openGenre(Genre g) {
    final api = context.read<LibraryController>().api;
    openPage(
      context,
      CollectionScreen(
        title: g.name,
        owner: 'Top songs right now',
        kind: 'Genre',
        cover: Track(
          id: 'g:${g.id}',
          title: g.name,
          artist: '',
          source: TrackSource.deezer,
          artworkUrl: g.pictureUrl,
        ),
        load: () => api.chartTracks(genreId: g.id, limit: 50),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryController>();
    final c = context.read<PlayerController>();
    final p = Palette.of(context);
    final q = _q.trim().toLowerCase();
    final local = q.isEmpty
        ? <Track>[]
        : [...lib.channelTracks, ...lib.localTracks]
              .where(
                (t) =>
                    t.title.toLowerCase().contains(q) ||
                    t.artist.toLowerCase().contains(q),
              )
              .take(5)
              .toList();
    final nothing =
        _songs.isEmpty && local.isEmpty && _artists.isEmpty && _albums.isEmpty;

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
                onSubmitted: _run,
                decoration: InputDecoration(
                  hintText: 'Artists, songs, albums',
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
                  ? _genres(lib, p)
                  : _loading && nothing
                  ? const Center(child: CircularProgressIndicator())
                  : _error != null && nothing
                  ? EmptyState(icon: AppIcons.offline, text: _error!)
                  : nothing
                  ? EmptyState(
                      icon: AppIcons.search,
                      text: 'No results for "$_q"',
                    )
                  : ListView(
                      padding: const EdgeInsets.only(bottom: 24),
                      children: [
                        if (_artists.isNotEmpty) _topArtist(_artists.first, p),
                        if (_songs.isNotEmpty) ...[
                          const SectionHeader('Songs'),
                          for (var i = 0; i < _songs.length.clamp(0, 8); i++)
                            ArtTrackRow(
                              track: _songs[i],
                              onTap: () => c.playQueue(_songs, i),
                            ),
                        ],
                        if (_free.isNotEmpty) ...[
                          const SectionHeader(
                            'Free full songs',
                            subtitle: 'Audius',
                          ),
                          for (var i = 0; i < _free.length.clamp(0, 5); i++)
                            ArtTrackRow(
                              track: _free[i],
                              onTap: () => c.playQueue(_free, i),
                            ),
                        ],
                        if (_stations.isNotEmpty) ...[
                          const SectionHeader('Radio stations'),
                          HRow(
                            height: 178,
                            children: [
                              for (var i = 0; i < _stations.length; i++)
                                StationCard(
                                  station: _stations[i],
                                  onTap: () => c.playQueue(_stations, i),
                                ),
                            ],
                          ),
                        ],
                        if (_artists.length > 1) ...[
                          const SectionHeader('Artists'),
                          HRow(
                            height: 130,
                            children: [
                              for (final a in _artists.skip(1))
                                ArtistBubble(
                                  artist: a,
                                  onTap: () =>
                                      openArtist(context, a.id, a.name),
                                ),
                            ],
                          ),
                        ],
                        if (_albums.isNotEmpty) ...[
                          const SectionHeader('Albums'),
                          HRow(
                            height: 206,
                            children: [
                              for (final a in _albums)
                                CoverCard(
                                  title: a.title,
                                  subtitle: a.owner,
                                  art: Artwork(
                                    a.coverTrack,
                                    size: 148,
                                    radius: 16,
                                  ),
                                  onTap: () => openCollection(context, a),
                                ),
                            ],
                          ),
                        ],
                        if (local.isNotEmpty) ...[
                          const SectionHeader(
                            'Your music',
                            subtitle: 'Channel and phone',
                          ),
                          for (var i = 0; i < local.length; i++)
                            ArtTrackRow(
                              track: local[i],
                              onTap: () => c.playQueue(local, i),
                            ),
                        ],
                        if (_playlists.isNotEmpty) ...[
                          const SectionHeader('Playlists'),
                          for (final col in _playlists)
                            ListTile(
                              contentPadding: const EdgeInsets.symmetric(
                                horizontal: 20,
                                vertical: 4,
                              ),
                              leading: Artwork(
                                col.coverTrack,
                                size: 52,
                                radius: 12,
                              ),
                              title: Text(
                                col.title,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                              subtitle: Text(
                                '${col.owner} • ${col.trackCount ?? 0} songs',
                                style: TextStyle(color: p.sub),
                              ),
                              trailing: Icon(
                                AppIcons.chevron,
                                color: p.sub,
                                size: 18,
                              ),
                              onTap: () => openCollection(context, col),
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

  Widget _topArtist(Artist a, Palette p) => Padding(
    padding: const EdgeInsets.fromLTRB(20, 20, 20, 0),
    child: Material(
      color: p.card,
      borderRadius: BorderRadius.circular(20),
      child: InkWell(
        borderRadius: BorderRadius.circular(20),
        onTap: () => openArtist(context, a.id, a.name),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              ArtistAvatar(a, size: 72),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Top result',
                      style: TextStyle(color: p.sub, fontSize: 12),
                    ),
                    Text(
                      a.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.4,
                      ),
                    ),
                    if (a.fans != null)
                      Text(
                        'Artist • ${compact(a.fans!)} fans',
                        style: TextStyle(color: p.sub, fontSize: 13),
                      ),
                  ],
                ),
              ),
              Icon(AppIcons.chevron, color: p.sub),
            ],
          ),
        ),
      ),
    ),
  );

  Widget _genres(LibraryController lib, Palette p) {
    if (lib.genres.isEmpty) {
      return const EmptyState(
        icon: AppIcons.search,
        text: 'Find any artist, song or album.',
      );
    }
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
    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 24, 20, 0),
          sliver: SliverToBoxAdapter(child: banner),
        ),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
          sliver: SliverGrid.builder(
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 2,
              mainAxisSpacing: 12,
              crossAxisSpacing: 12,
              childAspectRatio: 1.7,
            ),
            itemCount: lib.genres.length,
            itemBuilder: (_, i) {
              final g = lib.genres[i];
              return Material(
                color: p.ink,
                borderRadius: BorderRadius.circular(18),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  onTap: () => _openGenre(g),
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      if (g.pictureUrl != null)
                        Image.network(
                          g.pictureUrl!,
                          fit: BoxFit.cover,
                          webHtmlElementStrategy:
                              WebHtmlElementStrategy.fallback,
                          errorBuilder: (_, _, _) => const SizedBox(),
                        ),
                      const DecoratedBox(
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.bottomLeft,
                            end: Alignment.topRight,
                            colors: [Color(0xE61C1D22), Color(0x331C1D22)],
                          ),
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.all(14),
                        child: Align(
                          alignment: Alignment.bottomLeft,
                          child: Text(
                            g.name,
                            style: const TextStyle(
                              color: Colors.white,
                              fontWeight: FontWeight.w700,
                              fontSize: 16,
                            ),
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
