import type { Album, CatalogAlbumStatus, DownloadJob, DownloadJobState } from '@rev/schemas';
import { HttpError } from '../api/errors.js';
import { UpstreamError } from '../adapters/errors.js';
import type { CatalogAlbum, LidarrCatalog } from '../adapters/lidarr/catalog.js';
import type { LidarrManager } from '../adapters/lidarr/manager.js';
import type { NavidromeLibrary } from '../adapters/navidrome/library.js';
import { resolveAvailability } from '../library/availability.js';
import { findLibraryAlbum } from '../library/matching.js';
import { type DownloadJobStore, type JobRow, toApi } from './jobs.js';
import { isTerminal } from './state-machine.js';

export interface AcquisitionDeps {
  jobs: DownloadJobStore;
  catalog: LidarrCatalog | null;
  manager: LidarrManager | null;
  library: NavidromeLibrary | null;
  log: { warn(obj: object, msg: string): void; error(obj: object, msg: string): void };
}

/**
 * Requests albums and reports their status. A request becomes a download job; this phase takes it
 * as far as Lidarr (added, monitored, searched). Observing the rest of the pipeline is Phase 6.
 */
export class AcquisitionService {
  /** Concurrent requests for the same album share one attempt instead of racing. */
  private readonly inFlight = new Map<string, Promise<DownloadJob>>();

  constructor(private readonly deps: AcquisitionDeps) {}

  list(activeOnly: boolean): DownloadJob[] {
    return this.deps.jobs.list({ activeOnly }).map(toApi);
  }

  /** Clears the history of finished jobs. Jobs still in progress are untouched. */
  clearFinished(): number {
    return this.deps.jobs.deleteFinished();
  }

  get(id: string): DownloadJob {
    const job = this.deps.jobs.get(id);
    if (!job) throw new HttpError(404, 'NOT_FOUND', 'Download not found');
    return toApi(job);
  }

  async albumStatus(catalogAlbumId: string): Promise<CatalogAlbumStatus> {
    const info = await this.catalogInfo(catalogAlbumId);
    const [libraryAlbum, lidarr] = await Promise.all([
      this.findInLibrary(info),
      this.lidarrState(catalogAlbumId),
    ]);
    const latest = this.deps.jobs.latestFor(catalogAlbumId) ?? null;
    const activeState =
      latest && !isTerminal(latest.state as DownloadJobState)
        ? (latest.state as DownloadJobState)
        : null;
    return {
      catalogAlbumId,
      title: info.title,
      artist: info.artistName,
      year: info.year,
      albumType: info.albumType,
      trackCount: lidarr?.trackCount || null,
      availability: resolveAvailability({
        inNavidrome: libraryAlbum !== null,
        lidarr,
        job:
          activeState ??
          (latest?.state === 'FAILED' || latest?.state === 'TIMEOUT' ? latest.state : null),
      }),
      libraryAlbumId: libraryAlbum?.id ?? null,
      job: latest ? toApi(latest) : null,
    };
  }

  request(catalogAlbumId: string, requestedBy: number | null): Promise<DownloadJob> {
    if (!this.deps.manager) {
      throw new HttpError(503, 'LIDARR_NOT_CONFIGURED', 'Downloading is not set up on this server');
    }
    const active = this.deps.jobs.activeFor(catalogAlbumId);
    if (active) return Promise.resolve(toApi(active));

    let attempt = this.inFlight.get(catalogAlbumId);
    if (!attempt) {
      attempt = this.runRequest(catalogAlbumId, requestedBy).finally(() =>
        this.inFlight.delete(catalogAlbumId),
      );
      this.inFlight.set(catalogAlbumId, attempt);
    }
    return attempt;
  }

  async cancel(id: string): Promise<DownloadJob> {
    const job = this.deps.jobs.get(id);
    if (!job) throw new HttpError(404, 'NOT_FOUND', 'Download not found');
    if (isTerminal(job.state as DownloadJobState)) return toApi(job);

    const cancelled = this.deps.jobs.transition(id, 'CANCELLED');
    await this.undoLidarrChanges(job);
    return toApi(cancelled);
  }

