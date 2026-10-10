# REV

**Your music. Your server. No subscription.**

REV is a native Android music player for a self-hosted library, plus a small server that ties your
existing music stack together. You get a single app for listening, finding and requesting music,
with the files on your own machine.

REV doesn't replace the services you already run. It sits in front of them:

| Service                                        | Role                                         | Needed?                      |
| ---------------------------------------------- | -------------------------------------------- | ---------------------------- |
| [Navidrome](https://www.navidrome.org)         | Your library: metadata, streaming, playlists | **Required**                 |
| **REV server** (this repo)                     | The app's only backend; holds every password | **Required**                 |
| [Lidarr](https://lidarr.audio)                 | Tracks wanted albums and imports them        | Optional, for album requests |
| [slskd](https://github.com/slskd/slskd)        | Soulseek client that downloads them          | Optional, for album requests |
| [Soularr](https://github.com/mrusse/soularr)   | Connects Lidarr's wanted list to slskd       | Optional, for album requests |
| [Tailscale](https://tailscale.com) or a tunnel | Reaching your server from outside your home  | Recommended                  |

The phone talks only to the REV server. Navidrome, Lidarr and slskd credentials never leave the
server, and none of those services needs to be exposed to the internet.

## What you get

- **Library**: artists, albums, playlists, favorites and genres, cached on the phone so browsing is
  instant and works offline.
- **Playback**: gapless, crossfade, ReplayGain (track or album) and an equalizer. Playback survives
  closing the app, and you get lock-screen, notification and headset controls.
- **Home**: recently played, mixes built from your listening, similar artists, albums you haven't
  heard, and rediscoveries.
- **Autoplay**: when the queue runs out, REV plays the next album or similar songs.
- **Offline**: download albums or playlists to the phone; smart downloads keep your favorites and
  recent listening on the device.
- **Lyrics**: synced lyrics that follow the song, from your files or [LRCLIB](https://lrclib.net),
  cached for offline use.
- **Album requests**: the server can request albums you don't have through Lidarr and follow them
  through Soularr and slskd until they can be played, with live progress in the app's Downloads
  tab. Starting requests from in-app catalog search is currently switched off; requests go through
  the API (`POST /api/downloads`, see [docs/api.md](docs/api.md)).
- **Listening stats**: weekly, monthly, yearly and all-time reports on your listening.
  - Top artists, albums, songs and genres
  - Streaks, listening rhythm, discoveries
  - A swipeable **year in review**
  - Smart playlists such as _On repeat_, _Forgotten favorites_ and _Top songs of 2026_, which you
    can save to Navidrome
- **Everywhere**: Android Auto, a home-screen widget, Chromecast on your home network and a Wear OS
  app.
- **Home and away**: at home the app talks to the server over the local network, and elsewhere it
  uses your remote address. It switches by itself.

Requirements: an Android 10+ phone, and a Linux machine (or anything that runs Node.js 22) that is
always on and can reach your music.

## How it fits together

```text
 Android app ──HTTPS / Tailscale──▶ REV server ──▶ Navidrome  (library, streams)
                                         │
                                         ├──▶ Lidarr ──▶ Soularr ──▶ slskd   (album requests)
                                         └──▶ LRCLIB, Cover Art Archive    (lyrics, artwork)
```

The REV server is a small Node.js service with its own SQLite database. The database holds REV's
accounts and sessions, the listening log behind the stats, lyrics and download tracking.

## Setting it up

The steps below assume a Linux server. Each backend service has its own install guide; REV only
needs their URLs and credentials.

### 1. Navidrome (required)

Install [Navidrome](https://www.navidrome.org/docs/installation/) and point it at your music
folder. Then create a **dedicated, non-admin user** for REV (for example `rev`) in Navidrome's
web UI. REV uses this one account for everything, so favorites, play counts and playlists made in
REV live under it.

### 2. Album requests (optional)

To request albums you don't own, set up:

1. **Lidarr**, with the root folder Navidrome scans as its library.
2. **slskd**, with a Soulseek account. Note its API key (`web.authentication.api_keys` in slskd's
   config).
3. **Soularr**, configured with your Lidarr and slskd. REV doesn't talk to Soularr. It only puts
   albums on Lidarr's wanted list, and Soularr picks them up as usual.

Skip this step and the app still works fully as a player; only album requests are unavailable.
See [docs/downloads.md](docs/downloads.md) for how requests are tracked.

### 3. The REV server

You need Node.js 22 or newer.

```sh
git clone https://github.com/aanol-basnet/REV.git
cd REV
npm ci
npm run build

cp server/.env.example server/.env
chmod 600 server/.env
$EDITOR server/.env          # see below
```

The settings that matter most in `server/.env`:

| Variable                                    | What to put                                                                      |
| ------------------------------------------- | -------------------------------------------------------------------------------- |
| `REV_HOST` / `REV_PORT`                     | Where REV listens. `0.0.0.0` with a firewall, or your Tailscale IP. Port `4600`. |
| `NAVIDROME_URL` / `_USERNAME` / `_PASSWORD` | Navidrome's address and the dedicated user from step 1                           |
| `LIDARR_URL` / `LIDARR_API_KEY`             | Optional, for requests (Lidarr → Settings → General → API Key)                   |
| `SLSKD_URL` / `SLSKD_API_KEY`               | Optional, for detailed download progress                                         |

Every variable is listed in [docs/deployment.md](docs/deployment.md). If the configuration is
invalid, REV refuses to start and names the offending variables, never their values.

There's no sign-up in the app, so create your account on the server:

```sh
npm run user -w @rev/server -- add <username>     # asks for a password (10+ characters)
```

Start it and check that it answers:

```sh
npm start -w @rev/server
curl http://127.0.0.1:4600/api/system/health
```

To keep it running, use systemd. [docs/deployment.md](docs/deployment.md#systemd) has a hardened
system unit, and `deploy/systemd/rev.user.service` is a user unit for running without root.
Database migrations run automatically at startup.

**Back up `server/data/rev.db`.** Your listening history exists only there; Navidrome keeps only
play counts. A nightly `sqlite3 server/data/rev.db ".backup rev-$(date +%F).db"` is enough.

### 4. Reaching the server from your phone

Choose one:

- **Tailscale** (simplest and private): install Tailscale on the server and the phone. Then set
  `REV_HOST` to the server's Tailscale IP, or to `0.0.0.0` behind a firewall, and use
  `100.x.y.z:4600` in the app. Tailscale encrypts the traffic, so plain HTTP is fine.
- **A tunnel or reverse proxy with HTTPS** (Cloudflare Tunnel, Caddy, nginx, `tailscale serve`).
  Use the `https://` address in the app.

Optionally, add the server's **home-network address** (`http://192.168.x.x:4600`) in the app. At
home the app then streams directly over your LAN, and it switches back to the remote address when
you leave. Allow the port from your LAN only:

```sh
sudo ufw allow from 192.168.1.0/24 to any port 4600 proto tcp
```

The same LAN access enables **Chromecast**: set `REV_CAST_BASE_URL` (see
[docs/deployment.md](docs/deployment.md#casting-lan)).

### 5. The Android app

There's no Play Store release. Build the app yourself, or download the debug APK from the latest
successful [CI run](https://github.com/aanol-basnet/REV/actions) (artifact `android`).

To build it you need JDK 17+ and the Android SDK with `platforms;android-37.0`:

```sh
cd android/REV
echo "sdk.dir=/path/to/android/sdk" > local.properties
./gradlew assembleDebug                 # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                  # or install straight to a connected phone
```

Open REV, enter your server address (for example `100.x.y.z:4600` or `https://rev.example.com`),
and sign in with the account from step 3.

The Wear OS app is in `android/REV/wear` (`./gradlew :wear:installDebug`). It controls the phone
and can carry downloaded albums to the watch.

### Troubleshooting

- **Settings → System status** in the app shows whether Navidrome, Lidarr, slskd and Soularr are
  reachable, as REV sees them.
- **"Different server"**: the address now answers as another REV installation. Each server has an
  instance id, and the app won't send your session to a different one.
- **Server logs**: `journalctl -u rev -f`, or `journalctl --user -u rev -f` for the user unit.

## Documentation

- [Architecture](docs/architecture.md): how the pieces fit together
- [Deployment](docs/deployment.md): every setting, systemd, Tailscale, LAN and casting
- [API](docs/api.md): the HTTP and WebSocket API the app uses
- [Android](docs/android.md): app structure, playback, offline and connection handling
- [Downloads](docs/downloads.md): how album requests are followed through the stack
- [Lyrics](docs/lyrics.md): providers, matching and caching

## Development

```text
android/REV/      Kotlin + Jetpack Compose app (Gradle multi-module; `wear/` is the Wear OS app)
server/           Node.js + TypeScript + Fastify + SQLite/Drizzle
shared/schemas/   Zod schemas shared by the server (the API contract)
docs/             Architecture, API, Android, deployment notes
```

```sh
npm run dev -w @rev/server                                  # server with auto-reload
npm run typecheck && npm run lint && npm run format:check && npm test

cd android/REV && ./gradlew test lintDebug assembleDebug    # app
```

CI runs both on every push and pull request.

## Status

REV is a personal project, built and used daily by one person on one server. It works well for
that, but it hasn't been tested on many setups. Expect rough edges, and please open an issue if
something doesn't work for you.

Known limitations:

- REV uses one Navidrome account, so everyone signing in to the same REV server shares its
  favorites, playlists and play counts. Listening stats are kept per REV account.
- The Wear OS app hasn't been tried on many watches.
