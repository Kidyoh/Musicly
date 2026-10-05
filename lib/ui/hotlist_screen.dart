import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'icons.dart';
import 'theme.dart';
import 'widgets.dart';

enum _Tab { songs, stations }

/// Your hotlist: the songs you play most, and the stations everyone's
/// tuning into right now.
class HotlistScreen extends StatefulWidget {
  const HotlistScreen({super.key});
  @override
  State<HotlistScreen> createState() => _HotlistScreenState();
}

class _HotlistScreenState extends State<HotlistScreen> {
  _Tab _tab = _Tab.songs;
  Future<List<Track>>? _stations;

  Future<List<Track>> _loadStations() =>
      context.read<LibraryController>().radio.top(limit: 50);

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryController>();
    final c = context.read<PlayerController>();
    final p = Palette.of(context);

    Widget rank(int i) => SizedBox(
      width: 28,
      child: Text(
        '${i + 1}',
        textAlign: TextAlign.center,
        style: TextStyle(
          fontSize: i < 3 ? 20 : 16,
          fontWeight: FontWeight.w800,
          color: i < 3 ? p.ink : p.sub,
        ),
      ),
    );

    Widget songs() {
      final top = lib.mostPlayed.take(50).toList();
      if (top.isEmpty) {
        return const EmptyState(
          icon: AppIcons.hot,
          text: 'Your most played songs show up here.\nStart listening to your channel or phone music.',
        );
      }
      return ListView.builder(
        padding: const EdgeInsets.only(bottom: 24),
        itemCount: top.length,
        itemBuilder: (_, i) => ArtTrackRow(
          track: top[i],
          onTap: () => c.playQueue(top, i),
          leading: rank(i),
        ),
      );
    }

    Widget stations() {
      _stations ??= _loadStations();
      return FutureBuilder<List<Track>>(
        future: _stations,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snap.hasError || (snap.data?.isEmpty ?? true)) {
            return EmptyState(
              icon: AppIcons.offline,
              text: 'Could not load stations.',
              action: OutlinedButton(
                onPressed: () => setState(() => _stations = _loadStations()),
                child: const Text('Retry'),
              ),
            );
          }
          final list = snap.data!;
          return ListView.builder(
            padding: const EdgeInsets.only(bottom: 24),
            itemCount: list.length,
            itemBuilder: (_, i) => ArtTrackRow(
              track: list[i],
              onTap: () => c.playQueue(list, i),
              leading: rank(i),
            ),
          );
        },
      );
    }

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 12),
              child: Row(
                children: [
                  const Expanded(
                    child: Text(
                      'Hotlist',
                      style: TextStyle(
                        fontSize: 30,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.8,
                      ),
                    ),
                  ),
                  SegmentedButton<_Tab>(
                    showSelectedIcon: false,
                    style: SegmentedButton.styleFrom(
                      visualDensity: VisualDensity.compact,
                      selectedBackgroundColor: p.ink,
                      selectedForegroundColor: p.onInk,
                      side: BorderSide(color: p.line),
                    ),
                    segments: const [
                      ButtonSegment(value: _Tab.songs, label: Text('Your top')),
                      ButtonSegment(value: _Tab.stations, label: Text('Radio')),
                    ],
                    selected: {_tab},
                    onSelectionChanged: (s) => setState(() => _tab = s.first),
                  ),
                ],
              ),
            ),
            Expanded(child: _tab == _Tab.songs ? songs() : stations()),
          ],
        ),
      ),
    );
  }
}
