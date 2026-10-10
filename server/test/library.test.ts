import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AlbumDetail,
  AlbumPage,
  ApiError,
  ArtistDetail,
  ArtistList,
  Favorites,
  GenreList,
  PlaylistDetail,
  PlaylistList,
  TrackPage,
} from '@rev/schemas';
import { createTestApp, signedInHeaders, type TestContext } from './helpers.js';
import { fakeNavidrome, NAVIDROME, subsonicError, subsonicOk } from './fake-navidrome.js';

const lateralus = {
  id: 'al-1',
  name: 'Lateralus',
  artist: 'Tool',
  artistId: 'ar-1',
  year: 2001,
  genre: 'Progressive Metal',
  coverArt: 'al-1_65f0c2a1',
  songCount: 13,
  duration: 4705.5,
  created: '2024-03-01T10:00:00Z',
  starred: '2024-04-01T10:00:00Z',
};

const theGrudge = {
  id: 'tr-1',
  title: 'The Grudge',
  album: 'Lateralus',
  albumId: 'al-1',
  artist: 'Tool',
  artistId: 'ar-1',
  track: 1,
  discNumber: 1,
  year: 2001,
  coverArt: 'mf-tr-1',
  duration: 516,
  suffix: 'flac',
  bitRate: 1024,
  samplingRate: 44100,
  bitDepth: 16,
  size: 66_000_000,
  path: '/srv/music/library/Tool/Lateralus/01 The Grudge.flac',
  replayGain: { trackGain: -8.15, albumGain: -6.63, trackPeak: 0.988678, albumPeak: 0 },
};

