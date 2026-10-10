import { z } from 'zod';

/**
 * Normalized album availability. Computed by the server so clients never interpret raw Lidarr/slskd state.
 *
 * - AVAILABLE    playable now (in Navidrome)
 * - IN_LIBRARY   Lidarr has the files, but Navidrome doesn't list the album yet
 * - MISSING      not owned; can be requested
 * - REQUESTED    wanted in Lidarr, no download in progress yet
 * - DOWNLOADING / IMPORTING / INDEXING   acquisition in progress
 * - FAILED       the last acquisition attempt failed
 */
export const AlbumAvailability = z.enum([
  'AVAILABLE',
  'IN_LIBRARY',
  'MISSING',
  'REQUESTED',
  'DOWNLOADING',
  'IMPORTING',
  'INDEXING',
  'FAILED',
]);
export type AlbumAvailability = z.infer<typeof AlbumAvailability>;

/** Lifecycle of a server-side acquisition job (Lidarr → Soularr → slskd → import → Navidrome). */
export const DownloadJobState = z.enum([
  'REQUESTED',
  'CHECKING',
  'ADDED_TO_LIDARR',
  'WAITING_FOR_SOULARR',
  'SEARCHING',
  'QUEUED',
  'DOWNLOADING',
  'IMPORTING',
  'INDEXING',
  'AVAILABLE',
  'FAILED',
  'TIMEOUT',
  'CANCELLED',
]);
export type DownloadJobState = z.infer<typeof DownloadJobState>;
