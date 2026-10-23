# Free-provider contract, authentication, and availability repair

Base: `7ba0abd59` (main). Branch: `fix/free-provider-contract-auth-2026-10-22`.

## Behavior

- The vetted Qwen Space adapter checks `/config` and `/gradio_api/info` before sending a generation. It finds the `add_message` dependency, verifies the two supported public parameters and custom components, maps them onto the complete component order, and inserts JSON null only for discovered `state` inputs. Unknown, duplicated, or missing components fail locally. No universal padding is added to other Spaces.
- The simplified Gradio protocol remains paired: `/gradio_api/call/add_message` submission, no custom `session_hash`, and event-ID retrieval from the same call route. No server conversation persistence or mixed `/queue` protocol is introduced.
- A terminal queue error remains a failure even after HTTP 200 and accepted submission. For the Qwen exception-handler update, the adapter retains only a bounded, sanitized diagnostic field for use when a terminal error confirms failure. It never retains the preceding user history as diagnostic context. Ordinary error-like model text does not independently fail a completed generation.
- Two equivalent terminal generation failures open a persisted circuit for that provider/model. The delay grows to at most five minutes. After the deadline, one generation recovery attempt may run at a time. Catalog reachability, job acceptance, and replay of a previously cached answer cannot clear the circuit; a newly completed generation can.
- Existing OpenCode Free profiles retain their identity and historical conversations, but are unavailable for new selection or dispatch. Preflight records `PROVIDER_CLIENT_RESTRICTED` with `requestSent=false`, and the chat offers **Choose another provider** while preserving the draft. New installs do not enable that restricted preset.
- LLM7 uses an explicit free-user-token authentication mode. A profile-owned secret is stored in the existing vault; the shared Free Models connection stays credential-free. Normalization, migration, resolution, the compatible adapter, generation, connection checks, and catalog requests preserve the token. Switching to an anonymous provider removes the profile-owned secret. The provider-approval build gate remains intact.
- LLM7 catalog eligibility requires a chat model with `tier=turbo` and `usage_based_only=false`. Positive usage pricing does not exclude a free-allowance model. Paid-only, Pro, unknown-access, and non-chat entries are excluded. Context-window objects and streaming capability are interpreted. There is no paid-route fallback, and users must supply a free token.
- Persisted cooldowns include the observed time, exact deadline, deadline source, original status/code/category/message, hashed credential identity, known quota scope, and originating failure ID. Old numeric deadlines migrate with unknown cause/scope. Unknown or account/IP scope remains shared; only explicitly token-scoped restrictions are isolated by credential. Later local blocks export the origin even if the source failure occurred in another chat. Retry eligibility is never described as a guaranteed allowance reset.
- Lightweight failure history now includes typed categories, suggested actions, and whether a generation dispatch was attempted. It covers local preflight blocks even with verbose logging off. Temporary chats continue to omit persistent failure history. No prompt-body fields were added.

## Validation coverage

The fixture under `app/src/test/resources/free/gradio` is a minimized snapshot of the deployed Gradio 5.27.0 layout: input components `33` (`antdxsender`), `38` (`antdform`), `60` and `1` (`state`). The public API advertises only `input_value` and `settings_form_value`.

Regression tests cover:

- Full four-position validation, interleaved State components, incompatible inputs, and session/retrieval pairing.
- A generating diagnostic followed by `error:null`, no success answer on terminal failure, credential redaction, and ordinary error-like successful text.
- Circuit opening, queue acceptance not restoring readiness, and one recovery attempt at a time.
- LLM7 missing-token preflight, bearer dispatch, adapter preservation, vault round trip, secret removal on provider switch, authenticated catalog requests, and model eligibility.
- Saved cooldown blocking with zero further HTTP calls, cross-chat causal export, legacy migration, exact deadlines, and credential scope.
- Saved OpenCode selection reconciliation and a ChatViewModel submission that retains the draft without creating a run or queued prompt.

Validation: full debug Kotlin and unit-test compilation passed. The focused Android unit-test task (`*Free*`, `*ProviderAdaptersTest`, `*SettingRepositorySecretMigrationTest`, `*SelectPlatformSelectionTest`, `*ChatPromptQueueTest`) passed all 163 tests, with zero failures or skips. Changed Kotlin files passed ktlint 1.3.1 and `git diff --check`. The local runner used a preloaded Byte Buddy agent because this environment prohibits dynamic JVM attachment.

## External limitations

These changes repair the client contract and eligibility handling. They cannot repair the hosted Qwen application's DashScope backend. No live authenticated LLM7 generation or physical-device UI validation is implied by mocked contract tests. OpenCode Free remains restricted; adding a token does not make that route supported. A free-capable model listing is not proof of a particular account's billing configuration.

## Reference contracts

- Qwen Space: https://huggingface.co/spaces/Qwen/Qwen3-Demo
- Deployed config: https://qwen-qwen3-demo.hf.space/config
- Deployed public API: https://qwen-qwen3-demo.hf.space/gradio_api/info
- Gradio 5.27.0 input validation: https://github.com/gradio-app/gradio/blob/gradio%405.27.0/gradio/blocks.py
- Gradio 5.27.0 call routes: https://github.com/gradio-app/gradio/blob/gradio%405.27.0/gradio/routes.py
- LLM7 catalog fields: https://docs.llm7.io/guides/models-api
- LLM7 limits: https://docs.llm7.io/limits
- LLM7 token setup: https://github.com/chigwell/llm7.io
- OpenCode restriction report: https://github.com/anomalyco/opencode/issues/49621
