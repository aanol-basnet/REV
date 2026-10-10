# Lyrics

Lyrics are fetched, matched, cached and synchronized by REV; they don't depend on tags in the audio
files (though Navidrome's are used when present).

```text
App ── GET /api/lyrics/:trackId ──▶ REV
                                     │ 1. REV cache (SQLite `lyrics` table)
                                     │ 2. Navidrome  (OpenSubsonic getLyricsBySongId: embedded/sidecar)
                                     │ 3. LRCLIB synced
                                     │ 4. LRCLIB plain
                                     ▼ 5. none (cached, re-checked after 7 days)
App caches the answer in Room ── works offline afterwards
```

## Providers

Providers implement `LyricsProvider` (`server/src/lyrics/providers.ts`) and are tried in the
order of `REV_LYRICS_PROVIDERS` (default `navidrome,lrclib`). The first one that has lyrics wins.

- **Navidrome** — uses the songLyrics extension. Synced lyrics arrive timed (Navidrome's `offset`
  is applied). A Navidrome without the extension simply has none.
- **LRCLIB** — exact lookup by artist, title, album and duration first; otherwise a search whose
  candidates are scored. Sent with a `REV/<version>` User-Agent, at most two requests at a time.

Android clients never contact LRCLIB: all external lookups, caching and rate limiting happen on the
server, so providers can be swapped without touching the app.

## Matching

`scoreCandidate()` (`server/src/lyrics/match.ts`) compares normalized title and artist
(case, accents, punctuation, apostrophes, `feat.`/`ft.` credits and edition suffixes such as
"(2021 Remaster)" ignored; "(Live)" and "(Part 2)" are not ignored). Multi-artist credits match on
any credited artist ("TOOL;Tool", "A & B"). Album and duration raise the score; a duration more than
10 s off rejects the candidate. Anything that isn't clearly the same song is rejected — REV prefers
no lyrics to wrong lyrics. Among equal matches, synced lyrics win.

## Parsing and storage

LRC is parsed once on the server (`server/src/lyrics/lrc.ts`) into `{ startMs, text }` lines:
multiple timestamps per line, `[offset:]`, metadata tags and word-level `<mm:ss.xx>` timings are
handled. The `lyrics` table stores `status`, `source`, `sourceId`, plain text, the parsed lines
(JSON), `isInstrumental`, `fetchedAt` and `updatedAt`. Provider _failures_ are never cached as "no
lyrics"; a later request tries again.

## App

- `LyricsRepositoryImpl` serves the device cache first and fetches only when needed; cached lyrics
  work offline. Downloading a track for offline use also fetches its lyrics.
- `LyricsSync.currentLine()` finds the current line by binary search over the player position
  (updated a few times a second while playing — no separate timer).
- The lyrics screen (Player → Lyrics) keeps the current line just above centre, emphasised, with
  past and upcoming lines dimmed; tapping a line seeks there. Scrolling by hand pauses auto-scroll
  for a few seconds. Plain lyrics scroll freely; instrumental and "no lyrics" have their own states.
