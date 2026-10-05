import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:just_audio_background/just_audio_background.dart';
import 'package:provider/provider.dart';

import 'services/home_widgets.dart';
import 'state/library_controller.dart';
import 'state/lyrics_controller.dart';
import 'state/player_controller.dart';
import 'ui/shell.dart';
import 'ui/theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  if (!kIsWeb) {
    await JustAudioBackground.init(
      androidNotificationChannelId: 'com.musicly.player',
      androidNotificationChannelName: 'Now playing',
      androidNotificationChannelDescription:
          'Playback controls for the notification shade and lock screen',
      androidNotificationOngoing: true,
      androidNotificationIcon: 'drawable/ic_stat_musicly',
      notificationColor: const Color(0xFF1C1D22),
      preloadArtwork: true,
      artDownscaleWidth: 512,
      artDownscaleHeight: 512,
    );
  }
  final library = LibraryController();
  final player = PlayerController()
    ..onTrackStarted = library.recordPlay
    ..telegramUrl = ((fileId) => library.bot!.fileUrl(fileId))
    ..localCopy = library.downloadedCopy;
  library.onRestored = player.reloadSettings;
  // Pending library changes are backed up when the app leaves the screen.
  AppLifecycleListener(onHide: library.flushBackup);
  // Home-screen widgets follow whatever is playing.
  player.addListener(
    () => HomeWidgets.update(
      player.current,
      playing: player.isPlaying,
      onAir: player.nowOnAir,
    ),
  );
  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider.value(value: library),
        ChangeNotifierProvider.value(value: player),
        ChangeNotifierProvider(create: (_) => LyricsController(player)),
      ],
      child: const MusiclyApp(),
    ),
  );
}

class MusiclyApp extends StatelessWidget {
  const MusiclyApp({super.key});

  @override
  Widget build(BuildContext context) {
    final mode = context.select<LibraryController, ThemeMode>(
      (c) => c.themeMode,
    );
    return MaterialApp(
      title: 'Musicly',
      debugShowCheckedModeBanner: false,
      theme: buildTheme(Brightness.light),
      darkTheme: buildTheme(Brightness.dark),
      themeMode: mode,
      home: const Shell(),
    );
  }
}
