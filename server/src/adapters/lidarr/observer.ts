import { z } from 'zod';
import type { LidarrClient } from './client.js';
import { LidarrAlbum, LidarrSystemStatus } from './schemas.js';

const QueueItem = z.object({
  albumId: z.number().nullish(),
  status: z.string().nullish(),
  /** downloading, downloadFailed, downloadFailedPending, importBlocked, importPending, importing, importFailed, imported, ignored */
  trackedDownloadState: z.string().nullish(),
  size: z.number().default(0),
  sizeleft: z.number().default(0),
});

const Command = z.object({
  name: z.string().nullish(),
  commandName: z.string().nullish(),
  message: z.string().nullish(),
  status: z.string(),
  queued: z.string().nullish(),
  body: z.object({ path: z.string().nullish() }).partial().nullish(),
});

export interface LidarrAlbumProgress {
  monitored: boolean;
  trackCount: number;
  trackFileCount: number;
}

export interface LidarrQueueEntry {
  lidarrAlbumId: number;
  state: string;
  /** 0–1, from bytes remaining. */
  progress: number | null;
}

/** A "DownloadedAlbumsScan" import, which is how Soularr hands finished downloads to Lidarr. */
export interface LidarrImport {
  /** Folder being imported; Soularr names it "Artist - Title (Year)". */
  path: string;
  running: boolean;
  /** Finished, and Lidarr couldn't import the files. */
  failed: boolean;
  queuedAt: number | null;
}

/** Read-only views of Lidarr used to follow a request through the pipeline. */
export class LidarrObserver {
  constructor(private readonly client: LidarrClient) {}

  /** Null when Lidarr no longer has the album. */
  async album(lidarrAlbumId: number): Promise<LidarrAlbumProgress | null> {
    try {
      const album = await this.client.get(`album/${lidarrAlbumId}`, {}, LidarrAlbum);
      const stats = album.statistics ?? { trackCount: 0, trackFileCount: 0 };
      return {
        monitored: album.monitored,
        trackCount: stats.trackCount,
        trackFileCount: stats.trackFileCount,
      };
    } catch (err) {
      if (err instanceof Error && 'kind' in err && err.kind === 'not_found') return null;
      throw err;
    }
  }

  /** Lidarr's own download-client queue (used when indexers, not Soularr, fetch the album). */
  async queue(lidarrAlbumIds: number[]): Promise<LidarrQueueEntry[]> {
    if (lidarrAlbumIds.length === 0) return [];
    const params = new URLSearchParams();
    for (const id of lidarrAlbumIds) params.append('albumIds', String(id));
    const items = await this.client.get(`queue/details?${params}`, {}, z.array(QueueItem));
    return items.flatMap((item) =>
      item.albumId
        ? [
            {
              lidarrAlbumId: item.albumId,
              state: item.trackedDownloadState ?? item.status ?? 'unknown',
              progress:
                item.size > 0 ? Math.min(1, Math.max(0, 1 - item.sizeleft / item.size)) : null,
            },
          ]
        : [],
    );
  }

  async recentImports(): Promise<LidarrImport[]> {
    const commands = await this.client.get('command', {}, z.array(Command));
    return commands.flatMap((c) => {
      const name = c.name ?? c.commandName ?? '';
      const path = c.body?.path;
      if (!/DownloadedAlbumsScan/i.test(name.replace(/\s/g, '')) || !path) return [];
      const finished = ['completed', 'failed', 'aborted', 'cancelled', 'orphaned'].includes(
        c.status,
      );
      return [
        {
          path,
          running: !finished,
          // Lidarr reports a scan that imported nothing as completed with a "Failed" message.
          failed: finished && (c.status !== 'completed' || /fail/i.test(c.message ?? '')),
          queuedAt: c.queued ? Date.parse(c.queued) : null,
        },
      ];
    });
  }

  async version(): Promise<string> {
    return (await this.client.get('system/status', {}, LidarrSystemStatus)).version;
  }
}
