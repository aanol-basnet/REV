import type { DownloadJobState } from '@rev/schemas';
import type { TransferPhase } from '../adapters/slskd/client.js';
import { artistKey, basicNormalize, titleKey } from '../library/normalize.js';

/**
 * What was observed about one requested album in one check. `undefined` means "couldn't be
 * observed this time" (service down); it never moves a job.
 */
export interface JobFacts {
  now: number;
  /** Navidrome album id when the album is playable. */
  libraryAlbumId?: string | null | undefined;
  /** Lidarr's view; null when Lidarr no longer has the album. */
  lidarr?: { monitored: boolean; trackCount: number; trackFileCount: number } | null | undefined;
  /** Lidarr download-client queue entries for the album (indexer downloads). */
  queue?: { state: string; progress: number | null }[] | undefined;
  /** Lidarr imports of this album's folder started since the request (Soularr's hand-off). */
  imports?: { running: boolean; failed: boolean }[] | undefined;
  /** slskd searches for this album started since the request (Soularr searching). */
  searches?: { active: boolean }[] | undefined;
  /** slskd transfers belonging to this album requested since the request. */
  transfers?: { phase: TransferPhase; size: number; bytesTransferred: number }[] | undefined;
}

export interface JobSnapshot {
  state: DownloadJobState;
  startedAt: number;
  updatedAt: number;
}

export interface Assessment {
  state: DownloadJobState;
  progress?: number | null;
  error?: string | null;
  libraryAlbumId?: string;
}

export interface Timeouts {
  /** Give up on a request after this long without it becoming playable. */
  overallMs: number;
  /** Give up waiting for Navidrome to list an album Lidarr has fully imported. */
  indexingMs: number;
}

export const FAILURE_MESSAGES = {
  importFailed: "The downloaded files couldn't be added to your library.",
  removed: 'The album was removed from your library manager.',
  withdrawn: 'The request was withdrawn in your library manager.',
  timeout: "Couldn't find a source for this album in time.",
  indexingTimeout: "The album was imported but hasn't appeared in your library.",
} as const;

/**
 * Decides where a job is from the evidence, strongest evidence first:
 * playable → files in Lidarr → import running/failed → transferring → searching → waiting.
 * Pure: the monitor gathers facts, this decides, the store enforces the state machine.
 */
export function assess(job: JobSnapshot, facts: JobFacts, timeouts: Timeouts): Assessment | null {
  if (facts.libraryAlbumId) return { state: 'AVAILABLE', libraryAlbumId: facts.libraryAlbumId };

  const lidarr = facts.lidarr;
  if (lidarr === null) return { state: 'FAILED', error: FAILURE_MESSAGES.removed };
  if (lidarr) {
    const complete = lidarr.trackCount > 0 && lidarr.trackFileCount >= lidarr.trackCount;
    if (complete) {
      if (job.state === 'INDEXING' && facts.now - job.updatedAt > timeouts.indexingMs) {
        return { state: 'TIMEOUT', error: FAILURE_MESSAGES.indexingTimeout };
      }
      return { state: 'INDEXING', progress: 1 };
    }
    if (!lidarr.monitored) return { state: 'CANCELLED', error: FAILURE_MESSAGES.withdrawn };
  }

  const next = pipelineState(facts);
  // Only give up while nothing is happening; never cut off an active transfer or import.
  const idle = next === null || STALLED_STATES.has(next.state);
  if (idle && facts.now - job.startedAt > timeouts.overallMs)
    return { state: 'TIMEOUT', error: FAILURE_MESSAGES.timeout };
  return next;
}

const STALLED_STATES = new Set<DownloadJobState>(['WAITING_FOR_SOULARR', 'SEARCHING', 'QUEUED']);

