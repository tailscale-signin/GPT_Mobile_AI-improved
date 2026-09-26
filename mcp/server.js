#!/usr/bin/env node
// Retired: the previous companion used incompatible SDK APIs and unrestricted file/VM access.
console.error('The bundled experimental MCP server has been retired. Configure a maintained Streamable HTTP server in Settings > Tool connections > Marketplace. Local host tools require a separately configured host. See mcp/README.md.');
process.exitCode = 1;
