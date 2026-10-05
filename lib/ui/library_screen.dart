import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/device_library.dart';
import '../services/home_widgets.dart';
import '../state/library_controller.dart';
import 'icons.dart';
import 'nav.dart';
import 'radio_screen.dart';
import 'telegram_screen.dart';
import 'routes.dart';
import 'sheets.dart';
import 'theme.dart';
import 'widgets.dart';

class LibraryScreen extends StatelessWidget {
  const LibraryScreen({super.key});

  Future<void> _newPlaylist(BuildContext context) async {
    final lib = context.read<LibraryController>();
    final name = await promptText(context, 'New playlist');
    if (name == null || !context.mounted) return;
    openUserPlaylist(context, lib.createPlaylist(name));
  }

  String _backupSummary(LibraryController lib) {
    final at = [lib.lastLocalBackup, lib.lastBackup]
        .whereType<DateTime>()
        .fold<DateTime?>(null, (a, b) => a == null || b.isAfter(a) ? b : a);
    if (at == null) return 'Saved to this phone and Telegram automatically';
    final m = DateTime.now().difference(at).inMinutes;
    return 'Last saved ${m < 1
        ? 'just now'
        : m < 60
        ? '$m min ago'
        : m < 1440
        ? '${m ~/ 60} h ago'
        : at.toLocal().toString().substring(0, 10)}';
  }

  Future<void> _restoreFile(BuildContext context) async {
    final err = await context.read<LibraryController>().restoreFromFile();
    if (context.mounted) toast(context, err ?? 'Library restored');
  }

  void _backupSheet(BuildContext context) {
    final lib = context.read<LibraryController>();
    final p = Palette.of(context);
    String when(DateTime? t) =>
        t == null ? 'Not yet' : t.toLocal().toString().substring(0, 16);
    showModalBottomSheet(
      context: context,
      useRootNavigator: true,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Backup & restore',
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    'Musicly saves your likes, playlists, history and settings automatically: to '
                    'Download › Musicly on this phone (kept even if you uninstall) and, once connected, '
                    'to your Telegram bot chat.',
                    style: TextStyle(color: p.sub, height: 1.45),
                  ),
                ],
              ),
            ),
            ListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 24),
              leading: const Icon(AppIcons.phone),
              title: const Text('On this phone'),
              subtitle: Text(
                DeviceLibrary.supported
                    ? 'Last saved: ${when(lib.lastLocalBackup)}'
                    : 'Android only',
              ),
            ),
            ListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 24),
              leading: const Icon(AppIcons.telegram),
              title: const Text('In Telegram'),
              subtitle: Text(
                lib.backupReady
                    ? 'Last saved: ${when(lib.lastBackup)}'
                    : 'Connect your channel and press Start in your bot',
              ),
            ),
            const Divider(),
            ListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 24),
              leading: const Icon(AppIcons.backup),
              title: const Text('Back up now'),
              onTap: () async {
                Navigator.pop(ctx);
                final ok = await lib.backupNow();
                if (context.mounted) {
                  toast(
                    context,
                    ok ? 'Library backed up' : 'Backup failed. Try again.',
                  );
                }
              },
            ),
            if (DeviceLibrary.supported)
              ListTile(
                contentPadding: const EdgeInsets.symmetric(horizontal: 24),
                leading: const Icon(AppIcons.folder),
                title: const Text('Restore from a backup file'),
                subtitle: const Text(
                  'Download › Musicly › musicly-backup.json',
                ),
                onTap: () {
                  Navigator.pop(ctx);
                  _restoreFile(context);
                },
              ),
            if (lib.backupReady)
              ListTile(
                contentPadding: const EdgeInsets.symmetric(horizontal: 24),
                leading: const Icon(AppIcons.restore),
                title: const Text('Restore from Telegram'),
                onTap: () async {
                  Navigator.pop(ctx);
                  final ok = await lib.restoreFromTelegram();
                  if (context.mounted) {
                    toast(
                      context,
                      ok ? 'Library restored' : 'No backup found yet.',
                    );
                  }
                },
              ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  Future<void> _widgets(BuildContext context) async {
    final canPin = await HomeWidgets.canPin();
    if (!context.mounted) return;
    final p = Palette.of(context);
    showModalBottomSheet(
      context: context,
      useRootNavigator: true,
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Home-screen widgets',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 6),
              Text(
                canPin
                    ? 'Pick one to place on your home screen. They show the cover and control playback without opening the app.'
                    : 'Long-press an empty spot on your home screen, tap Widgets, and find Musicly.',
                style: TextStyle(color: p.sub, height: 1.45),
              ),
              if (canPin) ...[
                const SizedBox(height: 12),
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(AppIcons.music),
                  title: const Text(
                    'Now playing',
                    style: TextStyle(fontWeight: FontWeight.w600),
                  ),
                  subtitle: const Text('4×2 · cover, song and controls'),
                  onTap: () {
                    Navigator.pop(ctx);
                    HomeWidgets.pin();
                  },
                ),
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(AppIcons.disc),
                  title: const Text(
                    'Mini player',
                    style: TextStyle(fontWeight: FontWeight.w600),
                  ),
                  subtitle: const Text('2×2 · cover with a play button'),
                  onTap: () {
                    Navigator.pop(ctx);
                    HomeWidgets.pin(mini: true);
                  },
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _scan(BuildContext context) async {
    final lib = context.read<LibraryController>();
    final ok = await lib.scanDevice();
    if (!context.mounted) return;
    toast(
      context,
      ok
          ? 'Found ${lib.deviceTracks.length} songs on this phone'
          : (lib.scanError ?? 'Scan failed'),
    );
  }

  Future<void> _pick(BuildContext context) async {
    final n = await context.read<LibraryController>().pickFiles();
    if (context.mounted && n > 0) {
      toast(context, 'Added $n song${n == 1 ? '' : 's'}');
    }
  }

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryController>();
    final p = Palette.of(context);

    Widget tile(Widget leading, String title, String sub, VoidCallback onTap) =>
        InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
            child: Row(
              children: [
                leading,
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontWeight: FontWeight.w600,
                          fontSize: 16,
                        ),
                      ),
                      const SizedBox(height: 3),
                      Text(sub, style: TextStyle(color: p.sub, fontSize: 13)),
                    ],
                  ),
                ),
                Icon(AppIcons.chevron, color: p.sub, size: 18),
              ],
            ),
          ),
        );

    Widget square(IconData icon, {bool dark = false}) => Container(
      width: 56,
      height: 56,
      decoration: BoxDecoration(
        color: dark ? p.ink : p.card,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Icon(icon, color: dark ? p.onInk : p.ink),
    );

    final phone = DeviceLibrary.supported;

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: ListView(
          padding: const EdgeInsets.only(bottom: 24),
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 12, 8),
              child: Row(
                children: [
                  const Expanded(
                    child: Text(
                      'Library',
                      style: TextStyle(
                        fontSize: 30,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.8,
                      ),
                    ),
                  ),
                  IconButton.filled(
                    tooltip: 'New playlist',
                    style: IconButton.styleFrom(
                      backgroundColor: p.ink,
                      foregroundColor: p.onInk,
                    ),
                    onPressed: () => _newPlaylist(context),
                    icon: const Icon(AppIcons.add, size: 20),
                  ),
                ],
              ),
            ),
            if (lib.showRestoreCard && DeviceLibrary.supported)
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 4, 20, 12),
                child: Container(
                  padding: const EdgeInsets.all(18),
                  decoration: BoxDecoration(
                    color: p.card,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Icon(AppIcons.restore),
                          const SizedBox(width: 10),
                          const Expanded(
                            child: Text(
                              'Reinstalled? Restore your library',
                              style: TextStyle(
                                fontWeight: FontWeight.w700,
                                fontSize: 16,
                              ),
                            ),
                          ),
                          IconButton(
                            icon: Icon(AppIcons.close, size: 18, color: p.sub),
                            onPressed: lib.dismissRestore,
                          ),
                        ],
                      ),
                      Text(
                        'Pick musicly-backup.json from Download › Musicly to bring back your likes, playlists and history.',
                        style: TextStyle(color: p.sub, height: 1.45),
                      ),
                      const SizedBox(height: 12),
                      PillButton(
                        icon: AppIcons.folder,
                        label: 'Choose backup file',
                        onPressed: () => _restoreFile(context),
                      ),
                    ],
                  ),
                ),
              ),
            tile(
              square(AppIcons.heartOn, dark: true),
              'Liked songs',
              count(lib.likedSongs.length, 'song'),
              () =>
                  openLive(context, 'Liked songs', 'You', (l) => l.likedSongs),
            ),
            tile(
              square(AppIcons.telegram),
              lib.channelConnected
                  ? (lib.channelName ?? 'Telegram')
                  : 'Your Telegram channel',
              lib.channelConnected
                  ? '${count(lib.channelTracks.length, 'song')} • full length'
                  : 'Stream songs from your own channel',
              () => openPage(context, const TelegramScreen()),
            ),
            tile(
              square(AppIcons.radio),
              'Live radio',
              lib.savedStations.isEmpty
                  ? 'Stations from around the world'
                  : '${lib.savedStations.length} saved stations',
              () => openPage(context, const RadioScreen()),
            ),
            if (lib.localTracks.isNotEmpty)
              tile(
                square(AppIcons.phone),
                'On this phone',
                '${count(lib.localTracks.length, 'song')} • full length',
                () => openLive(
                  context,
                  'On this phone',
                  'Your music',
                  (l) => l.localTracks,
                  removableFiles: true,
                ),
              ),
            tile(
              square(AppIcons.history),
              'Recently played',
              count(lib.recent.length, 'song'),
              () =>
                  openLive(context, 'Recently played', 'You', (l) => l.recent),
            ),
            tile(
              square(AppIcons.backup),
              'Backup & restore',
              _backupSummary(lib),
              () => _backupSheet(context),
            ),
            if (HomeWidgets.supported)
              tile(
                square(AppIcons.sparkle),
                'Home-screen widgets',
                'Now playing and mini player',
                () => _widgets(context),
              ),

            // Playlists
            SectionHeader(
              'Your playlists',
              action: 'New',
              onAction: () => _newPlaylist(context),
            ),
            if (lib.playlists.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Text(
                  'Make your first playlist with the + button, or use ⋯ on any song.',
                  style: TextStyle(color: p.sub, height: 1.5),
                ),
              )
            else
              for (final pl in lib.playlists)
                tile(
                  Mosaic(pl.tracks, size: 56),
                  pl.name,
                  count(pl.tracks.length, 'song'),
                  () => openUserPlaylist(context, pl),
                ),

            // Artists from your own music
            if (lib.artists.isNotEmpty) ...[
              const SectionHeader('Your artists'),
              HRow(
                height: 148,
                children: [
                  for (final (name, songs) in lib.artists.take(20))
                    ArtistBubble(
                      name: name,
                      cover: songs.firstWhere(
                        (t) => t.artworkUrl != null || t.mediaId != null,
                        orElse: () => songs.first,
                      ),
                      subtitle: count(songs.length, 'song'),
                      onTap: () => openArtist(context, name),
                    ),
                ],
              ),
            ],

            // Phone music
            if (phone && lib.deviceTracks.isNotEmpty) ...[
              SectionHeader(
                'Albums on this phone',
                action: lib.scanning ? null : 'Rescan',
                onAction: () => _scan(context),
              ),
              HRow(
                height: 206,
                children: [
                  for (final (album, artist, tracks) in lib.deviceAlbums)
                    CoverCard(
                      title: album,
                      subtitle: artist,
                      art: Artwork(tracks.first, size: 148, radius: 16),
                      onTap: () => openLive(
                        context,
                        album,
                        artist,
                        (l) {
                          for (final a in l.deviceAlbums) {
                            if (a.$1 == album && a.$2 == artist) return a.$3;
                          }
                          return const [];
                        },
                        kind: 'Album',
                        cover: tracks.first,
                      ),
                    ),
                ],
              ),
            ],
            if (lib.deviceTracks.isEmpty)
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 28, 20, 0),
                child: Container(
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    color: p.card,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Icon(AppIcons.headphones),
                          const SizedBox(width: 10),
                          Text(
                            phone
                                ? 'Play full songs from your phone'
                                : 'Bring your own music',
                            style: const TextStyle(
                              fontWeight: FontWeight.w700,
                              fontSize: 17,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Text(
                        phone
                            ? 'Musicly can find the songs already on this phone and play them in full, '
                                  'with album art and synced lyrics.'
                            : 'Add MP3, M4A, FLAC or WAV files. Name them like "Artist - Title.mp3" '
                                  'and Musicly will find synced lyrics too.',
                        style: TextStyle(color: p.sub, height: 1.5),
                      ),
                      const SizedBox(height: 14),
                      Row(
                        children: [
                          if (phone)
                            Expanded(
                              child: PillButton(
                                icon: AppIcons.phone,
                                label: lib.scanning
                                    ? 'Scanning…'
                                    : 'Find my music',
                                onPressed: lib.scanning
                                    ? null
                                    : () => _scan(context),
                              ),
                            ),
                          if (phone) const SizedBox(width: 12),
                          Expanded(
                            child: PillButton(
                              icon: AppIcons.folder,
                              label: 'Pick files',
                              filled: !phone,
                              onPressed: () => _pick(context),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
