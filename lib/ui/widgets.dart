import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../state/player_controller.dart';

String fmt(Duration? d) {
  if (d == null) return '--:--';
  final m = d.inMinutes;
  final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
  return '$m:$s';
}

class Artwork extends StatelessWidget {
  const Artwork(this.track, {super.key, this.size = 52, this.radius = 12});
  final Track? track;
  final double size;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final placeholder = Container(
      width: size,
      height: size,
      color: cs.secondaryContainer,
      child: Icon(Icons.music_note_rounded,
          size: size * 0.45, color: cs.onSecondaryContainer),
    );
    final url = track?.artworkUrl;
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: url == null
          ? placeholder
          : Image.network(url,
              width: size,
              height: size,
              fit: BoxFit.cover,
              errorBuilder: (_, _, _) => placeholder),
    );
  }
}

class TrackTile extends StatelessWidget {
  const TrackTile({super.key, required this.track, required this.onTap, this.onRemove});
  final Track track;
  final VoidCallback onTap;
  final VoidCallback? onRemove;

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final playing = c.current?.id == track.id;
    final cs = Theme.of(context).colorScheme;
    return ListTile(
      onTap: onTap,
      leading: Artwork(track),
      title: Text(track.title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
              fontWeight: FontWeight.w600,
              color: playing ? cs.primary : null)),
      subtitle: Text(track.artist, maxLines: 1, overflow: TextOverflow.ellipsis),
      trailing: PopupMenuButton<String>(
        icon: const Icon(Icons.more_horiz_rounded),
        onSelected: (v) {
          switch (v) {
            case 'fav':
              c.toggleFavorite(track);
            case 'next':
              c.playNext(track);
            case 'queue':
              c.addToQueue(track);
            case 'remove':
              onRemove?.call();
          }
        },
        itemBuilder: (_) => [
          PopupMenuItem(
              value: 'fav',
              child: Text(c.isFavorite(track)
                  ? 'Remove from favorites'
                  : 'Add to favorites')),
          const PopupMenuItem(value: 'next', child: Text('Play next')),
          const PopupMenuItem(value: 'queue', child: Text('Add to queue')),
          if (onRemove != null)
            const PopupMenuItem(value: 'remove', child: Text('Remove')),
        ],
      ),
    );
  }
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
            Icon(icon, size: 56, color: Theme.of(context).colorScheme.outline),
            const SizedBox(height: 12),
            Text(text, textAlign: TextAlign.center),
            if (action != null) ...[const SizedBox(height: 16), action!],
          ]),
        ),
      );
}
