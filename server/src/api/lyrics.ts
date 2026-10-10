import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { LibraryId, LyricsResponse } from '@rev/schemas';
import { requireAuth } from '../auth/middleware.js';
import type { AuthService } from '../auth/service.js';
import type { LyricsService } from '../lyrics/service.js';
import { parseInput } from './validation.js';

const Params = z.object({ trackId: LibraryId });

export function lyricsRoutes(
  app: FastifyInstance,
  auth: AuthService,
  service: LyricsService,
): void {
  app.get('/api/lyrics/:trackId', { preHandler: requireAuth(auth) }, async (req) =>
    LyricsResponse.parse(await service.get(parseInput(Params, req.params).trackId)),
  );
}
