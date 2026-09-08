# GitHub MCP integration guidelines

This document captures engineering practices that must be followed when adding to or modifying the code paths that connect this app to the GitHub MCP server (`https://api.githubcopilot.com/mcp/`, configured via the `"github"` entry in `McpPresetCatalog.kt`) or any other Streamable HTTP MCP server handled by `data/agent/tool/McpClientManager.kt`. The rules below were distilled from real defects observed while operating GitHub's MCP tools directly, and apply both to this app's own MCP client code and to any agent/automation that uses this app's tools against GitHub.

## 1. Write confirmations can lie

A successful response from a mutating tool call (file write, credential save, PR merge, etc.) is not proof the change persisted as intended. Always re-read the affected resource after writing to confirm the actual state.

This codebase already applies this pattern for credentials: `ToolConnectionRepository.storeVerified()` performs the encrypted write and then a read-back with a `contentEquals` check before treating the value as saved, keeping the original value and surfacing a recoverable warning on mismatch. Any new MCP-related persistence (tool bindings, OAuth tokens, cached tool schemas) must follow the same read-after-write verification pattern rather than trusting the write call's return value alone.

## 2. Text-flattening readers destroy whitespace/structure

MCP tool schemas, JSON-RPC payloads, and any code/config/YAML fetched on behalf of a tool call are structurally sensitive (whitespace, indentation, escaping). Never route this data through a plain-text/readability extractor. Parse JSON-RPC and tool schema payloads with the app's structured deserializer (as `McpToolMapper.kt` does) rather than any text-flattening HTTP reader.

## 3. Backslashes may render doubled in some tools (precaution)

Not reproduced in this codebase, but when rendering raw JSON-RPC payloads or tool arguments in debug logs, traces, or exported markdown, spot-check byte-level content before treating a rendered diff as ground truth.

## 4. Stale cached content vs. authoritative source

Do not trust a previously cached tool list or a stale copy of a server's response after configuration changes (new bearer token, new OAuth grant, server URL edit). Re-run tool discovery (`McpClientManager.listTools()`) to get the authoritative, current list rather than relying on a cached snapshot. This mirrors the general rule of trusting an API's current state over a possibly-stale CDN/cache copy.

## 5. No partial patching — treat records as whole-file/whole-record writes

MCP tool calls that "update" a resource (e.g., GitHub's file-update tools) replace the whole target, not a diff. Any code in this app that persists MCP-related state (tool bindings, connection records) must read the full existing record, apply the in-memory change, and write the complete record back — the same discipline `storeVerified()` already uses for credentials.

## 6. Never build on unverified tool output

Output from any MCP tool call, including GitHub's, is untrusted external input until checked. Do not assume a tool call succeeded, returned complete data, or is safe to act on without inspecting its actual JSON-RPC result/error. This is one reason tool output is capped and bounded in this app (see Limits below): unbounded trust in remote tool output is treated as a defect, not a convenience. A concrete fix in this spirit: `MAX_TOOL_PAGES` and `MAX_DISCOVERED_TOOLS` in `McpClientManager.kt` were previously set to `Int.MAX_VALUE` (effectively unbounded), allowing a misbehaving or compromised MCP server to force unbounded pagination or an unbounded in-memory tool list; see PR #68 for the fix (finite bounds with a documented rationale).

## 7. A timeout does not mean the operation failed

Per this app's documented limits (`docs/agent-tools.md`), individual tool calls time out after 60 seconds. On a GitHub MCP tool timeout, the server-side mutation (e.g., a file write, PR merge, or comment) may have already succeeded. Never blindly auto-retry a mutating GitHub MCP tool call after a timeout; first re-read current state (e.g., re-fetch the file/PR/issue) to determine whether the original call actually took effect, then decide whether a retry is needed.

## Best practice: track provenance for every verified fact

When an agent (human-directed or autonomous) uses the GitHub MCP tools through this app or any other client, it should track, per resource touched: file/resource path, the verified content or state, the method used to verify it (e.g., re-read via API vs. assumption), a timestamp, and the commit SHA or resource version. When two sources of truth disagree (e.g., a cached tool list vs. a fresh `listTools()` call, or a raw CDN response vs. an API response), prefer the authoritative API/commit-history source over caches, extracted text, or memory of an earlier turn.

## Where this applies in this codebase

- `data/agent/tool/McpClientManager.kt` — the Streamable HTTP/SSE MCP transport client; discovery bounds (rule 6) and timeout handling (rule 7) live here.
- `data/agent/tool/McpOAuthClient.kt`, `McpOAuthCoordinator.kt` — OAuth 2.1/PKCE flow; token persistence must follow rule 1/5.
- `data/repository/ToolConnectionRepository.kt` — encrypted credential storage; already implements the rule 1/5 pattern via `storeVerified()`, used as the reference implementation above.
- `data/mcp/McpPresetCatalog.kt` — static preset metadata only (including the `"github"` preset's URL and required env key); it contains no transport logic itself.
- `docs/agent-tools.md` — documents the user-facing limits (15 min run cap, 8 rounds, 24 tool calls, 4 concurrent, 60s per-call timeout, 64 KiB bounded output) that operationalize rules 6 and 7.
