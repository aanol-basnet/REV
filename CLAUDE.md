# REV: notes for working on this repo

## Versioning (every commit)

Every commit bumps the app version: `rev.version` in `android/REV/gradle.properties`, shared by the
phone and watch apps. The version code is derived from it, so only that one line changes.

- `+0.0.1` for fixes and small changes
- `+0.1.0` (patch back to 0) for a big feature
- `1.0.0` once the app feels finished

Put the bump in the same commit and mention the new version in the summary. Updates are installed
by reinstalling a debug build (`./gradlew installDebug`); there is no release or auto-update
pipeline, so don't add one unless asked. The version shows at the bottom of Settings.

## Checks before committing

```sh
npm run typecheck && npm run lint && npm run format:check && npm test     # server
cd android/REV && ./gradlew test lintDebug assembleDebug                  # app, zero lint findings
```

Update `docs/` when behaviour changes.

## Public repo

This repo is public. Never commit personal data: real IPs or Tailscale addresses, hostnames, home
paths, email addresses, tokens, `.env` files, databases or keystores. Use placeholders such as
`192.168.1.x`, `100.64.0.7`, `myserver` and `alice` in docs and tests.
