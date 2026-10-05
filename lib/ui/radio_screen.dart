import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../services/radio_api.dart';
import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'icons.dart';
import 'theme.dart';
import 'widgets.dart';

/// Live radio from around the world: near you, top stations, by genre, or search.
class RadioScreen extends StatefulWidget {
  const RadioScreen({super.key, this.initialFilter = 'near'});

  /// 'near', 'top', 'saved' or a genre tag like 'pop'.
  final String initialFilter;
  @override
  State<RadioScreen> createState() => _RadioScreenState();
}

class _RadioScreenState extends State<RadioScreen> {
  late String _filter = widget.initialFilter;
  String _q = '';
  Timer? _debounce;
  late Future<List<Track>> _future = _load();

  Future<List<Track>> _load() {
    final lib = context.read<LibraryController>();
    final api = lib.radio;
    if (_q.trim().isNotEmpty) return api.search(_q.trim(), limit: 40);
    return switch (_filter) {
      'near' =>
        lib.countryCode == null ? api.top() : api.byCountry(lib.countryCode!),
      'top' => api.top(),
      'saved' => Future.value(lib.savedStations),
      _ => api.byTag(_filter),
    };
  }

  void _reload() => setState(() => _future = _load());

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = context.read<PlayerController>();
    final p = Palette.of(context);
    final filters = [
      ('near', 'Near you'),
      ('top', 'Top'),
      ('saved', 'Saved'),
      ...RadioApi.tags,
    ];

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(AppIcons.back),
          onPressed: () => Navigator.maybePop(context),
        ),
        title: const Text('Live radio'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 12),
            child: TextField(
              onChanged: (v) {
                _q = v;
                _debounce?.cancel();
                _debounce = Timer(const Duration(milliseconds: 450), _reload);
              },
              decoration: const InputDecoration(
                hintText: 'Search stations',
                prefixIcon: Icon(AppIcons.search, size: 20),
              ),
            ),
          ),
          SizedBox(
            height: 40,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 20),
              children: [
                for (final (id, label) in filters)
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: ChoiceChip(
                      label: Text(label),
                      selected: _filter == id && _q.isEmpty,
                      onSelected: (_) {
                        _filter = id;
                        _reload();
                      },
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Expanded(
            child: FutureBuilder<List<Track>>(
              future: _future,
              builder: (context, snap) {
                if (snap.connectionState != ConnectionState.done) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (snap.hasError) {
                  return EmptyState(
                    icon: AppIcons.offline,
                    text: 'Could not reach the radio directory.',
                    action: OutlinedButton(
                      onPressed: _reload,
                      child: const Text('Retry'),
                    ),
                  );
                }
                final list = snap.data!;
                if (list.isEmpty) {
                  return EmptyState(
                    icon: AppIcons.radio,
                    text: _filter == 'saved' && _q.isEmpty
                        ? 'Tap the heart while a station plays to save it here.'
                        : 'No stations found.',
                  );
                }
                return GridView.builder(
                  padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
                  gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                    maxCrossAxisExtent: 180,
                    mainAxisSpacing: 18,
                    crossAxisSpacing: 14,
                    childAspectRatio: 0.74,
                  ),
                  itemCount: list.length,
                  itemBuilder: (_, i) => LayoutBuilder(
                    builder: (_, box) => StationCard(
                      station: list[i],
                      width: box.maxWidth,
                      onTap: () => c.playQueue(list, i),
                    ),
                  ),
                );
              },
            ),
          ),
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Text(
              'Stations from radio-browser.info',
              style: TextStyle(color: p.sub, fontSize: 11),
            ),
          ),
        ],
      ),
    );
  }
}
