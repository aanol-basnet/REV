# REV API

Base path: `/api`. JSON in and out. Schemas live in `shared/schemas` (`@rev/schemas`).

## Errors

All non-2xx responses (`VALIDATION_ERROR`, `UNAUTHORIZED`, `NOT_FOUND`, `RATE_LIMITED`, `INTERNAL`, …):

```json
{ "error": { "code": "NOT_FOUND", "message": "Route not found" } }
```

## Authentication

REV has its own accounts (created with the `user` CLI; there is no self-registration). Clients
authenticate with opaque bearer tokens:

| Token                | Lifetime | Notes                                                          |
| -------------------- | -------- | -------------------------------------------------------------- |
| Access (`rev_at_…`)  | 15 min   | Sent as `Authorization: Bearer <token>`.                       |
| Refresh (`rev_rt_…`) | 30 days  | Single use: every refresh returns a new pair (sliding expiry). |

Only SHA-256 hashes of tokens are stored server-side. Passwords are hashed with Argon2id.

**Refresh-token reuse:** presenting a refresh token that has already been rotated revokes the whole
session (it signals the token was copied). As an exception, the previous token is accepted for 60 s
after a rotation so a client whose refresh response was lost on a flaky connection can retry.

Protected routes answer `401` with `WWW-Authenticate: Bearer` when the access token is missing,
invalid or expired. Clients should refresh once and retry; if refresh also fails, sign in again.

Rate limits (per IP): login 10/min, refresh 30/min. Exceeding them returns `429 RATE_LIMITED`.

### `POST /api/auth/login`

```json
{ "username": "alice", "password": "…", "deviceName": "Pixel 9" }
```

`200`:

```json
{
  "accessToken": "rev_at_…",
  "accessTokenExpiresAt": 1790954144097,
  "refreshToken": "rev_rt_…",
  "refreshTokenExpiresAt": 1793545244097,
  "user": { "id": 1, "username": "alice" }
}
```

`401 INVALID_CREDENTIALS` for a wrong password or unknown user (indistinguishable, including timing).

### `POST /api/auth/refresh`

`{ "refreshToken": "rev_rt_…" }` → same body as login. `401 INVALID_REFRESH_TOKEN` when the token is
unknown, expired, revoked or reused.

### `POST /api/auth/logout` (authenticated)

Revokes the current session. `204`.

### `GET /api/auth/session` (authenticated)

```json
{
  "user": { "id": 1, "username": "alice" },
  "sessionId": "b67678f1-…",
  "deviceName": "Pixel 9",
  "createdAt": 1790953244097
}
```

## Library (authenticated)

Backed by Navidrome through the server's Subsonic adapter. Responses use REV's own model
(`shared/schemas/src/library.ts`); Subsonic shapes and file paths never reach clients. Durations
are milliseconds and timestamps epoch milliseconds. Ids are Navidrome ids (`[A-Za-z0-9._-]{1,100}`);
anything else is rejected with `400`.

| Method       | Path                                             | Returns                                                           |
| ------------ | ------------------------------------------------ | ----------------------------------------------------------------- |
| GET          | `/api/library/artists`                           | `{ artists: Artist[] }` (A–Z)                                     |
| GET          | `/api/library/artists/:id`                       | `ArtistDetail` (artist + `albums`)                                |
| GET          | `/api/library/albums?type=&offset=&size=&genre=` | `AlbumPage`                                                       |
| GET          | `/api/library/albums/:id`                        | `AlbumDetail` (album + `tracks`)                                  |
| GET          | `/api/library/tracks?offset=&size=`              | `TrackPage` (whole library, paged)                                |
| GET          | `/api/library/tracks/:id`                        | `Track`                                                           |
| GET          | `/api/library/genres`                            | `{ genres: Genre[] }`                                             |
| GET          | `/api/library/favorites`                         | `{ artists, albums, tracks }`                                     |
| PUT / DELETE | `/api/library/favorites/:kind/:id`               | `204`; `kind` is `artists`, `albums` or `tracks`                  |
| GET          | `/api/library/artwork/:coverArtId?size=`         | Image bytes, `Cache-Control: private, max-age=2592000, immutable` |

