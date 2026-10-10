# Android

Project: `android/REV` (Gradle multi-module, Kotlin, Jetpack Compose, Hilt, Room, Media3).

| Setting                    | Value                                                          |
| -------------------------- | -------------------------------------------------------------- |
| `applicationId`            | `app.rev`                                                      |
| `minSdk`                   | 29 (Android 10)                                                |
| `compileSdk` / `targetSdk` | 37                                                             |
| JVM target                 | 17                                                             |
| Build                      | AGP 9 (built-in Kotlin), Gradle 9.8 wrapper, KSP for Hilt/Room |

Dependency versions are in `gradle/libs.versions.toml`.

The Wear OS app is the `:wear` module (same `applicationId`, `minSdk` 30); `:wearprotocol` is a
plain Kotlin module holding the phone ↔ watch protocol both apps share.

## Toolchain

You need a JDK (17+) and the Android SDK with `platforms;android-37.0`. Build-tools are installed
automatically by the Android Gradle plugin once SDK licenses are accepted.

Point Gradle at the SDK with `android/REV/local.properties` (not committed):

```properties
sdk.dir=/path/to/android/sdk
```

and set `JAVA_HOME` if `java` is not on your `PATH`.

## Commands

```sh
./gradlew assembleDebug     # build APK → app/build/outputs/apk/debug/
./gradlew test              # JVM unit tests
./gradlew installDebug      # install on a connected device
```

## Versioning

The phone and watch apps share one version, `rev.version` in `android/REV/gradle.properties`. The
version code is derived from it (`1.2.3` → `10203`), so Android always sees a newer build as an
update. Every commit bumps the version: `0.0.1` for fixes and small changes, `0.1.0` for a big
feature, and `1.0.0` once the app feels finished. The version is shown at the bottom of Settings.

## Server connection and security

| Concern          | Implementation                                                                                                                                                                                                                                                                                                                                |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Server address   | `ServerUrl` (domain) normalizes input (`100.x.y.z:4600` → `http://100.x.y.z:4600`). Stored with the server's instance id, and an optional home-network address, in DataStore (`ServerConfigStore`). See "Home network and remote" below.                                                                                                      |
| Credentials      | Only REV session tokens are stored — never the password, never backend API keys. They are encrypted with an AES-256-GCM key in the Android Keystore (`KeystoreSessionStore`). If the key is lost the app simply starts signed out.                                                                                                            |
| HTTP             | `RevHttpClient` (Ktor + OkHttp). The access token is attached **only** to requests for the configured server's addresses (not to login/refresh/health); redirects are never followed. On a 401 it refreshes once (single-flight) and retries; a rejected refresh clears the session. Probing a new address uses a client with no credentials. |
| Connection state | `ConnectionMonitor` combines the device network state with a `/api/system/health` check (on change, on demand, and every 30 s while observed) → `Online / Checking / NoNetwork / ServerUnreachable / WrongServer`.                                                                                                                            |
| Cleartext        | Allowed by `network_security_config.xml` for Tailscale/LAN addresses; the UI warns when plain HTTP would go to a public host.                                                                                                                                                                                                                 |
| Backups          | Disabled (`allowBackup=false`, `data_extraction_rules.xml`); tokens are device-bound anyway.                                                                                                                                                                                                                                                  |

## Home network and remote

A server has a **remote address** that works anywhere (e.g. `https://rev.example.com` through
Cloudflare Tunnel, or a Tailscale IP) and, optionally, a **home-network address**
(`http://192.168.x.x:4600`), entered at setup or in Settings → Server → Home network. At home REV
talks to the server directly, so streaming and downloads skip the tunnel; elsewhere it uses the
remote address. There is no manual switch.

`RevConnectionManager` (`data/connection`) is the single source of the base URL: the API clients,
the stream resolver, offline downloads, artwork, cast-token requests and the WebSocket all ask it
via `activeUrl()`; nothing else reads the configuration.

