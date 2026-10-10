import { z } from 'zod';

/** Unauthenticated liveness probe used by clients to verify they are talking to a REV server. */
export const HealthResponse = z.object({
  status: z.literal('ok'),
  name: z.literal('REV'),
  version: z.string(),
  /** Stable per-installation id; lets clients detect that a configured URL now points at a different server. */
  instanceId: z.string().uuid(),
  uptimeSeconds: z.number().int().nonnegative(),
});
export type HealthResponse = z.infer<typeof HealthResponse>;

/** Where this server can be reached directly on the local network (authenticated). */
export const ServerAddresses = z.object({ lanUrls: z.array(z.string()) });
export type ServerAddresses = z.infer<typeof ServerAddresses>;
