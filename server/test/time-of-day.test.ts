import { describe, expect, it } from 'vitest';
import type { PlayRow } from '../src/stats/report.js';
import { timeOfDay, timesOfDay } from '../src/stats/time-of-day.js';
import { weightedOrder } from '../src/home/time-mix.js';
import { seeded } from '../src/home/service.js';
import { Zone } from '../src/stats/zone.js';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const NOW = Date.UTC(2026, 9, 9, 12);
/** Midnight UTC, a few days back. */
const MIDNIGHT = Date.UTC(2026, 9, 5);

function row(trackId: string, hour: number, day = 0, kind: 'play' | 'skip' = 'play'): PlayRow {
  return {
    trackId,
    kind,
    startedAt: MIDNIGHT - day * DAY + hour * HOUR,
    listenedMs: kind === 'play' ? 200_000 : 4_000,
    title: trackId,
    artist: `Artist ${trackId}`,
    artistId: `ar-${trackId}`,
    album: 'x',
    albumId: null,
    coverArtId: `cov-${trackId}`,
    year: null,
    genres: [trackId.startsWith('m') ? 'Ambient' : 'Metal'],
    durationMs: 200_000,
  };
}

describe('time of day', () => {
  const utc = new Zone('UTC');

  it('splits the day into morning, afternoon, evening and night', () => {
    expect([4, 5, 11, 12, 16, 17, 21, 22, 0].map(timeOfDay)).toEqual([
      'night',
      'morning',
      'morning',
      'afternoon',
      'afternoon',
      'evening',
      'evening',
      'night',
      'night',
    ]);
  });

  it('ranks songs that belong to a time above songs played all day, and leaves out skipped ones', () => {
    const rows: PlayRow[] = [];
    for (let d = 0; d < 4; d++) {
      rows.push(
        row('m-only', 8, d),
        row('allday', 9, d),
        row('allday', 15, d),
        row('allday', 20, d),
      );
      rows.push(row('m-skip', 9, d, 'skip'), row('e-only', 19, d), row('e-only2', 20, d));
    }
    const times = timesOfDay(rows, utc, NOW);
    const morning = times.find((t) => t.time === 'morning');
    expect(morning?.plays).toBe(8);
    expect(morning?.tracks.map((t) => t.id)).toEqual(['m-only', 'allday']);
    expect(morning?.skipped).toEqual(['m-skip']);
    expect(morning?.genres.find((g) => g.name === 'Ambient')?.score).toBeGreaterThan(0);
    // The afternoon has only four plays: not enough to say anything.
    expect(times.map((t) => t.time)).toEqual(['morning', 'evening']);
  });

  it('counts recent listens more than old ones', () => {
    const rows: PlayRow[] = [];
    for (let i = 0; i < 4; i++) rows.push(row('new', 8, i), row('old', 8, 200 + i));
    const morning = timesOfDay(rows, utc, NOW).find((t) => t.time === 'morning');
    expect(morning?.tracks.map((t) => t.id)).toEqual(['new', 'old']);
  });

  it('orders a mix at random, favouring higher scores', () => {
    const random = seeded(7);
    let firsts = 0;
    for (let i = 0; i < 400; i++) {
      const order = weightedOrder(
        [
          { id: 'low', score: 1 },
          { id: 'high', score: 4 },
        ],
        random,
      );
      if (order[0] === 'high') firsts++;
    }
    // 4 / (4 + 1) of the time, roughly.
    expect(firsts).toBeGreaterThan(280);
    expect(firsts).toBeLessThan(360);
  });
});