| Rule               | Behaviour                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Choosing Local     | The home address must answer `/api/system/health` (2 s connect / 3 s total) **with the configured instance id**; another device at that IP is never used.                                                                                                                                                                                                                                                                                  |
| When it is checked | At start, on every network change (Wi-Fi ↔ cellular), every 30 s while in use, every 60 s (5 min in background) while on remote, and early after a failed request to it.                                                                                                                                                                                                                                                                   |
| Leaving Local      | Two failed checks in a row (3 s apart). A single failure, or a failed API request, doesn't switch anything.                                                                                                                                                                                                                                                                                                                                |
| No flapping        | After leaving Local, it isn't used again on the same network for 60 s. A new network is fresh evidence and is checked at once.                                                                                                                                                                                                                                                                                                             |
| Remote failures    | The remote address is the fallback and is never abandoned; ordinary request retries and the connection badge handle outages.                                                                                                                                                                                                                                                                                                               |
| In-flight work     | A switch never interrupts anything: a playing stream keeps its connection (the next track or a seek uses the new address); downloads resume with `Range` on whichever is active.                                                                                                                                                                                                                                                           |
| WebSocket          | Reconnects to the new address when the route changes.                                                                                                                                                                                                                                                                                                                                                                                      |
| Artwork cache      | Coil's disk-cache key is the remote address + path whichever address fetched it, so cached covers work on both and offline.                                                                                                                                                                                                                                                                                                                |
| Address changes    | When the home address doesn't answer (or none is set), the app asks the server for its current LAN address (`GET /api/system/addresses`, over the remote address) at most every 10 minutes and adopts one that answers with the same instance id: a new DHCP lease is followed automatically, and a fresh setup finds the home address by itself. Removing the address in Settings turns this off ("Find automatically" turns it back on). |
| Restart            | Nothing is remembered: each start begins on remote and re-discovers the home address within a few seconds. Background downloads wait up to 4 s for that first decision.                                                                                                                                                                                                                                                                    |
| Tokens             | The same session works on both addresses. Tokens go only to those two exact origins (scheme, host, port); the home address must be private-network (or HTTPS).                                                                                                                                                                                                                                                                             |

Settings shows the route subtly in the status badge (**Home network** / **Remote**) when a home
address is set.

## Library cache

| Table                                   | Contents                                                                                                   |
| --------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `artists`, `albums`, `tracks`, `genres` | Metadata as last received from REV (normalized model, no file paths)                                       |
| `list_entries`                          | Ordered list membership: `artists`, `albums:alphabetical`, `albums:newest`, `albums:recent`, `artist:<id>` |

- `LibraryRepositoryImpl` exposes `observe*` (Room flows) and `refresh*` (fetch → write) pairs.
- Album detail refresh replaces that album's tracks, dropping ones the server no longer lists.
- Favorites refresh makes the cached starred flags match the server exactly. Toggling a favorite
  updates the cache immediately and reverts if the server refuses.
- The cache is cleared when the app is pointed at a different REV installation.
- The whole-library track list and genre album lists are paged from the server, not cached.
- `playback_queue` (v3) holds the single persisted queue as JSON plus index, position and modes.
- `download_jobs` (v4) caches server download jobs for the Downloads screen.
- v5 adds `playlists`, the offline collection tables and progress columns on `offline_tracks`.

Artwork: UI code passes a `CoverArt(id, size)` to Coil; `CoverArtMapper` (app module) turns it into
`<server>/api/library/artwork/<id>?size=…`, fetched with the authenticated Ktor client and kept in
Coil's disk cache.

## Playback

