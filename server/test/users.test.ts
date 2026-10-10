import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createUser, deleteUser, setPassword, UserError } from '../src/auth/users.js';
import { createTestApp, type TestContext } from './helpers.js';

describe('user management', () => {
  let t: TestContext;

  beforeEach(async () => {
    t = await createTestApp();
  });

  afterEach(async () => {
    await t.close();
  });

  const login = (password: string) =>
    t.app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: { username: 'alice', password },
    });

  it('stores an Argon2id hash, never the password', async () => {
    const user = await createUser(t.handle.db, 'alice', 'a long password');
    expect(user.passwordHash).toMatch(/^\$argon2id\$/);
    expect(user.passwordHash).not.toContain('a long password');
  });

  it('validates usernames, passwords and duplicates', async () => {
    await expect(createUser(t.handle.db, 'bad name', 'a long password')).rejects.toThrow(UserError);
    await expect(createUser(t.handle.db, 'alice', 'short')).rejects.toThrow(UserError);
    await createUser(t.handle.db, 'alice', 'a long password');
    await expect(createUser(t.handle.db, 'ALICE', 'a long password')).rejects.toThrow(
      /already exists/,
    );
  });

  it('changing a password signs out existing sessions', async () => {
    await createUser(t.handle.db, 'alice', 'a long password');
    const { accessToken } = (await login('a long password')).json() as { accessToken: string };
    await setPassword(t.handle.db, 'alice', 'another long password');

    const res = await t.app.inject({
      url: '/api/auth/session',
      headers: { authorization: `Bearer ${accessToken}` },
    });
    expect(res.statusCode).toBe(401);
    expect((await login('a long password')).statusCode).toBe(401);
    expect((await login('another long password')).statusCode).toBe(200);
  });

  it('deleting a user removes their sessions', async () => {
    await createUser(t.handle.db, 'alice', 'a long password');
    const { accessToken } = (await login('a long password')).json() as { accessToken: string };
    deleteUser(t.handle.db, 'alice');
    const res = await t.app.inject({
      url: '/api/auth/session',
      headers: { authorization: `Bearer ${accessToken}` },
    });
    expect(res.statusCode).toBe(401);
    expect(() => deleteUser(t.handle.db, 'alice')).toThrow(UserError);
  });
});
