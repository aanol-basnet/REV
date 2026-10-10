import { afterEach, describe, expect, it } from 'vitest';
import type { Album, Genre, Track } from '@rev/schemas';
import { HomeFeed, MixTracks } from '@rev/schemas';
import type { ListenedAlbum, ListenedTrack } from '../src/adapters/navidrome/library.js';
import {
  HomeService,
  type ListeningSource,
  rankGenres,
  seeded,
  spreadArtists,
} from '../src/home/service.js';
import { createTestApp, signedInHeaders, type TestContext } from './helpers.js';
import { fakeNavidrome, NAVIDROME, subsonicOk } from './fake-navidrome.js';

const DAY = 86_400_000;
const NOW = 1_800_000_000_000;

function album(id: string, artist: string, extra: Partial<Album> = {}): Album {
  return {
    id,
    name: `Album ${id}`,
    artist,
    artistId: `ar-${artist}`,
    year: 2000,
    genre: null,
    coverArtId: `cov-${id}`,
    trackCount: 10,
    durationMs: 1000,
    starred: false,
    createdAt: null,
    playedAt: NOW - DAY,
    releaseTypes: ['album'],
    ...extra,
  };
}

const listened = (a: Album, playCount: number, genres: string[] = []): ListenedAlbum => ({
  album: a,
  playCount,
  genres,
});

function track(id: string, artist: string): Track {
  return {
    id,
    title: `Track ${id}`,
    album: 'x',
    albumId: `al-${artist}`,
    artist,
    artistId: `ar-${artist}`,
    trackNumber: 1,
    discNumber: 1,
    year: null,
    genre: null,
    coverArtId: null,
    durationMs: 1000,
    starred: false,
    format: 'flac',
    bitRateKbps: null,
    sampleRateHz: null,
    bitDepth: null,
    sizeBytes: null,
    replayGain: null,
  };
}

/** A library where you mostly play Tool and metalcore. */
function source(overrides: Partial<ListeningSource> = {}): ListeningSource {
  const frequent = [
    listened(album('t1', 'Tool'), 40, ['Progressive Metal']),
    listened(album('t2', 'Tool'), 30, ['Progressive Metal']),
    listened(album('m1', 'Architects'), 20, ['Metalcore']),
    listened(album('old', 'Deftones', { playedAt: NOW - 90 * DAY }), 15, ['Alternative Metal']),
    listened(album('old2', 'Deftones', { playedAt: NOW - 80 * DAY }), 12, ['Alternative Metal']),
    listened(album('old3', 'Korn', { playedAt: NOW - 70 * DAY }), 11, ['Nu Metal']),
    listened(album('old4', 'Korn', { playedAt: NOW - 60 * DAY }), 10, ['Nu Metal']),
    listened(album('r1', 'Rock Band'), 5, ['Rock']),
    ...Array.from({ length: 4 }, (_, i) =>
      listened(album(`rot-${i}`, `Rotation ${i}`), 9 - i, ['Metalcore']),
    ),
  ];
  const genres: Genre[] = [
    { name: 'Progressive Metal', albumCount: 30, trackCount: 300 },
    { name: 'Metalcore', albumCount: 40, trackCount: 400 },
    { name: 'Alternative Metal', albumCount: 50, trackCount: 500 },
    { name: 'Nu Metal', albumCount: 20, trackCount: 200 },
    { name: 'Rock', albumCount: 900, trackCount: 9000 },
  ];
  const byGenre = (g: string) =>
    Array.from({ length: 10 }, (_, i) =>
      listened(album(`${g}-${i}`, `${g} artist ${i}`), i % 2, [g]),
    );
  return {
    listenedAlbums: async (type) => {
      if (typeof type !== 'string') return byGenre(type.genre);
      if (type === 'frequent') return frequent;
      if (type === 'recent')
        return [
          listened(album('t1', 'Tool'), 40),
          listened(album('m1', 'Architects'), 20),
          ...Array.from({ length: 4 }, (_, i) => listened(album(`rec-${i}`, `Rec ${i}`), 1)),
        ];
      if (type === 'newest')
        return Array.from({ length: 6 }, (_, i) => listened(album(`new-${i}`, 'New'), 0));
      return Array.from({ length: 20 }, (_, i) =>
        listened(album(`rand-${i}`, `R${i}`), i < 10 ? 0 : 3, i < 5 ? ['Metalcore'] : ['Jazz']),
      );
    },
    artistAlbums: async (id) =>
      Array.from({ length: 3 }, (_, i) => listened(album(`${id}-${i}`, id.replace('ar-', '')), i)),
    similarArtists: async (id) =>
      id === 'ar-Tool'
        ? ['A Perfect Circle', 'Karnivool', 'Puscifer', 'Soen'].map((n) => ({
            id: `ar-${n}`,
            name: n,
            albumCount: 3,
            coverArtId: null,
            starred: false,
          }))
        : [],
    randomSongs: async (size, genre) =>
      Array.from({ length: Math.min(size, 60) }, (_, i): ListenedTrack => ({
        track: track(`s${genre ?? ''}${i}`, `A${i % 7}`),
        playCount: i % 3 === 0 ? 0 : 2,
      })),
    // Album ids look like `ar-<artist>-<n>`.
    albumTracks: async (id) =>
      Array.from({ length: 8 }, (_, i) => track(`${id}-t${i}`, id.slice(3, id.lastIndexOf('-')))),
    genres: async () => genres,
    favorites: async () => ({
      artists: [],
      albums: [album('fav', 'Tool')],
      tracks: Array.from({ length: 6 }, (_, i) => track(`fav${i}`, 'Tool')),
    }),
    ...overrides,
  };
}

