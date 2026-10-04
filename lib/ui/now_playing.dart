import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:just_audio/just_audio.dart';
import 'package:provider/provider.dart';

import '../state/lyrics_controller.dart';
import '../state/player_controller.dart';
import 'lyrics_view.dart';
import 'sheets.dart';
import 'theme.dart';
import 'widgets.dart';

void openNowPlaying(BuildContext context, {bool lyrics = false}) {
  Navigator.of(context, rootNavigator: true).push(PageRouteBuilder(
    opaque: true,
    transitionDuration: const Duration(milliseconds: 420),
    reverseTransitionDuration: const Duration(milliseconds: 320),
    pageBuilder: (_, _, _) => NowPlayingScreen(startOnLyrics: lyrics),
    transitionsBuilder: (_, anim, _, child) => SlideTransition(
      position: Tween(begin: const Offset(0, 1), end: Offset.zero)
          .animate(CurvedAnimation(parent: anim, curve: Curves.easeOutCubic)),
      child: child,
    ),
  ));
}

class NowPlayingScreen extends StatefulWidget {
  const NowPlayingScreen({super.key, this.startOnLyrics = false});
  final bool startOnLyrics;
  @override
  State<NowPlayingScreen> createState() => _NowPlayingScreenState();
}

class _NowPlayingScreenState extends State<NowPlayingScreen> {
  late bool _lyrics = widget.startOnLyrics;
  double _dragY = 0;

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final p = Palette.of(context);
    final t = c.current;
    if (t == null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) Navigator.maybePop(context);
      });
      return const Scaffold();
    }

    return GestureDetector(
      // Swipe down to close.
      onVerticalDragUpdate: (d) => setState(() => _dragY = (_dragY + d.delta.dy).clamp(0, 400)),
      onVerticalDragEnd: (d) {
        if (_dragY > 120 || d.velocity.pixelsPerSecond.dy > 900) {
          Navigator.pop(context);
        } else {
          setState(() => _dragY = 0);
        }
      },
      child: Transform.translate(
        offset: Offset(0, _dragY),
        child: Scaffold(
          appBar: AppBar(
            leading: IconButton(
              icon: const Icon(Icons.keyboard_arrow_down_rounded, size: 30),
              onPressed: () => Navigator.pop(context),
            ),
            title: Text(_lyrics ? 'Lyrics' : 'Now Playing'),
            actions: [
              IconButton(
                tooltip: 'Queue',
                icon: const Icon(Icons.queue_music_rounded),
                onPressed: () => showQueue(context),
              ),
            ],
          ),
          body: SafeArea(
            top: false,
            child: LayoutBuilder(builder: (context, box) {
              final art = (box.maxWidth - 120).clamp(180.0, 340.0);
              return Column(children: [
                Expanded(
                  child: AnimatedSwitcher(
                    duration: const Duration(milliseconds: 350),
                    switchInCurve: Curves.easeOutCubic,
                    transitionBuilder: (child, a) => FadeTransition(
                      opacity: a,
                      child: ScaleTransition(
                          scale: Tween(begin: 0.96, end: 1.0).animate(a), child: child),
                    ),
                    child: _lyrics
                        ? const Padding(
                            key: ValueKey('lyrics'),
                            padding: EdgeInsets.symmetric(horizontal: 24),
                            child: LyricsView(),
                          )
                        : GestureDetector(
                            key: const ValueKey('art'),
                            onTap: () => setState(() => _lyrics = true),
                            child: Center(
                              child: AnimatedScale(
                                scale: c.player.playing ? 1 : 0.92,
                                duration: const Duration(milliseconds: 400),
                                curve: Curves.easeOutBack,
                                child: Artwork(t, size: art, radius: 26, shadow: true),
                              ),
                            ),
                          ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 28),
                  child: Column(children: [
                    const SizedBox(height: 20),
                    Row(children: [
                      IconButton(
                        onPressed: () {
                          HapticFeedback.lightImpact();
                          c.toggleFavorite(t);
                        },
                        icon: AnimatedSwitcher(
                          duration: const Duration(milliseconds: 250),
                          transitionBuilder: (ch, a) => ScaleTransition(scale: a, child: ch),
                          child: Icon(
                            c.isFavorite(t) ? Icons.favorite_rounded : Icons.favorite_border_rounded,
                            key: ValueKey(c.isFavorite(t)),
                            color: c.isFavorite(t) ? const Color(0xFFE5484D) : p.sub,
                          ),
                        ),
                      ),
                      Expanded(
                        child: Text(t.title,
                            textAlign: TextAlign.center,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                                fontSize: 26, fontWeight: FontWeight.w700, letterSpacing: -0.6)),
                      ),
                      IconButton(
                        icon: Icon(Icons.more_horiz_rounded, color: p.sub),
                        onPressed: () => _more(context),
                      ),
                    ]),
                    Text(t.artist,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(color: p.sub, fontSize: 15)),
                    const SizedBox(height: 22),
                    WaveformSeekBar(trackId: t.id),
                    const SizedBox(height: 14),
                    _Controls(c: c),
                    const SizedBox(height: 18),
                    _ActionBar(
                      lyrics: _lyrics,
                      onLyrics: () => setState(() => _lyrics = !_lyrics),
                    ),
                    const SizedBox(height: 8),
                  ]),
                ),
              ]);
            }),
          ),
        ),
      ),
    );
  }

  void _more(BuildContext context) {
    final t = context.read<PlayerController>().current;
    if (t == null) return;
    showTrackOptions(context, t);
  }
}

