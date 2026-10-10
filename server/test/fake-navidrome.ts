import { createHash } from 'node:crypto';

export const NAVIDROME = {
  url: 'http://navidrome.test:4533/',
  username: 'rev',
  password: 'hunter2',
};

type Handler = (params: URLSearchParams) => Response | Promise<Response>;

export function subsonicOk(payload: Record<string, unknown>): Response {
  return Response.json({
    'subsonic-response': { status: 'ok', version: '1.16.1', type: 'navidrome', ...payload },
  });
}

export function subsonicError(code: number, message = 'error'): Response {
  return Response.json({
    'subsonic-response': { status: 'failed', version: '1.16.1', error: { code, message } },
  });
}

/**
 * A fetch stand-in for Navidrome. Verifies Subsonic token auth on every call and records requests.
 * Unregistered endpoints fail the test loudly.
 */
export function fakeNavidrome(handlers: Record<string, Handler>) {
  const calls: { endpoint: string; params: URLSearchParams }[] = [];
  const fetchImpl = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(
      typeof input === 'string' ? input : input instanceof URL ? input : input.url,
    );
    const endpoint = url.pathname.replace(/^\/rest\//, '');
    const params = url.searchParams;
    // Form-posted parameters (lists) count the same as query parameters.
    if (init?.body instanceof URLSearchParams) {
      for (const [key, value] of init.body) params.append(key, value);
    }
    calls.push({ endpoint, params });

    const salt = params.get('s') ?? '';
    const expected = createHash('md5')
      .update(NAVIDROME.password + salt)
      .digest('hex');
    if (params.get('u') !== NAVIDROME.username || params.get('t') !== expected) {
      return subsonicError(40, 'Wrong username or password');
    }
    const handler = handlers[endpoint];
    if (!handler) throw new Error(`unexpected Navidrome call: ${endpoint}`);
    return handler(params);
  }) as typeof fetch;
  return { fetch: fetchImpl, calls };
}
