import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import 'package:provider/provider.dart';

import '../state/player_controller.dart';
import 'now_playing.dart';
import 'widgets.dart';

class MiniPlayer extends StatelessWidget {
  const MiniPlayer({super.key});

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final t = c.current;
    if (t == null) return const SizedBox.shrink();
    final cs = Theme.of(context).colorScheme;
    return GestureDetector(
      onTap: () => showNowPlaying(context),
      child: Container(
        margin: const EdgeInsets.fromLTRB(8, 0, 8, 4),
        decoration: BoxDecoration(
            color: cs.surfaceContainerHigh,
            borderRadius: BorderRadius.circular(16)),
        clipBehavior: Clip.antiAlias,
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          ListTile(
            dense: true,
            leading: Artwork(t, size: 44, radius: 10),
            title: Text(t.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w600)),
            subtitle: Text(t.artist, maxLines: 1, overflow: TextOverflow.ellipsis),
            trailing: Row(mainAxisSize: MainAxisSize.min, children: [
              StreamBuilder<PlayerState>(
                stream: c.player.playerStateStream,
                builder: (_, snap) {
                  final playing = snap.data?.playing ?? false;
                  return IconButton(
                    onPressed: c.togglePlay,
                    icon: Icon(playing
                        ? Icons.pause_rounded
                        : Icons.play_arrow_rounded),
                  );
                },
              ),
              IconButton(
                  onPressed: c.next, icon: const Icon(Icons.skip_next_rounded)),
            ]),
          ),
          StreamBuilder<Duration>(
            stream: c.player.positionStream,
            builder: (_, snap) {
              final total = c.player.duration?.inMilliseconds ?? 0;
              final pos = snap.data?.inMilliseconds ?? 0;
              return LinearProgressIndicator(
                  minHeight: 2, value: total == 0 ? 0 : (pos / total).clamp(0, 1));
            },
          ),
        ]),
      ),
    );
  }
}
