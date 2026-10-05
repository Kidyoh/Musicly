import 'dart:async';

import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import '../models/track.dart';
import '../services/lyrics_api.dart';
import '../state/library_controller.dart';
import '../state/lyrics_controller.dart';
import '../state/player_controller.dart';
import 'icons.dart';
import 'routes.dart';
import 'theme.dart';
import 'widgets.dart';

void toast(BuildContext context, String msg) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(
      SnackBar(content: Text(msg), duration: const Duration(seconds: 2)),
    );
}

Widget _tile(
  IconData icon,
  String label,
  VoidCallback onTap, {
  Widget? trailing,
  String? sub,
}) => ListTile(
  contentPadding: const EdgeInsets.symmetric(horizontal: 24),
  leading: Icon(icon, size: 22),
  title: Text(label, style: const TextStyle(fontWeight: FontWeight.w500)),
  subtitle: sub == null ? null : Text(sub),
  trailing: trailing,
  onTap: onTap,
);

Widget _sheetTitle(String title, {String? sub, Widget? trailing}) => Padding(
  padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
  child: Row(
    children: [
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
            ),
            if (sub != null)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Text(
                  sub,
                  style: const TextStyle(color: Color(0xFF8A8FA3)),
                ),
              ),
          ],
        ),
      ),
      ?trailing,
    ],
  ),
);

Future<String?> promptText(
  BuildContext context,
  String title, {
  String initial = '',
  String action = 'Create',
  String hint = 'Playlist name',
}) {
  final ctl = TextEditingController(text: initial);
  return showDialog<String>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: TextField(
        controller: ctl,
        autofocus: true,
        textCapitalization: TextCapitalization.sentences,
        decoration: InputDecoration(hintText: hint),
        onSubmitted: (v) => Navigator.pop(ctx, v),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(ctx, ctl.text),
          child: Text(action),
        ),
      ],
    ),
  );
}

void showTrackOptions(
  BuildContext context,
  Track t, {
  VoidCallback? onRemove,
  String? removeLabel,
}) {
  final c = context.read<PlayerController>();
  final lib = context.read<LibraryController>();
  final messenger = ScaffoldMessenger.of(context);
  showModalBottomSheet(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    builder: (ctx) => SafeArea(
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 24),
              leading: Artwork(t, size: 52),
              title: Text(
                t.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
              subtitle: Text(
                t.album == null ? t.artist : '${t.artist} • ${t.album}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            const Divider(),
            _tile(
              lib.isFavorite(t) ? AppIcons.heartOn : AppIcons.heart,
              lib.isFavorite(t) ? 'Remove from Liked songs' : 'Like',
              () {
                lib.toggleFavorite(t);
                Navigator.pop(ctx);
              },
            ),
            if (t.isPersistable)
              _tile(AppIcons.addPlaylist, 'Add to playlist', () {
                Navigator.pop(ctx);
                showAddToPlaylist(context, [t]);
              }),
            _tile(AppIcons.playNext, 'Play next', () {
              c.playNext(t);
              Navigator.pop(ctx);
              messenger.showSnackBar(
                const SnackBar(content: Text('Playing next')),
              );
            }),
            _tile(AppIcons.addQueue, 'Add to queue', () {
              c.addToQueue([t]);
              Navigator.pop(ctx);
              messenger.showSnackBar(
                const SnackBar(content: Text('Added to queue')),
              );
            }),
            if (t.artistId != null)
              _tile(AppIcons.artist, 'Go to artist', () {
                Navigator.pop(ctx);
                openArtist(context, t.artistId!, t.artist);
              }),
            if (t.albumId != null && t.source == TrackSource.deezer)
              _tile(AppIcons.disc, 'Go to album', () {
                Navigator.pop(ctx);
                openAlbumOf(context, t);
              }),
            if (t.source == TrackSource.deezer)
              _tile(AppIcons.radio, 'Start song radio', () async {
                Navigator.pop(ctx);
                if (t.artistId == null) return;
                final radio = await lib.api.artistRadio(t.artistId!, limit: 40);
                c.playQueue([t, ...radio.where((x) => x.id != t.id)], 0);
              }),
            if (t.link != null)
              _tile(AppIcons.external, 'Play full song on Deezer', () {
                Navigator.pop(ctx);
                launchUrl(
                  Uri.parse(t.link!),
                  mode: LaunchMode.externalApplication,
                );
              }),
            if (onRemove != null)
              _tile(AppIcons.trash, removeLabel ?? 'Remove from library', () {
                onRemove();
                Navigator.pop(ctx);
              }),
            const SizedBox(height: 8),
          ],
        ),
      ),
    ),
  );
}

