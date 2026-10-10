import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ApiError, HealthResponse } from '@rev/schemas';
import { buildApp } from '../src/app.js';
import { createTestApp, type TestContext } from './helpers.js';

describe('system routes', () => {
  let t: TestContext;

  beforeEach(async () => {
    t = await createTestApp();
  });

  afterEach(async () => {
    await t.close();
  });

  it('GET /api/system/health returns a valid health payload without auth', async () => {
    const res = await t.app.inject({ method: 'GET', url: '/api/system/health' });
    expect(res.statusCode).toBe(200);
    expect(HealthResponse.parse(res.json()).name).toBe('REV');
  });

  it('instance id is stable across app instances on the same database', async () => {
    const first = HealthResponse.parse((await t.app.inject('/api/system/health')).json());
    const again = await buildApp({
      config: {
        logLevel: 'info',
        navidrome: {},
        lidarr: {},
        slskd: {},
        downloads: { pollIntervalMs: 15_000, timeoutMs: 1, indexingTimeoutMs: 1 },
        lyrics: { providers: [], lrclibUrl: 'https://lrclib.test' },
      },
      backgroundTasks: false,
      db: t.handle.db,
      logger: false,
    });
    const second = HealthResponse.parse((await again.inject('/api/system/health')).json());
    await again.close();
    expect(second.instanceId).toBe(first.instanceId);
  });

  it('unknown routes return the standard error shape', async () => {
    const res = await t.app.inject('/api/nope');
    expect(res.statusCode).toBe(404);
    expect(ApiError.parse(res.json()).error.code).toBe('NOT_FOUND');
  });
});
