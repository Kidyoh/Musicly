# Musicly

A monochrome, Material 3 music player for Android, iOS and web, built with Flutter.

## Features

- **Home**: greeting, "Jump back in", featured playlists and trending songs
- **Search**: songs, playlists and your own files, plus genre browsing
- **Library**: liked songs, songs on this device, recently played
- **Hotlist**: live charts by week, month or all time, filterable by genre
- **Album / playlist pages**: Play and Shuffle, find-in-list, sort, add all to queue, like all
- **Now Playing**: waveform scrubber, shuffle / repeat, swipe down to close
- **Synced lyrics** (from [LRCLIB](https://lrclib.net)): karaoke-style highlighting, tap a line
  to jump there, ±0.5s timing adjustment, and manual lyrics search. Your choices are remembered per song.
- **Queue**: drag to reorder, remove, play next
- **Sleep timer** with fade-out (or stop at the end of the song), **playback speed** 0.5x–2x
- Background playback with lock-screen controls (Android / iOS), light and dark themes
- Free streaming from [Audius](https://audius.co), no API keys needed

Name local files like `Artist - Title.mp3` and Musicly will find lyrics for them.

## Run

```
flutter pub get
flutter run                              # pick a device
flutter build apk --release --split-per-abi
flutter build web --release --no-web-resources-cdn
```

Font: [Inter](https://rsms.me/inter/) (SIL Open Font License, see `assets/fonts/OFL.txt`).
