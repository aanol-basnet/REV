import { hash, verify } from '@node-rs/argon2';

// @node-rs/argon2 defaults to Argon2id with OWASP-recommended parameters (m=19 MiB, t=2, p=1).

export const MIN_PASSWORD_LENGTH = 10;

export function hashPassword(password: string): Promise<string> {
  return hash(password);
}

export async function verifyPassword(passwordHash: string, password: string): Promise<boolean> {
  try {
    return await verify(passwordHash, password);
  } catch {
    return false;
  }
}

let dummyHash: Promise<string> | undefined;

/**
 * Performs a verification against a throwaway hash so that "unknown user" takes as long as
 * "wrong password" and usernames cannot be enumerated by timing.
 */
export async function burnPasswordVerification(password: string): Promise<void> {
  dummyHash ??= hash('rev-dummy-password-for-timing');
  await verifyPassword(await dummyHash, password);
}
