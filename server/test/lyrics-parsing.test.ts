import { describe, expect, it } from 'vitest';
import { parseLrc, plainFromLines } from '../src/lyrics/lrc.js';
import { scoreCandidate } from '../src/lyrics/match.js';

describe('LRC parsing', () => {
  it('parses timestamps, ignores metadata and sorts', () => {
    const lines = parseLrc(
      [
        '[ar:Tool]',
        '[ti:Schism]',
        '[00:12.34] I know the pieces fit',
        '[00:05.5]Intro',
        '',
        '[01:02.005]Late',
      ].join('\n'),
    );
    expect(lines).toEqual([
      { startMs: 5_500, text: 'Intro' },
      { startMs: 12_340, text: 'I know the pieces fit' },
      { startMs: 62_005, text: 'Late' },
    ]);
  });

  it('expands repeated timestamps and keeps gaps', () => {
    expect(parseLrc('[00:10.00][00:30.00]Chorus\n[00:20.00]')).toEqual([
      { startMs: 10_000, text: 'Chorus' },
      { startMs: 20_000, text: '' },
      { startMs: 30_000, text: 'Chorus' },
    ]);
  });

  it('applies the offset tag and strips word timings', () => {
    expect(parseLrc('[offset:+500]\n[00:10.00]<00:10.00>Hello <00:10.50>world')).toEqual([
      { startMs: 9_500, text: 'Hello world' },
    ]);
  });

  it('returns nothing for unsynced text', () => {
    expect(parseLrc('Just some words\nNo times here')).toEqual([]);
  });

  it('derives plain text', () => {
    expect(
      plainFromLines([
        { startMs: 0, text: 'A' },
        { startMs: 1, text: '' },
        { startMs: 2, text: 'B' },
      ]),
    ).toBe('A\n\nB');
  });
});

describe('lyrics matching', () => {
  const schism = { title: 'Schism', artist: 'Tool', album: 'Lateralus', durationSec: 403 };

  it('accepts the same song with messy credits and edition suffixes', () => {
    expect(
      scoreCandidate(schism, {
        title: 'Schism',
        artist: 'TOOL;Tool',
        album: 'Lateralus',
        durationSec: 404,
      }),
    ).toBe(155);
    expect(
      scoreCandidate(
        {
          title: 'Stargazing (feat. X)',
          artist: 'Kygo feat. Justin Jesso',
          album: null,
          durationSec: 220,
        },
        { title: 'Stargazing', artist: 'Kygo', album: 'Stargazing - EP', durationSec: 223 },
      ),
    ).toBe(120);
    expect(
      scoreCandidate(schism, {
        title: 'Schism (2021 Remaster)',
        artist: 'Tool',
        album: null,
        durationSec: null,
      }),
    ).toBe(100);
  });

  it('ranks closer durations and matching albums higher', () => {
    const exact = scoreCandidate(schism, { ...schism, durationSec: 403 }) ?? 0;
    const near = scoreCandidate(schism, { ...schism, album: 'Live', durationSec: 408 }) ?? 0;
    expect(exact).toBeGreaterThan(near);
  });

  it('rejects clearly different songs', () => {
    expect(
      scoreCandidate(schism, {
        title: 'Schism (Live)',
        artist: 'Tool',
        album: null,
        durationSec: 403,
      }),
    ).toBeNull();
    expect(
      scoreCandidate(schism, {
        title: 'Schism',
        artist: 'Someone Else',
        album: null,
        durationSec: 403,
      }),
    ).toBeNull();
    expect(
      scoreCandidate(schism, { title: 'Schism', artist: 'Tool', album: null, durationSec: 600 }),
    ).toBeNull();
    expect(
      scoreCandidate(schism, {
        title: 'Parabola',
        artist: 'Tool',
        album: 'Lateralus',
        durationSec: 403,
      }),
    ).toBeNull();
  });
});
