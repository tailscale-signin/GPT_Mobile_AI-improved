# NVIDIA Hosted AI Provider — Integration Implementation

Date: 2026-10-16
Branch: `feat/nvidia-provider-complete-integration-2026-10-16`
Target: `GPT_Mobile_AI-improved` / Kotlin Android

## Scope and architecture

This completes the existing `ClientType.NVIDIA` path rather than adding a duplicate provider. NVIDIA chat, tool calls, Combined Mode, benchmarking, remote delegation, research, and Local Assist retain the shared `ChatRepositoryImpl` and `OpenAICompatibleAdapter` infrastructure.

- Account keys continue in reusable `ProviderConnection` records and `SecretVault`; UI only briefly accesses them to list models.
- `NvidiaEndpoints` normalizes hosted base URLs, prevents duplicated operation paths, and rejects credentials in URLs.
- `NvidiaModelCapabilities` carries **conservative**, documented capabilities. Unknown values remain unknown, never silently inferred from a publisher or model name.
- `NvidiaRequestPolicy` applies GPT-OSS low/medium `reasoning_effort` and documented Nemotron-3 `chat_template_kwargs.enable_thinking` controls. GPT-OSS does not offer fully disabled reasoning; low is its documented minimum.
- Existing OpenAI-compatible SSE streaming, tool-call assembly, usage accounting, cancellation, output limits, retry/cooldown, and preconnection infrastructure are reused.
- NVIDIA streams use the existing reasoning parser for `reasoning_content` and `<think>` segments to avoid putting recognized reasoning in the visible answer.
- Known text-only models reject images before dispatch.
- `NvidiaModelRepository` loads `/v1/models`, using an in-memory ten-minute cache partitioned by endpoint and hashed credential. No credentials or inferred entitlements are persisted in the catalog.
- `NvidiaModelPickerDialog` is available in Add Profile, initial setup and Edit Profile. Suggested model IDs are not reported as account-verified, and manual model ID entry always remains possible.
- Connection Doctor distinguishes discovery from inference. The separate opt-in POST uses a bounded 64-token request and may consume API credits. It is never run automatically.

## Source of truth

- Provider type: `data/model/ClientType.kt`
- Defaults: `data/ModelConstants.kt`
- Transport: `data/network/OpenAIAPIImpl.kt`
- Adapter: `data/agent/provider/ProviderAdapters.kt`
- NVIDIA-specific policy and discovery: `data/nvidia/`
- Credential storage: `data/repository/SettingRepositoryImpl.kt`
- Model UI: `presentation/ui/setting/NvidiaModelPickerDialog.kt`
- Connection Doctor: `presentation/ui/setting/ConnectionDoctorViewModel.kt`
- Combined/delegation/research execution: `data/repository/ChatRepositoryImpl.kt`

No Room schema or `ClientType` ordinal modifications are required.

## Implementation and QA matrix

| Path | Implementation | Check |
| --- | --- | --- |
| New NVIDIA connection | Existing secure provider connection and default endpoint | Add, save, reopen and replace key |
| Model discovery | Live /models, keyed cache, suggestions on failure | Two different keys, refresh, manual ID |
| Ordinary chat | Existing OpenAI-compatible adapter | Verify text and `[DONE]` handling |
| GPT-OSS reasoning | Model-aware low/medium effort | JSON wire assertions |
| Nemotron reasoning | Template `enable_thinking` on/off | JSON wire assertions |
| Tool/MCP cycles | Shared executor and tool-call ID replay | Existing ProviderAdapters/tool suite |
| Research/delegation | Existing role-isolated provider session | Existing research/delegation suite |
| Combined Mode | Existing profile/source attribution | Existing Combined Mode suite |
| Image handling | Reject known text-only model requests | Admission regression test |
| Diagnostics | Existing /models inspector + opt-in minimal chat POST | Local HTTP fixture |
| Pricing | Existing provider identity and manual reviewed overrides | Never assume free or invent model pricing |
| Cold-start | Existing `ProviderTransportWarmer` and pooled transport | No automatic billed generation |

## Operational constraints

1. NVIDIA model IDs, their capabilities and account entitlements can change. Known capability rules must be reviewed against official NVIDIA documentation and the live provider response.
2. A successful `/models` request is **not** proof that `/chat/completions` works. Use opt-in Connection Doctor inference testing when the account owner authorizes it.
3. No NVIDIA API key is bundled, uploaded or needed for automated unit tests. Actual paid-hosted smoke testing remains environment-dependent.
4. Never strip authorized tools or silence unsupported-tool errors merely to produce a text-only answer; preserve tool budgets and the original request's intent.
5. Do not add manufacturer-specific inference behavior for arbitrary OpenAI, Groq, Ollama, or OpenRouter profiles.
6. NVIDIA hosted API inference is distinct from self-hosted NVIDIA NIM or local NVIDIA GPU/QNN acceleration.

## Validation and merge gate

Run `./gradlew test`, `./gradlew assembleDebug`, Kotlin lint 1.3.1, and applicable Android acceptance tests. Verify one real NVIDIA credential on the opt-in chat probe if available. Merge only after required checks are green, with any unsupported NIM model capability explicitly documented as unknown.

Official model-specific guidance:
- https://docs.api.nvidia.com/nim/reference/openai-gpt-oss-20b-infer
- https://docs.api.nvidia.com/nim/reference/nvidia-nemotron-3-super-120b-a12b
- https://docs.api.nvidia.com/nim/reference/nvidia-nemotron-3-ultra-550b-a55b
