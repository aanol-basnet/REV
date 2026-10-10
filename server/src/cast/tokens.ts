import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { eq } from 'drizzle-orm';
import type { Db } from '../db/client.js';
import { serverMeta } from '../db/schema.js';

const KEY_NAME = 'media_signing_key';
/** Long enough for an evening of casting; short enough that a leaked URL soon stops working. */
export const CAST_TOKEN_TTL_SECONDS = 12 * 3600;

/**
 * Media-only access tokens for Cast receivers. A token is an HMAC of its expiry with a per-install
 * secret; it unlocks audio streams and artwork until it expires, and nothing else.
 */
export class CastTokens {
  private readonly key: Buffer;

  constructor(
    db: Db,
    private readonly now: () => number = Date.now,
  ) {
    this.key = loadOrCreateKey(db);
  }

  issue(): { token: string; expiresAt: number } {
    const expiresAt = Math.floor(this.now() / 1000) + CAST_TOKEN_TTL_SECONDS;
    return { token: this.sign(expiresAt), expiresAt };
  }

  verify(token: unknown, exp: unknown): boolean {
    if (typeof token !== 'string' || typeof exp !== 'string' || !/^\d{1,12}$/.test(exp))
      return false;
    const expiresAt = Number(exp);
    if (expiresAt * 1000 < this.now()) return false;
    const expected = Buffer.from(this.sign(expiresAt));
    const given = Buffer.from(token);
    return given.length === expected.length && timingSafeEqual(given, expected);
  }

  private sign(expiresAt: number): string {
    return createHmac('sha256', this.key).update(`cast:${expiresAt}`).digest('base64url');
  }
}

function loadOrCreateKey(db: Db): Buffer {
  const existing = db.select().from(serverMeta).where(eq(serverMeta.key, KEY_NAME)).get();
  if (existing) return Buffer.from(existing.value, 'hex');
  const key = randomBytes(32);
  db.insert(serverMeta)
    .values({ key: KEY_NAME, value: key.toString('hex') })
    .onConflictDoNothing()
    .run();
  const stored = db.select().from(serverMeta).where(eq(serverMeta.key, KEY_NAME)).get();
  return Buffer.from(stored?.value ?? key.toString('hex'), 'hex');
}
