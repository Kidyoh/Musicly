import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../services/audius_api.dart';
import '../state/player_controller.dart';
import 'theme.dart';
import 'widgets.dart';

/// Live charts: ranked trending songs, filterable by period and genre.
class HotlistScreen extends StatefulWidget {
  const HotlistScreen({super.key});
  @override
  State<HotlistScreen> createState() => _HotlistScreenState();
}

class _HotlistScreenState extends State<HotlistScreen> {
  String _time = 'week';
  String? _genre;
  late Future<List<Track>> _future = _load();

  Future<List<Track>> _load() =>
      context.read<PlayerController>().api.trending(limit: 50, genre: _genre, time: _time);

  void _reload() => setState(() => _future = _load());

  @override
  Widget build(BuildContext context) {
    final c = context.read<PlayerController>();
    final p = Palette.of(context);

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: Column(children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 12),
            child: Row(children: [
              const Expanded(
                child: Text('Hotlist',
                    style: TextStyle(fontSize: 30, fontWeight: FontWeight.w700, letterSpacing: -0.8)),
              ),
              SegmentedButton<String>(
                showSelectedIcon: false,
                style: SegmentedButton.styleFrom(
                  visualDensity: VisualDensity.compact,
                  selectedBackgroundColor: p.ink,
                  selectedForegroundColor: p.onInk,
                  side: BorderSide(color: p.line),
                ),
                segments: const [
                  ButtonSegment(value: 'week', label: Text('Week')),
                  ButtonSegment(value: 'month', label: Text('Month')),
                  ButtonSegment(value: 'allTime', label: Text('All')),
                ],
                selected: {_time},
                onSelectionChanged: (s) {
                  _time = s.first;
                  _reload();
                },
              ),
            ]),
          ),
          SizedBox(
            height: 40,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 20),
              children: [
                for (final g in [null, ...AudiusApi.genres])
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: ChoiceChip(
                      label: Text(g ?? 'All genres'),
                      selected: _genre == g,
                      onSelected: (_) {
                        _genre = g;
                        _reload();
                      },
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 8),
          Expanded(
            child: FutureBuilder<List<Track>>(
              future: _future,
              builder: (context, snap) {
                if (snap.connectionState != ConnectionState.done) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (snap.hasError) {
                  return EmptyState(
                    icon: Icons.cloud_off_rounded,
                    text: 'Could not load the charts.',
                    action: OutlinedButton(onPressed: _reload, child: const Text('Retry')),
                  );
                }
                final tracks = snap.data!;
                if (tracks.isEmpty) {
                  return const EmptyState(icon: Icons.music_off_outlined, text: 'Nothing trending here yet.');
                }
                return RefreshIndicator(
                  onRefresh: () async {
                    _reload();
                    await _future;
                  },
                  child: ListView.builder(
                    padding: const EdgeInsets.only(bottom: 24),
                    itemCount: tracks.length,
                    itemBuilder: (_, i) => ArtTrackRow(
                      track: tracks[i],
                      onTap: () => c.playQueue(tracks, i),
                      leading: SizedBox(
                        width: 28,
                        child: Text('${i + 1}',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                              fontSize: i < 3 ? 20 : 16,
                              fontWeight: FontWeight.w800,
                              color: i < 3 ? p.ink : p.sub,
                            )),
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ]),
      ),
    );
  }
}
