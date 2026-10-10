import { afterEach, describe, expect, it } from 'vitest';
import type { Album, Track } from '@rev/schemas';
import { NextAlbum, RadioTracks } from '@rev/schemas';
import type { ListenedAlbum } from '../src/adapters/navidrome/library.js';
import { AutoplayService, type AutoplaySource } from '../src/home/autoplay.js';
import { seeded } from '../src/home/service.js';
import { createTestApp, signedInHeaders, type TestContext } from './helpers.js';
import { fakeNavidrome, NAVIDROME, subsonicOk } from './fake-navidrome.js';

function album(id: string, artist: string, year: number | null, playCount = 0): ListenedAlbum {
  const a: Album = {
    id,
    name: `Album ${id}`,
    artist,
    artistId: `ar-${artist}`,
    year,
    genre: 'Metal',
    coverArtId: null,
    trackCount: 3,
    durationMs: 1000,
    starred: false,
    createdAt: null,
    playedAt: null,
    releaseTypes: [],
  };
  return { album: a, playCount, genres: ['Metal'] };
}

function track(id: string, artist: string): Track {
  return {
    id,
    title: id,
    album: 'x',
    albumId: null,
    artist,
    artistId: `ar-${artist}`,
    trackNumber: null,
    discNumber: null,
    year: null,
    genre: null,
    coverArtId: null,
    durationMs: 1000,
    starred: false,
    format: null,
    bitRateKbps: null,
    sampleRateHz: null,
    bitDepth: null,
    sizeBytes: null,
    replayGain: null,
  };
}

const artists: Record<string, ListenedAlbum[]> = {
  'ar-Tool': [
    album('aenima', 'Tool', 1996),
    album('lateralus', 'Tool', 2001),
    album('10k', 'Tool', 2006),
  ],
  'ar-APC': [album('apc1', 'APC', 2000, 5), album('apc2', 'APC', 2003, 1)],
  'ar-Soen': [album('soen1', 'Soen', 2012, 0)],
};

function source(): AutoplaySource {
  return {
    artistAlbums: async (id) => artists[id] ?? [],
    similarArtists: async (id) =>
      id === 'ar-Tool'
        ? ['APC', 'Soen'].map((n) => ({
            id: `ar-${n}`,
            name: n,
            albumCount: 1,
            coverArtId: null,
            starred: false,
          }))
        : [],
    albumTracks: async (id) =>
      [1, 2, 3].map((n) =>
        track(`${id}-${n}`, id.startsWith('apc') ? 'APC' : id.startsWith('soen') ? 'Soen' : 'Tool'),
      ),
    listenedAlbums: async () => [album('random1', 'Someone', 1999)],
    randomSongs: async (size) =>
      Array.from({ length: Math.min(size, 40) }, (_, i) => ({
        track: track(`rnd${i}`, `R${i % 5}`),
        playCount: 0,
      })),
  };
}

describe('autoplay', () => {
  const service = () => new AutoplayService(source(), seeded(7));

  it("plays the artist's next release after an album", async () => {
    const next = await service().nextAlbum({
      albumId: 'aenima',
      artistId: 'ar-Tool',
      genre: null,
      exclude: [],
    });
    expect(next.album?.id).toBe('lateralus');
    expect(next.tracks.map((t) => t.id)).toEqual(['lateralus-1', 'lateralus-2', 'lateralus-3']);
  });

  it('moves to a similar artist after the last release, least played album first', async () => {
    const next = await service().nextAlbum({
      albumId: '10k',
      artistId: 'ar-Tool',
      genre: null,
      exclude: ['soen1'],
    });
    expect(next.album?.id).toBe('apc2');
  });

  it('falls back to any album, and to nothing when the library has nothing else', async () => {
    const next = await service().nextAlbum({
      albumId: 'x',
      artistId: null,
      genre: null,
      exclude: [],
    });
    expect(next.album?.id).toBe('random1');
    const none = await service().nextAlbum({
      albumId: 'x',
      artistId: null,
      genre: null,
      exclude: ['random1'],
    });
    expect(none).toEqual({ album: null, tracks: [] });
  });

  it('builds a radio from similar artists, without songs already queued', async () => {
    const tracks = await service().radio({
      artistIds: ['ar-Tool'],
      genres: [],
      exclude: ['apc1-1'],
    });
    expect(tracks).toHaveLength(25);
    expect(new Set(tracks.map((t) => t.id)).size).toBe(25);
    expect(tracks.map((t) => t.id)).not.toContain('apc1-1');
    expect(tracks.some((t) => t.artist === 'APC' || t.artist === 'Soen')).toBe(true);
  });
});

