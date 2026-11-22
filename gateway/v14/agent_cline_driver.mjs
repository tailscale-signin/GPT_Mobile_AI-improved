// Optional Cline SDK bridge. This file executes only in the sealed container.
import {createRequire} from 'node:module';
import {createInterface} from 'node:readline';
import {randomUUID} from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import {spawn} from 'node:child_process';

const require = createRequire('/opt/gateway-agents/package.json');
const {Agent, createTool} = require('@cline/sdk');
let session, agent, busy = false;
const output = (value) => process.stdout.write(JSON.stringify(value) + '\n');
const root = process.cwd();

async function source(relative, create = false) {
    if (typeof relative !== 'string' || relative.length > 400 || path.isAbsolute(relative) || relative.includes('\\') || /[\u0000-\u001f]/.test(relative) ||
        relative.split('/').some(x => !x || ['.', '..', '.git'].includes(x.toLowerCase()))) throw new Error('Unsafe source path');
    const target = path.join(root, relative);
    let current = root;
    for (const part of relative.split('/')) {
        current = path.join(current, part);
        const stat = await fs.lstat(current).catch(() => null);
        if (stat?.isSymbolicLink()) throw new Error('Source links unavailable');
    }
    if (create) await fs.mkdir(path.dirname(target), {recursive: true});
    return target;
}

const tools = [
    createTool({name: 'list_files', description: 'List up to 200 checkout paths at a bounded directory.', retryable: false, maxRetries: 0, timeoutMs: 10000,
        inputSchema: {type: 'object', properties: {directory: {type: 'string'}}, required: ['directory'], additionalProperties: false},
        execute: async ({directory}) => {
            const target = directory === '.' ? root : await source(directory);
            const entries = [];
            for await (const row of await fs.opendir(target)) {
                if (row.name.toLowerCase() === '.git' || row.isSymbolicLink()) continue;
                entries.push({name: row.name, directory: row.isDirectory()});
                if (entries.length > 200) break;
            }
            return {entries: entries.slice(0, 200), truncated: entries.length > 200};
        }}),
    createTool({name: 'read_file', description: 'Read 1-200 lines from an exact checkout source path.', retryable: false, maxRetries: 0, timeoutMs: 10000,
        inputSchema: {type: 'object', properties: {path: {type: 'string'}, start_line: {type: 'integer', minimum: 1}, line_count: {type: 'integer', minimum: 1, maximum: 200}}, required: ['path'], additionalProperties: false},
        execute: async ({path: relative, start_line = 1, line_count = 100}) => {
            const file = await source(relative), stat = await fs.stat(file);
            if (!stat.isFile() || stat.size > 4 * 1024 * 1024) throw new Error('Read a bounded regular file');
            const text = await fs.readFile(file, 'utf8'), lines = text.split('\n');
            return {path: relative, content: lines.slice(start_line - 1, start_line - 1 + line_count).join('\n').slice(0, 16000), total_lines: lines.length};
        }}),
    createTool({name: 'edit_file', description: 'Replace one unique exact anchor in a source file. Empty old creates a new file only.', retryable: false, maxRetries: 0, timeoutMs: 10000,
        inputSchema: {type: 'object', properties: {path: {type: 'string'}, old: {type: 'string'}, replacement: {type: 'string'}}, required: ['path', 'old', 'replacement'], additionalProperties: false},
        execute: async ({path: relative, old, replacement}) => {
            const file = await source(relative, true);
            if (replacement.length > 512 * 1024) throw new Error('Edit budget exceeded');
            const stat = await fs.stat(file).catch(e => {if (e.code === 'ENOENT') return null; throw e;});
            if (stat && (!stat.isFile() || stat.size > 4 * 1024 * 1024)) throw new Error('Read a bounded regular file');
            const existing = await fs.readFile(file, 'utf8').catch(e => {if (e.code === 'ENOENT') return null; throw e;});
            if (old === '') {
                if (existing !== null) throw new Error('New source only');
                await fs.writeFile(file, replacement, {flag: 'wx'});
            } else {
                if (existing === null || existing.split(old).length !== 2) throw new Error('Unique inspected anchor required');
                await fs.writeFile(file, existing.replace(old, replacement));
            }
            return {path: relative, edited: true};
        }}),
    createTool({name: 'run_check', description: 'Run an argv command in the isolated checkout with a 60-second/output limit.', retryable: false, maxRetries: 0, timeoutMs: 65000,
        inputSchema: {type: 'object', properties: {argv: {type: 'array', items: {type: 'string'}, minItems: 1, maxItems: 32}}, required: ['argv'], additionalProperties: false},
        execute: ({argv}, context) => new Promise((resolve, reject) => {
            if (context.abortSignal?.aborted) return reject(new Error('Check aborted before spawn'));
            const child = spawn(argv[0], argv.slice(1), {cwd: root, shell: false, detached: true, stdio: ['ignore', 'pipe', 'pipe']});
            let text = '', size = 0, ended = false, reason = null;
            const finish = (code, error = null) => {
                if (ended) return;
                ended = true; clearTimeout(timer); context.abortSignal?.removeEventListener('abort', abort);
                child.stdout.destroy(); child.stderr.destroy();
                if (error) reject(new Error('Isolated check could not be started'));
                else resolve({exit_code: code, output: text, output_bytes: size, stopped_reason: reason, validation_scope: 'exploratory'});
            };
            const stop = cause => {
                reason = cause;
                try {if (child.pid) process.kill(-child.pid, 'SIGKILL');} catch (_) {child.kill('SIGKILL');}
                // Detached descendants may keep pipes open. Completion must not
                // depend on their close event after this enforced deadline.
                finish(null);
            };
            const abort = () => stop('ABORTED');
            const timer = setTimeout(() => stop('TIMED_OUT'), 60000);
            context.abortSignal?.addEventListener('abort', abort, {once: true});
            const read = block => {size += block.length; text = (text + block.toString('utf8')).slice(-16000); if (size > 1024 * 1024) stop('OUTPUT_LIMIT');};
            child.stdout.on('data', read); child.stderr.on('data', read);
            child.on('error', error => finish(null, error));
            child.on('close', code => finish(code));
        })})
];