describe('library API', () => {
  let t: TestContext;

  afterEach(async () => {
    await t.close();
  });

  async function setup(handlers: Parameters<typeof fakeNavidrome>[0]) {
    const nav = fakeNavidrome(handlers);
    t = await createTestApp({ navidrome: NAVIDROME, fetch: nav.fetch });
    return { nav, headers: await signedInHeaders(t) };
  }

  it('requires authentication', async () => {
    await setup({});
    const res = await t.app.inject('/api/library/artists');
    expect(res.statusCode).toBe(401);
  });

  it('returns 503 when Navidrome is not configured', async () => {
    t = await createTestApp();
    const res = await t.app.inject({
      url: '/api/library/artists',
      headers: await signedInHeaders(t),
    });
    expect(res.statusCode).toBe(503);
    expect(ApiError.parse(res.json()).error.code).toBe('NAVIDROME_NOT_CONFIGURED');
  });

  it('flattens the artist index', async () => {
    const { headers } = await setup({
      getArtists: () =>
        subsonicOk({
          artists: {
            ignoredArticles: 'The',
            index: [
              { name: 'A', artist: [{ id: 'ar-2', name: 'Avenged Sevenfold', albumCount: 8 }] },
              {
                name: 'T',
                artist: [
                  {
                    id: 'ar-1',
                    name: 'Tool',
                    albumCount: 5,
                    coverArt: 'ar-1_0',
                    starred: '2024-01-01T00:00:00Z',
                  },
                ],
              },
            ],
          },
        }),
    });
    const res = await t.app.inject({ url: '/api/library/artists', headers });
    expect(res.statusCode).toBe(200);
    expect(ArtistList.parse(res.json()).artists).toEqual([
      { id: 'ar-2', name: 'Avenged Sevenfold', albumCount: 8, coverArtId: null, starred: false },
      { id: 'ar-1', name: 'Tool', albumCount: 5, coverArtId: 'ar-1_0', starred: true },
    ]);
  });

  it('returns an artist with albums', async () => {
    const { headers, nav } = await setup({
      getArtist: () =>
        subsonicOk({ artist: { id: 'ar-1', name: 'Tool', albumCount: 1, album: [lateralus] } }),
    });
    const res = await t.app.inject({ url: '/api/library/artists/ar-1', headers });
    const body = ArtistDetail.parse(res.json());
    expect(body.albums[0]?.name).toBe('Lateralus');
    expect(nav.calls[0]?.params.get('id')).toBe('ar-1');
  });

  it('maps album details and tracks to the REV model without leaking file paths', async () => {
    const { headers } = await setup({
      getAlbum: () => subsonicOk({ album: { ...lateralus, song: [theGrudge] } }),
    });
    const res = await t.app.inject({ url: '/api/library/albums/al-1', headers });
    expect(res.statusCode).toBe(200);
    const album = AlbumDetail.parse(res.json());
    expect(album).toMatchObject({
      name: 'Lateralus',
      durationMs: 4_705_500,
      starred: true,
      createdAt: Date.parse('2024-03-01T10:00:00Z'),
      playedAt: null,
      trackCount: 13,
    });
    expect(album.tracks[0]).toMatchObject({
      title: 'The Grudge',
      durationMs: 516_000,
      format: 'flac',
      bitRateKbps: 1024,
      sampleRateHz: 44100,
      starred: false,
      replayGain: { trackGainDb: -8.15, albumGainDb: -6.63, trackPeak: 0.988678, albumPeak: null },
    });
    expect(res.body).not.toContain('/srv/music');
  });

  it('pages album lists and translates list types', async () => {
    const { headers, nav } = await setup({
      getAlbumList2: (p) =>
        subsonicOk({
          albumList2: { album: Array.from({ length: Number(p.get('size')) }, () => lateralus) },
        }),
    });
    const res = await t.app.inject({
      url: '/api/library/albums?type=newest&size=2&offset=4',
      headers,
    });
    const page = AlbumPage.parse(res.json());
    expect(page).toMatchObject({ offset: 4, hasMore: true });
    expect(page.albums).toHaveLength(2);
    const params = nav.calls[0]?.params;
    expect(params?.get('type')).toBe('newest');
    expect(params?.get('offset')).toBe('4');
  });

  it('requires a genre for byGenre album lists', async () => {
    const { headers } = await setup({});
    const res = await t.app.inject({ url: '/api/library/albums?type=byGenre', headers });
    expect(res.statusCode).toBe(400);
    expect(ApiError.parse(res.json()).error.message).toContain('genre');
  });

  it('pages all tracks via an empty search', async () => {
    const { headers, nav } = await setup({
      search3: () => subsonicOk({ searchResult3: { song: [theGrudge] } }),
    });
    const res = await t.app.inject({ url: '/api/library/tracks?size=10', headers });
    expect(TrackPage.parse(res.json())).toMatchObject({ hasMore: false, offset: 0 });
    expect(nav.calls[0]?.params.get('query')).toBe('');
    expect(nav.calls[0]?.params.get('songCount')).toBe('10');
  });

  it('lists playlists and returns a playlist with its tracks in order', async () => {
    const { headers } = await setup({
      getPlaylists: () =>
        subsonicOk({
          playlists: {
            playlist: [
              {
                id: 'pl-1',
                name: 'Road trip',
                owner: 'rev',
                songCount: 2,
                duration: 900,
                changed: '2026-01-01T00:00:00Z',
              },
            ],
          },
        }),
      getPlaylist: () =>
        subsonicOk({
          playlist: {
            id: 'pl-1',
            name: 'Road trip',
            songCount: 2,
            duration: 900,
            entry: [theGrudge, { ...theGrudge, id: 'tr-2', title: 'Schism' }],
          },
        }),
    });
    const list = PlaylistList.parse(
      (await t.app.inject({ url: '/api/library/playlists', headers })).json(),
    );
    expect(list.playlists[0]).toMatchObject({
      id: 'pl-1',
      name: 'Road trip',
      trackCount: 2,
      durationMs: 900_000,
      comment: null,
    });
    const detail = PlaylistDetail.parse(
      (await t.app.inject({ url: '/api/library/playlists/pl-1', headers })).json(),
    );
    expect(detail.tracks.map((tr) => tr.title)).toEqual(['The Grudge', 'Schism']);
  });

  it('handles Navidrome omitting empty arrays', async () => {
    const { headers } = await setup({
      getStarred2: () => subsonicOk({ starred2: {} }),
      getGenres: () => subsonicOk({ genres: {} }),
    });
    const fav = await t.app.inject({ url: '/api/library/favorites', headers });
    expect(Favorites.parse(fav.json())).toEqual({ artists: [], albums: [], tracks: [] });
    const genres = await t.app.inject({ url: '/api/library/genres', headers });
    expect(GenreList.parse(genres.json()).genres).toEqual([]);
  });

  it('stars and unstars with the right Subsonic parameter', async () => {
    const { headers, nav } = await setup({
      star: () => subsonicOk({}),
      unstar: () => subsonicOk({}),
    });
    const put = await t.app.inject({
      method: 'PUT',
      url: '/api/library/favorites/albums/al-1',
      headers,
    });
    const del = await t.app.inject({
      method: 'DELETE',
      url: '/api/library/favorites/tracks/tr-1',
      headers,
    });
    expect(put.statusCode).toBe(204);
    expect(del.statusCode).toBe(204);
    expect(
      nav.calls.map((c) => [c.endpoint, c.params.get('albumId') ?? c.params.get('id')]),
    ).toEqual([
      ['star', 'al-1'],
      ['unstar', 'tr-1'],
    ]);
  });

  it('rejects ids that are not plain Navidrome ids', async () => {
    const { headers } = await setup({});
    for (const url of ['/api/library/albums/..%2F..%2Fetc', '/api/library/artwork/a%26u%3Dadmin']) {
      const res = await t.app.inject({ url, headers });
      expect(res.statusCode).toBe(400);
    }
    const kind = await t.app.inject({
      method: 'PUT',
      url: '/api/library/favorites/users/1',
      headers,
    });
    expect(kind.statusCode).toBe(400);
  });

  it('streams artwork with cache headers', async () => {
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47]);
    const { headers, nav } = await setup({
      getCoverArt: () =>
        new Response(png, { headers: { 'content-type': 'image/png', 'content-length': '4' } }),
    });
    const res = await t.app.inject({ url: '/api/library/artwork/al-1_65f0c2a1?size=300', headers });
    expect(res.statusCode).toBe(200);
    expect(res.headers['content-type']).toBe('image/png');
    expect(res.headers['cache-control']).toContain('max-age');
    expect(res.rawPayload).toEqual(png);
    expect(nav.calls[0]?.params.get('size')).toBe('300');
  });

  it('maps Navidrome failures to safe errors', async () => {
    const { headers } = await setup({
      getAlbum: () => subsonicError(70, 'Album not found'),
      getSong: () => new Response('oops', { status: 500 }),
      getCoverArt: () => subsonicError(70, 'Artwork not found'),
    });
    expect((await t.app.inject({ url: '/api/library/albums/x', headers })).statusCode).toBe(404);
    expect((await t.app.inject({ url: '/api/library/artwork/x', headers })).statusCode).toBe(404);

    const broken = await t.app.inject({ url: '/api/library/tracks/x', headers });
    expect(broken.statusCode).toBe(503);
    expect(ApiError.parse(broken.json()).error.code).toBe('NAVIDROME_UNAVAILABLE');
  });

  it('reports Navidrome being down or rejecting REV credentials', async () => {
    const nav = fakeNavidrome({});
    t = await createTestApp({
      navidrome: { ...NAVIDROME, password: 'wrong' },
      fetch: nav.fetch,
    });
    const headers = await signedInHeaders(t);
    const res = await t.app.inject({ url: '/api/library/genres', headers });
    expect(res.statusCode).toBe(502);
    expect(ApiError.parse(res.json()).error.code).toBe('NAVIDROME_AUTH_FAILED');
    expect(res.body).not.toContain('wrong');

    await t.close();
    const down = (async () => {
      throw new TypeError('fetch failed', { cause: { code: 'ECONNREFUSED' } });
    }) as typeof fetch;
    t = await createTestApp({ navidrome: NAVIDROME, fetch: down });
    const res2 = await t.app.inject({
      url: '/api/library/genres',
      headers: await signedInHeaders(t),
    });
    expect(res2.statusCode).toBe(503);
    expect(ApiError.parse(res2.json()).error.message).toBe('Music server unavailable');
  });
});

