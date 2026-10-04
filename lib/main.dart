import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:just_audio_background/just_audio_background.dart';
import 'package:provider/provider.dart';

import 'state/player_controller.dart';
import 'ui/home_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  if (!kIsWeb) {
    await JustAudioBackground.init(
      androidNotificationChannelId: 'com.musicly.audio',
      androidNotificationChannelName: 'Musicly playback',
      androidNotificationOngoing: true,
    );
  }
  runApp(ChangeNotifierProvider(
    create: (_) => PlayerController(),
    child: const MusiclyApp(),
  ));
}

class MusiclyApp extends StatefulWidget {
  const MusiclyApp({super.key});
  @override
  State<MusiclyApp> createState() => _MusiclyAppState();
}

class _MusiclyAppState extends State<MusiclyApp> {
  ThemeMode _mode = ThemeMode.system;

  ThemeData _theme(Brightness b) => ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
            seedColor: const Color(0xFF5B5BD6), brightness: b),
        appBarTheme: const AppBarTheme(scrolledUnderElevation: 0),
      );

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Musicly',
      debugShowCheckedModeBanner: false,
      theme: _theme(Brightness.light),
      darkTheme: _theme(Brightness.dark),
      themeMode: _mode,
      home: HomeScreen(
        onToggleTheme: () => setState(() {
          final dark = Theme.of(context).brightness == Brightness.dark;
          _mode = dark ? ThemeMode.light : ThemeMode.dark;
        }),
      ),
    );
  }
}
