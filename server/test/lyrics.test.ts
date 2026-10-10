import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, LyricsResponse, type ServerEvent } from '@rev/schemas';
import { createTestApp, signedInHeaders, type TestContext } from './helpers.js';
import { fakeNavidrome, NAVIDROME, subsonicError, subsonicOk } from './fake-navidrome.js';
import { routeFetch } from './fake-services.js';

const DAY = 24 * 3_600_000;
const song = { id: 'tr-1', title: 'Schism', artist: 'Tool', album: 'Lateralus', duration: 403 };

interface LrclibWorld {
  get?: (p: URLSearchParams) => Response;
  search?: (p: URLSearchParams) => Response;
  calls: string[];
}

function lrclib(world: LrclibWorld): typeof fetch {
  return (async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
    const url = new URL(String(input));
    world.calls.push(url.pathname);
    expect(new Headers(init?.headers).get('user-agent')).toMatch(/^REV\//);
    const handler = url.pathname === '/api/get' ? world.get : world.search;
    return handler
      ? handler(url.searchParams)
      : Response.json({ statusCode: 404 }, { status: 404 });
  }) as typeof fetch;
}

const record = (over: object = {}) => ({
  id: 537184,
  trackName: 'Schism',
  artistName: 'Tool',
  albumName: 'Lateralus',
  duration: 404,
  instrumental: false,
  plainLyrics: 'I know the pieces fit\nCause I watched them fall away',
  syncedLyrics: '[00:42.10] I know the pieces fit\n[00:46.50] Cause I watched them fall away',
  ...over,
});

describe('lyrics', () => {
  let t: TestContext;
  let headers: { authorization: string };
  let now = 1_800_000_000_000;

  afterEach(async () => {
    await t.close();
  });

  async function setup(navLyrics: () => Response, world: LrclibWorld) {
    const nav = fakeNavidrome({
      getSong: () => subsonicOk({ song }),
      getLyricsBySongId: navLyrics,
    });
    t = await createTestApp({
      navidrome: NAVIDROME,
      now: () => now,
      fetch: routeFetch({ 'navidrome.test:4533': nav.fetch, 'lrclib.test': lrclib(world) }),
    });
    headers = await signedInHeaders(t);
    return nav;
  }

  const get = async () => {
    const res = await t.app.inject({ url: '/api/lyrics/tr-1', headers });
    return { status: res.statusCode, body: res.json() as unknown };
  };
  const noNavidromeLyrics = () => subsonicOk({ lyricsList: {} });

  it('prefers Navidrome lyrics and returns them pre-parsed', async () => {
    const world: LrclibWorld = { calls: [] };
    await setup(
      () =>
        subsonicOk({
          lyricsList: {
            structuredLyrics: [
              {
                synced: true,
                offset: 100,
                line: [
                  { start: 12_100, value: 'Line one' },
                  { start: 15_000, value: 'Line two' },
                ],
              },
            ],
          },
        }),
      world,
    );
    const lyrics = LyricsResponse.parse((await get()).body);
    expect(lyrics).toMatchObject({
      status: 'found',
      source: 'navidrome',
      plain: 'Line one\nLine two',
    });
    expect(lyrics.synced).toEqual([
      { startMs: 12_000, text: 'Line one' },
      { startMs: 14_900, text: 'Line two' },
    ]);
    expect(world.calls).toEqual([]);
  });

  it('falls back to LRCLIB, caches the result, and announces it', async () => {
    const world: LrclibWorld = { calls: [], get: () => Response.json(record()) };
    const nav = await setup(noNavidromeLyrics, world);
    const events: ServerEvent[] = [];
    t.app.rev.bus.subscribe((e) => events.push(e));

    const first = LyricsResponse.parse((await get()).body);
    expect(first).toMatchObject({ status: 'found', source: 'lrclib' });
    expect(first.synced?.[0]).toEqual({ startMs: 42_100, text: 'I know the pieces fit' });
    expect(events).toEqual([{ type: 'lyrics.updated', data: { trackId: 'tr-1' } }]);

    const navCalls = nav.calls.length;
    const second = LyricsResponse.parse((await get()).body);
    expect(second).toEqual(first);
    expect(world.calls).toEqual(['/api/get']);
    expect(nav.calls.length).toBe(navCalls);
  });

  it('scores search results and never attaches a different song', async () => {
    const world: LrclibWorld = {
      calls: [],
      get: () => Response.json({}, { status: 404 }),
      search: () =>
        Response.json([
          record({ id: 1, trackName: 'Schism (Live)' }),
          record({ id: 2, artistName: 'Some Cover Band' }),
          record({ id: 3, duration: 600 }),
        ]),
    };
    await setup(noNavidromeLyrics, world);
    const lyrics = LyricsResponse.parse((await get()).body);
    expect(lyrics).toMatchObject({ status: 'not_found', source: null, synced: null, plain: null });
  });

  it('picks the best search match, preferring synced lyrics', async () => {
    const world: LrclibWorld = {
      calls: [],
      get: () => Response.json({}, { status: 404 }),
      search: () =>
        Response.json([
          record({ id: 10, albumName: 'Live', duration: 409, syncedLyrics: null }),
          record({ id: 11, artistName: 'TOOL;Tool', albumName: 'Lateralus', duration: 404 }),
        ]),
    };
    await setup(noNavidromeLyrics, world);
    const lyrics = LyricsResponse.parse((await get()).body);
    expect(lyrics.status).toBe('found');
    expect(lyrics.synced).not.toBeNull();
  });

  it('caches "no lyrics" for a week, then asks again', async () => {
    const world: LrclibWorld = { calls: [] };
    await setup(noNavidromeLyrics, world);
    expect(LyricsResponse.parse((await get()).body).status).toBe('not_found');
    const calls = world.calls.length;
    await get();
    expect(world.calls.length).toBe(calls);
    now += 8 * DAY;
    await get();
    expect(world.calls.length).toBeGreaterThan(calls);
  });

  it('reports an outage without caching it', async () => {
    const world: LrclibWorld = { calls: [], get: () => new Response('down', { status: 503 }) };
    await setup(noNavidromeLyrics, world);
    const failed = await get();
    expect(failed.status).toBe(503);
    expect(ApiError.parse(failed.body).error.code).toBe('LRCLIB_UNAVAILABLE');

    world.get = () => Response.json(record());
    expect(LyricsResponse.parse((await get()).body).status).toBe('found');
  });

  it('marks instrumentals and treats a Navidrome without the lyrics extension as "none there"', async () => {
    const world: LrclibWorld = {
      calls: [],
      get: () =>
        Response.json(record({ instrumental: true, plainLyrics: null, syncedLyrics: null })),
    };
    await setup(() => subsonicError(0, 'unknown method'), world);
    expect(LyricsResponse.parse((await get()).body)).toMatchObject({
      status: 'instrumental',
      synced: null,
      plain: null,
    });
  });

  it('shares one lookup between concurrent requests', async () => {
    const world: LrclibWorld = { calls: [], get: () => Response.json(record()) };
    await setup(noNavidromeLyrics, world);
    await Promise.all([get(), get(), get()]);
    expect(world.calls).toEqual(['/api/get']);
  });
});
