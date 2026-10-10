import { eq } from 'drizzle-orm';
import type { LyricsResponse } from '@rev/schemas';
import type { NavidromeLibrary } from '../adapters/navidrome/library.js';
import { UpstreamError } from '../adapters/errors.js';
import type { Db } from '../db/client.js';
import { lyrics } from '../db/schema.js';
import type { LyricLine } from './lrc.js';
import type { FoundLyrics, LyricsProvider, TrackForLyrics } from './providers.js';

/** "No lyrics" is re-checked after this long; lyrics get added to LRCLIB over time. */
const NOT_FOUND_TTL_MS = 7 * 24 * 3_600_000;

type Row = typeof lyrics.$inferSelect;

/**
 * Lyrics resolution (§34): REV's cache, then each provider in order (Navidrome, then LRCLIB, where
 * synced lyrics beat plain). Results — including a definite "none" — are cached so repeated plays
 * and offline clients don't hit providers again. A provider failure is never cached as "none".
 */
export class LyricsService {
  private readonly inFlight = new Map<string, Promise<LyricsResponse>>();

  constructor(
    private readonly db: Db,
    private readonly library: NavidromeLibrary | null,
    private readonly providers: LyricsProvider[],
    private readonly onUpdated: (trackId: string) => void = () => {},
    private readonly now: () => number = Date.now,
  ) {}

  get(trackId: string): Promise<LyricsResponse> {
    const cached = this.cached(trackId);
    if (cached) return Promise.resolve(cached);
    let attempt = this.inFlight.get(trackId);
    if (!attempt) {
      attempt = this.resolve(trackId).finally(() => this.inFlight.delete(trackId));
      this.inFlight.set(trackId, attempt);
    }
    return attempt;
  }

  private cached(trackId: string): LyricsResponse | null {
    const row = this.db.select().from(lyrics).where(eq(lyrics.trackId, trackId)).get();
    if (!row) return null;
    if (row.status === 'not_found' && this.now() - row.fetchedAt > NOT_FOUND_TTL_MS) return null;
    return toResponse(row);
  }

  private async resolve(trackId: string): Promise<LyricsResponse> {
    if (!this.library)
      throw new UpstreamError('navidrome', 'unavailable', 'Navidrome not configured');
    const song = await this.library.track(trackId);
    const track: TrackForLyrics = {
      id: trackId,
      title: song.title,
      artist: song.artist,
      album: song.album || null,
      durationSec: song.durationMs > 0 ? song.durationMs / 1000 : null,
    };

    let failure: unknown = null;
    for (const provider of this.providers) {
      try {
        const found = await provider.find(track);
        if (found) return this.store(trackId, found);
      } catch (err) {
        failure = err; // try the next provider, but don't conclude "none"
      }
    }
    if (failure) throw failure;
    return this.store(trackId, null);
  }

  private store(trackId: string, found: FoundLyrics | null): LyricsResponse {
    const now = this.now();
    const status = !found
      ? 'not_found'
      : found.instrumental && !found.plain
        ? 'instrumental'
        : 'found';
    const row: Row = {
      trackId,
      status,
      source: found?.source ?? null,
      sourceId: found?.sourceId ?? null,
      plainLyrics: found?.plain ?? null,
      syncedLyrics: found?.synced ? JSON.stringify(found.synced) : null,
      isInstrumental: found?.instrumental ?? false,
      fetchedAt: now,
      updatedAt: now,
    };
    this.db
      .insert(lyrics)
      .values(row)
      .onConflictDoUpdate({ target: lyrics.trackId, set: row })
      .run();
    if (found) this.onUpdated(trackId);
    return toResponse(row);
  }
}

function toResponse(row: Row): LyricsResponse {
  return {
    trackId: row.trackId,
    status: row.status as LyricsResponse['status'],
    source: row.source as LyricsResponse['source'],
    synced: row.syncedLyrics ? (JSON.parse(row.syncedLyrics) as LyricLine[]) : null,
    plain: row.plainLyrics,
    fetchedAt: row.fetchedAt,
  };
}
