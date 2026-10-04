import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../services/lyrics_api.dart';
import '../state/lyrics_controller.dart';
import '../state/player_controller.dart';
import 'theme.dart';
import 'widgets.dart';

void toast(BuildContext context, String msg) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(msg), duration: const Duration(seconds: 2)));
}

Widget _sheetTile(BuildContext context, IconData icon, String label, VoidCallback onTap,
    {Widget? trailing}) {
  return ListTile(
    contentPadding: const EdgeInsets.symmetric(horizontal: 24),
    leading: Icon(icon),
    title: Text(label, style: const TextStyle(fontWeight: FontWeight.w500)),
    trailing: trailing,
    onTap: onTap,
  );
}

void showTrackOptions(BuildContext context, Track t, {VoidCallback? onRemove}) {
  final c = context.read<PlayerController>();
  final messenger = ScaffoldMessenger.of(context);
  showModalBottomSheet(
    context: context,
    useRootNavigator: true,
    builder: (ctx) => SafeArea(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        ListTile(
          contentPadding: const EdgeInsets.symmetric(horizontal: 24),
          leading: Artwork(t, size: 48),
          title: Text(t.title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontWeight: FontWeight.w700)),
          subtitle: Text(t.artist),
        ),
        const Divider(),
        _sheetTile(ctx, c.isFavorite(t) ? Icons.favorite_rounded : Icons.favorite_border_rounded,
            c.isFavorite(t) ? 'Remove from favorites' : 'Add to favorites', () {
          c.toggleFavorite(t);
          Navigator.pop(ctx);
        }),
        _sheetTile(ctx, Icons.playlist_play_rounded, 'Play next', () {
          c.playNext(t);
          Navigator.pop(ctx);
          messenger.showSnackBar(const SnackBar(content: Text('Playing next')));
        }),
        _sheetTile(ctx, Icons.playlist_add_rounded, 'Add to queue', () {
          c.addToQueue([t]);
          Navigator.pop(ctx);
          messenger.showSnackBar(const SnackBar(content: Text('Added to queue')));
        }),
        if (onRemove != null)
          _sheetTile(ctx, Icons.delete_outline_rounded, 'Remove from library', () {
            onRemove();
            Navigator.pop(ctx);
          }),
        const SizedBox(height: 8),
      ]),
    ),
  );
}

void showSleepTimer(BuildContext context) {
  final c = context.read<PlayerController>();
  showModalBottomSheet(
    context: context,
    useRootNavigator: true,
    builder: (ctx) => SafeArea(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        const Padding(
          padding: EdgeInsets.fromLTRB(24, 0, 24, 4),
          child: Align(
              alignment: Alignment.centerLeft,
              child: Text('Sleep timer',
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700))),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
          child: Align(
            alignment: Alignment.centerLeft,
            child: Text('Music fades out gently when time is up.',
                style: TextStyle(color: Palette.of(ctx).sub)),
          ),
        ),
        for (final m in [5, 10, 15, 30, 45, 60])
          _sheetTile(ctx, Icons.bedtime_outlined, '$m minutes', () {
            c.setSleepTimer(Duration(minutes: m));
            Navigator.pop(ctx);
          }),
        _sheetTile(ctx, Icons.music_off_outlined, 'End of this song', () {
          c.setSleepTimer(null, atTrackEnd: true);
          Navigator.pop(ctx);
        }),
        if (c.sleepActive)
          _sheetTile(ctx, Icons.close_rounded, 'Turn off timer', () {
            c.setSleepTimer(null);
            Navigator.pop(ctx);
          }),
        const SizedBox(height: 8),
      ]),
    ),
  );
}

void showSpeed(BuildContext context) {
  showModalBottomSheet(
    context: context,
    useRootNavigator: true,
    builder: (ctx) => Consumer<PlayerController>(
      builder: (ctx, c, _) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Row(children: [
              const Text('Playback speed',
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
              const Spacer(),
              Text('${c.speed.toStringAsFixed(2)}x',
                  style: const TextStyle(fontWeight: FontWeight.w600)),
            ]),
            Slider(
              value: c.speed,
              min: 0.5,
              max: 2.0,
              divisions: 15,
              onChanged: c.setSpeed,
            ),
            Wrap(spacing: 8, children: [
              for (final s in [0.75, 1.0, 1.25, 1.5, 2.0])
                ChoiceChip(
                  label: Text('${s}x'),
                  selected: c.speed == s,
                  onSelected: (_) => c.setSpeed(s),
                ),
            ]),
          ]),
        ),
      ),
    ),
  );
}

