import { z } from 'zod';
import { DownloadJob } from './downloads.js';

export const ServiceName = z.enum(['navidrome', 'lidarr', 'slskd']);
export type ServiceName = z.infer<typeof ServiceName>;

/**
 * online: reachable and working. degraded: reachable but not fully working (e.g. slskd not
 * connected to Soulseek). offline: unreachable or rejecting REV. not_configured: not set up.
 */
export const ServiceState = z.enum(['online', 'degraded', 'offline', 'not_configured']);
export type ServiceState = z.infer<typeof ServiceState>;

export const ServiceStatus = z.object({
  status: ServiceState,
  version: z.string().nullable(),
  /** Short explanation when not online. */
  detail: z.string().nullable(),
  checkedAt: z.number().int().nullable(),
});
export type ServiceStatus = z.infer<typeof ServiceStatus>;

/** Soularr has no API; REV infers activity from the searches it makes in slskd. */
export const SoularrStatus = z.object({
  status: z.enum(['active', 'idle', 'unknown']),
  lastActivityAt: z.number().int().nullable(),
});

export const SystemStatus = z.object({
  services: z.object({
    navidrome: ServiceStatus,
    lidarr: ServiceStatus,
    slskd: ServiceStatus,
    soularr: SoularrStatus,
  }),
  library: z
    .object({
      artists: z.number().int(),
      albums: z.number().int(),
      tracks: z.number().int().nullable(),
    })
    .nullable(),
  downloads: z.object({ active: z.number().int() }),
});
export type SystemStatus = z.infer<typeof SystemStatus>;

/** Messages pushed over the `/api/events` WebSocket. */
export const ServerEvent = z.discriminatedUnion('type', [
  z.object({ type: z.literal('download.created'), data: DownloadJob }),
  z.object({ type: z.literal('download.updated'), data: DownloadJob }),
  z.object({ type: z.literal('download.completed'), data: DownloadJob }),
  z.object({ type: z.literal('download.failed'), data: DownloadJob }),
  z.object({ type: z.literal('library.updated'), data: z.object({ libraryAlbumId: z.string() }) }),
  z.object({ type: z.literal('lyrics.updated'), data: z.object({ trackId: z.string() }) }),
  z.object({ type: z.literal('system.navidrome.status'), data: ServiceStatus }),
  z.object({ type: z.literal('system.lidarr.status'), data: ServiceStatus }),
  z.object({ type: z.literal('system.slskd.status'), data: ServiceStatus }),
]);
export type ServerEvent = z.infer<typeof ServerEvent>;
