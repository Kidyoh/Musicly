# Musicly

A monochrome, Material 3 music player for Android, iOS and web, built with Flutter.

## Music sources

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
- Queue with drag to reorder, sleep timer with fade-out, playback speed, light and dark themes,
  background playback with lock-screen controls

## Run

```
flutter pub get
flutter run
flutter build apk --release --split-per-abi
flutter build web --release --no-web-resources-cdn
```

Fonts: [Inter](https://rsms.me/inter/) (SIL OFL) and [Phosphor icons](https://phosphoricons.com) (MIT),
licenses in `assets/fonts/`.
