import { randomUUID } from 'node:crypto';
import { and, desc, eq, inArray, notInArray } from 'drizzle-orm';
import type { DownloadJob, DownloadJobState } from '@rev/schemas';
import type { Db } from '../db/client.js';
import { downloadJobs } from '../db/schema.js';
import { canTransition, InvalidTransitionError, TERMINAL_STATES } from './state-machine.js';

export type JobRow = typeof downloadJobs.$inferSelect;

export interface NewJob {
  catalogAlbumId: string;
  catalogArtistId: string | null;
  title: string;
  artist: string;
  year: number | null;
  requestedBy: number | null;
}

/** Fields that may change alongside a state transition. */
export type JobUpdate = Partial<
  Pick<
    JobRow,
    | 'catalogArtistId'
    | 'title'
    | 'artist'
    | 'year'
    | 'progress'
    | 'error'
    | 'lidarrAlbumId'
    | 'lidarrArtistId'
    | 'addedToLidarr'
    | 'monitoredByRev'
    | 'libraryAlbumId'
  >
>;

const TERMINAL = [...TERMINAL_STATES];

/** Persistence for download jobs. Every state change goes through the state machine. */
export class DownloadJobStore {
  constructor(
    private readonly db: Db,
    private readonly now: () => number = Date.now,
    /** Called after every write, with the row before the change (null for a new job). */
    private readonly onChange: (job: JobRow, previous: JobRow | null) => void = () => {},
  ) {}

  create(job: NewJob): JobRow {
    const now = this.now();
    const row = this.db
      .insert(downloadJobs)
      .values({ id: randomUUID(), ...job, state: 'REQUESTED', startedAt: now, updatedAt: now })
      .returning()
      .get();
    this.onChange(row, null);
    return row;
  }

  get(id: string): JobRow | undefined {
    return this.db.select().from(downloadJobs).where(eq(downloadJobs.id, id)).get();
  }

  list(options: { activeOnly?: boolean; limit?: number } = {}): JobRow[] {
    const query = this.db.select().from(downloadJobs);
    const filtered = options.activeOnly
      ? query.where(notInArray(downloadJobs.state, TERMINAL))
      : query;
    return filtered
      .orderBy(desc(downloadJobs.startedAt))
      .limit(options.limit ?? 200)
      .all();
  }

  /** Deletes finished jobs (available, failed, cancelled, timed out); returns how many. */
  deleteFinished(): number {
    return this.db.delete(downloadJobs).where(inArray(downloadJobs.state, TERMINAL)).run().changes;
  }

  /** The unfinished job for an album, if any. There is at most one. */
  activeFor(catalogAlbumId: string): JobRow | undefined {
    return this.db
      .select()
      .from(downloadJobs)
      .where(
        and(
          eq(downloadJobs.catalogAlbumId, catalogAlbumId),
          notInArray(downloadJobs.state, TERMINAL),
        ),
      )
      .get();
  }

  latestFor(catalogAlbumId: string): JobRow | undefined {
    return this.db
      .select()
      .from(downloadJobs)
      .where(eq(downloadJobs.catalogAlbumId, catalogAlbumId))
      .orderBy(desc(downloadJobs.startedAt))
      .limit(1)
      .get();
  }

  /** Moves a job to [to], validating the transition. Terminal states stamp completedAt. */
  transition(id: string, to: DownloadJobState, update: JobUpdate = {}): JobRow {
    const job = this.get(id);
    if (!job) throw new Error(`download job ${id} not found`);
    const from = job.state as DownloadJobState;
    if (!canTransition(from, to)) throw new InvalidTransitionError(from, to);
    const now = this.now();
    const row = this.db
      .update(downloadJobs)
      .set({
        ...update,
        state: to,
        updatedAt: now,
        ...(TERMINAL_STATES.has(to) ? { completedAt: now } : {}),
        ...(to === 'AVAILABLE' ? { progress: 1 } : {}),
      })
      .where(eq(downloadJobs.id, id))
      .returning()
      .get();
    this.onChange(row, job);
    return row;
  }

  /** Updates details without changing state (e.g. progress). */
  update(id: string, update: JobUpdate): JobRow | undefined {
    const previous = this.get(id);
    if (!previous) return undefined;
    const row = this.db
      .update(downloadJobs)
      .set({ ...update, updatedAt: this.now() })
      .where(eq(downloadJobs.id, id))
      .returning()
      .get();
    this.onChange(row, previous);
    return row;
  }
}

export function toApi(job: JobRow): DownloadJob {
  return {
    id: job.id,
    catalogAlbumId: job.catalogAlbumId,
    catalogArtistId: job.catalogArtistId,
    title: job.title,
    artist: job.artist,
    year: job.year,
    state: job.state as DownloadJobState,
    progress: job.progress,
    error: job.error,
    libraryAlbumId: job.libraryAlbumId,
    startedAt: job.startedAt,
    updatedAt: job.updatedAt,
    completedAt: job.completedAt,
  };
}
