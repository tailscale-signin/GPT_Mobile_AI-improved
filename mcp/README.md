# GPT Mobile AI - Model Context Protocol (MCP) Server

This directory contains the reference MCP server implementation matching the tools and resources specified in `mcp/tools/manifest.json` and `mcp/resources/manifest.json`.

## Prerequisites

- [Node.js](https://nodejs.org/) v18 or later
- npm v9 or later

## Installation

```bash
cd mcp
npm install
```

## Running the Server

The server communicates via standard input/output (`stdio`) per the Model Context Protocol specification:

```bash
node server.js
```

Or from the root directory:

```bash
npm --prefix mcp start
```

## Tools Implemented

1. **`read_file`**: Read file contents from the local filesystem.
2. **`write_file`**: Write or update files, creating parent directories automatically.
3. **`list_directory`**: List directory contents with entry types.
4. **`delete_file`**: Remove files from the filesystem.
5. **`execute_code`**: Execute JavaScript (Node) or Python snippets in a sandboxed child process with timeout.
6. **`query_database`**: Execute SQL queries against an SQLite/Room database.
7. **`http_request`**: Perform outbound HTTP/HTTPS requests with configurable methods, headers, and 30-second timeouts.
8. **`translate_text`**: Translate text between supported languages.

## Resources Exposed

- **`mcp://system/info`**: System metrics (platform, architecture, memory, CPU count, uptime).
- **`mcp://database/schema`**: Room/SQLite database schema definition (Schema v19).

## Client Integration

### VS Code / Cursor

Ensure `.vscode/mcp.json` is configured in the repository root:

```json
{
  "mcpServers": {
    "gpt-mobile-mcp": {
      "command": "node",
      "args": ["mcp/server.js"],
      "env": {},
      "workingDirectory": "."
    }
  }
}
```

### Android App

The Android client connects to MCP servers via `McpClientManager` located in `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/McpClientManager.kt`.
