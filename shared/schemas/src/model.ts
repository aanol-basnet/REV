import { z } from 'zod';
import { LibraryId } from './library.js';

// The personal skip model: trained on the server from one user's listening log, used and further
// trained on the phone. See docs/training-data.md.

/** Feedback from the player: not_now (skip it, not this) or more_like_this. Open-ended. */
export const FeedbackEvent = z.object({
  trackId: LibraryId,
  /** The listen it's about (its startedAt), so it joins the listening log. */
  listenStartedAt: z.number().int().positive(),
  kind: z.string().min(1).max(40),
  /** When the button was pressed, epoch ms. */
  at: z.number().int().positive(),
  /** Where in the song. */
  positionMs: z.number().int().min(0).max(86_400_000).optional(),
});
export type FeedbackEvent = z.infer<typeof FeedbackEvent>;

export const FeedbackBatch = z.object({ events: z.array(FeedbackEvent).min(1).max(500) });
export type FeedbackBatch = z.infer<typeof FeedbackBatch>;

export const FeedbackBatchResult = z.object({
  recorded: z.number().int().nonnegative(),
  duplicates: z.number().int().nonnegative(),
});
export type FeedbackBatchResult = z.infer<typeof FeedbackBatchResult>;

/**
 * Something you did with a song besides listening: playlist_add, play_next, queue_add,
 * queue_remove (taken out of the queue before it played), favorite, unfavorite, repeat_one or
 * lyrics. Open-ended, so an older server stores kinds a newer app sends.
 */
export const InteractionEvent = z.object({
  kind: z.string().min(1).max(40),
  trackId: LibraryId,
  /** When, epoch ms. With the kind and song, what makes it unique (resends are ignored). */
  at: z.number().int().positive(),
});
export type InteractionEvent = z.infer<typeof InteractionEvent>;

export const InteractionBatch = z.object({ events: z.array(InteractionEvent).min(1).max(500) });
export type InteractionBatch = z.infer<typeof InteractionBatch>;

export const InteractionBatchResult = z.object({
  recorded: z.number().int().nonnegative(),
  duplicates: z.number().int().nonnegative(),
  /** Dropped because the song is no longer in the library. */
  unknown: z.number().int().nonnegative(),
});
export type InteractionBatchResult = z.infer<typeof InteractionBatchResult>;

/** How the model did on the most recent listens, which it wasn't trained on. */
export const ModelMetrics = z.object({
  /** Listens it was tested on. */
  holdout: z.number().int().nonnegative(),
  /** Lower is better. */
  logLoss: z.number(),
  /** Always guessing the usual skip rate; the model should beat this. */
  baselineLogLoss: z.number(),
  /** How well it ranks skips above listens: 0.5 is chance, 1 perfect. */
  auc: z.number().nullable(),
  /** The same for the smart queue's hand-tuned score, where it was recorded. */
  smartQueueAuc: z.number().nullable(),
  /** The same for the phone's own model at the time, where it was recorded. */
  phoneModelAuc: z.number().nullable().default(null),
});
export type ModelMetrics = z.infer<typeof ModelMetrics>;

/** How a model was trained; picked by trying several on recent listens and keeping the best. */
export const TrainingSettings = z.object({
  learningRate: z.number().positive(),
  l2: z.number().nonnegative(),
  epochs: z.number().int().positive(),
});
export type TrainingSettings = z.infer<typeof TrainingSettings>;

/** One training run, kept so you can see whether the model is getting better. */
export const TrainingRun = z.object({
  trainedAt: z.number().int(),
  examples: z.number().int().nonnegative(),
  logLoss: z.number().nullable(),
  baselineLogLoss: z.number().nullable(),
  auc: z.number().nullable(),
});
export type TrainingRun = z.infer<typeof TrainingRun>;

/**
 * Logistic regression over named features (docs/training-data.md): the chance of skipping a song
 * is sigmoid(bias + the weights of its features).
 */
export const SkipModel = z.object({
  version: z.literal(1),
  trainedAt: z.number().int(),
  /** The newest listen it learned from; the phone replays its own newer listens on top. */
  dataUntil: z.number().int(),
  /** Listens it learned from. */
  examples: z.number().int().nonnegative(),
  bias: z.number(),
  weights: z.record(z.string(), z.number()),
  metrics: ModelMetrics.nullable(),
  settings: TrainingSettings.nullable().default(null),
  /** Earlier training runs and this one, oldest first (up to 50). */
  history: z.array(TrainingRun).default([]),
});
export type SkipModel = z.infer<typeof SkipModel>;

export const SkipModelResponse = z.object({ model: SkipModel.nullable() });
export type SkipModelResponse = z.infer<typeof SkipModelResponse>;
