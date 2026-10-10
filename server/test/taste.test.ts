import { describe, expect, it } from 'vitest';
import type { PlayRow } from '../src/stats/report.js';
import { buildTaste } from '../src/stats/taste.js';
import { Zone } from '../src/stats/zone.js';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const NOW = Date.UTC(2026, 9, 9, 12);

function row(
  trackId: string,
  artist: string,
  startedAt: number,
  kind: 'play' | 'skip' = 'play',
  genres = ['Metal'],
  listenedMs = kind === 'play' ? 200_000 : 5_000,
): PlayRow {
  return {
    trackId,
    kind,
    startedAt,
    listenedMs,
    title: trackId,
    artist,
    artistId: `ar-${artist}`,
    album: 'x',
    albumId: null,
    coverArtId: null,
    year: 1999,
    genres,
    durationMs: 200_000,
  };
}

describe('taste profile', () => {
  const utc = new Zone('UTC');

  it('counts recent listens more than old ones', () => {
    const recent = buildTaste(
      [row('a', 'New', NOW - DAY), row('b', 'Old', NOW - 720 * DAY)],
      utc,
      NOW,
    );
    const score = (id: string) => recent.artists.find((a) => a.id === id)?.score ?? 0;
    expect(score('ar-New')).toBeGreaterThan(score('ar-Old'));
  });

  it('weighs a late skip less than an early one', () => {
    const taste = buildTaste(
      [
        row('a', 'Early', NOW - DAY, 'skip', ['Metal'], 2_000),
        row('b', 'Late', NOW - DAY, 'skip', ['Metal'], 120_000),
      ],
      utc,
      NOW,
    );
    const score = (id: string) => taste.artists.find((a) => a.id === id)?.score ?? 0;
    expect(score('ar-Early')).toBeLessThan(score('ar-Late'));
    expect(taste.decades).toEqual([{ decade: 1990, score: expect.any(Number) as number }]);
  });

  it('finds what is played more at a time of day', () => {
    const rows: PlayRow[] = [];
    for (let d = 1; d <= 25; d++) {
      rows.push(row(`m${d}`, 'Morning', NOW - d * DAY - 3 * HOUR, 'play', ['Jazz'])); // 09:00
      rows.push(row(`n${d}`, 'Night', NOW - d * DAY + 10 * HOUR, 'play', ['Metal'])); // 22:00
      rows.push(row(`o${d}`, 'Night', NOW - d * DAY + 11 * HOUR, 'play', ['Metal'])); // 23:00
    }
    const taste = buildTaste(rows, utc, NOW);
    const morning = taste.dayparts.find((p) => p.fromHour === 8);
    const night = taste.dayparts.find((p) => p.fromHour === 20);
    expect(morning?.genres.find((g) => g.name === 'Jazz')?.score).toBe(1);
    expect(night?.genres.find((g) => g.name === 'Metal')?.score).toBeGreaterThan(0);
    // Too few plays in the small hours to say anything.
    expect(taste.dayparts.find((p) => p.fromHour === 0)?.genres).toEqual([]);
  });

  it('learns what is played more in each listening mode', () => {
    const rows: PlayRow[] = [];
    for (let d = 1; d <= 12; d++) {
      rows.push({
        ...row(`c${d}`, 'Kids Band', NOW - d * DAY, 'play', ['Pop']),
        mode: 'driving_family',
      });
      rows.push({
        ...row(`w${d}`, 'Ambient One', NOW - d * DAY + HOUR, 'play', ['Ambient']),
        mode: 'work',
      });
      rows.push(row(`x${d}`, 'Metal Band', NOW - d * DAY + 2 * HOUR, 'play', ['Metal']));
    }
    const taste = buildTaste(rows, utc, NOW);
    const family = taste.modes.find((m) => m.mode === 'driving_family');
    expect(family?.genres.find((g) => g.name === 'Pop')?.score).toBe(1);
    expect(family?.artists.map((a) => a.id)).toEqual(['ar-Kids Band']);
    expect(taste.modes.map((m) => m.mode).sort()).toEqual(['driving_family', 'work']);
  });
});
