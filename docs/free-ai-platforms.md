# Free AI platforms

Settings → AI Platforms → Add → Free provides an account-free provider picker. The same picker is available when editing a Free profile or its provider connection. A profile switch relinks only that profile; a connection change applies to every linked profile. Provider selections survive restarts and configuration backup/restore through the existing connection URL and profile model fields. No database migration is required.

The four presets are intentionally fixed. Credentials, arbitrary model IDs, paid routing, and automatic provider fallback are not used by Free profiles.

| Provider | Transport / model | Availability and limits |
| --- | --- | --- |
| Kilo | OpenAI-compatible SSE, `kilo-auto/free` | Default. Anonymous allowance: 200 requests/hour/IP. Public prompts only. Tool calling supported by the free router. |
| Pollinations legacy | Legacy text GET, `openai` | Text-only, non-streaming fallback. The modern authenticated API is not used. Legacy availability may change; prompts over 6,000 characters produce a clear error. |
| OVHcloud | OpenAI-compatible SSE, `Meta-Llama-3_3-70B-Instruct` | Anonymous allowance: 2 requests/minute/IP/model. Shared-IP limits can cause HTTP 429 even before the app reaches its own allowance. |
| LLM7 | OpenAI-compatible SSE, `mistral-Nemo-Instruct-2407` | Listed with an approval status. Their terms require prior written approval for embedded/downstream access. Disabled unless the approved app build sets `-PfreeLlm7Approved=true`. Server allowances include 1 request/second, 10/minute, 60/hour, and 500,000 tokens/day. |

The request limiter is shared across profiles and tool rounds, serializes each provider's requests, enforces local rolling request windows, and honors `Retry-After`. It does not claim to know other apps' IP usage or the provider's daily token balance. Limits reset locally on process restart; the provider remains authoritative. Quota errors offer a retry or an explicit provider change. Background title generation is skipped to preserve the allowance.

## Memory exception

Free profiles never perform automatic fact capture, fact recall, workspace knowledge lookup, attachment indexing, or memory tool calls. Global memory settings cannot override this policy. Connected MCP tools, local files, location, and model delegation are unavailable; public web search, URL reading, date, and calculation remain available when the provider supports tools. Requests containing attachments stop before contacting a Free provider. Normal conversation history and the explicitly configured system prompt are still sent, so users must keep these public as well.

Known manual/imported free routes also exclude fact capture/recall and workspace memory. This includes `:free`, `openrouter/free`, `kilo-auto/free`, legacy Pollinations, LLM7, and anonymous OVH endpoints. Normal local and paid profiles retain their existing memory behavior.

## Verification and provider references

Regression tests cover the no-memory rule, attachment rejection before transport, private-tool exclusion, provider switching, pinned models, credential removal, legacy GET conversion, and quota cancellation/retry behavior. Live availability is not guaranteed by those tests.

Provider documentation and terms reviewed on 2026-09-26:

- [Kilo gateway models and providers](https://kilo.ai/docs/gateway/models-and-providers)
- [Pollinations API documentation](https://github.com/pollinations/pollinations)
- [OVHcloud AI Endpoints](https://endpoints.ai.cloud.ovh.net/)
- [LLM7 terms, section 3A](https://github.com/chigwell/llm7.io/blob/main/TERMS.md)

Enable the LLM7 build property only after written provider approval. It controls both picker/save availability and the runtime transport guard, including imported configurations.
