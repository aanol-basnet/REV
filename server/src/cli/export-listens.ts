/**
 * Exports a user's listening log as training data: one row per play or skip, with the situation
 * it happened in and features worked out from the log. See docs/training-data.md.
 *
 *   rev-export-listens <username> [--format csv|jsonl] [--tz Europe/Berlin] [--out file]
 *
 * Writes to stdout without --out. Only reads the database.
 */
import { existsSync, writeFileSync } from 'node:fs';
import { asc, eq } from 'drizzle-orm';
import { loadConfig } from '../config.js';
import { openDatabase } from '../db/client.js';
import { plays } from '../db/schema.js';
import { listUsers } from '../auth/users.js';
import { buildDataset, toCsv } from '../stats/dataset.js';
import { Zone } from '../stats/zone.js';

function usage(): never {
  console.error(
    'Usage: rev-export-listens <username> [--format csv|jsonl] [--tz Area/City] [--out file]',
  );
  process.exit(2);
}

function main(): void {
  if (existsSync('.env')) process.loadEnvFile('.env');
  const args = process.argv.slice(2);
  const username = args.find((a, i) => !a.startsWith('--') && !args[i - 1]?.startsWith('--'));
  const option = (name: string) => {
    const i = args.indexOf(`--${name}`);
    return i >= 0 ? args[i + 1] : undefined;
  };
  if (!username) usage();
  const format = option('format') ?? 'csv';
  if (format !== 'csv' && format !== 'jsonl') usage();
  const zone = new Zone(option('tz') ?? Intl.DateTimeFormat().resolvedOptions().timeZone);

  const database = openDatabase(loadConfig().databasePath);
  try {
    const user = listUsers(database.db).find((u) => u.username === username);
    if (!user) {
      console.error(`No user "${username}"`);
      process.exit(1);
    }
    const rows = database.db
      .select()
      .from(plays)
      .where(eq(plays.userId, user.id))
      .orderBy(asc(plays.startedAt))
      .all()
      .map((r) => ({ ...r, genres: parseGenres(r.genres) }));
    const records = buildDataset(rows, zone);
    const text =
      format === 'csv' ? toCsv(records) : records.map((r) => JSON.stringify(r)).join('\n') + '\n';
    const out = option('out');
    if (out) {
      writeFileSync(out, text);
      console.error(`Wrote ${records.length} listens to ${out}`);
    } else {
      process.stdout.write(text);
    }
  } finally {
    database.close();
  }
}

function parseGenres(json: string): string[] {
  try {
    const value: unknown = JSON.parse(json);
    return Array.isArray(value) ? value.filter((g): g is string => typeof g === 'string') : [];
  } catch {
    return [];
  }
}

main();