void showAddToPlaylist(
  BuildContext context,
  List<Track> tracks, {
  String? suggestedName,
}) {
  final lib = context.read<LibraryController>();
  final messenger = ScaffoldMessenger.of(context);
  void done(int n, String name) => messenger.showSnackBar(
    SnackBar(
      content: Text(
        n == 0
            ? 'Already in $name'
            : 'Added ${n == 1 ? '1 song' : '$n songs'} to $name',
      ),
    ),
  );

  showModalBottomSheet(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    builder: (ctx) => SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(ctx).height * 0.7,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _sheetTitle('Add to playlist'),
            ListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 24),
              leading: Container(
                width: 52,
                height: 52,
                decoration: BoxDecoration(
                  color: Palette.of(ctx).ink,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(AppIcons.add, color: Palette.of(ctx).onInk),
              ),
              title: const Text(
                'New playlist',
                style: TextStyle(fontWeight: FontWeight.w600),
              ),
              onTap: () async {
                final name = await promptText(
                  ctx,
                  'New playlist',
                  initial: suggestedName ?? '',
                );
                if (name == null) return;
                final p = lib.createPlaylist(
                  name,
                  tracks.where((t) => t.isPersistable).toList(),
                );
                if (ctx.mounted) Navigator.pop(ctx);
                done(p.tracks.length, p.name);
              },
            ),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (final p in lib.playlists)
                    ListTile(
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 24,
                      ),
                      leading: Mosaic(p.tracks, size: 52),
                      title: Text(
                        p.name,
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                      subtitle: Text(count(p.tracks.length, 'song')),
                      onTap: () {
                        final n = lib.addToPlaylist(p, tracks);
                        Navigator.pop(ctx);
                        done(n, p.name);
                      },
                    ),
                ],
              ),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    ),
  );
}