/** Where the job is between "wanted in Lidarr" and "files in Lidarr". */
function pipelineState(facts: JobFacts): Assessment | null {
  const lidarr = facts.lidarr;
  const imports = facts.imports ?? [];
  if (imports.some((i) => i.running)) return { state: 'IMPORTING', progress: 1 };

  const queue = facts.queue ?? [];
  if (queue.some((q) => q.state === 'importPending' || q.state === 'importing'))
    return { state: 'IMPORTING', progress: 1 };
  if (queue.some((q) => q.state === 'importFailed' || q.state === 'importBlocked')) {
    return { state: 'FAILED', error: FAILURE_MESSAGES.importFailed };
  }

  const transfers = facts.transfers ?? [];
  const active = transfers.filter((t) => t.phase === 'active');
  const queued = transfers.filter((t) => t.phase === 'queued');
  const succeeded = transfers.filter((t) => t.phase === 'succeeded');
  if (active.length > 0 || (queued.length > 0 && succeeded.length > 0)) {
    return { state: 'DOWNLOADING', progress: byteProgress(transfers) };
  }
  const downloading = queue.find((q) => q.state === 'downloading');
  if (downloading) return { state: 'DOWNLOADING', progress: downloading.progress };
  if (queued.length > 0) return { state: 'QUEUED', progress: 0 };

  // Soularr gave up importing: it won't retry this album on its own.
  if (imports.length > 0 && imports.every((i) => i.failed))
    return { state: 'FAILED', error: FAILURE_MESSAGES.importFailed };

  // Everything arrived; Soularr moves the files and starts the import on its next step.
  if (succeeded.length > 0 && succeeded.length === transfers.length)
    return { state: 'IMPORTING', progress: 1 };

  if ((facts.searches ?? []).some((s) => s.active)) return { state: 'SEARCHING', progress: null };

  // Known to Lidarr and wanted, nothing happening: Soularr works through the wanted list in batches.
  if (lidarr) return { state: 'WAITING_FOR_SOULARR', progress: null };
  return null;
}

function byteProgress(
  transfers: { size: number; bytesTransferred: number; phase: TransferPhase }[],
): number | null {
  const counted = transfers.filter((t) => t.phase !== 'failed');
  const total = counted.reduce((sum, t) => sum + t.size, 0);
  if (total <= 0) return null;
  const done = counted.reduce(
    (sum, t) => sum + (t.phase === 'succeeded' ? t.size : t.bytesTransferred),
    0,
  );
  return Math.min(1, done / total);
}

// ---- Matching service activity to an album ---------------------------------------------------

/**
 * Whether a Soulseek search was for this album. Soularr searches by album title (optionally
 * prefixed with the artist), after stripping configured blacklist words.
 */
export function searchMatches(
  searchText: string,
  album: { title: string; artist: string },
): boolean {
  const text = basicNormalize(searchText);
  const title = titleKey(album.title);
  if (!title) return false;
  return (
    text === title ||
    text === `${artistKey(album.artist)} ${title}` ||
    text === `${basicNormalize(album.artist)} ${title}`
  );
}

/**
 * Whether a transfer's remote directory holds this album. Remote paths vary wildly
 * ("Music\\Tool\\2001 - Lateralus [FLAC]"), so the album title must appear as whole words in the
 * directory and either the artist appears in the path or the last folder is essentially the title.
 */
export function directoryMatches(
  directory: string,
  album: { title: string; artist: string },
): boolean {
  const segments = directory.split(/[\\/]+/).filter(Boolean);
  const path = ` ${basicNormalize(segments.join(' '))} `;
  const title = titleKey(album.title);
  if (!title || !path.includes(` ${title} `)) return false;
  const artist = artistKey(album.artist);
  if (artist && path.includes(` ${artist} `)) return true;
  const last = basicNormalize(segments.at(-1) ?? '')
    .replace(/\b(19|20)\d\d\b/g, '')
    .replace(/\b(flac|mp3|320|v0|web|cd|vinyl|24bit|16bit|24 \d+|16 44|lossless)\b/g, '')
    .trim()
    .replace(/\s+/g, ' ');
  return last === title;
}

/** Whether a Lidarr import folder is this album's. Soularr names it "Artist - Title (Year)". */
export function importPathMatches(path: string, album: { title: string; artist: string }): boolean {
  const folder =
    path
      .split(/[\\/]+/)
      .filter(Boolean)
      .at(-1) ?? '';
  const normalized = ` ${basicNormalize(folder)} `;
  return (
    normalized.includes(` ${titleKey(album.title)} `) &&
    normalized.includes(` ${artistKey(album.artist)} `)
  );
}
