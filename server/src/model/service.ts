import { and, asc, eq, gt, sql } from 'drizzle-orm';
import type { FeedbackBatchResult, FeedbackEvent, SkipModel } from '@rev/schemas';
import { SkipModel as SkipModelSchema } from '@rev/schemas';
import type { Db } from '../db/client.js';
import { feedback, plays, skipModels, users } from '../db/schema.js';
import { buildDataset, type DatasetRecord, listenKey } from '../stats/dataset.js';
import { Zone } from '../stats/zone.js';
import { type FeatureInput, features } from './features.js';
import { type Example, MIN_EXAMPLES, trainSkipModel } from './train.js';

/**
 * Feedback from the player and each user's personal skip model: trained from the listening log
 * and feedback, stored, and retrained when there's something new to learn from.
 */
export class ModelService {
  constructor(
    private readonly db: Db,
    private readonly now: () => number = Date.now,
    private readonly zone: Zone = new Zone(Intl.DateTimeFormat().resolvedOptions().timeZone),
  ) {}

  recordFeedback(userId: number, events: FeedbackEvent[]): FeedbackBatchResult {
    let recorded = 0;
    for (const e of events) {
      const inserted = this.db
        .insert(feedback)
        .values({
          userId,
          trackId: e.trackId,
          listenStartedAt: e.listenStartedAt,
          kind: e.kind,
          at: e.at,
          positionMs: e.positionMs ?? null,
          recordedAt: this.now(),
        })
        .onConflictDoNothing()
        .run();
      recorded += inserted.changes;
    }
    return { recorded, duplicates: events.length - recorded };
  }

  /** The user's listening log as training examples, oldest first. */
  examples(userId: number): Example[] {
    return this.dataset(userId).map(exampleFrom);
  }

  dataset(userId: number): DatasetRecord[] {
    const rows = this.db
      .select()
      .from(plays)
      .where(eq(plays.userId, userId))
      .orderBy(asc(plays.startedAt))
      .all()
      .map((r) => ({ ...r, genres: parseGenres(r.genres) }));
    const given = new Map(
      this.db
        .select({
          trackId: feedback.trackId,
          startedAt: feedback.listenStartedAt,
          kind: feedback.kind,
        })
        .from(feedback)
        .where(eq(feedback.userId, userId))
        .orderBy(asc(feedback.at))
        .all()
        .map((f) => [listenKey(f.trackId, f.startedAt), f.kind] as const),
    );
    return buildDataset(rows, this.zone, given);
  }

  /** Trains and stores a new model; null while there's too little to learn from. */
  train(userId: number): SkipModel | null {
    const model = trainSkipModel(this.examples(userId), { now: this.now() });
    if (!model) return null;
    const json = JSON.stringify(model);
    this.db
      .insert(skipModels)
      .values({ userId, model: json, trainedAt: model.trainedAt })
      .onConflictDoUpdate({
        target: skipModels.userId,
        set: { model: json, trainedAt: model.trainedAt },
      })
      .run();
    return model;
  }

  latest(userId: number): SkipModel | null {
    const row = this.db.select().from(skipModels).where(eq(skipModels.userId, userId)).get();
    if (!row) return null;
    const parsed = SkipModelSchema.safeParse(JSON.parse(row.model));
    return parsed.success ? parsed.data : null;
  }

  /** Retrains every user with listens or feedback newer than their model; returns how many. */
  trainWhereNeeded(): number {
    let trained = 0;
    for (const { id } of this.db.select({ id: users.id }).from(users).all()) {
      const since =
        this.db.select().from(skipModels).where(eq(skipModels.userId, id)).get()?.trainedAt ?? 0;
      const newer = (table: typeof plays | typeof feedback) =>
        this.db
          .select({ n: sql<number>`count(*)` })
          .from(table)
          .where(and(eq(table.userId, id), gt(table.recordedAt, since)))
          .get()?.n ?? 0;
      if (newer(plays) + newer(feedback) === 0) continue;
      if (this.train(id)) trained += 1;
    }
    return trained;
  }
}

/** A dataset row as the model sees it: context where the app recorded it, the log's own otherwise. */
export function featureInput(r: DatasetRecord): FeatureInput {
  const str = (v: unknown) => (typeof v === 'string' && v.length > 0 ? v : null);
  const num = (v: unknown) => (typeof v === 'number' ? v : null);
  const bool = (v: unknown) => (typeof v === 'boolean' ? v : null);
  return {
    trackId: String(r.track_id),
    artistId: str(r.artist_id),
    albumId: str(r.album_id),
    genres: str(r.genres)?.split('|') ?? [],
    year: num(r.year),
    localHour: num(r.local_hour) ?? 0,
    weekday: num(r.weekday) ?? 0,
    source: str(r.source),
    queueShape: str(r.queue_shape),
    shuffle: bool(r.shuffle),
    output: str(r.output),
    network: str(r.network),
    charging: bool(r.charging),
    favorite: bool(r.favorite),
    previousOutcome: str(r.previous_outcome) ?? str(r.previous_kind),
    sessionSkips: num(r.session_skips) ?? num(r.session_skips_before),
    sessionPosition: num(r.session_position),
    trackPlays: num(r.track_plays_before) ?? 0,
    trackSkips: num(r.track_skips_before) ?? 0,
    mode: str(r.mode),
  };
}

/** Feedback buttons override what happened and count double. */
export function exampleFrom(r: DatasetRecord): Example {
  const given = r.feedback;
  const label =
    given === 'not_now' ? 1 : given === 'more_like_this' ? 0 : r.skipped === true ? 1 : 0;
  return {
    features: features(featureInput(r)),
    label,
    weight: given === 'not_now' || given === 'more_like_this' ? 2 : 1,
    startedAt: Number(r.started_at_ms),
    predictedScore: typeof r.predicted_score === 'number' ? r.predicted_score : null,
    predictedSkip: typeof r.predicted_skip === 'number' ? r.predicted_skip : null,
  };
}

export { MIN_EXAMPLES };

function parseGenres(json: string): string[] {
  try {
    const value: unknown = JSON.parse(json);
    return Array.isArray(value) ? value.filter((g): g is string => typeof g === 'string') : [];
  } catch {
    return [];
  }
}
