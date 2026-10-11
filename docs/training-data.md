# Listening data for training

REV logs every play and skip in the server's database (`plays` table in the SQLite file set by
`REV_DATABASE_URL`). Since app 0.5.0, each listen also carries a **context**: the situation it
was played in and what you did while it played. Together they are a dataset for training the smart
queue: given a song and a situation, will you listen or skip?

Nothing here leaves your server. The database is never part of the repo.

## Exporting

```sh
cd server
npm run export-listens -- <username> --out listens.csv              # from source
node dist/cli/export-listens.js <username> --format jsonl --out listens.jsonl
```

Options: `--format csv|jsonl` (default csv), `--tz Area/City` for local times of listens without
context (default: this machine's zone), `--out file` (default: stdout). It only reads the database.

## Columns

One row per listen, oldest first. Booleans are `1`/`0` in CSV; empty means unknown.

### The listen (the label)

| Column                             | Meaning                                                           |
| ---------------------------------- | ----------------------------------------------------------------- |
| `kind`, `skipped`                  | `play` (heard long enough to count) or `skip`                     |
| `early_skip`                       | Skipped within 30 s: a strong "not now"                           |
| `outcome`                          | How it went (below)                                               |
| `label`, `label_weight`            | What the skip model learns from it: 1 skip / 0 not, and how much  |
| `listened_ms`, `listened_fraction` | How much was actually heard (seeking doesn't count)               |
| `favorited_during`                 | Favorited (1) or unfavorited (0) while it played: a strong signal |

#### Outcomes

The app reads each listen as it ends (`ListenOutcomes.kt`, the same as the server's
`server/src/stats/outcomes.ts`; `shared/model/outcome-fixtures.json` keeps them identical).
Listens from before it did are read from `kind` and `listened_ms` alone.

| Outcome       | When                                                                                                                   | Label, weight |
| ------------- | ---------------------------------------------------------------------------------------------------------------------- | ------------- |
| `saved`       | Added to a playlist while it played                                                                                    | 0, 1.5        |
| `replayed`    | You went back to it (previous) and it counted                                                                          | 0, 1.5        |
| `completed`   | Played to the end, or left in its last 15 s                                                                            | 0, 1          |
| `outro`       | Counted, then left with more than 15 s to go: skipping a long outro                                                    | 0, 1          |
| `played`      | Counted; how it ended isn't known                                                                                      | 0, 1          |
| `bored`       | Left after the first 30 s (or half a short song), before it counted                                                    | 1, 0.5        |
| `rejected`    | Left within the first 30 s                                                                                             | 1, 1          |
| `interrupted` | Left while paused 20 s+, soon after a pause of a minute+ or one the phone made (call, unplug), or stopped while paused | none          |
| `browsing`    | Left within 30 s while adding songs to playlists (one in the last 10 min)                                              | none          |
| `went_back`   | Left for the song before, before it counted                                                                            | none          |

"None" means it says nothing about the song: it isn't learned from, doesn't count in the taste
profile, and isn't a skip in the session's or song's history.

### The song

`track_id`, `title`, `artist`, `artist_id`, `album`, `album_id`, `year`, `genres` (joined with `|`),
`duration_ms`.

### When

| Column                                | Meaning                                                     |
| ------------------------------------- | ----------------------------------------------------------- |
| `started_at`, `started_at_ms`         | UTC                                                         |
| `local_date`, `local_hour`, `weekday` | Local time (0 = Monday), from the phone's offset when known |
| `utc_offset_minutes`                  | The phone's offset from UTC                                 |

### Worked out from the log (every row, old ones too)

| Column                                                         | Meaning                                      |
| -------------------------------------------------------------- | -------------------------------------------- |
| `session_id`, `session_position`                               | Sessions break after 30 min without a listen |
| `session_skips_before`                                         | Skips earlier in the session                 |
| `previous_track_id`, `previous_kind`, `minutes_since_previous` | The listen before, within the session        |
| `track_plays_before`, `track_skips_before`                     | Your history with this song up to then       |
| `artist_plays_before`, `artist_skips_before`                   | Your history with this artist up to then     |

### Recorded by the app (`has_context` = 1)

| Column                                  | Meaning                                                                                                                                                                      |
| --------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `source`                                | How it got into the queue: `picked`, `queued` (Play next / Add to queue), `autoplay`, `smart`, `restored`, `car`, `unknown`                                                  |
| `mode`, `mode_name`, `mode_source`      | The listening mode (`driving_solo`, `driving_family`, `work`, `home`, `jam`, or `custom:...` for your own), its name, and whether you `picked` it or REV guessed it (`auto`) |
| `smart_change`                          | For `smart`: what the smart queue did (`MoreLikeThis`, `OtherArtist`, ...)                                                                                                   |
| `queue_shape`, `shuffle`, `repeat`      | `album`, `artist` or `mixed`; shuffle on; `off`/`one`/`all`                                                                                                                  |
| `queue_position`, `queue_length`        | Place in the play order and the queue's size                                                                                                                                 |
| `previous_outcome`, `session_skips`     | How the song before ended (`play`, `skip`, `none`); skips so far this session                                                                                                |
| `end_reason`                            | How you left: `next`, `previous`, `jump` (picked another song in the queue), `new_queue`, `stopped`, or `ended` (played out)                                                 |
| `output`, `output_name`                 | `speaker`, `headphones`, `bluetooth`, `car` (Android Auto), `cast`; the device's name (car kit, earbuds)                                                                     |
| `network`, `charging`, `screen_on`      | `wifi`/`cellular`/`none`; plugged in; screen on when it started                                                                                                              |
| `volume`, `volume_end`                  | Media volume (0–1) when it started and when the listen was logged                                                                                                            |
| `start_position_ms`, `end_position_ms`  | Where it started (not 0 when resumed) and where you were when it was logged (left, for a skip)                                                                               |
| `seeks_forward`, `seeked_forward_ms`    | Skipping ahead within the song (impatience, skipping an intro)                                                                                                               |
| `seeks_back`, `seeked_back_ms`          | Going back within the song (replaying a part you love)                                                                                                                       |
| `pauses`, `paused_ms`                   | Pauses you made                                                                                                                                                              |
| `paused_at_end`, `last_pause_ms`        | Paused when you left it; the most recent pause's length (yours or the phone's)                                                                                               |
| `heard_since_resume_ms`, `system_pause` | Heard after that pause; whether the phone made it (a call, unplugged headphones, another app's sound)                                                                        |
| `arrived_by`                            | How the song began: `auto`, `next`, `previous`, `jump`, `repeat`, `new_queue`                                                                                                |
| `added_to_playlist`, `curating`         | Added to a playlist while it played; songs added to playlists in the last 10 minutes                                                                                         |
| `predicted_score`                       | The smart queue's score for the song when it started: compare its guess with what you did                                                                                    |
| `predicted_skip`                        | The phone's personal model's chance of skipping it when it started                                                                                                           |
| `feedback`                              | A feedback button pressed during it: `not_now` or `more_like_this`                                                                                                           |

Plays are logged once they count (half the song or 4 minutes), then sent again when the song
ends, with its outcome; the server updates the row, so the behaviour columns cover the whole
listen (for plays that never ended, such as the app being killed, up to when they counted).

### Interactions

Things done with songs besides listening are kept too (`interactions` table, `POST
/api/stats/interactions`): `playlist_add`, `favorite`, `unfavorite`, `play_next`, `queue_add`,
`queue_remove` (taken out of the queue before it played), `repeat_one` and `lyrics`, with the
song's artist and genres. The taste profile counts them like plays or skips of the artist and
genres (a favorite 2, a playlist add 1.5, repeat one 1, queueing 0.5, lyrics 0.3, unfavoriting
−1, removing from the queue −0.5).

## The personal skip model

REV already trains one model from this data: logistic regression predicting "will you skip it",
over named features of the song, the situation and your history, plus combinations such as an
artist in the car or a genre late at night (`server/src/model/features.ts`, the same in the app's
`SkipFeatures.kt`; `shared/model/feature-fixtures.json` keeps them identical).

- **Modes:** the listening mode is a feature (`m:work`) and is combined with the artist, the
  genres and how the song before ended, so the model learns your taste per activity.
- **Labels:** the outcome's label and weight (above); listens with none are left out. "Not now"
  is 1 and "More like this" 0, counting double, whatever the outcome.
- **Training** (server): AdaGrad with L2. It tunes itself: on all but the newest 20% of listens,
  it tries 18 combinations of learning rate, L2 and epochs plus the last model's settings, each
  trained on the oldest four-fifths and judged on the rest, and keeps the best (the last settings
  stay unless something beats them). With those it trains on all but the newest 20% and tests on
  those, then trains on everything. Each model keeps its `settings` and a `history` of runs (log
  loss, AUC) so you can see it improve. The server retrains a minute after it
  starts and then every 3 hours when there's anything new. To run it by hand and see how it does:

  ```sh
  cd server
  npm run train-model -- <username>          # or: node dist/cli/train-model.js <username>
  ```

  It prints log loss against always guessing the usual skip rate, AUC (0.5 chance, 1 perfect)
  next to the smart queue's hand-tuned score and the phone model's own guesses, and the strongest
  signals it found.

- **On the phone:** the app fetches the model (`GET /api/stats/model`, cached for offline use)
  and keeps training it with every listen and feedback button, the same way, online or offline.
  It keeps those examples, so when a newer model arrives it replays the ones that model hasn't
  seen (`dataUntil`). The smart queue and autoplay add the model's chance of a skip to their
  score, with more say the more it has learned (full say from 1,000 listens).

## Notes for training

- **Labels:** `label` / `label_weight` (from `outcome`) for "will I skip it", leaving out rows
  without one; `listened_fraction` for "how much will I listen"; `favorited_during` and
  `added_to_playlist` as strong positives.
- **Don't leak the future:** `*_before` columns only count earlier listens; keep it that way when
  adding features (split train/test by time, not at random).
- **Selection bias:** you only rate songs that were put in front of you. `source` and
  `predicted_score` say how a song got there, so a model can tell "skipped what the smart queue
  pushed" from "skipped what I picked".
- **Old rows** (`has_context` = 0) still have the song, time, session and history columns.
