import { createServer } from 'node:http';
import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { SSEClientTransport } from '@modelcontextprotocol/sdk/client/sse.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { CallToolRequestSchema, ListToolsRequestSchema, isInitializeRequest } from '@modelcontextprotocol/sdk/types.js';

import { validateResearchSource } from './research-source.mjs';

/** Each upstream retains its own schema; prefixes prevent cross-provider tool collisions. */
export async function createResearchBridge({
  sources, token, host = '127.0.0.1', port = 8112,
  allowedHosts = ['localhost', '127.0.0.1'], maxSessions = 8, idleMs = 900_000
}) {
  if (!Array.isArray(sources) || sources.length < 1 || sources.length > 3 || !/^[\x21-\x7e]{24,512}$/.test(token ?? '')) {
    throw new Error('Configure one to three upstream sources and a strong bridge bearer token.');
  }
  const upstreams = [];
  const routes = new Map();
  const definitions = [];
  try {
    for (const source of sources) {
      validateResearchSource(source);
      if (source.requiresEnv && !process.env[source.requiresEnv]) {
        if (source.optional) continue;
        throw new Error('Required upstream credential is missing.');
      }
      const upstream = new Client({ name: 'gpt-mobile-research-bridge', version: '1.0.0' });
      const env = Object.fromEntries(['PATH', 'HOME', 'USERPROFILE', 'SystemRoot', 'APPDATA', 'LOCALAPPDATA', 'TEMP', 'TMP', ...(source.envKeys ?? [])]
        .filter(key => process.env[key] !== undefined).map(key => [key, process.env[key]]));
      const child = source.url
        ? new SSEClientTransport(new URL(source.url))
        : new StdioClientTransport({ command: source.command, args: source.args, cwd: source.cwd, stderr: 'ignore', env });
      try {
        let connectTimer;
        try {
          await Promise.race([
            upstream.connect(child),
            new Promise((_, reject) => { connectTimer = setTimeout(() => reject(new Error('Upstream initialization timed out.')), 15_000); })
          ]);
        } finally { clearTimeout(connectTimer); }
        const tools = (await upstream.listTools()).tools.filter(tool => (source.tools ?? []).includes(tool.name));
        if (!tools.length) throw new Error('No supported upstream tools discovered.');
        upstreams.push(upstream);
        for (const tool of tools) {
          const name = source.prefix ? `${source.prefix}__${tool.name}` : tool.name;
          if (routes.has(name)) throw new Error('Duplicate upstream tool identity.');
          routes.set(name, { upstream, name: tool.name });
          definitions.push({ ...tool, name });
        }
      } catch (error) {
        await upstream.close();
        if (!source.optional) throw error;
      }
    }
    if (!definitions.length) throw new Error('No research tools are available.');
  } catch (error) {
    await Promise.all(upstreams.map(client => client.close()));
    throw error;
  }
  const closeUpstreams = () => Promise.all(upstreams.map(client => client.close()));
  const toolList = { tools: definitions };
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
        res.setHeader('WWW-Authenticate', 'Bearer realm="research-mcp"');
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
          const server = new Server({ name: 'gpt-mobile-research', version: '1.0.0' }, { capabilities: { tools: {} } });
          server.setRequestHandler(ListToolsRequestSchema, async () => toolList);
          server.setRequestHandler(CallToolRequestSchema, async (request, extra) => {
            if (!routes.has(request.params.name)) return { content: [{ type: 'text', text: 'Unknown Research tool.' }], isError: true };
            if (calls >= 4) return { content: [{ type: 'text', text: 'Research server is busy. Try again shortly.' }], isError: true };
            calls++;
            try {
              const route = routes.get(request.params.name);
              return await route.upstream.callTool({ ...request.params, name: route.name }, undefined, { signal: extra.signal, timeout: 60_000 });
            } catch {
              return { content: [{ type: 'text', text: 'Research tool unavailable or timed out. Check the host and provider.' }], isError: true };
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
      if (!res.headersSent) reply(res, 500, 'Research bridge request failed.');
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
    await closeUpstreams();
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
      await closeUpstreams();
    }
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const configPath = resolve(process.env.RESEARCH_MCP_CONFIG ?? 'news.config.json');
  const config = JSON.parse(await readFile(configPath, 'utf8'));
  for (const source of config.sources) {
    if (source.cwd) source.cwd = resolve(dirname(configPath), source.cwd);
    if (source.command === 'node') {
      source.command = process.execPath;
      source.args = source.args.map(arg => arg.endsWith('.mjs') || arg.endsWith('.js') ? resolve(dirname(configPath), arg) : arg);
    }
  }
  const bridge = await createResearchBridge({
    sources: config.sources, token: process.env.RESEARCH_MCP_TOKEN,
    host: process.env.RESEARCH_MCP_HOST ?? '127.0.0.1',
    port: Number(process.env.RESEARCH_MCP_PORT ?? 8112),
    allowedHosts: (process.env.RESEARCH_MCP_ALLOWED_HOSTS ?? 'localhost,127.0.0.1').split(',')
  });
  const stop = async () => { await bridge.close(); process.exit(0); };
  process.once('SIGINT', stop); process.once('SIGTERM', stop);
  process.stderr.write(`Research MCP listening on port ${bridge.address.port}.\n`);
}
