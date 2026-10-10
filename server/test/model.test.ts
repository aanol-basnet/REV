import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it } from 'vitest';
import { FeedbackBatchResult, SkipModelResponse } from '@rev/schemas';
import { type FeatureInput, features } from '../src/model/features.js';
import { auc, type Example, predict, trainSkipModel } from '../src/model/train.js';
import { seeded } from '../src/home/service.js';
import { createTestApp, signedInHeaders, type TestContext } from './helpers.js';
import { fakeNavidrome, NAVIDROME, subsonicOk } from './fake-navidrome.js';

const fixtures = JSON.parse(
  readFileSync(new URL('../../shared/model/feature-fixtures.json', import.meta.url), 'utf8'),
) as { cases: { input: FeatureInput; features: string[] }[] };

describe('skip model features', () => {
  it('match the shared fixtures the app is checked against too', () => {
    for (const c of fixtures.cases) expect(features(c.input)).toEqual(c.features);
  });
});

describe('skip model training', () => {
  /** Tool is skipped in the car and kept on headphones; Adele is always kept. */
  function examples(n: number): Example[] {
    const random = seeded(3);
    return Array.from({ length: n }, (_, i) => {
      const tool = random() < 0.5;
      const car = random() < 0.5;
      const input: FeatureInput = {
        trackId: `t${i % 40}`,
        artistId: tool ? 'ar-tool' : 'ar-adele',
        albumId: null,
        genres: [tool ? 'Metal' : 'Pop'],
        year: 2000,
        localHour: 12,
        weekday: 1,
        source: null,
        queueShape: null,
        shuffle: null,
        output: car ? 'car' : 'headphones',
        network: null,
        charging: null,
        favorite: null,
        previousOutcome: null,
        sessionSkips: null,
        sessionPosition: null,
        trackPlays: 0,
        trackSkips: 0,
        mode: null,
      };
      const skipped = tool && car ? random() < 0.9 : random() < 0.1;
      return {
        features: features(input),
        label: skipped ? 1 : 0,
        weight: 1,
        startedAt: i,
        predictedScore: 0,
      };
    });
  }

  it('learns what you skip in which situation, and says how well it does on new listens', () => {
    const model = trainSkipModel(examples(600), { random: seeded(1), now: 5 });
    if (!model) throw new Error('expected a model');
    const at = (artist: string, output: string) =>
      predict(model, [
        `a:${artist}`,
        `o:${output}`,
        `a:${artist}|o:${output}`,
        `g:${artist === 'ar-tool' ? 'metal' : 'pop'}`,
        `g:${artist === 'ar-tool' ? 'metal' : 'pop'}|o:${output}`,
      ]);
    expect(at('ar-tool', 'car')).toBeGreaterThan(0.7);
    expect(at('ar-tool', 'headphones')).toBeLessThan(0.3);
    expect(at('ar-adele', 'car')).toBeLessThan(0.3);
    expect(model.metrics?.holdout).toBe(120);
    expect(model.metrics?.auc).toBeGreaterThan(0.8);
    expect(model.metrics?.logLoss).toBeLessThan(model.metrics?.baselineLogLoss ?? 0);
    // A score that's always the same can't tell skips apart.
    expect(model.metrics?.smartQueueAuc).toBe(0.5);
    expect(model.dataUntil).toBe(599);
    expect(model.trainedAt).toBe(5);
  });

  it('waits for enough listens', () => {
    expect(trainSkipModel(examples(20))).toBeNull();
  });

  it('measures ranking with AUC', () => {
    expect(auc([1, 1, 0, 0], [0.9, 0.8, 0.2, 0.1])).toBe(1);
    expect(auc([1, 0], [0.5, 0.5])).toBe(0.5);
    expect(auc([0, 0], [0.1, 0.2])).toBeNull();
  });
});

describe('feedback and the model over the API', () => {
  let t: TestContext;
  afterEach(async () => {
    await t.close();
  });

  it('records feedback once, trains on listens and feedback, and serves the model', async () => {
    const nav = fakeNavidrome({
      getSong: (params) => {
        const id = params.get('id') ?? '';
        const artist = id.startsWith('m') ? 'Metal Band' : 'Pop Star';
        return subsonicOk({
          song: {
            id,
            title: id,
            album: 'x',
            albumId: `al-${artist}`,
            artist,
            artistId: `ar-${artist}`,
            duration: 200,
            genre: 'G',
            genres: [{ name: 'G' }],
          },
        });
      },
      scrobble: () => subsonicOk({}),
    });
    t = await createTestApp({ navidrome: NAVIDROME, fetch: nav.fetch });
    const headers = await signedInHeaders(t);
    expect(
      SkipModelResponse.parse((await t.app.inject({ url: '/api/stats/model', headers })).json())
        .model,
    ).toBeNull();

    const start = Date.UTC(2026, 9, 1);
    const listens = Array.from({ length: 80 }, (_, i) => ({
      trackId: i % 2 === 0 ? `m${i % 10}` : `p${i % 10}`,
      startedAt: start + i * 300_000,
      kind: i % 2 === 0 ? 'skip' : 'play',
      listenedMs: i % 2 === 0 ? 3_000 : undefined,
      context: { v: 1, source: 'picked', output: 'speaker' },
    }));
    expect(
      (
        await t.app.inject({
          method: 'POST',
          url: '/api/stats/listens',
          headers,
          payload: { listens },
        })
      ).statusCode,
    ).toBe(200);

    const event = {
      trackId: 'm0',
      listenStartedAt: start,
      kind: 'not_now',
      at: start + 2_000,
      positionMs: 2_000,
    };
    const send = () =>
      t.app.inject({
        method: 'POST',
        url: '/api/stats/feedback',
        headers,
        payload: { events: [event] },
      });
    expect(FeedbackBatchResult.parse((await send()).json())).toEqual({
      recorded: 1,
      duplicates: 0,
    });
    expect(FeedbackBatchResult.parse((await send()).json())).toEqual({
      recorded: 0,
      duplicates: 1,
    });

    const trained = SkipModelResponse.parse(
      (await t.app.inject({ method: 'POST', url: '/api/stats/model/train', headers })).json(),
    ).model;
    expect(trained?.examples).toBe(80);
    expect(trained?.weights['a:ar-Metal Band']).toBeGreaterThan(0);
    expect(trained?.weights['a:ar-Pop Star']).toBeLessThan(0);
    const served = SkipModelResponse.parse(
      (await t.app.inject({ url: '/api/stats/model', headers })).json(),
    ).model;
    expect(served).toEqual(trained);
    expect((await t.app.inject({ url: '/api/stats/model' })).statusCode).toBe(401);
  });
});
