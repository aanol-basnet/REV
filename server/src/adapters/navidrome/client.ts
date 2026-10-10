import { createHash, randomBytes } from 'node:crypto';
import { Readable } from 'node:stream';
import type { z } from 'zod';
import { UpstreamError } from '../errors.js';
import { SubsonicEnvelope } from './schemas.js';

export interface NavidromeConfig {
  url: string;
  username: string;
  password: string;
}

export interface AudioStream {
  /** 200 for a full response, 206 for a byte range. */
  status: number;
  headers: Record<string, string>;
  body: Readable;
}

export interface StreamOptions {
  /** Forwarded HTTP Range header, for seeking. */
  range?: string;
  /** Ask Navidrome to transcode down to this bit rate (kbps). Omitted: original file. */
  maxBitRateKbps?: number;
  /** Aborts the upstream download, e.g. when the client disconnects. */
  signal?: AbortSignal;
}

/** Upstream response headers worth relaying to players. */
const STREAM_HEADERS = [
  'content-type',
  'content-length',
  'content-range',
  'accept-ranges',
  'last-modified',
  'etag',
];

export interface CoverArt {
  contentType: string;
  contentLength: number | null;
  body: Readable;
}

const API_VERSION = '1.16.1';
const CLIENT_NAME = 'REV';

// Subsonic error codes: https://opensubsonic.netlify.app/docs/responses/error/
const AUTH_ERROR_CODES = new Set([40, 41, 42, 43, 44, 50]);
const NOT_FOUND_CODE = 70;

/** A list value repeats the parameter (`songId=a&songId=b`), as Subsonic expects. */
type Params = Record<string, string | number | boolean | undefined | readonly string[]>;

/**
 * Minimal Subsonic API client for Navidrome. The only place that knows Navidrome's URL scheme,
 * authentication and error format. Credentials never leave this class.
 */
export class SubsonicClient {
  private readonly baseUrl: string;

  constructor(
    private readonly config: NavidromeConfig,
    private readonly fetchImpl: typeof fetch = fetch,
    private readonly timeoutMs = 15_000,
  ) {
    this.baseUrl = config.url.replace(/\/+$/, '');
  }

  /** The Navidrome account REV signs in as (owner of the playlists REV creates). */
  get username(): string {
    return this.config.username;
  }

  /** Calls `endpoint` and validates the payload inside `subsonic-response` against `schema`. */
  async call<T extends z.ZodType>(
    endpoint: string,
    params: Params,
    schema: T,
  ): Promise<z.infer<T>> {
    const response = await this.request(endpoint, params);
    let json: unknown;
    try {
      json = await response.json();
    } catch (cause) {
      throw new UpstreamError('navidrome', 'protocol', `${endpoint}: response is not JSON`, {
        cause,
      });
    }
    const payload = this.unwrap(endpoint, json);
    const parsed = schema.safeParse(payload);
    if (!parsed.success) {
      throw new UpstreamError('navidrome', 'protocol', `${endpoint}: unexpected response shape`, {
        cause: parsed.error,
      });
    }
    return parsed.data;
  }

  async coverArt(id: string, size?: number): Promise<CoverArt> {
    const response = await this.request('getCoverArt', { id, size });
    const contentType = response.headers.get('content-type') ?? '';
    if (!contentType.startsWith('image/') || !response.body) {
      // Subsonic reports errors (e.g. unknown id) as a normal JSON body.
      this.unwrap('getCoverArt', await response.json().catch(() => null));
      throw new UpstreamError('navidrome', 'protocol', 'getCoverArt: response is not an image');
    }
    const length = Number(response.headers.get('content-length'));
    return {
      contentType,
      contentLength: Number.isFinite(length) && length > 0 ? length : null,
      body: Readable.fromWeb(response.body as import('node:stream/web').ReadableStream),
    };
  }

