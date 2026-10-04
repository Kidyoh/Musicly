import 'dart:math';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../state/player_controller.dart';
import 'sheets.dart';
import 'theme.dart';

String fmt(Duration? d) {
  if (d == null) return '--:--';
  final m = d.inMinutes;
  final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
  return '$m:$s';
}

class Artwork extends StatelessWidget {
  const Artwork(this.track, {super.key, this.size = 48, this.radius = 10, this.shadow = false});
  final Track? track;
  final double size;
  final double radius;
  final bool shadow;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final dark = Theme.of(context).brightness == Brightness.dark;
    final placeholder = Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: dark ? [p.line, p.card] : [p.ink.withValues(alpha: 0.85), p.ink],
        ),
      ),
      child: Icon(Icons.graphic_eq_rounded,
          size: size * 0.4, color: dark ? p.sub : p.onInk.withValues(alpha: 0.7)),
    );
    final url = track?.artworkUrl;
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(radius),
        boxShadow: shadow
            ? [
                BoxShadow(
                    color: Colors.black.withValues(alpha: 0.28),
                    blurRadius: 32,
                    offset: const Offset(0, 16)),
              ]
            : null,
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: url == null
            ? placeholder
            : Image.network(url,
                width: size,
                height: size,
                fit: BoxFit.cover,
                gaplessPlayback: true,
                // Some Audius content nodes don't send CORS headers; let the
                // browser render those with an <img> element instead.
                webHtmlElementStrategy: WebHtmlElementStrategy.fallback,
                frameBuilder: (_, child, frame, sync) => sync || frame != null
                    ? child
                    : placeholder,
                errorBuilder: (_, _, _) => placeholder),
      ),
    );
  }
}

/// Three bouncing bars shown next to the track that is playing.
class EqualizerBars extends StatefulWidget {
  const EqualizerBars({super.key, required this.playing, this.color, this.size = 16});
  final bool playing;
  final Color? color;
  final double size;
  @override
  State<EqualizerBars> createState() => _EqualizerBarsState();
}

class _EqualizerBarsState extends State<EqualizerBars> with SingleTickerProviderStateMixin {
  late final AnimationController _c =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 900));

  @override
  void initState() {
    super.initState();
    if (widget.playing) _c.repeat();
  }

  @override
  void didUpdateWidget(EqualizerBars old) {
    super.didUpdateWidget(old);
    if (widget.playing && !_c.isAnimating) _c.repeat();
    if (!widget.playing && _c.isAnimating) _c.stop();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final color = widget.color ?? Palette.of(context).ink;
    return AnimatedBuilder(
      animation: _c,
      builder: (_, _) => SizedBox(
        width: widget.size,
        height: widget.size,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: List.generate(4, (i) {
            final phase = _c.value * 2 * pi + i * 1.7;
            final h = widget.playing ? 0.3 + 0.7 * (0.5 + 0.5 * sin(phase)).abs() : 0.3;
            return Container(
              width: widget.size / 6,
              height: widget.size * h,
              decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(1)),
            );
          }),
        ),
      ),
    );
  }
}

/// Waveform scrubber. Bars are generated deterministically from the track id
/// so each song gets its own recognisable shape.
class WaveformSeekBar extends StatefulWidget {
  const WaveformSeekBar({super.key, required this.trackId});
  final String trackId;
  @override
  State<WaveformSeekBar> createState() => _WaveformSeekBarState();
}

class _WaveformSeekBarState extends State<WaveformSeekBar> {
  double? _drag;
  late List<double> _bars = _gen(widget.trackId);

  @override
  void didUpdateWidget(WaveformSeekBar old) {
    super.didUpdateWidget(old);
    if (old.trackId != widget.trackId) _bars = _gen(widget.trackId);
  }