describe('streaming and play reporting', () => {
  let t: TestContext;

  afterEach(async () => {
    await t.close();
  });

  async function setup(
    handlers: Parameters<typeof fakeNavidrome>[0],
    fetchSpy?: (init?: RequestInit) => void,
  ) {
    const nav = fakeNavidrome(handlers);
    const fetchImpl = (async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
      fetchSpy?.(init);
      return nav.fetch(input, init);
    }) as typeof fetch;
    t = await createTestApp({ navidrome: NAVIDROME, fetch: fetchImpl });
    return { nav, headers: await signedInHeaders(t) };
  }

  const audio = Buffer.from('fLaC-audio-bytes');

  it('serves the same session on the home-network and tunnel addresses without leaking Navidrome', async () => {
    const { headers } = await setup({
      stream: () => new Response(audio, { headers: { 'content-type': 'audio/flac' } }),
    });
    // Directly on the LAN, and through Cloudflare Tunnel (which forwards to the same port).
    for (const via of [
      { host: '192.168.1.69:4600' },
      { host: 'rev.example.com', 'x-forwarded-proto': 'https', 'cf-connecting-ip': '203.0.113.9' },
    ]) {
      const res = await t.app.inject({
        url: '/api/library/tracks/tr-1/stream',
        headers: { ...headers, ...via },
      });
      expect(res.statusCode).toBe(200);
      expect(res.rawPayload).toEqual(audio);
      const exposed = JSON.stringify(res.headers) + res.body;
      for (const secret of [NAVIDROME.password, 'navidrome.test', 'u=rev', 't=', 's=']) {
        expect(exposed).not.toContain(secret);
      }
    }
  });

  it('streams the original file and passes through range requests', async () => {
    let upstreamRange: string | undefined;
    const { headers, nav } = await setup(
      {
        stream: () =>
          new Response(audio.subarray(5), {
            status: 206,
            headers: {
              'content-type': 'audio/flac',
              'content-length': String(audio.length - 5),
              'content-range': `bytes 5-${audio.length - 1}/${audio.length}`,
              'accept-ranges': 'bytes',
              'x-internal': 'nope',
            },
          }),
      },
      (init) => {
        upstreamRange = (init?.headers as Record<string, string> | undefined)?.range;
      },
    );

    const res = await t.app.inject({
      url: '/api/library/tracks/tr-1/stream',
      headers: { ...headers, range: 'bytes=5-' },
    });

    expect(res.statusCode).toBe(206);
    expect(res.headers['content-type']).toBe('audio/flac');
    expect(res.headers['content-range']).toBe(`bytes 5-${audio.length - 1}/${audio.length}`);
    expect(res.headers['accept-ranges']).toBe('bytes');
    expect(res.headers['x-internal']).toBeUndefined();
    expect(res.rawPayload).toEqual(audio.subarray(5));
    expect(upstreamRange).toBe('bytes=5-');
    expect(nav.calls[0]?.params.get('format')).toBe('raw');
  });

  it('requests transcoding only for an allowed bit rate', async () => {
    const { headers, nav } = await setup({
      stream: () => new Response(audio, { headers: { 'content-type': 'audio/mpeg' } }),
    });
    const ok = await t.app.inject({
      url: '/api/library/tracks/tr-1/stream?maxBitRate=192',
      headers,
    });
    expect(ok.statusCode).toBe(200);
    expect(nav.calls[0]?.params.get('maxBitRate')).toBe('192');
    expect(nav.calls[0]?.params.get('format')).toBeNull();

    const bad = await t.app.inject({
      url: '/api/library/tracks/tr-1/stream?maxBitRate=1000',
      headers,
    });
    expect(bad.statusCode).toBe(400);
  });

  it('requires auth and maps unknown tracks to 404', async () => {
    const { headers } = await setup({ stream: () => subsonicError(70, 'not found') });
    expect((await t.app.inject('/api/library/tracks/tr-1/stream')).statusCode).toBe(401);
    expect(
      (await t.app.inject({ url: '/api/library/tracks/nope/stream', headers })).statusCode,
    ).toBe(404);
  });

  it('stops the upstream download when the player disconnects', async () => {
    let upstreamAborted = false;
    const endless = (async (_input: Parameters<typeof fetch>[0], init?: RequestInit) => {
      init?.signal?.addEventListener('abort', () => {
        upstreamAborted = true;
      });
      let cancelled = false;
      const body = new ReadableStream<Uint8Array>({
        async pull(controller) {
          await new Promise((r) => setTimeout(r, 5));
          if (!cancelled) controller.enqueue(new Uint8Array(1024));
        },
        cancel() {
          cancelled = true;
        },
      });
      return new Response(body, { headers: { 'content-type': 'audio/flac' } });
    }) as typeof fetch;
    t = await createTestApp({ navidrome: NAVIDROME, fetch: endless });
    const headers = await signedInHeaders(t);
    await t.app.listen({ port: 0, host: '127.0.0.1' });
    const { port } = t.app.server.address() as { port: number };

    const client = new AbortController();
    const res = await fetch(`http://127.0.0.1:${port}/api/library/tracks/tr-1/stream`, {
      headers,
      signal: client.signal,
    });
    await res.body?.getReader().read();
    client.abort();
    await vi.waitFor(() => expect(upstreamAborted).toBe(true));
  });

  it('scrobbles now-playing and completed plays', async () => {
    const { headers, nav } = await setup({
      scrobble: () => subsonicOk({}),
      getSong: () => subsonicOk({ song: { id: 'tr-1', title: 'The Grudge', duration: 500 } }),
    });
    const nowPlaying = await t.app.inject({
      method: 'POST',
      url: '/api/library/tracks/tr-1/scrobble',
      headers,
      payload: { submission: false },
    });
    const played = await t.app.inject({
      method: 'POST',
      url: '/api/library/tracks/tr-1/scrobble',
      headers,
      payload: { submission: true, playedAt: 1_800_000_000_000 },
    });
    expect(nowPlaying.statusCode).toBe(204);
    expect(played.statusCode).toBe(204);
    const scrobbles = nav.calls.filter((c) => c.endpoint === 'scrobble');
    expect(scrobbles.map((c) => [c.params.get('submission'), c.params.get('time')])).toEqual([
      ['false', null],
      ['true', '1800000000000'],
    ]);

    const invalid = await t.app.inject({
      method: 'POST',
      url: '/api/library/tracks/tr-1/scrobble',
      headers,
      payload: { submission: 'yes' },
    });
    expect(invalid.statusCode).toBe(400);
  });
});
