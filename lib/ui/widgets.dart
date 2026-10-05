import 'dart:math';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../services/device_library.dart';
import '../services/telegram_bot.dart';
import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'icons.dart';
import 'sheets.dart';
import 'theme.dart';

String fmt(Duration? d) {
  if (d == null) return '--:--';
  final m = d.inMinutes;
  final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
  return '$m:$s';
}

/// "1 song", "3 songs".
String count(int n, String word) => '$n ${n == 1 ? word : '${word}s'}';

String compact(int n) {
  if (n >= 1000000) {
    return '${(n / 1000000).toStringAsFixed(n >= 10000000 ? 0 : 1)}M';
  }
  if (n >= 1000) return '${(n / 1000).toStringAsFixed(n >= 10000 ? 0 : 1)}K';
  return '$n';
}

/// Image provider for a track's cover, from the web or the phone's library.
Future<ImageProvider?> artworkProvider(Track t) async {
  if (TelegramFiles.isThumb(t.artworkUrl)) {
    final url = await TelegramFiles.resolveThumb(t.artworkUrl!);
    return url == null ? null : NetworkImage(url);
  }
  if (t.artworkUrl != null) return NetworkImage(t.artworkUrl!);
  if (t.mediaId != null) {
    final b = await DeviceLibrary.artwork(t.mediaId!);
    if (b != null) return MemoryImage(b);
  }
  return null;
}

class Artwork extends StatelessWidget {
  const Artwork(
    this.track, {
    super.key,
    this.size = 48,
    this.radius = 10,
    this.shadow = false,
    this.icon,
  });
  final Track? track;
  final double size;
  final double radius;
  final bool shadow;
  final IconData? icon;

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
          colors: dark ? [p.line, p.card] : [const Color(0xFF3A3B42), p.ink],
        ),
      ),
      child: Icon(
        icon ?? AppIcons.music,
        size: size * 0.38,
        color: dark ? p.sub : Colors.white.withValues(alpha: 0.75),
      ),
    );

    Widget network(String url) => Image.network(
      url,
      width: size,
      height: size,
      fit: BoxFit.cover,
      gaplessPlayback: true,
      webHtmlElementStrategy: WebHtmlElementStrategy.fallback,
      frameBuilder: (_, child, frame, sync) =>
          sync || frame != null ? child : placeholder,
      errorBuilder: (_, _, _) => placeholder,
    );

    Widget image;
    final t = track;
    if (TelegramFiles.isThumb(t?.artworkUrl) &&
        TelegramFiles.cachedThumb(t!.artworkUrl!) != null) {
      image = network(TelegramFiles.cachedThumb(t.artworkUrl!)!);
    } else if (TelegramFiles.isThumb(t?.artworkUrl)) {
      // Telegram covers need a link lookup first.
      image = FutureBuilder<String?>(
        future: TelegramFiles.resolveThumb(t!.artworkUrl!),
        builder: (_, snap) =>
            snap.data == null ? placeholder : network(snap.data!),
      );
    } else if (t?.artworkUrl != null) {
      image = network(t!.artworkUrl!);
    } else if (t?.mediaId != null) {
      image = FutureBuilder<Uint8List?>(
        future: DeviceLibrary.artwork(t!.mediaId!),
        builder: (_, snap) => snap.data == null
            ? placeholder
            : Image.memory(
                snap.data!,
                width: size,
                height: size,
                fit: BoxFit.cover,
                gaplessPlayback: true,
              ),
      );
    } else {
      image = placeholder;
    }

    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(radius),
        boxShadow: shadow
            ? [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.25),
                  blurRadius: 30,
                  offset: const Offset(0, 14),
                ),
              ]
            : null,
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: image,
      ),
    );
  }
}

/// 2×2 grid of covers for playlists the user made.
class Mosaic extends StatelessWidget {
  const Mosaic(this.tracks, {super.key, this.size = 56, this.radius = 12});
  final List<Track> tracks;
  final double size;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final withArt = tracks
        .where((t) => t.artworkUrl != null || t.mediaId != null)
        .toList();
    final seen = <String>{};
    final unique = withArt
        .where((t) => seen.add(t.artworkUrl ?? '${t.mediaId}'))
        .take(4)
        .toList();
    if (unique.length < 4) {
      return Artwork(
        unique.isEmpty ? null : unique.first,
        size: size,
        radius: radius,
        icon: AppIcons.playNext,
      );
    }
    final half = size / 2;
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: SizedBox(
        width: size,
        height: size,
        child: Wrap(
          children: [for (final t in unique) Artwork(t, size: half, radius: 0)],
        ),
      ),
    );
  }
}

