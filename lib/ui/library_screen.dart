import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../state/player_controller.dart';
import 'collection_screen.dart';
import 'sheets.dart';
import 'theme.dart';
import 'widgets.dart';

class LibraryScreen extends StatelessWidget {
  const LibraryScreen({super.key});

  void _open(BuildContext context, String title, String owner, List<Track> Function(PlayerController) live,
      {bool removable = false}) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => CollectionScreen(
        title: title,
        owner: owner,
        kind: 'Collection',
        live: live,
        removable: removable,
      ),
    ));
  }

  Future<void> _add(BuildContext context) async {
    final n = await context.read<PlayerController>().pickLocalFiles();
    if (context.mounted && n > 0) toast(context, 'Added $n song${n == 1 ? '' : 's'}');
  }

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final p = Palette.of(context);

    Widget tile(IconData icon, String title, String sub, VoidCallback onTap, {bool dark = false}) =>
        InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
            child: Row(children: [
              Container(
                width: 56,
                height: 56,
                decoration: BoxDecoration(
                    color: dark ? p.ink : p.card, borderRadius: BorderRadius.circular(14)),
                child: Icon(icon, color: dark ? p.onInk : p.ink),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
                  const SizedBox(height: 3),
                  Text(sub, style: TextStyle(color: p.sub, fontSize: 13)),
                ]),
              ),
              Icon(Icons.chevron_right_rounded, color: p.sub),
            ]),
          ),
        );

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: ListView(padding: const EdgeInsets.only(bottom: 24), children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 12, 8),
            child: Row(children: [
              const Expanded(
                child: Text('Library',
                    style: TextStyle(fontSize: 30, fontWeight: FontWeight.w700, letterSpacing: -0.8)),
              ),
              IconButton.filled(
                style: IconButton.styleFrom(backgroundColor: p.ink, foregroundColor: p.onInk),
                tooltip: 'Add music from device',
                onPressed: () => _add(context),
                icon: const Icon(Icons.add_rounded),
              ),
            ]),
          ),
          tile(Icons.favorite_rounded, 'Liked songs', '${c.favorites.length} songs',
              () => _open(context, 'Liked songs', 'You', (c) => c.favorites),
              dark: true),
          tile(Icons.smartphone_rounded, 'On this device', '${c.localTracks.length} songs',
              () => _open(context, 'On this device', 'Your files', (c) => c.localTracks,
                  removable: true)),
          tile(Icons.history_rounded, 'Recently played', '${c.recent.length} songs',
              () => _open(context, 'Recently played', 'You', (c) => c.recent)),
          if (c.localTracks.isEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 24, 20, 0),
              child: Container(
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(color: p.card, borderRadius: BorderRadius.circular(20)),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  const Text('Bring your own music',
                      style: TextStyle(fontWeight: FontWeight.w700, fontSize: 17)),
                  const SizedBox(height: 6),
                  Text(
                    'Add MP3, M4A, FLAC or WAV files from your device. Name files like '
                    '"Artist - Title.mp3" and Musicly will find synced lyrics for them too.',
                    style: TextStyle(color: p.sub, height: 1.5),
                  ),
                  const SizedBox(height: 14),
                  PillButton(
                      icon: Icons.folder_open_rounded,
                      label: 'Add music',
                      onPressed: () => _add(context)),
                ]),
              ),
            )
          else ...[
            const SectionHeader('Your songs'),
            for (var i = 0; i < c.localTracks.length; i++)
              NumberedTrackRow(
                index: i,
                track: c.localTracks[i],
                onTap: () => c.playQueue(c.localTracks, i),
                onRemove: () => c.removeLocal(c.localTracks[i]),
              ),
          ],
        ]),
      ),
    );
  }
}
