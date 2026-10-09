import 'dart:math';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../state/library_controller.dart';
import 'icons.dart';
import 'minimal_style.dart';
import 'sheets.dart';
import 'theme.dart';

/// Choose how the player looks. The choice applies to Now Playing and the
/// mini player straight away.
void showPlayerStyle(BuildContext context) {
  showModalBottomSheet(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    builder: (ctx) => const SafeArea(child: _StyleSheet()),
  );
}

class _StyleSheet extends StatelessWidget {
  const _StyleSheet();

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryController>();
    final p = Palette.of(context);
    Widget card(PlayerStyle style, String name, String note, Widget preview) {
      final on = lib.playerStyle == style;
      return Expanded(
        child: GestureDetector(
          onTap: () {
            lib.setPlayerStyle(style);
            toast(context, '$name player');
          },
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: p.card,
              borderRadius: BorderRadius.circular(24),
              border: Border.all(
                color: on ? p.ink : Colors.transparent,
                width: 2,
              ),
            ),
            child: Column(
              children: [
                AspectRatio(
                  aspectRatio: .62,
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(16),
                    child: preview,
                  ),
                ),
                const SizedBox(height: 10),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    if (on) ...[
                      Icon(AppIcons.check, size: 16, color: p.ink),
                      const SizedBox(width: 6),
                    ],
                    Text(
                      name,
                      style: const TextStyle(
                        fontWeight: FontWeight.w700,
                        fontSize: 16,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 2),
                Text(
                  note,
                  textAlign: TextAlign.center,
                  style: TextStyle(color: p.sub, fontSize: 12.5, height: 1.3),
                ),
              ],
            ),
          ),
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 20),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Player style',
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 4),
          Text(
            'How Now Playing and the mini player look. Change it any time.',
            style: TextStyle(color: p.sub, height: 1.4),
          ),
          const SizedBox(height: 16),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              card(
                PlayerStyle.classic,
                'Classic',
                'Cover art, glow and a waveform',
                const _ClassicPreview(),
              ),
              const SizedBox(width: 12),
              card(
                PlayerStyle.minimal,
                'Minimal',
                'A turning disc and a click-wheel',
                const _MinimalPreview(),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _ClassicPreview extends StatelessWidget {
  const _ClassicPreview();

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final heights = [.3, .6, .9, .5, 1.0, .7, .4, .8, .55, .35, .65, .45];
    return Container(
      color: p.bg,
      child: LayoutBuilder(
        builder: (context, box) {
          final w = box.maxWidth;
          return Stack(
            children: [
              Positioned.fill(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.center,
                      colors: [
                        const Color(0xFFFF8A5B).withValues(alpha: .35),
                        p.bg,
                      ],
                    ),
                  ),
                ),
              ),
              Positioned(
                left: w * .14,
                top: w * .16,
                child: Container(
                  width: w * .72,
                  height: w * .72,
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(w * .1),
                    gradient: const LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [Color(0xFFFF8A5B), Color(0xFFC2185B)],
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: const Color(0xFFC2185B).withValues(alpha: .35),
                        blurRadius: 16,
                        offset: const Offset(0, 8),
                      ),
                    ],
                  ),
                ),
              ),
              Positioned(
                left: w * .14,
                right: w * .14,
                top: w * 1.05,
                child: SizedBox(
                  height: w * .16,
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: [
                      for (final h in heights)
                        Expanded(
                          child: Center(
                            child: Container(
                              width: w * .022,
                              height: w * .16 * h,
                              decoration: BoxDecoration(
                                color: h > .6 ? p.ink : p.line,
                                borderRadius: BorderRadius.circular(4),
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
              Positioned(
                left: 0,
                right: 0,
                top: w * 1.3,
                child: Center(
                  child: Container(
                    width: w * .2,
                    height: w * .2,
                    decoration: BoxDecoration(
                      color: p.ink,
                      shape: BoxShape.circle,
                    ),
                    child: Icon(AppIcons.play, size: w * .09, color: p.onInk),
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _MinimalPreview extends StatelessWidget {
  const _MinimalPreview();

  @override
  Widget build(BuildContext context) {
    final m = MiniPalette.of(context);
    return Container(
      color: m.bg,
      child: LayoutBuilder(
        builder: (context, box) {
          final w = box.maxWidth;
          final disc = w * .9;
          final wheel = w * .5;
          return Stack(
            children: [
              Positioned(
                left: w * .05,
                top: w * .08,
                child: SizedBox.square(
                  dimension: disc,
                  child: CustomPaint(
                    painter: DotRingPainter(
                      frac: .38,
                      on: m.ink,
                      off: m.line,
                      marker: m.ink,
                      dots: 48,
                      dotRadius: 2.4,
                      inset: 4,
                    ),
                    child: Center(
                      child: Container(
                        width: disc * .62,
                        height: disc * .62,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          border: Border.all(color: m.panel, width: 3),
                          gradient: const LinearGradient(
                            begin: Alignment.topLeft,
                            end: Alignment.bottomRight,
                            colors: [Color(0xFFFF8A5B), Color(0xFFC2185B)],
                          ),
                        ),
                        child: Center(
                          child: Container(
                            width: disc * .09,
                            height: disc * .09,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: m.bg,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
              Positioned(
                left: (w - wheel) / 2,
                top: w * 1.06,
                child: Container(
                  width: wheel,
                  height: wheel,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: m.tile,
                    boxShadow: m.soft,
                  ),
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      Container(
                        width: wheel * .34,
                        height: wheel * .34,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: m.bg,
                        ),
                      ),
                      for (var i = 0; i < 4; i++)
                        Align(
                          alignment: Alignment(
                            .72 * cos(i * pi / 2),
                            .72 * sin(i * pi / 2),
                          ),
                          child: Container(
                            width: 4,
                            height: 4,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: i == 3 ? m.accent : m.sub,
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}
