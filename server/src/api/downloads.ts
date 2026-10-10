import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import {
  CatalogAlbumStatus,
  CreateDownloadRequest,
  DownloadJob,
  DownloadJobList,
  DownloadListQuery,
  MusicBrainzId,
} from '@rev/schemas';
import { authOf, requireAuth } from '../auth/middleware.js';
import type { AuthService } from '../auth/service.js';
import type { AcquisitionService } from '../downloads/service.js';
import { parseInput } from './validation.js';

const JobParams = z.object({ id: z.string().uuid() });
const CatalogParams = z.object({ mbid: MusicBrainzId });

export function downloadRoutes(
  app: FastifyInstance,
  auth: AuthService,
  acquisition: AcquisitionService,
): void {
  const preHandler = requireAuth(auth);

  app.get('/api/downloads', { preHandler }, async (req) => {
    const { active } = parseInput(DownloadListQuery, req.query);
    return DownloadJobList.parse({ jobs: acquisition.list(active ?? false) });
  });

  // Clears finished jobs from the history; anything in progress stays.
  app.delete('/api/downloads', { preHandler }, async () => ({
    cleared: acquisition.clearFinished(),
  }));

  app.post(
    '/api/downloads',
    { preHandler, config: { rateLimit: { max: 30, timeWindow: '1 minute' } } },
    async (req, reply) => {
      const { catalogAlbumId } = parseInput(CreateDownloadRequest, req.body);
      const job = await acquisition.request(catalogAlbumId, authOf(req).userId);
      return reply.code(201).send(DownloadJob.parse(job));
    },
  );

  app.get('/api/downloads/:id', { preHandler }, async (req) =>
    DownloadJob.parse(acquisition.get(parseInput(JobParams, req.params).id)),
  );

  app.delete('/api/downloads/:id', { preHandler }, async (req) =>
    DownloadJob.parse(await acquisition.cancel(parseInput(JobParams, req.params).id)),
  );

  app.get('/api/catalog/albums/:mbid', { preHandler }, async (req) =>
    CatalogAlbumStatus.parse(
      await acquisition.albumStatus(parseInput(CatalogParams, req.params).mbid),
    ),
  );
}
