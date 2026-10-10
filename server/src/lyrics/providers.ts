import { z } from 'zod';
import type { NavidromeLibrary } from '../adapters/navidrome/library.js';
import { UpstreamError } from '../adapters/errors.js';
import { REV_VERSION } from '../version.js';
import { type LyricLine, parseLrc, plainFromLines } from './lrc.js';
import { type LyricsCandidate, scoreCandidate, type TrackSignature } from './match.js';

export interface TrackForLyrics extends TrackSignature {
  id: string;
}

export interface FoundLyrics {
  source: 'navidrome' | 'lrclib';
  sourceId: string | null;
  synced: LyricLine[] | null;
  plain: string | null;
  instrumental: boolean;
}

/** A lyrics source. Returns null for "has none"; throws UpstreamError when it couldn't be asked. */
export interface LyricsProvider {
  readonly name: 'navidrome' | 'lrclib';
  find(track: TrackForLyrics): Promise<FoundLyrics | null>;
}

export class NavidromeLyricsProvider implements LyricsProvider {
  readonly name = 'navidrome' as const;

  constructor(private readonly library: NavidromeLibrary) {}

  async find(track: TrackForLyrics): Promise<FoundLyrics | null> {
    let lyrics: Awaited<ReturnType<NavidromeLibrary['lyrics']>>;
    try {
      lyrics = await this.library.lyrics(track.id);
    } catch (err) {
      // A Navidrome without the songLyrics extension answers with an error: that means "none here",
      // not an outage. Only an unreachable Navidrome is a real failure.
      if (err instanceof UpstreamError && err.kind !== 'unavailable') return null;
      throw err;
    }
    if (!lyrics) return null;
    return {
      source: 'navidrome',
      sourceId: null,
      synced: lyrics.synced,
      plain: lyrics.plain,
      instrumental: false,
    };
  }
}

const LrclibRecord = z.object({
  id: z.number(),
  trackName: z.string(),
  artistName: z.string(),
  albumName: z.string().nullish(),
  duration: z.number().nullish(),
  instrumental: z.boolean().default(false),
  plainLyrics: z.string().nullish(),
  syncedLyrics: z.string().nullish(),
});
type LrclibRecord = z.infer<typeof LrclibRecord>;

/**
 * LRCLIB (https://lrclib.net), a free community lyrics database. Tries the exact-signature lookup
 * first, then a search whose candidates are scored; anything that isn't clearly the same song is
 * rejected. Requests are limited to a couple at a time out of courtesy.
 */
export class LrclibProvider implements LyricsProvider {
  readonly name = 'lrclib' as const;
  private readonly baseUrl: string;
  private active = 0;
  private readonly waiting: (() => void)[] = [];

  constructor(
    baseUrl: string,
    private readonly fetchImpl: typeof fetch = fetch,
    private readonly maxConcurrent = 2,
    private readonly timeoutMs = 10_000,
  ) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
  }

  async find(track: TrackForLyrics): Promise<FoundLyrics | null> {
    const exact = await this.getExact(track);
    if (exact && scoreCandidate(track, candidate(exact)) !== null) return toFound(exact);

    const results = await this.request(
      'search',
      { track_name: track.title, artist_name: track.artist },
      z.array(LrclibRecord),
    );
    const ranked = (results ?? [])
      .map((r) => ({ record: r, score: scoreCandidate(track, candidate(r)) }))
      .filter((r): r is { record: LrclibRecord; score: number } => r.score !== null)
      // Prefer synced lyrics among equally good matches.
      .sort(
        (a, b) =>
          b.score - a.score || Number(!!b.record.syncedLyrics) - Number(!!a.record.syncedLyrics),
      );
    const best = ranked[0]?.record;
    return best ? toFound(best) : null;
  }

  private getExact(track: TrackForLyrics): Promise<LrclibRecord | null> {
    if (track.durationSec === null || !track.album) return Promise.resolve(null);
    return this.request(
      'get',
      {
        track_name: track.title,
        artist_name: track.artist,
        album_name: track.album,
        duration: String(Math.round(track.durationSec)),
      },
      LrclibRecord,
    );
  }

  /** null on 404 (not found); throws when LRCLIB can't be reached. */
  private async request<T extends z.ZodType>(
    path: string,
    params: Record<string, string>,
    schema: T,
  ): Promise<z.infer<T> | null> {
    await this.acquire();
    try {
      const url = `${this.baseUrl}/api/${path}?${new URLSearchParams(params)}`;
      let response: Response;
      try {
        response = await this.fetchImpl(url, {
          headers: {
            'user-agent': `REV/${REV_VERSION} (self-hosted music app)`,
            accept: 'application/json',
          },
          signal: AbortSignal.timeout(this.timeoutMs),
        });
      } catch (cause) {
        throw new UpstreamError('lrclib', 'unavailable', `${path}: request failed`, { cause });
      }
      if (response.status === 404) return null;
      if (!response.ok) {
        throw new UpstreamError(
          'lrclib',
          response.status >= 500 || response.status === 429 ? 'unavailable' : 'protocol',
          `${path}: HTTP ${response.status}`,
        );
      }
      const parsed = schema.safeParse(await response.json().catch(() => null));
      if (!parsed.success)
        throw new UpstreamError('lrclib', 'protocol', `${path}: unexpected response`);
      return parsed.data;
    } finally {
      this.release();
    }
  }

  private async acquire(): Promise<void> {
    if (this.active < this.maxConcurrent) {
      this.active++;
      return;
    }
    await new Promise<void>((resolve) => this.waiting.push(resolve));
    this.active++;
  }

  private release(): void {
    this.active--;
    this.waiting.shift()?.();
  }
}

function candidate(r: LrclibRecord): LyricsCandidate {
  return {
    title: r.trackName,
    artist: r.artistName,
    album: r.albumName ?? null,
    durationSec: r.duration ?? null,
  };
}

function toFound(r: LrclibRecord): FoundLyrics | null {
  const synced = r.syncedLyrics ? parseLrc(r.syncedLyrics) : [];
  const plain = r.plainLyrics?.trim() || (synced.length > 0 ? plainFromLines(synced) : null);
  if (!r.instrumental && !plain && synced.length === 0) return null;
  return {
    source: 'lrclib',
    sourceId: String(r.id),
    synced: synced.length > 0 ? synced : null,
    plain,
    instrumental: r.instrumental,
  };
}
