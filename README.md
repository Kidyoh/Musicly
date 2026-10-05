# Musicly

A monochrome, Material 3 music player for Android, iOS and web, built with Flutter.

## Music sources

- **Live radio** ([Radio Browser](https://www.radio-browser.info)): tens of thousands of real
  stations worldwide, full songs as they're broadcast, "near you" by phone region, genres and search.
- **Free full songs** ([Audius](https://audius.co)): full-length tracks from independent artists.
- **Your Telegram channel**: full songs posted in your own channel, read straight from Telegram with
  a bot token you paste in the app (no server). New posts appear automatically; "Import older songs"
  reads the channel's history; you can also forward songs to the bot. Bots can stream files up to 20 MB.
- **Deezer catalog**: real worldwide and genre charts, artists, albums and playlists with
  free 30-second previews (no account or API key). "Play full song on Deezer" opens the full track.
- **Your phone's music** (Android): one tap scans the phone's library and plays full songs with
  embedded album art. On iOS and web you can pick audio files instead.

## Features

- **Home**: "Your mix" built from the artists you play, like and follow; "Because you like…";
  top songs, popular artists, top albums and playlists
- **Artist pages**: popular songs, discography, related artists, artist radio, follow
- **Your playlists**: create, rename, delete, reorder (drag), add from anywhere, auto cover mosaic
- **Now Playing**: soft glow taken from the album art, waveform scrubber, swipe down to close
- **Synced lyrics** from [LRCLIB](https://lrclib.net): karaoke-style highlighting on full songs,
  tap a line to jump, ±0.5s timing, manual search (previews show the lyrics unsynced)
- **Sound**: 5-band equalizer with presets and loudness boost (Android), smooth fades between songs
- **Hotlist**: live charts for songs, albums and artists by genre
- **Home-screen widgets** (Android): 4×2 "Now playing" and 2×2 mini player with cover art and controls
- **Lock screen and notification** player with cover art for every source, seek bar and controls
- **Backup that survives reinstalls**: the library (likes, playlists, history, settings, channel songs)
  is saved as a pinned file in your private chat with your Telegram bot and restored when you reconnect
- Queue with drag to reorder, sleep timer with fade-out, playback speed, light and dark themes,
  background playback with lock-screen controls

## Run

```
flutter pub get
flutter run
flutter build apk --release --split-per-abi --obfuscate --split-debug-info=build/symbols
flutter build web --release --no-web-resources-cdn
```

Release builds are signed with the key in `android/key.properties` (not committed); without it
they fall back to the debug key.

Fonts: [Inter](https://rsms.me/inter/) (SIL OFL) and [Phosphor icons](https://phosphoricons.com) (MIT),
licenses in `assets/fonts/`.
