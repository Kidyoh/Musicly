import 'dart:math';

import 'package:flutter/material.dart';

/// The "Minimal" player style: soft greys, one small orange accent, round
/// shapes, and surfaces that look gently raised. Light and dark versions.
class MiniPalette {
  const MiniPalette({
    required this.dark,
    required this.bg,
    required this.panel,
    required this.tile,
    required this.ink,
    required this.sub,
    required this.line,
    required this.accent,
    required this.hi,
    required this.lo,
  });

  final bool dark;
  final Color bg; // screen
  final Color panel; // the control panel
  final Color tile; // round buttons
  final Color ink; // text and the progress marker
  final Color sub; // quiet text
  final Color line; // inactive dots and hairlines
  final Color accent; // the one splash of colour
  final Color hi; // highlight of a raised surface
  final Color lo; // shade of a raised surface

  static const light = MiniPalette(
    dark: false,
    bg: Color(0xFFECEDEF),
    panel: Color(0xFFF7F7F8),
    tile: Color(0xFFFFFFFF),
    ink: Color(0xFF0F1013),
    sub: Color(0xFF9DA1AC),
    line: Color(0xFFD5D8DE),
    accent: Color(0xFFF26A1B),
    hi: Color(0xFFFFFFFF),
    lo: Color(0xFFD0D3DA),
  );

  static const darkTheme = MiniPalette(
    dark: true,
    bg: Color(0xFF111214),
    panel: Color(0xFF17181B),
    tile: Color(0xFF222327),
    ink: Color(0xFFF4F5F7),
    sub: Color(0xFF727681),
    line: Color(0xFF2E3036),
    accent: Color(0xFFFF7A2E),
    hi: Color(0xFF26272C),
    lo: Color(0xFF070708),
  );

  static MiniPalette of(BuildContext context) =>
      Theme.of(context).brightness == Brightness.dark ? darkTheme : light;

  /// A surface that looks pressed out of the background.
  List<BoxShadow> get raised => [
    BoxShadow(color: hi, offset: const Offset(-6, -6), blurRadius: 14),
    BoxShadow(color: lo, offset: const Offset(7, 7), blurRadius: 16),
  ];

  /// A gentle drop shadow for panels and round buttons.
  List<BoxShadow> get soft => [
    BoxShadow(
      color: Colors.black.withValues(alpha: dark ? 0.45 : 0.07),
      blurRadius: 22,
      offset: const Offset(0, 8),
    ),
  ];
}

/// A ring of dots that fills as the song plays, with a short tick at the
/// current position (the "playhead").
class DotRingPainter extends CustomPainter {
  const DotRingPainter({
    required this.frac,
    required this.on,
    required this.off,
    required this.marker,
    this.dots = 72,
    this.showMarker = true,
    this.dotRadius = 2.2,
    this.inset = 8,
  });

  final double frac;
  final Color on, off, marker;
  final int dots;
  final bool showMarker;
  final double dotRadius;
  final double inset;

  @override
  void paint(Canvas canvas, Size size) {
    final c = size.center(Offset.zero);
    final r = size.width / 2 - inset;
    final scale = size.width / 340;
    for (var i = 0; i < dots; i++) {
      final a = -pi / 2 + 2 * pi * i / dots;
      final lit = (i + .5) / dots <= frac;
      canvas.drawCircle(
        c + Offset(cos(a), sin(a)) * r,
        (lit ? dotRadius * 1.2 : dotRadius) * max(scale, .5),
        Paint()..color = lit ? on : off,
      );
    }
    if (showMarker) {
      final a = -pi / 2 + 2 * pi * frac;
      final d = Offset(cos(a), sin(a));
      canvas.drawLine(
        c + d * (r - 24 * scale),
        c + d * (r - 6 * scale),
        Paint()
          ..color = marker
          ..strokeWidth = 5 * max(scale, .5)
          ..strokeCap = StrokeCap.round,
      );
    }
  }

  @override
  bool shouldRepaint(DotRingPainter old) =>
      old.frac != frac ||
      old.on != on ||
      old.off != off ||
      old.marker != marker ||
      old.showMarker != showMarker;
}

/// A round button on the control panel, with a tiny dot that turns orange
/// when the feature is on.
class MiniTile extends StatelessWidget {
  const MiniTile({
    super.key,
    required this.palette,
    required this.onTap,
    required this.tooltip,
    this.icon,
    this.label,
    this.active = false,
    this.size = 52,
  });

  final MiniPalette palette;
  final VoidCallback onTap;
  final String tooltip;
  final IconData? icon;
  final String? label;
  final bool active;
  final double size;

  @override
  Widget build(BuildContext context) {
    final m = palette;
    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          width: size,
          height: size,
          decoration: BoxDecoration(
            color: m.tile,
            shape: BoxShape.circle,
            boxShadow: m.soft,
          ),
          child: Stack(
            alignment: Alignment.center,
            children: [
              if (icon != null)
                Icon(icon, size: size * .4, color: active ? m.ink : m.sub),
              if (label != null)
                Text(
                  label!,
                  style: TextStyle(
                    fontSize: size * .27,
                    fontWeight: FontWeight.w700,
                    color: active ? m.ink : m.sub,
                  ),
                ),
              Positioned(
                left: size * .2,
                top: size * .2,
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 250),
                  width: 6,
                  height: 6,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: active ? m.accent : m.line,
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