Album list `type`: `alphabetical` (default), `newest` (recently added), `recent` (recently played),
`frequent`, `random`, `starred`, `byGenre` (requires `genre`). `size` is 1–500 (default 50); pages
report `hasMore`.

Example `Track`:

```json
{
  "id": "tr-1",
  "title": "The Grudge",
  "album": "Lateralus",
  "albumId": "al-1",
  "artist": "Tool",
  "artistId": "ar-1",
  "trackNumber": 1,
  "discNumber": 1,
  "year": 2001,
  "genre": "Progressive Metal",
  "coverArtId": "mf-tr-1",
  "durationMs": 516000,
  "starred": false,
  "format": "flac",
  "bitRateKbps": 1024,
  "sampleRateHz": 44100,
  "bitDepth": 16,
  "sizeBytes": 66000000
}
```

### Playlists

Playlists live in Navidrome, so ones made in REV also appear in Navidrome and other Subsonic apps.
They belong to the Navidrome account REV signs in as; each playlist reports `canEdit`, and other
users' (public) playlists are read-only (`403 PLAYLIST_READ_ONLY` on any change).

| Method | Path                                   | Body                                   | Returns                                                          |
| ------ | -------------------------------------- | -------------------------------------- | ---------------------------------------------------------------- |
| GET    | `/api/library/playlists`               |                                        | `{ playlists: Playlist[] }`                                      |
| GET    | `/api/library/playlists/:id`           |                                        | `PlaylistDetail` (playlist + `tracks`)                           |
| POST   | `/api/library/playlists`               | `{ name, comment?, trackIds? }`        | `201` `PlaylistDetail`                                           |
| PATCH  | `/api/library/playlists/:id`           | `{ name?, comment? }` (`""` clears it) | `PlaylistDetail`                                                 |
| POST   | `/api/library/playlists/:id/tracks`    | `{ trackIds, skipDuplicates? = true }` | `{ playlist, added, skipped }` (appends)                         |
| PUT    | `/api/library/playlists/:id/tracks`    | `{ trackIds }`, the complete new order | `PlaylistDetail` (how songs are removed and reordered)           |
| POST   | `/api/library/playlists/:id/duplicate` | `{ name? }` (default "<name> (copy)")  | `201` `PlaylistDetail`, an editable copy (read-only sources too) |
| DELETE | `/api/library/playlists/:id`           |                                        | `204`                                                            |

Names are 1–200 characters, descriptions up to 1000, up to 10,000 songs per request. Song lists
are sent to Navidrome as a form `POST` (OpenSubsonic `formPost`), so long playlists don't
overflow a URL.

### Streaming

`GET /api/library/tracks/:id/stream` relays audio from Navidrome so its credentials never reach
clients. Without `maxBitRate` the original file is sent untouched (`format=raw`); with
`maxBitRate` (64, 96, 128, 160, 192, 256 or 320) Navidrome transcodes. `Range` requests are
forwarded and `206` / `Content-Range` / `Accept-Ranges` returned, so players can seek. The upstream
download is aborted as soon as the client disconnects.

### Artist top tracks

`GET /api/library/artists/:id/top-tracks` returns `{ tracks }`, best first: by your play count, then
what the artist is known for (Navidrome's metadata agents, e.g. Last.fm `getTopSongs`, waited on for
at most 3 s), then songs from the albums you play most. The same song on several releases counts
once ("Schism" and "Schism (Live)"); on shared releases only the artist's own songs count. Up to 30
tracks plus any starred songs beyond that. Cached per artist for 10 minutes.

Albums carry `releaseTypes` (lowercased MusicBrainz types from the tags: `album`, `ep`, `single`,
`compilation`, `live`…; empty when untagged).

### Home and mixes

`GET /api/home` (authenticated) returns `HomeFeed`: `quickPicks` (albums), `mixes`, `sections` and
`genres`, built from Navidrome's listening data — play counts, last-played times, similar artists
(its metadata agents, e.g. Last.fm) and genre tags. Everything recommended is in the library.

