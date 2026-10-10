import { randomUUID } from 'node:crypto';
import { eq } from 'drizzle-orm';
import type { Db } from './client.js';
import { serverMeta } from './schema.js';

const INSTANCE_ID_KEY = 'instance_id';

/** Returns this installation's instance id, generating and persisting it on first run. */
export function getOrCreateInstanceId(db: Db): string {
  const existing = db.select().from(serverMeta).where(eq(serverMeta.key, INSTANCE_ID_KEY)).get();
  if (existing) return existing.value;

  const id = randomUUID();
  db.insert(serverMeta).values({ key: INSTANCE_ID_KEY, value: id }).onConflictDoNothing().run();
  // Re-read in case of a concurrent insert.
  return db.select().from(serverMeta).where(eq(serverMeta.key, INSTANCE_ID_KEY)).get()?.value ?? id;
}