/// Round artist picture: the cover of one of their songs.
class ArtistAvatar extends StatelessWidget {
  const ArtistAvatar({super.key, required this.cover, this.size = 96});
  final Track? cover;
  final double size;

  @override
  Widget build(BuildContext context) =>
      Artwork(cover, size: size, radius: size / 2, icon: AppIcons.artist);
}

/// Three bouncing bars shown next to the song that's playing.
class EqualizerBars extends StatefulWidget {
  const EqualizerBars({
    super.key,
    required this.playing,
    this.color,
    this.size = 16,
  });
  final bool playing;
  final Color? color;
  final double size;
  @override
  State<EqualizerBars> createState() => _EqualizerBarsState();
}

class _EqualizerBarsState extends State<EqualizerBars>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 900),
  );

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
            final h = widget.playing
                ? 0.3 + 0.7 * (0.5 + 0.5 * sin(phase)).abs()
                : 0.3;
            return Container(
              width: widget.size / 6,
              height: widget.size * h,
              decoration: BoxDecoration(
                color: color,
                borderRadius: BorderRadius.circular(1),
              ),
            );
          }),
        ),
      ),
    );
  }
}

/// Waveform scrubber. Bars are generated from the song id so each song has
/// its own recognisable shape.
class WaveformSeekBar extends StatefulWidget {
  const WaveformSeekBar({super.key, required this.trackId, this.accent});
  final String trackId;
  final Color? accent;
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
      final env = 0.45 + 0.55 * sin(pi * i / n);
      return (0.15 + r.nextDouble() * 0.85) * env;
    });
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
        final total = c.player.duration ?? Duration.zero;
        final pos = snap.data ?? Duration.zero;
        final frac =
            _drag ??
            (total.inMilliseconds == 0
                    ? 0.0
                    : pos.inMilliseconds / total.inMilliseconds)
                .clamp(0.0, 1.0);
        void update(Offset local, double width) =>
            setState(() => _drag = (local.dx / width).clamp(0.0, 1.0));
        void commit() {
          if (_drag != null && total > Duration.zero) c.seek(total * _drag!);
          setState(() => _drag = null);
        }

        return Column(
          children: [
            LayoutBuilder(
              builder: (context, box) {
                return GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onHorizontalDragStart: (d) =>
                      update(d.localPosition, box.maxWidth),
                  onHorizontalDragUpdate: (d) =>
                      update(d.localPosition, box.maxWidth),
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
              },
            ),
            const SizedBox(height: 6),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  fmt(_drag != null ? total * _drag! : pos),
                  style: TextStyle(
                    fontSize: 12,
                    color: p.ink,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                Text(fmt(total), style: TextStyle(fontSize: 12, color: p.sub)),
              ],
            ),
          ],
        );
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
    final paint = Paint()
      ..strokeCap = StrokeCap.round
      ..strokeWidth = w;
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
    this.removeLabel,
    this.trailing,
  });
  final int index;
  final Track track;
  final VoidCallback onTap;
  final VoidCallback? onRemove;
  final String? removeLabel;
  final Widget? trailing;

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
        child: Row(
          children: [
            SizedBox(
              width: 36,
              child: isCurrent
                  ? Align(
                      alignment: Alignment.centerLeft,
                      child: EqualizerBars(playing: c.isPlaying),
                    )
                  : Text(
                      (index + 1).toString().padLeft(2, '0'),
                      style: TextStyle(
                        fontWeight: FontWeight.w600,
                        color: p.ink,
                      ),
                    ),
            ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    track.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontWeight: FontWeight.w600,
                      fontSize: 15,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    track.duration == null || track.duration == Duration.zero
                        ? track.artist
                        : '${track.artist}  •  ${fmt(track.duration)}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(color: p.sub, fontSize: 13),
                  ),
                ],
              ),
            ),
            ?trailing,
            MoreButton(
              track: track,
              onRemove: onRemove,
              removeLabel: removeLabel,
            ),
          ],
        ),
      ),
    );
  }
}

