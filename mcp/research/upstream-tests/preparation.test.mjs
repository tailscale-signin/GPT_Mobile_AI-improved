import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installUpstreamDependencies } from '../upstream-dependencies.mjs';
import { enhanceAirbnbSource } from '../airbnb-enhance.mjs';

test('preparation preserves custom source and manifests and accepts known previous preparation', () => {
  const root = mkdtempSync(join(tmpdir(), 'reviewed-upstream-'));
  const folder = join(root, 'airbnb');
  const source = fileURLToPath(new URL('../upstreams/airbnb', import.meta.url));
  const revision = 'd5a8f18f5978b0a33dea4b3ec130c389bad845cc';
  try {
    execFileSync('git', ['clone', '--shared', '--no-checkout', source, folder], { stdio: 'pipe' });
    execFileSync('git', ['checkout', '--detach', revision], { cwd: folder, stdio: 'pipe' });
    const manifest = readFileSync(join(folder, 'package.json'));
    const lock = readFileSync(join(folder, 'package-lock.json'));
    const util = readFileSync(join(folder, 'util.ts'));
    // Model Git core.autocrlf without granting arbitrary normalization or filter authority.
    writeFileSync(join(folder, 'util.ts'), util.toString('utf8').replace(/\r?\n/g, '\r\n'));
    writeFileSync(join(folder, 'package.json'), manifest.toString('utf8').replace(/\r?\n/g, '\r\n'));
    writeFileSync(join(folder, 'package-lock.json'), lock.toString('utf8').replace(/\r?\n/g, '\r\n'));
    assert.doesNotThrow(() => installUpstreamDependencies('airbnb', folder, revision));
    writeFileSync(join(folder, 'util.ts'), util);
    writeFileSync(join(folder, 'package.json'), manifest);
    writeFileSync(join(folder, 'package-lock.json'), lock);
    writeFileSync(join(folder, 'util.ts'), Buffer.concat([util, Buffer.from('\n// user change\n')]));
    assert.throws(() => installUpstreamDependencies('airbnb', folder, revision), /source has custom changes/);
    assert.deepEqual(readFileSync(join(folder, 'package.json')), manifest);
    assert.deepEqual(readFileSync(join(folder, 'package-lock.json')), lock);
    writeFileSync(join(folder, 'util.ts'), util);
    for (const [file, flag] of [['index.ts', '--assume-unchanged'], ['util.ts', '--skip-worktree']]) {
      const prior = readFileSync(join(folder, file));
      execFileSync('git', ['update-index', flag, file], { cwd: folder, stdio: 'pipe' });
      const custom = Buffer.concat([prior, Buffer.from('\n// hidden user change\n')]);
      writeFileSync(join(folder, file), custom);
      assert.throws(() => installUpstreamDependencies('airbnb', folder, revision), /source has custom changes/);
      assert.deepEqual(readFileSync(join(folder, file)), custom);
      assert.deepEqual(readFileSync(join(folder, 'package.json')), manifest);
      execFileSync('git', ['update-index', flag.replace('--', '--no-'), file], { cwd: folder, stdio: 'pipe' });
      writeFileSync(join(folder, file), prior);
    }
    writeFileSync(join(folder, 'unreviewed.ts'), 'export const custom = true;\n');
    assert.throws(() => installUpstreamDependencies('airbnb', folder, revision), /unreviewed files/);
    assert.deepEqual(readFileSync(join(folder, 'package.json')), manifest);
    rmSync(join(folder, 'unreviewed.ts'));
    writeFileSync(join(folder, 'staged.ts'), 'export const staged = true;\n');
    execFileSync('git', ['add', 'staged.ts'], { cwd: folder, stdio: 'pipe' });
    assert.throws(() => installUpstreamDependencies('airbnb', folder, revision), /staged changes/);
    assert.deepEqual(readFileSync(join(folder, 'package.json')), manifest);
    execFileSync('git', ['rm', '--cached', 'staged.ts'], { cwd: folder, stdio: 'pipe' });
    rmSync(join(folder, 'staged.ts'));
    const customManifest = Buffer.concat([manifest, Buffer.from(' ')]);
    writeFileSync(join(folder, 'package.json'), customManifest);
    assert.throws(() => installUpstreamDependencies('airbnb', folder, revision), /custom changes/);
    assert.deepEqual(readFileSync(join(folder, 'package.json')), customManifest);
    assert.deepEqual(readFileSync(join(folder, 'package-lock.json')), lock);
    writeFileSync(join(folder, 'package.json'), manifest);
    // Windows users may not have permission to create file symlinks; the production guard is shared.
    if (process.platform !== 'win32') {
      const outside = join(root, 'custom-manifest.json');
      writeFileSync(outside, manifest);
      rmSync(join(folder, 'package.json'));
      symlinkSync(outside, join(folder, 'package.json'));
      assert.throws(() => installUpstreamDependencies('airbnb', folder, revision), /regular file/);
      assert.deepEqual(readFileSync(outside), manifest);
      rmSync(join(folder, 'package.json'));
      writeFileSync(join(folder, 'package.json'), manifest);
    }
    const original = readFileSync(join(folder, 'index.ts'), 'utf8');
    const current = enhanceAirbnbSource(original);
    const previous = current.replace('robots.isAllowed(new URL(path, BASE_URL).href, USER_AGENT)', 'robots.isAllowed(path, USER_AGENT)');
    writeFileSync(join(folder, 'index.ts'), previous);
    assert.doesNotThrow(() => installUpstreamDependencies('airbnb', folder, revision));
    writeFileSync(join(folder, 'index.ts'), current);
    assert.doesNotThrow(() => installUpstreamDependencies('airbnb', folder, revision));
    assert.deepEqual(readFileSync(join(folder, 'package-lock.json')), readFileSync(fileURLToPath(new URL('../upstream-dependencies/airbnb/package-lock.json', import.meta.url))));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
