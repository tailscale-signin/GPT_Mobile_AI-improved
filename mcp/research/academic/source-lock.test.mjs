import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';

// Use module-relative paths so this also runs directly from the repository root.
const routerUrl = new URL('../../../gateway/v14/academic.py', import.meta.url);
const bridgeManifestUrl = new URL('./manifest.json', import.meta.url);
const gatewayManifestUrl = new URL('../../../gateway/manifest-v14.json', import.meta.url);

test('academic bridge source lock matches the actual reviewed router bytes', async () => {
  const [router, manifestText] = await Promise.all([
    readFile(routerUrl),
    readFile(bridgeManifestUrl, 'utf8')
  ]);
  const manifest = JSON.parse(manifestText);
  assert.match(manifest.routerSha256, /^[0-9a-f]{64}$/);
  assert.equal(
    manifest.routerSha256,
    createHash('sha256').update(router).digest('hex'),
    'Academic router changed without updating its reviewed MCP source lock; the bridge will refuse to start.'
  );
});

test('academic bridge and gateway package pin the same router revision', async () => {
  const [bridgeText, gatewayText] = await Promise.all([
    readFile(bridgeManifestUrl, 'utf8'),
    readFile(gatewayManifestUrl, 'utf8')
  ]);
  const bridge = JSON.parse(bridgeText);
  const gateway = JSON.parse(gatewayText);
  assert.match(gateway.files['v14/academic.py'], /^[0-9a-f]{64}$/);
  assert.equal(
    bridge.routerSha256,
    gateway.files['v14/academic.py'],
    'The academic MCP bridge and gateway package must be reviewed against the same router bytes.'
  );
});