/// Row with artwork, used in search, home and the charts.
class ArtTrackRow extends StatelessWidget {
  const ArtTrackRow({
    super.key,
    required this.track,
    required this.onTap,
    this.leading,
  });
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
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 7),
        child: Row(
          children: [
            if (leading != null) ...[leading!, const SizedBox(width: 12)],
            Stack(
              alignment: Alignment.center,
              children: [
                track.isRadio
                    ? StationArt(track, size: 52, radius: 12)
                    : Artwork(track, size: 52, radius: 12),
                if (isCurrent)
                  Container(
                    width: 52,
                    height: 52,
                    decoration: BoxDecoration(
                      color: Colors.black45,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Center(
                      child: EqualizerBars(
                        playing: c.isPlaying,
                        color: Colors.white,
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    track.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontWeight: FontWeight.w600,
                      fontSize: 15,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Row(
                    children: [
                      Flexible(
                        child: Text(
                          track.artist,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(color: p.sub, fontSize: 13),
                        ),
                      ),
                      const SizedBox(width: 6),
                      SourceBadge(track),
                    ],
                  ),
                ],
              ),
            ),
            MoreButton(track: track),
          ],
        ),
      ),
    );
  }
}

class MoreButton extends StatelessWidget {
  const MoreButton({
    super.key,
    required this.track,
    this.onRemove,
    this.removeLabel,
  });
  final Track track;
  final VoidCallback? onRemove;
  final String? removeLabel;
  @override
  Widget build(BuildContext context) => IconButton(
    icon: Icon(AppIcons.more, color: Palette.of(context).sub, size: 20),
    onPressed: () => showTrackOptions(
      context,
      track,
      onRemove: onRemove,
      removeLabel: removeLabel,
    ),
  );
}

class SectionHeader extends StatelessWidget {
  const SectionHeader(
    this.title, {
    super.key,
    this.subtitle,
    this.action,
    this.onAction,
  });
  final String title;
  final String? subtitle;
  final String? action;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(20, 28, 12, 12),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (subtitle != null)
                Text(
                  subtitle!,
                  style: TextStyle(
                    color: Palette.of(context).sub,
                    fontSize: 13,
                  ),
                ),
              Text(
                title,
                style: const TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.3,
                ),
              ),
            ],
          ),
        ),
        if (action != null)
          TextButton(
            onPressed: onAction,
            child: Text(
              action!,
              style: TextStyle(
                color: Palette.of(context).sub,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
      ],
    ),
  );
}

class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    required this.icon,
    required this.text,
    this.action,
  });
  final IconData icon;
  final String text;
  final Widget? action;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(32),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 72,
            height: 72,
            decoration: BoxDecoration(
              color: Palette.of(context).card,
              shape: BoxShape.circle,
            ),
            child: Icon(icon, size: 30, color: Palette.of(context).sub),
          ),
          const SizedBox(height: 16),
          Text(
            text,
            textAlign: TextAlign.center,
            style: TextStyle(color: Palette.of(context).sub, height: 1.5),
          ),
          if (action != null) ...[const SizedBox(height: 20), action!],
        ],
      ),
    ),
  );
}

/// Dark filled ("Play") or soft grey ("Shuffle") button from the design.
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
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
          ),
          textStyle: const TextStyle(
            fontFamily: 'Inter',
            fontWeight: FontWeight.w600,
            fontSize: 15,
          ),
        ),
        icon: Icon(icon, size: 20),
        label: Text(label),
      ),
    );
  }
}

/// Square cover with title and subtitle, for albums and playlists.
class CoverCard extends StatelessWidget {
  const CoverCard({
    super.key,
    required this.title,
    required this.subtitle,
    required this.art,
    required this.onTap,
    this.width = 148,
  });
  final String title;
  final String subtitle;
  final Widget art;
  final VoidCallback onTap;
  final double width;

  @override
  Widget build(BuildContext context) => GestureDetector(
    onTap: onTap,
    child: SizedBox(
      width: width,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          art,
          const SizedBox(height: 10),
          Text(
            title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
          ),
          const SizedBox(height: 2),
          Text(
            subtitle,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(color: Palette.of(context).sub, fontSize: 12),
          ),
        ],
      ),
    ),
  );
}

/// Round artist picture with name (and song count) underneath.
class ArtistBubble extends StatelessWidget {
  const ArtistBubble({
    super.key,
    required this.name,
    required this.cover,
    required this.onTap,
    this.subtitle,
    this.size = 96,
  });
  final String name;
  final Track? cover;
  final VoidCallback onTap;
  final String? subtitle;
  final double size;

  @override
  Widget build(BuildContext context) => GestureDetector(
    onTap: onTap,
    child: SizedBox(
      width: size,
      child: Column(
        children: [
          ArtistAvatar(cover: cover, size: size),
          const SizedBox(height: 8),
          Text(
            name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13),
          ),
          if (subtitle != null)
            Text(
              subtitle!,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(color: Palette.of(context).sub, fontSize: 11),
            ),
        ],
      ),
    ),
  );
}

