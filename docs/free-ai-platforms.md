# Free AI platforms

Settings → AI Platforms → Add → Free provides an account-free provider picker. The same picker is available when editing a Free profile or its provider connection. A profile switch relinks only that profile; a connection change applies to every linked profile. Provider selections survive restarts and configuration backup/restore through the existing connection URL and profile model fields. No database migration is required.

Free profiles use app-owned endpoints and vetted model catalogs. LLM7 supports anonymous access with an optional personal free API token. Other anonymous presets discard credentials when switching providers. The app does not select paid-only models or automatically switch providers. The table below highlights four routes; additional presets are available in the picker.

| Provider | Transport / model | Availability and limits |
| --- | --- | --- |
| Kilo | OpenAI-compatible SSE, `kilo-auto/free` | Default. Anonymous allowance: 200 requests/hour/IP. Public prompts only. Tool calling supported by the free router. |
| Pollinations Classic | OpenAI-compatible POST `/openai`, `openai-fast` alias for the current GPT-OSS 20B route | Anonymous text and streaming. Combined contributions are supported with the server's fixed 1,500-token output allowance. A smaller hard cap cannot be enforced and is rejected. One active text request per IP, with 30 seconds between completed requests. HTTP 402 with Retry-After is a free-slot cooldown. |
| OVHcloud | OpenAI-compatible SSE, `Mistral-Small-3.2-24B-Instruct-2506` | Anonymous allowance: 2 requests/minute/IP/model. Shared-IP limits can cause HTTP 429 even before the app reaches its own allowance. |
| LLM7 | OpenAI-compatible chat; Automatic resolves a current free-allowance model | Anonymous access restored and live verified on October 24 2026; a personal token is optional. Each generation checks for `model_type=chat`, `tier=turbo`, and `usage_based_only=false`. Streaming follows the selected model. The app-owner-approved build gate remains enabled by default. The free-token service advertises up to 100,000 input/output tokens per rolling 24 hours, 1 request/second, 60/minute, and 250/hour; anonymous availability and server limits remain authoritative. |

The request limiter is shared across profiles and tool rounds, serializes each provider's requests, enforces local rolling request windows, and honors `Retry-After`. It does not claim to know other apps' IP usage or the provider's daily token balance. Local request counts reset on process restart; persisted server cooldowns remain until their recorded deadline, and the provider remains authoritative. Known token-scoped limits are isolated by credential, while unknown or IP-scoped restrictions remain shared. Quota errors offer a retry or an explicit provider change. Background title generation is skipped to preserve the allowance.

## Memory exception

Free profiles never perform automatic fact capture, fact recall, workspace knowledge lookup, attachment indexing, or memory tool calls. Global memory settings cannot override this policy. Connected MCP tools, local files, location, and model delegation are unavailable; public web search, URL reading, date, and calculation remain available when the provider supports tools. Requests containing attachments stop before contacting a Free provider. Normal conversation history and the explicitly configured system prompt are still sent, so users must keep these public as well.

Known manual/imported free routes also exclude fact capture/recall and workspace memory. This includes `:free`, `openrouter/free`, `kilo-auto/free`, Pollinations Classic, LLM7, and anonymous OVH endpoints. Normal local and paid profiles retain their existing memory behavior.

## Verification and provider references

Regression tests cover the no-memory rule, attachment rejection before transport, private-tool exclusion, provider switching, free-model eligibility, optional-token preservation and removal, Pollinations POST and fixed-cap admission, and quota cancellation/retry behavior. Live availability is not guaranteed by those tests. See the [Research design and implementation follow-up](design/Research_Mode_Stability_Implementation_2026-10-23.md#appendix-d-main-menu-and-free-provider-implementation) for the current route evidence and implementation scope.

Original provider review: September 26 2026. Pollinations and LLM7 contracts rechecked October 24 2026:

- [Kilo gateway models and providers](https://kilo.ai/docs/gateway/models-and-providers)
- [Pollinations Classic API documentation](https://github.com/pollinations/pollinations/blob/master/APIDOCS.md)
- [OVHcloud AI Endpoints](https://endpoints.ai.cloud.ovh.net/)
- [LLM7 terms, section 3A](https://github.com/chigwell/llm7.io/blob/main/TERMS.md)
- [LLM7 free-model catalog fields](https://docs.llm7.io/guides/models-api)

The app owner confirmed LLM7 integration approval on September 26 2026, and the existing `freeLlm7Approved` build property defaults to true. It still controls picker/save availability and runtime transport, including imported configurations. LLM7's public documentation currently describes token-required access; the ordinary anonymous endpoint was also verified to return HTTP 200 and a model answer on October 24. If the service later requires authentication, the app explains how to add a free token. A token from a paid or topped-up account remains subject to that account's billing rules even when the model is eligible for free access.
