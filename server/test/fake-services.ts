/** Routes a single fetch stand-in to per-host fakes; unknown hosts fail the test. */
export function routeFetch(routes: Record<string, typeof fetch>): typeof fetch {
  return (async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
    const url = new URL(
      typeof input === 'string' ? input : input instanceof URL ? input : input.url,
    );
    const handler = routes[url.host];
    if (!handler) throw new Error(`unexpected request to ${url.host}`);
    return handler(input, init);
  }) as typeof fetch;
}

export const LIDARR = { url: 'http://lidarr.test:8686', apiKey: 'lidarr-secret-key' };

type LidarrHandler = (url: URL) => unknown;

/** Fake Lidarr: checks the API key header and answers JSON per `path` (after /api/v1/). */
export function fakeLidarr(handlers: Record<string, LidarrHandler>) {
  const calls: { path: string; url: URL; method: string; body: unknown }[] = [];
  const fetchImpl = (async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
    const url = new URL(
      typeof input === 'string' ? input : input instanceof URL ? input : input.url,
    );
    const path = url.pathname.replace(/^\/api\/v1\//, '');
    const method = init?.method ?? 'GET';
    calls.push({ path, url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    const headers = new Headers(init?.headers);
    if (headers.get('x-api-key') !== LIDARR.apiKey)
      return new Response('Unauthorized', { status: 401 });
    const handler = handlers[`${method} ${path}`] ?? handlers[path];
    if (!handler) return new Response('Not found', { status: 404 });
    return Response.json(handler(url));
  }) as typeof fetch;
  return { fetch: fetchImpl, calls };
}

export const SLSKD = { url: 'http://slskd.test:5030', apiKey: 'slskd-secret-key' };

export interface SlskdWorld {
  transfers: {
    username: string;
    directory: string;
    filename: string;
    state: string;
    size: number;
    bytesTransferred: number;
    requestedAt: string;
  }[];
  searches: { id: string; searchText: string; state: string; startedAt: string }[];
  serverState: string;
}

/** Fake slskd serving transfers/searches/application from a mutable world. */
export function fakeSlskd(world: SlskdWorld): typeof fetch {
  return (async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
    const url = new URL(
      typeof input === 'string' ? input : input instanceof URL ? input : input.url,
    );
    if (new Headers(init?.headers).get('x-api-key') !== SLSKD.apiKey)
      return new Response(null, { status: 401 });
    switch (url.pathname) {
      case '/api/v0/transfers/downloads': {
        const byUser = new Map<string, Map<string, object[]>>();
        world.transfers.forEach((t, i) => {
          const dirs = byUser.get(t.username) ?? new Map<string, object[]>();
          byUser.set(t.username, dirs);
          const files = dirs.get(t.directory) ?? [];
          dirs.set(t.directory, files);
          files.push({ id: `t${i}`, ...t, filename: `${t.directory}\\${t.filename}` });
        });
        return Response.json(
          [...byUser].map(([username, dirs]) => ({
            username,
            directories: [...dirs].map(([directory, files]) => ({
              directory,
              fileCount: files.length,
              files,
            })),
          })),
        );
      }
      case '/api/v0/searches':
        return Response.json(world.searches);
      case '/api/v0/application':
        return Response.json({
          version: { current: '0.24.0' },
          server: { state: world.serverState },
        });
      default:
        return new Response(null, { status: 404 });
    }
  }) as typeof fetch;
}
