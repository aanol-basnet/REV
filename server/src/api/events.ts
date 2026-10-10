import type { FastifyInstance } from 'fastify';
import type { ServerEvent } from '@rev/schemas';
import { authOf, requireAuth } from '../auth/middleware.js';
import type { AuthService } from '../auth/service.js';
import type { EventBus } from '../events/bus.js';

const PING_INTERVAL_MS = 30_000;

/**
 * Live events over WebSocket. Authenticated with the normal bearer token on the upgrade request;
 * the connection closes if the session is signed out or revoked. Clients should fetch current
 * state over REST after (re)connecting, then apply events.
 */
export function eventRoutes(app: FastifyInstance, auth: AuthService, bus: EventBus): void {
  app.get('/api/events', { websocket: true, preValidation: requireAuth(auth) }, (socket, req) => {
    const { sessionId } = authOf(req);
    const send = (event: ServerEvent) => {
      if (socket.readyState === socket.OPEN) socket.send(JSON.stringify(event));
    };
    const unsubscribe = bus.subscribe(send);

    const ping = setInterval(() => {
      if (!auth.isSessionActive(sessionId)) {
        socket.close(4001, 'Session ended');
        return;
      }
      socket.ping();
    }, PING_INTERVAL_MS);
    ping.unref();

    socket.on('close', () => {
      clearInterval(ping);
      unsubscribe();
    });
    // Clients don't send anything meaningful; ignore input.
    socket.on('message', () => {});
  });
}
