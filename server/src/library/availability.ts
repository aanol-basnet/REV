import type { AlbumAvailability, DownloadJobState } from '@rev/schemas';
import type { LidarrAlbumState } from '../adapters/lidarr/catalog.js';

export interface AvailabilityInputs {
  /** The album is in Navidrome, i.e. playable. */
  inNavidrome: boolean;
  /** Lidarr's view of the album, if Lidarr tracks it. */
  lidarr?: LidarrAlbumState | null;
  /** State of REV's download job for the album, if any (Phase 6). */
  job?: DownloadJobState | null;
}

/**
 * The single place where service-specific facts become an [AlbumAvailability].
 * Order matters: being playable beats everything; an active download beats Lidarr's bookkeeping.
 */
export function resolveAvailability({
  inNavidrome,
  lidarr,
  job,
}: AvailabilityInputs): AlbumAvailability {
  if (inNavidrome) return 'AVAILABLE';

  switch (job) {
    case 'REQUESTED':
    case 'CHECKING':
    case 'ADDED_TO_LIDARR':
    case 'WAITING_FOR_SOULARR':
    case 'SEARCHING':
      return 'REQUESTED';
    case 'QUEUED':
    case 'DOWNLOADING':
      return 'DOWNLOADING';
    case 'IMPORTING':
      return 'IMPORTING';
    case 'INDEXING':
      return 'INDEXING';
    case 'FAILED':
    case 'TIMEOUT':
      return 'FAILED';
    case 'AVAILABLE':
    case 'CANCELLED':
    case null:
    case undefined:
      break;
  }

  if (lidarr) {
    const complete = lidarr.trackCount > 0 && lidarr.trackFileCount >= lidarr.trackCount;
    if (complete) return 'IN_LIBRARY';
    if (lidarr.monitored) return 'REQUESTED';
  }
  return 'MISSING';
}
