import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/device_library.dart';
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
            tile(
              square(AppIcons.heartOn, dark: true),
              'Liked songs',
              '${lib.likedSongs.length} songs',
              () =>
                  openLive(context, 'Liked songs', 'You', (l) => l.likedSongs),
            ),
            tile(
              square(AppIcons.telegram),
              lib.channelConnected
                  ? (lib.channelName ?? 'Telegram')
                  : 'Your Telegram channel',
              lib.channelConnected
                  ? '${lib.channelTracks.length} songs • full length'
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
                '${lib.localTracks.length} songs • full length',
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
              '${lib.recent.length} songs',
              () =>
                  openLive(context, 'Recently played', 'You', (l) => l.recent),
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
                  '${pl.tracks.length} songs',
                  () => openUserPlaylist(context, pl),
                ),

            // Following
            if (lib.following.isNotEmpty) ...[
              const SectionHeader('Artists you follow'),
              HRow(
                height: 130,
                children: [
                  for (final a in lib.following)
                    ArtistBubble(
                      artist: a,
                      onTap: () => openArtist(context, a.id, a.name),
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
