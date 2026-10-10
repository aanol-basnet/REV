import type { DownloadJobState } from '@rev/schemas';
import type { LidarrObserver } from '../adapters/lidarr/observer.js';
import type { NavidromeLibrary } from '../adapters/navidrome/library.js';
import type { SlskdClient } from '../adapters/slskd/client.js';
import { findLibraryAlbum } from '../library/matching.js';
import type { DownloadJobStore, JobRow } from './jobs.js';
import {
  assess,
  directoryMatches,
  importPathMatches,
  type JobFacts,
  searchMatches,
  type Timeouts,
} from './progress.js';
import { canTransition } from './state-machine.js';

export interface MonitorDeps {
  jobs: DownloadJobStore;
  lidarr: LidarrObserver | null;
  slskd: SlskdClient | null;
  library: NavidromeLibrary | null;
  timeouts: Timeouts;
  intervalMs: number;
  log: { warn(obj: object, msg: string): void; info(obj: object, msg: string): void };
  now?: () => number;
}

/** Activity from before the request (plus clock slack) belongs to someone else. */
const CLOCK_SLACK_MS = 60_000;

/** States owned by the request call itself; the monitor leaves them alone. */
const IN_FLIGHT = new Set<DownloadJobState>(['REQUESTED', 'CHECKING']);

/**
 * Follows active download jobs through the pipeline by observing Navidrome, Lidarr and slskd
 * (Soularr's actions show up there). Each tick gathers shared facts once, assesses every job,
 * and applies the result through the job store's state machine.
 */
export class DownloadMonitor {
  private timer: NodeJS.Timeout | null = null;
  private running = false;
  /** Jobs for which a Navidrome scan has been requested, so it happens once. */
  private readonly scanRequested = new Set<string>();
  private readonly now: () => number;

  constructor(private readonly deps: MonitorDeps) {
    this.now = deps.now ?? Date.now;
  }

  start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => void this.tick(), this.deps.intervalMs);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  /** One observation pass. Overlapping passes are skipped. */
  async tick(): Promise<void> {
    if (this.running) return;
    this.running = true;
    try {
      await this.observe();
    } catch (err) {
      this.deps.log.warn({ err }, 'download monitor pass failed');
    } finally {
      this.running = false;
    }
  }

  private async observe(): Promise<void> {
    const { jobs, lidarr, slskd } = this.deps;
    const active = jobs
      .list({ activeOnly: true })
      .filter((j) => !IN_FLIGHT.has(j.state as DownloadJobState));
    if (active.length === 0) return;

    const lidarrIds = active.flatMap((j) => (j.lidarrAlbumId === null ? [] : [j.lidarrAlbumId]));
    const [transfers, searches, imports, queue] = await Promise.all([
      this.attempt('slskd transfers', () => slskd?.downloads()),
      this.attempt('slskd searches', () => slskd?.searches()),
      this.attempt('Lidarr imports', () => lidarr?.recentImports()),
      this.attempt('Lidarr queue', () => lidarr?.queue(lidarrIds)),
    ]);

    for (const job of active) {
      const album = { title: job.title, artist: job.artist };
      const since = job.startedAt - CLOCK_SLACK_MS;
      const facts: JobFacts = {
        now: this.now(),
        transfers: transfers
          ?.filter((t) => (t.requestedAt ?? since) >= since && directoryMatches(t.directory, album))
          .map((t) => ({ phase: t.phase, size: t.size, bytesTransferred: t.bytesTransferred })),
        searches: searches
          ?.filter((s) => (s.startedAt ?? since) >= since && searchMatches(s.searchText, album))
          .map((s) => ({ active: s.active })),
        imports: imports
          ?.filter((i) => (i.queuedAt ?? since) >= since && importPathMatches(i.path, album))
          .map((i) => ({ running: i.running, failed: i.failed })),
        queue: queue
          ?.filter((q) => q.lidarrAlbumId === job.lidarrAlbumId)
          .map((q) => ({ state: q.state, progress: q.progress })),
      };
      if (job.lidarrAlbumId !== null && lidarr) {
        const id = job.lidarrAlbumId;
        const observed = await this.attempt('Lidarr album', () => lidarr.album(id));
        if (observed !== undefined) facts.lidarr = observed;
      }
      const libraryAlbumId = await this.libraryAlbumId(job);
      if (libraryAlbumId !== undefined) facts.libraryAlbumId = libraryAlbumId;

      this.apply(job, facts);
      // Jobs that started out already imported still deserve a scan nudge.
      if (job.state === 'INDEXING') void this.requestScan(job.id);
    }
  }

  private apply(job: JobRow, facts: JobFacts): void {
    const { jobs, timeouts } = this.deps;
    const result = assess(
      { state: job.state as DownloadJobState, startedAt: job.startedAt, updatedAt: job.updatedAt },
      facts,
      timeouts,
    );
    if (!result) return;

    if (result.state === job.state) {
      const progress = result.progress ?? null;
      if (progress !== job.progress) jobs.update(job.id, { progress });
      return;
    }
    if (!canTransition(job.state as DownloadJobState, result.state)) return;

    jobs.transition(job.id, result.state, {
      ...(result.progress !== undefined ? { progress: result.progress } : {}),
      ...(result.error !== undefined ? { error: result.error } : {}),
      ...(result.libraryAlbumId ? { libraryAlbumId: result.libraryAlbumId } : {}),
    });
    this.deps.log.info(
      { jobId: job.id, from: job.state, to: result.state },
      'download job progressed',
    );
    if (result.state === 'INDEXING') void this.requestScan(job.id);
  }

  /** Files are in place; nudge Navidrome instead of waiting for its scheduled scan. */
  private async requestScan(jobId: string): Promise<void> {
    if (!this.deps.library || this.scanRequested.has(jobId)) return;
    this.scanRequested.add(jobId);
    const started = await this.attempt('Navidrome scan', () => this.deps.library?.startScan());
    if (started === false) {
      this.deps.log.info(
        {},
        'Navidrome account cannot start scans; waiting for the scheduled scan',
      );
    }
  }

  /** Navidrome id if playable, null if not, undefined if Navidrome couldn't be asked. */
  private async libraryAlbumId(job: JobRow): Promise<string | null | undefined> {
    const library = this.deps.library;
    if (!library) return undefined;
    const found = await this.attempt('Navidrome lookup', () =>
      findLibraryAlbum(library, { title: job.title, artist: job.artist }),
    );
    return found === undefined ? undefined : (found?.id ?? null);
  }

  private async attempt<T>(
    what: string,
    run: () => Promise<T> | undefined,
  ): Promise<T | undefined> {
    try {
      return await run();
    } catch (err) {
      this.deps.log.warn({ err }, `${what} unavailable`);
      return undefined;
    }
  }
}
