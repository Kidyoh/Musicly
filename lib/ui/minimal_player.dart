import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:just_audio/just_audio.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../state/library_controller.dart';
import '../state/lyrics_controller.dart';
import '../state/player_controller.dart';
import 'icons.dart';
import 'lyrics_view.dart';
import 'minimal_style.dart';
import 'routes.dart';
import 'sheets.dart';
import 'widgets.dart';

/// Now Playing in the Minimal style: a record-like disc ringed by dots (drag
/// the ring to seek), and a click-wheel with round buttons for everything else.
class MinimalNowPlaying extends StatefulWidget {
  const MinimalNowPlaying({super.key, this.startOnLyrics = false});
  final bool startOnLyrics;
  @override
  State<MinimalNowPlaying> createState() => _MinimalNowPlayingState();
}

class _MinimalNowPlayingState extends State<MinimalNowPlaying>
    with SingleTickerProviderStateMixin {
  late final AnimationController _spin = AnimationController(
    vsync: this,
    duration: const Duration(seconds: 28),
  );
  late bool _lyrics = widget.startOnLyrics;
  double? _drag; // position (0..1) while the ring is being dragged
  bool _spinning = false;

  @override
  void dispose() {
    _spin.dispose();
    super.dispose();
  }

  void _syncSpin(bool playing) {
    if (playing == _spinning) return;
    _spinning = playing;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (playing) {
        _spin.repeat();
      } else {
        _spin.stop();
      }
    });
  }

  double _fracAt(Offset local, double size) {
    final d = local - Offset(size / 2, size / 2);
    var a = atan2(d.dy, d.dx) + pi / 2;
    if (a < 0) a += 2 * pi;
    return a / (2 * pi);
  }

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final lib = context.watch<LibraryController>();
    final m = MiniPalette.of(context);
    final t = c.current;
    if (t == null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) Navigator.maybePop(context);
      });
      return Scaffold(backgroundColor: m.bg);
    }
    _syncSpin(c.isPlaying);
    final liked = lib.isFavorite(t);

    return Scaffold(
      backgroundColor: m.bg,
      body: SafeArea(
        child: Column(
          children: [
            // Header: a big quiet title and a way back.
            Padding(
              padding: const EdgeInsets.fromLTRB(28, 14, 12, 0),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      t.isRadio ? 'Radio' : 'Music',
                      style: TextStyle(
                        fontSize: 30,
                        fontWeight: FontWeight.w800,
                        letterSpacing: -1,
                        color: m.ink,
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: 'Close',
                    icon: Icon(AppIcons.down, color: m.ink),
                    onPressed: () => Navigator.pop(context),
                  ),
                ],
              ),
            ),
            Expanded(
              child: LayoutBuilder(
                builder: (context, box) {
                  final size = min(box.maxHeight - 40, box.maxWidth - 36);
                  return Center(
                    child: AnimatedSwitcher(
                      duration: const Duration(milliseconds: 350),
                      child: _lyrics
                          ? Padding(
                              key: const ValueKey('lyrics'),
                              padding: const EdgeInsets.fromLTRB(22, 10, 22, 6),
                              child: DecoratedBox(
                                decoration: BoxDecoration(
                                  color: m.panel,
                                  borderRadius: BorderRadius.circular(34),
                                  boxShadow: m.soft,
                                ),
                                child: const ClipRRect(
                                  borderRadius: BorderRadius.all(
                                    Radius.circular(34),
                                  ),
                                  child: Padding(
                                    padding: EdgeInsets.symmetric(
                                      horizontal: 18,
                                    ),
                                    child: LyricsView(),
                                  ),
                                ),
                              ),
                            )
                          : _disc(c, t, m, max(size, 150)),
                    ),
                  );
                },
              ),
            ),
            // Title and artist.
            Padding(
              padding: const EdgeInsets.fromLTRB(32, 6, 32, 14),
              child: Column(
                children: [
                  AnimatedSwitcher(
                    duration: const Duration(milliseconds: 250),
                    child: Text(
                      t.title,
                      key: ValueKey(t.id),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 28,
                        fontWeight: FontWeight.w800,
                        letterSpacing: -.8,
                        color: m.ink,
                      ),
                    ),
                  ),
                  const SizedBox(height: 3),
                  GestureDetector(
                    onTap: t.isRadio
                        ? null
                        : () => openArtist(context, t.artist),
                    child: Text(
                      t.isRadio ? (c.nowOnAir ?? t.artist) : t.artist,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 16, color: m.sub),
                    ),
                  ),
                ],
              ),
            ),
            _console(context, c, lib, t, m, liked),
            const SizedBox(height: 14),
          ],
        ),
      ),
    );
  }

  // ---- The disc ---------------------------------------------------------------

  Widget _disc(PlayerController c, Track t, MiniPalette m, double size) {
    return StreamBuilder<Duration>(
      key: const ValueKey('disc'),
      stream: c.player.positionStream,
      builder: (context, snap) {
        final total = c.player.duration ?? t.duration ?? Duration.zero;
        final pos = snap.data ?? Duration.zero;
        final live = t.isRadio;
        final frac =
            _drag ??
            (live || total == Duration.zero
                ? 0.0
                : (pos.inMilliseconds / total.inMilliseconds).clamp(0.0, 1.0));
        final shown = _drag == null ? pos : total * _drag!;

        void drag(Offset local) {
          if (live || total == Duration.zero) return;
          final f = _fracAt(local, size);
          // Ignore a jump across the top of the ring.
          if (_drag != null && (f - _drag!).abs() > .5) return;
          setState(() => _drag = f);
        }

        void commit() {
          if (_drag != null && total > Duration.zero) c.seek(total * _drag!);
          setState(() => _drag = null);
        }

        final art = size * .62;
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () {
                HapticFeedback.selectionClick();
                c.togglePlay();
              },
              onPanStart: (d) => drag(d.localPosition),
              onPanUpdate: (d) => drag(d.localPosition),
              onPanEnd: (_) => commit(),
              onPanCancel: () => setState(() => _drag = null),
              child: SizedBox(
                width: size,
                height: size,
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    CustomPaint(
                      size: Size.square(size),
                      painter: DotRingPainter(
                        frac: frac,
                        on: m.ink,
                        off: m.line,
                        marker: m.ink,
                        showMarker: !live,
                      ),
                    ),
                    // The raised plate the record sits on.
                    Container(
                      width: size * .84,
                      height: size * .84,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        gradient: LinearGradient(
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                          colors: m.dark
                              ? [
                                  const Color(0xFF26272C),
                                  const Color(0xFF16171A),
                                ]
                              : [Colors.white, const Color(0xFFE3E5E9)],
                        ),
                        boxShadow: m.raised,
                      ),
                    ),
                    // The cover, turning slowly while the music plays.
                    RotationTransition(
                      turns: _spin,
                      child: Container(
                        width: art,
                        height: art,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          border: Border.all(color: m.panel, width: 5),
                          boxShadow: [
                            BoxShadow(
                              color: Colors.black.withValues(
                                alpha: m.dark ? .5 : .16,
                              ),
                              blurRadius: 18,
                              offset: const Offset(0, 8),
                            ),
                          ],
                        ),
                        child: t.isRadio
                            ? StationArt(t, size: art - 10, radius: art)
                            : Artwork(t, size: art - 10, radius: art),
                      ),
                    ),
                    // Spindle hole.
                    Container(
                      width: size * .1,
                      height: size * .1,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: m.bg,
                        border: Border.all(color: m.panel, width: 3),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 6),
            SizedBox(
              width: size * .86,
              child: live
                  ? Center(
                      child: Text(
                        '● LIVE',
                        style: TextStyle(
                          color: m.accent,
                          fontWeight: FontWeight.w800,
                          fontSize: 13,
                          letterSpacing: 2,
                        ),
                      ),
                    )
                  : Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          fmt(shown),
                          style: TextStyle(
                            color: m.ink,
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                            fontFeatures: const [FontFeature.tabularFigures()],
                          ),
                        ),
                        Text(
                          fmt(total == Duration.zero ? null : total),
                          style: TextStyle(
                            color: m.sub,
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                            fontFeatures: const [FontFeature.tabularFigures()],
                          ),
                        ),
                      ],
                    ),
            ),
          ],
        );
      },
    );
  }

  // ---- The control panel ------------------------------------------------------

  Widget _console(
    BuildContext context,
    PlayerController c,
    LibraryController lib,
    Track t,
    MiniPalette m,
    bool liked,
  ) {
    final l = context.watch<LyricsController>();
    final synced =
        l.status == LyricsStatus.found &&
        (l.lyrics?.isSynced ?? false) &&
        l.canSync;
    final speed = c.speed == c.speed.roundToDouble()
        ? c.speed.toInt().toString()
        : c.speed.toString();

    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: m.panel,
        borderRadius: BorderRadius.circular(40),
        boxShadow: m.soft,
      ),
      child: LayoutBuilder(
        builder: (context, box) {
          final wheel = (box.maxWidth * .6).clamp(150.0, 208.0);
          final tile = ((box.maxWidth - wheel - 12) / 2).clamp(38.0, 54.0);
          Widget row(Widget a, Widget b) => Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [a, b],
          );
          return SizedBox(
            height: wheel,
            child: Row(
              children: [
                SizedBox(
                  width: tile * 2 + 8,
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      row(
                        MiniTile(
                          palette: m,
                          size: tile,
                          label: 'x$speed',
                          tooltip: 'Speed',
                          active: c.speed != 1.0,
                          onTap: () => showSpeed(context),
                        ),
                        MiniTile(
                          palette: m,
                          size: tile,
                          icon: c.eqEnabled || c.loudnessOn
                              ? AppIcons.soundOn
                              : AppIcons.sound,
                          tooltip: 'Sound',
                          active: c.eqEnabled || c.loudnessOn,
                          onTap: () => showSound(context),
                        ),
                      ),
                      row(
                        MiniTile(
                          palette: m,
                          size: tile,
                          icon: _lyrics ? AppIcons.lyricsOn : AppIcons.lyrics,
                          tooltip: synced ? 'Lyrics (synced)' : 'Lyrics',
                          active: _lyrics,
                          onTap: () => setState(() => _lyrics = !_lyrics),
                        ),
                        MiniTile(
                          palette: m,
                          size: tile,
                          icon: c.sleepActive
                              ? AppIcons.sleepOn
                              : AppIcons.sleep,
                          tooltip: 'Sleep timer',
                          active: c.sleepActive,
                          onTap: () => showSleepTimer(context),
                        ),
                      ),
                      row(
                        MiniTile(
                          palette: m,
                          size: tile,
                          icon: AppIcons.shuffle,
                          tooltip: 'Shuffle',
                          active: c.shuffle,
                          onTap: c.toggleShuffle,
                        ),
                        MiniTile(
                          palette: m,
                          size: tile,
                          icon: repeatIcon(c.loopMode),
                          tooltip: 'Repeat',
                          active: c.loopMode != LoopMode.off,
                          onTap: c.cycleLoop,
                        ),
                      ),
                    ],
                  ),
                ),
                const Spacer(),
                _wheel(context, c, lib, t, m, liked, wheel),
              ],
            ),
          );
        },
      ),
    );
  }

  /// The click-wheel: queue on top, previous and next at the sides, play and
  /// pause at the bottom, and a heart in the middle.
  Widget _wheel(
    BuildContext context,
    PlayerController c,
    LibraryController lib,
    Track t,
    MiniPalette m,
    bool liked,
    double size,
  ) {
    Widget key(Alignment at, IconData icon, String tip, VoidCallback onTap) =>
        Align(
          alignment: at,
          child: Tooltip(
            message: tip,
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () {
                HapticFeedback.selectionClick();
                onTap();
              },
              child: SizedBox(
                width: size * .26,
                height: size * .26,
                child: Icon(icon, size: size * .12, color: m.ink),
              ),
            ),
          ),
        );
    const r = .74;
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: m.tile,
        boxShadow: m.soft,
      ),
      child: Stack(
        children: [
          key(
            const Alignment(0, -r),
            AppIcons.queue,
            'Queue',
            () => showQueue(context),
          ),
          key(const Alignment(-r, 0), AppIcons.prev, 'Previous', c.previous),
          key(const Alignment(r, 0), AppIcons.next, 'Next', c.next),
          key(
            const Alignment(0, r),
            c.isPlaying ? AppIcons.pause : AppIcons.play,
            c.isPlaying ? 'Pause' : 'Play',
            c.togglePlay,
          ),
          Center(
            child: Tooltip(
              message: liked ? 'Unlike' : 'Like',
              child: GestureDetector(
                onTap: () {
                  HapticFeedback.lightImpact();
                  lib.toggleFavorite(t);
                },
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 250),
                  width: size * .34,
                  height: size * .34,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: m.bg,
                    border: Border.all(color: m.panel, width: 4),
                  ),
                  child: Icon(
                    liked ? AppIcons.heartOn : AppIcons.heart,
                    size: size * .13,
                    color: liked ? m.accent : m.sub,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// The mini player in the Minimal style: a light pill with a round cover, and
/// a thin progress ring around the play button.
class MinimalMiniPlayer extends StatelessWidget {
  const MinimalMiniPlayer({super.key, required this.onOpen});
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final m = MiniPalette.of(context);
    final t = c.current;
    return AnimatedSize(
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeOutCubic,
      child: t == null
          ? const SizedBox(width: double.infinity)
          : GestureDetector(
              onTap: onOpen,
              onVerticalDragEnd: (d) {
                if ((d.primaryVelocity ?? 0) < -300) onOpen();
              },
              onHorizontalDragEnd: (d) {
                final v = d.primaryVelocity ?? 0;
                if (v < -300) c.next();
                if (v > 300) c.previous();
              },
              child: Container(
                margin: const EdgeInsets.fromLTRB(12, 0, 12, 6),
                padding: const EdgeInsets.fromLTRB(10, 10, 12, 10),
                decoration: BoxDecoration(
                  color: m.panel,
                  borderRadius: BorderRadius.circular(34),
                  boxShadow: m.soft,
                ),
                child: Row(
                  children: [
                    t.isRadio
                        ? StationArt(t, size: 48, radius: 24)
                        : Artwork(t, size: 48, radius: 24),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            t.title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: m.ink,
                              fontWeight: FontWeight.w800,
                              fontSize: 15,
                              letterSpacing: -.2,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            t.isRadio
                                ? '● LIVE  ${c.nowOnAir ?? t.artist}'
                                : t.artist,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(color: m.sub, fontSize: 12.5),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    GestureDetector(
                      onTap: c.togglePlay,
                      child: StreamBuilder<Duration>(
                        stream: c.player.positionStream,
                        builder: (_, snap) {
                          final total = c.player.duration?.inMilliseconds ?? 0;
                          final pos = snap.data?.inMilliseconds ?? 0;
                          return SizedBox(
                            width: 48,
                            height: 48,
                            child: Stack(
                              alignment: Alignment.center,
                              children: [
                                SizedBox(
                                  width: 48,
                                  height: 48,
                                  child: CircularProgressIndicator(
                                    value: total == 0
                                        ? 0
                                        : (pos / total).clamp(0.0, 1.0),
                                    strokeWidth: 2.5,
                                    color: m.accent,
                                    backgroundColor: m.line,
                                  ),
                                ),
                                Container(
                                  width: 38,
                                  height: 38,
                                  decoration: BoxDecoration(
                                    shape: BoxShape.circle,
                                    color: m.tile,
                                    boxShadow: m.soft,
                                  ),
                                  child: Icon(
                                    c.isPlaying
                                        ? AppIcons.pause
                                        : AppIcons.play,
                                    size: 17,
                                    color: m.ink,
                                  ),
                                ),
                              ],
                            ),
                          );
                        },
                      ),
                    ),
                  ],
                ),
              ),
            ),
    );
  }
}
