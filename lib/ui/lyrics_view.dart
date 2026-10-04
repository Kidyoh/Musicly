import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../state/lyrics_controller.dart';
import '../state/player_controller.dart';
import 'sheets.dart';
import 'theme.dart';
import 'widgets.dart';

/// Karaoke-style synced lyrics. The current line is highlighted and kept in
/// view; tapping any line seeks there. Scrolling by hand pauses the follow
/// for a few seconds.
class LyricsView extends StatefulWidget {
  const LyricsView({super.key});
  @override
  State<LyricsView> createState() => _LyricsViewState();
}

class _LyricsViewState extends State<LyricsView> {
  final _scroll = ScrollController();
  final Map<int, GlobalKey> _keys = {};
  StreamSubscription<Duration>? _sub;
  int _line = -1;
  DateTime _userScrolledAt = DateTime(0);

  @override
  void initState() {
    super.initState();
    final c = context.read<PlayerController>();
    _sub = c.player.positionStream.listen(_onPosition);
  }

  void _onPosition(Duration pos) {
    if (!mounted) return;
    final i = context.read<LyricsController>().lineAt(pos);
    if (i == _line) return;
    setState(() => _line = i);
    if (DateTime.now().difference(_userScrolledAt) < const Duration(seconds: 4)) return;
    WidgetsBinding.instance.addPostFrameCallback((_) => _follow());
  }

  void _follow() {
    final ctx = _keys[_line < 0 ? 0 : _line]?.currentContext;
    if (ctx == null) return;
    Scrollable.ensureVisible(ctx,
        alignment: 0.35,
        duration: const Duration(milliseconds: 450),
        curve: Curves.easeOutCubic);
  }

  @override
  void dispose() {
    _sub?.cancel();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = context.watch<LyricsController>();
    final c = context.read<PlayerController>();
    final p = Palette.of(context);

    switch (l.status) {
      case LyricsStatus.loading:
      case LyricsStatus.idle:
        return const Center(child: CircularProgressIndicator());
      case LyricsStatus.error:
        return EmptyState(
          icon: Icons.cloud_off_rounded,
          text: 'Could not load lyrics.',
          action: OutlinedButton(onPressed: l.retry, child: const Text('Try again')),
        );
      case LyricsStatus.notFound:
        return EmptyState(
          icon: Icons.lyrics_outlined,
          text: 'No lyrics found for this song.',
          action: FilledButton.icon(
            onPressed: () => showLyricsSearch(context),
            icon: const Icon(Icons.search_rounded),
            label: const Text('Search lyrics'),
          ),
        );
      case LyricsStatus.found:
        break;
    }

    final lyr = l.lyrics!;
    if (lyr.instrumental) {
      return const EmptyState(icon: Icons.piano_rounded, text: 'Instrumental — enjoy the music.');
    }

    final toolbar = Row(children: [
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
            color: lyr.isSynced ? p.ink : p.card, borderRadius: BorderRadius.circular(20)),
        child: Text(lyr.isSynced ? 'Synced' : 'Not synced',
            style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: lyr.isSynced ? p.onInk : p.sub)),
      ),
      const Spacer(),
      if (lyr.isSynced) ...[
        _Nudge(icon: Icons.remove_rounded, onTap: () => l.nudge(const Duration(milliseconds: -500))),
        SizedBox(
          width: 52,
          child: Text(
            '${l.offset.isNegative ? '' : '+'}${(l.offset.inMilliseconds / 1000).toStringAsFixed(1)}s',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 12, color: p.sub, fontWeight: FontWeight.w600),
          ),
        ),
        _Nudge(icon: Icons.add_rounded, onTap: () => l.nudge(const Duration(milliseconds: 500))),
        const SizedBox(width: 4),
      ],
      IconButton(
        tooltip: 'Search lyrics',
        icon: const Icon(Icons.manage_search_rounded),
        onPressed: () => showLyricsSearch(context),
      ),
    ]);

    if (!lyr.isSynced) {
      return Column(children: [
        toolbar,
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(vertical: 16),
            child: Text(lyr.plain ?? '',
                style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w600, height: 1.6)),
          ),
        ),
      ]);
    }

    return Column(children: [
      toolbar,
      Expanded(
        child: NotificationListener<UserScrollNotification>(
          onNotification: (_) {
            _userScrolledAt = DateTime.now();
            return false;
          },
          child: ShaderMask(
            shaderCallback: (r) => const LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [Colors.transparent, Colors.black, Colors.black, Colors.transparent],
              stops: [0, 0.08, 0.85, 1],
            ).createShader(r),
            blendMode: BlendMode.dstIn,
            child: ListView.builder(
              controller: _scroll,
              padding: const EdgeInsets.symmetric(vertical: 60),
              itemCount: lyr.synced.length,
              itemBuilder: (_, i) {
                final line = lyr.synced[i];
                final active = i == _line;
                final passed = i < _line;
                return InkWell(
                  key: _keys.putIfAbsent(i, GlobalKey.new),
                  borderRadius: BorderRadius.circular(12),
                  onTap: () {
                    _userScrolledAt = DateTime(0);
                    final target = line.time - l.offset;
                    c.seek(target.isNegative ? Duration.zero : target);
                  },
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
                    child: AnimatedDefaultTextStyle(
                      duration: const Duration(milliseconds: 300),
                      curve: Curves.easeOut,
                      style: TextStyle(
                        fontFamily: 'Inter',
                        fontSize: active ? 26 : 22,
                        height: 1.3,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.4,
                        color: active
                            ? p.ink
                            : passed
                                ? p.sub.withValues(alpha: 0.55)
                                : p.sub.withValues(alpha: 0.8),
                      ),
                      child: Text(line.text.isEmpty ? '♪' : line.text),
                    ),
                  ),
                );
              },
            ),
          ),
        ),
      ),
    ]);
  }
}

class _Nudge extends StatelessWidget {
  const _Nudge({required this.icon, required this.onTap});
  final IconData icon;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => InkWell(
        customBorder: const CircleBorder(),
        onTap: onTap,
        child: Container(
          width: 28,
          height: 28,
          decoration: BoxDecoration(color: Palette.of(context).card, shape: BoxShape.circle),
          child: Icon(icon, size: 16),
        ),
      );
}
