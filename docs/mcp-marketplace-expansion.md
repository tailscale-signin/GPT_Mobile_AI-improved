# MCP marketplace expansion

Base: `feat/queued-prompts-model-controls-debug-marketplace` at `b3422ae007dfa645cbdf9179d56486743565101a`.

The base contains 17 presets. This change adds 18 and updates branding for existing entries, for 35 total. All 22 services in the requested marketplace recommendations are represented; Tavily, Jina, Hugging Face and Supabase already existed.

## Connection behavior

Presets populate the existing Streamable HTTP configuration and tool-discovery flow. They do not automatically connect accounts, run tools, upload conversation history, or synchronize local memory. Enable the desired tools on an AI profile after connecting.

`OAUTH` is the application's actual browser-sign-in constant; `OAUTH21` is not a supported stored value. Documented endpoint badges mean provider documentation exists, not that an authenticated account has been tested.

| Service | Default authentication | Setup / source |
| --- | --- | --- |
| Mem0 Memory | OAUTH | [Provider documentation](https://docs.mem0.ai/platform/mem0-mcp) |
| Supermemory | OAUTH | [Provider documentation](https://supermemory.ai/mcp/) |
| Mnemoverse | OAUTH | [Provider documentation](https://mnemoverse.com/docs/api/integrations) |
| Pearls | OAUTH | [Self-hosted URL required](https://github.com/Garblesnarff/pearls) |
| Brave Search | NONE | [Self-hosted URL required](https://github.com/brave/brave-search-mcp-server) |
| Stack Overflow | OAUTH | [Provider documentation](https://stackoverflow.com/help/mcp-server) |
| Semgrep | OAUTH | [Provider documentation](https://github.com/semgrep/mcp) |
| DeepWiki | NONE | [Provider documentation](https://docs.devin.ai/work-with-devin/deepwiki-mcp) |
| Netlify | OAUTH | [Provider documentation](https://docs.netlify.com/build/build-with-ai/agent-setup-guides/set-up-claude-code-for-netlify/) |
| Airtable | OAUTH | [Provider documentation](https://support.airtable.com/articles/9897799762-using-the-airtable-mcp-server) |
| Prisma Postgres | OAUTH | [Provider documentation](https://www.prisma.io/docs/ai/tools/mcp-server) |
| Slack | BEARER | [Provider documentation](https://docs.slack.dev/ai/slack-mcp-server/) |
| Asana | OAUTH | [Provider documentation](https://developers.asana.com/docs/using-asanas-mcp-server) |
| Todoist | OAUTH | [Provider documentation](https://developer.todoist.com/) |
| Google Drive | BEARER | [Provider documentation](https://developers.google.com/workspace/guides/configure-mcp-servers) |
| Google Sheets | BEARER | [Provider documentation](https://developers.google.com/workspace/sheets/api/guides/configure-mcp-server) |
| Excalidraw | NONE | [Provider documentation](https://github.com/excalidraw/excalidraw-mcp) |
| Bright Data | NONE | [Provider documentation](https://github.com/brightdata/brightdata-mcp) |

## Corrections to the supplied proposal

- Supermemory: `https://mcp.supermemory.ai/mcp`, browser OAuth, instead of the proposed API hostname and Bearer default.
- Jina: existing `https://mcp.jina.ai/v1` retained; no duplicate preset.
- Brave: official server supports HTTP but requires a deployment. No provider documentation was found for the proposed hosted address. The API key belongs on the server, not in a client Authorization header.
- Pearls: configure a reachable deployment; an example domain is not a hosted service.
- Slack: requires an approved internal/directory Slack app and scoped user token. Generic dynamic OAuth registration is unavailable. Bearer is the usable default; custom OAuth client configuration remains available in connection settings.
- Google Drive/Sheets: developer-preview enrollment, enabled APIs and scoped Google OAuth access tokens are required. The generic MCP OAuth flow is not configured for Google's client-secret setup; Bearer tokens must be refreshed by the user.
- Semgrep: an unauthenticated initialization returned HTTP 401 with OAuth protected-resource metadata. Browser sign-in replaces the proposed NONE setting. The old standalone repository has been archived/moved.
- Bright Data: provider docs specify a `token` query parameter. Setup requires the complete URL, masks it while configuring, and rejects empty, duplicate or sample token values. The URL remains a secret within the connection endpoint, so existing URL storage/export behavior applies.
- Excalidraw: the public server is `https://mcp.excalidraw.com`. Its interactive MCP Apps canvas is not implemented in this client; the preset describes this limitation rather than promising embedded editing.
- Free-tier quotas and star counts are deliberately omitted because they change. Account and usage requirements are described per provider.

## Icons

28 bundled brand assets plus the existing GitHub resource cover remote provider branding, including the four existing requested services. Resources are loaded locally in both marketplace cards and setup dialogs, without runtime image downloads. Source URLs, hashes and ownership notes are in `mcp-brand-assets.json`. Vector marks come from Simple Icons (CC0); provider favicons and repository assets retain their owners' trademarks. Pearls publishes no project logo in its repository, so it uses a neutral threading glyph rather than an invented official mark.

## Validation

- 13 catalog JUnit tests passed using the cached Kotlin 2.3.21 compiler and JUnit 4.13.2.
- Checked bundled resource existence, vector XML parsing, catalog uniqueness, all 22 requested providers, category filtering, auth constants and endpoint-token validation.
- `git diff --check` passed.
- Full Android/Compose compilation was not run: the Gradle wrapper download failed with `Network is unreachable`; this environment also has Java 17 rather than the project's Java 21 toolchain. Authenticated provider discovery and Android UI rendering remain unverified.
