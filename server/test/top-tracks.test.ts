import { describe, expect, it } from 'vitest';
import type { Album, Track } from '@rev/schemas';
import type { ListenedAlbum, ListenedTrack } from '../src/adapters/navidrome/library.js';
import { TopTracksService, type TopTracksSource } from '../src/library/top-tracks.js';

const album = (id: string): Album => ({
  id,
  name: id,
  artist: 'Tool',
  artistId: 'ar-tool',
  year: 2000,
  genre: null,
  coverArtId: null,
  trackCount: 3,
  durationMs: 1,
  starred: false,
  createdAt: null,
  playedAt: null,
  releaseTypes: ['album'],
});

const track = (id: string, title: string, albumId: string, extra: Partial<Track> = {}): Track => ({
  id,
  title,
  album: albumId,
  albumId,
  artist: 'Tool',
  artistId: 'ar-tool',
  trackNumber: Number(id.replace(/\D/g, '')) || 1,
  discNumber: 1,
  year: null,
  genre: null,
  coverArtId: null,
  durationMs: 1,
  starred: false,
  format: null,
  bitRateKbps: null,
  sampleRateHz: null,
  bitDepth: null,
  sizeBytes: null,
  replayGain: null,
  ...extra,
});

const played = (t: Track, playCount: number): ListenedTrack => ({ track: t, playCount });

function source(overrides: Partial<TopTracksSource> = {}): TopTracksSource {
  const albums: ListenedAlbum[] = [
    { album: album('lateralus'), playCount: 50, genres: [] },
    { album: album('aenima'), playCount: 5, genres: [] },
    { album: album('live'), playCount: 0, genres: [] },
  ];
  const songs: Record<string, ListenedTrack[]> = {
    lateralus: [
      played(track('l1', 'The Grudge', 'lateralus'), 3),
      played(track('l2', 'Schism', 'lateralus'), 12),
      played(track('l3', 'Parabola', 'lateralus'), 0),
    ],
    aenima: [
      played(track('a1', 'Stinkfist', 'aenima'), 0),
      played(track('a2', 'Forty Six & 2', 'aenima'), 0),
      played(track('a3', 'Eulogy', 'aenima', { starred: true }), 0),
    ],
    live: [
      played(track('v1', 'Schism (Live)', 'live'), 1),
      played(track('v2', 'Guest Song', 'live', { artistId: 'ar-guest', artist: 'Guest' }), 40),
    ],
  };
  return {
    artist: async () => ({
      id: 'ar-tool',
      name: 'Tool',
      albumCount: 3,
      coverArtId: null,
      starred: false,
      albums: [],
    }),
    artistAlbums: async () => albums,
    albumSongs: async (id) => songs[id] ?? [],
    topSongs: async () => [track('x', 'Forty Six & 2', 'aenima')],
    ...overrides,
  };
}

const service = (src = source(), externalTimeoutMs = 1000) =>
  new TopTracksService(src, { now: () => 0, cacheMs: 60_000, externalTimeoutMs });

describe('artist top tracks', () => {
  it('ranks by your plays, then what the artist is known for, then your favourite albums', async () => {
    const titles = (await service().topTracks('ar-tool')).map((t) => t.title);
    expect(titles.slice(0, 3)).toEqual(['Schism', 'The Grudge', 'Forty Six & 2']);
    // Remaining songs follow the albums you play most.
    expect(titles.indexOf('Parabola')).toBeLessThan(titles.indexOf('Stinkfist'));
  });

  it('counts a song once across releases and ignores guests on shared releases', async () => {
    const titles = (await service().topTracks('ar-tool')).map((t) => t.title);
    expect(titles.filter((t) => t.toLowerCase().startsWith('schism'))).toEqual(['Schism']);
    expect(titles).not.toContain('Guest Song');
  });

  it("doesn't wait on a slow metadata agent", async () => {
    const slow = source({ topSongs: () => new Promise(() => {}) });
    const started = Date.now();
    const tracks = await service(slow, 20).topTracks('ar-tool');
    expect(Date.now() - started).toBeLessThan(1000);
    expect(tracks[0]?.title).toBe('Schism');
  });

  it('caches per artist', async () => {
    let calls = 0;
    const base = source();
    const s = service({ ...base, artistAlbums: async (id) => (calls++, base.artistAlbums(id)) });
    await s.topTracks('ar-tool');
    await s.topTracks('ar-tool');
    expect(calls).toBe(1);
  });
});
