import { describe, expect, it } from 'vitest';
import {
  assess,
  directoryMatches,
  FAILURE_MESSAGES,
  importPathMatches,
  type JobFacts,
  searchMatches,
} from '../src/downloads/progress.js';
import { transferPhase } from '../src/adapters/slskd/client.js';

const HOUR = 3_600_000;
const timeouts = { overallMs: 72 * HOUR, indexingMs: 6 * HOUR };
const job = (
  state: Parameters<typeof assess>[0]['state'] = 'WAITING_FOR_SOULARR',
  startedAt = 0,
  updatedAt = 0,
) => ({
  state,
  startedAt,
  updatedAt,
});
const wanted = { monitored: true, trackCount: 10, trackFileCount: 0 };
const facts = (f: Partial<JobFacts>): JobFacts => ({ now: HOUR, lidarr: wanted, ...f });

describe('job assessment', () => {
  it('walks the happy path', () => {
    expect(assess(job('ADDED_TO_LIDARR'), facts({}), timeouts)?.state).toBe('WAITING_FOR_SOULARR');
    expect(assess(job(), facts({ searches: [{ active: true }] }), timeouts)?.state).toBe(
      'SEARCHING',
    );
    expect(
      assess(
        job(),
        facts({ transfers: [{ phase: 'queued', size: 100, bytesTransferred: 0 }] }),
        timeouts,
      )?.state,
    ).toBe('QUEUED');

    const downloading = assess(
      job(),
      facts({
        transfers: [
          { phase: 'succeeded', size: 100, bytesTransferred: 100 },
          { phase: 'active', size: 100, bytesTransferred: 50 },
          { phase: 'queued', size: 200, bytesTransferred: 0 },
        ],
      }),
      timeouts,
    );
    expect(downloading).toEqual({ state: 'DOWNLOADING', progress: 150 / 400 });

    expect(
      assess(
        job(),
        facts({ transfers: [{ phase: 'succeeded', size: 100, bytesTransferred: 100 }] }),
        timeouts,
      )?.state,
    ).toBe('IMPORTING');
    expect(
      assess(job(), facts({ imports: [{ running: true, failed: false }] }), timeouts)?.state,
    ).toBe('IMPORTING');
    expect(assess(job(), facts({ lidarr: { ...wanted, trackFileCount: 10 } }), timeouts)).toEqual({
      state: 'INDEXING',
      progress: 1,
    });
    expect(assess(job('INDEXING'), facts({ libraryAlbumId: 'al-1' }), timeouts)).toEqual({
      state: 'AVAILABLE',
      libraryAlbumId: 'al-1',
    });
  });

  it("follows indexer downloads through Lidarr's own queue", () => {
    expect(
      assess(job(), facts({ queue: [{ state: 'downloading', progress: 0.4 }] }), timeouts),
    ).toEqual({
      state: 'DOWNLOADING',
      progress: 0.4,
    });
    expect(
      assess(job(), facts({ queue: [{ state: 'importPending', progress: 1 }] }), timeouts)?.state,
    ).toBe('IMPORTING');
    expect(
      assess(job(), facts({ queue: [{ state: 'importBlocked', progress: 1 }] }), timeouts)?.error,
    ).toBe(FAILURE_MESSAGES.importFailed);
  });

  it("fails when Soularr's import failed and nothing else is happening", () => {
    const result = assess(
      job('IMPORTING'),
      facts({
        imports: [{ running: false, failed: true }],
        transfers: [{ phase: 'succeeded', size: 1, bytesTransferred: 1 }],
      }),
      timeouts,
    );
    expect(result).toEqual({ state: 'FAILED', error: FAILURE_MESSAGES.importFailed });
  });

  it('notices changes made outside REV', () => {
    expect(assess(job(), facts({ lidarr: null }), timeouts)?.state).toBe('FAILED');
    expect(assess(job(), facts({ lidarr: { ...wanted, monitored: false } }), timeouts)?.state).toBe(
      'CANCELLED',
    );
  });

  it('times out only while stalled, never during an active transfer', () => {
    const late = { now: 73 * HOUR };
    expect(assess(job(), facts({ ...late }), timeouts)?.state).toBe('TIMEOUT');
    expect(assess(job(), facts({ ...late, searches: [{ active: true }] }), timeouts)?.state).toBe(
      'TIMEOUT',
    );
    expect(
      assess(
        job(),
        facts({ ...late, transfers: [{ phase: 'active', size: 10, bytesTransferred: 1 }] }),
        timeouts,
      )?.state,
    ).toBe('DOWNLOADING');
  });

  it('times out an import Navidrome never picks up', () => {
    const complete = { ...wanted, trackFileCount: 10 };
    expect(
      assess(job('INDEXING', 0, 0), facts({ now: 2 * HOUR, lidarr: complete }), timeouts)?.state,
    ).toBe('INDEXING');
    expect(
      assess(job('INDEXING', 0, 0), facts({ now: 7 * HOUR, lidarr: complete }), timeouts)?.error,
    ).toBe(FAILURE_MESSAGES.indexingTimeout);
  });

  it('holds still when nothing could be observed', () => {
    expect(assess(job('SEARCHING'), { now: HOUR }, timeouts)).toBeNull();
  });
});

describe('matching service activity to an album', () => {
  const lateralus = { title: 'Lateralus', artist: 'Tool' };

  it('recognises Soularr searches', () => {
    expect(searchMatches('Lateralus', lateralus)).toBe(true);
    expect(searchMatches('Tool Lateralus', lateralus)).toBe(true);
    expect(searchMatches('Lateralus Live', lateralus)).toBe(false);
    expect(searchMatches('Ænima', { title: 'Ænima', artist: 'Tool' })).toBe(true);
  });

  it('recognises remote album folders', () => {
    expect(directoryMatches('@@abcde\\Music\\Tool\\2001 - Lateralus [FLAC]', lateralus)).toBe(true);
    expect(directoryMatches('@@abcde\\Shared\\Lateralus (2001) FLAC', lateralus)).toBe(true);
    expect(directoryMatches('@@abcde\\Music\\Various\\Best of Lateralus Covers', lateralus)).toBe(
      false,
    );
    expect(directoryMatches('@@abcde\\Music\\Tool\\Undertow', lateralus)).toBe(false);
  });

  it('recognises Soularr import folders', () => {
    expect(importPathMatches('/srv/music/downloads/Tool - Lateralus (2001)', lateralus)).toBe(true);
    expect(importPathMatches('/srv/music/downloads/Tool - Undertow (1993)', lateralus)).toBe(false);
  });

  it('reads slskd transfer states', () => {
    expect(transferPhase('Queued, Remotely')).toBe('queued');
    expect(transferPhase('Requested')).toBe('queued');
    expect(transferPhase('Initializing')).toBe('active');
    expect(transferPhase('InProgress')).toBe('active');
    expect(transferPhase('Completed, Succeeded')).toBe('succeeded');
    expect(transferPhase('Completed, Errored')).toBe('failed');
    expect(transferPhase('Completed, Cancelled')).toBe('failed');
  });
});
