/**
 * Trains a user's personal skip model now and shows how well it predicts their most recent
 * listens, next to the smart queue's hand-tuned score. The server also retrains on its own.
 *
 *   rev-train-model <username>
 */
import { existsSync } from 'node:fs';
import { loadConfig } from '../config.js';
import { openDatabase } from '../db/client.js';
import { listUsers } from '../auth/users.js';
import { ModelService, MIN_EXAMPLES } from '../model/service.js';

function main(): void {
  if (existsSync('.env')) process.loadEnvFile('.env');
  const username = process.argv[2];
  if (!username) {
    console.error('Usage: rev-train-model <username>');
    process.exit(2);
  }
  const database = openDatabase(loadConfig().databasePath);
  try {
    const user = listUsers(database.db).find((u) => u.username === username);
    if (!user) {
      console.error(`No user "${username}"`);
      process.exit(1);
    }
    const service = new ModelService(database.db);
    const examples = service.examples(user.id);
    const skips = examples.filter((e) => e.label === 1).length;
    console.log(`${examples.length} listens (${skips} skips, ${examples.length - skips} listens)`);
    const model = service.train(user.id);
    if (!model) {
      console.log(`Not enough to learn from yet: needs ${MIN_EXAMPLES} listens.`);
      return;
    }
    console.log(
      `Trained on ${model.examples} listens; ${Object.keys(model.weights).length} features kept.`,
    );
    const m = model.metrics;
    if (!m) {
      console.log('Too few listens to hold some back for testing yet.');
      return;
    }
    console.log(`Tested on the newest ${m.holdout} listens:`);
    console.log(
      `  log loss       ${m.logLoss}  (always guessing the usual skip rate: ${m.baselineLogLoss}; lower is better)`,
    );
    console.log(`  AUC            ${m.auc ?? 'n/a'}  (0.5 is chance, 1 is perfect)`);
    console.log(
      `  smart queue    ${m.smartQueueAuc ?? 'n/a'}  (AUC of the hand-tuned score, where recorded)`,
    );
    console.log(
      `  phone model    ${m.phoneModelAuc ?? 'n/a'}  (AUC of the phone's own model at the time, where recorded)`,
    );
    const strongest = Object.entries(model.weights)
      .sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]))
      .slice(0, 10);
    console.log('Strongest signals (positive: more likely to skip):');
    for (const [feature, weight] of strongest)
      console.log(`  ${weight > 0 ? '+' : ''}${weight}\t${feature}`);
  } finally {
    database.close();
  }
}

main();