| Piece                                    | Role                                                                                                                                                                                                                                                                                                                      |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PlaybackService` (`:playback`)          | `MediaSessionService` owning ExoPlayer: audio focus, becoming-noisy pause, wake lock, media notification, lock-screen/Bluetooth controls, system playback resumption                                                                                                                                                      |
| `revDataSourceFactory`                   | Resolves `rev:track/<id>` per load via `TrackSourceResolver` (local file → server stream → unavailable)                                                                                                                                                                                                                   |
| `authenticatedMediaHttpClient` (`:data`) | OkHttp for streams: session token only for the configured server, one refresh-and-retry on 401                                                                                                                                                                                                                            |
| `CoilBitmapLoader`                       | Notification/lock-screen artwork through the authenticated, disk-cached image loader                                                                                                                                                                                                                                      |
| `QueuePersister`                         | Saves queue, position, shuffle and repeat to Room (debounced on change, every 15 s while playing, on destroy); restores into an idle player at service start                                                                                                                                                              |
| `PlayReportTracker`                      | "Now playing" on track start; a play once listened to for half the track or 4 min (`PlayCountPolicy`); a skip when moving on before that. Follows the session player, so casting counts                                                                                                                                   |
| `ListenQueue` (`:data`)                  | Plays and skips wait in Room (`pending_listens`) until `POST /api/stats/listens` succeeds; flushed after each listen, on "now playing" and whenever the server comes back online                                                                                                                                          |
| Playlists                                | Playlists tab → New playlist; "Add to playlist" in every song menu, on album/artist pages, in the player, and "Save" on the queue (`AddToPlaylistHost`). Own playlists have Edit (rename, description, drag to reorder, remove, delete — saved together); others' are read-only. Edits keep a downloaded playlist in step |
| `:feature:stats`                         | "Your listening" (chart icon on Home): week / month / year / all-time reports from the server, stepping back through earlier periods; the current ones are saved for offline viewing                                                                                                                                      |
| `PlaybackController`                     | App-side `MediaController` wrapper: `PlayerState` / `PlaybackProgress` flows (progress every 250 ms only while playing), transport and queue commands; implements the domain `PlaybackQueue`                                                                                                                              |
| `:feature:player`                        | Mini player above the bottom bar, full player, queue (tap to jump, swipe to remove, drag to reorder)                                                                                                                                                                                                                      |

Unplayable tracks (e.g. not downloaded while offline) are skipped automatically; playback stops only
when every remaining track has failed. Signing out or switching server clears the queue.

Shuffle uses `QueueShuffleOrder`: a queue filled from empty is shuffled and starts at its first
song (so every song plays); turning shuffle on mid-queue keeps the current song and shuffles the
rest. Songs inserted or moved play right after the song now before them in the queue, so "Play
next" plays next, "Add to queue" plays after everything else (`endOfPlayOrder`), and dragging in the
queue works shuffled or not: the queue shows play order and `queueMove` turns a drag into the queue
move that produces it. Drop targets are measured from the laid-out rows (`dropTarget`), so rows
that grow with large fonts land where they're dropped.

## Search

`:feature:search` searches the **library only**: a search field (debounced 350 ms; clearing
returns to idle at once) over `SearchRepository`. Filter chips switch between All (the first few
songs, artists and albums, each with **See all**), Songs, Albums and Artists; a kind with no results
for the query falls back to All. Results refresh when returning to the search screen. When the
server can't be reached, `searchOnDevice` searches what's on the phone instead (cached artists and
albums, downloaded and liked songs) with `LocalSearch` (`:domain`: case- and accent-insensitive,
every word must match, names starting with the query first), and a notice says so. Home has a
search pill that opens the Search tab.

The wider catalog (Lidarr/MusicBrainz) is no longer searched. The catalog album page
(`CatalogAlbumRoute`) remains for albums requested earlier and opens from their rows in Downloads.

Library has its own **filter** field above its tabs. It narrows the cached Artists, Albums,
Playlists, Genres and Favorites lists on the device (`LocalSearch.filter`); on Tracks, which are
paged from the server, it runs a search (on the device when offline).

## Downloads and live events

- `ServerEventsClient` (`:data`) holds a WebSocket to `/api/events` while the user is signed in and
  the app is in the foreground (`ProcessLifecycleOwner`), with 1 s → 30 s backoff. It uses the same
  authenticated OkHttp client as streaming, so the token only goes to the configured server.
- `ServerEventSync` applies events: on (re)connect it reloads the job list; job events update the
  `download_jobs` Room cache (never letting an older update overwrite a newer one); `library.updated`
  refreshes "Recently added".
- `:feature:downloads`: album requests still in progress and finished ones (Cancel, Retry, Open),
  below the music on the device (see "Offline downloads"). Works offline from the cache.
- The catalog album page follows its job from the cache and only polls (every 30 s) while the live
  connection is down.
- Home shows "N albums downloading" while anything is in progress.
- Settings → System status (`SystemStatusRoute`): service health, Soularr's last activity, library
  size, active downloads.

## Offline downloads (music on the device)

Distinct from server-side album _requests_: this saves music the user already has to the phone.

| Piece                       | Role                                                                                                                                                                         |
| --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `offline_collections`       | What the user chose to keep: an album (`album:<id>`), playlist (`playlist:<id>`) or single track (`track:<id>`)                                                              |
| `offline_collection_tracks` | Which tracks each collection needs                                                                                                                                           |
| `offline_tracks` (§23)      | One row per track: state (`Queued`, `Downloading`, `Downloaded`, `Failed`), bytes, file path and the metadata needed to show and play it with no server and no library cache |
| `TrackDownloadWorker`       | WorkManager worker (Hilt) downloading one track from `/api/library/tracks/:id/stream` (original file) with the authenticated OkHttp client                                   |
| `OfflineDownloadScheduler`  | One unique work per track (`offline-track:<id>`, `KEEP`), so a track is never downloaded twice; Wi-Fi-only → `UNMETERED` constraint; exponential backoff                     |
| `OfflineFiles`              | `filesDir/offline`: app-private, no permissions, removed with the app. `<id>.part` while downloading, `<id>.<ext>` when done                                                 |
| `OfflineRepositoryImpl`     | Creates collections, queues missing tracks, retries, removes (deleting a file only when no other collection needs it), storage usage                                         |
| `CoilArtworkPrefetcher`     | Caches covers of downloaded music at every size the app shows, so artwork appears offline                                                                                    |

Behaviour:

- **Resume:** an interrupted download keeps its `.part` file and continues with an HTTP `Range`
  request; a mismatched partial file (`416`) is discarded and restarted.
- **Storage:** a download is refused if it would leave less than 500 MB free; the job also requires
  storage not to be low.
- **Wi-Fi only** (Settings, on by default): changing it re-queues pending downloads with the new
  constraint.
- **Playback:** the player resolves each track when it starts loading; a fully downloaded copy
  always wins (`state = 'Downloaded'` and the file exists), so downloaded albums and queues play with
  no connection, and missing tracks are skipped.
- **Server change / sign-out:** downloads are kept; they are the user's music and need no server.
- UI: album and playlist pages show **Download → progress → ✓ Downloaded** (remove asks to confirm);
  tracks show a ✓ when on the device; the track sheet can download one track; Settings shows space
  used/free, the Wi-Fi-only switch and "Remove all downloads".
- **Downloads tab:** a summary ("N songs on this device", space used and free) with **Play** and
  **Shuffle** for everything downloaded, **Offline mixes**, then the downloads by _Albums &
  playlists_ (progress, ⋮ → Retry/Remove download), _Songs_ (tap to play; ⋮ → Play next, Add to
  queue, Go to album/artist) or _Artists_ (opens the artist's downloaded and liked songs). It reads
  only the device, so it all works with no connection.

### Offline mixes

`OfflineMixes` (`:domain`, pure, unit-tested) builds mixes from downloaded songs
(`OfflineTrackDao.observeDownloaded`, with genres borrowed from the library cache): **All
downloads**, **Liked Songs** (cached favorites that are downloaded), radios for the three most
downloaded artists (their songs every third track; in between, artists sharing their genres) and
mixes for the two biggest genres. A mix needs at least six songs. Songs by one artist are spread
at least three apart where possible. Offline mixes have ids starting `offline:` and show an
_Offline_ badge.

They appear on the Downloads tab and on Home, where they come first while the server can't be
reached. Tapping a server mix on Home with no server rebuilds the same kind of mix from downloads
(artist radio around that artist and the artists it features, genre, Liked Songs, Discovery) and
says so; only when nothing downloaded fits is the error shown.

### Smart downloads

Settings → Smart downloads keeps music on the phone automatically: **Favorites** (starred tracks,
as one `favorites` collection) and the **N most recently played albums** (0/5/10/20), within a
**size limit** (2/5/10/20 GB). `SmartDownloadWorker` runs every 12 hours (Wi-Fi rules apply) and
when the settings change; `SmartDownloadPlanner` (pure, unit-tested) picks what fits the limit, in
priority order. Automatic collections are marked `automatic = 1`: albums that drop out of the
selection are removed, but anything the user downloaded by hand is never touched or downgraded.

## Artist page

The artist's top five songs (the server's ranking, see api.md → Artist top tracks), marked ♥ when
liked and ✓ when downloaded, with Play, Shuffle and Add to queue. Below them, **Load more** opens a
screen with every song by the artist you've liked or downloaded — liked first, then downloads —
which also works offline. Then the artist's releases in horizontal rows by kind — Albums, EPs,
Singles, Live, Compilations — newest first, from the release-type tags (Room v9 stores them with the
library cache).

## Home

Home shows the server's recommendations (see api.md → Home and mixes), cached in
`filesDir/home_feed.json` so it opens instantly and works offline: a greeting over a colour wash
from what you've been playing, six quick-access album tiles, **Made for you** mixes (tap to play;
artist radios, genre mixes, Liked Songs, Discovery), rows such as _On repeat_, _Because you listen
to …_ (links to the artist), _More …_ (links to the genre), _Rediscover_ and _Not heard yet_, and
your genres as chips, then **From your downloads** (offline mixes; first when offline). Pull to
refresh rebuilds the recommendations. Tapping the tab you're already on returns to that tab's start.

## Playlists

Library → Playlists lists Navidrome playlists; a playlist page offers Play, Shuffle and Download.
Playlist tracks are cached like album tracks, so a downloaded playlist opens offline. Creating and
editing playlists is not implemented yet.

## Lyrics

`:feature:lyrics` (Player → Lyrics): synced lyrics follow playback and can be tapped to seek; see
[lyrics.md](lyrics.md). Room v6 adds the `lyrics` cache table.

## Look and feel

- **Theme:** dark only, smoky by default; Settings → Appearance → _AMOLED black_ switches to pure
  black (`ThemeMode.Amoled`, stored with `AppearanceStore`).
- **Artwork colour:** `rememberArtworkAccent(coverArtId)` extracts a cover's dominant hue with
  Palette, then caps saturation and lightness so it tints rather than paints (no neon). It is used
  for soft top-down gradients (`Modifier.artworkGradient`) on the player, album header and lyrics,
  and to tint the mini player. Cached per cover; animates between tracks.
- **Glass:** floating surfaces (mini player, navigation bar) use `Glass.container()` — translucent,
  lightly tinted, with a hairline highlight; no blur or gloss.
- **Motion:** 200 ms fades between screens; switching tabs slides toward the new tab's side; the
  full player slides up; the mini player slides in and out with playback.
- **Offline banner:** above the mini player, "Music server unavailable. Downloaded music is still
  available offline." (or the no-network variant), announced politely to screen readers.
- **Large screens:** at ≥ 600 dp wide a navigation rail replaces the bottom bar; the player puts
  artwork beside the controls in landscape. Album and artist grids are adaptive already.
- **Accessibility:** controls have content descriptions and click labels (play track, go to
  artist/album, jump to lyric line), lyric lines are buttons, headings are marked, queue rows grow
  with the font size, the offline banner is a live region.

## Navigation

Bottom navigation: Home, Library, Search, Downloads, Settings (§35), as a floating glass pill above
the mini player (which has matching rounded corners). Tabs are icons; the current one is an inverted
capsule with its name. On wide screens a navigation rail is used instead.

**Swipe gestures** (`Modifier.swipeGestures` in `:core`; a drag past 72 dp or a fling, claiming only
the directions it handles so lists, rows, sliders and pagers keep their own drags):

| Where                                                                                          | Gesture                                                             |
| ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| Tab screens                                                                                    | Left/right: next/previous tab                                       |
| Library                                                                                        | Left/right: between its tabs (a pager)                              |
| Mini player                                                                                    | Left: next track · right: previous · up: open the player            |
| Full player                                                                                    | Artwork left/right: next/previous · down anywhere: close the player |
| Routes are type-safe `@Serializable` objects; each feature module contributes its destinations |
| (`homeScreen`, `libraryScreens`) and the app module wires them together.                       |

## Phase 10 extras

| Feature            | How                                                                                                                                                                                                                                                                                                                                                                                                       |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android Auto       | `PlaybackService` is a `MediaLibraryService`; `LibraryTree` serves Recently played, Recently added, Playlists, Downloaded and Favorites, plus voice search. Artwork reaches the car through `ArtworkProvider` (`content://app.rev.artwork/<id>`), read-only and backed by the Coil cache, so no tokens leave the app.                                                                                     |
| ReplayGain         | `ReplayGainAudioProcessor` in the ExoPlayer audio sink applies track or album gain (Settings → Playback; default Album) with pre-amp, limited by the tagged peak so it never clips, and ramped to avoid clicks. Tracks without tags play unchanged.                                                                                                                                                       |
| Gapless            | ExoPlayer's gapless playback (encoder delay/padding) on the playlist; crossfade is skipped between consecutive tracks of the same album so live/concept albums stay gapless.                                                                                                                                                                                                                              |
| Crossfade          | 0–12 s (Settings → Playback, off by default). `Crossfader` starts the next track on a second "tail" player and fades with an equal-power curve.                                                                                                                                                                                                                                                           |
| Home-screen widget | Glance `NowPlayingWidget`: artwork, title/artist, play-pause, next; tap opens REV.                                                                                                                                                                                                                                                                                                                        |
| Casting            | Media3 `CastPlayer` wraps the local player; the player screen has a Cast button. `RevCastConverter` hands the receiver signed, media-only URLs (see api.md → Casting). Needs `REV_CAST_BASE_URL` on the server.                                                                                                                                                                                           |
| Wear OS            | Phone: `WearBridge` publishes now playing (DataItem with artwork), executes play/pause/next/previous, lists albums downloaded on the phone and copies one to the watch over `ChannelClient`. Watch: a "Phone" remote card, "On this watch" albums played by its own `MediaSessionService` (Bluetooth headphones required, as Wear OS expects), and "Add from phone". The watch never talks to the server. |