void showQueue(BuildContext context) {
  showModalBottomSheet(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    builder: (_) => DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.75,
      maxChildSize: 0.95,
      builder: (ctx, scroll) => Consumer<PlayerController>(builder: (ctx, c, _) {
        final p = Palette.of(ctx);
        return Column(children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
            child: Row(children: [
              const Text('Up next', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
              const Spacer(),
              Text('${c.queue.length} songs', style: TextStyle(color: p.sub)),
            ]),
          ),
          Expanded(
            child: ReorderableListView.builder(
              scrollController: scroll,
              buildDefaultDragHandles: false,
              itemCount: c.queue.length,
              onReorderItem: c.moveInQueue,
              itemBuilder: (_, i) {
                final t = c.queue[i];
                final active = i == c.currentIndex;
                return Material(
                  key: ValueKey('${t.id}#$i'),
                  color: active ? p.card : Colors.transparent,
                  child: ListTile(
                    contentPadding: const EdgeInsets.only(left: 20, right: 8),
                    leading: Artwork(t, size: 44),
                    title: Text(t.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontWeight: FontWeight.w600)),
                    subtitle: Text(t.artist, maxLines: 1, overflow: TextOverflow.ellipsis),
                    onTap: () => c.jumpTo(i),
                    trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                      if (active)
                        Padding(
                          padding: const EdgeInsets.all(12),
                          child: EqualizerBars(playing: c.player.playing),
                        )
                      else
                        IconButton(
                            icon: Icon(Icons.close_rounded, color: p.sub),
                            onPressed: () => c.removeFromQueue(i)),
                      ReorderableDragStartListener(
                        index: i,
                        child: Padding(
                          padding: const EdgeInsets.all(8),
                          child: Icon(Icons.drag_handle_rounded, color: p.sub),
                        ),
                      ),
                    ]),
                  ),
                );
              },
            ),
          ),
        ]);
      }),
    ),
  );
}

/// Lets the user search LRCLIB and pick the right lyrics for the current song.
void showLyricsSearch(BuildContext context) {
  final c = context.read<PlayerController>();
  final t = c.current;
  if (t == null) return;
  showModalBottomSheet(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    builder: (_) => _LyricsSearchSheet(
        initial: t.artist == 'Unknown artist' ? t.title : '${t.title} ${t.artist}'),
  );
}

class _LyricsSearchSheet extends StatefulWidget {
  const _LyricsSearchSheet({required this.initial});
  final String initial;
  @override
  State<_LyricsSearchSheet> createState() => _LyricsSearchSheetState();
}

class _LyricsSearchSheetState extends State<_LyricsSearchSheet> {
  late final _ctl = TextEditingController(text: widget.initial);
  List<Lyrics>? _results;
  bool _loading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _search();
  }

  @override
  void dispose() {
    _ctl.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final q = _ctl.text.trim();
    if (q.isEmpty) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final r = await context.read<LyricsController>().api.search(q);
      r.sort((a, b) => (b.isSynced ? 1 : 0).compareTo(a.isSynced ? 1 : 0));
      if (mounted) setState(() => _results = r);
    } catch (_) {
      if (mounted) setState(() => _error = 'Search failed. Check your connection.');
    }
    if (mounted) setState(() => _loading = false);
  }

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * 0.8,
        child: Column(children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(24, 0, 24, 12),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text('Search lyrics',
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: TextField(
              controller: _ctl,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _search(),
              decoration: InputDecoration(
                hintText: 'Song title and artist',
                prefixIcon: const Icon(Icons.search_rounded),
                suffixIcon: IconButton(
                    icon: const Icon(Icons.arrow_forward_rounded), onPressed: _search),
              ),
            ),
          ),
          const SizedBox(height: 8),
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : _error != null
                    ? EmptyState(icon: Icons.cloud_off_rounded, text: _error!)
                    : (_results?.isEmpty ?? false)
                        ? const EmptyState(
                            icon: Icons.lyrics_outlined, text: 'No lyrics found. Try another search.')
                        : ListView.builder(
                            itemCount: _results?.length ?? 0,
                            itemBuilder: (_, i) {
                              final l = _results![i];
                              return ListTile(
                                contentPadding: const EdgeInsets.symmetric(horizontal: 24),
                                title: Text(l.trackName,
                                    style: const TextStyle(fontWeight: FontWeight.w600)),
                                subtitle: Text(
                                    '${l.artistName}  •  ${fmt(l.duration)}',
                                    style: TextStyle(color: p.sub)),
                                trailing: Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                                  decoration: BoxDecoration(
                                    color: l.isSynced ? p.ink : p.card,
                                    borderRadius: BorderRadius.circular(20),
                                  ),
                                  child: Text(
                                    l.isSynced ? 'Synced' : (l.instrumental ? 'Instrumental' : 'Plain'),
                                    style: TextStyle(
                                        fontSize: 12,
                                        fontWeight: FontWeight.w600,
                                        color: l.isSynced ? p.onInk : p.sub),
                                  ),
                                ),
                                onTap: () {
                                  unawaited(context.read<LyricsController>().choose(l));
                                  Navigator.pop(context);
                                },
                              );
                            },
                          ),
          ),
        ]),
      ),
    );
  }
}