  /**
   * Opens an audio stream. The timeout covers only the wait for response headers; once audio flows
   * it may take as long as the listener needs, until [options.signal] aborts it.
   */
  async stream(id: string, options: StreamOptions = {}): Promise<AudioStream> {
    const headersTimeout = AbortSignal.timeout(this.timeoutMs);
    const controller = new AbortController();
    const abort = () => controller.abort();
    headersTimeout.addEventListener('abort', abort, { once: true });
    options.signal?.addEventListener('abort', abort, { once: true });

    const params: Params = options.maxBitRateKbps
      ? { id, maxBitRate: options.maxBitRateKbps, estimateContentLength: true }
      : { id, format: 'raw' };
    let response: Response;
    try {
      response = await this.request('stream', params, {
        signal: controller.signal,
        headers: options.range ? { range: options.range } : {},
      });
    } finally {
      headersTimeout.removeEventListener('abort', abort);
    }

    const contentType = response.headers.get('content-type') ?? '';
    if (contentType.includes('json') || contentType.includes('xml') || !response.body) {
      // Subsonic reports errors (unknown id, ...) as a normal body.
      this.unwrap('stream', await response.json().catch(() => null));
      throw new UpstreamError('navidrome', 'protocol', 'stream: response is not audio');
    }
    const headers: Record<string, string> = {};
    for (const name of STREAM_HEADERS) {
      const value = response.headers.get(name);
      if (value !== null) headers[name] = value;
    }
    return {
      status: response.status,
      headers,
      body: Readable.fromWeb(response.body as import('node:stream/web').ReadableStream),
    };
  }

  private async request(
    endpoint: string,
    params: Params,
    init: { signal?: AbortSignal; headers?: Record<string, string> } = {},
  ): Promise<Response> {
    const query = this.authParams();
    const values = new URLSearchParams();
    let hasList = false;
    for (const [key, value] of Object.entries(params)) {
      if (value === undefined) continue;
      if (Array.isArray(value)) {
        hasList = true;
        for (const item of value) values.append(key, String(item));
      } else {
        values.set(key, String(value));
      }
    }
    // Lists (e.g. a playlist's songs) can outgrow a URL: they go as a form body (OpenSubsonic
    // formPost), with only the credentials in the query string.
    const requestInit: RequestInit = {
      signal: init.signal ?? AbortSignal.timeout(this.timeoutMs),
      headers: init.headers ?? {},
    };
    if (hasList) {
      requestInit.method = 'POST';
      requestInit.body = values;
      requestInit.headers = {
        ...init.headers,
        'content-type': 'application/x-www-form-urlencoded',
      };
    } else {
      for (const [key, value] of values) query.append(key, value);
    }
    const url = `${this.baseUrl}/rest/${endpoint}?${query}`;
    let response: Response;
    try {
      response = await this.fetchImpl(url, requestInit);
    } catch (cause) {
      throw new UpstreamError('navidrome', 'unavailable', `${endpoint}: ${describe(cause)}`, {
        cause,
      });
    }
    if (response.status === 401 || response.status === 403) {
      throw new UpstreamError('navidrome', 'auth', `${endpoint}: HTTP ${response.status}`);
    }
    if (response.status === 416) {
      throw new UpstreamError('navidrome', 'protocol', `${endpoint}: range not satisfiable`);
    }
    if (!response.ok) {
      throw new UpstreamError(
        'navidrome',
        response.status >= 500 ? 'unavailable' : 'protocol',
        `${endpoint}: HTTP ${response.status}`,
      );
    }
    return response;
  }

  private unwrap(endpoint: string, json: unknown): unknown {
    const envelope = SubsonicEnvelope.safeParse(json);
    if (!envelope.success) {
      throw new UpstreamError('navidrome', 'protocol', `${endpoint}: not a Subsonic response`);
    }
    const body = envelope.data['subsonic-response'];
    if (body.status === 'failed') {
      const code = body.error?.code ?? 0;
      const message = `${endpoint}: Subsonic error ${code} ${body.error?.message ?? ''}`.trim();
      if (AUTH_ERROR_CODES.has(code)) throw new UpstreamError('navidrome', 'auth', message);
      if (code === NOT_FOUND_CODE) throw new UpstreamError('navidrome', 'not_found', message);
      throw new UpstreamError('navidrome', 'protocol', message);
    }
    return body;
  }

  /** Token authentication: t = md5(password + salt), with a fresh salt per request. */
  private authParams(): URLSearchParams {
    const salt = randomBytes(8).toString('hex');
    const token = createHash('md5')
      .update(this.config.password + salt)
      .digest('hex');
    return new URLSearchParams({
      u: this.config.username,
      t: token,
      s: salt,
      v: API_VERSION,
      c: CLIENT_NAME,
      f: 'json',
    });
  }
}

function describe(err: unknown): string {
  if (err instanceof Error) {
    const cause = (err as { cause?: { code?: string } }).cause?.code;
    return cause ? `${err.name} (${cause})` : err.name;
  }
  return 'request failed';
}
