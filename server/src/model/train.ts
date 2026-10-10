import type { ModelMetrics, SkipModel } from '@rev/schemas';

/** One listen to learn from. */
export interface Example {
  features: string[];
  /** 1: skipped (or "not now"); 0: listened (or "more like this"). */
  label: 0 | 1;
  /** Feedback buttons count double. */
  weight: number;
  startedAt: number;
  /** The smart queue's score when it played, to compare against. */
  predictedScore: number | null;
  /** The phone's model's chance of a skip when it played. */
  predictedSkip?: number | null;
}

export interface TrainOptions {
  epochs?: number;
  learningRate?: number;
  l2?: number;
  /** Share of the newest listens held back to test on. */
  holdout?: number;
  random?: () => number;
  now?: number;
}

/** Fewer listens than this say too little to train on. */
export const MIN_EXAMPLES = 50;
const MIN_HOLDOUT = 20;
const MAX_FEATURES = 40_000;
const MIN_WEIGHT = 0.005;

interface Fit {
  bias: number;
  weights: Map<string, number>;
}

/**
 * Trains the skip model: logistic regression with AdaGrad and a little L2. First on the older
 * listens, tested on the newest ones (the way it'll be used: predicting what comes next), then on
 * everything for the model that's served.
 */
export function trainSkipModel(examples: Example[], options: TrainOptions = {}): SkipModel | null {
  if (examples.length < MIN_EXAMPLES) return null;
  const sorted = [...examples].sort((a, b) => a.startedAt - b.startedAt);
  const holdoutSize = Math.floor(sorted.length * (options.holdout ?? 0.2));
  const train = sorted.slice(0, sorted.length - holdoutSize);
  const test = sorted.slice(sorted.length - holdoutSize);

  const metrics = test.length >= MIN_HOLDOUT ? evaluate(fit(train, options), train, test) : null;
  const final = fit(sorted, options);
  const kept = [...final.weights]
    .filter(([, w]) => Math.abs(w) >= MIN_WEIGHT)
    .sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]))
    .slice(0, MAX_FEATURES);
  return {
    version: 1,
    trainedAt: options.now ?? Date.now(),
    dataUntil: sorted[sorted.length - 1]?.startedAt ?? 0,
    examples: sorted.length,
    bias: round(final.bias),
    weights: Object.fromEntries(kept.map(([f, w]) => [f, round(w)])),
    metrics,
  };
}

export function predict(
  model: { bias: number; weights: Record<string, number> | Map<string, number> },
  features: string[],
): number {
  const get = (f: string) =>
    (model.weights instanceof Map ? model.weights.get(f) : model.weights[f]) ?? 0;
  return sigmoid(model.bias + features.reduce((sum, f) => sum + get(f), 0));
}

function fit(examples: Example[], options: TrainOptions): Fit {
  const lr = options.learningRate ?? 0.15;
  const l2 = options.l2 ?? 1e-5;
  const random = options.random ?? Math.random;
  const weights = new Map<string, number>();
  const squares = new Map<string, number>();
  let bias = 0;
  let biasSquares = 0;
  const order = examples.map((_, i) => i);
  for (let epoch = 0; epoch < (options.epochs ?? 6); epoch++) {
    shuffleInPlace(order, random);
    for (const i of order) {
      const ex = examples[i] as Example;
      const p = sigmoid(bias + ex.features.reduce((s, f) => s + (weights.get(f) ?? 0), 0));
      const g = (p - ex.label) * ex.weight;
      biasSquares += g * g;
      bias -= (lr * g) / Math.sqrt(biasSquares + 1e-8);
      for (const f of ex.features) {
        const w = weights.get(f) ?? 0;
        const grad = g + l2 * w;
        const sq = (squares.get(f) ?? 0) + grad * grad;
        squares.set(f, sq);
        weights.set(f, w - (lr * grad) / Math.sqrt(sq + 1e-8));
      }
    }
  }
  return { bias, weights };
}

function evaluate(model: Fit, train: Example[], test: Example[]): ModelMetrics {
  const rate = clamp(
    train.reduce((s, e) => s + e.label * e.weight, 0) /
      Math.max(
        1e-9,
        train.reduce((s, e) => s + e.weight, 0),
      ),
  );
  const predictions = test.map((e) => predict(model, e.features));
  const scored = test.filter((e) => e.predictedScore !== null);
  const phone = test.filter((e) => e.predictedSkip !== null && e.predictedSkip !== undefined);
  return {
    holdout: test.length,
    logLoss: round(logLoss(test, predictions)),
    baselineLogLoss: round(
      logLoss(
        test,
        test.map(() => rate),
      ),
    ),
    auc: roundOrNull(
      auc(
        test.map((e) => e.label),
        predictions,
      ),
    ),
    // A high score means "fits": lower score, more likely skipped.
    smartQueueAuc: roundOrNull(
      auc(
        scored.map((e) => e.label),
        scored.map((e) => -(e.predictedScore as number)),
      ),
    ),
    phoneModelAuc: roundOrNull(
      auc(
        phone.map((e) => e.label),
        phone.map((e) => e.predictedSkip as number),
      ),
    ),
  };
}

function logLoss(examples: Example[], predictions: number[]): number {
  let total = 0;
  let weight = 0;
  examples.forEach((e, i) => {
    const p = clamp(predictions[i] as number);
    total += -e.weight * (e.label * Math.log(p) + (1 - e.label) * Math.log(1 - p));
    weight += e.weight;
  });
  return weight > 0 ? total / weight : 0;
}

/** Area under the ROC curve: the chance a random skip scores above a random listen. */
export function auc(labels: number[], scores: number[]): number | null {
  const positives = labels.filter((l) => l === 1).length;
  const negatives = labels.length - positives;
  if (positives === 0 || negatives === 0) return null;
  const ranked = scores.map((s, i) => ({ s, l: labels[i] })).sort((a, b) => a.s - b.s);
  let rankSum = 0;
  let i = 0;
  while (i < ranked.length) {
    let j = i;
    while (j + 1 < ranked.length && ranked[j + 1]?.s === ranked[i]?.s) j++;
    const rank = (i + j) / 2 + 1;
    for (let k = i; k <= j; k++) if (ranked[k]?.l === 1) rankSum += rank;
    i = j + 1;
  }
  return (rankSum - (positives * (positives + 1)) / 2) / (positives * negatives);
}

function sigmoid(x: number): number {
  return 1 / (1 + Math.exp(-Math.max(-30, Math.min(30, x))));
}

function clamp(p: number): number {
  return Math.min(1 - 1e-6, Math.max(1e-6, p));
}

function shuffleInPlace(items: number[], random: () => number): void {
  for (let i = items.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [items[i], items[j]] = [items[j] as number, items[i] as number];
  }
}

function round(n: number): number {
  return Math.round(n * 10_000) / 10_000;
}

function roundOrNull(n: number | null): number | null {
  return n === null ? null : round(n);
}
