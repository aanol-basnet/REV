import type { FastifyInstance } from 'fastify';
import { HealthResponse, ServerAddresses, SystemStatus } from '@rev/schemas';
import { requireAuth } from '../auth/middleware.js';
import type { AuthService } from '../auth/service.js';
import type { SystemStatusService } from '../system/status.js';
import { REV_VERSION } from '../version.js';

export interface SystemRouteDeps {
  instanceId: string;
  startedAt: number;
  auth: AuthService;
  status: SystemStatusService;
  /** The server's current LAN addresses. */
  lanUrls: () => string[];
}

export function systemRoutes(app: FastifyInstance, deps: SystemRouteDeps): void {
  app.get('/api/system/health', () =>
    HealthResponse.parse({
      status: 'ok',
      name: 'REV',
      version: REV_VERSION,
      instanceId: deps.instanceId,
      uptimeSeconds: Math.floor((Date.now() - deps.startedAt) / 1000),
    }),
  );

  app.get('/api/system/status', { preHandler: requireAuth(deps.auth) }, async () =>
    SystemStatus.parse(await deps.status.status()),
  );

  // Lets the app find this server on the home network after its address changes. Signed-in only:
  // the private address is nobody else's business.
  app.get('/api/system/addresses', { preHandler: requireAuth(deps.auth) }, () =>
    ServerAddresses.parse({ lanUrls: deps.lanUrls() }),
  );
}
