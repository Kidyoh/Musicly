import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/collection.dart';
import '../models/track.dart';
import '../services/audius_api.dart';
import '../state/player_controller.dart';
import 'collection_screen.dart';
import 'home_screen.dart';
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
  List<Collection> _playlists = [];

  @override
  void dispose() {
    _debounce?.cancel();
    _ctl.dispose();
    super.dispose();
  }

  void _onChanged(String v) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 450), () => _run(v));
    setState(() => _q = v);
  }

  Future<void> _run(String q) async {
    q = q.trim();
    if (q.isEmpty) {
      setState(() {
        _songs = [];
        _playlists = [];
      });
      return;
    }
    final api = context.read<PlayerController>().api;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final r = await Future.wait([api.search(q), api.searchPlaylists(q)]);
      if (!mounted || q != _q.trim()) return;
      setState(() {
        _songs = r[0] as List<Track>;
        _playlists = r[1] as List<Collection>;
      });
    } catch (_) {
      if (mounted) setState(() => _error = 'Search failed. Check your connection.');
    }
    if (mounted) setState(() => _loading = false);
  }

  void _openGenre(String g) {
    final c = context.read<PlayerController>();
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => CollectionScreen(
        title: g,
        owner: 'Top this week',
        kind: 'Genre',
        load: () => c.api.trending(genre: g, limit: 40),
      ),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final p = Palette.of(context);
    final q = _q.trim().toLowerCase();
    final local = q.isEmpty
        ? <Track>[]
        : c.localTracks
            .where((t) => t.title.toLowerCase().contains(q) || t.artist.toLowerCase().contains(q))
            .toList();

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: Column(children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(20, 16, 20, 12),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text('Search',
                  style: TextStyle(fontSize: 30, fontWeight: FontWeight.w700, letterSpacing: -0.8)),
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
                hintText: 'Songs, artists, playlists',
                prefixIcon: const Icon(Icons.search_rounded),
                suffixIcon: _q.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.close_rounded),
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
                ? _genres(p)
                : _loading && _songs.isEmpty
                    ? const Center(child: CircularProgressIndicator())
                    : _error != null && _songs.isEmpty && local.isEmpty
                        ? EmptyState(icon: Icons.cloud_off_rounded, text: _error!)
                        : _songs.isEmpty && local.isEmpty && _playlists.isEmpty
                            ? EmptyState(icon: Icons.search_off_rounded, text: 'No results for "$_q"')
                            : ListView(padding: const EdgeInsets.only(bottom: 24), children: [
                                if (local.isNotEmpty) ...[
                                  const SectionHeader('On this device'),
                                  for (var i = 0; i < local.length; i++)
                                    ArtTrackRow(track: local[i], onTap: () => c.playQueue(local, i)),
                                ],
                                if (_songs.isNotEmpty) ...[
                                  const SectionHeader('Songs'),
                                  for (var i = 0; i < _songs.length; i++)
                                    ArtTrackRow(track: _songs[i], onTap: () => c.playQueue(_songs, i)),
                                ],
                                if (_playlists.isNotEmpty) ...[
                                  const SectionHeader('Playlists'),
                                  for (final col in _playlists)
                                    ListTile(
                                      contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
                                      leading: Artwork(col.coverTrack, size: 52, radius: 12),
                                      title: Text(col.title,
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                          style: const TextStyle(fontWeight: FontWeight.w600)),
                                      subtitle: Text('${col.owner} • ${col.trackCount ?? 0} songs',
                                          style: TextStyle(color: p.sub)),
                                      trailing: Icon(Icons.chevron_right_rounded, color: p.sub),
                                      onTap: () => openCollection(context, col),
                                    ),
                                ],
                              ]),
          ),
        ]),
      ),
    );
  }

  Widget _genres(Palette p) {
    const shades = [0.95, 0.85, 0.75, 0.65];
    return ListView(padding: const EdgeInsets.fromLTRB(20, 0, 20, 24), children: [
      const SectionHeaderInline('Browse genres'),
      GridView.builder(
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 2, mainAxisSpacing: 12, crossAxisSpacing: 12, childAspectRatio: 1.9),
        itemCount: AudiusApi.genres.length,
        itemBuilder: (_, i) {
          final g = AudiusApi.genres[i];
          return Material(
            color: p.ink.withValues(alpha: shades[i % shades.length]),
            borderRadius: BorderRadius.circular(16),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: () => _openGenre(g),
              child: Stack(children: [
                Positioned(
                  right: -10,
                  bottom: -14,
                  child: Icon(Icons.graphic_eq_rounded, size: 70, color: p.onInk.withValues(alpha: 0.12)),
                ),
                Padding(
                  padding: const EdgeInsets.all(14),
                  child: Text(g,
                      style: TextStyle(color: p.onInk, fontWeight: FontWeight.w700, fontSize: 16)),
                ),
              ]),
            ),
          );
        },
      ),
    ]);
  }
}

class SectionHeaderInline extends StatelessWidget {
  const SectionHeaderInline(this.text, {super.key});
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(0, 24, 0, 12),
        child: Text(text, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
      );
}
