import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
/** Only the public generic GitMCP service is allowed as a remote upstream. No bridge/provider credentials are forwarded. */
export function validateResearchSource(source) {
  if (!/^[a-z][a-z0-9_]{0,25}$/.test(source.prefix)) throw new Error('Invalid upstream configuration.');
  if (source.kind === 'reviewed_local_academic') {
    const approved = resolve(dirname(fileURLToPath(import.meta.url)), 'academic/server.mjs');
    if (source.url || !['node', process.execPath].includes(source.command) || source.args?.length !== 1 || resolve(source.args[0]) !== approved ||
        source.prefix !== 'academic' || source.envKeys?.some(key => !['GPT_ACADEMIC_STATE_DIR', 'GPT_ACADEMIC_PYTHON'].includes(key)) ||
        source.tools?.some(name => !/^deep_research_(search_papers|get_paper|read_paper|citations|verify_evidence|status)$/.test(name))) {
      throw new Error('Academic upstream must use the reviewed local composite and its six read-only tools.');
    }
    source.command = process.execPath;
    source.args = [approved];
    return;
  }
  if (source.url) {
    const url = new URL(source.url);
    if (source.transport !== 'sse' || url.protocol !== 'https:' || url.hostname !== 'gitmcp.io' ||
        url.pathname !== '/docs' || url.port || url.username || url.password || url.search || url.hash ||
        source.command || source.args || source.envKeys || source.requiresEnv) {
      throw new Error('Remote upstream must be the public GitMCP /docs SSE endpoint without credentials.');
    }
  } else if (!source.command || !Array.isArray(source.args)) {
    throw new Error('Invalid upstream configuration.');
  }
}

