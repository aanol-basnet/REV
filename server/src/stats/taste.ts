import type { TasteProfile } from '@rev/schemas';
import type { PlayRow } from './report.js';
import { DAY_MS, type Zone } from './zone.js';

/** A listen's weight halves every this many days, so the profile follows changing taste. */
const HALF_LIFE_DAYS = 180;
/** A skip after this long says less about the song (you heard a good part of it). */
const EARLY_SKIP_MS = 30_000;
const MAX_ARTISTS = 400;
const MAX_GENRES = 100;
const MAX_TRACKS = 3000;
const DAYPART_HOURS = 4;
/** Plays a part of the day needs before it says anything, and a genre or artist within it. */
const MIN_DAYPART_PLAYS = 20;
const MIN_DAYPART_ITEM_PLAYS = 3;
const MAX_DAYPART_ITEMS = 15;

interface Tally {
  plays: number;
  skips: number;
}

/**
 * Turns the listening log into a taste profile: per artist, genre and decade a score from
 * recency-weighted plays and skips, per song its counts, and per part of the day the genres and
 * artists played more or less than usual then.
 */
export function buildTaste(rows: PlayRow[], zone: Zone, now: number): TasteProfile {
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

  for (const row of rows) {
    const ageDays = Math.max(0, now - row.startedAt) / DAY_MS;
    const weight = 0.5 ** (ageDays / HALF_LIFE_DAYS);
    const played = row.kind === 'play';
    const skipWeight = row.listenedMs < EARLY_SKIP_MS ? 1 : 0.5;
    const add = (tally: Tally) => {
      if (played) tally.plays += weight;
      else tally.skips += weight * skipWeight;
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

    if (played) {
      const part = parts[Math.floor(zone.hour(row.startedAt) / DAYPART_HOURS)];
      for (const target of part ? [part, overall] : [overall]) {
        target.plays += 1;
        for (const genre of row.genres) bump(target.genres, genre.toLowerCase());
        if (row.artistId) bump(target.artists, row.artistId);
      }
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
function lift(share: number, usual: number): number {
  return round(Math.max(-1, Math.min(1, Math.log2(share / usual))));
}

function round(n: number): number {
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
