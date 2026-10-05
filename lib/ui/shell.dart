import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'home_screen.dart';
import 'icons.dart';
import 'nav.dart';
import 'hotlist_screen.dart';
import 'library_screen.dart';
import 'now_playing.dart';
import 'search_screen.dart';
import 'theme.dart';
import 'widgets.dart';

/// Four tabs, each with its own navigator so the dark mini player and the
/// nav bar stay visible on album pages, just like the design.
class Shell extends StatefulWidget {
  const Shell({super.key});
  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> {
  int _tab = 0;
  final _navKeys = List.generate(4, (_) => GlobalKey<NavigatorState>());

  static const _items = [
    (AppIcons.home, AppIcons.homeOn, 'Home'),
    (AppIcons.search, AppIcons.searchOn, 'Search'),
    (AppIcons.library, AppIcons.libraryOn, 'Library'),
    (AppIcons.hot, AppIcons.hotOn, 'Hotlist'),
  ];

  @override
  void initState() {
    super.initState();
    shellNavigator.value = () => _navKeys[_tab].currentState;
  }

  Widget _root(int i) => switch (i) {
    0 => HomeScreen(onOpenTab: _select),
    1 => const SearchScreen(),
    2 => const LibraryScreen(),
    _ => const HotlistScreen(),
  };

  void _select(int i) {
    if (i == _tab) {
      _navKeys[i].currentState?.popUntil((r) => r.isFirst);
    } else {
      HapticFeedback.selectionClick();
      setState(() => _tab = i);
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final c = context.watch<PlayerController>();
    if (c.playError != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(c.playError!)));
        c.playError = null;
      });
    }
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        final nav = _navKeys[_tab].currentState;
        if (nav != null && nav.canPop()) {
          nav.pop();
        } else if (_tab != 0) {
          setState(() => _tab = 0);
        } else {
          SystemNavigator.pop();
        }
      },
      child: Scaffold(
        body: IndexedStack(
          index: _tab,
          children: [
            for (var i = 0; i < 4; i++)
              Navigator(
                key: _navKeys[i],
                onGenerateRoute: (_) =>
                    MaterialPageRoute(builder: (_) => _root(i)),
              ),
          ],
        ),
        bottomNavigationBar: Container(
          color: p.bg,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const _MiniPlayer(),
              Container(
                decoration: BoxDecoration(
                  color: p.bg,
                  borderRadius: const BorderRadius.vertical(
                    top: Radius.circular(24),
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.05),
                      blurRadius: 20,
                      offset: const Offset(0, -4),
                    ),
                  ],
                ),
                child: SafeArea(
                  top: false,
                  child: SizedBox(
                    height: 64,
                    child: Row(
                      children: [
                        for (var i = 0; i < _items.length; i++)
                          Expanded(
                            child: InkResponse(
                              onTap: () => _select(i),
                              radius: 36,
                              child: Column(
                                mainAxisAlignment: MainAxisAlignment.center,
                                children: [
                                  AnimatedSwitcher(
                                    duration: const Duration(milliseconds: 200),
                                    child: Icon(
                                      i == _tab ? _items[i].$2 : _items[i].$1,
                                      key: ValueKey(i == _tab),
                                      size: 24,
                                      color: i == _tab ? p.ink : p.sub,
                                    ),
                                  ),
                                  const SizedBox(height: 4),
                                  Text(
                                    _items[i].$3,
                                    style: TextStyle(
                                      fontSize: 11,
                                      fontWeight: i == _tab
                                          ? FontWeight.w700
                                          : FontWeight.w500,
                                      color: i == _tab ? p.ink : p.sub,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _MiniPlayer extends StatelessWidget {
  const _MiniPlayer();

  @override
  Widget build(BuildContext context) {
    final c = context.watch<PlayerController>();
    final lib = context.watch<LibraryController>();
    final p = Palette.of(context);
    final t = c.current;
    return AnimatedSize(
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeOutCubic,
      child: t == null
          ? const SizedBox(width: double.infinity)
          : GestureDetector(
              onTap: () => openNowPlaying(context),
              onVerticalDragEnd: (d) {
                if ((d.primaryVelocity ?? 0) < -300) openNowPlaying(context);
              },
              onHorizontalDragEnd: (d) {
                final v = d.primaryVelocity ?? 0;
                if (v < -300) c.next();
                if (v > 300) c.previous();
              },
              child: Container(
                margin: const EdgeInsets.fromLTRB(0, 0, 0, 0),
                decoration: BoxDecoration(
                  color: p.dock,
                  borderRadius: const BorderRadius.vertical(
                    top: Radius.circular(24),
                  ),
                ),
                clipBehavior: Clip.antiAlias,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    StreamBuilder<Duration>(
                      stream: c.player.positionStream,
                      builder: (_, snap) {
                        final total = c.player.duration?.inMilliseconds ?? 0;
                        final pos = snap.data?.inMilliseconds ?? 0;
                        return LinearProgressIndicator(
                          minHeight: 2,
                          value: total == 0 ? 0 : (pos / total).clamp(0.0, 1.0),
                          color: p.onDock,
                          backgroundColor: Colors.white12,
                        );
                      },
                    ),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(14, 12, 14, 16),
                      child: Row(
                        children: [
                          t.isRadio
                              ? StationArt(t, size: 46, radius: 23)
                              : Artwork(t, size: 46, radius: 23),
                          const SizedBox(width: 14),
                          Expanded(
                            child: AnimatedSwitcher(
                              duration: const Duration(milliseconds: 250),
                              layoutBuilder: (cur, prev) => Stack(
                                alignment: Alignment.centerLeft,
                                children: [...prev, ?cur],
                              ),
                              child: Column(
                                key: ValueKey(t.id),
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    t.title,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      color: p.onDock,
                                      fontWeight: FontWeight.w700,
                                      fontSize: 15,
                                    ),
                                  ),
                                  const SizedBox(height: 2),
                                  Text(
                                    t.isRadio
                                        ? '● LIVE  ${c.nowOnAir ?? t.artist}'
                                        : t.artist,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                      color: Colors.white60,
                                      fontSize: 12,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                          IconButton(
                            onPressed: () => lib.toggleFavorite(t),
                            icon: Icon(
                              lib.isFavorite(t)
                                  ? AppIcons.heartOn
                                  : AppIcons.heart,
                              color: lib.isFavorite(t)
                                  ? const Color(0xFFE5484D)
                                  : p.onDock,
                            ),
                          ),
                          const SizedBox(width: 4),
                          GestureDetector(
                            onTap: c.togglePlay,
                            child: Container(
                              width: 44,
                              height: 44,
                              decoration: BoxDecoration(
                                color: Colors.white,
                                borderRadius: BorderRadius.circular(12),
                              ),
                              child: Icon(
                                c.isPlaying ? AppIcons.pause : AppIcons.play,
                                size: 20,
                                color: const Color(0xFF1C1D22),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
    );
  }
}
