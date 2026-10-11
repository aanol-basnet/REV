import { and, desc, eq, gte, lt, sql } from 'drizzle-orm';
import type {
  SaveSmartPlaylistResult,
  SmartPlaylist,
  SmartPlaylistDetail,
  SmartPlaylistKind,
  Track,
} from '@rev/schemas';
import type { Db } from '../db/client.js';
import { plays, smartPlaylistSaves } from '../db/schema.js';
import { UpstreamError } from '../adapters/errors.js';
import { HttpError } from '../api/errors.js';
import type { PlaylistService } from '../library/playlists.js';
import { DAY_MS, Zone } from './zone.js';
import { resolveTracks } from '../library/resolve.js';

/** The parts of the music server smart playlists need. */
export interface SmartPlaylistLibrary {
  track(id: string): Promise<Track>;
}

const ON_REPEAT_DAYS = 30;
const ON_REPEAT_SIZE = 25;
/** "Forgotten": played at least this often, but not in [FORGOTTEN_DAYS]. */
const FORGOTTEN_MIN_PLAYS = 3;
const FORGOTTEN_DAYS = 90;
const FORGOTTEN_SIZE = 50;
const YEAR_SIZE = 50;
/** Fewer songs than this isn't much of a playlist. */
const MIN_TRACKS = 5;

interface Candidate {
  trackId: string;
  coverArtId: string | null;
}

/**
 * Playlists made from the listening log ("On repeat", "Forgotten favorites", "Top songs of <year>").
 * They change as you listen; saving one writes it to Navidrome as an ordinary playlist, and saving
 * again replaces that playlist's songs (or makes a new one if it was deleted there).
 */
export class SmartPlaylistService {
  constructor(
    private readonly db: Db,
    private readonly library: SmartPlaylistLibrary,
    private readonly playlists: PlaylistService,
    private readonly now: () => number = Date.now,
  ) {}

  list(userId: number, tz: string): SmartPlaylist[] {
    const zone = new Zone(tz);
    const ids = ['on-repeat', 'forgotten', ...this.years(userId, zone).map((y) => `year-${y}`)];
    return ids
      .map((id) => this.summary(userId, id, this.candidates(userId, id, zone)))
      .filter((p) => p.trackCount >= MIN_TRACKS);
  }

  async detail(userId: number, id: string, tz: string): Promise<SmartPlaylistDetail> {
    const candidates = this.candidates(userId, id, new Zone(tz));
    const tracks = await resolveTracks(
      candidates.map((c) => c.trackId),
      (id) => this.library.track(id),
    );
    return { ...this.summary(userId, id, candidates), trackCount: tracks.length, tracks };
  }

  async save(userId: number, id: string, tz: string): Promise<SaveSmartPlaylistResult> {
    const { tracks } = await this.detail(userId, id, tz);
    if (tracks.length === 0)
      throw new HttpError(404, 'NOT_FOUND', 'This playlist has no songs yet');
    const trackIds = tracks.map((t) => t.id);
    const saved = this.savedPlaylistId(userId, id);
    if (saved) {
      const updated = await this.playlists.setTracks(saved, trackIds).then(
        () => true,
        (err: unknown) => {
          // Deleted in Navidrome, or no longer REV's to change: make a new one.
          if (err instanceof UpstreamError && err.kind === 'not_found') return false;
          if (err instanceof HttpError && err.statusCode === 403) return false;
          throw err;
        },
      );
      if (updated) {
        this.remember(userId, id, saved);
        return { playlistId: saved, created: false };
      }
    }
    const created = await this.playlists.create({
      name: playlistName(id),
      comment: 'Made by REV from your listening. Saving it again in REV updates it.',
      trackIds,
    });
    this.remember(userId, id, created.id);
    return { playlistId: created.id, created: true };
  }

