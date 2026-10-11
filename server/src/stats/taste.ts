import type { TasteProfile } from '@rev/schemas';
import type { PlayRow } from './report.js';
import { OUTCOME_SIGNAL, outcomeOf } from './outcomes.js';
import { DAY_MS, type Zone } from './zone.js';

/** A listen's weight halves every this many days, so the profile follows changing taste. */
const HALF_LIFE_DAYS = 180;
/**
 * What each interaction says, as plays (positive) or skips (negative) of the song's artist and
 * genres: adding to a playlist or favoriting says a lot; queueing it says a little; taking it out
 * of the queue unheard is a mild no.
 */
const INTERACTION_WEIGHTS: Record<string, number> = {
  playlist_add: 1.5,
  favorite: 2,
  repeat_one: 1,
  play_next: 0.5,
  queue_add: 0.5,
  lyrics: 0.3,
  unfavorite: -1,
  queue_remove: -0.5,
};
const MAX_ARTISTS = 400;
const MAX_GENRES = 100;
const MAX_TRACKS = 3000;
const DAYPART_HOURS = 4;
/** Plays a part of the day needs before it says anything, and a genre or artist within it. */
const MIN_DAYPART_PLAYS = 20;
const MIN_DAYPART_ITEM_PLAYS = 3;
const MAX_DAYPART_ITEMS = 15;
/** Plays a listening mode needs before it says anything, and a genre or artist within it. */
const MIN_MODE_PLAYS = 10;
const MIN_MODE_ITEM_PLAYS = 2;

/** An interaction with a song, with its artist and genres. */
export interface InteractionRow {
  kind: string;
  trackId: string;
  at: number;
  artistId: string | null;
  genres: string[];
}

interface Tally {
  plays: number;
  skips: number;
}

/**
 * Turns the listening log into a taste profile: per artist, genre and decade a score from
 * recency-weighted plays and skips (weighed by how each listen went, plus interactions such as
 * playlist adds), per song its counts, and per part of the day (and per
 * listening mode) the genres and artists played more or less than usual then. Times of day come
 * from [timesOfDay].
 */