| Section `kind` | Contents                                                                                         |
| -------------- | ------------------------------------------------------------------------------------------------ |
| `recent`       | Recently played                                                                                  |
| `frequent`     | Most played, and played within the last 45 days ("On repeat")                                    |
| `similar`      | Albums by artists similar to one of your two most played artists (`basedOn`/`basedOnId`)         |
| `genre`        | More of a genre you play a lot, unheard albums first. Genres are ranked by plays ÷ √(genre size) |
| `rediscover`   | Most played, but not in the last 45 days                                                         |
| `unheard`      | Never-played albums, leaning to your genres                                                      |
| `newest`       | Recently added                                                                                   |

Rows under four albums are left out, and no album appears in two rows (except "recent"). A row
whose source fails is dropped; the rest still returns. The feed is cached for 15 minutes
(`?refresh=true` rebuilds) and its shuffles are seeded by date, so it's stable within a day.
Clients localize titles from `kind` and `basedOn`.

With `?tz=<IANA zone>`, the feed's `mixes` start with the mix for the current time of day
(`{ id: "time:morning", kind: "time", name: "morning" }`; also `afternoon` 12–17, `evening`
17–22, `night` 22–5, morning being 5–12), built from REV's own listening log for the signed-in
user, once that time of day has at least 8 plays and 4 songs played more than skipped then. It
isn't part of the cached feed, so it changes with the clock.

`GET /api/mixes/:id` returns `{ id, tracks }`, freshly shuffled: `artist:<id>` (an artist radio:
the artist every third track, the rest from similar artists), `genre:<name>`, `favorites` (starred
tracks), `discovery` (never-played tracks) or `time:<time of day>` (with `?tz=`, UTC by
default: up to 34 of your songs for that time, picked at random weighted by score, and every third
song a new one from the radio for the artists and genres you lean toward then, or, if none stand
out, for the artists of your songs; songs you mostly skip then stay out). In every mix, songs by one artist are spread at
least three apart where the mix allows. Play counts come from the one Navidrome account REV uses, so
they're shared by all REV users.

### Autoplay

What to append when the queue runs out. The app picks the request from the queue's shape:

- `POST /api/autoplay/next-album` `{ albumId, artistId, genre, exclude }`, after whole albums:
  `{ album, tracks }` for the artist's next release, else an album by a similar artist, the genre,
  or any album.
- `POST /api/autoplay/artist` `{ artistId, genres, exclude, excludeAlbums?, size? }`, after a
  queue of one artist's songs (such as their top songs): `{ tracks }` with up to `size` (default
  25, max 60) of the artist's songs not in `exclude` or from `excludeAlbums`, shuffled. When none
  are left, a radio of similar artists and the genres without the artist.
- `POST /api/autoplay/radio` `{ artistIds, genres, exclude, avoidArtists?, size? }`, after
  anything else: `{ tracks }`, up to `size` songs from similar artists and the genres, with the
  seed artists every fourth song, never by `avoidArtists`.

The app's smart queue uses the same endpoints with a bigger `size` and ranks the songs itself.

### Casting

`POST /api/cast/token` (authenticated) returns `{ baseUrl, token, expiresAt }`. A Chromecast can't
send the app's bearer token, so the app gives it stream and artwork URLs on `baseUrl` with
`?cast=<token>&exp=<epoch seconds>` appended. The token is an HMAC over its expiry with a key kept
in the server database; it is valid for 12 hours and **only** for
`GET /api/library/tracks/:id/stream` and `GET /api/library/artwork/:coverArtId` — every other
endpoint ignores it. `baseUrl` comes from `REV_CAST_BASE_URL` (a LAN address the Cast device can
reach); without it the endpoint returns `503 CAST_NOT_CONFIGURED`.

Tracks also carry `replayGain` (`{ trackGainDb, albumGainDb, trackPeak, albumPeak }` or `null`),
taken from the file tags Navidrome reads.

### Scrobbling

`POST /api/library/tracks/:id/scrobble` with `submission: false` marks a track as now playing.
`submission: true` records a play: it goes into REV's listening log and on to Navidrome (play
count, "recently played"). The app now reports plays through `POST /api/stats/listens` instead.

Errors specific to the music server:

