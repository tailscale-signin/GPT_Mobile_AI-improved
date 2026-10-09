import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { createResearchBridge } from './bridge.mjs';
import { extractListingMedia, enhanceAirbnbSource } from './airbnb-enhance.mjs';
const token = 'test-only-research-token-with-32-characters';
const sources = ['news_google', 'news_serpapi', 'news_hacker'].map(prefix => ({ prefix, command: process.execPath, args: [fileURLToPath(new URL('./fixtures/upstream.mjs', import.meta.url))], tools: ['read'] }));

test('three backends share one authenticated endpoint and retain distinct routing', async () => {
  const bridge = await createResearchBridge({ sources, token, port: 0 });
  const client = new Client({ name: 'android-contract-test', version: '1' });
  try {
    await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${bridge.address.port}/mcp`), { requestInit: { headers: { Authorization: `Bearer ${token}` } } }));
    const tools = (await client.listTools()).tools;
    assert.deepEqual(tools.map(tool => tool.name), sources.map(source => `${source.prefix}__read`));
    for (const tool of tools) {
      const result = await client.callTool({ name: tool.name, arguments: { query: 'Canada news' } });
      assert.deepEqual(JSON.parse(result.content[0].text), { name: 'read', arguments: { query: 'Canada news' } });
    }
    assert.equal((await fetch(`http://127.0.0.1:${bridge.address.port}/mcp`, { method: 'POST', body: '{}' })).status, 401);
  } finally { await client.close(); await bridge.close(); }
});

test('optional key-dependent provider is omitted while free providers stay usable', async () => {
  const bridge = await createResearchBridge({ sources: [sources[0], { ...sources[1], requiresEnv: 'RESEARCH_TEST_MISSING_CREDENTIAL', optional: true }], token, port: 0 });
  const client = new Client({ name: 'test', version: '1' });
  try {
    await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${bridge.address.port}/mcp`), { requestInit: { headers: { Authorization: `Bearer ${token}` } } }));
    assert.equal((await client.listTools()).tools.length, 1);
  } finally { await client.close(); await bridge.close(); }
});

test('photo and review extraction excludes avatars and unrelated text without extra network requests', () => {
  const result = extractListingMedia({ contextualPictures: [{ picture: 'https://a0.muscache.com/im/pictures/photo.jpg' }, { picture: 'https://evil.test/pictures/a.jpg' }, { picture: 'https://a0.muscache.com/im/users/avatar.jpg' }], reviews: [{ comments: 'Very clean' }], unrelated: { text: 'Not a review' } });
  assert.deepEqual(result, { photos: ['https://a0.muscache.com/im/pictures/photo.jpg'], reviews: [{ comments: 'Very clean' }] });
  assert.throws(() => enhanceAirbnbSource('unsupported layout'), /contract changed/);
});

test('weak bridge tokens are rejected before starting any upstream', async () => {
  await assert.rejects(createResearchBridge({ sources, token: 'weak', port: 0 }), /bearer token/);
});
