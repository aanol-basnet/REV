import { randomUUID } from 'node:crypto';
import { eq, lt } from 'drizzle-orm';
import type { TokenResponse } from '@rev/schemas';
import type { Db } from '../db/client.js';
import { sessions, users } from '../db/schema.js';
import { burnPasswordVerification, verifyPassword } from './passwords.js';
import { ACCESS_TOKEN_PREFIX, generateToken, hashToken, REFRESH_TOKEN_PREFIX } from './tokens.js';
import { findUserByUsername } from './users.js';

export interface AuthOptions {
  accessTokenTtlMs: number;
  refreshTokenTtlMs: number;
  /**
   * How long after a rotation the previous refresh token is still accepted. Covers a client that
   * refreshed but never received the response (common on flaky mobile links). Outside this window,
   * presenting a superseded refresh token is treated as theft and revokes the session.
   */
  refreshReuseGraceMs: number;
}

export const DEFAULT_AUTH_OPTIONS: AuthOptions = {
  accessTokenTtlMs: 15 * 60 * 1000,
  refreshTokenTtlMs: 30 * 24 * 60 * 60 * 1000,
  refreshReuseGraceMs: 60 * 1000,
};

export interface AuthContext {
  sessionId: string;
  userId: number;
  username: string;
  deviceName: string | null;
  sessionCreatedAt: number;
}

export type RefreshResult =
  { ok: true; tokens: TokenResponse } | { ok: false; reason: 'invalid' | 'expired' | 'reused' };

type Session = typeof sessions.$inferSelect;

/** Only touch last_used_at at most this often, to avoid a write per request. */
const LAST_USED_RESOLUTION_MS = 60 * 1000;

export class AuthService {
  constructor(
    private readonly db: Db,
    private readonly options: AuthOptions = DEFAULT_AUTH_OPTIONS,
    private readonly now: () => number = Date.now,
  ) {}

  /** Returns tokens for valid credentials, or null. Never reveals which part was wrong. */
  async login(
    username: string,
    password: string,
    deviceName: string | null,
  ): Promise<TokenResponse | null> {
    const user = findUserByUsername(this.db, username);
    if (!user) {
      await burnPasswordVerification(password);
      return null;
    }
    if (!(await verifyPassword(user.passwordHash, password))) return null;

    const now = this.now();
    this.purgeExpired(now);
    const issued = this.issueTokens(now);
    this.db
      .insert(sessions)
      .values({
        id: randomUUID(),
        userId: user.id,
        deviceName,
        accessTokenHash: issued.accessTokenHash,
        accessTokenExpiresAt: issued.accessTokenExpiresAt,
        refreshTokenHash: issued.refreshTokenHash,
        refreshTokenExpiresAt: issued.refreshTokenExpiresAt,
        createdAt: now,
        lastUsedAt: now,
      })
      .run();
    return issued.toResponse({ id: user.id, username: user.username });
  }

  refresh(refreshToken: string): RefreshResult {
    const tokenHash = hashToken(refreshToken);
    const now = this.now();

    return this.db.transaction((tx) => {
      const current = tx
        .select()
        .from(sessions)
        .where(eq(sessions.refreshTokenHash, tokenHash))
        .get();
      if (current) {
        if (current.revokedAt !== null) return { ok: false, reason: 'invalid' } as const;
        if (current.refreshTokenExpiresAt <= now) return { ok: false, reason: 'expired' } as const;
        return { ok: true, tokens: this.rotate(tx, current, now) } as const;
      }

      const superseded = tx
        .select()
        .from(sessions)
        .where(eq(sessions.previousRefreshTokenHash, tokenHash))
        .get();
      if (!superseded || superseded.revokedAt !== null) {
        return { ok: false, reason: 'invalid' } as const;
      }
      const withinGrace =
        superseded.rotatedAt !== null &&
        now - superseded.rotatedAt <= this.options.refreshReuseGraceMs &&
        superseded.refreshTokenExpiresAt > now;
      if (withinGrace) {
        return { ok: true, tokens: this.rotate(tx, superseded, now) } as const;
      }
      tx.update(sessions).set({ revokedAt: now }).where(eq(sessions.id, superseded.id)).run();
      return { ok: false, reason: 'reused' } as const;
    });
  }

  authenticate(accessToken: string): AuthContext | null {
    const now = this.now();
    const row = this.db
      .select({ session: sessions, username: users.username })
      .from(sessions)
      .innerJoin(users, eq(users.id, sessions.userId))
      .where(eq(sessions.accessTokenHash, hashToken(accessToken)))
      .get();
    if (!row) return null;
    const { session } = row;
    if (session.revokedAt !== null || session.accessTokenExpiresAt <= now) return null;

    if (now - session.lastUsedAt >= LAST_USED_RESOLUTION_MS) {
      this.db.update(sessions).set({ lastUsedAt: now }).where(eq(sessions.id, session.id)).run();
    }
    return {
      sessionId: session.id,
      userId: session.userId,
      username: row.username,
      deviceName: session.deviceName,
      sessionCreatedAt: session.createdAt,
    };
  }

  /** Whether a session can still be used (not signed out, revoked or expired). */
  isSessionActive(sessionId: string): boolean {
    const session = this.db.select().from(sessions).where(eq(sessions.id, sessionId)).get();
    return !!session && session.revokedAt === null && session.refreshTokenExpiresAt > this.now();
  }

  logout(sessionId: string): void {
    this.db.delete(sessions).where(eq(sessions.id, sessionId)).run();
  }

  /** Deletes sessions that can no longer be used. Revoked rows are kept until expiry for reuse detection. */
  purgeExpired(now = this.now()): void {
    this.db.delete(sessions).where(lt(sessions.refreshTokenExpiresAt, now)).run();
  }

  private rotate(tx: Pick<Db, 'update' | 'select'>, session: Session, now: number): TokenResponse {
    const issued = this.issueTokens(now);
    tx.update(sessions)
      .set({
        accessTokenHash: issued.accessTokenHash,
        accessTokenExpiresAt: issued.accessTokenExpiresAt,
        refreshTokenHash: issued.refreshTokenHash,
        refreshTokenExpiresAt: issued.refreshTokenExpiresAt,
        previousRefreshTokenHash: session.refreshTokenHash,
        rotatedAt: now,
        lastUsedAt: now,
      })
      .where(eq(sessions.id, session.id))
      .run();
    const user = tx
      .select({ id: users.id, username: users.username })
      .from(users)
      .where(eq(users.id, session.userId))
      .get();
    if (!user) throw new Error('session references a missing user');
    return issued.toResponse(user);
  }

  private issueTokens(now: number) {
    const accessToken = generateToken(ACCESS_TOKEN_PREFIX);
    const refreshToken = generateToken(REFRESH_TOKEN_PREFIX);
    const accessTokenExpiresAt = now + this.options.accessTokenTtlMs;
    const refreshTokenExpiresAt = now + this.options.refreshTokenTtlMs;
    return {
      accessTokenHash: hashToken(accessToken),
      accessTokenExpiresAt,
      refreshTokenHash: hashToken(refreshToken),
      refreshTokenExpiresAt,
      toResponse: (user: { id: number; username: string }): TokenResponse => ({
        accessToken,
        accessTokenExpiresAt,
        refreshToken,
        refreshTokenExpiresAt,
        user,
      }),
    };
  }
}