| Status | Code                       | Meaning                                         |
| ------ | -------------------------- | ----------------------------------------------- |
| 503    | `NAVIDROME_NOT_CONFIGURED` | `NAVIDROME_*` variables are not set             |
| 503    | `NAVIDROME_UNAVAILABLE`    | Navidrome is down or timed out                  |
| 502    | `NAVIDROME_AUTH_FAILED`    | Navidrome rejected REV's configured credentials |
| 502    | `NAVIDROME_ERROR`          | Unexpected Navidrome response                   |
| 404    | `NOT_FOUND`                | Unknown id                                      |

## Listening statistics (authenticated)

Navidrome only keeps a play count and the last play per track, so REV keeps its own dated log of
listens per REV user (table `plays`, with the track's title, artist, album, genres and length as
they were at the time). Reports are built from that log. History starts when this was deployed.

### `POST /api/stats/listens`

```json
{
  "listens": [
    { "trackId": "tr-1", "startedAt": 1791547200000, "kind": "play" },
    { "trackId": "tr-2", "startedAt": 1791547500000, "kind": "skip", "listenedMs": 12000 }
  ]
}
```

1–500 listens. `play`: listened to long enough to count (half the track or four minutes);
`listenedMs` defaults to the track's length. `skip`: moved on before that, after `listenedMs`.
Each play is also scrobbled to Navidrome with its original time. A listen is identified by
track + `startedAt`; resends are ignored and not scrobbled twice. Tracks Navidrome no longer has
are dropped. Returns `{ recorded, duplicates, unknown }`. If Navidrome can't be reached the
request fails (502/503) after keeping what it recorded; the app retries later.

Each listen may carry a `context` object: the situation it was played in and what you did while
it played (training data for the smart queue; fields in [training-data.md](training-data.md)).
Every field is optional; unknown fields are kept, and a context that doesn't validate is dropped
without rejecting the listen.

