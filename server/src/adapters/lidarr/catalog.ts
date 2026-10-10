import { TtlCache } from '../../library/ttl-cache.js';
import type { LidarrClient } from './client.js';
import { type LidarrAlbum, LidarrAlbumList, type LidarrStatistics } from './schemas.js';

/** A release group from the MusicBrainz catalog, via Lidarr's metadata lookup. */
export interface CatalogAlbum {
  foreignAlbumId: string;
  title: string;
  artistName: string;
  foreignArtistId: string | null;
  year: number | null;
  albumType: string | null;
}

/** What Lidarr's own library knows about an album. */
export interface LidarrAlbumState {
  lidarrAlbumId: number;
  lidarrArtistId: number;
  monitored: boolean;
  trackFileCount: number;
  trackCount: number;
}

const INDEX_TTL_MS = 60_000;
/** Catalog metadata changes rarely; lookups are slow (they go to Lidarr's metadata server). */
const LOOKUP_TTL_MS = 5 * 60_000;
const LOOKUP_CACHE_SIZE = 200;

/**
 * Read-side Lidarr access: looking up MusicBrainz release groups and knowing which albums Lidarr
 * already tracks. The library index is cached briefly; it's one local request for every album.
 */
export class LidarrCatalog {
  private index: { at: number; byForeignAlbumId: Map<string, LidarrAlbumState> } | null = null;
  private indexLoading: Promise<Map<string, LidarrAlbumState>> | null = null;
  private readonly albumLookups: TtlCache<CatalogAlbum[]>;

  constructor(
    private readonly client: LidarrClient,
    private readonly now: () => number = Date.now,
  ) {
    this.albumLookups = new TtlCache(LOOKUP_TTL_MS, LOOKUP_CACHE_SIZE, now);
  }

  searchAlbums(term: string): Promise<CatalogAlbum[]> {
    return this.albumLookups.getOrLoad(lookupKey(term), async () => {
      const albums = await this.client.get('album/lookup', { term }, LidarrAlbumList);
      return albums.map(toCatalogAlbum).filter((a): a is CatalogAlbum => a !== null);
    });
  }

  /** Lidarr's state for each of [foreignAlbumIds] that it tracks. */
  async libraryStates(foreignAlbumIds: string[]): Promise<Map<string, LidarrAlbumState>> {
    const index = await this.libraryIndex();
    const result = new Map<string, LidarrAlbumState>();
    for (const id of foreignAlbumIds) {
      const state = index.get(id);
      if (state) result.set(id, state);
    }
    return result;
  }

  /** Forget cached library state, e.g. after REV changes something in Lidarr. */
  invalidate(): void {
    this.index = null;
  }

  private async libraryIndex(): Promise<Map<string, LidarrAlbumState>> {
    if (this.index && this.now() - this.index.at < INDEX_TTL_MS) return this.index.byForeignAlbumId;
    this.indexLoading ??= this.client
      .get('album', {}, LidarrAlbumList)
      .then((albums) => {
        const byForeignAlbumId = new Map<string, LidarrAlbumState>();
        for (const album of albums) {
          if (album.foreignAlbumId) byForeignAlbumId.set(album.foreignAlbumId, toState(album));
        }
        this.index = { at: this.now(), byForeignAlbumId };
        return byForeignAlbumId;
      })
      .finally(() => {
        this.indexLoading = null;
      });
    return this.indexLoading;
  }
}

function lookupKey(term: string): string {
  return term.trim().toLowerCase().replace(/\s+/g, ' ');
}

function toCatalogAlbum(a: LidarrAlbum): CatalogAlbum | null {
  if (!a.foreignAlbumId || !a.title) return null;
  return {
    foreignAlbumId: a.foreignAlbumId,
    title: a.title,
    artistName: a.artist?.artistName ?? 'Unknown artist',
    foreignArtistId: a.artist?.foreignArtistId ?? null,
    year: yearOf(a.releaseDate),
    albumType: a.albumType || null,
  };
}

function toState(a: LidarrAlbum): LidarrAlbumState {
  const stats: LidarrStatistics = a.statistics ?? {
    trackFileCount: 0,
    trackCount: 0,
    totalTrackCount: 0,
    sizeOnDisk: 0,
  };
  return {
    lidarrAlbumId: a.id,
    lidarrArtistId: a.artistId,
    monitored: a.monitored,
    trackFileCount: stats.trackFileCount,
    trackCount: stats.trackCount,
  };
}

function yearOf(date: string | null | undefined): number | null {
  if (!date) return null;
  const year = Number(date.slice(0, 4));
  return Number.isInteger(year) && year > 1000 ? year : null;
}
