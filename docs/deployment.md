# Deployment

REV runs as a plain Node.js process under systemd, next to Navidrome, Lidarr and slskd. No Docker.

## Configuration

Environment variables (see `server/.env.example`). `server/.env` is loaded automatically when present.

| Variable                                                                                  | Default                                   | Notes                                                                                     |
| ----------------------------------------------------------------------------------------- | ----------------------------------------- | ----------------------------------------------------------------------------------------- |
| `REV_HOST`                                                                                | `127.0.0.1`                               | Bind address. Use the Tailscale IP (or `0.0.0.0` behind a firewall) for phone access.     |
| `REV_PORT`                                                                                | `4600`                                    |                                                                                           |
| `REV_DATABASE_URL`                                                                        | `./data/rev.db`                           | SQLite file path, relative to the working directory.                                      |
| `REV_LOG_LEVEL`                                                                           | `info`                                    | `fatal` … `trace`                                                                         |
| `NAVIDROME_URL` / `_USERNAME` / `_PASSWORD`                                               | —                                         | Required: the library, streaming, playlists and stats all come from Navidrome             |
| `LIDARR_URL` / `LIDARR_API_KEY`                                                           | —                                         | Optional: needed for album requests (downloads)                                           |
| `SLSKD_URL` / `SLSKD_API_KEY`                                                             | —                                         | Read-only: download progress and Soularr activity. Optional but recommended.              |
| `REV_LYRICS_PROVIDERS` / `LRCLIB_URL`                                                     | `navidrome,lrclib` / `https://lrclib.net` | See [lyrics.md](lyrics.md)                                                                |
| `REV_LAN_URL`                                                                             | detected                                  | LAN address given to the app (and Cast); detected from the interfaces when unset.         |
| `REV_CAST_BASE_URL`                                                                       | —                                         | Address Chromecasts use to fetch audio, e.g. `http://192.168.1.10:4600`. Enables casting. |
| `REV_DOWNLOAD_POLL_SECONDS` / `REV_DOWNLOAD_TIMEOUT_HOURS` / `REV_INDEXING_TIMEOUT_HOURS` | 15 / 72 / 6                               | See [downloads.md](downloads.md)                                                          |

Invalid configuration stops startup with a list of offending variable names (values are never printed).

## Navidrome

REV talks to Navidrome's Subsonic API with one configured Navidrome account. Use a dedicated,
non-admin Navidrome user (e.g. `rev`) with access to your library; REV does not need admin rights.
Favorites, play counts and "recently played" belong to that Navidrome account, so all REV users on
one server share them.

If these variables are missing, REV still starts, logs a warning, and library endpoints answer
`503 NAVIDROME_NOT_CONFIGURED`.

## Outbound access

The REV server makes a few outbound HTTPS requests: Lidarr's metadata lookups (made by Lidarr
itself), catalog cover images from `coverartarchive.org`, and lyrics from `lrclib.net`. Nothing is exposed inbound; the phone
still talks only to REV over Tailscale.

## Lidarr and Soularr

REV requests albums by monitoring them in Lidarr; Soularr, running as before, picks them up from
Lidarr's wanted list and downloads them with slskd. REV doesn't talk to Soularr and needs no
Soularr changes. Artists REV adds are monitored with _no_ other albums and _no_ future releases, so
requesting one album never queues a whole discography.

## Accounts

There is no sign-up in the app. Manage accounts on the server:

```sh
npm run user -w @rev/server -- add <username>      # prompts for a password (min. 10 chars)
npm run user -w @rev/server -- passwd <username>   # also signs the user out on all devices
npm run user -w @rev/server -- remove <username>
npm run user -w @rev/server -- list
```

In production (built code, run from `server/` so `.env` is picked up):
`node dist/cli/user.js add <username>`. When stdin is not a terminal the password is read from its
first line, e.g. for scripting.

## Database migrations

Migrations in `server/drizzle/` are applied automatically at startup. After changing
`server/src/db/schema.ts`, generate a new migration and commit it:

```sh
npm run db:generate -w @rev/server -- --name <change>
```

## systemd

```ini
# /etc/systemd/system/rev.service
[Unit]
Description=REV music server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=rev
WorkingDirectory=/opt/rev/server
ExecStart=/usr/bin/node dist/main.js
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/opt/rev/server/data
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```sh
npm ci && npm run build
sudo systemctl daemon-reload && sudo systemctl enable --now rev
journalctl -u rev -f
```

## Running as a user service (no root)

This is how the reference install runs: a systemd **user** unit, started at boot thanks to
lingering, restarting automatically (e.g. until the Tailscale address exists after boot).

```sh
cp deploy/systemd/rev.user.service ~/.config/systemd/user/rev.service   # adjust WorkingDirectory
loginctl enable-linger "$USER"        # start at boot, without logging in
systemctl --user daemon-reload
systemctl --user enable --now rev
journalctl --user -u rev -f
```

After pulling changes: `npm ci && npm run build && systemctl --user restart rev`.

## Home network access (app)

The app can use the server's LAN address at home and the public/remote address elsewhere (see
[android.md](android.md#home-network-and-remote)). Nothing changes on the server: REV listens on
`REV_HOST=0.0.0.0` port 4600 and Cloudflare Tunnel (or Tailscale) stays the remote path. The LAN
only has to be allowed through the firewall — the same rule as casting:

```sh
sudo ufw allow from 192.168.1.0/24 to any port 4600 proto tcp
```

No port forwarding, and Navidrome stays unexposed. If the server's LAN address changes (a new DHCP
lease), the app finds the new one by asking the server over the remote address and switches once
it answers as the same server; casting follows it too. A DHCP reservation still avoids the gap.

## Casting (LAN)

A Cast device on the home network fetches audio straight from REV, not through the phone, and it
can't use Tailscale. Set `REV_CAST_BASE_URL` to the machine's LAN address, bind REV to an address
reachable from the LAN (`REV_HOST=0.0.0.0`), and allow the port from the LAN only, for example:

```sh
sudo ufw allow from 192.168.1.0/24 to any port 4600 proto tcp
```

Cast URLs carry a 12-hour, media-only signed token (see [api.md](api.md#casting)); the rest of the
API still requires sign-in.

## Tailscale

Bind REV to the machine's Tailscale address (`REV_HOST=100.x.y.z`), or to all interfaces with a
firewall that only admits the `tailscale0` interface, and enter `100.x.y.z:4600` as the server
address in the app. MagicDNS names (`myserver:4600`, `myserver.tailnet-name.ts.net:4600`) work too.

Plain HTTP is acceptable here because Tailscale encrypts the traffic end to end. The app shows a
warning if you enter a plain-HTTP address outside Tailscale or a private network. For HTTPS, put
REV behind `tailscale serve` or a reverse proxy and use the `https://` URL.

The app refuses to talk to a different REV installation at a saved address: each server has an
instance id (see `GET /api/system/health`), and the app reports "Different server" if it changes.
