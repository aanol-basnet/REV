import { existsSync } from 'node:fs';
import { ConfigError, loadConfig } from './config.js';
import { openDatabase } from './db/client.js';
import { buildApp } from './app.js';
import { REV_VERSION } from './version.js';

if (existsSync('.env')) process.loadEnvFile('.env');

let config;
try {
  config = loadConfig();
} catch (err) {
  if (err instanceof ConfigError) {
    console.error(err.message);
    process.exit(1);
  }
  throw err;
}

const database = openDatabase(config.databasePath);
const app = await buildApp({ config, db: database.db });

let shuttingDown = false;
async function shutdown(signal: string): Promise<void> {
  if (shuttingDown) return;
  shuttingDown = true;
  app.log.info({ signal }, 'shutting down');
  await app.close();
  database.close();
  process.exit(0);
}
process.on('SIGINT', () => void shutdown('SIGINT'));
process.on('SIGTERM', () => void shutdown('SIGTERM'));

await app.listen({ host: config.host, port: config.port });
app.log.info({ version: REV_VERSION }, 'REV server started');
