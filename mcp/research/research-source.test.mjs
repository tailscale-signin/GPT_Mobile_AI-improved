import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateResearchSource } from './research-source.mjs';

test('GitMCP remote upstream is exact, public, and cannot receive bridge/provider credentials', () => {
  const source = { prefix: 'gitmcp', transport: 'sse', url: 'https://gitmcp.io/docs' };
  assert.doesNotThrow(() => validateResearchSource(source));
  for (const url of ['http://gitmcp.io/docs', 'https://gitmcp.io.evil.test/docs', 'https://gitmcp.io/private', 'https://user:secret@gitmcp.io/docs', 'https://gitmcp.io/docs?token=secret', 'https://gitmcp.io:8443/docs', 'https://127.0.0.1/docs']) {
    assert.throws(() => validateResearchSource({ ...source, url }));
  }
  for (const credentials of [{ envKeys: ['GITHUB_TOKEN'] }, { requiresEnv: 'GITHUB_TOKEN' }, { command: 'node', args: [] }]) {
    assert.throws(() => validateResearchSource({ ...source, ...credentials }));
  }
});
