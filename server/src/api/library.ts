import type { FastifyInstance, FastifyRequest } from 'fastify';
import { z } from 'zod';
import {
  AlbumDetail,
  CastToken,
  AlbumListQuery,
  AlbumPage,
  ArtistDetail,
  ArtistList,
  ArtworkQuery,
  FavoriteKind,
  Favorites,
  GenreList,
  PlaylistDetail,
  PlaylistList,
  LibraryId,
  ScrobbleRequest,
  AddPlaylistTracksRequest,
  AddPlaylistTracksResult,
  CreatePlaylistRequest,
  DuplicatePlaylistRequest,
  SetPlaylistTracksRequest,
  UpdatePlaylistRequest,
  StreamQuery,
  Track,
  TrackListQuery,
  TrackPage,
  TopTracks,
} from '@rev/schemas';
import type { TopTracksService } from '../library/top-tracks.js';
import type { NavidromeLibrary } from '../adapters/navidrome/library.js';
import { authOf, requireAuth, requireAuthOrCastToken } from '../auth/middleware.js';
import type { CastTokens } from '../cast/tokens.js';
import type { ListeningService } from '../stats/service.js';
import { PlaylistService } from '../library/playlists.js';
import type { AuthService } from '../auth/service.js';
import { HttpError } from './errors.js';
import { parseInput } from './validation.js';

const IdParams = z.object({ id: LibraryId });
const FavoriteParams = z.object({ kind: FavoriteKind, id: LibraryId });

/** Artwork is immutable per id (Navidrome bakes a timestamp into cover art ids). */
const ARTWORK_CACHE_CONTROL = 'private, max-age=2592000, immutable';

