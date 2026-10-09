import { execFileSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { existsSync, writeFileSync } from 'node:fs';
import { enhanceAirbnbSource } from './airbnb-enhance.mjs';

const root = dirname(fileURLToPath(import.meta.url));
const revisions = [
  ['airbnb', 'https://github.com/openbnb-org/mcp-server-airbnb.git', 'd5a8f18f5978b0a33dea4b3ec130c389bad845cc'],
  ['hn-server', 'https://github.com/pskill9/hn-server.git', 'ed1a4b951e9a7cdd7de0575a16d53bbe53330a9d']
];
for (const [name, url, revision] of revisions) {
  if (process.argv.slice(2).length && !process.argv.slice(2).includes(name)) continue;
  const folder = resolve(root, 'upstreams', name);
  if (!existsSync(folder)) {
    execFileSync('git', ['clone', url, folder], { stdio: 'inherit' });
    execFileSync('git', ['checkout', revision], { cwd: folder, stdio: 'inherit' });
  }
  const current = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: folder, encoding: 'utf8' }).trim();
  if (current !== revision) throw new Error(`Unexpected ${name} revision; move the upstream checkout aside and run preparation again.`);
  execFileSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['ci', '--ignore-scripts'], { cwd: folder, stdio: 'inherit' });
  if (name === 'airbnb') {
    const source = resolve(folder, 'index.ts');
    const original = execFileSync('git', ['show', `${revision}:index.ts`], { cwd: folder, encoding: 'utf8' });
    writeFileSync(source, enhanceAirbnbSource(original));
  }
  execFileSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['run', 'build'], { cwd: folder, stdio: 'inherit' });
}