  static List<double> _gen(String id) {
    final r = Random(id.hashCode);
    const n = 72;
    final raw = List.generate(n, (i) {
      final env = 0.45 + 0.55 * sin(pi * i / n); // louder in the middle
      return (0.15 + r.nextDouble() * 0.85) * env;
    });
    // Light smoothing so neighbouring bars relate to each other.
    return List.generate(n, (i) {
      final a = raw[max(0, i - 1)], b = raw[i], c = raw[min(n - 1, i + 1)];
      return (a + 2 * b + c) / 4;
    });
  }

  @override
  Widget build(BuildContext context) {
    final c = context.read<PlayerController>();
    final p = Palette.of(context);
    return StreamBuilder<Duration>(
      stream: c.player.positionStream,
      builder: (context, snap) {
        final total = c.player.duration ?? c.current?.duration ?? Duration.zero;
        final pos = snap.data ?? Duration.zero;
        final frac = _drag ??
            (total.inMilliseconds == 0 ? 0.0 : pos.inMilliseconds / total.inMilliseconds)
                .clamp(0.0, 1.0);
        void update(Offset local, double width) =>
            setState(() => _drag = (local.dx / width).clamp(0.0, 1.0));
        void commit() {
          if (_drag != null && total > Duration.zero) {
            c.seek(total * _drag!);
          }
          setState(() => _drag = null);
        }

        return Column(children: [
          LayoutBuilder(builder: (context, box) {
            return GestureDetector(
              behavior: HitTestBehavior.opaque,
              onHorizontalDragStart: (d) => update(d.localPosition, box.maxWidth),
              onHorizontalDragUpdate: (d) => update(d.localPosition, box.maxWidth),
              onHorizontalDragEnd: (_) => commit(),
              onTapDown: (d) => update(d.localPosition, box.maxWidth),
              onTapUp: (_) => commit(),
              child: SizedBox(
                height: 44,
                width: double.infinity,
                child: CustomPaint(
                  painter: _WavePainter(_bars, frac, p.ink, p.line),
                ),
              ),
            );
          }),
          const SizedBox(height: 6),
          Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
            Text(fmt(_drag != null ? total * _drag! : pos),
                style: TextStyle(fontSize: 12, color: p.ink, fontWeight: FontWeight.w500)),
            Text(fmt(total), style: TextStyle(fontSize: 12, color: p.sub)),
          ]),
        ]);
      },
    );
  }
}

class _WavePainter extends CustomPainter {
  _WavePainter(this.bars, this.progress, this.active, this.inactive);
  final List<double> bars;
  final double progress;
  final Color active;
  final Color inactive;

  @override
  void paint(Canvas canvas, Size size) {
    final step = size.width / bars.length;
    final w = max(1.5, step * 0.45);
    final paint = Paint()..strokeCap = StrokeCap.round..strokeWidth = w;
    final mid = size.height / 2;
    for (var i = 0; i < bars.length; i++) {
      final x = step * i + step / 2;
      final h = max(w, bars[i] * size.height);
      paint.color = (i + 0.5) / bars.length <= progress ? active : inactive;
      canvas.drawLine(Offset(x, mid - h / 2), Offset(x, mid + h / 2), paint);
    }
  }

  @override
  bool shouldRepaint(_WavePainter o) =>
      o.progress != progress || o.bars != bars || o.active != active;
}

/// Row used in collection lists: "01  Title / Artist • 4:21  ⋯".
class NumberedTrackRow extends StatelessWidget {
  const NumberedTrackRow({
    super.key,
    required this.index,
    required this.track,
    required this.onTap,
    this.onRemove,
  });
  final int index;
  final Track track;
  final VoidCallback onTap;
  final VoidCallback? onRemove;

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final p = Palette.of(context);
    final isCurrent = c.current?.id == track.id;
    return InkWell(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 250),
        margin: const EdgeInsets.symmetric(horizontal: 8),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
        decoration: BoxDecoration(
          color: isCurrent ? p.card : Colors.transparent,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(children: [
          SizedBox(
            width: 36,
            child: isCurrent
                ? Align(
                    alignment: Alignment.centerLeft,
                    child: EqualizerBars(playing: c.player.playing))
                : Text((index + 1).toString().padLeft(2, '0'),
                    style: TextStyle(fontWeight: FontWeight.w600, color: p.ink)),
          ),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(track.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
              const SizedBox(height: 3),
              Text(
                  track.duration == null || track.duration == Duration.zero
                      ? track.artist
                      : '${track.artist}  •  ${fmt(track.duration)}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: p.sub, fontSize: 13)),
            ]),
          ),
          MoreButton(track: track, onRemove: onRemove),
        ]),
      ),
    );
  }
}

