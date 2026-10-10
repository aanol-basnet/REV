import type { FastifyError, FastifyInstance } from 'fastify';
import type { ApiError } from '@rev/schemas';
import { UpstreamError } from '../adapters/errors.js';

/** An error whose message is safe to return to clients. */
export class HttpError extends Error {
  constructor(
    readonly statusCode: number,
    readonly code: string,
    message: string,
    readonly headers: Record<string, string> = {},
  ) {
    super(message);
  }
}

function body(code: string, message: string): ApiError {
  return { error: { code, message } };
}

/** Uniform, non-leaky error responses for every route. */
export function registerErrorHandling(app: FastifyInstance): void {
  app.setNotFoundHandler((_req, reply) => {
    void reply.code(404).send(body('NOT_FOUND', 'Route not found'));
  });

  app.setErrorHandler((err: FastifyError, req, reply) => {
    if (err instanceof HttpError) {
      return reply.code(err.statusCode).headers(err.headers).send(body(err.code, err.message));
    }
    if (err instanceof UpstreamError) {
      const mapped = fromUpstream(err);
      // Not-found is routine; everything else means a backend service needs attention.
      if (err.kind === 'not_found') req.log.debug({ err }, 'upstream not found');
      else req.log.error({ err, service: err.service, kind: err.kind }, 'upstream failure');
      return reply.code(mapped.statusCode).send(body(mapped.code, mapped.message));
    }
    // Fastify's own client errors (malformed JSON, payload too large, ...).
    if (err.statusCode !== undefined && err.statusCode >= 400 && err.statusCode < 500) {
      return reply.code(err.statusCode).send(body(err.code ?? 'BAD_REQUEST', err.message));
    }
    req.log.error({ err }, 'unhandled error');
    return reply.code(500).send(body('INTERNAL', 'Internal server error'));
  });
}

const SERVICE_LABEL: Record<UpstreamError['service'], string> = {
  navidrome: 'Music server',
  lidarr: 'Library manager',
  slskd: 'Download service',
  coverart: 'Cover art service',
  lrclib: 'Lyrics service',
};

function fromUpstream(err: UpstreamError): HttpError {
  const label = SERVICE_LABEL[err.service];
  const prefix = err.service.toUpperCase();
  switch (err.kind) {
    case 'not_found':
      return new HttpError(404, 'NOT_FOUND', 'Not found');
    case 'unavailable':
      return new HttpError(503, `${prefix}_UNAVAILABLE`, `${label} unavailable`);
    case 'auth':
      return new HttpError(502, `${prefix}_AUTH_FAILED`, `${label} rejected REV's credentials`);
    case 'protocol':
      return new HttpError(502, `${prefix}_ERROR`, `${label} returned an unexpected response`);
  }
}
