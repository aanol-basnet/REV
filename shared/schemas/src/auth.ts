import { z } from 'zod';

export const LoginRequest = z.object({
  username: z.string().trim().min(1).max(64),
  password: z.string().min(1).max(1024),
  /** Human-readable label for the session, e.g. "Pixel 9". */
  deviceName: z.string().trim().min(1).max(100).optional(),
});
export type LoginRequest = z.infer<typeof LoginRequest>;

export const RefreshRequest = z.object({
  refreshToken: z.string().min(1).max(256),
});
export type RefreshRequest = z.infer<typeof RefreshRequest>;

export const AuthUser = z.object({
  id: z.number().int(),
  username: z.string(),
});
export type AuthUser = z.infer<typeof AuthUser>;

/** Returned by login and refresh. Timestamps are epoch milliseconds. */
export const TokenResponse = z.object({
  accessToken: z.string(),
  accessTokenExpiresAt: z.number().int(),
  refreshToken: z.string(),
  refreshTokenExpiresAt: z.number().int(),
  user: AuthUser,
});
export type TokenResponse = z.infer<typeof TokenResponse>;

export const SessionResponse = z.object({
  user: AuthUser,
  sessionId: z.string(),
  deviceName: z.string().nullable(),
  createdAt: z.number().int(),
});
export type SessionResponse = z.infer<typeof SessionResponse>;