  private summary(userId: number, id: string, candidates: Candidate[]): SmartPlaylist {
    const covers = [...new Set(candidates.flatMap((c) => (c.coverArtId ? [c.coverArtId] : [])))];
    const year = id.startsWith('year-') ? Number(id.slice('year-'.length)) : null;
    return {
      id,
      kind: kindOf(id),
      year,
      trackCount: candidates.length,
      coverArtIds: covers.slice(0, 4),
      savedPlaylistId: this.savedPlaylistId(userId, id),
    };
  }

  /** Track ids in playlist order, most played first. */
  private candidates(userId: number, id: string, zone: Zone): Candidate[] {
    const now = this.now();
    const count = sql<number>`count(*)`;
    const last = sql<number>`max(${plays.startedAt})`;
    const mine = and(eq(plays.userId, userId), eq(plays.kind, 'play'));
    const select = {
      trackId: plays.trackId,
      coverArtId: sql<string | null>`max(${plays.coverArtId})`,
    };
    switch (kindOf(id)) {
      case 'on-repeat':
        return this.db
          .select(select)
          .from(plays)
          .where(and(mine, gte(plays.startedAt, now - ON_REPEAT_DAYS * DAY_MS)))
          .groupBy(plays.trackId)
          .having(sql`${count} >= 2`)
          .orderBy(desc(count), desc(last))
          .limit(ON_REPEAT_SIZE)
          .all();
      case 'forgotten':
        return this.db
          .select(select)
          .from(plays)
          .where(mine)
          .groupBy(plays.trackId)
          .having(
            and(
              sql`${count} >= ${FORGOTTEN_MIN_PLAYS}`,
              sql`${last} < ${now - FORGOTTEN_DAYS * DAY_MS}`,
            ),
          )
          .orderBy(desc(count), desc(last))
          .limit(FORGOTTEN_SIZE)
          .all();
      case 'year': {
        const year = Number(id.slice('year-'.length));
        return this.db
          .select(select)
          .from(plays)
          .where(
            and(
              mine,
              gte(plays.startedAt, zone.startOf(year, 1, 1)),
              lt(plays.startedAt, zone.startOf(year + 1, 1, 1)),
            ),
          )
          .groupBy(plays.trackId)
          .orderBy(desc(count), desc(sql`sum(${plays.listenedMs})`), desc(last))
          .limit(YEAR_SIZE)
          .all();
      }
    }
  }

  /** Years with plays, newest first. */
  private years(userId: number, zone: Zone): number[] {
    const row = this.db
      .select({
        first: sql<number | null>`min(${plays.startedAt})`,
        last: sql<number | null>`max(${plays.startedAt})`,
      })
      .from(plays)
      .where(and(eq(plays.userId, userId), eq(plays.kind, 'play')))
      .get();
    if (row?.first == null || row.last == null) return [];
    const years: number[] = [];
    for (let y = zone.date(row.last).year; y >= zone.date(row.first).year; y--) years.push(y);
    return years;
  }

  private savedPlaylistId(userId: number, smartId: string): string | null {
    const row = this.db
      .select({ playlistId: smartPlaylistSaves.playlistId })
      .from(smartPlaylistSaves)
      .where(and(eq(smartPlaylistSaves.userId, userId), eq(smartPlaylistSaves.smartId, smartId)))
      .get();
    return row?.playlistId ?? null;
  }

  private remember(userId: number, smartId: string, playlistId: string): void {
    this.db
      .insert(smartPlaylistSaves)
      .values({ userId, smartId, playlistId, savedAt: this.now() })
      .onConflictDoUpdate({
        target: [smartPlaylistSaves.userId, smartPlaylistSaves.smartId],
        set: { playlistId, savedAt: this.now() },
      })
      .run();
  }
}

function kindOf(id: string): SmartPlaylistKind {
  if (id === 'on-repeat' || id === 'forgotten') return id;
  return 'year';
}

/** The name it gets in Navidrome, where the app's translations don't reach. */
function playlistName(id: string): string {
  switch (kindOf(id)) {
    case 'on-repeat':
      return 'On Repeat';
    case 'forgotten':
      return 'Forgotten Favorites';
    case 'year':
      return `Top Songs of ${id.slice('year-'.length)}`;
  }
}
