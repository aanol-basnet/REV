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
| `listened_ms`, `listened_fraction` | How much was actually heard (seeking doesn't count)               |
| `favorited_during`                 | Favorited (1) or unfavorited (0) while it played: a strong signal |

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

| Column                                 | Meaning                                                                                                                                                                      |
| -------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `source`                               | How it got into the queue: `picked`, `queued` (Play next / Add to queue), `autoplay`, `smart`, `restored`, `car`, `unknown`                                                  |
| `mode`, `mode_name`, `mode_source`     | The listening mode (`driving_solo`, `driving_family`, `work`, `home`, `jam`, or `custom:...` for your own), its name, and whether you `picked` it or REV guessed it (`auto`) |
| `smart_change`                         | For `smart`: what the smart queue did (`MoreLikeThis`, `OtherArtist`, ...)                                                                                                   |
| `queue_shape`, `shuffle`, `repeat`     | `album`, `artist` or `mixed`; shuffle on; `off`/`one`/`all`                                                                                                                  |
| `queue_position`, `queue_length`       | Place in the play order and the queue's size                                                                                                                                 |
| `previous_outcome`, `session_skips`    | How the song before ended (`play`, `skip`, `none`); skips so far this session                                                                                                |
| `end_reason`                           | For skips: `next`, `previous`, `jump` (picked another song in the queue), `new_queue`, `stopped`                                                                             |
| `output`, `output_name`                | `speaker`, `headphones`, `bluetooth`, `car` (Android Auto), `cast`; the device's name (car kit, earbuds)                                                                     |
| `network`, `charging`, `screen_on`     | `wifi`/`cellular`/`none`; plugged in; screen on when it started                                                                                                              |
| `volume`, `volume_end`                 | Media volume (0–1) when it started and when the listen was logged                                                                                                            |
| `start_position_ms`, `end_position_ms` | Where it started (not 0 when resumed) and where you were when it was logged (left, for a skip)                                                                               |
| `seeks_forward`, `seeked_forward_ms`   | Skipping ahead within the song (impatience, skipping an intro)                                                                                                               |
| `seeks_back`, `seeked_back_ms`         | Going back within the song (replaying a part you love)                                                                                                                       |
| `pauses`, `paused_ms`                  | Pauses you made                                                                                                                                                              |
| `predicted_score`                      | The smart queue's score for the song when it started: compare its guess with what you did                                                                                    |
| `predicted_skip`                       | The phone's personal model's chance of skipping it when it started                                                                                                           |
| `feedback`                             | A feedback button pressed during it: `not_now` or `more_like_this`                                                                                                           |

Plays are logged once they count (half the song or 4 minutes), so for plays the behaviour
columns cover the song up to that point.

## The personal skip model

REV already trains one model from this data: logistic regression predicting "will you skip it",
over named features of the song, the situation and your history, plus combinations such as an
artist in the car or a genre late at night (`server/src/model/features.ts`, the same in the app's
`SkipFeatures.kt`; `shared/model/feature-fixtures.json` keeps them identical).

- **Modes:** the listening mode is a feature (`m:work`) and is combined with the artist, the
  genres and how the song before ended, so the model learns your taste per activity.
- **Labels:** a skip is 1, a play 0; "Not now" is 1 and "More like this" 0, counting double.
- **Training** (server): AdaGrad with a little L2. It first trains on all but the newest 20% of
  listens and tests on those, then trains on everything. The server retrains a minute after it
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

- **Labels:** `skipped` / `early_skip` for "will I skip it", `listened_fraction` for "how much will
  I listen", `favorited_during` as a strong positive.
- **Don't leak the future:** `*_before` columns only count earlier listens; keep it that way when
  adding features (split train/test by time, not at random).
- **Selection bias:** you only rate songs that were put in front of you. `source` and
  `predicted_score` say how a song got there, so a model can tell "skipped what the smart queue
  pushed" from "skipped what I picked".
- **Old rows** (`has_context` = 0) still have the song, time, session and history columns.
