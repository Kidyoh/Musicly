import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../state/player_controller.dart';
import 'sheets.dart';
import 'theme.dart';
import 'widgets.dart';

/// Album / playlist page, laid out like the design: cover on the left,
/// meta + big title + artist on the right, Play & Shuffle, numbered list.
class CollectionScreen extends StatefulWidget {
  const CollectionScreen({
    super.key,
    required this.title,
    required this.owner,
    this.kind = 'Playlist',
    this.year,
    this.cover,
    this.load,
    this.live,
    this.removable = false,
  });

  final String title;
  final String owner;
  final String kind;
  final int? year;
  final Track? cover;
  final Future<List<Track>> Function()? load;
  final List<Track> Function(PlayerController c)? live;
  final bool removable;

  @override
  State<CollectionScreen> createState() => _CollectionScreenState();
}

enum _Sort { original, title, artist, duration }

class _CollectionScreenState extends State<CollectionScreen> {
  Future<List<Track>>? _future;
  bool _searching = false;
  String _q = '';
  _Sort _sort = _Sort.original;

  @override
  void initState() {
    super.initState();
    _future = widget.load?.call();
  }

  List<Track> _apply(List<Track> all) {
    var l = all
        .where((t) =>
            _q.isEmpty ||
            t.title.toLowerCase().contains(_q.toLowerCase()) ||
            t.artist.toLowerCase().contains(_q.toLowerCase()))
        .toList();
    switch (_sort) {
      case _Sort.title:
        l.sort((a, b) => a.title.toLowerCase().compareTo(b.title.toLowerCase()));
      case _Sort.artist:
        l.sort((a, b) => a.artist.toLowerCase().compareTo(b.artist.toLowerCase()));
      case _Sort.duration:
        l.sort((a, b) => (a.duration ?? Duration.zero).compareTo(b.duration ?? Duration.zero));
      case _Sort.original:
        break;
    }
    return l;
  }

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    if (widget.live != null) return _body(context, c, widget.live!(c), false, null);
    return FutureBuilder<List<Track>>(
      future: _future,
      builder: (context, snap) => _body(
          context,
          c,
          snap.data ?? const [],
          snap.connectionState != ConnectionState.done,
          snap.hasError ? 'Could not load songs.' : null),
    );
  }

  Widget _body(BuildContext context, PlayerController c, List<Track> all, bool loading,
      String? error) {
    final p = Palette.of(context);
    final tracks = _apply(all);
    final cover = widget.cover ?? (all.isNotEmpty ? all.first : null);
    final meta = [
      widget.kind,
      if (!loading) '${all.length} songs',
      if (widget.year != null) '${widget.year}',
    ].join(' • ');

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios_new_rounded, size: 20),
          onPressed: () => Navigator.maybePop(context),
        ),
        title: _searching
            ? TextField(
                autofocus: true,
                onChanged: (v) => setState(() => _q = v),
                decoration: const InputDecoration(
                    hintText: 'Find in list', isDense: true,
                    contentPadding: EdgeInsets.symmetric(horizontal: 14, vertical: 10)),
              )
            : null,
        actions: [
          IconButton(
            icon: Icon(_searching ? Icons.close_rounded : Icons.search_rounded),
            onPressed: () => setState(() {
              _searching = !_searching;
              _q = '';
            }),
          ),
        ],
      ),
      body: ListView(padding: const EdgeInsets.only(bottom: 24), children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Artwork(cover, size: 112, radius: 14, shadow: true),
            const SizedBox(width: 18),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(meta, style: TextStyle(color: p.sub, fontSize: 12)),
                const SizedBox(height: 4),
                Text(widget.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 26, fontWeight: FontWeight.w700, letterSpacing: -0.6, height: 1.15)),
                const SizedBox(height: 4),
                Text(widget.owner,
                    style: TextStyle(
                        color: p.sub,
                        decoration: TextDecoration.underline,
                        decorationColor: p.sub)),
                const SizedBox(height: 10),
                Row(children: [
                  _OutlineIcon(
                    icon: Icons.playlist_add_rounded,
                    tooltip: 'Add all to queue',
                    onTap: tracks.isEmpty
                        ? null
                        : () {
                            c.addToQueue(tracks);
                            toast(context, 'Added ${tracks.length} songs to queue');
                          },
                  ),
                  const SizedBox(width: 10),
                  _OutlineIcon(
                    icon: Icons.favorite_border_rounded,
                    tooltip: 'Save all to favorites',
                    onTap: tracks.isEmpty
                        ? null
                        : () {
                            c.favoriteAll(tracks);
                            toast(context, 'Saved to favorites');
                          },
                  ),
                  const SizedBox(width: 10),
                  PopupMenuButton<_Sort>(
                    tooltip: 'Sort',
                    onSelected: (s) => setState(() => _sort = s),
                    itemBuilder: (_) => [
                      for (final s in _Sort.values)
                        CheckedPopupMenuItem(
                          value: s,
                          checked: _sort == s,
                          child: Text(switch (s) {
                            _Sort.original => 'Default order',
                            _Sort.title => 'Title',
                            _Sort.artist => 'Artist',
                            _Sort.duration => 'Duration',
                          }),
                        ),
                    ],
                    child: const _OutlineIcon(icon: Icons.more_horiz_rounded, wide: true),
                  ),
                ]),
              ]),
            ),
          ]),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: Row(children: [
            Expanded(
              child: PillButton(
                icon: Icons.play_circle_outline_rounded,
                label: 'Play',
                onPressed: tracks.isEmpty ? null : () => c.playQueue(tracks, 0),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: PillButton(
                icon: Icons.shuffle_rounded,
                label: 'Shuffle',
                filled: false,
                onPressed: tracks.isEmpty ? null : () => c.playShuffled(tracks),
              ),
            ),
          ]),
        ),
        const SizedBox(height: 12),
        if (loading)
          const Padding(
              padding: EdgeInsets.all(40), child: Center(child: CircularProgressIndicator()))
        else if (error != null)
          EmptyState(icon: Icons.cloud_off_rounded, text: error)
        else if (tracks.isEmpty)
          EmptyState(
              icon: Icons.music_off_outlined,
              text: _q.isEmpty ? 'Nothing here yet.' : 'No songs match "$_q".')
        else
          for (var i = 0; i < tracks.length; i++)
            NumberedTrackRow(
              index: i,
              track: tracks[i],
              onTap: () => c.playQueue(tracks, i),
              onRemove: widget.removable ? () => c.removeLocal(tracks[i]) : null,
            ),
      ]),
    );
  }
}

class _OutlineIcon extends StatelessWidget {
  const _OutlineIcon({required this.icon, this.onTap, this.tooltip, this.wide = false});
  final IconData icon;
  final VoidCallback? onTap;
  final String? tooltip;
  final bool wide;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final box = Container(
      width: wide ? 40 : 30,
      height: 26,
      decoration: BoxDecoration(
        border: Border.all(color: p.ink, width: 1.4),
        borderRadius: BorderRadius.circular(wide ? 13 : 8),
      ),
      child: Icon(icon, size: 16),
    );
    if (onTap == null && tooltip == null) return box;
    return Tooltip(
      message: tooltip ?? '',
      child: InkWell(borderRadius: BorderRadius.circular(8), onTap: onTap, child: box),
    );
  }
}
