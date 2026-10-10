import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import {
  ArtistContinuationRequest,
  HomeFeed,
  HomeQuery,
  MixId,
  MixTracks,
  NextAlbum,
  NextAlbumRequest,
  RadioRequest,
  RadioTracks,
} from '@rev/schemas';
import { requireAuth } from '../auth/middleware.js';
import type { AuthService } from '../auth/service.js';
import type { AutoplayService } from '../home/autoplay.js';
import type { HomeService } from '../home/service.js';
import { HttpError } from './errors.js';
import { parseInput } from './validation.js';

const MixParams = z.object({ id: MixId });

export function homeRoutes(
  app: FastifyInstance,
  auth: AuthService,
  home: HomeService | null,
  autoplay: AutoplayService | null,
): void {
  const preHandler = requireAuth(auth);
  const configured = <T>(service: T | null): T => {
    if (!service)
      throw new HttpError(503, 'NAVIDROME_NOT_CONFIGURED', 'Music server is not configured');
    return service;
  };
  const service = (): HomeService => configured(home);

  app.get('/api/home', { preHandler }, async (req) => {
    const { refresh } = parseInput(HomeQuery, req.query);
    return HomeFeed.parse(await service().feed(refresh === 'true'));
  });

  app.get('/api/mixes/:id', { preHandler }, async (req) => {
    const { id } = parseInput(MixParams, req.params);
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
