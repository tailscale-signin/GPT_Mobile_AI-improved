/** Only the public generic GitMCP service is allowed as a remote upstream. No bridge/provider credentials are forwarded. */
export function validateResearchSource(source) {
  if (!/^[a-z][a-z0-9_]{0,25}$/.test(source.prefix)) throw new Error('Invalid upstream configuration.');
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

