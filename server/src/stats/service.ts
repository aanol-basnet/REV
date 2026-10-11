import { and, asc, eq, gte, lt, sql } from 'drizzle-orm';
import {
  type InteractionBatchResult,
  type InteractionEvent,
  type Listen,
  type ListenBatchResult,
  ListenContext,
  type ListeningReport,
  type ReportQuery,
  type TasteProfile,
  type TasteQuery,
  type Track,
} from '@rev/schemas';
import type { Db } from '../db/client.js';
import { interactions, plays } from '../db/schema.js';
import { UpstreamError } from '../adapters/errors.js';
import {
  artistKey,
  type Bounds,
  buildReport,
  type History,
  type PlayRow,
  periodBounds,
} from './report.js';
import { type ListenOutcome, outcomeOf } from './outcomes.js';
import { buildTaste, type InteractionRow } from './taste.js';
import { timesOfDay, type TimeTaste } from './time-of-day.js';
import { DAY_MS, Zone } from './zone.js';

/** The parts of the music server the listening log needs. */
export interface ListeningSource {
  trackWithGenres(id: string): Promise<{ track: Track; genres: string[] }>;
  scrobble(id: string, submission: boolean, playedAt?: number): Promise<void>;
}

/**
 * Keeps each user's dated listening log and builds reports from it. Plays are also passed on to
 * Navidrome, so its play counts and "recently played" stay right.
 */
export class ListeningService {
  constructor(
    private readonly db: Db,
    private readonly source: ListeningSource,
    private readonly now: () => number = Date.now,
  ) {}

  /**
   * Records listens; a listen already recorded (same track and start) is ignored, so the app can
   * safely resend after a lost response, except that a resend with an outcome (a play sent again
   * when it ended) updates its context and time heard. Fails, keeping what was recorded so far, when the music
   * server can't be reached: the app keeps the rest and tries again later.
   */
  async record(userId: number, listens: Listen[]): Promise<ListenBatchResult> {
    const result = { recorded: 0, duplicates: 0, unknown: 0 };
    const details = new Map<string, { track: Track; genres: string[] } | null>();
    for (const listen of [...listens].sort((a, b) => a.startedAt - b.startedAt)) {
      if (this.exists(userId, listen)) {
        // A play sent again when it ended: keep how it went (no second scrobble).
        if (listen.context?.outcome) this.updateEnding(userId, listen);
        result.duplicates += 1;
        continue;
      }
      let detail = details.get(listen.trackId);
      if (detail === undefined) {
        detail = await this.source.trackWithGenres(listen.trackId).catch((err: unknown) => {
          if (err instanceof UpstreamError && err.kind === 'not_found') return null;
          throw err;
        });
        details.set(listen.trackId, detail);
      }
      if (!detail) {
        result.unknown += 1;
        continue;
      }
      if (listen.kind === 'play') {
        await this.source.scrobble(listen.trackId, true, listen.startedAt);
      }
      const { track, genres } = detail;
      const cap = track.durationMs > 0 ? track.durationMs : Number.MAX_SAFE_INTEGER;
      const heard = listen.listenedMs ?? (listen.kind === 'play' ? track.durationMs : 0);
      const inserted = this.db
        .insert(plays)
        .values({
          userId,
          trackId: listen.trackId,
          kind: listen.kind,
          startedAt: listen.startedAt,
          listenedMs: Math.min(heard, cap),
          title: track.title,
          artist: track.artist,
          artistId: track.artistId,
          album: track.album,
          albumId: track.albumId,
          coverArtId: track.coverArtId,
          year: track.year,
          genres: JSON.stringify(genres),
          durationMs: track.durationMs,
          recordedAt: this.now(),
          context: listen.context ? JSON.stringify(listen.context) : null,
        })
        .onConflictDoNothing()
        .run();
      if (inserted.changes > 0) result.recorded += 1;
      else result.duplicates += 1;
    }
    return result;
  }

  report(userId: number, query: ReportQuery): ListeningReport {
    const zone = new Zone(query.tz);
    const now = this.now();
    const earliest = this.earliest(userId);
    const bounds = periodBounds(query.period, query.offset, now, zone, earliest);
    const previousRows =
      query.period === 'all'
        ? null
        : this.rows(userId, periodBounds(query.period, query.offset + 1, now, zone, earliest));
    return buildReport({
      kind: query.period,
      offset: query.offset,
      zone,
      now,
      bounds,
      rows: this.rows(userId, bounds),
      previousRows,
      history: this.history(userId, zone, earliest),
    });
  }

  /** The smart queue's taste profile, from the last two years of listens. */
  taste(userId: number, query: TasteQuery): TasteProfile {
    const now = this.now();
    const rows = this.rows(userId, { from: now - TASTE_DAYS * DAY_MS, to: now + DAY_MS });
    const zone = new Zone(query.tz);
    return {
      ...buildTaste(rows, zone, now, this.interactionRows(userId, now - TASTE_DAYS * DAY_MS)),
      timesOfDay: timesOfDay(rows, zone, now).map((t) => ({
        time: t.time,
        plays: t.plays,
        tracks: t.tracks.map(({ id, score }) => ({ id, score })),
        genres: t.genres,
        artists: t.artists,
      })),
    };
  }

  private interactionRows(userId: number, from: number): InteractionRow[] {
    return this.db
      .select()
      .from(interactions)
      .where(and(eq(interactions.userId, userId), gte(interactions.at, from)))
      .all()
      .map((r) => ({
        kind: r.kind,
        trackId: r.trackId,
        at: r.at,
        artistId: r.artistId,
        genres: parseGenres(r.genres),
      }));
  }

