# Musicly

A monochrome, Material 3 music player for Android, iOS and web, built with Flutter.

## Music sources (all full-length)

- **Your Telegram channels**: songs posted in the channels you run, read straight from Telegram through
  your own bot (no server). Connect as many channels as you like; one bot reads them all. The app walks you through it: open BotFather, paste the token, tap
  "Add bot to my channel", and the channel shows up by itself. New posts appear automatically; "Import older songs"
  reads the channel's history; songs forwarded to the bot are added too. Bots can stream files up to
  20 MB. After each sync, songs are saved to **Music/Musicly** on the phone so they play offline and
  stay after a reinstall.
- **Your phone's music**: one tap scans the phone's library (Android); on iOS and web, pick files.
- **Live radio** ([Radio Browser](https://www.radio-browser.info)): real stations worldwide, near you,
  by genre or by name, with "now on air" titles.
- **YouTube** (Android and iOS): switch Search to YouTube to find songs, artists and playlists, with
  suggestions as you type. YouTube songs play like any other: in the queue, on the lock screen, with
  lyrics, likes and your playlists. Follow artists, save playlists, and "Start radio" on any song for
  an endless run of similar ones. Read straight from YouTube (no key, no server), so YouTube can
  occasionally pause playback with a bot check; it lifts after a few minutes.

## Features

- **Jam**: listen together.
  - *In the same room*: one phone hosts and plays; friends on the same Wi-Fi (or the host's hotspot)
    join from Musicly, see what's playing and add songs from the host's library or their own phone.
  - *Online, from anywhere*: friends enter the host's code and hear the host's songs on their own
    phones, in step. Messages go through the free [ntfy.sh](https://ntfy.sh) relay; songs are shared
    as temporary links on [Litterbox](https://litterbox.catbox.moe) that delete themselves within
    12 hours. No account or server of our own.
  - The host can let friends control playback and can remove people.
- **Your mix**: a fresh blend of your channel, phone and YouTube songs, weighted toward what you like
  and play; **Quick picks** from YouTube based on what you play, and new songs from artists you follow
- **Hotlist**: your most played songs, and the most popular radio stations
- **Artist pages** built from your own music plus their popular songs on YouTube; **your playlists**
  with drag to reorder
- **Now Playing** with a glow from the cover, waveform scrubber and **synced lyrics**
  ([LRCLIB](https://lrclib.net)) with tap-to-seek and timing adjustment
- **Home-screen widgets** (Android) and a lock-screen player with cover art for every source
- **Backups that survive reinstalls**: Download/Musicly/musicly-backup.json on the phone, plus a
  pinned file in your Telegram bot chat; restored from Library › Backup & restore
- Equalizer and loudness boost (Android), smooth fades, sleep timer, speed, queue, light and dark themes
- Playback recovers by itself if a song fails to load, and the next song is fetched ahead of time

## Run

```
flutter pub get
flutter run
flutter build apk --release --target-platform android-arm64,android-arm   # one APK for every phone
flutter build web --release --no-web-resources-cdn
```

Release builds are signed with the key in `android/key.properties` (not committed); without it
they fall back to the debug key.

Fonts: [Inter](https://rsms.me/inter/) (SIL OFL) and [Phosphor icons](https://phosphoricons.com) (MIT),
licenses in `assets/fonts/`.
