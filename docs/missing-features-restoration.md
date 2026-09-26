# Missing feature restoration

Based on main `22e8ea25`, compared with `feat/chat-queue-model-popup-settings-mcp-v0-9-12`. This change keeps main's newer chat queue, model controls, Fact Vault, themes, favorite groups and local-runtime handling.

## Analytics

Settings → Usage statistics now includes tool-call rankings with failure counts, run success percentage, text-derived output estimates, and profile/provider/model combinations. All use the selected 7-day, 30-day or stored-history window. Lists show the top 12 entries with the total number of groups disclosed.

- Reported token totals and existing charts remain provider usage only, including a genuine reported zero.
- Estimates apply only to completed runs without reported output usage and with a surviving current assistant answer. The SQL projection joins both assistant-message ID and current-run ID; a retry cannot reuse another run's answer. Historical revisions are deliberately not estimated. SQL counts text without loading answer bodies. The heuristic is ceiling(characters / 4), not a tokenizer or billing measure; reasoning and non-text output are excluded.
- Success = completed / (completed + failed + interrupted). Canceled, running and queued runs are excluded; no eligible runs displays an em dash.
- Profile/provider/model grouping preserves distinct profile IDs even when display names match. Tool grouping preserves connection identity and counts protocol errors as failures.
- Usage statistics is bounded to the latest 10,000 runs and tool events. Diagnostics now samples up to 250 runs and 500 tool events. No database schema migration is needed.

## New-chat picker

Enabled favorites appear first, preserving original order within each group and original selection indices. Disabled profiles remain hidden. Separate/Combined modes show the existing localized descriptions; combined mode still requires at least two enabled selected profiles.

## MCP presets

These are configurable connections, not a claim of tested account authorization. Save the connection, then use **Authorize** in connection settings. If a provider requires a registered public OAuth client, configure its client ID there. The app retains its existing PKCE, secret storage and tool-selection controls.

| Preset | Official endpoint reference | Setup notes |
| --- | --- | --- |
| Linear | https://linear.app/docs/mcp | OAuth or Bearer API key; `/mcp/readonly` is available for read-only tools. |
| Sentry | https://mcp.sentry.dev/ | `/mcp`, OAuth. Optional actual organization/project suffix; no experimental flag or hard-coded organization. |
| Vercel | https://vercel.com/docs/agent-resources/vercel-mcp | Approved clients required for authenticated access. GPT Mobile approval has not been established. |
| Atlassian Rovo | https://developer.atlassian.com/cloud/rovo-mcp/ | `/v2/mcp`, OAuth, workspace/admin permissions; some tools consume Rovo credits. |
| Cloudflare Browser Rendering | https://github.com/cloudflare/mcp-server-cloudflare/blob/main/server.json | `/mcp`, OAuth or suitably scoped Bearer token; account usage limits apply. |

Documentation reviewed on 2026-09-26. Android browser sign-in and authenticated tool calls require device/account testing. All five entries retain `verifiedRemote = false` and visible setup instructions. The four newly bundled logo vectors use Simple Icons brand assets with provenance in `mcp-brand-assets.json`; Cloudflare reuses its existing branded asset.

## Validation

- `:app:compileDebugKotlin` passed, including Room query generation.
- `:app:testDebugUnitTest` passed for UsageStatisticsTest, ChatProfileOrderTest, RunOutputLengthTest, McpPresetCatalogTest (data/catalog), and ToolEventRecorderTest: 36 tests, no failures or skips. The retry test runs against an in-memory Room database with Robolectric.
- ktlint 1.3.1 passed for all changed Kotlin files; `git diff --check` passed.
- Four new vector resources parse and match the hashes recorded in the asset manifest.
- No device UI test or authenticated vendor MCP login was performed.