  /** What you listen to in each part of the day, from the last two years of listens. */
  timesOfDay(userId: number, tz: string): TimeTaste[] {
    const now = this.now();
    const rows = this.rows(userId, { from: now - TASTE_DAYS * DAY_MS, to: now + DAY_MS });
    return timesOfDay(rows, new Zone(tz), now);
  }

  /**
   * Records interactions with the song's artist and genres; resends are ignored. Like listens, it
   * fails when the music server can't be reached, and the app tries again later.
   */
  async recordInteractions(
    userId: number,
    events: InteractionEvent[],
  ): Promise<InteractionBatchResult> {
    const result = { recorded: 0, duplicates: 0, unknown: 0 };
    const details = new Map<string, { track: Track; genres: string[] } | null>();
    for (const e of events) {
      let detail = details.get(e.trackId);
      if (detail === undefined) {
        detail = await this.source.trackWithGenres(e.trackId).catch((err: unknown) => {
          if (err instanceof UpstreamError && err.kind === 'not_found') return null;
          throw err;
        });
        details.set(e.trackId, detail);
      }
      if (!detail) {
        result.unknown += 1;
        continue;
      }
      const inserted = this.db
        .insert(interactions)
        .values({
          userId,
          kind: e.kind,
          trackId: e.trackId,
          at: e.at,
          artistId: detail.track.artistId,
          artist: detail.track.artist,
          genres: JSON.stringify(detail.genres),
          recordedAt: this.now(),
        })
        .onConflictDoNothing()
        .run();
      if (inserted.changes > 0) result.recorded += 1;
      else result.duplicates += 1;
    }
    return result;
  }

  private updateEnding(userId: number, listen: Listen): void {
    const where = and(
      eq(plays.userId, userId),
      eq(plays.trackId, listen.trackId),
      eq(plays.startedAt, listen.startedAt),
    );
    const row = this.db
      .select({ durationMs: plays.durationMs, listenedMs: plays.listenedMs })
      .from(plays)
      .where(where)
      .get();
    if (!row) return;
    const cap = row.durationMs > 0 ? row.durationMs : Number.MAX_SAFE_INTEGER;
    this.db
      .update(plays)
      .set({
        context: JSON.stringify(listen.context),
        listenedMs: Math.min(listen.listenedMs ?? row.listenedMs, cap),
      })
      .where(where)
      .run();
  }

  private exists(userId: number, listen: Listen): boolean {
    const row = this.db
      .select({ id: plays.id })
      .from(plays)
      .where(
        and(
          eq(plays.userId, userId),
          eq(plays.trackId, listen.trackId),
          eq(plays.startedAt, listen.startedAt),
        ),
      )
      .get();
    return row !== undefined;
  }

  private earliest(userId: number): number | null {
    const row = this.db
      .select({ first: sql<number | null>`min(${plays.startedAt})` })
      .from(plays)
      .where(eq(plays.userId, userId))
      .get();
    return row?.first ?? null;
  }

  private rows(userId: number, { from, to }: Bounds): PlayRow[] {
    return this.db
      .select()
      .from(plays)
      .where(and(eq(plays.userId, userId), gte(plays.startedAt, from), lt(plays.startedAt, to)))
      .orderBy(asc(plays.startedAt))
      .all()
      .map((r) => ({
        trackId: r.trackId,
        kind: r.kind === 'skip' ? 'skip' : 'play',
        startedAt: r.startedAt,
        listenedMs: r.listenedMs,
        title: r.title,
        artist: r.artist,
        artistId: r.artistId,
        album: r.album,
        albumId: r.albumId,
        coverArtId: r.coverArtId,
        year: r.year,
        genres: parseGenres(r.genres),
        durationMs: r.durationMs,
        ...fromContext(r),
      }));
  }

  private history(userId: number, zone: Zone, earliest: number | null): History {
    const played = and(eq(plays.userId, userId), eq(plays.kind, 'play'));
    const firstArtistPlay = new Map<string, number>();
    const artists = this.db
      .select({
        artistId: plays.artistId,
        artist: plays.artist,
        first: sql<number>`min(${plays.startedAt})`,
      })
      .from(plays)
      .where(played)
      .groupBy(plays.artistId, plays.artist)
      .all();
    for (const r of artists) {
      const key = artistKey(r);
      firstArtistPlay.set(key, Math.min(r.first, firstArtistPlay.get(key) ?? r.first));
    }
    const firstTrackPlay = new Map(
      this.db
        .select({ key: plays.trackId, first: sql<number>`min(${plays.startedAt})` })
        .from(plays)
        .where(played)
        .groupBy(plays.trackId)
        .all()
        .map((r) => [r.key, r.first] as const),
    );
    const playDays = new Set(
      this.db
        .select({ at: plays.startedAt })
        .from(plays)
        .where(played)
        .all()
        .map((r) => zone.dayNumber(r.at)),
    );
    return { firstArtistPlay, firstTrackPlay, playDays, earliest };
  }
}

const TASTE_DAYS = 730;

/** What the taste profile reads from a listen's context: its listening mode and outcome. */
function fromContext(r: {
  kind: string;
  listenedMs: number;
  durationMs: number;
  context: string | null;
}): { mode: string | null; outcome: ListenOutcome } {
  const parsed = r.context ? ListenContext.safeParse(safeJson(r.context)) : null;
  const context = parsed?.success ? parsed.data : null;
  const mode = context?.mode;
  return {
    mode: typeof mode === 'string' && mode.length > 0 ? mode : null,
    outcome: outcomeOf({ ...r, context }),
  };
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function parseGenres(json: string): string[] {
  try {
    const value: unknown = JSON.parse(json);
    return Array.isArray(value) ? value.filter((g): g is string => typeof g === 'string') : [];
  } catch {
    return [];
  }
}
