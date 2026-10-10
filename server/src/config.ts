import { z } from 'zod';

const optionalUrl = z.preprocess((v) => (v === '' ? undefined : v), z.string().url().optional());
const optionalString = z.preprocess((v) => (v === '' ? undefined : v), z.string().optional());

const EnvSchema = z.object({
  REV_HOST: z.string().default('127.0.0.1'),
  REV_PORT: z.coerce.number().int().min(1).max(65535).default(4600),
  REV_DATABASE_URL: z.string().min(1).default('./data/rev.db'),
  REV_LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace']).default('info'),

  // Backend services. Optional until the phase that integrates each one.
  NAVIDROME_URL: optionalUrl,
  NAVIDROME_USERNAME: optionalString,
  NAVIDROME_PASSWORD: optionalString,
  LIDARR_URL: optionalUrl,
  LIDARR_API_KEY: optionalString,
  /** Where requested albums go. Default: Lidarr's first accessible root folder. */
  LIDARR_ROOT_FOLDER: optionalString,
  /** Profiles for artists REV adds. Default: the root folder's defaults. */
  LIDARR_QUALITY_PROFILE_ID: z.preprocess(
    (v) => (v === '' ? undefined : v),
    z.coerce.number().int().positive().optional(),
  ),
  LIDARR_METADATA_PROFILE_ID: z.preprocess(
    (v) => (v === '' ? undefined : v),
    z.coerce.number().int().positive().optional(),
  ),
  /** Also ask Lidarr to search its own indexers when an album is requested (Soularr works either way). */
  LIDARR_SEARCH_ON_REQUEST: z.preprocess(
    (v) => (v === '' ? undefined : v),
    z.enum(['true', 'false']).default('true'),
  ),
  SLSKD_URL: optionalUrl,
  SLSKD_API_KEY: optionalString,

  /** Address Cast receivers on the home network use to reach REV, e.g. http://192.168.1.10:4600. */
  REV_CAST_BASE_URL: optionalUrl,
  REV_LAN_URL: optionalUrl,

  /** Lyrics providers in priority order, after REV's cache (§34). */
  REV_LYRICS_PROVIDERS: z.string().default('navidrome,lrclib'),
  LRCLIB_URL: z.string().url().default('https://lrclib.net'),

  /** How often active downloads are checked (seconds). */
  REV_DOWNLOAD_POLL_SECONDS: z.coerce.number().int().min(5).max(600).default(15),
  /** A request that hasn't become playable in this long is given up (hours). */
  REV_DOWNLOAD_TIMEOUT_HOURS: z.coerce
    .number()
    .min(1)
    .max(24 * 30)
    .default(72),
  /** Imported but still not in Navidrome after this long → timeout (hours). */
  REV_INDEXING_TIMEOUT_HOURS: z.coerce.number().min(0.1).max(72).default(6),
});

export interface Config {
  host: string;
  port: number;
  databasePath: string;
  logLevel: z.infer<typeof EnvSchema>['REV_LOG_LEVEL'];
  navidrome: { url?: string; username?: string; password?: string };
  lidarr: {
    url?: string;
    apiKey?: string;
    rootFolder?: string;
    qualityProfileId?: number;
    metadataProfileId?: number;
    searchOnRequest?: boolean;
  };
  slskd: { url?: string; apiKey?: string };
  downloads: { pollIntervalMs: number; timeoutMs: number; indexingTimeoutMs: number };
  lyrics: { providers: ('navidrome' | 'lrclib')[]; lrclibUrl: string };
  castBaseUrl?: string;
  /** Overrides the detected LAN address(es) given to the app, e.g. behind unusual networking. */
  lanUrl?: string;
}

export class ConfigError extends Error {
  override name = 'ConfigError';
}

/** Strips undefined values so optional props satisfy exactOptionalPropertyTypes. */
function defined<T extends Record<string, unknown>>(
  obj: T,
): { [K in keyof T]?: Exclude<T[K], undefined> } {
  return Object.fromEntries(Object.entries(obj).filter(([, v]) => v !== undefined)) as {
    [K in keyof T]?: Exclude<T[K], undefined>;
  };
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const parsed = EnvSchema.safeParse(env);
  if (!parsed.success) {
    // Report variable names only; values may be secrets.
    const problems = parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`);
    throw new ConfigError(`Invalid configuration:\n  ${problems.join('\n  ')}`);
  }
  const e = parsed.data;
  return {
    host: e.REV_HOST,
    port: e.REV_PORT,
    databasePath: e.REV_DATABASE_URL,
    logLevel: e.REV_LOG_LEVEL,
    navidrome: defined({
      url: e.NAVIDROME_URL,
      username: e.NAVIDROME_USERNAME,
      password: e.NAVIDROME_PASSWORD,
    }),
    lidarr: defined({
      url: e.LIDARR_URL,
      apiKey: e.LIDARR_API_KEY,
      rootFolder: e.LIDARR_ROOT_FOLDER,
      qualityProfileId: e.LIDARR_QUALITY_PROFILE_ID,
      metadataProfileId: e.LIDARR_METADATA_PROFILE_ID,
      searchOnRequest: e.LIDARR_SEARCH_ON_REQUEST === 'true',
    }),
    slskd: defined({ url: e.SLSKD_URL, apiKey: e.SLSKD_API_KEY }),
    ...(e.REV_CAST_BASE_URL ? { castBaseUrl: e.REV_CAST_BASE_URL.replace(/\/+$/, '') } : {}),
    ...(e.REV_LAN_URL ? { lanUrl: e.REV_LAN_URL.replace(/\/+$/, '') } : {}),
    lyrics: {
      providers: e.REV_LYRICS_PROVIDERS.split(',')
        .map((p) => p.trim())
        .filter((p): p is 'navidrome' | 'lrclib' => p === 'navidrome' || p === 'lrclib'),
      lrclibUrl: e.LRCLIB_URL,
    },
    downloads: {
      pollIntervalMs: e.REV_DOWNLOAD_POLL_SECONDS * 1000,
      timeoutMs: e.REV_DOWNLOAD_TIMEOUT_HOURS * 3_600_000,
      indexingTimeoutMs: e.REV_INDEXING_TIMEOUT_HOURS * 3_600_000,
    },
  };
}
