import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { MusicBrainzId, SearchQuery, SearchResponse } from '@rev/schemas';
import { requireAuth } from '../auth/middleware.js';
import type { AuthService } from '../auth/service.js';
import type { SearchService } from '../search/service.js';
import { UpstreamError } from '../adapters/errors.js';
import { parseInput } from './validation.js';

const CoverParams = z.object({ mbid: MusicBrainzId });
const COVER_ART_ARCHIVE = 'https://coverartarchive.org';

export function searchRoutes(
  app: FastifyInstance,
  auth: AuthService,
  search: SearchService,
  fetchImpl: typeof fetch,
): void {
  const preHandler = requireAuth(auth);

  app.get(
    '/api/search',
    { preHandler, config: { rateLimit: { max: 60, timeWindow: '1 minute' } } },
    async (req) => {
      const { q } = parseInput(SearchQuery, req.query);
      return SearchResponse.parse(await search.search(q));
    },
  );

  // Covers for catalog albums the user doesn't own yet, from the Cover Art Archive. Only release
  // group ids (validated UUIDs) are accepted; this is not a general image proxy.
  app.get('/api/catalog/albums/:mbid/cover', { preHandler }, async (req, reply) => {
    const { mbid } = parseInput(CoverParams, req.params);
    let response: Response;
    try {
      response = await fetchImpl(`${COVER_ART_ARCHIVE}/release-group/${mbid}/front-250`, {
        signal: AbortSignal.timeout(15_000),
        redirect: 'follow',
      });
    } catch (cause) {
      throw new UpstreamError('coverart', 'unavailable', 'cover art archive unreachable', {
        cause,
      });
    }
    const type = response.headers.get('content-type') ?? '';
    if (response.status === 404) throw new UpstreamError('coverart', 'not_found', 'no cover');
    if (!response.ok || !type.startsWith('image/') || !response.body) {
      throw new UpstreamError('coverart', 'protocol', `cover art archive: HTTP ${response.status}`);
    }
    reply
      .header('content-type', type)
      .header('cache-control', 'private, max-age=2592000, immutable');
    return reply.send(Buffer.from(await response.arrayBuffer()));
  });
}
