import type { FastifyReply, FastifyRequest, preHandlerAsyncHookHandler } from 'fastify';
import { HttpError } from '../api/errors.js';
import type { AuthContext, AuthService } from './service.js';

declare module 'fastify' {
  interface FastifyRequest {
    auth?: AuthContext;
  }
}

function unauthorized(): HttpError {
  // The WWW-Authenticate header tells HTTP clients (e.g. Ktor's bearer auth) to refresh and retry.
  return new HttpError(401, 'UNAUTHORIZED', 'Authentication required', {
    'www-authenticate': 'Bearer',
  });
}

/** preHandler that requires a valid access token and attaches the session to the request. */
export function requireAuth(auth: AuthService): preHandlerAsyncHookHandler {
  return async (req: FastifyRequest, _reply: FastifyReply) => {
    const header = req.headers.authorization;
    const match = header ? /^Bearer (\S+)$/i.exec(header) : null;
    const token = match?.[1];
    if (!token) throw unauthorized();
    const ctx = auth.authenticate(token);
    if (!ctx) throw unauthorized();
    req.auth = ctx;
  };
}

/** Returns the session for a route guarded by requireAuth. */
export function authOf(req: FastifyRequest): AuthContext {
  if (!req.auth) throw new Error('authOf() used on a route without requireAuth');
  return req.auth;
}

/**
 * For media routes only: a valid session token, or a valid Cast token in the query string
 * (`?exp=…&cast=…`) for receivers that can't send headers.
 */
export function requireAuthOrCastToken(
  auth: AuthService,
  castTokens: { verify(token: unknown, exp: unknown): boolean },
): preHandlerAsyncHookHandler {
  const session = requireAuth(auth);
  return async (req: FastifyRequest, reply: FastifyReply) => {
    const query = req.query as Record<string, unknown> | undefined;
    if (query && query.cast !== undefined && castTokens.verify(query.cast, query.exp)) return;
    await session.call(req.server, req, reply);
  };
}