  private async runRequest(
    catalogAlbumId: string,
    requestedBy: number | null,
  ): Promise<DownloadJob> {
    const { jobs, manager, log } = this.deps;
    if (!manager) throw new HttpError(503, 'LIDARR_NOT_CONFIGURED', 'Downloading is not set up');
    const info = await this.catalogInfo(catalogAlbumId);
    const job = jobs.create({
      catalogAlbumId,
      catalogArtistId: info.foreignArtistId,
      title: info.title,
      artist: info.artistName,
      year: info.year,
      requestedBy,
    });
    jobs.transition(job.id, 'CHECKING');

    const owned = await this.findInLibrary(info);
    if (owned) return toApi(jobs.transition(job.id, 'AVAILABLE', { libraryAlbumId: owned.id }));

    try {
      const outcome = await manager.request(catalogAlbumId);
      const complete = outcome.trackCount > 0 && outcome.trackFileCount >= outcome.trackCount;
      // Files already in Lidarr: only Navidrome's scan is outstanding.
      return toApi(
        jobs.transition(job.id, complete ? 'INDEXING' : 'ADDED_TO_LIDARR', {
          lidarrAlbumId: outcome.lidarrAlbumId,
          lidarrArtistId: outcome.lidarrArtistId,
          addedToLidarr: outcome.addedToLidarr,
          monitoredByRev: outcome.monitoredByRev,
          catalogArtistId: outcome.foreignArtistId ?? info.foreignArtistId,
        }),
      );
    } catch (err) {
      log.error({ err, catalogAlbumId }, 'album request failed');
      return toApi(jobs.transition(job.id, 'FAILED', { error: userMessage(err) }));
    }
  }

  /** Stops monitoring what REV started monitoring, unless the files already arrived. */
  private async undoLidarrChanges(job: JobRow): Promise<void> {
    const { manager, log } = this.deps;
    if (!manager || job.lidarrAlbumId === null || !(job.addedToLidarr || job.monitoredByRev))
      return;
    try {
      const state = await this.lidarrState(job.catalogAlbumId);
      const complete =
        state !== null && state.trackCount > 0 && state.trackFileCount >= state.trackCount;
      if (!complete) await manager.withdraw(job.lidarrAlbumId);
    } catch (err) {
      log.warn({ err, jobId: job.id }, 'could not withdraw cancelled request from Lidarr');
    }
  }

  private async catalogInfo(catalogAlbumId: string): Promise<CatalogAlbum> {
    const { catalog } = this.deps;
    if (!catalog)
      throw new HttpError(
        503,
        'LIDARR_NOT_CONFIGURED',
        'The music catalog is not set up on this server',
      );
    const matches = await catalog.searchAlbums(`lidarr:${catalogAlbumId}`);
    const info = matches.find((a) => a.foreignAlbumId === catalogAlbumId);
    if (!info) throw new HttpError(404, 'NOT_FOUND', 'Album not found in the catalog');
    return info;
  }

  private async lidarrState(catalogAlbumId: string) {
    if (!this.deps.catalog) return null;
    try {
      return (await this.deps.catalog.libraryStates([catalogAlbumId])).get(catalogAlbumId) ?? null;
    } catch (err) {
      this.deps.log.warn({ err }, 'Lidarr library state unavailable');
      return null;
    }
  }

  /** The playable library copy of a catalog album, if any. Lookup failures count as "not found". */
  private async findInLibrary(info: CatalogAlbum): Promise<Album | null> {
    if (!this.deps.library) return null;
    try {
      return await findLibraryAlbum(this.deps.library, {
        title: info.title,
        artist: info.artistName,
      });
    } catch (err) {
      this.deps.log.warn({ err }, 'Navidrome lookup failed while checking an album');
      return null;
    }
  }
}

/** Failure reasons worded for the user; service details stay in the logs and system status. */
function userMessage(err: unknown): string {
  if (err instanceof UpstreamError) {
    switch (err.kind) {
      case 'unavailable':
        return "The library manager isn't responding. Try again later.";
      case 'auth':
        return "REV couldn't sign in to the library manager.";
      case 'not_found':
        return "This album couldn't be found in the catalog.";
      case 'protocol':
        return 'The library manager rejected the request.';
    }
  }
  return 'The request failed unexpectedly.';
}
