# Changelog

What changed in each version of the REV app. The app shows these notes when you tap the version at
the bottom of Settings.

## 0.8.0 — 2026-10-11

### New

- **Your morning, afternoon, evening and night mix.** REV learns which songs you play at each time
  of day, and the first mix on Home is the one for right now: mostly your songs for this time,
  with new songs that fit it mixed in. Songs you tend to skip at this time stay out. It appears
  once you've listened enough at that time of day, and works offline too, from your downloads.

## 0.7.1 — 2026-10-11

### Fixed

- The navigation bar follows the back button: going back from Library to Home highlights Home
  again, instead of staying on Library.

## 0.7.0 — 2026-10-10

### New

- **Listening modes:** tell REV what you're up to: Driving solo, Driving with family, At work, At
  home, Jam inspiration, or your own (like Gym). Tap the chip at the top of Home or the player.
- REV learns your taste in each mode separately: what you play and skip while driving with family
  isn't what you want at work. Family drives and work lean to songs you know; Jam inspiration
  leans to songs you haven't heard much.
- **REV recognises your modes by itself.** It learns where and when you pick each one (your car's
  Bluetooth, the time of day, Wi-Fi or mobile data, weekdays or weekends) and switches on its own
  next time. A mode you picked ends when you move somewhere else (like getting out of the car) or
  take a break.
- Switching mode reorders what's coming up, and "Play for this mode" starts a fitting mix.

## 0.6.0 — 2026-10-10

### New

- **Not now** and **More like this** buttons in the player, next to Lyrics, and on the
  notification and lock screen. Not now skips the song and tells REV it's not what you want right
  now. More like this lines up songs like the one playing, in any queue.
- **REV now has its own AI that learns your taste.** It predicts which songs you'd skip in the
  situation you're in (time, where you're listening, what you've been skipping), and the smart
  queue and autoplay use it to pick what plays next. The more you listen, the more say it gets.
- It learns on your phone from every song and every button press, even offline, and your server
  trains it on your whole history every few hours. Nothing leaves your own server.

## 0.5.0 — 2026-10-10

### New

- **Your listening is now recorded in detail**, to train REV's own recommendations. With every
  play and skip REV notes the situation: how the song got into the queue (you picked it, Play
  next, autoplay, the smart queue, the car), the queue and shuffle, the song before, how far into
  your session you were, and how you left it (next, back, jumped, new queue).
- It also notes where you were listening: speaker, headphones, Bluetooth (with the device's name,
  so the car and your earbuds are told apart), Android Auto or Cast; Wi-Fi or mobile data;
  charging; screen on; volume.
- And what you did while a song played: seeking ahead or back, pausing, liking or unliking it, and
  how well the smart queue expected it to go.
- It all stays on your own server. The server can export it as a spreadsheet-ready dataset.

### Changed

- Leaving a song by starting something new now counts as a skip, like pressing next does.

## 0.4.0 — 2026-10-10

### New

- **Smart queue** (replaces Follow my skips). REV now learns what you're in the mood for from how
  you skip, and from your listening history: the artists, genres and eras you love or skip, the
  songs you always skip, and what you usually play at this time of day. Works offline too.
- On shuffle or a playlist: three skips reshuffle what's next around what you've been enjoying.
  Skip one artist or genre twice and the rest of it moves back. Keep skipping (six) and you get
  something fresh, from what you liked today.
- Playing one artist: skip through one of their albums and their other albums come next; skip
  across their albums and similar artists take over.
- Playing an album in order: skipping to a song is just picking it. Skip four in a row and a
  different album is lined up.
- Stop on a song after skipping, and what's next becomes songs like it.
- When the queue changes, the player tells you why, and the queue shows it at the top.

### Changed

- Autoplay's similar songs are now picked to fit your session and taste, from a bigger pool.
- The setting is now Settings → Playback → Smart queue. This needs the updated REV server for
  your history; without it, it learns from this session only.

## 0.3.0 — 2026-10-10

### New

- **Follow my skips:** skip three songs in a row early on, then stay on one for 30 seconds, and the
  rest of your queue changes to songs like that one. The artists you skipped are left out. Works
  offline too, using your downloads.
- Turn it off in Settings → Playback. It leaves albums played in order and repeat alone.

## 0.2.6 — 2026-10-10

### Changed

- No changes in the app. REV is now open source under the GNU GPL v3.0.

## 0.2.5 — 2026-10-10

### Changed

- After an artist's top songs, the queue carries on with more of that artist's songs until you've
  heard them all, and only then moves on to similar artists in the same genre. This needs the
  updated REV server; with an older one, it moves on to similar songs as before.

## 0.2.4 — 2026-10-10

### Changed

- Tapping a mix now opens it like a playlist, with its songs and Play, Shuffle and Queue buttons.
  The play button on the mix card still plays it straight away.

## 0.2.3 — 2026-10-10

### New

- Patch notes: tap the app version at the bottom of Settings to see what changed in each version.

## 0.2.2 — 2026-10-10

### Fixed

- Settings now says "Server version" for the server's version, so it's no longer mistaken for the
  app's. The app's version is at the bottom of Settings.

## 0.2.1 — 2026-10-10

### Changed

- No changes in the app; this version only updated the developer notes.

## 0.2.0 — 2026-10-10

### New

- Year in review: a swipeable story of your year under Stats → Year, with your listening time,
  listening style, song of the year, top artists, albums and genres, when you listen and the new
  artists you found. It ends with your top songs, ready to play or save.
- Smart playlists built from your listening: On repeat, Forgotten favorites and Top songs of each
  year. Play or shuffle them, or save them to Navidrome; saving again updates the same playlist.
  These need the updated REV server.
- Equalizer under Settings → Playback, with your phone's presets, a slider per band and bass boost.
  It also works during crossfades, and equalizer apps such as Wavelet can attach to REV.
- The app's version is shown at the bottom of Settings.

### Fixed

- Play next now plays the song next when shuffle is on.
- You can reorder the queue while shuffle is on.
- Shuffled playback starts at the beginning of the shuffled order, so every song gets played.
- Turning shuffle on keeps the current song playing and shuffles the rest.
- Dragging songs in the queue and the playlist editor drops them where you let go, also at large
  font sizes, and dragging the same song twice works.

## 0.1.0 — 2026-10-03

### New

- The first version of REV: your Navidrome library with offline caching, gapless playback,
  crossfade and ReplayGain, Home mixes, autoplay, offline downloads, synced lyrics, album requests,
  listening stats, playlist editing, Android Auto, a home-screen widget, Chromecast and a Wear OS
  app.