/// Horizontal scrolling row.
class HRow extends StatelessWidget {
  const HRow({
    super.key,
    required this.height,
    required this.children,
    this.spacing = 14,
  });
  final double height;
  final List<Widget> children;
  final double spacing;
  @override
  Widget build(BuildContext context) => SizedBox(
    height: height,
    child: ListView.separated(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 20),
      itemCount: children.length,
      separatorBuilder: (_, _) => SizedBox(width: spacing),
      itemBuilder: (_, i) => children[i],
    ),
  );
}

/// Small tag after the artist: LIVE for radio, a tick for channel songs
/// saved on the phone.
class SourceBadge extends StatelessWidget {
  const SourceBadge(this.track, {super.key});
  final Track track;

  @override
  Widget build(BuildContext context) {
    if (track.source == TrackSource.telegram) {
      final saved = context.watch<LibraryController>().isDownloaded(track);
      return saved
          ? Icon(AppIcons.saved, size: 13, color: Palette.of(context).sub)
          : const SizedBox.shrink();
    }
    if (!track.isRadio) return const SizedBox.shrink();
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
      decoration: BoxDecoration(
        color: const Color(0xFFE5484D),
        borderRadius: BorderRadius.circular(5),
      ),
      child: const Text(
        'LIVE',
        style: TextStyle(
          fontSize: 9,
          fontWeight: FontWeight.w800,
          letterSpacing: 0.4,
          color: Colors.white,
        ),
      ),
    );
  }
}

/// Radio logos are usually small favicons, so they sit centred on a soft
/// card instead of being stretched edge to edge.
class StationArt extends StatelessWidget {
  const StationArt(
    this.station, {
    super.key,
    this.size = 120,
    this.radius = 18,
  });
  final Track station;
  final double size;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final icon = Icon(AppIcons.radio, size: size * 0.36, color: p.sub);
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: p.card,
        borderRadius: BorderRadius.circular(radius),
      ),
      padding: EdgeInsets.all(size * 0.18),
      child: station.artworkUrl == null
          ? icon
          : ClipRRect(
              borderRadius: BorderRadius.circular(radius * 0.4),
              child: Image.network(
                station.artworkUrl!,
                fit: BoxFit.contain,
                webHtmlElementStrategy: WebHtmlElementStrategy.fallback,
                frameBuilder: (_, child, frame, sync) =>
                    sync || frame != null ? child : icon,
                errorBuilder: (_, _, _) => icon,
              ),
            ),
    );
  }
}

/// Square station tile with a LIVE tag, for horizontal rows and grids.
class StationCard extends StatelessWidget {
  const StationCard({
    super.key,
    required this.station,
    required this.onTap,
    this.width = 132,
  });
  final Track station;
  final VoidCallback onTap;
  final double width;

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final playing = c.current?.id == station.id;
    return GestureDetector(
      onTap: onTap,
      child: SizedBox(
        width: width,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Stack(
              children: [
                StationArt(station, size: width),
                Positioned(left: 8, top: 8, child: SourceBadge(station)),
                if (playing)
                  Positioned(
                    right: 10,
                    bottom: 10,
                    child: EqualizerBars(playing: c.isPlaying, size: 14),
                  ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              station.title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
            ),
            Text(
              station.artist,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(color: Palette.of(context).sub, fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }
}

/// Replaces the scrubber on live radio: a pulsing dot and what's on air.
class LiveBar extends StatefulWidget {
  const LiveBar({super.key, this.onAir});
  final String? onAir;
  @override
  State<LiveBar> createState() => _LiveBarState();
}

class _LiveBarState extends State<LiveBar> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1200),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Container(
      height: 64,
      padding: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: p.card,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        children: [
          FadeTransition(
            opacity: Tween(begin: 0.35, end: 1.0).animate(_c),
            child: Container(
              width: 10,
              height: 10,
              decoration: const BoxDecoration(
                color: Color(0xFFE5484D),
                shape: BoxShape.circle,
              ),
            ),
          ),
          const SizedBox(width: 10),
          const Text(
            'LIVE',
            style: TextStyle(fontWeight: FontWeight.w800, letterSpacing: 0.8),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Text(
              widget.onAir == null
                  ? 'Streaming now'
                  : 'On air: ${widget.onAir}',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(color: p.sub, fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }
}
