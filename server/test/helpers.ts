import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { AuthService, DEFAULT_AUTH_OPTIONS, type AuthOptions } from '../src/auth/service.js';
import { createUser } from '../src/auth/users.js';
import type { Config } from '../src/config.js';
import { openDatabase, type DbHandle } from '../src/db/client.js';
import type { Interfaces } from '../src/system/lan.js';

export interface TestContext {
  app: FastifyInstance;
  handle: DbHandle;
  clock: { now: number; advance(ms: number): void };
  close(): Promise<void>;
}

export interface TestAppOptions {
  auth?: Partial<AuthOptions>;
  navidrome?: Config['navidrome'];
  lidarr?: Config['lidarr'];
  slskd?: Config['slskd'];
  downloads?: Partial<Config['downloads']>;
  lyrics?: Partial<Config['lyrics']>;
  now?: () => number;
  castBaseUrl?: string;
  host?: string;
  lanUrl?: string;
  networkInterfaces?: () => Interfaces;
  fetch?: typeof fetch;
}

export async function createTestApp(options: TestAppOptions = {}): Promise<TestContext> {
  const handle = openDatabase(':memory:');
  const clock = {
    now: 1_800_000_000_000,
    advance(ms: number) {
      this.now += ms;
    },
  };
  const authService = new AuthService(
    handle.db,
    { ...DEFAULT_AUTH_OPTIONS, ...options.auth },
    () => clock.now,
  );
  const app = await buildApp({
    config: {
      logLevel: 'info',
      navidrome: options.navidrome ?? {},
      lidarr: options.lidarr ?? {},
      slskd: options.slskd ?? {},
      lyrics: {
        providers: ['navidrome', 'lrclib'],
        lrclibUrl: 'https://lrclib.test',
        ...options.lyrics,
      },
      downloads: {
        pollIntervalMs: 15_000,
        timeoutMs: 72 * 3_600_000,
        indexingTimeoutMs: 6 * 3_600_000,
        ...options.downloads,
      },
      ...(options.castBaseUrl ? { castBaseUrl: options.castBaseUrl.replace(/\/+$/, '') } : {}),
      ...(options.host ? { host: options.host, port: 4600 } : {}),
      ...(options.lanUrl ? { lanUrl: options.lanUrl } : {}),
    },
    ...(options.networkInterfaces ? { networkInterfaces: options.networkInterfaces } : {}),
    backgroundTasks: false,
    ...(options.now ? { now: options.now } : {}),
    db: handle.db,
    authService,
    logger: false,
    ...(options.fetch ? { fetch: options.fetch } : {}),
  });
  return {
    app,
    handle,
    clock,
    async close() {
      await app.close();
      handle.close();
    },
  };
}

/** Creates a user and returns an Authorization header for them. */
export async function signedInHeaders(t: TestContext): Promise<{ authorization: string }> {
  await createUser(t.handle.db, 'tester', 'a long password');
  const res = await t.app.inject({
    method: 'POST',
    url: '/api/auth/login',
    payload: { username: 'tester', password: 'a long password' },
  });
  return { authorization: `Bearer ${(res.json() as { accessToken: string }).accessToken}` };
}
