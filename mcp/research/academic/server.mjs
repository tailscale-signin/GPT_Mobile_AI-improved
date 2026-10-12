import { validateArguments } from './contracts.mjs';
import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { ListToolsRequestSchema, CallToolRequestSchema } from '@modelcontextprotocol/sdk/types.js';

const here = dirname(fileURLToPath(import.meta.url));
const modulePath = resolve(here, '../../../gateway/v14/academic.py');
const lock = JSON.parse(await readFile(resolve(here, 'manifest.json'), 'utf8'));
const actual = createHash('sha256').update(await readFile(modulePath)).digest('hex');
if (actual !== lock.routerSha256) throw new Error('Academic router differs from its reviewed source lock.');
const operations = ['search_papers', 'get_paper', 'read_paper', 'citations', 'verify_evidence', 'status'];
const tools = operations.map(operation => ({
  name: `deep_research_${operation}`,
  description: `Read-only academic ${operation.replaceAll('_', ' ')}. Metadata is a discovery lead; quote verification checks captured text, not scientific truth.`,
  annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: true },
  inputSchema: {
    type: 'object', additionalProperties: false,
    properties: {
      ephemeral: { type: 'boolean' },
      query: { type: 'string', minLength: 1, maxLength: 500 }, identifier: { type: 'string', maxLength: 500 },
      paperId: { type: 'string', maxLength: 500 }, versionId: { type: 'string', maxLength: 700 }, quote: { type: 'string', maxLength: 4000 },
      direction: { type: 'string', enum: ['references', 'citations'] },
      scope: { type: 'string', enum: ['papers'] }, discipline: { type: 'array', items: { type: 'string' }, maxItems: 5 },
      depth: { type: 'integer', minimum: 1, maximum: 100 }, maxResults: { type: 'integer', minimum: 1, maximum: 50 },
      maxProviderCalls: { type: 'integer', minimum: 1, maximum: 64 }, cursor: { type: 'string', enum: Array.from({ length: 10 }, (_, i) => String(i + 1)) },
      fromYear: { type: 'integer', minimum: 1500, maximum: 3000 }, toYear: { type: 'integer', minimum: 1500, maximum: 3000 },
      reviewStatus: { type: 'string', enum: ['any', 'unknown', 'preprint', 'peer_reviewed'] },
      enabledProviderIds: { type: 'array', items: { type: 'string', maxLength: 40 }, maxItems: 256 },
      includeDomains: { type: 'array', items: { type: 'string', maxLength: 200 }, maxItems: 20 },
      excludeDomains: { type: 'array', items: { type: 'string', maxLength: 200 }, maxItems: 20 }
    },
    required: operation === 'search_papers' ? ['query'] : operation === 'verify_evidence' ? ['paperId', 'versionId', 'quote'] : operation === 'status' ? [] : ['identifier']
  }
}));
let worker;
let lines;
const pending = new Map();
function start() {
  if (worker) return;
  worker = spawn(process.env.GPT_ACADEMIC_PYTHON || (process.platform === 'win32' ? 'python' : 'python3'), [modulePath], {
    stdio: ['pipe', 'pipe', 'ignore'], windowsHide: true,
    env: Object.fromEntries(['PATH', 'HOME', 'USERPROFILE', 'SystemRoot', 'TEMP', 'TMP', 'GPT_ACADEMIC_STATE_DIR'].filter(k => process.env[k] !== undefined).map(k => [k, process.env[k]]))
  });
  const active = worker;
  lines = createInterface({ input: active.stdout });
  lines.on('line', line => {
    if (line.length > 512 * 1024) { active.kill(); return; }
    let message; try { message = JSON.parse(line); } catch { active.kill(); return; }
    pending.get(message.id)?.resolve(message);
  });
  function failed() {
    if (worker === active) worker = undefined;
    for (const task of pending.values()) task.reject(new Error('Academic worker unavailable. Completed evidence is retained.'));
    pending.clear();
  }
  active.once('error', failed); active.once('exit', failed);
}
const server = new Server({ name: 'gpt-mobile-deep-research', version: '1.0.0' }, { capabilities: { tools: {} } });
server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools }));
server.setRequestHandler(CallToolRequestSchema, async (request, extra) => {
  if (!tools.some(tool => tool.name === request.params.name) || pending.size >= 3) return { isError: true, content: [{ type: 'text', text: 'Academic operation unavailable or busy.' }] };
  if (JSON.stringify(request.params.arguments ?? {}).length > 32 * 1024) throw new Error('Academic arguments too large.');
  let args;
  try { args = validateArguments(request.params.name, request.params.arguments ?? {}); }
  catch { return { isError: true, content: [{ type: 'text', text: 'Invalid academic arguments.' }] }; }
  start();
  const id = randomUUID();
  const active = worker;
  let timeout;
  const cancel = () => { if (active?.stdin.writable) active.stdin.write(JSON.stringify({ cancel: id }) + '\n'); pending.get(id)?.reject(new Error('Academic operation cancelled.')); };
  try {
    const message = await new Promise((resolveResult, reject) => {
      pending.set(id, { resolve: resolveResult, reject });
      timeout = setTimeout(() => { cancel(); reject(new Error('Academic deadline exceeded.')); }, 55_000);
      extra.signal.addEventListener('abort', cancel, { once: true });
      if (extra.signal.aborted) { cancel(); reject(new Error('Academic operation cancelled.')); return; }
      active.stdin.write(JSON.stringify({ id, name: request.params.name, arguments: args }) + '\n');
    });
    return { isError: Boolean(message.error), content: [{ type: 'text', text: JSON.stringify(message.error ? { status: message.error, errorOrigin: 'upstream_source' } : message.result) }] };
  } catch {
    return { isError: true, content: [{ type: 'text', text: 'Academic operation interrupted or unavailable; completed evidence remains available.' }] };
  } finally {
    clearTimeout(timeout); extra.signal.removeEventListener('abort', cancel); pending.delete(id);
  }
});
process.once('SIGTERM', () => { worker?.kill(); process.exit(0); });
process.once('SIGINT', () => { worker?.kill(); process.exit(0); });
process.stdin.once('end', () => { worker?.stdin.end(); setTimeout(() => worker?.kill(), 1500).unref(); });
await server.connect(new StdioServerTransport());
