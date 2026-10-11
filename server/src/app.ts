import Fastify, { type FastifyInstance } from 'fastify';
import rateLimit from '@fastify/rate-limit';
import websocket from '@fastify/websocket';
import type { Config } from './config.js';
import type { Db } from './db/client.js';
import { getOrCreateInstanceId } from './db/meta.js';
import { HttpError, registerErrorHandling } from './api/errors.js';
import { systemRoutes } from './api/system.js';
import { authRoutes } from './api/auth.js';
import { libraryRoutes } from './api/library.js';
import { AuthService } from './auth/service.js';
import { SubsonicClient } from './adapters/navidrome/client.js';
import { NavidromeLibrary } from './adapters/navidrome/library.js';
import { LidarrClient } from './adapters/lidarr/client.js';
import { LidarrCatalog } from './adapters/lidarr/catalog.js';
import { SearchService } from './search/service.js';
import { searchRoutes } from './api/search.js';
import { downloadRoutes } from './api/downloads.js';
import { LidarrManager } from './adapters/lidarr/manager.js';
import { DownloadJobStore } from './downloads/jobs.js';
import { AcquisitionService } from './downloads/service.js';
import { DownloadMonitor } from './downloads/monitor.js';
import { EventBus } from './events/bus.js';
import { eventRoutes } from './api/events.js';
import { SlskdClient } from './adapters/slskd/client.js';
import { LidarrObserver } from './adapters/lidarr/observer.js';
import { HealthMonitor } from './system/health.js';
import { SystemStatusService } from './system/status.js';
import { LyricsService } from './lyrics/service.js';
import {
  LrclibProvider,
  type LyricsProvider,
  NavidromeLyricsProvider,
} from './lyrics/providers.js';
import { lyricsRoutes } from './api/lyrics.js';
import { networkInterfaces } from 'node:os';
import { CastTokens } from './cast/tokens.js';
import { type Interfaces, lanUrls } from './system/lan.js';
import { HomeService } from './home/service.js';
import { TimeMixService } from './home/time-mix.js';
import { AutoplayService } from './home/autoplay.js';
import { TopTracksService } from './library/top-tracks.js';
import { homeRoutes } from './api/home.js';
import { ListeningService } from './stats/service.js';
import { ModelService } from './model/service.js';

/** Personal skip models: first trained a minute after start, then checked for new data every few hours. */
const MODEL_FIRST_TRAINING_MS = 60_000;
const MODEL_TRAINING_INTERVAL_MS = 3 * 3_600_000;
import { statsRoutes } from './api/stats.js';
import { SmartPlaylistService } from './stats/smart-playlists.js';
import { PlaylistService } from './library/playlists.js';

export interface AppDeps {
  config: Pick<
    Config,
    'logLevel' | 'navidrome' | 'lidarr' | 'slskd' | 'downloads' | 'lyrics' | 'castBaseUrl'
  > &
    Partial<Pick<Config, 'host' | 'port' | 'lanUrl'>>;
  /** Network interfaces, for working out LAN addresses; overridden in tests. */
  networkInterfaces?: () => Interfaces;
  db: Db;
  /** Override for tests (e.g. a controllable clock). */
  authService?: AuthService;
  /** fetch used for backend services; overridden in tests. */
  fetch?: typeof fetch;
  /** Disable request logging in tests. */
  logger?: boolean;
  /** Run the download and health monitors on timers. Tests drive them by hand. */
  backgroundTasks?: boolean;
  /** Clock for download jobs and monitors; overridden in tests. */
  now?: () => number;
}

/** Long-lived services, exposed for tests and diagnostics. */
export interface RevServices {
  bus: EventBus;
  monitor: DownloadMonitor;
  health: HealthMonitor;
}

declare module 'fastify' {
  interface FastifyInstance {
    rev: RevServices;
  }
}

