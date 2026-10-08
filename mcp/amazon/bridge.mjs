import { createServer } from 'node:http';
import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { CallToolRequestSchema, ListToolsRequestSchema, isInitializeRequest } from '@modelcontextprotocol/sdk/types.js';

export const AMAZON_TOOLS = [
  'search_products', 'get_product', 'get_price_history', 'get_deals',
  'get_buy_link', 'compare_marketplaces', 'add_price_watch',
  'list_price_watches', 'remove_price_watch'
];

/** One account-owned upstream process; all HTTP sessions share its price history and watches. */
export async function createAmazonBridge({
  entryPath, token, host = '127.0.0.1', port = 8111,
  allowedHosts = ['localhost', '127.0.0.1'], maxSessions = 8, idleMs = 900_000
}) {
  if (!entryPath || !/^[\x21-\x7e]{24,512}$/.test(token ?? '')) {
    throw new Error('Set an upstream entry path and a strong bridge bearer token.');
  }
  const entry = resolve(entryPath);
  const upstream = new Client({ name: 'gpt-mobile-amazon-bridge', version: '1.0.0' });
  const child = new StdioClientTransport({
    command: process.execPath, args: [entry], cwd: dirname(entry), stderr: 'ignore',
    env: Object.fromEntries(Object.entries(process.env).filter(([key, value]) =>
      key.startsWith('AMAZON_') && !key.startsWith('AMAZON_MCP_') && value !== undefined))
  });
  let toolList;
  try {
    await upstream.connect(child);
    toolList = await upstream.listTools();
    if (!AMAZON_TOOLS.every(name => toolList.tools.some(tool => tool.name === name))) {
      throw new Error('The upstream server does not expose the supported Amazon tool set.');
    }
    toolList = { tools: toolList.tools.filter(tool => AMAZON_TOOLS.includes(tool.name)) };
  } catch (error) {
    await upstream.close();
    throw error;
  }
  const sessions = new Map();
  let pendingSessions = 0;
  let calls = 0;
  let closing = false;
  const hosts = new Set(allowedHosts.map(value => value.trim().toLowerCase()));
  const authorization = createHash('sha256').update(`Bearer ${token}`).digest();
  const reply = (res, status, message) => {
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32000, message } }));
  };
  const dispose = async record => {
    if (record.closing) return;
    record.closing = true;
    if (record.transport.sessionId) sessions.delete(record.transport.sessionId);
    await record.server.close();
  };
  const http = createServer(async (req, res) => {
    let record;
    try {
      if (closing || req.url?.split('?')[0] !== '/mcp') return reply(res, 404, 'Endpoint unavailable.');
      const digest = createHash('sha256').update(req.headers.authorization ?? '').digest();
      if (!timingSafeEqual(authorization, digest)) {
        res.setHeader('WWW-Authenticate', 'Bearer realm="amazon-mcp"');
        return reply(res, 401, 'Bridge authentication required.');
      }
      const requestedHost = new URL(`http://${req.headers.host}`).hostname.toLowerCase();
      if (!hosts.has(requestedHost)) return reply(res, 403, 'Host is not allowed.');
      if (req.headers.origin) {
        const origin = new URL(req.headers.origin);
        if (!hosts.has(origin.hostname.toLowerCase())) return reply(res, 403, 'Origin is not allowed.');
      }
      const sessionId = req.headers['mcp-session-id'];
      record = typeof sessionId === 'string' ? sessions.get(sessionId) : undefined;
      if (sessionId && !record) return reply(res, 404, 'Session expired. Initialize a new session.');
      let body;
      if (req.method === 'POST') {
        if (!req.headers['content-type']?.startsWith('application/json')) return reply(res, 415, 'JSON required.');
        let size = 0;
        const chunks = [];
        for await (const chunk of req) {
          size += chunk.length;
          if (size > 128 * 1024) return reply(res, 413, 'Request too large.');
          chunks.push(chunk);
        }
        try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
        catch { return reply(res, 400, 'Invalid JSON.'); }
      }
      if (!record) {
        if (req.method !== 'POST' || !isInitializeRequest(body)) return reply(res, 400, 'Initialize a session first.');
        if (sessions.size + pendingSessions >= maxSessions) return reply(res, 503, 'Session limit reached.');
        pendingSessions++;
        try {
          const server = new Server({ name: 'amazon-mcp-jannafta', version: '1.0.0' }, { capabilities: { tools: {} } });
          server.setRequestHandler(ListToolsRequestSchema, async () => toolList);
          server.setRequestHandler(CallToolRequestSchema, async (request, extra) => {
            if (!AMAZON_TOOLS.includes(request.params.name)) return { content: [{ type: 'text', text: 'Unknown Amazon tool.' }], isError: true };
            if (calls >= 4) return { content: [{ type: 'text', text: 'Amazon server is busy. Try again shortly.' }], isError: true };
            calls++;
            try {
              return await upstream.callTool(request.params, undefined, { signal: extra.signal, timeout: 60_000 });
            } catch {
              return { content: [{ type: 'text', text: 'Amazon tool unavailable or timed out. Check the host and provider.' }], isError: true };
            } finally { calls--; }
          });
          const transport = new StreamableHTTPServerTransport({
            sessionIdGenerator: randomUUID, enableJsonResponse: true,
            maxRequestBodySize: 128 * 1024,
            onsessioninitialized: id => sessions.set(id, record)
          });
          record = { server, transport, lastActive: Date.now(), closing: false };
          transport.onclose = () => { if (transport.sessionId) sessions.delete(transport.sessionId); };
          await server.connect(transport);
          await transport.handleRequest(req, res, body);
          if (!transport.sessionId) await dispose(record);
        } finally { pendingSessions--; }
      } else {
        record.lastActive = Date.now();
        await record.transport.handleRequest(req, res, body);
      }
    } catch {
      if (!res.headersSent) reply(res, 500, 'Amazon bridge request failed.');
      else res.end();
      if (record && !record.transport.sessionId) await dispose(record);
    }
  });
  http.requestTimeout = 10_000;
  http.headersTimeout = 10_000;
  const reaper = setInterval(() => {
    for (const record of sessions.values()) {
      if (Date.now() - record.lastActive > idleMs) void dispose(record);
    }
  }, Math.min(30_000, Math.max(100, idleMs))).unref();
  try {
    await new Promise((resolveListen, reject) => {
      http.once('error', reject);
      http.listen(port, host, resolveListen);
    });
  } catch (error) {
    clearInterval(reaper);
    await upstream.close();
    throw error;
  }
  return {
    address: http.address(),
    async close() {
      if (closing) return;
      closing = true;
      clearInterval(reaper);
      for (const record of [...sessions.values()]) await dispose(record);
      http.closeAllConnections();
      await new Promise(done => http.close(done));
      await upstream.close();
    }
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const host = process.env.AMAZON_MCP_HOST ?? '127.0.0.1';
    const bridge = await createAmazonBridge({
      entryPath: process.env.AMAZON_MCP_ENTRY,
      token: process.env.AMAZON_MCP_TOKEN,
      host,
      port: Number(process.env.AMAZON_MCP_PORT ?? 8111),
      allowedHosts: (process.env.AMAZON_MCP_ALLOWED_HOSTS ?? `localhost,127.0.0.1,${host}`).split(',')
    });
    console.error(`Amazon MCP bridge ready on port ${bridge.address.port}.`);
    for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => { await bridge.close(); process.exit(0); });
  } catch {
    console.error('Amazon MCP bridge could not start. Check the upstream build and bridge configuration.');
    process.exitCode = 1;
  }
}
