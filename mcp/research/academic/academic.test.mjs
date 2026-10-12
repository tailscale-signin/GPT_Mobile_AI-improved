import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { validateArguments } from './contracts.mjs';
import { validateResearchSource } from '../research-source.mjs';

test('academic schema rejects unknown fields and unbounded requests before dispatch', () => {
  for (const args of [{ query: '' }, { query: 'x', depth: 0 }, { query: 'x', sources: 'all' },
    { query: 'x', maxProviderCalls: 65 }, { query: 'x', fromYear: 2026, toYear: 2020 },
    { query: 'x', includeDomains: ['http://127.0.0.1'] }, { query: 'x', cursor: '999' }]) {
    assert.throws(() => validateArguments('deep_research_search_papers', args));
  }
  assert.equal(validateArguments('deep_research_search_papers', { query: 'inference', maxProviderCalls: 3 }).query, 'inference');
  const expanded = validateArguments('deep_research_search_papers', { query: 'inference', maxProviderCalls: 5, cursor: '10', ephemeral: true,
    enabledProviderIds: Array.from({ length: 37 }, (_, i) => `engine_${i}`) });
  assert.equal(expanded.enabledProviderIds.length, 37);
  assert.equal(expanded.ephemeral, true);
});

test('local academic child path and tool names are pinned; arbitrary wrappers are rejected', () => {
  const source = { kind: 'reviewed_local_academic', prefix: 'academic', command: 'node', args: ['academic/server.mjs'], tools: ['deep_research_status'] };
  validateResearchSource(source);
  assert.equal(source.command, process.execPath);
  for (const change of [{ args: ['other.mjs'] }, { command: 'python' }, { envKeys: ['GITHUB_TOKEN'] }, { tools: ['shell'] }, { prefix: 'other' }]) {
    assert.throws(() => validateResearchSource({ ...source, ...change }));
  }
});

test('real stdio handshake exposes exactly six stable tools and status does not query providers', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'academic-test-'));
  const client = new Client({ name: 'academic-contract-test', version: '1' });
  const transport = new StdioClientTransport({ command: process.execPath, args: [resolve('academic/server.mjs')],
    env: { PATH: process.env.PATH, GPT_ACADEMIC_STATE_DIR: dir }, stderr: 'pipe' });
  try {
    await client.connect(transport);
    const list = await client.listTools();
    assert.equal(list.tools.length, 6);
    assert.equal(new Set(list.tools.map(t => t.name)).size, 6);
    const result = await client.callTool({ name: 'deep_research_status', arguments: {} });
    assert.equal(result.isError, false);
    const payload = JSON.parse(result.content[0].text);
    assert.equal(payload.owner, 'gateway_mcp');
    assert.deepEqual(payload.quotas, {});
    const bad = await client.callTool({ name: 'deep_research_search_papers', arguments: { query: 'x', maxProviderCalls: 999 } });
    assert.equal(bad.isError, true);
  } finally { await client.close(); await rm(dir, { recursive: true, force: true }); }
});

test('academic HTTP bridge requires bearer, rejects foreign origins, and routes one composite', async () => {
  const { createResearchBridge } = await import('../bridge.mjs');
  const { StreamableHTTPClientTransport } = await import('@modelcontextprotocol/sdk/client/streamableHttp.js');
  const token = 'academic-contract-test-token-with-32-characters';
  const bridge = await createResearchBridge({ sources: [{ kind: 'reviewed_local_academic', prefix: 'academic', command: 'node',
    args: ['academic/server.mjs'], tools: ['deep_research_status'] }], token, port: 0 });
  const url = `http://127.0.0.1:${bridge.address.port}/mcp`;
  const client = new Client({ name: 'academic-http-test', version: '1' });
  try {
    assert.equal((await fetch(url, { method: 'POST', body: '{}' })).status, 401);
    assert.equal((await fetch(url, { method: 'POST', headers: { Authorization: 'Bearer incorrect' }, body: '{}' })).status, 401);
    assert.equal((await fetch(url, { method: 'POST', headers: { Authorization: `Bearer ${token}`, Origin: 'https://untrusted.example' }, body: '{}' })).status, 403);
    await client.connect(new StreamableHTTPClientTransport(new URL(url), { requestInit: { headers: { Authorization: `Bearer ${token}` } } }));
    assert.deepEqual((await client.listTools()).tools.map(t => t.name), ['academic__deep_research_status']);
    assert.equal((await client.callTool({ name: 'academic__deep_research_status', arguments: {} })).isError, false);
  } finally { await client.close(); await bridge.close(); }
});
