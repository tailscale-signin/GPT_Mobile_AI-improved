import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { request } from 'node:http';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { AMAZON_TOOLS, createAmazonBridge } from './bridge.mjs';

const entryPath = fileURLToPath(new URL('./fixtures/upstream.mjs', import.meta.url));
const token = 'test-only-token-with-at-least-32-characters';
const post = (url, headers, body) => new Promise((resolve, reject) => {
  const req = request(url, { method: 'POST', headers }, res => {
    res.resume();
    res.once('end', () => resolve(res.statusCode));
  });
  req.once('error', reject);
  req.end(body);
});
const connect = async bridge => {
  const client = new Client({ name: 'android-contract-test', version: '1' });
  const transport = new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${bridge.address.port}/mcp`), { requestInit: { headers: { Authorization: `Bearer ${token}` } } });
  try { await client.connect(transport); }
  catch (error) { await client.close(); throw error; }
  return { client, transport };
};

test('real HTTP and stdio transports discover all nine tools and forward arguments and responses', async () => {
  const bridge = await createAmazonBridge({ entryPath, token, port: 0 });
  let connection;
  try {
    connection = await connect(bridge);
    const list = await connection.client.listTools();
    assert.deepEqual(list.tools.map(tool => tool.name), AMAZON_TOOLS);
    for (const name of AMAZON_TOOLS) {
      const result = await connection.client.callTool({ name, arguments: { asin: 'B08N5WRWNW', marketplace: 'CA', targetPrice: 40 } });
      assert.deepEqual(JSON.parse(result.content[0].text), { name, arguments: { asin: 'B08N5WRWNW', marketplace: 'CA', targetPrice: 40 } });
    }
    await connection.transport.terminateSession();
  } finally {
    await connection?.client.close();
    await bridge.close();
  }
});

test('authentication, host validation, malformed JSON and expired sessions are rejected', async () => {
  const bridge = await createAmazonBridge({ entryPath, token, port: 0 });
  const url = `http://127.0.0.1:${bridge.address.port}/mcp`;
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  try {
    assert.equal((await fetch(url, { method: 'POST', body: '{}' })).status, 401);
    assert.equal(await post(url, { ...headers, Host: 'untrusted.example' }, '{}'), 403);
    assert.equal((await fetch(url, { method: 'POST', headers: { ...headers, Origin: 'https://untrusted.example' }, body: '{}' })).status, 403);
    assert.equal((await fetch(url, { method: 'POST', headers, body: '{' })).status, 400);
    assert.equal((await fetch(url, { method: 'POST', headers: { ...headers, 'mcp-session-id': 'expired' }, body: '{}' })).status, 404);
  } finally { await bridge.close(); }
});

test('session capacity is released on disconnect and weak tokens fail before starting the host', async () => {
  await assert.rejects(createAmazonBridge({ entryPath, token: '', port: 0 }), /bearer token/);
  const bridge = await createAmazonBridge({ entryPath, token, port: 0, maxSessions: 1 });
  let first;
  let next;
  try {
    first = await connect(bridge);
    await assert.rejects(connect(bridge), /Session limit reached/);
    await first.transport.terminateSession();
    await first.client.close();
    next = await connect(bridge);
    assert.equal((await next.client.listTools()).tools.length, 9);
  } finally {
    await first?.client.close();
    await next?.client.close();
    await bridge.close();
  }
});
