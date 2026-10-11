import type { TimeOfDay } from '@rev/schemas';
import type { PlayRow } from './report.js';
import { OUTCOME_SIGNAL, outcomeOf } from './outcomes.js';
import { lift, round } from './taste.js';
import { DAY_MS, type Zone } from './zone.js';

/** Recent listens count more: a listen's weight halves every this many days. */
const HALF_LIFE_DAYS = 60;
/** Plays a part of the day needs before it says anything. */
const MIN_PLAYS = 8;
/** Plays an artist or genre needs within a part of the day before its lift counts. */
const MIN_ITEM_PLAYS = 2;
const MAX_TRACKS = 60;
const MAX_ITEMS = 15;

/** The part of the day [hour] (local, 0–23) falls in: morning 5–12, afternoon 12–17, evening 17–22, night 22–5. */
export function timeOfDay(hour: number): TimeOfDay {
  if (hour >= 5 && hour < 12) return 'morning';
  if (hour >= 12 && hour < 17) return 'afternoon';
  if (hour >= 17 && hour < 22) return 'evening';
  return 'night';
}

export const TIMES_OF_DAY: readonly TimeOfDay[] = ['morning', 'afternoon', 'evening', 'night'];

/** A song as played at one time of day. */
export interface TimeTrack {
  id: string;
  score: number;
  artist: string;
  coverArtId: string | null;
}

export interface TimeTaste {
  time: TimeOfDay;
  plays: number;
  /** Best first. */
  tracks: TimeTrack[];
  /** Songs mostly skipped at this time: kept out of its mix. */
  skipped: string[];
  genres: { name: string; score: number }[];
  artists: { id: string; score: number }[];
}

/**
 * What you listen to in each part of the day. A song's score is its recent plays then, minus
 * skips then, times how much it belongs to that time (a song you play only in the morning beats
 * one you play all day). Genres and artists are scored by how much more (or less) common they are
 * then than usual. Parts with fewer than [MIN_PLAYS] plays are left out.
 */
export function timesOfDay(rows: PlayRow[], zone: Zone, now: number): TimeTaste[] {
  interface Song {
    net: number;
    plays: number;
    artist: string;
    coverArtId: string | null;
  }
  const parts = new Map(
    TIMES_OF_DAY.map((time) => [
      time,
      {
        plays: 0,
        songs: new Map<string, Song>(),
        genres: new Map<string, number>(),
        artists: new Map<string, number>(),
      },
    ]),
  );
  const songPlays = new Map<string, number>();
  const overall = {
    plays: 0,
    genres: new Map<string, number>(),
    artists: new Map<string, number>(),
  };
  const genreNames = new Map<string, string>();

  for (const row of rows) {
    const part = parts.get(timeOfDay(zone.hour(row.startedAt)));
    if (!part) continue;
    const signal = OUTCOME_SIGNAL[row.outcome ?? outcomeOf({ ...row, context: null })];
    // Interrupted or browsing: says nothing about the song.
    if (signal.label === null) continue;
    const weight =
      0.5 ** (Math.max(0, now - row.startedAt) / DAY_MS / HALF_LIFE_DAYS) * signal.weight;
    const played = signal.label === 0;
    let song = part.songs.get(row.trackId);
    if (!song) {
      song = { net: 0, plays: 0, artist: row.artist, coverArtId: row.coverArtId };
      part.songs.set(row.trackId, song);
    }
    if (played) {
      song.net += weight;
      song.plays += 1;
      songPlays.set(row.trackId, (songPlays.get(row.trackId) ?? 0) + 1);
      part.plays += 1;
      overall.plays += 1;
      for (const genre of row.genres) {
        const key = genre.toLowerCase();
        genreNames.set(key, genre);
        bump(part.genres, key);
        bump(overall.genres, key);
      }
      if (row.artistId) {
        bump(part.artists, row.artistId);
        bump(overall.artists, row.artistId);
      }
    } else {
      song.net -= weight;
    }
  }

  const byStrength = <T extends { score: number }>(a: T, b: T) =>
    Math.abs(b.score) - Math.abs(a.score);
  const lifts = (counts: Map<string, number>, all: Map<string, number>, plays: number) =>
    [...counts]
      .filter(([, n]) => n >= MIN_ITEM_PLAYS)
      .map(([key, n]) => ({ key, score: lift(n / plays, (all.get(key) ?? n) / overall.plays) }))
      .sort(byStrength)
      .slice(0, MAX_ITEMS);

  return TIMES_OF_DAY.flatMap((time) => {
    const part = parts.get(time);
    if (!part || part.plays < MIN_PLAYS) return [];
    const songs = [...part.songs];
    return [
      {
        time,
        plays: part.plays,
        tracks: songs
          .filter(([, s]) => s.net > 0)
          .map(([id, s]) => {
            // Smoothed share of the song's plays that happen at this time.
            const belongs = (s.plays + 0.5) / ((songPlays.get(id) ?? 0) + 2);
            return {
              id,
              score: round(s.net * (0.5 + belongs)),
              artist: s.artist,
              coverArtId: s.coverArtId,
            };
          })
          .filter((t) => t.score > 0)
          .sort((a, b) => b.score - a.score)
          .slice(0, MAX_TRACKS),
        skipped: songs.filter(([, s]) => s.net < 0).map(([id]) => id),
        genres: lifts(part.genres, overall.genres, part.plays).map(({ key, score }) => ({
          name: genreNames.get(key) ?? key,
          score,
        })),
        artists: lifts(part.artists, overall.artists, part.plays).map(({ key, score }) => ({
          id: key,
          score,
        })),
      },
    ];
  });
}

function bump(map: Map<string, number>, key: string): void {
  map.set(key, (map.get(key) ?? 0) + 1);
}
