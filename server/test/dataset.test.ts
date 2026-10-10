import { describe, expect, it } from 'vitest';
import { buildDataset, type LogRow, toCsv } from '../src/stats/dataset.js';
import { Zone } from '../src/stats/zone.js';

const MIN = 60_000;
/** Friday 9 October 2026, 20:00 UTC. */
const T = Date.UTC(2026, 9, 9, 20);

function row(
  id: number,
  trackId: string,
  startedAt: number,
  kind = 'play',
  context: object | null = null,
): LogRow {
  return {
    id,
    trackId,
    kind,
    startedAt,
    listenedMs: kind === 'skip' ? 5_000 : 200_000,
    title: `Song ${trackId}`,
    artist: 'Tool',
    artistId: 'ar-tool',
    album: 'Lateralus',
    albumId: 'al-1',
    year: 2001,
    genres: ['Progressive Metal', 'Rock'],
    durationMs: 400_000,
    context: context ? JSON.stringify(context) : null,
  };
}

describe('training dataset', () => {
  it('adds sessions, the song before and what happened to the song and artist before', () => {
    const records = buildDataset(
      [
        row(1, 'a', T),
        row(2, 'b', T + 5 * MIN, 'skip'),
        row(3, 'a', T + 6 * MIN),
        // An hour later: a new session.
        row(4, 'b', T + 70 * MIN, 'skip', {
          v: 1,
          source: 'smart',
          shuffle: true,
          utcOffsetMinutes: 120,
        }),
      ],
      new Zone('UTC'),
    );
    expect(records.map((r) => r.session_id)).toEqual([1, 1, 1, 2]);
    expect(records.map((r) => r.session_position)).toEqual([0, 1, 2, 0]);
    expect(records[2]).toMatchObject({
      previous_track_id: 'b',
      previous_kind: 'skip',
      track_plays_before: 1,
    });
    expect(records[3]).toMatchObject({
      previous_track_id: null,
      track_skips_before: 1,
      artist_plays_before: 2,
      artist_skips_before: 1,
      skipped: true,
      early_skip: true,
      has_context: true,
      source: 'smart',
      shuffle: true,
      // The phone's offset wins over the zone: 21:10 UTC is 23:10 at +2.
      local_hour: 23,
      weekday: 4,
    });
    expect(records[0]).toMatchObject({
      has_context: false,
      source: null,
      local_hour: 20,
      listened_fraction: 0.5,
    });
  });

  it('writes CSV that spreadsheets and pandas can read', () => {
    const csv = toCsv(
      buildDataset([{ ...row(1, 'a', T), title: 'Hello, "World"' }], new Zone('UTC')),
    );
    const [header, line] = csv.trim().split('\n');
    expect(header?.startsWith('id,started_at,')).toBe(true);
    expect(line).toContain('"Hello, ""World"""');
    expect(line).toContain('Progressive Metal|Rock');
    expect(toCsv([])).toBe('');
  });
});