const service = (src = source(), now = () => NOW) =>
  new HomeService(src, { cacheMs: 60_000, now, random: seeded(1), onSectionError: () => {} });

describe('home recommendations', () => {
  it('builds rows from what you play, most specific tastes first', async () => {
    const feed = HomeFeed.parse(await service().feed());
    const kinds = feed.sections.map((s) => `${s.kind}${s.basedOn ? `:${s.basedOn}` : ''}`);

    expect(kinds[0]).toBe('recent');
    expect(kinds).toContain('frequent');
    expect(kinds).toContain('similar:Tool');
    expect(kinds).toContain('rediscover');
    expect(kinds).toContain('unheard');
    // "Rock" is huge and barely played: it never wins over a specific genre.
    expect(kinds.some((k) => k === 'genre:Rock')).toBe(false);
    expect(kinds.filter((k) => k.startsWith('genre:')).length).toBeGreaterThan(0);

    // No album appears twice outside the "recently played" row.
    const rest = feed.sections
      .filter((s) => s.kind !== 'recent')
      .flatMap((s) => s.albums.map((a) => a.id));
    expect(new Set(rest).size).toBe(rest.length);
  });

  it('recommends similar artists, unheard albums leaning to your genres, and old favourites', async () => {
    const feed = await service().feed();
    const similar = feed.sections.find((s) => s.kind === 'similar');
    expect(
      similar?.albums.every((a) =>
        ['A Perfect Circle', 'Karnivool', 'Puscifer', 'Soen'].includes(a.artist),
      ),
    ).toBe(true);

    const unheard = feed.sections.find((s) => s.kind === 'unheard');
    expect(unheard?.albums.slice(0, 5).map((a) => a.id)).toEqual([
      'rand-0',
      'rand-1',
      'rand-2',
      'rand-3',
      'rand-4',
    ]);

    const rediscover = feed.sections.find((s) => s.kind === 'rediscover');
    expect(rediscover?.albums.map((a) => a.id)).toEqual(['old', 'old2', 'old3', 'old4']);
  });

  it('offers mixes for top artists, genres, favourites and discovery, with covers', async () => {
    const feed = await service().feed();
    expect(feed.mixes.map((m) => m.kind)).toEqual(
      expect.arrayContaining(['artist', 'genre', 'favorites', 'discovery']),
    );
    const tool = feed.mixes.find((m) => m.id === 'artist:ar-Tool');
    expect(tool?.featuring).toEqual(['A Perfect Circle', 'Karnivool', 'Puscifer']);
    expect(tool?.coverArtIds.length).toBeGreaterThan(0);
    expect(feed.quickPicks.length).toBeGreaterThan(0);
  });

  it('artist radio mixes in the artist every third track; discovery is unheard tracks only', async () => {
    const s = service();
    const radio = await s.mix('artist:ar-Tool');
    expect(radio.length).toBeGreaterThan(10);
    expect(radio.filter((_, i) => i % 3 === 0).every((t) => t.artist === 'Tool')).toBe(true);
    expect(radio.some((t) => t.artist !== 'Tool')).toBe(true);

    const discovery = await s.mix('discovery');
    expect(discovery.every((t) => Number(t.id.replace('s', '')) % 3 === 0)).toBe(true);
    expect((await s.mix('genre:Metalcore')).length).toBe(50);
    expect((await s.mix('favorites')).length).toBe(6);
  });

  it('leaves out a row whose source fails instead of failing Home', async () => {
    const errors: string[] = [];
    const s = new HomeService(
      source({
        similarArtists: () => Promise.reject(new Error('last.fm down')),
        listenedAlbums: async (type) => {
          if (type === 'random') throw new Error('boom');
          return source().listenedAlbums(type, 10);
        },
      }),
      { cacheMs: 0, now: () => NOW, random: seeded(1), onSectionError: (s) => errors.push(s) },
    );
    const feed = await s.feed();
    expect(feed.sections.some((x) => x.kind === 'similar')).toBe(false);
    expect(feed.sections.some((x) => x.kind === 'frequent')).toBe(true);
    expect(errors).toEqual(expect.arrayContaining(['random', 'similar:Tool']));
  });

  it('reuses a recent feed, rebuilds on request, and is stable within a day', async () => {
    let calls = 0;
    let now = NOW;
    const base = source();
    const s = service({ ...base, genres: async () => (calls++, base.genres()) }, () => now);
    const first = await s.feed();
    now += 30_000;
    expect(await s.feed()).toBe(first);
    expect(calls).toBe(1);
    const rebuilt = await s.feed(true);
    expect(calls).toBe(2);
    expect(rebuilt.sections.map((x) => x.albums.map((a) => a.id))).toEqual(
      first.sections.map((x) => x.albums.map((a) => a.id)),
    );
  });

  it('ranks genres by plays relative to how much of the library they cover', () => {
    const ranked = rankGenres(
      [listened(album('a', 'x'), 10, ['Rock']), listened(album('b', 'y'), 6, ['Sludge'])],
      [
        { name: 'Rock', albumCount: 900, trackCount: 0 },
        { name: 'Sludge', albumCount: 9, trackCount: 0 },
      ],
    );
    expect(ranked.map((g) => g.item.name)).toEqual(['Sludge', 'Rock']);
  });

  it('spreads an artist out across a mix', () => {
    const songs = ['a1', 'a2', 'a3', 'b1', 'c1', 'd1', 'b2'].map((id) => ({
      id,
      artist: id.startsWith('a') ? 'A' : id.slice(0, 1).toUpperCase(),
    }));
    expect(spreadArtists(songs).map((s) => s.id)).toEqual([
      'a1',
      'b1',
      'c1',
      'd1',
      'a2',
      'b2',
      'a3',
    ]);
  });
});