Known limits: crossfade needs the next track to buffer in time
(otherwise it falls back to a normal transition).

## Equalizer

Settings → Playback → Equalizer (`EqualizerScreen`). The phone's own `Equalizer` and `BassBoost`
audio effects on REV's audio session: on/off, the device's presets, a slider per band (bands and
ranges differ per phone; `DeviceEqualizerCapabilities` reads them from a throwaway session) and bass
boost. Settings live in `PlaybackSettings.equalizer`; `AudioEffects` in the playback service creates
the effects only once turned on and follows the session if it changes. The crossfade tail player
shares the main player's audio session, so fades are equalized too. The session is also announced
(`ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`), so equalizer apps like Wavelet can attach.

## Smart playlists and year in review

In "Your listening" (`:feature:stats`): **Made from your listening** shows the server's smart
playlists (On repeat, Forgotten favorites, Top songs of each year; see api.md). The play button
plays one; tapping the card offers Play, Shuffle and **Save to Navidrome** (then "Update in
Navidrome" and "Open playlist").

The Year tab leads with **Your 2026 in review** (`YearInReviewScreen`): the year's report as a story,
one page at a time. It covers listening time, your listening style, song of the year, top artists,
albums and genres, when you listened (biggest month, busiest time, streaks) and discoveries. It
ends with your top songs, to play or save to Navidrome. Tap the right side or swipe to go on, the
left edge to go back.

## Room

Schemas are exported to `data/schemas/` and committed, so migrations can be verified against them.
Bump the database version and add a migration for every schema change (v1 → v2 is an
`AutoMigration` adding the library cache tables).
