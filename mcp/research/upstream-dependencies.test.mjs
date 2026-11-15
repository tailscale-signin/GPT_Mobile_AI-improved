import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { validateDependencyOverlay } from './upstream-dependencies.mjs';

const revision = 'a'.repeat(40);
const original = { 'package.json': Buffer.from('original manifest'), 'package-lock.json': Buffer.from('original lock') };
const overlay = { 'package.json': Buffer.from('reviewed manifest'), 'package-lock.json': Buffer.from('reviewed lock') };
const hashes = values => Object.fromEntries(Object.entries(values).map(([key, value]) => [key, createHash('sha256').update(value).digest('hex')]));
const record = { revision, upstreamSha256: hashes(original), overlaySha256: hashes(overlay) };

test('dependency preparation accepts pristine, prior reviewed and interrupted pairs', () => {
  for (const current of [original, overlay, { ...original, 'package.json': overlay['package.json'] }]) {
    assert.doesNotThrow(() => validateDependencyOverlay(record, revision, original, current, overlay));
  }
});

test('dependency preparation rejects changed source, unreviewed lock and user modifications', () => {
  assert.throws(() => validateDependencyOverlay(record, 'b'.repeat(40), original, original, overlay), /revision/);
  assert.throws(() => validateDependencyOverlay(record, revision, { ...original, 'package.json': Buffer.from('changed source') }, original, overlay), /Pinned upstream/);
  assert.throws(() => validateDependencyOverlay(record, revision, original, original, { ...overlay, 'package-lock.json': Buffer.from('unreviewed lock') }), /Reviewed dependency/);
  const custom = { ...original, 'package-lock.json': Buffer.from('user dependency changes') };
  assert.throws(() => validateDependencyOverlay(record, revision, original, custom, overlay), /custom changes/);
  assert.equal(custom['package-lock.json'].toString(), 'user dependency changes');
});