class _Controls extends StatelessWidget {
  const _Controls({required this.c});
  final PlayerController c;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
      IconButton(
        tooltip: 'Shuffle',
        onPressed: c.toggleShuffle,
        icon: Icon(Icons.shuffle_rounded, color: c.shuffle ? p.ink : p.sub),
      ),
      IconButton(
        iconSize: 34,
        onPressed: c.previous,
        icon: const Icon(Icons.skip_previous_rounded),
      ),
      StreamBuilder<PlayerState>(
        stream: c.player.playerStateStream,
        builder: (_, snap) {
          final s = snap.data;
          final playing = s?.playing ?? false;
          final busy = s?.processingState == ProcessingState.loading ||
              s?.processingState == ProcessingState.buffering;
          return GestureDetector(
            onTap: () {
              HapticFeedback.selectionClick();
              c.togglePlay();
            },
            child: Container(
              width: 68,
              height: 68,
              decoration: BoxDecoration(
                color: p.ink,
                shape: BoxShape.circle,
                boxShadow: [
                  BoxShadow(
                      color: p.ink.withValues(alpha: 0.25),
                      blurRadius: 20,
                      offset: const Offset(0, 8)),
                ],
              ),
              child: Stack(alignment: Alignment.center, children: [
                if (busy)
                  SizedBox(
                    width: 64,
                    height: 64,
                    child: CircularProgressIndicator(strokeWidth: 2, color: p.onInk),
                  ),
                AnimatedSwitcher(
                  duration: const Duration(milliseconds: 200),
                  transitionBuilder: (ch, a) => ScaleTransition(scale: a, child: ch),
                  child: Icon(
                    playing ? Icons.pause_rounded : Icons.play_arrow_rounded,
                    key: ValueKey(playing),
                    color: p.onInk,
                    size: 34,
                  ),
                ),
              ]),
            ),
          );
        },
      ),
      IconButton(
        iconSize: 34,
        onPressed: c.next,
        icon: const Icon(Icons.skip_next_rounded),
      ),
      IconButton(
        tooltip: 'Repeat',
        onPressed: c.cycleLoop,
        icon: Icon(
          c.loopMode == LoopMode.one ? Icons.repeat_one_rounded : Icons.repeat_rounded,
          color: c.loopMode == LoopMode.off ? p.sub : p.ink,
        ),
      ),
    ]);
  }
}

class _ActionBar extends StatelessWidget {
  const _ActionBar({required this.lyrics, required this.onLyrics});
  final bool lyrics;
  final VoidCallback onLyrics;

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final l = context.watch<LyricsController>();
    final p = Palette.of(context);
    final synced = l.status == LyricsStatus.found && (l.lyrics?.isSynced ?? false);

    Widget item(IconData icon, String label, VoidCallback onTap, {bool on = false}) => Expanded(
          child: InkWell(
            borderRadius: BorderRadius.circular(12),
            onTap: onTap,
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                Icon(icon, size: 22, color: on ? p.ink : p.sub),
                const SizedBox(height: 4),
                Text(label,
                    style: TextStyle(
                        fontSize: 11,
                        fontWeight: on ? FontWeight.w700 : FontWeight.w500,
                        color: on ? p.ink : p.sub)),
              ]),
            ),
          ),
        );

    final sleepLabel = c.sleepAtTrackEnd
        ? 'End of song'
        : c.sleepRemaining != null
            ? '${c.sleepRemaining!.inMinutes + 1} min'
            : 'Sleep';

    return Container(
      decoration: BoxDecoration(color: p.card, borderRadius: BorderRadius.circular(18)),
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      child: Row(children: [
        item(Icons.lyrics_outlined, synced ? 'Lyrics • Live' : 'Lyrics', onLyrics, on: lyrics),
        item(Icons.bedtime_outlined, sleepLabel, () => showSleepTimer(context), on: c.sleepActive),
        item(Icons.speed_rounded, '${c.speed}x', () => showSpeed(context), on: c.speed != 1.0),
        item(Icons.manage_search_rounded, 'Find lyrics', () => showLyricsSearch(context)),
      ]),
    );
  }
}
