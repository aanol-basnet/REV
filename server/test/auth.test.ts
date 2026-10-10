import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ApiError, SessionResponse, TokenResponse } from '@rev/schemas';
import { createUser } from '../src/auth/users.js';
import { createTestApp, type TestContext } from './helpers.js';

const PASSWORD = 'correct horse battery';
const MINUTE = 60_000;

describe('auth API', () => {
  let t: TestContext;

  beforeEach(async () => {
    t = await createTestApp();
    await createUser(t.handle.db, 'Alice', PASSWORD);
  });

  afterEach(async () => {
    await t.close();
  });

  async function login(username = 'alice', password = PASSWORD) {
    return t.app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: { username, password, deviceName: 'Test phone' },
    });
  }

  async function loginOk(): Promise<TokenResponse> {
    const res = await login();
    expect(res.statusCode).toBe(200);
    return TokenResponse.parse(res.json());
  }

  function session(accessToken: string) {
    return t.app.inject({
      url: '/api/auth/session',
      headers: { authorization: `Bearer ${accessToken}` },
    });
  }

  function refresh(refreshToken: string) {
    return t.app.inject({ method: 'POST', url: '/api/auth/refresh', payload: { refreshToken } });
  }

  it('logs in with valid credentials, case-insensitively', async () => {
    const res = await login('ALICE');
    expect(res.statusCode).toBe(200);
    const tokens = TokenResponse.parse(res.json());
    expect(tokens.user.username).toBe('alice');
    expect(tokens.accessToken).toMatch(/^rev_at_/);
    expect(tokens.refreshToken).toMatch(/^rev_rt_/);
  });

  it('gives identical responses for a wrong password and an unknown user', async () => {
    const wrongPassword = await login('alice', 'nope nope nope');
    const unknownUser = await login('nobody', PASSWORD);
    expect(wrongPassword.statusCode).toBe(401);
    expect(unknownUser.statusCode).toBe(401);
    expect(wrongPassword.json()).toEqual(unknownUser.json());
    expect(ApiError.parse(wrongPassword.json()).error.code).toBe('INVALID_CREDENTIALS');
  });

  it('rejects malformed bodies with a validation error', async () => {
    const res = await t.app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: { username: '' },
    });
    expect(res.statusCode).toBe(400);
    const err = ApiError.parse(res.json()).error;
    expect(err.code).toBe('VALIDATION_ERROR');
    expect(err.message).toContain('password');
  });

  it('protects session routes and advertises bearer auth', async () => {
    const res = await t.app.inject('/api/auth/session');
    expect(res.statusCode).toBe(401);
    expect(res.headers['www-authenticate']).toBe('Bearer');

    const bogus = await session('rev_at_bogus');
    expect(bogus.statusCode).toBe(401);
  });

  it('returns the current session for a valid access token', async () => {
    const tokens = await loginOk();
    const res = await session(tokens.accessToken);
    expect(res.statusCode).toBe(200);
    const body = SessionResponse.parse(res.json());
    expect(body.user.username).toBe('alice');
    expect(body.deviceName).toBe('Test phone');
  });

  it('expires access tokens and issues new ones on refresh', async () => {
    const tokens = await loginOk();
    t.clock.advance(16 * MINUTE);
    expect((await session(tokens.accessToken)).statusCode).toBe(401);

    const res = await refresh(tokens.refreshToken);
    expect(res.statusCode).toBe(200);
    const next = TokenResponse.parse(res.json());
    expect(next.refreshToken).not.toBe(tokens.refreshToken);
    expect((await session(next.accessToken)).statusCode).toBe(200);
  });

  it('accepts the previous refresh token briefly after rotation (lost response)', async () => {
    const tokens = await loginOk();
    expect((await refresh(tokens.refreshToken)).statusCode).toBe(200);
    t.clock.advance(10_000);
    const retry = await refresh(tokens.refreshToken);
    expect(retry.statusCode).toBe(200);
    expect((await session(TokenResponse.parse(retry.json()).accessToken)).statusCode).toBe(200);
  });

  it('revokes the session when a superseded refresh token is replayed after the grace period', async () => {
    const tokens = await loginOk();
    const rotated = TokenResponse.parse((await refresh(tokens.refreshToken)).json());
    t.clock.advance(2 * MINUTE);

    const replay = await refresh(tokens.refreshToken);
    expect(replay.statusCode).toBe(401);
    expect(ApiError.parse(replay.json()).error.code).toBe('INVALID_REFRESH_TOKEN');

    // The legitimate holder of the newest tokens is signed out too.
    expect((await session(rotated.accessToken)).statusCode).toBe(401);
    expect((await refresh(rotated.refreshToken)).statusCode).toBe(401);
  });

  it('rejects refresh tokens after they expire', async () => {
    const tokens = await loginOk();
    t.clock.advance(31 * 24 * 60 * MINUTE);
    expect((await refresh(tokens.refreshToken)).statusCode).toBe(401);
  });

  it('logout invalidates both tokens', async () => {
    const tokens = await loginOk();
    const res = await t.app.inject({
      method: 'POST',
      url: '/api/auth/logout',
      headers: { authorization: `Bearer ${tokens.accessToken}` },
    });
    expect(res.statusCode).toBe(204);
    expect((await session(tokens.accessToken)).statusCode).toBe(401);
    expect((await refresh(tokens.refreshToken)).statusCode).toBe(401);
  });

  it('rate-limits login attempts', async () => {
    const codes: number[] = [];
    for (let i = 0; i < 11; i++) codes.push((await login('alice', 'wrong password')).statusCode);
    expect(codes.slice(0, 10).every((c) => c === 401)).toBe(true);
    const limited = await login();
    expect(limited.statusCode).toBe(429);
    expect(ApiError.parse(limited.json()).error.code).toBe('RATE_LIMITED');
  });
});
