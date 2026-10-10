import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, SearchResponse } from '@rev/schemas';
import { createTestApp, signedInHeaders, type TestContext } from './helpers.js';
import { fakeNavidrome, NAVIDROME, subsonicOk } from './fake-navidrome.js';
import { fakeLidarr, LIDARR, routeFetch } from './fake-services.js';
import { resolveAvailability } from '../src/library/availability.js';

const MB = {
  lateralus: 'aaaaaaaa-0000-4000-8000-000000000001',
  opiate: 'aaaaaaaa-0000-4000-8000-000000000005',
};

const navAlbum = (id: string, name: string, year: number) => ({
  id,
  name,
  artist: 'Tool',
  artistId: 'ar-1',
  year,
  songCount: 10,
  duration: 3600,
  coverArt: `${id}_0`,
});

function navidrome() {
  return fakeNavidrome({
    search3: () =>
      subsonicOk({
        searchResult3: {
          artist: [{ id: 'ar-1', name: 'Tool', albumCount: 3 }],
          album: [navAlbum('al-lat', 'Lateralus', 2001), navAlbum('al-aen', 'Ænima', 1996)],
          song: [
            { id: 'tr-1', title: 'Schism', album: 'Lateralus', artist: 'Tool', duration: 403 },
          ],
        },
      }),
    getArtist: () =>
      subsonicOk({
        artist: {
          id: 'ar-1',
          name: 'Tool',
          album: [
            navAlbum('al-lat', 'Lateralus', 2001),
            navAlbum('al-aen', 'Ænima', 1996),
            navAlbum('al-10k', '10,000 Days (Remastered)', 2006),
          ],
        },
      }),
  });
}

/** Lidarr is configured but must not be asked anything by search. */
function lidarr() {
  return fakeLidarr({});
}

describe('library search', () => {
  let t: TestContext;

  afterEach(async () => {
    await t.close();
  });

  async function setup(routes: Record<string, typeof fetch>, opts: { lidarr?: boolean } = {}) {
    t = await createTestApp({
      navidrome: NAVIDROME,
      ...(opts.lidarr === false ? {} : { lidarr: LIDARR }),
      fetch: routeFetch(routes),
    });
    return signedInHeaders(t);
  }

  async function search(headers: Record<string, string>, q: string) {
    const res = await t.app.inject({ url: `/api/search?q=${encodeURIComponent(q)}`, headers });
    expect(res.statusCode).toBe(200);
    return SearchResponse.parse(res.json());
  }

  it('searches the library only, adding the discography of a named artist', async () => {
    const lid = lidarr();
    const headers = await setup({
      'navidrome.test:4533': navidrome().fetch,
      'lidarr.test:8686': lid.fetch,
    });

    const result = await search(headers, 'Tool');

    // Navidrome's hits first, then the rest of Tool's albums; nothing from the catalog.
    expect(result.albums.map((a) => a.title)).toEqual([
      'Lateralus',
      'Ænima',
      '10,000 Days (Remastered)',
    ]);
    expect(result.albums.every((a) => a.availability === 'AVAILABLE')).toBe(true);
    expect(result.albums[0]).toMatchObject({ libraryId: 'al-lat', catalogId: null });
    expect(result.artists).toEqual([
      { name: 'Tool', disambiguation: null, libraryId: 'ar-1', coverArtId: null, catalogId: null },
    ]);
    expect(result.tracks.map((tr) => tr.title)).toEqual(['Schism']);
    expect(result.sources).toEqual({ library: 'ok', catalog: 'not_configured' });
    expect(lid.calls).toHaveLength(0);
  });

  it('reports the library as unavailable when Navidrome is down', async () => {
    const down = (async () => {
      throw new TypeError('fetch failed');
    }) as typeof fetch;
    const headers = await setup({ 'navidrome.test:4533': down }, { lidarr: false });
    const result = await search(headers, 'Tool');
    expect(result.sources).toEqual({ library: 'unavailable', catalog: 'not_configured' });
    expect(result.albums).toEqual([]);
    expect(result.tracks).toEqual([]);
  });

  it('validates the query and requires auth', async () => {
    const headers = await setup({
      'navidrome.test:4533': navidrome().fetch,
      'lidarr.test:8686': lidarr().fetch,
    });
    expect((await t.app.inject({ url: '/api/search?q=%20', headers })).statusCode).toBe(400);
    expect((await t.app.inject('/api/search?q=tool')).statusCode).toBe(401);
  });

  it('serves catalog covers for valid release-group ids only', async () => {
    const jpeg = Buffer.from([0xff, 0xd8, 0xff]);
    const requested: string[] = [];
    const caa = (async (input: Parameters<typeof fetch>[0]) => {
      const url = String(input);
      requested.push(url);
      if (url.includes(MB.opiate)) return new Response(null, { status: 404 });
      return new Response(jpeg, { headers: { 'content-type': 'image/jpeg' } });
    }) as typeof fetch;
    const headers = await setup({ 'coverartarchive.org': caa });

    const ok = await t.app.inject({ url: `/api/catalog/albums/${MB.lateralus}/cover`, headers });
    expect(ok.statusCode).toBe(200);
    expect(ok.rawPayload).toEqual(jpeg);
    expect(requested[0]).toBe(
      `https://coverartarchive.org/release-group/${MB.lateralus}/front-250`,
    );

    expect(
      (await t.app.inject({ url: `/api/catalog/albums/${MB.opiate}/cover`, headers })).statusCode,
    ).toBe(404);
    const bad = await t.app.inject({ url: '/api/catalog/albums/..%2F..%2Fsecret/cover', headers });
    expect(bad.statusCode).toBe(400);
    expect(ApiError.parse(bad.json()).error.code).toBe('VALIDATION_ERROR');
  });
});

describe('availability', () => {
  const lidarr = (trackFileCount: number, trackCount: number, monitored = true) => ({
    lidarrAlbumId: 1,
    lidarrArtistId: 1,
    monitored,
    trackFileCount,
    trackCount,
  });

  it('prefers playable over everything', () => {
    expect(
      resolveAvailability({ inNavidrome: true, lidarr: lidarr(0, 10), job: 'DOWNLOADING' }),
    ).toBe('AVAILABLE');
  });

  it('reflects active downloads before Lidarr bookkeeping', () => {
    expect(resolveAvailability({ inNavidrome: false, lidarr: lidarr(0, 10), job: 'QUEUED' })).toBe(
      'DOWNLOADING',
    );
    expect(resolveAvailability({ inNavidrome: false, job: 'IMPORTING' })).toBe('IMPORTING');
    expect(resolveAvailability({ inNavidrome: false, job: 'TIMEOUT' })).toBe('FAILED');
    expect(resolveAvailability({ inNavidrome: false, job: 'CANCELLED' })).toBe('MISSING');
  });

  it('derives the rest from Lidarr', () => {
    expect(resolveAvailability({ inNavidrome: false, lidarr: lidarr(10, 10) })).toBe('IN_LIBRARY');
    expect(resolveAvailability({ inNavidrome: false, lidarr: lidarr(3, 10) })).toBe('REQUESTED');
    expect(resolveAvailability({ inNavidrome: false, lidarr: lidarr(3, 10, false) })).toBe(
      'MISSING',
    );
    expect(resolveAvailability({ inNavidrome: false })).toBe('MISSING');
  });
});