export function libraryRoutes(
  app: FastifyInstance,
  auth: AuthService,
  library: NavidromeLibrary | null,
  castTokens: CastTokens,
  castBaseUrl: () => string | undefined,
  topTracks: TopTracksService | null = null,
  listening: ListeningService | null = null,
): void {
  const preHandler = requireAuth(auth);
  // Audio and artwork may also be fetched by a Cast receiver with a media-only token.
  const mediaPreHandler = requireAuthOrCastToken(auth, castTokens);
  const lib = (): NavidromeLibrary => {
    if (!library) {
      throw new HttpError(503, 'NAVIDROME_NOT_CONFIGURED', 'Music server is not configured');
    }
    return library;
  };
  const id = (req: FastifyRequest) => parseInput(IdParams, req.params).id;

  app.get('/api/library/artists', { preHandler }, async () =>
    ArtistList.parse({ artists: await lib().artists() }),
  );

  app.get('/api/library/artists/:id', { preHandler }, async (req) =>
    ArtistDetail.parse(await lib().artist(id(req))),
  );

  app.get('/api/library/artists/:id/top-tracks', { preHandler }, async (req) => {
    lib();
    if (!topTracks)
      throw new HttpError(503, 'NAVIDROME_NOT_CONFIGURED', 'Music server is not configured');
    return TopTracks.parse({ tracks: await topTracks.topTracks(id(req)) });
  });

  app.get('/api/library/albums', { preHandler }, async (req) =>
    AlbumPage.parse(await lib().albums(parseInput(AlbumListQuery, req.query))),
  );

  app.get('/api/library/albums/:id', { preHandler }, async (req) =>
    AlbumDetail.parse(await lib().album(id(req))),
  );

  app.get('/api/library/tracks', { preHandler }, async (req) => {
    const { offset, size } = parseInput(TrackListQuery, req.query);
    return TrackPage.parse(await lib().tracks(offset, size));
  });

  app.get('/api/library/tracks/:id', { preHandler }, async (req) =>
    Track.parse(await lib().track(id(req))),
  );

  app.get('/api/library/genres', { preHandler }, async () =>
    GenreList.parse({ genres: await lib().genres() }),
  );

  app.get('/api/library/playlists', { preHandler }, async () =>
    PlaylistList.parse({ playlists: await lib().playlists() }),
  );

  app.get('/api/library/playlists/:id', { preHandler }, async (req) =>
    PlaylistDetail.parse(await lib().playlist(id(req))),
  );

  // Playlist editing; the playlists themselves live in Navidrome.
  const playlists = () => new PlaylistService(lib());

  app.post('/api/library/playlists', { preHandler }, async (req, reply) => {
    const body = parseInput(CreatePlaylistRequest, req.body);
    return reply.code(201).send(PlaylistDetail.parse(await playlists().create(body)));
  });

  app.post('/api/library/playlists/:id/duplicate', { preHandler }, async (req, reply) => {
    const { name } = parseInput(DuplicatePlaylistRequest, req.body ?? {});
    return reply.code(201).send(PlaylistDetail.parse(await playlists().duplicate(id(req), name)));
  });

  app.patch('/api/library/playlists/:id', { preHandler }, async (req) => {
    const body = parseInput(UpdatePlaylistRequest, req.body);
    return PlaylistDetail.parse(await playlists().update(id(req), body));
  });

  app.delete('/api/library/playlists/:id', { preHandler }, async (req, reply) => {
    await playlists().delete(id(req));
    return reply.code(204).send();
  });

  app.post('/api/library/playlists/:id/tracks', { preHandler }, async (req) => {
    const body = parseInput(AddPlaylistTracksRequest, req.body);
    return AddPlaylistTracksResult.parse(await playlists().add(id(req), body));
  });

  app.put('/api/library/playlists/:id/tracks', { preHandler }, async (req) => {
    const { trackIds } = parseInput(SetPlaylistTracksRequest, req.body);
    return PlaylistDetail.parse(await playlists().setTracks(id(req), trackIds));
  });

  app.get('/api/library/favorites', { preHandler }, async () =>
    Favorites.parse(await lib().favorites()),
  );

  app.put('/api/library/favorites/:kind/:id', { preHandler }, async (req, reply) => {
    const { kind, id } = parseInput(FavoriteParams, req.params);
    await lib().setFavorite(kind, id, true);
    return reply.code(204).send();
  });

  app.delete('/api/library/favorites/:kind/:id', { preHandler }, async (req, reply) => {
    const { kind, id } = parseInput(FavoriteParams, req.params);
    await lib().setFavorite(kind, id, false);
    return reply.code(204).send();
  });

  app.post('/api/cast/token', { preHandler }, async () => {
    const baseUrl = castBaseUrl();
    if (!baseUrl)
      throw new HttpError(503, 'CAST_NOT_CONFIGURED', 'Casting is not set up on this server');
    return CastToken.parse({ baseUrl, ...castTokens.issue() });
  });

  // Audio is relayed (not redirected) so Navidrome credentials never reach clients. Range requests
  // pass through for seeking, and the upstream download stops when the client disconnects.
  app.get('/api/library/tracks/:id/stream', { preHandler: mediaPreHandler }, async (req, reply) => {
    const { maxBitRate } = parseInput(StreamQuery, req.query);
    const upstreamAbort = new AbortController();
    reply.raw.on('close', () => upstreamAbort.abort());

    const range = req.headers.range;
    const audio = await lib().stream(id(req), {
      ...(typeof range === 'string' ? { range } : {}),
      ...(maxBitRate ? { maxBitRateKbps: maxBitRate } : {}),
      signal: upstreamAbort.signal,
    });
    audio.body.on('error', (err) => {
      if (!upstreamAbort.signal.aborted) req.log.warn({ err }, 'audio stream interrupted');
    });
    reply
      .code(audio.status)
      .headers(audio.headers)
      .header('cache-control', 'private, no-transform');
    return reply.send(audio.body);
  });

  app.post('/api/library/tracks/:id/scrobble', { preHandler }, async (req, reply) => {
    const body = parseInput(ScrobbleRequest, req.body);
    if (body.submission && listening) {
      // A completed play also goes into REV's listening log (which passes it on to Navidrome).
      await listening.record(authOf(req).userId, [
        { trackId: id(req), startedAt: body.playedAt ?? Date.now(), kind: 'play' },
      ]);
    } else {
      await lib().scrobble(id(req), body.submission, body.playedAt);
    }
    return reply.code(204).send();
  });

  app.get('/api/library/artwork/:id', { preHandler: mediaPreHandler }, async (req, reply) => {
    const { size } = parseInput(ArtworkQuery, req.query);
    const art = await lib().coverArt(id(req), size);
    reply.header('content-type', art.contentType).header('cache-control', ARTWORK_CACHE_CONTROL);
    if (art.contentLength !== null) reply.header('content-length', art.contentLength);
    return reply.send(art.body);
  });
}
