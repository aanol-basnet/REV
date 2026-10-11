import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import {
  FeedbackBatch,
  FeedbackBatchResult,
  ListenBatch,
  ListenBatchResult,
  ListeningReport,
  ReportQuery,
  SaveSmartPlaylistResult,
  SmartPlaylistDetail,
  SmartPlaylistId,
  SmartPlaylistQuery,
  SkipModelResponse,
  SmartPlaylists,
  TasteProfile,
  TasteQuery,
  InteractionBatch,
  InteractionBatchResult,
} from '@rev/schemas';
import { authOf, requireAuth } from '../auth/middleware.js';
import type { AuthService } from '../auth/service.js';
import type { ModelService } from '../model/service.js';
import type { ListeningService } from '../stats/service.js';
import type { SmartPlaylistService } from '../stats/smart-playlists.js';
import { HttpError } from './errors.js';
import { parseInput } from './validation.js';

export function statsRoutes(
  app: FastifyInstance,
  auth: AuthService,
  listening: ListeningService | null,
  smart: SmartPlaylistService | null,
  models: ModelService | null = null,
): void {
  const preHandler = requireAuth(auth);
  const service = (): ListeningService => {
    if (!listening) {
      throw new HttpError(503, 'NAVIDROME_NOT_CONFIGURED', 'Music server is not configured');
    }
    return listening;
  };
  const smartPlaylists = (): SmartPlaylistService => {
    if (!smart) {
      throw new HttpError(503, 'NAVIDROME_NOT_CONFIGURED', 'Music server is not configured');
    }
    return smart;
  };
  const SmartParams = z.object({ id: SmartPlaylistId });
  const model = (): ModelService => {
    if (!models)
      throw new HttpError(503, 'NAVIDROME_NOT_CONFIGURED', 'Music server is not configured');
    return models;
  };

  // What the app heard: completed plays and skips, possibly queued while offline.
  app.post('/api/stats/listens', { preHandler }, async (req) => {
    const { listens } = parseInput(ListenBatch, req.body);
    return ListenBatchResult.parse(await service().record(authOf(req).userId, listens));
  });

  app.get('/api/stats/report', { preHandler }, async (req) => {
    const query = parseInput(ReportQuery, req.query);
    return ListeningReport.parse(service().report(authOf(req).userId, query));
  });

  // Other things done with songs: added to a playlist, queued, favorited...
  app.post('/api/stats/interactions', { preHandler }, async (req) => {
    const { events } = parseInput(InteractionBatch, req.body);
    return InteractionBatchResult.parse(
      await service().recordInteractions(authOf(req).userId, events),
    );
  });

  // Feedback buttons in the player, about one listen.
  app.post('/api/stats/feedback', { preHandler }, async (req) => {
    const { events } = parseInput(FeedbackBatch, req.body);
    return FeedbackBatchResult.parse(model().recordFeedback(authOf(req).userId, events));
  });

  // The personal skip model: the latest one, or trained now.
  app.get('/api/stats/model', { preHandler }, async (req) => {
    return SkipModelResponse.parse({ model: model().latest(authOf(req).userId) });
  });

  app.post('/api/stats/model/train', { preHandler }, async (req) => {
    return SkipModelResponse.parse({ model: model().train(authOf(req).userId) });
  });

  // What the app's smart queue learns from: scores per artist, genre, decade, song and time of day.
  app.get('/api/stats/taste', { preHandler }, async (req) => {
    const query = parseInput(TasteQuery, req.query);
    return TasteProfile.parse(service().taste(authOf(req).userId, query));
  });

  // Smart playlists, made from the log; saving writes (or updates) a Navidrome playlist.
  app.get('/api/stats/playlists', { preHandler }, async (req) => {
    const { tz } = parseInput(SmartPlaylistQuery, req.query);
    return SmartPlaylists.parse({ playlists: smartPlaylists().list(authOf(req).userId, tz) });
  });

  app.get('/api/stats/playlists/:id', { preHandler }, async (req) => {
    const { id } = parseInput(SmartParams, req.params);
    const { tz } = parseInput(SmartPlaylistQuery, req.query);
    return SmartPlaylistDetail.parse(await smartPlaylists().detail(authOf(req).userId, id, tz));
  });

  app.post('/api/stats/playlists/:id/save', { preHandler }, async (req) => {
    const { id } = parseInput(SmartParams, req.params);
    const { tz } = parseInput(SmartPlaylistQuery, req.query);
    return SaveSmartPlaylistResult.parse(await smartPlaylists().save(authOf(req).userId, id, tz));
  });
}
