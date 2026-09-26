# Optional local MCP integrations

## What runs where

The marketplace now lists all 29 requested projects and two native configurable tools. A marketplace connection does not install Python, Node, Docker, desktop applications or Unreal on Android. Twenty-three project entries accept a user-supplied Streamable HTTP endpoint; six standalone clients/apps/guides open their documentation instead. No invented shared endpoint or device-local `localhost` address is installed.

Project/provider logos are bundled where a usable upstream asset was available. The official MCP protocol mark is the explicitly generic fallback for projects without a verified bundled logo; it is not claimed as their product logo. Source URLs and hashes are in `mcp-brand-assets.json`. The Unreal connector uses the associated Unreal Engine mark. Built-in tools use app icons and are not branded as upstream Houtini code.

## Built-in memory (enabled for a new vault)

Settings → Tool connections → Local memory capture and recall controls the same encrypted Fact Vault used by chat. Configure memory opens the full vault: review/delete facts, toggle learning and recall independently, allow/disallow cloud recall, restrict recall to the source chat, require review, and set retention/storage limits.

- New vaults default on. Previously persisted disabled choices, including legacy payloads omitting the old false default, remain disabled. Clear disables learning. A vault disappearing after it was loaded in the same process fails closed.
- Automatic capture reads only user messages and retains the existing bounded pattern-based extractor. It is not a general semantic memory model.
- `memory_capture` processes the actual current user message, never model-provided fact text. `memory_recall` searches without learning from the search query.
- Master tool-disable/local-tool-disable controls suppress both automatic memory and native memory tools.
- Disabled/review-pending facts, chat boundaries and cloud-recall policy apply equally to explicit recall. Detailed fact text is replaced with a count in persistent tool traces; the model receives the selected facts only when policy permits.
- Local storage does not mean local processing. Cloud recall is a separate visible setting. Llama/Ollama profiles use their configured server; only LiteRT inference runs on the phone.

## Built-in model delegation (optional)

Open its marketplace card → Configure, or Settings → Tool connections → Model delegation. Enable it, choose an enabled target AI profile, and configure local-platform restriction, input character limit, output token limit, timeout and call budget. Existing profiles supply model, endpoint, credentials and provider settings. No extra API key store is created.

Default limits: 8,000 input characters, 512 output tokens, 30 seconds, one call per AI run. Configurable ranges: 500–16,000 characters, 64–2,048 tokens, 5–40 seconds, 1–3 calls. The timeout stays below the existing 45-second bounded runner safeguard. At most 16,000 output characters are accepted.

The `delegate_to_model` tool sends only its task in an isolated text round. It does not inject chat history, attachments, saved facts or target custom system instructions, and it supplies no app tools. Self-delegation and on-device→on-device delegation are rejected before inference to avoid reentering the busy local runtime. Cloud→on-device, on-device→llama/Ollama, and other provider combinations use the existing adapters. Turn off “Only local AI platforms” to select a cloud/custom target.

Cancellation propagates to inference. Settings and target availability are rechecked before every call. Failures return a bounded tool error rather than provider credentials or exception text. Nested app tool calls are rejected. OpenAI-compatible requests explicitly send `tool_choice=none` when tools are disabled. A separately operated gateway must honor that request; use a direct inference endpoint for strict no-server-tools behavior. Delegated provider usage is not added to the parent model's reported token totals.

## External Houtini LM

