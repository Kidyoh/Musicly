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
  final _stationSearchController = TextEditingController();
  late Future<List<Track>> _future = _load();
  String? _selectedCountryCode;
  List<Map> _countries = [];
  bool _loadingCountries = false;

  Future<List<Track>> _load() {
    final lib = context.read<LibraryController>();
    final api = lib.radio;
    if (_q.trim().isNotEmpty) return api.search(_q.trim(), limit: 40);
    if (_selectedCountryCode != null) {
      return api.byCountry(_selectedCountryCode!);
    }
    return switch (_filter) {
      'near' =>
        lib.countryCode == null ? api.top() : api.byCountry(lib.countryCode!),
      'top' => api.top(),
      'saved' => Future.value(lib.savedStations),
      _ => api.byTag(_filter),
    };
  }

  void _reload() {
    setState(() {
      _future = _load();
    });
  }

  void _selectFilter(String filter, {String? countryCode}) {
    _debounce?.cancel();
    _stationSearchController.clear();
    _q = '';
    _selectedCountryCode = countryCode;
    _filter = filter;
    _reload();
  }

  Future<void> _loadCountries() async {
    if (_countries.isNotEmpty) return;
    setState(() => _loadingCountries = true);
    try {
      final countries = await context
          .read<LibraryController>()
          .radio
          .fetchCountries();
      // Sort alphabetically by name
      countries.sort(
        (a, b) =>
            (a['name'] as String? ?? '').compareTo(b['name'] as String? ?? ''),
      );
      if (mounted) setState(() => _countries = countries);
    } catch (_) {}
    if (mounted) setState(() => _loadingCountries = false);
  }

  void _showCountryPicker() async {
    await _loadCountries();
    if (!mounted) return;
    final p = Palette.of(context);
    final lib = context.read<LibraryController>();
    String searchQuery = '';
    showModalBottomSheet(
      context: context,
      useRootNavigator: true,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setModalState) {
          final filteredCountries = _countries
              .where(
                (c) => (c['name'] as String? ?? '').toLowerCase().contains(
                  searchQuery.toLowerCase(),
                ),
              )
              .toList();
          return Padding(
            padding: EdgeInsets.only(
              bottom: MediaQuery.viewInsetsOf(ctx).bottom,
            ),
            child: SafeArea(
              top: false,
              child: SizedBox(
                height: MediaQuery.sizeOf(ctx).height * 0.85,
                child: Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(24, 16, 24, 8),
                      child: Row(
                        children: [
                          const Text(
                            'Select Country',
                            style: TextStyle(
                              fontSize: 20,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          const Spacer(),
                          IconButton(
                            icon: const Icon(AppIcons.close),
                            onPressed: () => Navigator.pop(ctx),
                          ),
                        ],
                      ),
                    ),
                    const Divider(height: 1),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                      child: TextField(
                        decoration: InputDecoration(
                          hintText: 'Search countries...',
                          prefixIcon: const Icon(AppIcons.search, size: 20),
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                            borderSide: BorderSide.none,
                          ),
                          filled: true,
                          fillColor: p.card,
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 16,
                            vertical: 12,
                          ),
                        ),
                        onChanged: (v) {
                          setModalState(() => searchQuery = v);
                        },
                      ),
                    ),
                    const Divider(height: 1),
                    Expanded(
                      child: _loadingCountries
                          ? const Padding(
                              padding: EdgeInsets.all(32),
                              child: Center(child: CircularProgressIndicator()),
                            )
                          : ListView.builder(
                              itemCount: filteredCountries.length + 1,
                              itemBuilder: (_, i) {
                                if (i == 0) {
                                  return ListTile(
                                    leading: Icon(
                                      AppIcons.globe,
                                      color: _selectedCountryCode == null
                                          ? p.ink
                                          : p.sub,
                                    ),
                                    title: Text(
                                      'All countries',
                                      style: TextStyle(
                                        fontWeight: _selectedCountryCode == null
                                            ? FontWeight.w700
                                            : FontWeight.w400,
                                        color: _selectedCountryCode == null
                                            ? p.ink
                                            : p.ink,
                                      ),
                                    ),
                                    onTap: () {
                                      _selectFilter('top');
                                      Navigator.pop(ctx);
                                    },
                                  );
                                }
                                final c = filteredCountries[i - 1];
                                final code = c['iso_3166_1'] as String? ?? '';
                                final name = c['name'] as String? ?? '';
                                final count = c['stationcount'] as int? ?? 0;
                                return ListTile(
                                  leading: Text(
                                    _flag(code),
                                    style: const TextStyle(fontSize: 24),
                                  ),
                                  title: Text(
                                    name,
                                    style: TextStyle(
                                      fontWeight: _selectedCountryCode == code
                                          ? FontWeight.w700
                                          : FontWeight.w400,
                                    ),
                                  ),
                                  subtitle: Text('$count stations'),
                                  trailing: IconButton(
                                    tooltip:
                                        lib.favoriteRadioCountries.containsKey(
                                          code,
                                        )
                                        ? 'Unfavorite $name'
                                        : 'Favorite $name',
                                    icon: Icon(
                                      lib.favoriteRadioCountries.containsKey(
                                            code,
                                          )
                                          ? AppIcons.heartOn
                                          : AppIcons.heart,
                                    ),
                                    onPressed: code.isEmpty
                                        ? null
                                        : () {
                                            lib.toggleFavoriteRadioCountry(
                                              code,
                                              name,
                                            );
                                            setModalState(() {});
                                          },
                                  ),
                                  onTap: () {
                                    _selectFilter(
                                      'country:$code',
                                      countryCode: code,
                                    );
                                    Navigator.pop(ctx);
                                  },
                                );
                              },
                            ),
                    ),
                    const SizedBox(height: 16),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  String _flag(String code) {
    if (code.length != 2) return '🏳️';
    return code
        .toUpperCase()
        .codeUnits
        .map((c) => String.fromCharCode(0x1F1E6 - 65 + c))
        .join();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _stationSearchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = context.read<PlayerController>();
    final p = Palette.of(context);
    final favoriteCountries = context
        .watch<LibraryController>()
        .favoriteRadioCountries;
    final filters = [
      ('near', 'Near you'),
      for (final country in favoriteCountries.entries)
        ('country:${country.key}', '${_flag(country.key)} ${country.value}'),
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
        actions: [
          IconButton(
            icon: const Icon(AppIcons.globe),
            onPressed: _showCountryPicker,
            tooltip: 'Select country',
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 12),
            child: TextField(
              controller: _stationSearchController,
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
                        _selectFilter(
                          id,
                          countryCode: id.startsWith('country:')
                              ? id.substring('country:'.length)
                              : null,
                        );
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