describe('home routes', () => {
  let t: TestContext;
  afterEach(async () => {
    await t.close();
  });

  const sub = (id: string, extra: Record<string, unknown> = {}) => ({
    id,
    name: `Album ${id}`,
    artist: 'Tool',
    artistId: 'ar-1',
    songCount: 1,
    duration: 60,
    ...extra,
  });

  it('serves the feed and mixes to signed-in users only, validating mix ids', async () => {
    const nav = fakeNavidrome({
      getAlbumList2: () =>
        subsonicOk({
          albumList2: {
            album: [1, 2, 3, 4, 5].map((i) =>
              sub(`al-${i}`, { playCount: 6 - i, genres: [{ name: 'Sludge' }] }),
            ),
          },
        }),
      getGenres: () =>
        subsonicOk({ genres: { genre: [{ value: 'Sludge', songCount: 50, albumCount: 9 }] } }),
      getStarred2: () => subsonicOk({ starred2: {} }),
      getArtistInfo2: () => subsonicOk({ artistInfo2: {} }),
      getRandomSongs: () =>
        subsonicOk({
          randomSongs: { song: [{ id: 'tr-1', title: 'Song', artist: 'Tool', playCount: 0 }] },
        }),
    });
    t = await createTestApp({ navidrome: NAVIDROME, fetch: nav.fetch });
    const headers = await signedInHeaders(t);

    expect((await t.app.inject('/api/home')).statusCode).toBe(401);
    const res = await t.app.inject({ url: '/api/home', headers });
    expect(res.statusCode).toBe(200);
    const feed = HomeFeed.parse(res.json());
    expect(feed.sections[0]?.kind).toBe('recent');
    expect(feed.genres.map((g) => g.name)).toEqual(['Sludge']);
    // Nothing from Navidrome's configuration reaches the client.
    expect(res.body).not.toContain(NAVIDROME.password);
    expect(res.body).not.toContain('navidrome.test');

    const mix = await t.app.inject({ url: '/api/mixes/genre:Sludge', headers });
    expect(MixTracks.parse(mix.json()).tracks.map((x) => x.id)).toEqual(['tr-1']);
    expect(nav.calls.at(-1)?.params.get('genre')).toBe('Sludge');

    expect((await t.app.inject({ url: '/api/mixes/whatever', headers })).statusCode).toBe(400);
    expect((await t.app.inject({ url: '/api/mixes/artist:..%2F..', headers })).statusCode).toBe(
      400,
    );
  });

  it('reports a missing music server', async () => {
    t = await createTestApp();
    const headers = await signedInHeaders(t);
    expect((await t.app.inject({ url: '/api/home', headers })).statusCode).toBe(503);
  });
});
