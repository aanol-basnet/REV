import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { exampleFrom } from '../src/model/service.js';
import { buildDataset } from '../src/stats/dataset.js';
import { classifyListen, type ListenFacts, outcomeOf } from '../src/stats/outcomes.js';
import { Zone } from '../src/stats/zone.js';

const fixtures = JSON.parse(
  readFileSync(new URL('../../shared/model/outcome-fixtures.json', import.meta.url), 'utf8'),
) as { cases: { name: string; facts: ListenFacts; outcome: string }[] };

describe('listen outcomes', () => {
  it.each(fixtures.cases)('$name: $outcome', ({ facts, outcome }) => {
    expect(classifyListen(facts)).toBe(outcome);
  });

  it('uses the outcome the app sent, else works one out from older listens', () => {
    const row = { kind: 'skip', listenedMs: 4_000, durationMs: 200_000 };
    expect(outcomeOf({ ...row, context: null })).toBe('rejected');
    expect(outcomeOf({ ...row, listenedMs: 90_000, context: null })).toBe('bored');
    expect(outcomeOf({ ...row, kind: 'play', listenedMs: 200_000, context: null })).toBe('played');
    expect(outcomeOf({ ...row, context: { v: 1, outcome: 'interrupted' } })).toBe('interrupted');
    // Something the server doesn't know yet: worked out instead.
    expect(outcomeOf({ ...row, context: { v: 1, outcome: 'danced' } })).toBe('rejected');
  });

  it('trains only on listens that say something about the song, weighted by how much', () => {
    const row = (id: number, outcome: string | null, kind = 'skip', listenedMs = 4_000) => ({
      id,
      trackId: `t${id}`,
      kind,
      startedAt: id * 60_000,
      listenedMs,
      title: 't',
      artist: 'A',
      artistId: 'ar-a',
      album: 'x',
      albumId: null,
      year: null,
      genres: [],
      durationMs: 200_000,
      context: outcome ? JSON.stringify({ v: 1, outcome }) : null,
    });
    const records = buildDataset(
      [
        row(1, 'interrupted'),
        row(2, 'rejected'),
        row(3, null, 'skip', 90_000),
        row(4, 'saved', 'play'),
      ],
      new Zone('UTC'),
    );
    const examples = records.map(exampleFrom);
    expect(examples[0]).toBeNull();
    expect(examples.slice(1).map((e) => [e?.label, e?.weight])).toEqual([
      [1, 1],
      [1, 0.5],
      [0, 1.5],
    ]);
    // The interruption doesn't count as a skip of the artist before the next listen.
    expect(records[1]?.artist_skips_before).toBe(0);
    expect(records.map((r) => r.outcome)).toEqual(['interrupted', 'rejected', 'bored', 'saved']);
  });
});