A play is sent when it counts and again when the song ends, with an `outcome` in its context
(`completed`, `outro`, `saved`...; see [training-data.md](training-data.md#outcomes)): a resend
with an outcome replaces the stored context and `listenedMs` instead of being ignored, and isn't
scrobbled again.

### `POST /api/stats/interactions`

`{ events: [{ kind, trackId, at }] }` (1–500): things done with songs besides listening:
`playlist_add`, `play_next`, `queue_add`, `queue_remove`, `favorite`, `unfavorite`, `repeat_one`,
`lyrics` (other kinds are stored too). Stored with the song's artist and genres; feed the taste
profile. Resends (same kind, song and time) are ignored. Returns `{ recorded, duplicates, unknown }`;
fails like listens when Navidrome can't be reached.

### `GET /api/stats/report?period=week|month|year|all&offset=0&tz=Europe/Berlin`

`offset` 0 is the current period, 1 the one before. Weeks start on Monday. Days, hours and period
boundaries follow `tz` (IANA, default UTC). The report (`ListeningReport` in
`shared/schemas/src/stats.ts`) contains:

| Field                                               | What                                                                                                          |
| --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `period`                                            | Bounds, elapsed days, `hasEarlier`                                                                            |
| `totals` / `previous`                               | Plays, skips, listening time, distinct songs/artists/albums/genres, active days, daily average, skip rate     |
| `persona`                                           | The strongest habit: night owl, early bird, weekend, explorer, devotee, repeat listener, genre hopper, daily  |
| `highlights`                                        | Server-written facts: change vs the period before, genre/artist of the period, on repeat, finds, peak time…   |
| `topGenres`, `topArtists`, `topAlbums`, `topTracks` | Ranked by plays, with share and rank in the period before (`previousRank`); artists flag `isNew` (first ever) |
| `onRepeat`                                          | Songs played several times in a day (peak day and count)                                                      |
| `mostSkipped`                                       | Songs skipped more than played                                                                                |
| `discoveries`                                       | Artists first heard in the period; share of plays on songs new to you                                         |
| `rhythm`                                            | Plays by hour, weekday and a 7×24 heatmap                                                                     |
| `timeline`                                          | Per day (week, month) or per month (year, all time), each with its top genre and artist                       |
| `streaks`, `sessions`, `variety`                    | Consecutive days, listening sessions (30 min gap), how spread out your artists were (0–100)                   |

### Feedback and the personal skip model

- `POST /api/stats/feedback` `{ events: [{ trackId, listenStartedAt, kind, at, positionMs? }] }`:
  the player's "Not now" (`not_now`) and "More like this" (`more_like_this`) buttons, about the
  listen that started at `listenStartedAt`. Resends are ignored: `{ recorded, duplicates }`.
- `GET /api/stats/model`: `{ model }`, the latest personal skip model or null:
  `{ version: 1, trainedAt, dataUntil, examples, bias, weights: { feature: weight }, metrics }`,
  where `metrics` (null with too few listens) is `{ holdout, logLoss, baselineLogLoss, auc,
smartQueueAuc, phoneModelAuc }` from testing on the newest listens, `settings` (`{ learningRate,
l2, epochs }`) the tuned settings it was trained with, and `history` its training runs (`{
trainedAt, examples, logLoss, baselineLogLoss, auc }`, oldest first, up to 50). See
  [training-data.md](training-data.md).
- `POST /api/stats/model/train`: trains now and returns `{ model }` (null below 50 listens).

### `GET /api/stats/taste?tz=Europe/Berlin`

What the app's smart queue knows about your listening, from the last two years of the log
(recent listens count more: weight halves every 180 days). Scores are -1 to 1: plays against
skips (early skips count fully, later ones half), pulled toward 0 with little to go on.

```json
{
  "generatedAt": 1791000000000,
  "listens": 5120,
  "artists": [{ "id": "ar-1", "score": 0.91 }],
  "genres": [{ "name": "Progressive Metal", "score": 0.84 }],
  "decades": [{ "decade": 2000, "score": 0.7 }],
  "tracks": [{ "id": "tr-1", "plays": 31, "skips": 2 }],
  "dayparts": [{ "fromHour": 20, "genres": [{ "name": "Ambient", "score": 1 }], "artists": [] }]
}
```

`modes` lists each listening mode with at least 10 plays and the genres and artists played more or
less in it than usual (`{ mode, genres, artists }`, same scores as dayparts). `dayparts` are six 4-hour parts of the local day; their scores say how much more (up to 1) or
less (down to -1) a genre or artist is played then than usual. Parts with fewer than 20 plays are
empty.

`timesOfDay` lists morning (5–12), afternoon (12–17), evening (17–22) and night (22–5), each with
at least 8 plays: `{ time, plays, tracks: [{ id, score }], genres, artists }`. A song's score is
its plays at that time (weight halves every 60 days) minus skips then, times how much of its
playing happens then (smoothed share + 0.5), so a song you play only in the morning beats one you
play all day; up to 60, best first. Genres and artists are scored like dayparts. The app uses this
to build the time-of-day mix from downloads when offline.

### Smart playlists

Track lists made from the listening log; they change as you listen. Ids: `on-repeat` (most played in
the last 30 days, songs played at least twice, up to 25), `forgotten` (played at least 3 times but
not in the last 90 days, up to 50) and `year-<yyyy>` (the year's most played, up to 50; years follow
`tz`). Ties go to the more recently played song.

- `GET /api/stats/playlists?tz=` → `{ playlists: SmartPlaylist[] }`: `id`, `kind`, `year`,
  `trackCount`, up to four `coverArtIds`, `savedPlaylistId`. Lists with fewer than 5 songs are left
  out.
- `GET /api/stats/playlists/:id?tz=` → the same plus `tracks` (full `Track`s, in order; songs no
  longer in the library are dropped).
- `POST /api/stats/playlists/:id/save?tz=` → `{ playlistId, created }`. Writes the songs to a
  Navidrome playlist ("On Repeat", "Forgotten Favorites", "Top Songs of 2026"). REV remembers it
  (`smart_playlist_saves`), so saving again replaces that playlist's songs; if it was deleted in
  Navidrome (or isn't REV's to change), a new one is made. `404` when the list is empty.

## Search (authenticated)

### `GET /api/search?q=`

Searches the playable library (Navidrome). Rate limited to 60/min. When the query names a library
artist, the rest of their albums follow Navidrome's own hits. Up to 20 artists, 40 albums and 50
tracks.

```json
{
  "query": "Tool",
  "artists": [
    {
      "name": "Tool",
      "disambiguation": null,
      "libraryId": "ar-1",
      "coverArtId": "ar-1_0",
      "catalogId": null
    }
  ],
  "albums": [
    {
      "key": "lib:al-1",
      "title": "Lateralus",
      "artist": "Tool",
      "year": 2001,
      "albumType": null,
      "availability": "AVAILABLE",
      "libraryId": "al-1",
      "coverArtId": "al-1_0",
      "catalogId": null,
      "trackCount": 13
    }
  ],
  "tracks": [/* Track */],
  "sources": { "library": "ok", "catalog": "not_configured" }
}
```

- The MusicBrainz catalog (via Lidarr) is not searched; every result is playable.
  `sources.catalog` is always `not_configured` and the catalog fields are kept for older clients.
- `sources.library` is `unavailable` (with empty results) when Navidrome doesn't answer, or
  `not_configured`.

Availability values (`shared/schemas/src/states.ts`):

| Value                                  | Meaning                                                    |
| -------------------------------------- | ---------------------------------------------------------- |
| `AVAILABLE`                            | Playable (in Navidrome)                                    |
| `IN_LIBRARY`                           | Lidarr has all files; Navidrome doesn't list the album yet |
| `MISSING`                              | Not owned; can be requested                                |
| `REQUESTED`                            | Wanted in Lidarr, nothing downloading yet                  |
| `DOWNLOADING`, `IMPORTING`, `INDEXING` | Acquisition in progress (from download jobs, Phase 6)      |
| `FAILED`                               | The last acquisition attempt failed                        |

### `GET /api/catalog/albums/:releaseGroupId/cover`

Front cover (250 px) for a catalog album from the Cover Art Archive. Only MusicBrainz release-group
UUIDs are accepted. `404` when the archive has no cover.

## Downloads (authenticated)

A download job is REV's record of acquiring one album (`shared/schemas/src/downloads.ts`). Jobs are
shared by all REV users on the server.

| Method | Path                                  | Notes                                                                                 |
| ------ | ------------------------------------- | ------------------------------------------------------------------------------------- |
| GET    | `/api/downloads?active=true`          | `{ jobs: DownloadJob[] }`, newest first                                               |
| POST   | `/api/downloads`                      | `{ "catalogAlbumId": "<release-group id>" }` → `201 DownloadJob`. Rate limited 30/min |
| GET    | `/api/downloads/:id`                  | `DownloadJob`                                                                         |
| DELETE | `/api/downloads/:id`                  | Cancels; returns the job (idempotent)                                                 |
| GET    | `/api/catalog/albums/:releaseGroupId` | `CatalogAlbumStatus`: title, artist, availability, playable id, latest job            |

```json
{
  "id": "6f1c…",
  "catalogAlbumId": "…",
  "catalogArtistId": "…",
  "title": "Opiate",
  "artist": "Tool",
  "year": 1992,
  "state": "ADDED_TO_LIDARR",
  "progress": null,
  "error": null,
  "libraryAlbumId": null,
  "startedAt": 1790000000000,
  "updatedAt": 1790000001000,
  "completedAt": null
}
```

Requesting:

1. If an unfinished job exists for the album, it is returned (an album is never requested twice;
   concurrent requests share one attempt).
2. If the album is already playable, the job completes as `AVAILABLE` with `libraryAlbumId`.
3. Otherwise Lidarr is asked for it: an album Lidarr already tracks is monitored (and its artist,
   if needed); a new album is added together with its artist, with only that album monitored and no
   future releases. This puts it on Lidarr's wanted list, which Soularr works from. Lidarr's own
   indexer search is triggered too unless `LIDARR_SEARCH_ON_REQUEST=false`.
4. The job is now `ADDED_TO_LIDARR` (or `INDEXING` if Lidarr already had every file). Failures end
   in `FAILED` with a user-facing `error`; a new request can then be made.

Cancelling stops monitoring only what REV started monitoring, and only if the files haven't
arrived. Nothing is deleted. After the request, the server's download monitor moves the job along
(see [downloads.md](downloads.md)).

States and allowed transitions (`server/src/downloads/state-machine.ts`): forward along the
pipeline, skipping allowed; back and forth within `WAITING_FOR_SOULARR … DOWNLOADING` (Soularr
retries sources); any active state may end in `FAILED`, `TIMEOUT` or `CANCELLED`; `AVAILABLE`,
`FAILED`, `TIMEOUT` and `CANCELLED` are final.

`503 LIDARR_NOT_CONFIGURED` when Lidarr isn't set up.

## Lyrics (authenticated)

### `GET /api/lyrics/:trackId`

```json
{
  "trackId": "tr-1",
  "status": "found",
  "source": "lrclib",
  "synced": [
    { "startMs": 42100, "text": "I know the pieces fit" },
    { "startMs": 46500, "text": "" }
  ],
  "plain": "I know the pieces fit\n…",
  "fetchedAt": 1790000000000
}
```

`status` is `found`, `instrumental` or `not_found` (a definite answer). `synced` is already
parsed; clients never parse LRC. An empty `text` marks an instrumental gap. `503
LRCLIB_UNAVAILABLE` / `NAVIDROME_UNAVAILABLE` when a provider couldn't be asked (nothing is cached
then). A `lyrics.updated` event is published when new lyrics are cached. See [lyrics.md](lyrics.md).

## System

### `GET /api/system/status` (authenticated)

```json
{
  "services": {
    "navidrome": {
      "status": "online",
      "version": null,
      "detail": null,
      "checkedAt": 1790000000000
    },
    "lidarr": {
      "status": "online",
      "version": "2.9.6",
      "detail": null,
      "checkedAt": 1790000000000
    },
    "slskd": {
      "status": "degraded",
      "version": "0.24.0",
      "detail": "Not connected to the Soulseek network",
      "checkedAt": 1790000000000
    },
    "soularr": { "status": "active", "lastActivityAt": 1789999700000 }
  },
  "library": { "artists": 184, "albums": 1031, "tracks": 12482 },
  "downloads": { "active": 2 }
}
```

Service `status`: `online`, `degraded`, `offline` or `not_configured`. Soularr has no API: its
status is inferred from the newest slskd search (`active` within 15 min, otherwise `idle`).
`library.albums` sums per-artist album counts (compilations may count more than once).

### `GET /api/system/addresses` (authenticated)

`{ lanUrls: ["http://192.168.1.69:4600"] }`: where this server can be reached directly on the local
network, detected from the network interfaces on each request (loopback, VPNs such as Tailscale and
container bridges are skipped; nothing when REV listens only on loopback or a VPN address).
`REV_LAN_URL` overrides it. The app uses it to follow the server to a new DHCP address. Casting uses
the same address unless `REV_CAST_BASE_URL` pins one.

## Live events: `GET /api/events` (WebSocket, authenticated)

Upgrade with the usual `Authorization: Bearer` header. Each message is JSON `{ "type", "data" }`:

| Type                                                                               | Data                                              |
| ---------------------------------------------------------------------------------- | ------------------------------------------------- |
| `download.created` / `download.updated` / `download.completed` / `download.failed` | `DownloadJob`                                     |
| `library.updated`                                                                  | `{ "libraryAlbumId" }` — an album became playable |
| `system.navidrome.status` / `system.lidarr.status` / `system.slskd.status`         | `ServiceStatus`                                   |

The server pings every 30 s and closes the socket (code 4001) once the session is signed out or
revoked. Clients should reload state over REST after every (re)connect, then apply events; events
are not replayed.

### `GET /api/system/health`

Unauthenticated. Used by clients to check that a configured URL points at a REV server.

```json
{
  "status": "ok",
  "name": "REV",
  "version": "0.1.0",
  "instanceId": "9241c67b-f712-497b-b26e-9a312a198c4b",
  "uptimeSeconds": 42
}
```

`instanceId` is generated on first start and persisted in the REV database, so clients can tell
when an address starts pointing at a different installation.
