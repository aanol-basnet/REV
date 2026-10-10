import type { Track } from '@rev/schemas';
import type { ListenedTrack, NavidromeLibrary } from '../adapters/navidrome/library.js';

export type TopTracksSource = Pick<
  NavidromeLibrary,
  'artist' | 'artistAlbums' | 'albumSongs' | 'topSongs'
>;

export interface TopTracksOptions {
  now: () => number;
  cacheMs: number;
  /** How long to wait for the metadata agent's top songs; they're a bonus, not a requirement. */
  externalTimeoutMs: number;
}

const LIMIT = 30;

/**
 * An artist's songs in the order a listener would want them: what you play most, then what the
 * artist is known for (Navidrome's metadata agents, e.g. Last.fm), then songs from the albums you
 * play most. The same song on several releases counts once. Your starred songs are always included.
 */
export class TopTracksService {
  private readonly cache = new Map<string, { tracks: Track[]; at: number }>();

  constructor(
    private readonly library: TopTracksSource,
    private readonly options: TopTracksOptions,
  ) {}

  async topTracks(artistId: string): Promise<Track[]> {
    const now = this.options.now();
    const hit = this.cache.get(artistId);
    if (hit && now - hit.at < this.options.cacheMs) return hit.tracks;

    const [artist, albums] = await Promise.all([
      this.library.artist(artistId),
      this.library.artistAlbums(artistId),
    ]);
    const [known, perAlbum] = await Promise.all([
      this.knownSongs(artist.name),
      Promise.all(
        albums.map((a) => this.library.albumSongs(a.album.id).catch(() => [] as ListenedTrack[])),
      ),
    ]);
    const albumPlays = new Map(albums.map((a) => [a.album.id, a.playCount]));
    const knownRank = new Map(known.map((t, i) => [normalize(t.title), i]));

    const songs = perAlbum.flat();
    // On compilations and splits, only this artist's songs.
    const own = songs.filter((s) => s.track.artistId === artistId);
    const candidates = own.length > 0 ? own : songs;

    const ranked = candidates
      .map((s) => ({
        s,
        known: knownRank.get(normalize(s.track.title)) ?? Number.MAX_SAFE_INTEGER,
        albumPlays: albumPlays.get(s.track.albumId ?? '') ?? 0,
      }))
      .sort(
        (a, b) =>
          b.s.playCount - a.s.playCount ||
          a.known - b.known ||
          b.albumPlays - a.albumPlays ||
          (a.s.track.trackNumber ?? 99) - (b.s.track.trackNumber ?? 99),
      );

    const seen = new Set<string>();
    const unique = ranked.filter(({ s }) => {
      const key = normalize(s.track.title);
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
    const top = unique.slice(0, LIMIT).map(({ s }) => s.track);
    const starredBeyond = unique
      .slice(LIMIT)
      .filter(({ s }) => s.track.starred)
      .map(({ s }) => s.track);
    const tracks = [...top, ...starredBeyond];
    this.cache.set(artistId, { tracks, at: now });
    return tracks;
  }

  private async knownSongs(artistName: string): Promise<Track[]> {
    let timer: NodeJS.Timeout | undefined;
    const timeout = new Promise<Track[]>((resolve) => {
      timer = setTimeout(() => resolve([]), this.options.externalTimeoutMs);
    });
    try {
      return await Promise.race([this.library.topSongs(artistName, 20).catch(() => []), timeout]);
    } finally {
      clearTimeout(timer);
    }
  }
}

/** "Bat Country (Live)" and "bat country" are the same song for ranking purposes. */
function normalize(title: string): string {
  return title
    .toLowerCase()
    .replace(/\s*[([].*?(live|remaster|version|edit|mix|demo|acoustic|mono|stereo).*?[)\]]/g, '')
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim();
}