void showSleepTimer(BuildContext context) {
  final c = context.read<PlayerController>();
  showModalBottomSheet(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    builder: (ctx) => SafeArea(
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _sheetTitle(
              'Sleep timer',
              sub: 'Music fades out gently when time is up.',
            ),
            for (final m in [5, 10, 15, 30, 45, 60])
              _tile(AppIcons.sleep, '$m minutes', () {
                c.setSleepTimer(Duration(minutes: m));
                Navigator.pop(ctx);
              }),
            _tile(AppIcons.music, 'End of this song', () {
              c.setSleepTimer(null, atTrackEnd: true);
              Navigator.pop(ctx);
            }),
            if (c.sleepActive)
              _tile(AppIcons.close, 'Turn off timer', () {
                c.setSleepTimer(null);
                Navigator.pop(ctx);
              }),
            const SizedBox(height: 8),
          ],
        ),
      ),
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
          padding: const EdgeInsets.fromLTRB(0, 0, 0, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _sheetTitle(
                'Playback speed',
                trailing: Text(
                  '${c.speed.toStringAsFixed(2)}x',
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Slider(
                  value: c.speed,
                  min: 0.5,
                  max: 2.0,
                  divisions: 15,
                  onChanged: c.setSpeed,
                ),
              ),
              Wrap(
                spacing: 8,
                children: [
                  for (final s in [0.75, 1.0, 1.25, 1.5, 2.0])
                    ChoiceChip(
                      label: Text('${s}x'),
                      selected: c.speed == s,
                      onSelected: (_) => c.setSpeed(s),
                    ),
                ],
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

/// Equalizer, loudness and smooth fades.
void showSound(BuildContext context) {
  showModalBottomSheet(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    builder: (_) => const _SoundSheet(),
  );
}

class _SoundSheet extends StatefulWidget {
  const _SoundSheet();
  @override
  State<_SoundSheet> createState() => _SoundSheetState();
}

class _SoundSheetState extends State<_SoundSheet> {
  static const _labels = ['60', '230', '910', '3.6k', '14k'];

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final p = Palette.of(context);
    final curve = c.activeCurve;

    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.only(bottom: 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sheetTitle('Sound'),
            if (!c.effectsSupported)
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
                child: Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: p.card,
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Text(
                    'Equalizer and loudness boost need Android. Smooth fades work everywhere.',
                    style: TextStyle(color: p.sub),
                  ),
                ),
              ),
            SwitchListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 24),
              secondary: const Icon(AppIcons.eq),
              title: const Text(
                'Equalizer',
                style: TextStyle(fontWeight: FontWeight.w600),
              ),
              value: c.eqEnabled,
              onChanged: c.effectsSupported ? (v) => c.setEq(enabled: v) : null,
            ),
            AnimatedOpacity(
              duration: const Duration(milliseconds: 200),
              opacity: c.eqEnabled && c.effectsSupported ? 1 : 0.4,
              child: IgnorePointer(
                ignoring: !(c.eqEnabled && c.effectsSupported),
                child: Column(
                  children: [
                    SizedBox(
                      height: 40,
                      child: ListView(
                        scrollDirection: Axis.horizontal,
                        padding: const EdgeInsets.symmetric(horizontal: 24),
                        children: [
                          for (final pr in EqPreset.values.where(
                            (e) => e != EqPreset.custom,
                          ))
                            Padding(
                              padding: const EdgeInsets.only(right: 8),
                              child: ChoiceChip(
                                label: Text(pr.label),
                                selected: c.eqPreset == pr,
                                onSelected: (_) => c.setEq(preset: pr),
                              ),
                            ),
                          ChoiceChip(
                            label: const Text('Custom'),
                            selected: c.eqPreset == EqPreset.custom,
                            onSelected: (_) => c.setEq(preset: EqPreset.custom),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 12),
                    SizedBox(
                      height: 190,
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                        children: [
                          for (var i = 0; i < 5; i++)
                            Column(
                              children: [
                                Text(
                                  '${curve[i] > 0 ? '+' : ''}${curve[i].toStringAsFixed(0)}',
                                  style: TextStyle(
                                    fontSize: 11,
                                    color: p.sub,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                Expanded(
                                  child: RotatedBox(
                                    quarterTurns: 3,
                                    child: Slider(
                                      value: curve[i].clamp(-10, 10),
                                      min: -10,
                                      max: 10,
                                      onChanged: (v) {
                                        final g = List.of(curve)..[i] = v;
                                        c.setEq(custom: g);
                                      },
                                    ),
                                  ),
                                ),
                                Text(
                                  _labels[i],
                                  style: TextStyle(fontSize: 11, color: p.sub),
                                ),
                              ],
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const Divider(height: 32),
            SwitchListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 24),
              secondary: const Icon(AppIcons.loud),
              title: const Text(
                'Loudness boost',
                style: TextStyle(fontWeight: FontWeight.w600),
              ),
              subtitle: Text(
                '+${c.loudnessGain.toStringAsFixed(0)} dB for quiet songs and speakers',
              ),
              value: c.loudnessOn,
              onChanged: c.effectsSupported
                  ? (v) => c.setLoudness(on: v)
                  : null,
            ),
            if (c.loudnessOn)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Slider(
                  value: c.loudnessGain,
                  min: 1,
                  max: 10,
                  divisions: 9,
                  onChanged: (v) => c.setLoudness(gain: v),
                ),
              ),
            SwitchListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 24),
              secondary: const Icon(AppIcons.fade),
              title: const Text(
                'Smooth fades',
                style: TextStyle(fontWeight: FontWeight.w600),
              ),
              subtitle: Text(
                'Fade songs out and in over ${c.fadeSeconds.toStringAsFixed(0)}s',
              ),
              value: c.smoothFade,
              onChanged: (v) => c.setFade(on: v),
            ),
            if (c.smoothFade)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Slider(
                  value: c.fadeSeconds,
                  min: 1,
                  max: 8,
                  divisions: 7,
                  onChanged: (v) => c.setFade(seconds: v),
                ),
              ),
          ],
        ),
      ),
    );
  }
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
      builder: (ctx, scroll) => Consumer<PlayerController>(
        builder: (ctx, c, _) {
          final p = Palette.of(ctx);
          return Column(
            children: [
              _sheetTitle(
                'Up next',
                trailing: Text(
                  count(c.queue.length, 'song'),
                  style: TextStyle(color: p.sub),
                ),
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
                        contentPadding: const EdgeInsets.only(
                          left: 20,
                          right: 8,
                        ),
                        leading: Artwork(t, size: 44),
                        title: Text(
                          t.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontWeight: FontWeight.w600),
                        ),
                        subtitle: Text(
                          t.artist,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        onTap: () => c.jumpTo(i),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (active)
                              Padding(
                                padding: const EdgeInsets.all(12),
                                child: EqualizerBars(playing: c.player.playing),
                              )
                            else
                              IconButton(
                                icon: Icon(
                                  AppIcons.close,
                                  color: p.sub,
                                  size: 18,
                                ),
                                onPressed: () => c.removeFromQueue(i),
                              ),
                            ReorderableDragStartListener(
                              index: i,
                              child: Padding(
                                padding: const EdgeInsets.all(8),
                                child: Icon(AppIcons.drag, color: p.sub),
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
        },
      ),
    ),
  );
}

/// Lets the user search LRCLIB and pick the right lyrics for the current song.
void showLyricsSearch(BuildContext context) {
  final t = context.read<PlayerController>().current;
  if (t == null) return;
  showModalBottomSheet(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    builder: (_) => _LyricsSearchSheet(
      initial: t.artist == 'Unknown artist'
          ? t.title
          : '${t.title} ${t.artist}',
    ),
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
      if (mounted) {
        setState(() => _error = 'Search failed. Check your connection.');
      }
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
        child: Column(
          children: [
            _sheetTitle('Search lyrics'),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: TextField(
                controller: _ctl,
                textInputAction: TextInputAction.search,
                onSubmitted: (_) => _search(),
                decoration: InputDecoration(
                  hintText: 'Song title and artist',
                  prefixIcon: const Icon(AppIcons.search, size: 20),
                  suffixIcon: IconButton(
                    icon: const Icon(AppIcons.chevron),
                    onPressed: _search,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 8),
            Expanded(
              child: _loading
                  ? const Center(child: CircularProgressIndicator())
                  : _error != null
                  ? EmptyState(icon: AppIcons.offline, text: _error!)
                  : (_results?.isEmpty ?? false)
                  ? const EmptyState(
                      icon: AppIcons.lyrics,
                      text: 'No lyrics found. Try another search.',
                    )
                  : ListView.builder(
                      itemCount: _results?.length ?? 0,
                      itemBuilder: (_, i) {
                        final l = _results![i];
                        return ListTile(
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 24,
                          ),
                          title: Text(
                            l.trackName,
                            style: const TextStyle(fontWeight: FontWeight.w600),
                          ),
                          subtitle: Text(
                            '${l.artistName}  •  ${fmt(l.duration)}',
                            style: TextStyle(color: p.sub),
                          ),
                          trailing: Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 10,
                              vertical: 4,
                            ),
                            decoration: BoxDecoration(
                              color: l.isSynced ? p.ink : p.card,
                              borderRadius: BorderRadius.circular(20),
                            ),
                            child: Text(
                              l.isSynced
                                  ? 'Synced'
                                  : (l.instrumental ? 'Instrumental' : 'Plain'),
                              style: TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                                color: l.isSynced ? p.onInk : p.sub,
                              ),
                            ),
                          ),
                          onTap: () {
                            unawaited(
                              context.read<LyricsController>().choose(l),
                            );
                            Navigator.pop(context);
                          },
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

/// Small helper used by Now Playing's repeat button.
IconData repeatIcon(LoopMode m) =>
    m == LoopMode.one ? AppIcons.repeatOne : AppIcons.repeat;
