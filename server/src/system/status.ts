import type { SystemStatus } from '@rev/schemas';
import type { NavidromeLibrary } from '../adapters/navidrome/library.js';
import type { SlskdClient } from '../adapters/slskd/client.js';
import type { DownloadJobStore } from '../downloads/jobs.js';
import { TtlCache } from '../library/ttl-cache.js';
import type { HealthMonitor } from './health.js';

/** Soularr runs every few minutes; activity within this window counts as "active". */
const SOULARR_ACTIVE_WINDOW_MS = 15 * 60_000;
const HEALTH_MAX_AGE_MS = 30_000;

/** The system page: service health, library size and download activity. */
export class SystemStatusService {
  private readonly libraryStats = new TtlCache<NonNullable<SystemStatus['library']>>(5 * 60_000, 1);

  constructor(
    private readonly health: HealthMonitor,
    private readonly library: NavidromeLibrary | null,
    private readonly slskd: SlskdClient | null,
    private readonly jobs: DownloadJobStore,
    private readonly now: () => number = Date.now,
  ) {}

  async status(): Promise<SystemStatus> {
    await this.health.fresh(HEALTH_MAX_AGE_MS);
    const [library, soularr] = await Promise.all([this.librarySize(), this.soularr()]);
    return {
      services: {
        navidrome: this.health.status('navidrome'),
        lidarr: this.health.status('lidarr'),
        slskd: this.health.status('slskd'),
        soularr,
      },
      library,
      downloads: { active: this.jobs.list({ activeOnly: true }).length },
    };
  }

  private async librarySize(): Promise<SystemStatus['library']> {
    const library = this.library;
    if (!library) return null;
    try {
      const stats = await this.libraryStats.getOrLoad('stats', () => library.stats());
      return { artists: stats.artists, albums: stats.albums, tracks: stats.tracks };
    } catch {
      return null;
    }
  }

  private async soularr(): Promise<SystemStatus['services']['soularr']> {
    if (!this.slskd) return { status: 'unknown', lastActivityAt: null };
    try {
      const searches = await this.slskd.searches();
      const last = Math.max(0, ...searches.map((s) => s.startedAt ?? 0));
      if (last === 0) return { status: 'unknown', lastActivityAt: null };
      return {
        status: this.now() - last < SOULARR_ACTIVE_WINDOW_MS ? 'active' : 'idle',
        lastActivityAt: last,
      };
    } catch {
      return { status: 'unknown', lastActivityAt: null };
    }
  }
}