/// Row with artwork, used in search, home and hotlist.
class ArtTrackRow extends StatelessWidget {
  const ArtTrackRow({super.key, required this.track, required this.onTap, this.leading});
  final Track track;
  final VoidCallback onTap;
  final Widget? leading;

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final p = Palette.of(context);
    final isCurrent = c.current?.id == track.id;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
        child: Row(children: [
          if (leading != null) ...[leading!, const SizedBox(width: 12)],
          Stack(alignment: Alignment.center, children: [
            Artwork(track, size: 52, radius: 12),
            if (isCurrent)
              Container(
                width: 52,
                height: 52,
                decoration: BoxDecoration(
                    color: Colors.black45, borderRadius: BorderRadius.circular(12)),
                child: Center(
                    child: EqualizerBars(playing: c.player.playing, color: Colors.white)),
              ),
          ]),
          const SizedBox(width: 14),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(track.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
              const SizedBox(height: 3),
              Text(track.artist,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: p.sub, fontSize: 13)),
            ]),
          ),
          MoreButton(track: track),
        ]),
      ),
    );
  }
}

class MoreButton extends StatelessWidget {
  const MoreButton({super.key, required this.track, this.onRemove});
  final Track track;
  final VoidCallback? onRemove;
  @override
  Widget build(BuildContext context) => IconButton(
        icon: Icon(Icons.more_horiz_rounded, color: Palette.of(context).sub),
        onPressed: () => showTrackOptions(context, track, onRemove: onRemove),
      );
}

class SectionHeader extends StatelessWidget {
  const SectionHeader(this.title, {super.key, this.action, this.onAction});
  final String title;
  final String? action;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(20, 24, 12, 10),
        child: Row(children: [
          Expanded(
            child: Text(title,
                style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, letterSpacing: -0.3)),
          ),
          if (action != null)
            TextButton(
              onPressed: onAction,
              child: Text(action!,
                  style: TextStyle(color: Palette.of(context).sub, fontWeight: FontWeight.w600)),
            ),
        ]),
      );
}

class EmptyState extends StatelessWidget {
  const EmptyState({super.key, required this.icon, required this.text, this.action});
  final IconData icon;
  final String text;
  final Widget? action;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Container(
              width: 72,
              height: 72,
              decoration: BoxDecoration(color: Palette.of(context).card, shape: BoxShape.circle),
              child: Icon(icon, size: 32, color: Palette.of(context).sub),
            ),
            const SizedBox(height: 16),
            Text(text,
                textAlign: TextAlign.center,
                style: TextStyle(color: Palette.of(context).sub, height: 1.5)),
            if (action != null) ...[const SizedBox(height: 20), action!],
          ]),
        ),
      );
}

/// Dark filled pill button ("Play") and soft grey one ("Shuffle").
class PillButton extends StatelessWidget {
  const PillButton({
    super.key,
    required this.icon,
    required this.label,
    required this.onPressed,
    this.filled = true,
  });
  final IconData icon;
  final String label;
  final VoidCallback? onPressed;
  final bool filled;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return SizedBox(
      height: 48,
      child: FilledButton.icon(
        onPressed: onPressed,
        style: FilledButton.styleFrom(
          backgroundColor: filled ? p.ink : p.card,
          foregroundColor: filled ? p.onInk : p.ink,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
          textStyle: const TextStyle(fontFamily: 'Inter', fontWeight: FontWeight.w600, fontSize: 15),
        ),
        icon: Icon(icon, size: 20),
        label: Text(label),
      ),
    );
  }
}
