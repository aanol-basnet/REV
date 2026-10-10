import { createHash, randomBytes } from 'node:crypto';

// Prefixes make tokens recognisable in logs and to secret scanners.
export const ACCESS_TOKEN_PREFIX = 'rev_at_';
export const REFRESH_TOKEN_PREFIX = 'rev_rt_';

export function generateToken(prefix: string): string {
  return prefix + randomBytes(32).toString('base64url');
}

/** Tokens are high-entropy random values, so a fast unsalted hash is appropriate. */
export function hashToken(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}
