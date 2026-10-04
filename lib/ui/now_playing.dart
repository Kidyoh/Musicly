import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import 'package:provider/provider.dart';

import '../state/player_controller.dart';
import 'widgets.dart';

void showNowPlaying(BuildContext context) {
  showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    showDragHandle: true,
    builder: (_) => const NowPlaying(),
  );
}

class NowPlaying extends StatelessWidget {
  const NowPlaying({super.key});

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final t = c.current;
    if (t == null) return const SizedBox(height: 200);
    final cs = Theme.of(context).colorScheme;
    final side = (MediaQuery.sizeOf(context).shortestSide - 64).clamp(180.0, 380.0);

    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
      child: Column(children: [
        Artwork(t, size: side, radius: 28),
        const SizedBox(height: 24),
        Row(children: [
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(t.title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context)
                      .textTheme
                      .titleLarge
                      ?.copyWith(fontWeight: FontWeight.w700)),
              Text(t.artist, style: TextStyle(color: cs.onSurfaceVariant)),
            ]),
          ),
          IconButton(
            onPressed: () => c.toggleFavorite(t),
            icon: Icon(c.isFavorite(t)
                ? Icons.favorite_rounded
                : Icons.favorite_border_rounded),
            color: c.isFavorite(t) ? cs.primary : null,
          ),
        ]),
        const SizedBox(height: 8),
        _SeekBar(c.player),
        const SizedBox(height: 4),
        Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
          IconButton(
              onPressed: c.toggleShuffle,
              color: c.shuffle ? cs.primary : null,
              icon: const Icon(Icons.shuffle_rounded)),
          IconButton(
              iconSize: 40,
              onPressed: c.previous,
              icon: const Icon(Icons.skip_previous_rounded)),
          StreamBuilder<PlayerState>(
            stream: c.player.playerStateStream,
            builder: (_, snap) {
              final s = snap.data;
              final busy = s?.processingState == ProcessingState.loading ||
                  s?.processingState == ProcessingState.buffering;
              return SizedBox(
                width: 72,
                height: 72,
                child: busy
                    ? const Padding(
                        padding: EdgeInsets.all(20),
                        child: CircularProgressIndicator())
                    : IconButton.filled(
                        iconSize: 40,
                        onPressed: c.togglePlay,
                        icon: Icon(s?.playing == true
                            ? Icons.pause_rounded
                            : Icons.play_arrow_rounded)),
              );
            },
          ),
          IconButton(
              iconSize: 40,
              onPressed: c.next,
              icon: const Icon(Icons.skip_next_rounded)),
          IconButton(
              onPressed: c.cycleLoop,
              color: c.loopMode == LoopMode.off ? null : cs.primary,
              icon: Icon(c.loopMode == LoopMode.one
                  ? Icons.repeat_one_rounded
                  : Icons.repeat_rounded)),
        ]),
        const SizedBox(height: 12),
        Row(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [
          TextButton.icon(
              onPressed: () => _speedSheet(context, c),
              icon: const Icon(Icons.speed_rounded),
              label: Text('${c.speed}x')),
          TextButton.icon(
              onPressed: () => _sleepSheet(context, c),
              icon: const Icon(Icons.bedtime_outlined),
              label: Text(c.sleepRemaining == null
                  ? 'Sleep'
                  : '${c.sleepRemaining!.inMinutes + 1} min')),
          TextButton.icon(
              onPressed: () => _queueSheet(context),
              icon: const Icon(Icons.queue_music_rounded),
              label: const Text('Queue')),
        ]),
      ]),
    );
  }

  void _speedSheet(BuildContext context, PlayerController c) {
    showModalBottomSheet(
      context: context,
      builder: (_) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          for (final s in [0.75, 1.0, 1.25, 1.5, 2.0])
            ListTile(
              title: Text('${s}x'),
              trailing: c.speed == s ? const Icon(Icons.check_rounded) : null,
              onTap: () {
                c.setSpeed(s);
                Navigator.pop(context);
              },
            ),
        ]),
      ),
    );
  }

  void _sleepSheet(BuildContext context, PlayerController c) {
    showModalBottomSheet(
      context: context,
      builder: (_) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          for (final m in [5, 15, 30, 60])
            ListTile(
              title: Text('Stop after $m minutes'),
              onTap: () {
                c.setSleepTimer(Duration(minutes: m));
                Navigator.pop(context);
              },
            ),
          if (c.sleepRemaining != null)
            ListTile(
              title: const Text('Turn off timer'),
              onTap: () {
                c.setSleepTimer(null);
                Navigator.pop(context);
              },
            ),
        ]),
      ),
    );
  }

  void _queueSheet(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => const _QueueSheet(),
    );
  }
}

class _QueueSheet extends StatelessWidget {
  const _QueueSheet();

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    return ListView.builder(
      itemCount: c.queue.length,
      itemBuilder: (_, i) {
        final t = c.queue[i];
        final active = i == c.currentIndex;
        return ListTile(
          leading: Artwork(t, size: 44, radius: 10),
          title: Text(t.title, maxLines: 1, overflow: TextOverflow.ellipsis),
          subtitle: Text(t.artist, maxLines: 1, overflow: TextOverflow.ellipsis),
          selected: active,
          onTap: () => c.player.seek(Duration.zero, index: i),
          trailing: active
              ? const Icon(Icons.equalizer_rounded)
              : IconButton(
                  icon: const Icon(Icons.close_rounded),
                  onPressed: () => c.removeFromQueue(i)),
        );
      },
    );
  }
}

class _SeekBar extends StatefulWidget {
  const _SeekBar(this.player);
  final AudioPlayer player;
  @override
  State<_SeekBar> createState() => _SeekBarState();
}

class _SeekBarState extends State<_SeekBar> {
  double? _drag;

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<Duration>(
      stream: widget.player.positionStream,
      builder: (_, snap) {
        final total = widget.player.duration ?? Duration.zero;
        final pos = snap.data ?? Duration.zero;
        final max = total.inMilliseconds.toDouble();
        final value = (_drag ?? pos.inMilliseconds.toDouble()).clamp(0.0, max == 0 ? 1.0 : max);
        return Column(children: [
          Slider(
            value: value,
            max: max == 0 ? 1 : max,
            onChanged: max == 0 ? null : (v) => setState(() => _drag = v),
            onChangeEnd: (v) {
              widget.player.seek(Duration(milliseconds: v.round()));
              setState(() => _drag = null);
            },
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              Text(fmt(Duration(milliseconds: value.round()))),
              Text(fmt(total)),
            ]),
          ),
        ]);
      },
    );
  }
}