describe('artist autoplay', () => {
  const service = () => new AutoplayService(source(), seeded(7));
  const toolSongs = ['aenima', 'lateralus', '10k'].flatMap((a) =>
    [1, 2, 3].map((n) => `${a}-${n}`),
  );

  it("continues with the artist's songs not yet played", async () => {
    const tracks = await service().artistSongs({
      artistId: 'ar-Tool',
      genres: [],
      exclude: ['lateralus-1', '10k-2'],
    });
    expect(tracks.map((t) => t.id).sort()).toEqual(
      toolSongs.filter((id) => id !== 'lateralus-1' && id !== '10k-2').sort(),
    );
  });

  it('moves to similar artists, without the artist, once all their songs were played', async () => {
    const tracks = await service().artistSongs({
      artistId: 'ar-Tool',
      genres: ['Metal'],
      exclude: toolSongs,
    });
    expect(tracks.length).toBeGreaterThan(0);
    expect(tracks.some((t) => t.artist === 'APC' || t.artist === 'Soen')).toBe(true);
    expect(tracks.filter((t) => t.artist === 'Tool')).toEqual([]);
  });
});

describe('autoplay routes', () => {
  let t: TestContext;
  afterEach(async () => {
    await t.close();
  });

  it('serves next albums and radios to signed-in users, validating input', async () => {
    const nav = fakeNavidrome({
      getArtist: () =>
        subsonicOk({
          artist: {
            id: 'ar-1',
            name: 'Tool',
            album: [
              {
                id: 'al-1',
                name: 'Ænima',
                artist: 'Tool',
                artistId: 'ar-1',
                year: 1996,
                songCount: 1,
                duration: 60,
              },
              {
                id: 'al-2',
                name: 'Lateralus',
                artist: 'Tool',
                artistId: 'ar-1',
                year: 2001,
                songCount: 1,
                duration: 60,
              },
            ],
          },
        }),
      getAlbum: () =>
        subsonicOk({
          album: {
            id: 'al-2',
            name: 'Lateralus',
            artist: 'Tool',
            songCount: 1,
            duration: 60,
            song: [{ id: 'tr-2', title: 'Schism', artist: 'Tool', duration: 60 }],
          },
        }),
      getArtistInfo2: () => subsonicOk({ artistInfo2: {} }),
      getRandomSongs: () =>
        subsonicOk({
          randomSongs: { song: [{ id: 'tr-9', title: 'Other', artist: 'X', duration: 60 }] },
        }),
    });
    t = await createTestApp({ navidrome: NAVIDROME, fetch: nav.fetch });
    const headers = await signedInHeaders(t);
    const body = { albumId: 'al-1', artistId: 'ar-1', genre: null };

    expect(
      (await t.app.inject({ method: 'POST', url: '/api/autoplay/next-album', payload: body }))
        .statusCode,
    ).toBe(401);
    const next = await t.app.inject({
      method: 'POST',
      url: '/api/autoplay/next-album',
      payload: body,
      headers,
    });
    expect(NextAlbum.parse(next.json()).tracks.map((x) => x.id)).toEqual(['tr-2']);

    const radio = await t.app.inject({
      method: 'POST',
      url: '/api/autoplay/radio',
      payload: { artistIds: ['ar-1'], genres: [] },
      headers,
    });
    expect(RadioTracks.parse(radio.json()).tracks.length).toBeGreaterThan(0);

    const artist = await t.app.inject({
      method: 'POST',
      url: '/api/autoplay/artist',
      payload: { artistId: 'ar-1', exclude: ['tr-1'] },
      headers,
    });
    expect(RadioTracks.parse(artist.json()).tracks.map((x) => x.id)).toContain('tr-2');

    const bad = await t.app.inject({
      method: 'POST',
      url: '/api/autoplay/radio',
      payload: { artistIds: 'x' },
      headers,
    });
    expect(bad.statusCode).toBe(400);
  });
});
