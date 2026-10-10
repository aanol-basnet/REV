/**
 * Manage REV accounts. There is no self-registration; accounts are created here.
 *
 *   rev-user add <username>       prompts for a password
 *   rev-user passwd <username>    changes the password and signs the user out everywhere
 *   rev-user remove <username>
 *   rev-user list
 *
 * When stdin is not a terminal, the password is read from the first line of stdin.
 */
import { existsSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { loadConfig } from '../config.js';
import { openDatabase } from '../db/client.js';
import { createUser, deleteUser, listUsers, setPassword, UserError } from '../auth/users.js';

function readHidden(prompt: string): Promise<string> {
  return new Promise((resolve) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: true });
    let muted = false;
    // Suppress echo of typed characters while still showing the prompt.
    const output = rl as unknown as {
      _writeToOutput: (s: string) => void;
      output: NodeJS.WriteStream;
    };
    output._writeToOutput = (s: string) => {
      if (!muted) output.output.write(s);
    };
    rl.question(prompt, (answer) => {
      rl.close();
      process.stdout.write('\n');
      resolve(answer);
    });
    muted = true;
  });
}

async function readStdinLine(): Promise<string> {
  const rl = createInterface({ input: process.stdin });
  for await (const line of rl) {
    rl.close();
    return line;
  }
  return '';
}

async function promptPassword(): Promise<string> {
  if (!process.stdin.isTTY) return readStdinLine();
  const first = await readHidden('Password: ');
  const second = await readHidden('Confirm password: ');
  if (first !== second) throw new UserError('Passwords do not match');
  return first;
}

function usage(): never {
  console.error('Usage: rev-user <add|passwd|remove> <username> | rev-user list');
  process.exit(2);
}

async function main(): Promise<void> {
  if (existsSync('.env')) process.loadEnvFile('.env');
  const [command, username] = process.argv.slice(2);
  if (!command) usage();

  const database = openDatabase(loadConfig().databasePath);
  try {
    switch (command) {
      case 'list':
        for (const u of listUsers(database.db)) {
          console.log(`${u.id}\t${u.username}\t${new Date(u.createdAt).toISOString()}`);
        }
        break;
      case 'add': {
        if (!username) usage();
        const user = await createUser(database.db, username, await promptPassword());
        console.log(`Created user "${user.username}"`);
        break;
      }
      case 'passwd':
        if (!username) usage();
        await setPassword(database.db, username, await promptPassword());
        console.log('Password changed; all sessions for this user were signed out');
        break;
      case 'remove':
        if (!username) usage();
        deleteUser(database.db, username);
        console.log('User removed');
        break;
      default:
        usage();
    }
  } finally {
    database.close();
  }
}

main().catch((err: unknown) => {
  console.error(err instanceof UserError ? err.message : err);
  process.exit(1);
});
