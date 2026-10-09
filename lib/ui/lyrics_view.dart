import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../state/lyrics_controller.dart';
import '../state/player_controller.dart';
import 'icons.dart';
import 'sheets.dart';
import 'theme.dart';
import 'widgets.dart';

/// Karaoke-style synced lyrics. The current line is highlighted and kept in
/// view; tapping a line seeks there. Scrolling by hand pauses the follow.
class LyricsView extends StatefulWidget {
  const LyricsView({super.key});
  @override
  State<LyricsView> createState() => _LyricsViewState();
}

class _LyricsViewState extends State<LyricsView> {
  final Map<int, GlobalKey> _keys = {};
  StreamSubscription<Duration>? _sub;
  int _line = -1;
  DateTime _userScrolledAt = DateTime(0);

  @override
  void initState() {
    super.initState();
    _sub = context.read<PlayerController>().player.positionStream.listen(
      _onPosition,
    );
  }

  void _onPosition(Duration pos) {
    if (!mounted) return;
    final l = context.read<LyricsController>();
    if (!l.canSync) return;
    final i = l.lineAt(pos);
    if (i == _line) return;
    setState(() => _line = i);
    if (DateTime.now().difference(_userScrolledAt) <
        const Duration(seconds: 4)) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final ctx = _keys[_line < 0 ? 0 : _line]?.currentContext;
      if (ctx == null) return;
      Scrollable.ensureVisible(
        ctx,
        alignment: 0.35,
        duration: const Duration(milliseconds: 450),
        curve: Curves.easeOutCubic,
      );
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = context.watch<LyricsController>();
    final c = context.watch<PlayerController>();
    final p = Palette.of(context);

    if (c.current?.isRadio ?? false) {
      return EmptyState(
        icon: AppIcons.radio,
        text: c.nowOnAir == null
            ? 'Lyrics aren\'t available on live radio.'
            : 'On air: ${c.nowOnAir}\nLyrics aren\'t available on live radio.',
      );
    }
    switch (l.status) {
      case LyricsStatus.loading:
      case LyricsStatus.idle:
        return const Center(child: CircularProgressIndicator());
      case LyricsStatus.error:
        return EmptyState(
          icon: AppIcons.offline,
          text: 'Could not load lyrics.',
          action: OutlinedButton(
            onPressed: l.retry,
            child: const Text('Try again'),
          ),
        );
      case LyricsStatus.notFound:
        return EmptyState(
          icon: AppIcons.lyrics,
          text: 'No lyrics found for this song.',
          action: FilledButton.icon(
            onPressed: () => showLyricsSearch(context),
            icon: const Icon(AppIcons.search, size: 18),
            label: const Text('Search lyrics'),
          ),
        );
      case LyricsStatus.found:
        break;
    }

    final lyr = l.lyrics!;
    if (lyr.instrumental) {
      return const EmptyState(
        icon: AppIcons.headphones,
        text: 'Instrumental. Enjoy the music.',
      );
    }
    final live = lyr.isSynced && l.canSync;

    Widget pill(String text, bool dark) => Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: dark ? p.ink : p.card,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w600,
          color: dark ? p.onInk : p.sub,
        ),
      ),
    );

    final toolbar = Row(
      children: [
        pill(live ? 'Synced' : 'Not synced', live),
        const Spacer(),
        if (live) ...[
          _Nudge(
            icon: AppIcons.minus,
            onTap: () => l.nudge(const Duration(milliseconds: -500)),
          ),
          SizedBox(
            width: 52,
            child: Text(
              '${l.offset.isNegative ? '' : '+'}${(l.offset.inMilliseconds / 1000).toStringAsFixed(1)}s',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 12,
                color: p.sub,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          _Nudge(
            icon: AppIcons.plus,
            onTap: () => l.nudge(const Duration(milliseconds: 500)),
          ),
          const SizedBox(width: 4),
        ],
        IconButton(
          tooltip: 'Search lyrics',
          icon: const Icon(AppIcons.findLyrics),
          onPressed: () => showLyricsSearch(context),
        ),
      ],
    );

    final lines = lyr.isSynced
        ? lyr.synced.map((x) => x.text).toList()
        : (lyr.plain ?? '').split('\n');

    return Column(
      children: [
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
                colors: [
                  Colors.transparent,
                  Colors.black,
                  Colors.black,
                  Colors.transparent,
                ],
                stops: [0, 0.08, 0.85, 1],
              ).createShader(r),
              blendMode: BlendMode.dstIn,
              child: ListView.builder(
                padding: const EdgeInsets.symmetric(vertical: 60),
                itemCount: lines.length,
                itemBuilder: (_, i) {
                  final active = live && i == _line;
                  final passed = live && i < _line;
                  final text = lines[i].trim();
                  final child = Padding(
                    padding: const EdgeInsets.symmetric(
                      vertical: 8,
                      horizontal: 4,
                    ),
                    child: AnimatedDefaultTextStyle(
                      duration: const Duration(milliseconds: 300),
                      curve: Curves.easeOut,
                      style: TextStyle(
                        fontFamily: 'Inter',
                        fontSize: active ? 26 : 22,
                        height: 1.3,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.4,
                        color: !live
                            ? p.ink.withValues(alpha: 0.85)
                            : active
                            ? p.ink
                            : passed
                            ? p.sub.withValues(alpha: 0.5)
                            : p.sub.withValues(alpha: 0.8),
                      ),
                      child: Text(text.isEmpty ? '♪' : text),
                    ),
                  );
                  if (!live) return child;
                  return InkWell(
                    key: _keys.putIfAbsent(i, GlobalKey.new),
                    borderRadius: BorderRadius.circular(12),
                    onTap: () {
                      _userScrolledAt = DateTime(0);
                      final target = lyr.synced[i].time - l.offset;
                      c.seek(target.isNegative ? Duration.zero : target);
                    },
                    child: child,
                  );
                },
              ),
            ),
          ),
        ),
      ],
    );
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
      decoration: BoxDecoration(
        color: Palette.of(context).card,
        shape: BoxShape.circle,
      ),
      child: Icon(icon, size: 14),
    ),
  );
}