async function handle(frame) {
    try {
        const p = frame.params || {};
        let result;
        if (frame.method === 'initialize') result = {protocolVersion: 1, agentCapabilities: {gatewayFollowupWhenIdle: true}};
        else if (frame.method === 'session/new') {
            if (session || path.resolve(p.cwd) !== root) throw new Error('Invalid session');
            const options = p._meta.gateway;
            session = randomUUID();
            agent = new Agent({sessionId: session, providerId: options.provider_id || 'anthropic', modelId: options.model_id,
                apiKey: process.env.CLINE_API_KEY, baseUrl: process.env.CLINE_BASE_URL || undefined,
                tools, maxIterations: options.max_iterations, toolExecution: 'sequential'});
            agent.subscribe(event => {
                const encoded = JSON.stringify(event);
                output({jsonrpc: '2.0', method: 'session/update', params: {sessionId: session,
                    update: {sessionUpdate: 'gateway_event', event: encoded.length < 50000 ? event : {omitted: true}}}});
            });
            result = {sessionId: session};
        } else if (frame.method === 'session/prompt') {
            if (p.sessionId !== session || busy) throw new Error('Invalid active session');
            busy = true;
            try {
                const response = await agent.run(p.prompt.filter(x => x.type === 'text').map(x => x.text).join('\n'));
                if (response.status === 'failed') throw new Error('Agent failed');
                result = {stopReason: response.status === 'aborted' ? 'cancelled' : 'end_turn'};
            } finally {busy = false;}
        } else throw new Error('Unsupported method');
        if (frame.id !== undefined) output({jsonrpc: '2.0', id: frame.id, result});
    } catch (_) {
        if (frame.id !== undefined) output({jsonrpc: '2.0', id: frame.id, error: {code: -32000, message: 'Cline runtime operation rejected'}});
    }
}

for await (const line of createInterface({input: process.stdin, crlfDelay: Infinity})) {
    if (Buffer.byteLength(line) > 256 * 1024) break;
    try {void handle(JSON.parse(line));} catch (_) {break;}
}
