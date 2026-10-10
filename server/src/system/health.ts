import type { ServiceName, ServiceStatus } from '@rev/schemas';
import type { LidarrObserver } from '../adapters/lidarr/observer.js';
import type { NavidromeLibrary } from '../adapters/navidrome/library.js';
import type { SlskdClient } from '../adapters/slskd/client.js';
import { UpstreamError } from '../adapters/errors.js';

export interface HealthDeps {
  library: NavidromeLibrary | null;
  lidarr: LidarrObserver | null;
  slskd: SlskdClient | null;
  intervalMs: number;
  onChange: (service: ServiceName, status: ServiceStatus) => void;
  now?: () => number;
}

const NOT_CONFIGURED: ServiceStatus = {
  status: 'not_configured',
  version: null,
  detail: null,
  checkedAt: null,
};

/** Periodically checks each backend service and reports changes. */
export class HealthMonitor {
  private readonly statuses = new Map<ServiceName, ServiceStatus>();
  private timer: NodeJS.Timeout | null = null;
  private readonly now: () => number;

  constructor(private readonly deps: HealthDeps) {
    this.now = deps.now ?? Date.now;
  }

  start(): void {
    if (this.timer) return;
    void this.checkAll();
    this.timer = setInterval(() => void this.checkAll(), this.deps.intervalMs);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  status(service: ServiceName): ServiceStatus {
    return this.statuses.get(service) ?? NOT_CONFIGURED;
  }

  /** Checks now if the last check is older than [maxAgeMs]. */
  async fresh(maxAgeMs: number): Promise<void> {
    const services: ServiceName[] = ['navidrome', 'lidarr', 'slskd'];
    const stale = services.some((s) => {
      const checked = this.statuses.get(s)?.checkedAt;
      return checked == null || this.now() - checked > maxAgeMs;
    });
    if (stale) await this.checkAll();
  }

  async checkAll(): Promise<void> {
    const { library, lidarr, slskd } = this.deps;
    await Promise.all([
      this.check('navidrome', library, async (l) => {
        await l.ping();
        return { version: null, degraded: null };
      }),
      this.check('lidarr', lidarr, async (l) => ({ version: await l.version(), degraded: null })),
      this.check('slskd', slskd, async (s) => {
        const health = await s.health();
        return {
          version: health.version,
          degraded: health.connected ? null : 'Not connected to the Soulseek network',
        };
      }),
    ]);
  }

  private async check<S>(
    service: ServiceName,
    client: S | null,
    probe: (client: S) => Promise<{ version: string | null; degraded: string | null }>,
  ): Promise<void> {
    let next: ServiceStatus;
    if (!client) {
      next = NOT_CONFIGURED;
    } else {
      try {
        const { version, degraded } = await probe(client);
        next = {
          status: degraded ? 'degraded' : 'online',
          version,
          detail: degraded,
          checkedAt: this.now(),
        };
      } catch (err) {
        next = { status: 'offline', version: null, detail: describe(err), checkedAt: this.now() };
      }
    }
    const previous = this.statuses.get(service);
    this.statuses.set(service, next);
    if (!previous || previous.status !== next.status || previous.detail !== next.detail) {
      this.deps.onChange(service, next);
    }
  }
}

function describe(err: unknown): string {
  if (err instanceof UpstreamError) {
    switch (err.kind) {
      case 'auth':
        return 'Rejected REV’s credentials';
      case 'unavailable':
        return 'Not reachable';
      default:
        return 'Unexpected response';
    }
  }
  return 'Check failed';
}
