# Downloads

REV gets albums the user doesn't own through the existing stack, without replacing any of it:

```text
App ── POST /api/downloads ──▶ REV ──▶ Lidarr (album + artist monitored)
                                          │  wanted list
                                          ▼
                                       Soularr (every ~5 min, existing timer)
                                          │  searches + downloads
                                          ▼
                                        slskd ──▶ /srv/music/downloads
                                          │
                       Soularr ──▶ Lidarr "DownloadedAlbumsScan" import ──▶ library folder
                                                                              │
                                                                Navidrome scan ▼
                                                                         playable
```

REV never searches Soulseek, enqueues transfers or moves files itself. It **observes** each step
and keeps a download job per request so the app can show progress.

## Job states

| State                   | Meaning                                                                   | How REV knows                                                                                                                 |
| ----------------------- | ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `REQUESTED`, `CHECKING` | Request received, checking what we already have                           | Request handling                                                                                                              |
| `ADDED_TO_LIDARR`       | Album (and artist) monitored in Lidarr                                    | Request handling                                                                                                              |
| `WAITING_FOR_SOULARR`   | On Lidarr's wanted list; Soularr hasn't picked it up yet                  | Lidarr: monitored, no files, nothing else seen                                                                                |
| `SEARCHING`             | Soularr is searching Soulseek                                             | slskd: active search whose text is the album title (optionally prefixed with the artist)                                      |
| `QUEUED`                | Files queued with a peer                                                  | slskd: matching transfers, none started                                                                                       |
| `DOWNLOADING`           | Transferring; `progress` is bytes done / total                            | slskd: matching transfers in progress (or Lidarr's own queue, for indexer downloads)                                          |
| `IMPORTING`             | Files arrived; Lidarr is importing them                                   | slskd: all matching transfers done, or a running Lidarr `DownloadedAlbumsScan` for the album's "Artist - Title (Year)" folder |
| `INDEXING`              | Lidarr has every track file; waiting for Navidrome                        | Lidarr album statistics                                                                                                       |
| `AVAILABLE`             | Playable                                                                  | Navidrome search finds the album (normalized artist + title)                                                                  |
| `FAILED`                | Import failed, album removed from Lidarr, or the request couldn't be made | Lidarr import result / album missing                                                                                          |
| `TIMEOUT`               | No progress within the time limit                                         | See below                                                                                                                     |
| `CANCELLED`             | Cancelled in the app, or unmonitored in Lidarr by someone                 |                                                                                                                               |

A matching slskd transfer is one whose remote folder contains the album title as whole words and
either the artist name or a last folder that is just the title (year/format tags ignored). Only
activity that started after the request counts, so earlier downloads of the same album are ignored.

Rules are applied strongest evidence first (playable → files in Lidarr → import → transfers →
search → waiting) by a pure function, `assess()` in `server/src/downloads/progress.ts`, and every
change goes through the state machine (`state-machine.ts`). If a service can't be reached during a
check, that evidence is simply missing and the job stays where it is.

## Timing

| Setting                      | Default |                                                                                                                                                                                                                 |
| ---------------------------- | ------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `REV_DOWNLOAD_POLL_SECONDS`  | 15      | How often active jobs are checked                                                                                                                                                                               |
| `REV_DOWNLOAD_TIMEOUT_HOURS` | 72      | Give up after this long while _stalled_ (waiting, searching, queued). Never interrupts an active transfer or import. Soularr works through the wanted list a page at a time, so rare albums can take many runs. |
| `REV_INDEXING_TIMEOUT_HOURS` | 6       | Give up if Lidarr imported the album but Navidrome still doesn't list it                                                                                                                                        |

When a job reaches `INDEXING`, REV asks Navidrome to scan (`startScan`). That needs an admin
Navidrome account; otherwise REV waits for Navidrome's own scheduled scan or file watcher.

## Failures

- **Import failed** — Soularr moves the files to `failed_imports` and (with its
  `failed_import_denylist`) won't retry the album. Fix the files or tags, import manually in Lidarr,
  then request again.
- **Timed out** — usually no Soulseek peer had a matching release. Requesting again starts over.
- **Removed / withdrawn** — the album was deleted or unmonitored in Lidarr outside REV.

## Live updates

Every job change is published on the event bus and pushed over `/api/events`
(`download.created/updated/completed/failed`, plus `library.updated` when an album becomes
playable). The Android app keeps the socket open while signed in and in the foreground, caches jobs
in Room, and falls back to periodic refresh if the socket is down.
