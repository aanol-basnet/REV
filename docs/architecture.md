# Architecture

```text
Android app ──HTTPS/WebSocket──▶ REV server ──▶ Navidrome / Lidarr / slskd
                 (API, artwork, audio relay)
```

- The Android app never calls Lidarr or slskd and never sees their API keys.
- Audio is relayed by REV (`/api/library/tracks/:id/stream`) rather than fetched from Navidrome
  directly. Navidrome's stream URLs embed a reusable credential, so letting the phone fetch them
  would break credential isolation. The relay streams bytes with Range support and adds no
  transcoding of its own; on the same machine its overhead is negligible.
- Soularr is treated as existing infrastructure. REV observes its effects through Lidarr, slskd and
  Navidrome; it does not control or replace it.
- Remote access is via Tailscale; nothing needs to be exposed to the public internet.

## Server (`server/`)

| Path             | Purpose                                                          |
| ---------------- | ---------------------------------------------------------------- |
| `src/main.ts`    | Entry point: config, database, HTTP listener, graceful shutdown  |
| `src/app.ts`     | Builds the Fastify instance (used by `main.ts` and tests)        |
| `src/config.ts`  | Zod-validated environment configuration                          |
| `src/api/`       | HTTP routes and uniform error handling                           |
| `src/db/`        | Drizzle schema, SQLite connection, migrations runner             |
| `src/adapters/*` | One adapter per external service (only place that knows its API) |
| `drizzle/`       | Generated SQL migrations (applied automatically at startup)      |

Routes that need a user use the `requireAuth` preHandler (`src/auth/middleware.ts`). Request bodies
are validated with the shared Zod schemas via `parseInput`.

Backend failures are raised as `UpstreamError` (`service`, `kind`) by adapters and mapped centrally
to safe HTTP errors (`503 …_UNAVAILABLE`, `502 …_AUTH_FAILED`, `404`). Details go to the log only.

Artwork is the one place REV relays bytes from Navidrome: `/api/library/artwork/:id` streams a
specific cover image so Navidrome credentials stay on the server. It is not a general proxy; the id
is validated and only `getCoverArt` is called.

Album availability is decided in one function, `resolveAvailability` (`src/library/availability.ts`):
playable in Navidrome wins; then an active REV download job; then Lidarr's own state (complete files
→ `IN_LIBRARY`, monitored → `REQUESTED`); otherwise `MISSING`. Clients only ever see the result.

Every error response has the shape `{ "error": { "code": string, "message": string } }`.
Unexpected errors are logged and returned as a generic `INTERNAL` error; internals are never leaked.

## Shared schemas (`shared/schemas/`)

Zod schemas defining the API contract (`@rev/schemas`). The server validates against them. The
Android app mirrors the relevant types in Kotlin (`:domain`); keep the two in sync when changing
the contract. Normalized states such as `AlbumAvailability` and `DownloadJobState` are defined
here so clients never interpret raw Lidarr/slskd data.

## Android (`android/REV/`)

```text
UI (Compose) → ViewModel → domain → repository → remote / local data source
```

| Module       | Purpose                                                            |
| ------------ | ------------------------------------------------------------------ |
| `:app`       | Application, activity, navigation, Hilt entry point                |
| `:core`      | Theme and shared UI building blocks                                |
| `:domain`    | Pure-Kotlin models and use cases (no Android dependencies)         |
| `:data`      | Room database, repositories, network data sources                  |
| `:playback`  | `MediaSessionService` + ExoPlayer; the player lives outside the UI |
| `:feature:*` | One module per screen area (added as each phase lands)             |

App flow is driven by a single `AuthState` (`NeedsServer → SignedOut → SignedIn`) derived from
stored configuration and the stored session. Anything that clears the session (sign out, revoked
or expired refresh token) returns the app to sign-in without any screen handling it explicitly.

Library data is cache-first: screens observe Room and trigger a refresh from the server; the cache
is updated in place and the UI follows. If the server is unreachable, whatever was cached is still
shown with a notice. Very large collections (all tracks, albums in a genre) are paged from the
server instead of cached. Artwork goes through Coil with the authenticated REV client and Coil's
disk cache.

Playback:

```text
Compose UI ─▶ PlayerViewModel ─▶ PlaybackController ─▶ MediaController
                                                          │ (IPC)
Library screens ─▶ PlaybackQueue (domain interface) ──────┤
                                                          ▼
                     PlaybackService (MediaSessionService) ─▶ ExoPlayer
                                                                │
                                         ResolvingDataSource ◀──┘
                                         rev:track/<id> ─▶ TrackSourceResolver
                                              ├─ downloaded file  (file://)
                                              └─ REV stream URL   (OkHttp + session token)
```

Offline downloads (Phase 7) populate the "downloaded file" branch: WorkManager workers save
tracks to app-private storage and record them in Room (see [android.md](android.md)).

Queue items carry `rev:track/<id>` URIs, not URLs. The concrete source is chosen when each track
starts loading: a downloaded copy if one exists, otherwise the server stream, otherwise the track is
skipped as unavailable. So a queue of downloaded tracks plays with no connection, and there is no
"offline mode" switch. Because the player is exposed through a `MediaSession`, the notification,
lock screen, Bluetooth/headset and, later, Android Auto all control the same player.
