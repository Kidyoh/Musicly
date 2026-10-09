import 'package:flutter/widgets.dart';

/// Every icon in the app comes from Phosphor (MIT, fonts bundled in
/// assets/fonts): regular for outlines, fill for active states.
abstract final class AppIcons {
  static const IconData bell = IconData(0xe0ce, fontFamily: 'PhosphorRegular');

  // Jam
  static const IconData jam = IconData(0xe68e, fontFamily: 'PhosphorRegular');
  static const IconData jamOn = IconData(0xe68e, fontFamily: 'PhosphorFill');
  static const IconData broadcast = IconData(
    0xe0f2,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData crown = IconData(0xe614, fontFamily: 'PhosphorFill');
  static const IconData upload = IconData(
    0xe4c0,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData leave = IconData(0xe42a, fontFamily: 'PhosphorRegular');
  static const IconData wifi = IconData(0xe4ea, fontFamily: 'PhosphorRegular');

  // Navigation
  static const IconData home = IconData(0xe2c2, fontFamily: 'PhosphorRegular');
  static const IconData homeOn = IconData(0xe2c2, fontFamily: 'PhosphorFill');
  static const IconData search = IconData(
    0xe30c,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData searchOn = IconData(0xe30c, fontFamily: 'PhosphorBold');
  static const IconData library = IconData(
    0xe758,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData libraryOn = IconData(
    0xe758,
    fontFamily: 'PhosphorFill',
  );
  static const IconData hot = IconData(0xe242, fontFamily: 'PhosphorRegular');
  static const IconData hotOn = IconData(0xe242, fontFamily: 'PhosphorFill');
  static const IconData back = IconData(0xe138, fontFamily: 'PhosphorRegular');
  static const IconData down = IconData(0xe136, fontFamily: 'PhosphorRegular');
  static const IconData chevron = IconData(
    0xe13a,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData close = IconData(0xe4f6, fontFamily: 'PhosphorRegular');
  static const IconData more = IconData(0xe1fe, fontFamily: 'PhosphorBold');

  // Playback
  static const IconData play = IconData(0xe3d0, fontFamily: 'PhosphorFill');
  static const IconData pause = IconData(0xe39e, fontFamily: 'PhosphorFill');
  static const IconData next = IconData(0xe5a6, fontFamily: 'PhosphorFill');
  static const IconData prev = IconData(0xe5a4, fontFamily: 'PhosphorFill');
  static const IconData shuffle = IconData(
    0xe422,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData repeat = IconData(
    0xe3f6,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData repeatOne = IconData(
    0xe3f8,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData playCircle = IconData(
    0xe3d2,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData queue = IconData(0xe6ac, fontFamily: 'PhosphorRegular');
  static const IconData addQueue = IconData(
    0xe2f8,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData playNext = IconData(
    0xe6aa,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData drag = IconData(0xeae2, fontFamily: 'PhosphorRegular');

  // Library
  static const IconData heart = IconData(0xe2a8, fontFamily: 'PhosphorRegular');
  static const IconData heartOn = IconData(0xe2a8, fontFamily: 'PhosphorFill');
  static const IconData add = IconData(0xe3d4, fontFamily: 'PhosphorRegular');
  static const IconData addPlaylist = IconData(
    0xeb7c,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData music = IconData(0xe340, fontFamily: 'PhosphorRegular');
  static const IconData disc = IconData(0xecac, fontFamily: 'PhosphorRegular');
  static const IconData artist = IconData(
    0xe326,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData follow = IconData(
    0xe4d0,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData following = IconData(
    0xeafa,
    fontFamily: 'PhosphorFill',
  );
  static const IconData history = IconData(
    0xe1a0,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData phone = IconData(0xe1e0, fontFamily: 'PhosphorRegular');
  static const IconData folder = IconData(
    0xe256,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData edit = IconData(0xe3b4, fontFamily: 'PhosphorRegular');
  static const IconData trash = IconData(0xe4a6, fontFamily: 'PhosphorRegular');
  static const IconData sort = IconData(0xe444, fontFamily: 'PhosphorRegular');
  static const IconData check = IconData(0xe182, fontFamily: 'PhosphorBold');
  static const IconData external = IconData(
    0xe5de,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData sparkle = IconData(0xe6a2, fontFamily: 'PhosphorFill');
  static const IconData trend = IconData(0xe4ae, fontFamily: 'PhosphorRegular');
  static const IconData radio = IconData(0xe77e, fontFamily: 'PhosphorRegular');

  // Tools
  static const IconData lyrics = IconData(
    0xe75c,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData lyricsOn = IconData(0xe75c, fontFamily: 'PhosphorFill');
  static const IconData findLyrics = IconData(
    0xebe0,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData sleep = IconData(0xe58e, fontFamily: 'PhosphorRegular');
  static const IconData sleepOn = IconData(0xe58e, fontFamily: 'PhosphorFill');
  static const IconData speed = IconData(0xe628, fontFamily: 'PhosphorRegular');
  static const IconData sound = IconData(0xe434, fontFamily: 'PhosphorRegular');
  static const IconData soundOn = IconData(0xe434, fontFamily: 'PhosphorFill');
  static const IconData eq = IconData(0xebbc, fontFamily: 'PhosphorRegular');
  static const IconData loud = IconData(0xe44a, fontFamily: 'PhosphorRegular');
  static const IconData fade = IconData(0xe802, fontFamily: 'PhosphorRegular');
  static const IconData minus = IconData(0xe32a, fontFamily: 'PhosphorBold');
  static const IconData plus = IconData(0xe3d4, fontFamily: 'PhosphorBold');
  static const IconData sun = IconData(0xe472, fontFamily: 'PhosphorRegular');
  static const IconData moon = IconData(0xe330, fontFamily: 'PhosphorRegular');
  static const IconData offline = IconData(
    0xe1b6,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData waveform = IconData(
    0xe802,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData telegram = IconData(
    0xe5bc,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData telegramOn = IconData(
    0xe5bc,
    fontFamily: 'PhosphorFill',
  );
  static const IconData connected = IconData(
    0xeb5a,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData refresh = IconData(
    0xe094,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData link = IconData(0xe2e2, fontFamily: 'PhosphorRegular');
  static const IconData eye = IconData(0xe220, fontFamily: 'PhosphorRegular');
  static const IconData eyeOff = IconData(
    0xe224,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData backup = IconData(
    0xe1ae,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData restore = IconData(
    0xe1ac,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData download = IconData(
    0xe20c,
    fontFamily: 'PhosphorRegular',
  );
  static const IconData saved = IconData(0xe184, fontFamily: 'PhosphorFill');
  static const IconData key = IconData(0xe2d6, fontFamily: 'PhosphorRegular');
  static const IconData headphones = IconData(
    0xe2a6,
    fontFamily: 'PhosphorRegular',
  );
}
