import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:just_audio/just_audio.dart';
import 'package:provider/provider.dart';

import '../state/library_controller.dart';
import '../state/lyrics_controller.dart';
import '../state/player_controller.dart';
import 'art_color.dart';
import 'icons.dart';
import 'lyrics_view.dart';
import 'routes.dart';
import 'sheets.dart';
import 'theme.dart';
import 'widgets.dart';

void openNowPlaying(BuildContext context, {bool lyrics = false}) {
  Navigator.of(context, rootNavigator: true).push(
    PageRouteBuilder(
      transitionDuration: const Duration(milliseconds: 420),
      reverseTransitionDuration: const Duration(milliseconds: 320),
      pageBuilder: (_, _, _) => NowPlayingScreen(startOnLyrics: lyrics),
      transitionsBuilder: (_, anim, _, child) => SlideTransition(
        position: Tween(
          begin: const Offset(0, 1),
          end: Offset.zero,
        ).animate(CurvedAnimation(parent: anim, curve: Curves.easeOutCubic)),
        child: child,
      ),
    ),
  );
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
    final lib = context.watch<LibraryController>();
    final p = Palette.of(context);
    final dark = Theme.of(context).brightness == Brightness.dark;
    final t = c.current;
    if (t == null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) Navigator.maybePop(context);
      });
      return const Scaffold();
    }
    final liked = lib.isFavorite(t);

    return FutureBuilder<Color?>(
      future: artColor(t),
      builder: (context, snap) {
        final accent = snap.data;
        return GestureDetector(
          onVerticalDragUpdate: (d) =>
              setState(() => _dragY = (_dragY + d.delta.dy).clamp(0, 400)),
          onVerticalDragEnd: (d) {
            if (_dragY > 120 || d.velocity.pixelsPerSecond.dy > 900) {
              Navigator.pop(context);
            } else {
              setState(() => _dragY = 0);
            }
          },
          child: Transform.translate(
            offset: Offset(0, _dragY),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 700),
              curve: Curves.easeOut,
              // A soft glow taken from the album art.
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  stops: const [0, 0.62],
                  colors: [
                    Color.lerp(p.bg, accent ?? p.bg, dark ? 0.42 : 0.22)!,
                    p.bg,
                  ],
                ),
              ),
              child: Scaffold(
                backgroundColor: Colors.transparent,
                appBar: AppBar(
                  backgroundColor: Colors.transparent,
                  leading: IconButton(
                    icon: const Icon(AppIcons.down, size: 24),
                    onPressed: () => Navigator.pop(context),
                  ),
                  title: Column(
                    children: [
                      Text(
                        _lyrics
                            ? 'Lyrics'
                            : (t.isRadio ? 'Live radio' : 'Now Playing'),
                      ),
                      if (t.album != null)
                        Text(
                          t.album!,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 12,
                            color: p.sub,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                    ],
                  ),
                  actions: [
                    IconButton(
                      tooltip: 'Queue',
                      icon: const Icon(AppIcons.queue),
                      onPressed: () => showQueue(context),
                    ),
                  ],
                ),
                body: SafeArea(
                  top: false,
                  child: LayoutBuilder(
                    builder: (context, box) {
                      final art = (box.maxWidth - 110).clamp(180.0, 340.0);
                      return Column(
                        children: [
                          Expanded(
                            child: AnimatedSwitcher(
                              duration: const Duration(milliseconds: 350),
                              switchInCurve: Curves.easeOutCubic,
                              transitionBuilder: (child, a) => FadeTransition(
                                opacity: a,
                                child: ScaleTransition(
                                  scale: Tween(
                                    begin: 0.96,
                                    end: 1.0,
                                  ).animate(a),
                                  child: child,
                                ),
                              ),
                              child: _lyrics
                                  ? const Padding(
                                      key: ValueKey('lyrics'),
                                      padding: EdgeInsets.symmetric(
                                        horizontal: 24,
                                      ),
                                      child: LyricsView(),
                                    )
                                  : GestureDetector(
                                      key: const ValueKey('art'),
                                      onTap: () =>
                                          setState(() => _lyrics = true),
                                      child: Center(
                                        child: AnimatedScale(
                                          scale: c.isPlaying ? 1 : 0.92,
                                          duration: const Duration(
                                            milliseconds: 400,
                                          ),
                                          curve: Curves.easeOutBack,
                                          child: DecoratedBox(
                                            decoration: BoxDecoration(
                                              borderRadius:
                                                  BorderRadius.circular(26),
                                              boxShadow: [
                                                BoxShadow(
                                                  color:
                                                      (accent ?? Colors.black)
                                                          .withValues(
                                                            alpha: dark
                                                                ? 0.45
                                                                : 0.35,
                                                          ),
                                                  blurRadius: 48,
                                                  offset: const Offset(0, 22),
                                                ),
                                              ],
                                            ),
                                            child: t.isRadio
                                                ? StationArt(
                                                    t,
                                                    size: art,
                                                    radius: 26,
                                                  )
                                                : Artwork(
                                                    t,
                                                    size: art,
                                                    radius: 26,
                                                  ),
                                          ),
                                        ),
                                      ),
                                    ),
                            ),
                          ),
                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 28),
                            child: Column(
                              children: [
                                const SizedBox(height: 18),
                                Row(
                                  children: [
                                    IconButton(
                                      onPressed: () {
                                        HapticFeedback.lightImpact();
                                        lib.toggleFavorite(t);
                                      },
                                      icon: AnimatedSwitcher(
                                        duration: const Duration(
                                          milliseconds: 250,
                                        ),
                                        transitionBuilder: (ch, a) =>
                                            ScaleTransition(
                                              scale: a,
                                              child: ch,
                                            ),
                                        child: Icon(
                                          liked
                                              ? AppIcons.heartOn
                                              : AppIcons.heart,
                                          key: ValueKey(liked),
                                          color: liked
                                              ? const Color(0xFFE5484D)
                                              : p.sub,
                                        ),
                                      ),
                                    ),
                                    Expanded(
                                      child: Text(
                                        t.title,
                                        textAlign: TextAlign.center,
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        style: const TextStyle(
                                          fontSize: 26,
                                          fontWeight: FontWeight.w700,
                                          letterSpacing: -0.6,
                                        ),
                                      ),
                                    ),
                                    IconButton(
                                      icon: Icon(AppIcons.more, color: p.sub),
                                      onPressed: () =>
                                          showTrackOptions(context, t),
                                    ),
                                  ],
                                ),
                                Row(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: [
                                    Flexible(
                                      child: GestureDetector(
                                        onTap: t.isRadio
                                            ? null
                                            : () =>
                                                  openArtist(context, t.artist),
                                        child: Text(
                                          t.isRadio
                                              ? (c.nowOnAir ?? t.artist)
                                              : t.artist,
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                          style: TextStyle(
                                            color: p.sub,
                                            fontSize: 15,
                                          ),
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 20),
                                if (t.isRadio)
                                  LiveBar(onAir: c.nowOnAir)
                                else
                                  WaveformSeekBar(trackId: t.id),
                                const SizedBox(height: 12),
                                _Controls(c: c),
                                const SizedBox(height: 16),
                                _ActionBar(
                                  lyrics: _lyrics,
                                  onLyrics: () =>
                                      setState(() => _lyrics = !_lyrics),
                                ),
                                const SizedBox(height: 8),
                              ],
                            ),
                          ),
                        ],
                      );
                    },
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

class _Controls extends StatelessWidget {
  const _Controls({required this.c});
  final PlayerController c;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        IconButton(
          tooltip: 'Shuffle',
          onPressed: c.toggleShuffle,
          icon: Icon(AppIcons.shuffle, color: c.shuffle ? p.ink : p.sub),
        ),
        IconButton(
          iconSize: 30,
          onPressed: c.previous,
          icon: const Icon(AppIcons.prev),
        ),
        StreamBuilder<PlayerState>(
          stream: c.player.playerStateStream,
          builder: (_, snap) {
            final s = snap.data;
            final playing =
                (s?.playing ?? false) &&
                s?.processingState != ProcessingState.completed;
            final busy =
                s?.processingState == ProcessingState.loading ||
                s?.processingState == ProcessingState.buffering;
            return GestureDetector(
              onTap: () {
                HapticFeedback.selectionClick();
                c.togglePlay();
              },
              child: Container(
                width: 70,
                height: 70,
                decoration: BoxDecoration(
                  color: p.ink,
                  shape: BoxShape.circle,
                  boxShadow: [
                    BoxShadow(
                      color: p.ink.withValues(alpha: 0.25),
                      blurRadius: 20,
                      offset: const Offset(0, 8),
                    ),
                  ],
                ),
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    if (busy)
                      SizedBox(
                        width: 66,
                        height: 66,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: p.onInk,
                        ),
                      ),
                    AnimatedSwitcher(
                      duration: const Duration(milliseconds: 200),
                      transitionBuilder: (ch, a) =>
                          ScaleTransition(scale: a, child: ch),
                      child: Icon(
                        playing ? AppIcons.pause : AppIcons.play,
                        key: ValueKey(playing),
                        color: p.onInk,
                        size: 28,
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        ),
        IconButton(
          iconSize: 30,
          onPressed: c.next,
          icon: const Icon(AppIcons.next),
        ),
        IconButton(
          tooltip: 'Repeat',
          onPressed: c.cycleLoop,
          icon: Icon(
            repeatIcon(c.loopMode),
            color: c.loopMode == LoopMode.off ? p.sub : p.ink,
          ),
        ),
      ],
    );
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
    final synced =
        l.status == LyricsStatus.found &&
        (l.lyrics?.isSynced ?? false) &&
        l.canSync;
    final soundOn = c.eqEnabled || c.loudnessOn;

    Widget item(
      IconData icon,
      String label,
      VoidCallback onTap, {
      bool on = false,
    }) => Expanded(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 22, color: on ? p.ink : p.sub),
              const SizedBox(height: 4),
              Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: on ? FontWeight.w700 : FontWeight.w500,
                  color: on ? p.ink : p.sub,
                ),
              ),
            ],
          ),
        ),
      ),
    );

    final sleepLabel = c.sleepAtTrackEnd
        ? 'End of song'
        : c.sleepRemaining != null
        ? '${c.sleepRemaining!.inMinutes + 1} min'
        : 'Sleep';

    return Container(
      decoration: BoxDecoration(
        color: p.card.withValues(alpha: 0.85),
        borderRadius: BorderRadius.circular(18),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      child: Row(
        children: [
          item(
            lyrics ? AppIcons.lyricsOn : AppIcons.lyrics,
            synced ? 'Lyrics • Live' : 'Lyrics',
            onLyrics,
            on: lyrics,
          ),
          item(
            soundOn ? AppIcons.soundOn : AppIcons.sound,
            'Sound',
            () => showSound(context),
            on: soundOn,
          ),
          item(
            c.sleepActive ? AppIcons.sleepOn : AppIcons.sleep,
            sleepLabel,
            () => showSleepTimer(context),
            on: c.sleepActive,
          ),
          item(
            AppIcons.speed,
            '${c.speed}x',
            () => showSpeed(context),
            on: c.speed != 1.0,
          ),
        ],
      ),
    );
  }
}
