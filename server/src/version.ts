import { readFileSync } from 'node:fs';

// src/ and dist/ both sit directly under server/, so this resolves in dev and production.
const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')) as {
  version: string;
};

export const REV_VERSION = pkg.version;
