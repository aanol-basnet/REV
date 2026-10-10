import type { z } from 'zod';
import { UpstreamError } from '../errors.js';

export interface LidarrConfig {
  url: string;
  apiKey: string;
}

type Query = Record<string, string | number | boolean | undefined>;

/**
 * Minimal Lidarr API v1 client. The only code that knows Lidarr's URL scheme and API key; the key
 * is sent as a header (never in URLs, so it can't leak into logs).
 */
export class LidarrClient {
  private readonly baseUrl: string;

  constructor(
    private readonly config: LidarrConfig,
    private readonly fetchImpl: typeof fetch = fetch,
    /** Catalog lookups go out to Lidarr's metadata server and can be slow. */
    private readonly timeoutMs = 20_000,
  ) {
    this.baseUrl = config.url.replace(/\/+$/, '');
  }

  get<T extends z.ZodType>(path: string, query: Query, schema: T): Promise<z.infer<T>> {
    return this.send('GET', path, query, undefined, schema);
  }

  async send<T extends z.ZodType>(
    method: 'GET' | 'POST' | 'PUT' | 'DELETE',
    path: string,
    query: Query,
    body: unknown,
    schema: T,
  ): Promise<z.infer<T>> {
    const url = new URL(`${this.baseUrl}/api/v1/${path}`);
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined) url.searchParams.set(key, String(value));
    }
    const endpoint = `${method} ${path}`;
    let response: Response;
    try {
      response = await this.fetchImpl(url, {
        method,
        headers: {
          'x-api-key': this.config.apiKey,
          accept: 'application/json',
          ...(body === undefined ? {} : { 'content-type': 'application/json' }),
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch (cause) {
      throw new UpstreamError('lidarr', 'unavailable', `${endpoint}: request failed`, { cause });
    }

    if (response.status === 401 || response.status === 403) {
      throw new UpstreamError('lidarr', 'auth', `${endpoint}: HTTP ${response.status}`);
    }
    if (response.status === 404) {
      throw new UpstreamError('lidarr', 'not_found', `${endpoint}: HTTP 404`);
    }
    if (!response.ok) {
      const detail = await response.text().catch(() => '');
      throw new UpstreamError(
        'lidarr',
        response.status >= 500 ? 'unavailable' : 'protocol',
        `${endpoint}: HTTP ${response.status} ${detail.slice(0, 300)}`.trim(),
      );
    }

    const text = await response.text();
    let json: unknown = null;
    if (text.length > 0) {
      try {
        json = JSON.parse(text);
      } catch (cause) {
        throw new UpstreamError('lidarr', 'protocol', `${endpoint}: response is not JSON`, {
          cause,
        });
      }
    }
    const parsed = schema.safeParse(json);
    if (!parsed.success) {
      throw new UpstreamError('lidarr', 'protocol', `${endpoint}: unexpected response shape`, {
        cause: parsed.error,
      });
    }
    return parsed.data;
  }
}
