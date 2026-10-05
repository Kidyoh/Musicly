import 'package:flutter/material.dart';

/// Monochrome palette taken from the design: near-black ink on white,
/// with blue-grey secondary text and soft grey surfaces.
class Palette {
  const Palette({
    required this.bg,
    required this.card,
    required this.ink,
    required this.onInk,
    required this.sub,
    required this.line,
    required this.dock,
    required this.onDock,
  });

  final Color bg; // screen background
  final Color card; // soft grey buttons & chips
  final Color ink; // primary text, filled buttons
  final Color onInk; // content on ink
  final Color sub; // secondary text
  final Color line; // dividers, inactive waveform
  final Color dock; // mini player
  final Color onDock;

  static const light = Palette(
    bg: Color(0xFFFFFFFF),
    card: Color(0xFFF1F2F5),
    ink: Color(0xFF1C1D22),
    onInk: Color(0xFFFFFFFF),
    sub: Color(0xFF8A8FA3),
    line: Color(0xFFDCDEE5),
    dock: Color(0xFF1C1D22),
    onDock: Color(0xFFFFFFFF),
  );

  static const dark = Palette(
    bg: Color(0xFF0E0F12),
    card: Color(0xFF1C1D22),
    ink: Color(0xFFF4F5F7),
    onInk: Color(0xFF0E0F12),
    sub: Color(0xFF8A8FA3),
    line: Color(0xFF2C2E35),
    dock: Color(0xFF24252B),
    onDock: Color(0xFFFFFFFF),
  );

  static Palette of(BuildContext context) =>
      Theme.of(context).brightness == Brightness.dark ? dark : light;
}

ThemeData buildTheme(Brightness b) {
  final p = b == Brightness.dark ? Palette.dark : Palette.light;
  final scheme = ColorScheme(
    brightness: b,
    primary: p.ink,
    onPrimary: p.onInk,
    secondary: p.sub,
    onSecondary: p.onInk,
    error: const Color(0xFFD64545),
    onError: Colors.white,
    surface: p.bg,
    onSurface: p.ink,
    surfaceContainerHighest: p.card,
    surfaceContainerHigh: p.card,
    surfaceContainer: p.card,
    onSurfaceVariant: p.sub,
    outline: p.line,
    outlineVariant: p.line,
  );
  final base = ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    fontFamily: 'Inter',
  );
  return base.copyWith(
    scaffoldBackgroundColor: p.bg,
    splashFactory: InkSparkle.splashFactory,
    textTheme: base.textTheme.apply(bodyColor: p.ink, displayColor: p.ink),
    appBarTheme: AppBarTheme(
      backgroundColor: p.bg,
      surfaceTintColor: Colors.transparent,
      scrolledUnderElevation: 0,
      centerTitle: true,
      titleTextStyle: TextStyle(
        fontFamily: 'Inter',
        fontSize: 17,
        fontWeight: FontWeight.w600,
        color: p.ink,
      ),
      iconTheme: IconThemeData(color: p.ink),
    ),
    iconTheme: IconThemeData(color: p.ink),
    dividerTheme: DividerThemeData(color: p.line, thickness: 1, space: 1),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: p.bg,
      surfaceTintColor: Colors.transparent,
      showDragHandle: true,
      dragHandleColor: p.line,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: p.ink,
      contentTextStyle: TextStyle(fontFamily: 'Inter', color: p.onInk),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: p.card,
      selectedColor: p.ink,
      side: BorderSide.none,
      shape: const StadiumBorder(),
      labelStyle: TextStyle(
        fontFamily: 'Inter',
        color: p.ink,
        fontWeight: FontWeight.w500,
      ),
      secondaryLabelStyle: TextStyle(
        fontFamily: 'Inter',
        color: p.onInk,
        fontWeight: FontWeight.w600,
      ),
      showCheckmark: false,
    ),
    sliderTheme: SliderThemeData(
      activeTrackColor: p.ink,
      inactiveTrackColor: p.line,
      thumbColor: p.ink,
      overlayColor: p.ink.withValues(alpha: 0.08),
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: p.ink),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: p.card,
      hintStyle: TextStyle(color: p.sub),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide.none,
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
    ),
    pageTransitionsTheme: const PageTransitionsTheme(
      builders: {TargetPlatform.android: FadeForwardsPageTransitionsBuilder()},
    ),
  );
}
