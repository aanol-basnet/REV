import { afterEach, describe, expect, it } from 'vitest';
import { SaveSmartPlaylistResult, SmartPlaylistDetail, SmartPlaylists } from '@rev/schemas';
import { createTestApp, signedInHeaders, type TestContext } from './helpers.js';
import { NAVIDROME, subsonicError, subsonicOk } from './fake-navidrome.js';
import { playlistServer } from './fake-playlists.js';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
/** Friday 9 October 2026, 12:00 UTC. */
const NOW = Date.UTC(2026, 9, 9, 12);

const LIBRARY = new Set(Array.from({ length: 12 }, (_, i) => `s${i + 1}`));

describe('smart playlists', () => {
  let t: TestContext;
  afterEach(async () => {
    await t.close();
  });

  async function setup() {
    const server = playlistServer([], {
      getSong: (params) => {
        const id = params.get('id') ?? '';
        if (!LIBRARY.has(id)) return subsonicError(70, 'not found');
        return subsonicOk({
          song: {
            id,
            title: `Song ${id}`,
            artist: 'Tool',
            album: 'Lateralus',
            albumId: `al-${id}`,
            coverArt: `cov-${id}`,
            duration: 200,
          },
        });
      },
      scrobble: () => subsonicOk({}),
    });
    t = await createTestApp({ navidrome: NAVIDROME, fetch: server.nav.fetch, now: () => NOW });
    const headers = await signedInHeaders(t);
    return { ...server, headers };
  }

  /** Records [count] plays of each track, the last one [daysAgo] before now, a day apart. */
  async function listen(
    headers: Record<string, string>,
    plays: { trackId: string; count: number; daysAgo: number }[],
  ) {
    const listens = plays.flatMap(({ trackId, count, daysAgo }) =>
      Array.from({ length: count }, (_, i) => ({
        trackId,
        startedAt: NOW - (daysAgo + i) * DAY,
        kind: 'play' as const,
      })),
    );
    const res = await t.app.inject({
      method: 'POST',
      url: '/api/stats/listens',
      headers,
      payload: { listens },
    });
    expect(res.statusCode).toBe(200);
  }

  /** Lately: s2 and s1 three times, s3–s6 twice, s7 once. Last December: s8–s12 three times. */
  async function history(headers: Record<string, string>) {
    await listen(headers, [
      { trackId: 's1', count: 3, daysAgo: 2 },
      { trackId: 's2', count: 3, daysAgo: 1 },
      ...['s3', 's4', 's5', 's6'].map((trackId) => ({ trackId, count: 2, daysAgo: 3 })),
      { trackId: 's7', count: 1, daysAgo: 1 },
      ...['s8', 's9', 's10', 's11', 's12'].map((trackId, i) => ({
        trackId,
        count: 3 + i,
        daysAgo: 300,
      })),
    ]);
  }

  async function get<T>(
    schema: { parse(v: unknown): T },
    headers: Record<string, string>,
    url: string,
  ) {
    const res = await t.app.inject({ url, headers });
    expect(res.statusCode).toBe(200);
    return schema.parse(res.json());
  }

  it('lists what your listening makes, leaving out those with too few songs', async () => {
    const { headers } = await setup();
    expect((await get(SmartPlaylists, headers, '/api/stats/playlists?tz=UTC')).playlists).toEqual(
      [],
    );

    await history(headers);
    const { playlists } = await get(SmartPlaylists, headers, '/api/stats/playlists?tz=UTC');
    expect(playlists.map((p) => [p.id, p.kind, p.year, p.trackCount])).toEqual([
      ['on-repeat', 'on-repeat', null, 6],
      ['forgotten', 'forgotten', null, 5],
      ['year-2026', 'year', 2026, 7],
      ['year-2025', 'year', 2025, 5],
    ]);
    expect(playlists[0]?.coverArtIds).toEqual(['cov-s2', 'cov-s1', 'cov-s3', 'cov-s4']);
    expect(playlists.every((p) => p.savedPlaylistId === null)).toBe(true);
  });

  it('orders songs by plays, most recent first on a tie', async () => {
    const { headers } = await setup();
    await history(headers);
    const onRepeat = await get(SmartPlaylistDetail, headers, '/api/stats/playlists/on-repeat');
    expect(onRepeat.tracks.map((s) => s.id)).toEqual(['s2', 's1', 's3', 's4', 's5', 's6']);
    const forgotten = await get(SmartPlaylistDetail, headers, '/api/stats/playlists/forgotten');
    expect(forgotten.tracks.map((s) => s.id)).toEqual(['s12', 's11', 's10', 's9', 's8']);
    const year = await get(SmartPlaylistDetail, headers, '/api/stats/playlists/year-2026?tz=UTC');
    expect(year.tracks.map((s) => s.id)).toEqual(['s2', 's1', 's3', 's4', 's5', 's6', 's7']);
  });

  it('leaves out songs that are no longer in the library', async () => {
    const { headers } = await setup();
    await history(headers);
    LIBRARY.delete('s3');
    try {
      const detail = await get(SmartPlaylistDetail, headers, '/api/stats/playlists/on-repeat');
      expect(detail.tracks.map((s) => s.id)).toEqual(['s2', 's1', 's4', 's5', 's6']);
      expect(detail.trackCount).toBe(5);
    } finally {
      LIBRARY.add('s3');
    }
  });

  it('saves to Navidrome, updates that playlist next time, and starts over if it was deleted', async () => {
    const { headers, store } = await setup();
    await history(headers);
    const save = async () => {
      const res = await t.app.inject({
        method: 'POST',
        url: '/api/stats/playlists/forgotten/save',
        headers,
      });
      expect(res.statusCode).toBe(200);
      return SaveSmartPlaylistResult.parse(res.json());
    };

    const first = await save();
    expect(first.created).toBe(true);
    expect(store.get(first.playlistId)).toMatchObject({
      name: 'Forgotten Favorites',
      songs: ['s12', 's11', 's10', 's9', 's8'],
    });
    const { playlists } = await get(SmartPlaylists, headers, '/api/stats/playlists');
    expect(playlists.find((p) => p.id === 'forgotten')?.savedPlaylistId).toBe(first.playlistId);

    // More listening changes the order; saving again rewrites the same playlist.
    await listen(headers, [{ trackId: 's8', count: 5, daysAgo: 200 }]);
    const second = await save();
    expect(second).toEqual({ playlistId: first.playlistId, created: false });
    expect(store.get(first.playlistId)?.songs).toEqual(['s8', 's12', 's11', 's10', 's9']);
    expect(store.size).toBe(1);

    store.delete(first.playlistId);
    const third = await save();
    expect(third.created).toBe(true);
    expect(third.playlistId).not.toBe(first.playlistId);
  });

  it('validates the id and requires a session', async () => {
    const { headers } = await setup();
    expect((await t.app.inject({ url: '/api/stats/playlists/whatever', headers })).statusCode).toBe(
      400,
    );
    expect((await t.app.inject({ url: '/api/stats/playlists' })).statusCode).toBe(401);
    const empty = await t.app.inject({
      method: 'POST',
      url: '/api/stats/playlists/on-repeat/save',
      headers,
    });
    expect(empty.statusCode).toBe(404);
  });
});
