import 'dotenv/config';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { pool } from './db.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const dir = path.resolve(here, '../migrations');
const files = (await readdir(dir)).filter(x => x.endsWith('.sql')).sort();
for (const file of files) {
  const sql = await readFile(path.join(dir, file), 'utf8');
  await pool.query(sql);
  console.log(`applied ${file}`);
}
console.log('ZoolChat DB migrated');
await pool.end();
