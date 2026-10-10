import { z } from 'zod';
import { MusicBrainzId } from './search.js';
import { AlbumAvailability, DownloadJobState } from './states.js';

/**
 * A request to acquire an album (Lidarr → Soularr → slskd → import → Navidrome). Service ids stay
 * on the server; clients see the normalized state only.
 */
export const DownloadJob = z.object({
  id: z.string(),
  /** MusicBrainz release-group id of the album. */
  catalogAlbumId: z.string(),
  catalogArtistId: z.string().nullable(),
  title: z.string(),
  artist: z.string(),
  year: z.number().int().nullable(),
  state: DownloadJobState,
  /** 0–1 while downloading, when known. */
  progress: z.number().min(0).max(1).nullable(),
  /** User-facing reason when FAILED or TIMEOUT. */
  error: z.string().nullable(),
  /** Navidrome album id once AVAILABLE. */
  libraryAlbumId: z.string().nullable(),
  startedAt: z.number().int(),
  updatedAt: z.number().int(),
  completedAt: z.number().int().nullable(),
});
export type DownloadJob = z.infer<typeof DownloadJob>;

export const DownloadJobList = z.object({ jobs: z.array(DownloadJob) });
export type DownloadJobList = z.infer<typeof DownloadJobList>;

export const CreateDownloadRequest = z.object({ catalogAlbumId: MusicBrainzId });
export type CreateDownloadRequest = z.infer<typeof CreateDownloadRequest>;

export const DownloadListQuery = z.object({
  active: z
    .enum(['true', 'false'])
    .transform((v) => v === 'true')
    .optional(),
});

/** Everything the app needs to show a catalog album's page. */
export const CatalogAlbumStatus = z.object({
  catalogAlbumId: z.string(),
  title: z.string(),
  artist: z.string(),
  year: z.number().int().nullable(),
  albumType: z.string().nullable(),
  trackCount: z.number().int().nullable(),
  availability: AlbumAvailability,
  /** Navidrome album id when playable. */
  libraryAlbumId: z.string().nullable(),
  /** The latest download job for this album, if any. */
  job: DownloadJob.nullable(),
});
export type CatalogAlbumStatus = z.infer<typeof CatalogAlbumStatus>;
