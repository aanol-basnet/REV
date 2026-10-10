import type { FastifyInstance } from 'fastify';
import { LoginRequest, RefreshRequest, SessionResponse, TokenResponse } from '@rev/schemas';
import { authOf, requireAuth } from '../auth/middleware.js';
import type { AuthService } from '../auth/service.js';
import { HttpError } from './errors.js';
import { parseInput } from './validation.js';

export function authRoutes(app: FastifyInstance, auth: AuthService): void {
  app.post(
    '/api/auth/login',
    { config: { rateLimit: { max: 10, timeWindow: '1 minute' } } },
    async (req) => {
      const body = parseInput(LoginRequest, req.body);
      const tokens = await auth.login(body.username, body.password, body.deviceName ?? null);
      if (!tokens) {
        req.log.warn({ username: body.username }, 'failed login');
        throw new HttpError(401, 'INVALID_CREDENTIALS', 'Incorrect username or password');
      }
      req.log.info({ userId: tokens.user.id }, 'login');
      return TokenResponse.parse(tokens);
    },
  );

  app.post(
    '/api/auth/refresh',
    { config: { rateLimit: { max: 30, timeWindow: '1 minute' } } },
    async (req) => {
      const body = parseInput(RefreshRequest, req.body);
      const result = auth.refresh(body.refreshToken);
      if (!result.ok) {
        if (result.reason === 'reused') {
          req.log.warn('superseded refresh token presented; session revoked');
        }
        throw new HttpError(401, 'INVALID_REFRESH_TOKEN', 'Session expired, please sign in again');
      }
      return TokenResponse.parse(result.tokens);
    },
  );

  app.post('/api/auth/logout', { preHandler: requireAuth(auth) }, async (req, reply) => {
    auth.logout(authOf(req).sessionId);
    return reply.code(204).send();
  });

  app.get('/api/auth/session', { preHandler: requireAuth(auth) }, async (req) => {
    const ctx = authOf(req);
    return SessionResponse.parse({
      user: { id: ctx.userId, username: ctx.username },
      sessionId: ctx.sessionId,
      deviceName: ctx.deviceName,
      createdAt: ctx.sessionCreatedAt,
    });
  });
}
