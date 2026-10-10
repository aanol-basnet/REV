import { z } from 'zod';
import { UpstreamError } from '../errors.js';
import type { LidarrCatalog } from './catalog.js';
import type { LidarrClient } from './client.js';
import { LidarrAlbumList, LidarrArtist } from './schemas.js';

export interface LidarrRequestOptions {
  rootFolder?: string;
  qualityProfileId?: number;
  metadataProfileId?: number;
  /** Also run Lidarr's own indexer search. Soularr picks up monitored albums regardless. */
  searchOnRequest: boolean;
}

/** What happened in Lidarr when an album was requested. */
export interface RequestOutcome {
  lidarrAlbumId: number;
  lidarrArtistId: number;
  title: string;
  artistName: string;
  foreignArtistId: string | null;
  year: number | null;
  /** The album wasn't tracked by Lidarr; REV added it. */
  addedToLidarr: boolean;
  /** The album was tracked but unmonitored; REV turned monitoring on. */
  monitoredByRev: boolean;
  trackCount: number;
  trackFileCount: number;
}

// Lookup resources are posted back to Lidarr nearly verbatim, so unknown fields must survive.
const RawLookupAlbum = z
  .object({
    foreignAlbumId: z.string(),
    title: z.string().nullish(),
    releaseDate: z.string().nullish(),
    artist: z
      .object({ artistName: z.string().nullish(), foreignArtistId: z.string().nullish() })
      .passthrough(),
  })
  .passthrough();

const RootFolder = z.object({
  path: z.string(),
  accessible: z.boolean().default(true),
  defaultQualityProfileId: z.number(),
  defaultMetadataProfileId: z.number(),
});

const Ignored = z.unknown();

interface Defaults {
  rootFolderPath: string;
  qualityProfileId: number;
  metadataProfileId: number;
}

/**
 * Write-side Lidarr operations. Requests are made so that Soularr will act on them: the album and
 * its artist end up monitored (Lidarr's "wanted" list only includes monitored albums of monitored
 * artists), while nothing else of the artist's discography is monitored as a side effect.
 */
export class LidarrManager {
  private defaults: Defaults | null = null;

  constructor(
    private readonly client: LidarrClient,
    private readonly catalog: LidarrCatalog,
    private readonly options: LidarrRequestOptions,
  ) {}

  async request(foreignAlbumId: string): Promise<RequestOutcome> {
    try {
      const existing = await this.client.get('album', { foreignAlbumId }, LidarrAlbumList);
      const tracked = existing.find((a) => a.foreignAlbumId === foreignAlbumId);
      return tracked ? await this.monitorExisting(tracked) : await this.add(foreignAlbumId);
    } finally {
      this.catalog.invalidate();
    }
  }

  /** Undoes a request: stops monitoring the album. Files and the artist are left alone. */
  async withdraw(lidarrAlbumId: number): Promise<void> {
    await this.client.send(
      'PUT',
      'album/monitor',
      {},
      { albumIds: [lidarrAlbumId], monitored: false },
      Ignored,
    );
    this.catalog.invalidate();
  }

  private async monitorExisting(
    album: z.infer<typeof LidarrAlbumList>[number],
  ): Promise<RequestOutcome> {
    await this.ensureArtistMonitored(album.artistId);
    const monitoredByRev = !album.monitored;
    if (monitoredByRev) {
      await this.client.send(
        'PUT',
        'album/monitor',
        {},
        { albumIds: [album.id], monitored: true },
        Ignored,
      );
    }
    const stats = album.statistics ?? { trackCount: 0, trackFileCount: 0 };
    const complete = stats.trackCount > 0 && stats.trackFileCount >= stats.trackCount;
    if (!complete && this.options.searchOnRequest) {
      await this.client.send(
        'POST',
        'command',
        {},
        { name: 'AlbumSearch', albumIds: [album.id] },
        Ignored,
      );
    }
    return {
      lidarrAlbumId: album.id,
      lidarrArtistId: album.artistId,
      title: album.title ?? 'Unknown album',
      artistName: album.artist?.artistName ?? 'Unknown artist',
      foreignArtistId: album.artist?.foreignArtistId ?? null,
      year: yearOf(album.releaseDate),
      addedToLidarr: false,
      monitoredByRev,
      trackCount: stats.trackCount,
      trackFileCount: stats.trackFileCount,
    };
  }

