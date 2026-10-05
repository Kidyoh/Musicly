import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:just_audio_background/just_audio_background.dart';
import 'package:provider/provider.dart';

import 'services/deezer_api.dart';
import 'state/library_controller.dart';
import 'state/lyrics_controller.dart';
import 'state/player_controller.dart';
import 'ui/shell.dart';
import 'ui/theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  if (!kIsWeb) {
    await JustAudioBackground.init(
      androidNotificationChannelId: 'com.musicly.audio',
      androidNotificationChannelName: 'Musicly playback',
      androidNotificationOngoing: true,
      androidNotificationIcon: 'drawable/ic_stat_musicly',
    );
  }
  final api = DeezerApi();
  final library = LibraryController(api);
  final player = PlayerController(api)..onTrackStarted = library.recordPlay;
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