The separate Houtini LM card connects to the actual upstream package. Its host requires Node 22.5+ and an inference endpoint. The [upstream README](https://github.com/houtini-ai/houtini-lm) documents `HOUTINI_LM_ENDPOINT_URL` and the [Docker HTTP gateway route](https://github.com/houtini-ai/houtini-lm/blob/main/manual/docker.md). The inference URL and MCP URL are different: never paste a llama `/v1` API URL into the MCP field.

A generic alternative for an already working STDIO server is [Supergateway's Streamable HTTP mode](https://github.com/supercorp-ai/supergateway#stdio--streamable-http). Example on the computer, after installing the upstream package:

```sh
npx -y supergateway --stdio "npx -y @houtini/lm" --outputTransport streamableHttp --stateful --port 8012 --streamableHttpPath /mcp
```

Set Houtini's inference environment variables on that computer before launching. This bridge command does not itself establish incoming Bearer authentication. Keep it on a restricted host/network and use an authenticated TLS reverse proxy for remote access. On the phone, configure the reachable proxy `/mcp` URL and its matching authentication. `localhost` on the phone refers to the phone, not the PC. Explicit cleartext permission remains required for private HTTP endpoints. The application does not execute the command or expose a service automatically.

After saving any external connection, use connection health/discovery and explicitly enable its tools for the chosen AI profile. External memory stores do not inherit Fact Vault toggles or deletion policies. Configure their retention/access on the host.

## Project coverage

| Project | Marketplace behavior |
| --- | --- |
| [Ollama MCP Bridge](https://github.com/patruff/ollama-mcp-bridge) | Companion/setup guide; no server install |
| [MCP Client for Ollama](https://github.com/jonigl/mcp-client-for-ollama) | Companion/setup guide; no server install |
| [Houtini LM](https://github.com/houtini-ai/houtini-lm) | Configure your host MCP endpoint |
| [LM Studio Bridge](https://github.com/infinitimeless/claude-lmstudio-bridge) | Configure your host MCP endpoint |
| [ComfyUI MCP (legacy)](https://github.com/artokun/comfyui-mcp) | Configure your host MCP endpoint |
| [MARM Memory](https://github.com/Lyellr88/marm-memory) | Configure your host MCP endpoint |
| [TrueMemory](https://github.com/buildingjoshbetter/TrueMemory) | Configure your host MCP endpoint |
| [ClawMem](https://github.com/yoloshii/ClawMem) | Configure your host MCP endpoint |
| [uteke](https://github.com/codecoradev/uteke) | Configure your host MCP endpoint |
| [Sibyl Memory](https://github.com/Sibyl-Labs/Sibyl-Memory) | Configure your host MCP endpoint |
| [Agentset](https://github.com/agentset-ai/agentset) | Configure your host MCP endpoint |
| [DBHub](https://github.com/bytebase/dbhub) | Configure your host MCP endpoint |
| [Chat2DB](https://github.com/OtterMind/Chat2DB) | Companion/setup guide; no server install |
| [DBX](https://github.com/t8y2/dbx) | Configure your host MCP endpoint |
| [MCP SQLite](https://github.com/jparkerweb/mcp-sqlite) | Configure your host MCP endpoint |
| [MCP Alchemy](https://github.com/runekaagaard/mcp-alchemy) | Configure your host MCP endpoint |
| [Tabularis](https://github.com/TabularisDB/tabularis) | Configure your host MCP endpoint |
| [Filesystem MCP](https://github.com/sandraschi/filesystem-mcp) | Configure your host MCP endpoint |
| [MCP Workspace Server](https://github.com/answerlink/MCP-Workspace-Server) | Configure your host MCP endpoint |
| [Terminal Guardian](https://github.com/7Majesty-M/terminal-guardian-mcp) | Configure your host MCP endpoint |
| [FlyEnv](https://github.com/xpf0000/FlyEnv) | Configure your host MCP endpoint |
| [Smart Connections](https://github.com/msdanyg/smart-connections-mcp) | Configure your host MCP endpoint |
| [Code Memory](https://github.com/kapillamba4/code-memory) | Configure your host MCP endpoint |
| [Unreal MCP](https://github.com/GenOrca/unreal-mcp) | Configure your host MCP endpoint |
| [Arcade MCP Framework](https://github.com/ArcadeAI/arcade-mcp) | Configure your host MCP endpoint |
| [Private LLM Server Guide](https://github.com/varunvasudeva1/llm-server-docs) | Companion/setup guide; no server install |
| [DecisionNode](https://github.com/decisionnode/DecisionNode) | Configure your host MCP endpoint |
| [QodeX](https://github.com/QodeXcli/QodeX) | Companion/setup guide; no server install |
| [Deskdrop](https://github.com/SvReenen/Deskdrop) | Companion/setup guide; no server install |

Project documentation checked 2026-09-26. The legacy ComfyUI project advertises that it is unmaintained; its marketplace card says so. No vendor login, PC server deployment, GPU generation, or physical-device UI/inference test is claimed.

## Validation

Debug main/test compilation and 56 focused tests passed (0 failures, 0 errors). Coverage includes delegation budgets, provider selection, cancellation/timeouts, on-device reentry rejection, memory defaults and legacy opt-outs, recall trace redaction, cloud policy, resolver behavior and marketplace classification. Ktlint passed for all changed Kotlin files; `git diff --check` passed.