export function buildTaste(
  rows: PlayRow[],
  zone: Zone,
  now: number,
  interactions: InteractionRow[] = [],
): Omit<TasteProfile, 'timesOfDay'> {
  const artists = new Map<string, Tally>();
  const genres = new Map<string, Tally & { name: string }>();
  const decades = new Map<number, Tally>();
  const tracks = new Map<string, { plays: number; skips: number; last: number }>();
  const parts = Array.from({ length: 24 / DAYPART_HOURS }, () => ({
    plays: 0,
    genres: new Map<string, number>(),
    artists: new Map<string, number>(),
  }));
  const overall = {
    plays: 0,
    genres: new Map<string, number>(),
    artists: new Map<string, number>(),
  };
  const modes = new Map<string, typeof overall>();

  for (const row of rows) {
    const ageDays = Math.max(0, now - row.startedAt) / DAY_MS;
    // How it went decides what it says: an interruption or browsing says nothing, a rejection
    // more than getting bored late, a save more than a play.
    const signal = OUTCOME_SIGNAL[row.outcome ?? outcomeOf({ ...row, context: null })];
    if (signal.label === null) continue;
    const played = signal.label === 0;
    const weight = 0.5 ** (ageDays / HALF_LIFE_DAYS) * signal.weight;
    const add = (tally: Tally) => {
      if (played) tally.plays += weight;
      else tally.skips += weight;
    };

    if (row.artistId) add(entry(artists, row.artistId, () => ({ plays: 0, skips: 0 })));
    for (const genre of row.genres) {
      add(entry(genres, genre.toLowerCase(), () => ({ name: genre, plays: 0, skips: 0 })));
    }
    if (row.year)
      add(entry(decades, Math.floor(row.year / 10) * 10, () => ({ plays: 0, skips: 0 })));

    const track = entry(tracks, row.trackId, () => ({ plays: 0, skips: 0, last: 0 }));
    if (played) track.plays += 1;
    else track.skips += 1;
    track.last = Math.max(track.last, row.startedAt);

    if (played && row.mode) {
      const mode = entry(modes, row.mode, () => ({
        plays: 0,
        genres: new Map(),
        artists: new Map(),
      }));
      mode.plays += 1;
      for (const genre of row.genres) bump(mode.genres, genre.toLowerCase());
      if (row.artistId) bump(mode.artists, row.artistId);
    }
    if (played) {
      const part = parts[Math.floor(zone.hour(row.startedAt) / DAYPART_HOURS)];
      for (const target of part ? [part, overall] : [overall]) {
        target.plays += 1;
        for (const genre of row.genres) bump(target.genres, genre.toLowerCase());
        if (row.artistId) bump(target.artists, row.artistId);
      }
    }
  }

  // Things done with songs count like plays (or skips) of them, by how much they say.
  for (const i of interactions) {
    const value = INTERACTION_WEIGHTS[i.kind];
    if (value === undefined) continue;
    const weight = 0.5 ** (Math.max(0, now - i.at) / DAY_MS / HALF_LIFE_DAYS) * Math.abs(value);
    const add = (tally: Tally) => {
      if (value > 0) tally.plays += weight;
      else tally.skips += weight;
    };
    if (i.artistId) add(entry(artists, i.artistId, () => ({ plays: 0, skips: 0 })));
    for (const genre of i.genres) {
      add(entry(genres, genre.toLowerCase(), () => ({ name: genre, plays: 0, skips: 0 })));
    }
  }

  const byStrength = <T extends { score: number }>(a: T, b: T) =>
    Math.abs(b.score) - Math.abs(a.score);
  return {
    generatedAt: now,
    listens: rows.length,
    artists: [...artists]
      .map(([id, t]) => ({ id, score: score(t), volume: t.plays + t.skips }))
      .sort((a, b) => b.volume - a.volume)
      .slice(0, MAX_ARTISTS)
      .map(({ id, score }) => ({ id, score })),
    genres: [...genres.values()]
      .map((t) => ({ name: t.name, score: score(t), volume: t.plays + t.skips }))
      .sort((a, b) => b.volume - a.volume)
      .slice(0, MAX_GENRES)
      .map(({ name, score }) => ({ name, score })),
    decades: [...decades]
      .map(([decade, t]) => ({ decade, score: score(t) }))
      .sort((a, b) => a.decade - b.decade),
    tracks: [...tracks]
      .sort(([, a], [, b]) => b.last - a.last)
      .slice(0, MAX_TRACKS)
      .map(([id, t]) => ({ id, plays: t.plays, skips: t.skips })),
    dayparts: parts.map((part, i) => {
      const lifts = (counts: Map<string, number>, all: Map<string, number>) =>
        part.plays < MIN_DAYPART_PLAYS
          ? []
          : [...counts]
              .filter(([, n]) => n >= MIN_DAYPART_ITEM_PLAYS)
              .map(([key, n]) => ({
                key,
                score: lift(n / part.plays, (all.get(key) ?? n) / overall.plays),
              }))
              .sort(byStrength)
              .slice(0, MAX_DAYPART_ITEMS);
      return {
        fromHour: i * DAYPART_HOURS,
        genres: lifts(part.genres, overall.genres).map(({ key, score }) => ({
          name: genres.get(key)?.name ?? key,
          score,
        })),
        artists: lifts(part.artists, overall.artists).map(({ key, score }) => ({ id: key, score })),
      };
    }),
    modes: [...modes]
      .filter(([, m]) => m.plays >= MIN_MODE_PLAYS)
      .map(([mode, m]) => {
        const lifts = (counts: Map<string, number>, all: Map<string, number>) =>
          [...counts]
            .filter(([, n]) => n >= MIN_MODE_ITEM_PLAYS)
            .map(([key, n]) => ({
              key,
              score: lift(n / m.plays, (all.get(key) ?? n) / overall.plays),
            }))
            .sort(byStrength)
            .slice(0, MAX_DAYPART_ITEMS);
        return {
          mode,
          genres: lifts(m.genres, overall.genres).map(({ key, score }) => ({
            name: genres.get(key)?.name ?? key,
            score,
          })),
          artists: lifts(m.artists, overall.artists).map(({ key, score }) => ({ id: key, score })),
        };
      }),
  };
}

/**
 * Plays against skips, pulled toward 0 when there's little to go on: one play is 0.33, twenty
 * plays and no skips 0.91, as many skips as plays 0.
 */
function score({ plays, skips }: Tally): number {
  return round((plays - skips) / (plays + skips + 2));
}

/** How much more (up to 1) or less (down to -1) common something is in a part of the day. */
export function lift(share: number, usual: number): number {
  return round(Math.max(-1, Math.min(1, Math.log2(share / usual))));
}

export function round(n: number): number {
  return Math.round(n * 1000) / 1000;
}

function entry<K, V>(map: Map<K, V>, key: K, create: () => V): V {
  let value = map.get(key);
  if (value === undefined) {
    value = create();
    map.set(key, value);
  }
  return value;
}

function bump(map: Map<string, number>, key: string): void {
  map.set(key, (map.get(key) ?? 0) + 1);
}
