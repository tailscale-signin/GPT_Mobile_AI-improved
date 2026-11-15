import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { createResearchBridge } from '../bridge.mjs';

test('prepared companions preserve schemas, parsing and robots rules through the HTTP bridge', { timeout: 30_000 }, async () => {
  const token = 'test-only-reviewed-upstream-token-32-characters';
  const sources = [
    { prefix: 'airbnb', command: process.execPath, args: [fileURLToPath(new URL('./fixtures/airbnb-entry.mjs', import.meta.url))], tools: ['airbnb_search', 'airbnb_listing_details'] },
    { prefix: 'news_hacker', command: process.execPath, args: [fileURLToPath(new URL('./fixtures/hn-entry.mjs', import.meta.url))], tools: ['get_stories'] }
  ];
  const bridge = await createResearchBridge({ sources, token, port: 0 });
  const client = new Client({ name: 'reviewed-upstream-contract', version: '1' });
  try {
    await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${bridge.address.port}/mcp`), { requestInit: { headers: { Authorization: `Bearer ${token}` } } }));
    const tools = (await client.listTools()).tools;
    assert.deepEqual(tools.map(tool => tool.name).sort(), ['airbnb__airbnb_listing_details', 'airbnb__airbnb_search', 'news_hacker__get_stories']);
    assert.deepEqual(tools.find(tool => tool.name === 'news_hacker__get_stories').inputSchema.properties.type.enum, ['top', 'new', 'ask', 'show', 'jobs']);
    assert.equal(tools.find(tool => tool.name === 'airbnb__airbnb_search').inputSchema.properties.location.type, 'string');
    const blocked = await client.callTool({ name: 'airbnb__airbnb_listing_details', arguments: { id: 'blocked-fixture', adults: 2 } });
    assert.equal(blocked.isError, true);
    assert.match(JSON.parse(blocked.content[0].text).error, /disallowed.*robots/i);
    const allowed = await client.callTool({ name: 'airbnb__airbnb_listing_details', arguments: { id: 'dependency-fixture', adults: 2 } });
    assert.equal(allowed.isError, false);
    const listing = JSON.parse(allowed.content[0].text);
    assert.equal(new URL(listing.listingUrl).searchParams.get('adults'), '2');
    assert.deepEqual(listing.details.find(item => item.id === 'GPTMOBILE_MEDIA'), {
      id: 'GPTMOBILE_MEDIA', photos: ['https://a0.muscache.com/im/pictures/dependency-fixture.jpg'], reviews: [{ comments: 'Dependency fixture review' }]
    });
    const stories = await client.callTool({ name: 'news_hacker__get_stories', arguments: { type: 'new', limit: 1 } });
    assert.deepEqual(JSON.parse(stories.content[0].text), [{ title: 'Dependency fixture', url: 'https://news.ycombinator.com/item?id=123', points: 42, author: 'fixture', time: '2026-11-15T00:00:00', commentCount: 7, rank: 1 }]);
  } finally {
    await client.close();
    await bridge.close();
  }
});
