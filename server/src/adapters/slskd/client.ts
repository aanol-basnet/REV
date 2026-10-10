import { z } from 'zod';
import { UpstreamError } from '../errors.js';

export interface SlskdConfig {
  url: string;
  apiKey: string;
}

// slskd API v0 (https://github.com/slskd/slskd). Only fields REV uses; the rest is ignored.

const Transfer = z.object({
  id: z.string(),
  username: z.string(),
  filename: z.string(),
  size: z.number().default(0),
  /** Flags enum serialized as e.g. "Queued, Remotely", "InProgress", "Completed, Succeeded". */
  state: z.string(),
  bytesTransferred: z.number().default(0),
  requestedAt: z.string().nullish(),
});

const DownloadsResponse = z.array(
  z.object({
    username: z.string(),
    directories: z
      .array(z.object({ directory: z.string(), files: z.array(Transfer).default([]) }))
      .default([]),
  }),
);

const SearchesResponse = z.array(
  z.object({
    id: z.string(),
    searchText: z.string(),
    /** e.g. "InProgress", "Completed, TimedOut". */
    state: z.string(),
    startedAt: z.string().nullish(),
  }),
);

const ApplicationResponse = z.object({
  version: z.object({ current: z.string().nullish() }).partial().nullish(),
  server: z.object({ state: z.string().nullish() }).partial().nullish(),
});

export type TransferPhase = 'queued' | 'active' | 'succeeded' | 'failed';

export interface SlskdTransfer {
  username: string;
  /** Remote directory the file came from, e.g. "@@abc\\Music\\Tool\\2001 - Lateralus". */
  directory: string;
  filename: string;
  phase: TransferPhase;
  size: number;
  bytesTransferred: number;
  requestedAt: number | null;
}

export interface SlskdSearch {
  searchText: string;
  active: boolean;
  startedAt: number | null;
}

export interface SlskdHealth {
  version: string | null;
  /** Logged in to the Soulseek network. */
  connected: boolean;
}

/**
 * Read-only slskd client. REV observes transfers and searches made by Soularr; it never enqueues,
 * cancels or searches itself. The API key travels in the X-API-Key header.
 */
export class SlskdClient {
  private readonly baseUrl: string;

  constructor(
    private readonly config: SlskdConfig,
    private readonly fetchImpl: typeof fetch = fetch,
    private readonly timeoutMs = 10_000,
  ) {
    this.baseUrl = config.url.replace(/\/+$/, '');
  }

  async downloads(): Promise<SlskdTransfer[]> {
    const users = await this.get('transfers/downloads', DownloadsResponse);
    return users.flatMap((user) =>
      user.directories.flatMap((dir) =>
        dir.files.map((f) => ({
          username: user.username,
          directory: dir.directory,
          filename: f.filename,
          phase: transferPhase(f.state),
          size: f.size,
          bytesTransferred: f.bytesTransferred,
          requestedAt: timestamp(f.requestedAt),
        })),
      ),
    );
  }

  async searches(): Promise<SlskdSearch[]> {
    const searches = await this.get('searches', SearchesResponse);
    return searches.map((s) => ({
      searchText: s.searchText,
      active: !s.state.includes('Completed'),
      startedAt: timestamp(s.startedAt),
    }));
  }

  async health(): Promise<SlskdHealth> {
    const app = await this.get('application', ApplicationResponse);
    const state = app.server?.state ?? '';
    return { version: app.version?.current ?? null, connected: state.includes('LoggedIn') };
  }

  private async get<T extends z.ZodType>(path: string, schema: T): Promise<z.infer<T>> {
    let response: Response;
    try {
      response = await this.fetchImpl(`${this.baseUrl}/api/v0/${path}`, {
        headers: { 'x-api-key': this.config.apiKey, accept: 'application/json' },
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch (cause) {
      throw new UpstreamError('slskd', 'unavailable', `${path}: request failed`, { cause });
    }
    if (response.status === 401 || response.status === 403) {
      throw new UpstreamError('slskd', 'auth', `${path}: HTTP ${response.status}`);
    }
    if (!response.ok) {
      throw new UpstreamError(
        'slskd',
        response.status >= 500 ? 'unavailable' : 'protocol',
        `${path}: HTTP ${response.status}`,
      );
    }
    const parsed = schema.safeParse(await response.json().catch(() => null));
    if (!parsed.success) {
      throw new UpstreamError('slskd', 'protocol', `${path}: unexpected response shape`, {
        cause: parsed.error,
      });
    }
    return parsed.data;
  }
}

export function transferPhase(state: string): TransferPhase {
  if (state.includes('Completed')) return state.includes('Succeeded') ? 'succeeded' : 'failed';
  if (state.includes('InProgress') || state.includes('Initializing')) return 'active';
  return 'queued'; // Requested, Queued (locally/remotely)
}

function timestamp(value: string | null | undefined): number | null {
  if (!value) return null;
  // slskd serializes DateTime without a zone; it is UTC.
  const ms = Date.parse(/[zZ]|[+-]\d\d:\d\d$/.test(value) ? value : `${value}Z`);
  return Number.isNaN(ms) ? null : ms;
}
