import { eq } from 'drizzle-orm';
import type { Db } from '../db/client.js';
import { sessions, users } from '../db/schema.js';
import { hashPassword, MIN_PASSWORD_LENGTH } from './passwords.js';

export type User = typeof users.$inferSelect;

const USERNAME_PATTERN = /^[a-z0-9][a-z0-9._-]{0,63}$/;

export class UserError extends Error {
  override name = 'UserError';
}

export function normalizeUsername(username: string): string {
  return username.trim().toLowerCase();
}

function assertValidUsername(username: string): void {
  if (!USERNAME_PATTERN.test(username)) {
    throw new UserError(
      'Username must be 1-64 characters: letters, digits, ".", "_" or "-", starting with a letter or digit',
    );
  }
}

function assertValidPassword(password: string): void {
  if (password.length < MIN_PASSWORD_LENGTH) {
    throw new UserError(`Password must be at least ${MIN_PASSWORD_LENGTH} characters`);
  }
}

export function findUserByUsername(db: Db, username: string): User | undefined {
  return db
    .select()
    .from(users)
    .where(eq(users.username, normalizeUsername(username)))
    .get();
}

export function listUsers(db: Db): Pick<User, 'id' | 'username' | 'createdAt'>[] {
  return db
    .select({ id: users.id, username: users.username, createdAt: users.createdAt })
    .from(users)
    .all();
}

export async function createUser(
  db: Db,
  username: string,
  password: string,
  now = Date.now(),
): Promise<User> {
  const name = normalizeUsername(username);
  assertValidUsername(name);
  assertValidPassword(password);
  if (findUserByUsername(db, name)) throw new UserError(`User "${name}" already exists`);

  const passwordHash = await hashPassword(password);
  return db
    .insert(users)
    .values({ username: name, passwordHash, createdAt: now, updatedAt: now })
    .returning()
    .get();
}

/** Changes a password and signs the user out everywhere. */
export async function setPassword(
  db: Db,
  username: string,
  password: string,
  now = Date.now(),
): Promise<void> {
  const user = findUserByUsername(db, username);
  if (!user) throw new UserError(`User "${normalizeUsername(username)}" does not exist`);
  assertValidPassword(password);

  const passwordHash = await hashPassword(password);
  db.transaction((tx) => {
    tx.update(users).set({ passwordHash, updatedAt: now }).where(eq(users.id, user.id)).run();
    tx.delete(sessions).where(eq(sessions.userId, user.id)).run();
  });
}

/** Deletes a user; their sessions go with them (ON DELETE CASCADE). */
export function deleteUser(db: Db, username: string): void {
  const result = db
    .delete(users)
    .where(eq(users.username, normalizeUsername(username)))
    .run();
  if (result.changes === 0) {
    throw new UserError(`User "${normalizeUsername(username)}" does not exist`);
  }
}
