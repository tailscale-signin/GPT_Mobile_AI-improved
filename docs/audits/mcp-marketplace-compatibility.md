# MCP marketplace compatibility audit — 2026-09-25

The Android app connects directly over Streamable HTTP. Installing an addon saves its connection configuration; it does not run a Node, Python, Docker or browser process on the phone. After installation, select an AI profile and explicitly choose its discovered tools. Tokens are stored through SecretVault.

Selection criteria: maintained primary repository, documented hosted HTTPS endpoint, native Streamable HTTP transport, authentication supported by the app, and a useful distinct tool set. Catalog metadata means documented compatibility, not a successful account authorization or current server health.

## Supported candidates

| Addon / primary repository | Endpoint | Authentication | Live protocol check |
| --- | --- | --- | --- |
| [Context7](https://github.com/upstash/context7) | `https://mcp.context7.com/mcp` | Public; optional Bearer API key | initialize + tools/list passed; 2 tools |
| [Tavily](https://github.com/tavily-ai/tavily-mcp) | `https://mcp.tavily.com/mcp/` | Bearer Tavily key | HTTP 401; account credential required |
| [Firecrawl](https://github.com/firecrawl/firecrawl-mcp-server) | `https://mcp.firecrawl.dev/v2/mcp` | Public limited tier; optional Bearer key | initialize + tools/list passed; 3 tools |
| [Jina AI](https://github.com/jina-ai/MCP) | `https://mcp.jina.ai/v1` | Public reader; Bearer key for paid tools | initialize + tools/list passed; 12 tools |
| [Hugging Face](https://github.com/huggingface/hf-mcp-server) | `https://huggingface.co/mcp` | Bearer HF token; public subset exists | initialize + tools/list passed; 4 tools |
| [Microsoft Learn](https://github.com/MicrosoftDocs/mcp) | `https://learn.microsoft.com/api/mcp` | Public | initialize + tools/list passed; 3 tools |
| [Cloudflare Docs](https://github.com/cloudflare/mcp-server-cloudflare) | `https://docs.mcp.cloudflare.com/mcp` | Public | HTTP 403; access rejected from test environment; live use unverified |
| [Cloudflare Radar](https://github.com/cloudflare/mcp-server-cloudflare) | `https://radar.mcp.cloudflare.com/mcp` | Cloudflare token with Radar permissions | HTTP 403; access rejected from test environment; live use unverified |
| [Neon](https://github.com/neondatabase/mcp-server-neon) | `https://mcp.neon.tech/mcp?readonly=true` | Bearer Neon key; readonly=true | HTTP 401; account credential required |
| [Supabase](https://github.com/supabase/mcp) | `https://mcp.supabase.com/mcp?read_only=true` | Bearer PAT; read_only=true; optional project_ref | HTTP 403; access rejected from test environment; live use unverified |
| [Stripe](https://github.com/stripe/ai) | `https://mcp.stripe.com` | Bearer Agent API key | HTTP 401; account credential required |
| [Cloudflare API](https://github.com/cloudflare/mcp) | `https://mcp.cloudflare.com/mcp` | Bearer Cloudflare API token | HTTP 403; access rejected from test environment; live use unverified |

All live probes used MCP initialize, notifications/initialized and tools/list with protocol negotiation starting at 2025-11-25. No account credentials or mutating tools were used. Lists and capabilities can change; the app discovers them after connecting. A token-required endpoint is not claimed to be tested with an authenticated account. Cloudflare-hosted 403 responses are recorded honestly and require device/network verification.

Additional authentication references: [Stripe](https://docs.stripe.com/mcp), [Supabase](https://supabase.com/docs/guides/ai-tools/mcp), [Context7](https://github.com/upstash/context7/blob/master/packages/mcp/README.md), [Tavily](https://github.com/tavily-ai/tavily-mcp#remote-mcp-server), [Neon](https://github.com/neondatabase/mcp-server-neon#option-3-remote-hosted-mcp-server-api-key-based-authentication). Stripe's current docs require an Agent API key for future compatibility, so the catalog names that key type explicitly. Neon and Supabase use their documented read-only query options; these options do not replace reviewing which tools are enabled for a profile.

## Removed or deferred candidates

- Filesystem, Git, Memory, SQLite, browser automation/Playwright and generic fetch packages that require local STDIO processes: Android has no arbitrary Node/Python/Docker process host. Their package names must not be presented as working remote downloads. The app retains its actual integrated Android tools.
- Datadog, Grafana Cloud and New Relic presets: prior cards used assumed shared endpoints or broad organization links without a verified deployment and auth recipe for this client. Use a manually configured account/deployment URL until verified.
- Vercel, Linear, Notion and Sentry one-click presets: deferred from this curated set until the exact OAuth registration/redirect path is validated on Android. General browser OAuth connection support remains available; pricing no longer incorrectly requires a pasted token for OAuth.
- Grep's public endpoint passed discovery (one tool), but its previous catalog source pointed only to an organization page rather than a maintained server repository. It remains a possible manual connection, not one of the twelve GitHub-backed additions.

GitHub All Toolsets, GitHub Read Only and Exa remain existing catalog entries. The twelve candidates above extend the older built-in/GitHub/Exa selection. Account access, plan limits and actual tool calls still depend on each user's credentials and enabled tools.