  private async add(foreignAlbumId: string): Promise<RequestOutcome> {
    const lookup = await this.client.get(
      'album/lookup',
      { term: `lidarr:${foreignAlbumId}` },
      z.array(RawLookupAlbum),
    );
    const album = lookup.find((a) => a.foreignAlbumId === foreignAlbumId);
    if (!album)
      throw new UpstreamError(
        'lidarr',
        'not_found',
        `album ${foreignAlbumId} not found in catalog`,
      );

    const defaults = await this.resolveDefaults();
    // Lidarr adds the artist too if it doesn't know it yet. The artist is monitored (required for
    // the album to be "wanted") but with no other albums and no future releases.
    const body = {
      ...album,
      monitored: true,
      addOptions: { addType: 'manual', searchForNewAlbum: this.options.searchOnRequest },
      artist: {
        ...album.artist,
        qualityProfileId: defaults.qualityProfileId,
        metadataProfileId: defaults.metadataProfileId,
        rootFolderPath: defaults.rootFolderPath,
        monitored: true,
        monitorNewItems: 'none',
        addOptions: {
          monitor: 'none',
          albumsToMonitor: [],
          monitored: true,
          searchForMissingAlbums: false,
        },
      },
    };
    const added = await this.client.send(
      'POST',
      'album',
      {},
      body,
      z.object({ id: z.number(), artistId: z.number(), title: z.string().nullish() }),
    );
    // The artist may have existed already, possibly unmonitored.
    await this.ensureArtistMonitored(added.artistId);

    return {
      lidarrAlbumId: added.id,
      lidarrArtistId: added.artistId,
      title: album.title ?? added.title ?? 'Unknown album',
      artistName: album.artist.artistName ?? 'Unknown artist',
      foreignArtistId: album.artist.foreignArtistId ?? null,
      year: yearOf(album.releaseDate),
      addedToLidarr: true,
      monitoredByRev: false,
      trackCount: 0,
      trackFileCount: 0,
    };
  }

  private async ensureArtistMonitored(artistId: number): Promise<void> {
    const artist = await this.client.get(`artist/${artistId}`, {}, LidarrArtist);
    if (artist.monitored) return;
    await this.client.send(
      'PUT',
      'artist/editor',
      {},
      { artistIds: [artistId], monitored: true },
      Ignored,
    );
  }

  private async resolveDefaults(): Promise<Defaults> {
    if (this.defaults) return this.defaults;
    const folders = await this.client.get('rootfolder', {}, z.array(RootFolder));
    const folder = this.options.rootFolder
      ? folders.find(
          (f) => f.path.replace(/\/+$/, '') === this.options.rootFolder?.replace(/\/+$/, ''),
        )
      : folders.find((f) => f.accessible);
    if (!folder) {
      throw new UpstreamError(
        'lidarr',
        'protocol',
        this.options.rootFolder
          ? `root folder ${this.options.rootFolder} is not configured in Lidarr`
          : 'Lidarr has no accessible root folder',
      );
    }
    this.defaults = {
      rootFolderPath: folder.path,
      qualityProfileId: this.options.qualityProfileId ?? folder.defaultQualityProfileId,
      metadataProfileId: this.options.metadataProfileId ?? folder.defaultMetadataProfileId,
    };
    return this.defaults;
  }
}

function yearOf(date: string | null | undefined): number | null {
  if (!date) return null;
  const year = Number(date.slice(0, 4));
  return Number.isInteger(year) && year > 1000 ? year : null;
}
