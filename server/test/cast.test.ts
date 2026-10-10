import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, CastToken } from '@rev/schemas';
import { createTestApp, signedInHeaders, type TestContext } from './helpers.js';
import { fakeNavidrome, NAVIDROME, subsonicOk } from './fake-navidrome.js';

describe('cast tokens', () => {
  let t: TestContext;
  let now = 1_800_000_000_000;

  afterEach(async () => {
    await t.close();
  });

  async function setup(castBaseUrl?: string) {
    now = 1_800_000_000_000;
    const nav = fakeNavidrome({
      stream: () =>
        new Response(Buffer.from('audio'), { headers: { 'content-type': 'audio/flac' } }),
      getCoverArt: () =>
        new Response(Buffer.from([1, 2]), { headers: { 'content-type': 'image/jpeg' } }),
      getArtists: () => subsonicOk({ artists: { index: [] } }),
    });
    t = await createTestApp({
      navidrome: NAVIDROME,
      fetch: nav.fetch,
      now: () => now,
      ...(castBaseUrl ? { castBaseUrl } : {}),
    });
    return signedInHeaders(t);
  }

  async function issue(headers: Record<string, string>) {
    const res = await t.app.inject({ method: 'POST', url: '/api/cast/token', headers });
    expect(res.statusCode).toBe(200);
    return CastToken.parse(res.json());
  }

  it('lets a receiver fetch audio and artwork with the token instead of a session', async () => {
    const headers = await setup('http://192.168.1.10:4600/');
    const cast = await issue(headers);
    expect(cast.baseUrl).toBe('http://192.168.1.10:4600');
    const q = `exp=${cast.expiresAt}&cast=${encodeURIComponent(cast.token)}`;

    expect((await t.app.inject(`/api/library/tracks/tr-1/stream?${q}`)).statusCode).toBe(200);
    expect((await t.app.inject(`/api/library/artwork/al-1?${q}`)).statusCode).toBe(200);
  });

  it('unlocks nothing but media', async () => {
    const headers = await setup('http://192.168.1.10:4600');
    const cast = await issue(headers);
    const q = `exp=${cast.expiresAt}&cast=${encodeURIComponent(cast.token)}`;
    expect((await t.app.inject(`/api/library/artists?${q}`)).statusCode).toBe(401);
    expect((await t.app.inject({ method: 'POST', url: `/api/cast/token?${q}` })).statusCode).toBe(
      401,
    );
  });

  it('rejects forged, altered and expired tokens', async () => {
    const headers = await setup('http://192.168.1.10:4600');
    const cast = await issue(headers);
    const stream = (exp: number, token: string) =>
      t.app.inject(`/api/library/tracks/tr-1/stream?exp=${exp}&cast=${encodeURIComponent(token)}`);

    expect((await stream(cast.expiresAt, 'forged')).statusCode).toBe(401);
    expect((await stream(cast.expiresAt + 3600, cast.token)).statusCode).toBe(401); // extended expiry
    now += 13 * 3600 * 1000;
    expect((await stream(cast.expiresAt, cast.token)).statusCode).toBe(401);
  });

  it('reports when casting is not set up', async () => {
    const headers = await setup();
    const res = await t.app.inject({ method: 'POST', url: '/api/cast/token', headers });
    expect(res.statusCode).toBe(503);
    expect(ApiError.parse(res.json()).error.code).toBe('CAST_NOT_CONFIGURED');
  });
});