export async function buildApp({
  config,
  db,
  authService = new AuthService(db),
  fetch: fetchImpl = fetch,
  logger = true,
  backgroundTasks = true,
  now = Date.now,
  networkInterfaces: interfaces = () => networkInterfaces(),
}: AppDeps): Promise<FastifyInstance> {
  const app = Fastify({
    logger: logger
      ? {
          level: config.logLevel,
          redact: {
            paths: ['req.headers.authorization', 'req.headers.cookie', 'req.headers["x-api-key"]'],
            censor: '[redacted]',
          },
        }
      : false,
    bodyLimit: 1024 * 1024,
  });

  registerErrorHandling(app);
  await app.register(websocket, { options: { maxPayload: 4096 } });
  // Opt-in per route via `config.rateLimit`.
  await app.register(rateLimit, {
    global: false,
    errorResponseBuilder: () =>
      new HttpError(429, 'RATE_LIMITED', 'Too many requests, please try again shortly'),
  });

  const { url, username, password } = config.navidrome;
  const library =
    url && username && password
      ? new NavidromeLibrary(new SubsonicClient({ url, username, password }, fetchImpl))
      : null;
  if (!library) app.log.warn('Navidrome is not configured; library endpoints will return 503');

  const lidarrConfig = config.lidarr;
  const lidarrClient =
    lidarrConfig.url && lidarrConfig.apiKey
      ? new LidarrClient({ url: lidarrConfig.url, apiKey: lidarrConfig.apiKey }, fetchImpl)
      : null;
  const catalog = lidarrClient ? new LidarrCatalog(lidarrClient) : null;
  if (!catalog) app.log.warn('Lidarr is not configured; album requests are unavailable');

  const manager =
    lidarrClient && catalog
      ? new LidarrManager(lidarrClient, catalog, {
          ...(lidarrConfig.rootFolder ? { rootFolder: lidarrConfig.rootFolder } : {}),
          ...(lidarrConfig.qualityProfileId
            ? { qualityProfileId: lidarrConfig.qualityProfileId }
            : {}),
          ...(lidarrConfig.metadataProfileId
            ? { metadataProfileId: lidarrConfig.metadataProfileId }
            : {}),
          searchOnRequest: lidarrConfig.searchOnRequest ?? true,
        })
      : null;
  const bus = new EventBus();
  const jobs = new DownloadJobStore(db, now, (job, previous) =>
    bus.publishJobChange(job, previous),
  );
  const acquisition = new AcquisitionService({ jobs, catalog, manager, library, log: app.log });

  const search = new SearchService(library, (source, err) =>
    app.log.warn({ err, source }, 'search source failed'),
  );

  const { url: slskdUrl, apiKey: slskdKey } = config.slskd;
  const slskd =
    slskdUrl && slskdKey ? new SlskdClient({ url: slskdUrl, apiKey: slskdKey }, fetchImpl) : null;
  if (!slskd) app.log.warn('slskd is not configured; download progress will be coarser');
  const lidarrObserver = lidarrClient ? new LidarrObserver(lidarrClient) : null;

  const monitor = new DownloadMonitor({
    jobs,
    lidarr: lidarrObserver,
    slskd,
    library,
    timeouts: {
      overallMs: config.downloads.timeoutMs,
      indexingMs: config.downloads.indexingTimeoutMs,
    },
    intervalMs: config.downloads.pollIntervalMs,
    log: app.log,
    now,
  });
  const health = new HealthMonitor({
    library,
    lidarr: lidarrObserver,
    slskd,
    intervalMs: 60_000,
    onChange: (service, status) => bus.publish({ type: `system.${service}.status`, data: status }),
    now,
  });
  const systemStatus = new SystemStatusService(health, library, slskd, jobs, now);
  app.decorate('rev', { bus, monitor, health });
  if (backgroundTasks) {
    app.addHook('onReady', async () => {
      monitor.start();
      health.start();
    });
  }
  app.addHook('onClose', async () => {
    monitor.stop();
    health.stop();
  });

  const lyricsProviders: LyricsProvider[] = config.lyrics.providers.flatMap(
    (name): LyricsProvider[] => {
      if (name === 'navidrome') return library ? [new NavidromeLyricsProvider(library)] : [];
      return [new LrclibProvider(config.lyrics.lrclibUrl, fetchImpl)];
    },
  );
  const lyrics = new LyricsService(
    db,
    library,
    lyricsProviders,
    (trackId) => bus.publish({ type: 'lyrics.updated', data: { trackId } }),
    now,
  );

  // Explicit configuration wins; otherwise detected now, so a new DHCP lease needs no restart.
  const currentLanUrls = (): string[] =>
    config.lanUrl
      ? [config.lanUrl]
      : lanUrls(config.host ?? '127.0.0.1', config.port ?? 4600, interfaces());

  systemRoutes(app, {
    lanUrls: currentLanUrls,
    instanceId: getOrCreateInstanceId(db),
    startedAt: Date.now(),
    auth: authService,
    status: systemStatus,
  });
  eventRoutes(app, authService, bus);
  lyricsRoutes(app, authService, lyrics);
  authRoutes(app, authService);
  const listening = library ? new ListeningService(db, library, now) : null;
  const models = listening ? new ModelService(db, now) : null;
  if (models) {
    // Retrain personal models as new listens and feedback come in.
    const retrain = () => {
      try {
        const trained = models.trainWhereNeeded();
        if (trained > 0) app.log.info({ trained }, 'personal skip models retrained');
      } catch (err) {
        app.log.warn({ err }, 'personal skip model training failed');
      }
    };
    const first = setTimeout(retrain, MODEL_FIRST_TRAINING_MS);
    const every = setInterval(retrain, MODEL_TRAINING_INTERVAL_MS);
    first.unref();
    every.unref();
    app.addHook('onClose', async () => {
      clearTimeout(first);
      clearInterval(every);
    });
  }
  statsRoutes(
    app,
    authService,
    listening,
    library ? new SmartPlaylistService(db, library, new PlaylistService(library), now) : null,
    models,
  );
  libraryRoutes(
    app,
    authService,
    library,
    new CastTokens(db, now),
    // A Cast device on the LAN fetches media directly; follow the server's address unless pinned.
    () => config.castBaseUrl ?? currentLanUrls()[0],
    library
      ? new TopTracksService(library, { now, cacheMs: 10 * 60_000, externalTimeoutMs: 3_000 })
      : null,
    listening,
  );
  const autoplay = library ? new AutoplayService(library) : null;
  homeRoutes(
    app,
    authService,
    library
      ? new HomeService(library, {
          cacheMs: 15 * 60_000,
          now,
          random: Math.random,
          onSectionError: (section, err) => app.log.warn({ err, section }, 'home section failed'),
        })
      : null,
    autoplay,
    listening && library && autoplay
      ? new TimeMixService({
          times: (userId, tz) => listening.timesOfDay(userId, tz),
          track: (id) => library.track(id),
          radio: (req) => autoplay.radio(req),
          now,
          random: Math.random,
        })
      : null,
  );
  searchRoutes(app, authService, search, fetchImpl);
  downloadRoutes(app, authService, acquisition);

  return app;
}
