import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import {
  ArtistContinuationRequest,
  HomeFeed,
  HomeQuery,
  MixId,
  MixQuery,
  MixTracks,
  NextAlbum,
  NextAlbumRequest,
  RadioRequest,
  RadioTracks,
  TimeOfDay,
} from '@rev/schemas';
import { authOf, requireAuth } from '../auth/middleware.js';
import type { AuthService } from '../auth/service.js';
import type { AutoplayService } from '../home/autoplay.js';
import type { HomeService } from '../home/service.js';
import type { TimeMixService } from '../home/time-mix.js';
import { HttpError } from './errors.js';
import { parseInput } from './validation.js';

const MixParams = z.object({ id: MixId });

export function homeRoutes(
  app: FastifyInstance,
  auth: AuthService,
  home: HomeService | null,
  autoplay: AutoplayService | null,
  timeMix: TimeMixService | null = null,
): void {
  const preHandler = requireAuth(auth);
  const configured = <T>(service: T | null): T => {
    if (!service)
      throw new HttpError(503, 'NAVIDROME_NOT_CONFIGURED', 'Music server is not configured');
    return service;
  };
  const service = (): HomeService => configured(home);

  app.get('/api/home', { preHandler }, async (req) => {
    const { refresh, tz } = parseInput(HomeQuery, req.query);
    const feed = await service().feed(refresh === 'true');
    // The mix for this time of day goes first; it's yours and changes with the clock, so it isn't
    // part of the shared, cached feed.
    const time = tz && timeMix ? timeMix.current(authOf(req).userId, tz) : null;
    return HomeFeed.parse(time ? { ...feed, mixes: [time, ...feed.mixes] } : feed);
  });

  app.get('/api/mixes/:id', { preHandler }, async (req) => {
    const { id } = parseInput(MixParams, req.params);
    const time = TimeOfDay.safeParse(id.startsWith('time:') ? id.slice('time:'.length) : null);
    if (time.success) {
      const { tz } = parseInput(MixQuery, req.query);
      const tracks = await configured(timeMix).tracks(authOf(req).userId, time.data, tz ?? 'UTC');
      return MixTracks.parse({ id, tracks });
    }
    return MixTracks.parse({ id, tracks: await service().mix(id) });
  });

  // Autoplay: what to play when the queue runs out.
  app.post('/api/autoplay/next-album', { preHandler }, async (req) => {
    const body = parseInput(NextAlbumRequest, req.body);
    return NextAlbum.parse(await configured(autoplay).nextAlbum(body));
  });

  app.post('/api/autoplay/artist', { preHandler }, async (req) => {
    const body = parseInput(ArtistContinuationRequest, req.body);
    return RadioTracks.parse({ tracks: await configured(autoplay).artistSongs(body) });
  });

  app.post('/api/autoplay/radio', { preHandler }, async (req) => {
    const body = parseInput(RadioRequest, req.body);
    return RadioTracks.parse({ tracks: await configured(autoplay).radio(body) });
  });
}
