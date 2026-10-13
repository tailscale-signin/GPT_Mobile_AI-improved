# OpenRouter provider workspace

Implemented on `feat/local-ai-pricing-research-2026-10-12`, continuing the branch based on `version-v0.9.50.0`. The supplied design is preserved in `docs/design/OpenRouter_Provider_Upgrade_Design_Implementation_v0.9.50.0_2026-10-12.md`.

## User experience

- The official OpenRouter connection editor opens a themed Setup / Routing / Account workspace with icons, contextual actions, separate scroll positions, draft controls and accessible tab semantics. Generic compatible gateways retain their connection editor.
- Routing and Account remain disabled at exactly one 0.50 opacity layer until an explicit, successful current-key validation. A stored key, a long-looking string, an empty successful response, or a restored navigation argument cannot unlock the tabs.
- Setup provides connection selection/creation, masked key entry, explicit paste/reveal, validation, cancellation, replacement and disconnect. Unsaved replacement keys do not change the working connection. Diagnostics omit credentials and account identity.
- Routing includes an effective per-profile policy preview, searchable provider choices, ordered preferences with move buttons, exact provider slugs, allow/exclude controls, strategy, fallback, supported parameters, price ceilings, privacy/ZDR, distillable text, quantization and scalar performance preferences. Existing profile extras remain in profile settings.
- Eligible model prices use exact decimal USD-per-million conversion and accessible green text. The model picker displays prices and metadata-backed tool/reasoning/structured-output labels. Provider endpoint comparisons load on demand; freshness and unavailable models are identified. External model details and profile actions are provided.
- Prompt-cache stickiness, OpenRouter response caching and the local queue-result cache have separate controls. Remote and local TTLs are independent. The one-shot fresh-response action bypasses response-cache reuse on the next inference round. Benchmarks and requests with tools also bypass it.
- Account separates this key's allowance, effective budget, overlapping usage periods, BYOK usage and returned free-request quotas. It includes a masked key label, role, expiry, free-tier status and optional organization/workspace/region details. A zero or overdrawn allowance is valid authentication with exhausted funds. Missing amounts are never fabricated as zero.
- This-device receipts identify model, execution role, status, timing, token counts, cache metadata and confirmed versus pending cost. The history is explicitly scoped to this connection, including earlier keys; it is not an account-wide history.
- Optional account-wide access uses a separate, session-only management credential and allowlisted GET resources. Its wallet and delayed activity table stay in an isolated panel. The credential has broad authority; the UI explains that this is not a read-only key. Backgrounding, leaving the screen or switching connections clears the session.
- The batch panel provides queue controls, workspace job status/counts/progress, explicit refresh, result export, and confirmed cancel/delete actions. It states workspace scope and retention limits. Interactive chats are not automatically converted into background batch jobs.

## Ownership and request behavior

| Concern | Implementation |
|---|---|
| Credential authority | The existing provider connection and SecretVault remain authoritative. Portable OpenRouter preferences never contain an active key. |
| Validation races | Attempt cancellation plus endpoint/credential compare-and-replace prevents a stale validator from installing a key over a later edit. Connection deletion shares the mutation mutex. |
| Draft isolation | Account refresh always uses the saved credential and endpoint, preserving an unsaved replacement. Failed replacement validation preserves the working connection. Settings apply preserves newer draft edits. |
| Preferences | Connection-specific, versioned records with optimistic revision checks. Legacy defaults migrate automatically only for one unambiguous OpenRouter connection; otherwise the user selects which connection inherits them. Legacy plaintext key fields are stripped on migration. |
| Routing | One resolver combines provider defaults and profile options. Convenience choices can be overridden; hard allowlists, price limits and privacy restrictions can only become stricter. Conflicts fail before transmission. |
| Execution | The manually constructed chat adapter now actually receives the settings repository. Ordinary, regenerated, combined/synthesis, delegate/reviewer/helper and benchmark requests share the resolver. Streaming and nonstreaming completion transport records receipts. |
| Session identity | Opaque hashes include connection, conversation, profile and execution lane; titles and personal identifiers are not used. |
| Catalogs | A shared repository maintains bounded user-specific caches keyed by connection, endpoint and credential revision. Public catalog fallback is limited to unconfigured access. |
| Native batches | Only supported policy is serialized at envelope level before requests. Unrepresentable hard price/privacy/parameter restrictions and no-remote-storage policy reject submission. |
| Background queue | WorkManager stores explicit connection/revision/model identifiers, not raw keys. Rotation and disabled batching stop stale work. Cache identity includes connection, endpoint, credential revision, model, complete request and routing policy. |
| Retry and output | Only explicit throttling is retried by the worker; an ambiguous charged request is not replayed. Large results are written atomically to a private result file rather than overflowing WorkManager Data or being truncated. |
| Local execution | Failed llama.cpp batches stay local. Both legacy automatic OpenRouter fallback paths were removed; cancellation propagates. |

OpenRouter account and batch clients use approved HTTPS origins, bounded reads, timeouts, sanitized errors and disabled redirects. The shared authenticated network client also no longer follows redirects; deployments using redirect-only API URLs must configure their final endpoint explicitly.

## Persistence and receipts

Receipts retain at most 500 recent records across connections in the existing DataStore, avoiding a new Room schema or an invented database migration. Generation IDs deduplicate repeated telemetry; distinct retried generations remain separate. Final usage-only chunks are captured. Cancellation and missing cost remain explicit. Telemetry persistence failure cannot interrupt an answer or trigger a charged retry.

Pending-cost reconciliation is explicit, bounded to five records per action, and uses only the matching credential revision. It looks up generation metadata and never regenerates the answer. Scoped queue-cache count and clear operations use the existing Room table. Management sessions, validation grants and draft credentials are never persisted.

## Verification and release qualification

- **111 targeted tests pass:** 93 pure Kotlin regression/contract tests and 18 account, storage, receipt, race and batch tests. Coverage exercises policy precedence, endpoint validation, schema validation, exact money, scoped cache/session identity, credential replacement races, DataStore ownership/migration, receipt capture/deduplication and batch behavior. All fixtures use synthetic credentials.
- The workspace, model picker, ViewModels, account HTTP client, preferences, policy, receipts, batch clients/queue and supporting DTOs compile with Android API 37 and real Compose, Material, Lifecycle, DataStore and OkHttp libraries. The standalone compilation uses small stubs only for unrelated application boundaries/annotations and generated resources; it is not a full application build.
- An execution-role adapter integration test and updated WorkManager enqueue test are included for the normal Android suite. These require the full build and were not executed by the standalone harness.
- Resource preflight and ktlint 1.3.1 pass on the changed sources. `git diff --check` passes.
- The normal `:app:compileDebugKotlin` attempt remains blocked during plugin resolution for `com.android.legacy-kapt:9.4.1`, before application Kotlin compilation. Project dependency pins were not changed to work around that blocker. The standalone compiler is Kotlin 2.4.10; the project declares 2.4.20.
- No live credential, paid inference, authenticated account mutation, full APK build or physical-device acceptance was performed. Hilt/Room code generation, full integration tests, exact rendered-opacity checks, TalkBack, RTL, 200% font scaling, dynamic palettes, rotation/process recreation and frame/scroll timing still need the normal Android build and a device.

Optional design extensions left for a follow-up are per-device notification spending reminders, a percentile-performance editor, and workspace-filtered management charts. Scalar performance controls and a separate activity table are present. No account administration, billing purchase or broad management mutation was added.

## Contract references

Official documentation checked during implementation:

- https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key
- https://openrouter.ai/docs/guides/routing/provider-selection
- https://openrouter.ai/docs/guides/features/response-caching
- https://openrouter.ai/docs/guides/best-practices/prompt-caching
- https://openrouter.ai/docs/api/api-reference/models/list-models-filtered-by-user-provider-preferences-privacy-settings-and-guardrails
- https://openrouter.ai/docs/api/api-reference/endpoints/list-all-endpoints-for-a-model
- https://openrouter.ai/docs/batch-quickstart

The supplied design contains the full source register. API compatibility and live regional/account entitlements must be qualified with an authorized test account before release.

## Rebase and integration review — October 12, 2026

Rebased all four feature commits onto the fetched `origin/version-v0.9.50.0` at `51358adff3ba44161de549961fc58a89976c09eb`, incorporating ten newer target commits. Rebase completed without conflicts. Range comparison confirmed that the replay retained the feature changes alongside the target's localization, statistics attribution, read-only delegation admission, benchmark ownership/cleanup and catalog qualification fixes.

The second review fixed:

- An out-of-scope `profile` reference in multi-profile benchmark execution. Each run now records the billing origin of its actual `target`.
- Regional OpenRouter host recognition in pricing and canonical model identity. Official global/EU/US routes share billing/model identity while retaining separate deployments. Lookalike and custom hosts remain unrecognized.
- Credential rotation retaining scoped panels or a stuck loading state. Successful identity changes clear eligible catalogs, endpoint comparisons, provider lists, batch jobs and management sessions. Late endpoint failures for the old key cannot mark the new connection as failed.
- Trailing whitespace in imported design documents, preserving Markdown hard line breaks with explicit backslashes.

Verification on the rebased sources: **115 targeted Kotlin tests pass** (96 core/contract regressions and 19 account/storage/race/batch tests), and **92 repository Python tests pass**. The focused Android 37/Compose compilation passes with Java 21. Changed-source ktlint, 28 resource XML checks, 452 literal regex checks, committed Room schema history, the 31-producer statistics coverage manifest and branch-wide whitespace checks pass. Python native/instrumentation messages are fixture output, not new device execution.

The Java 21 full Android compile attempt still stops before source compilation because `com.android.legacy-kapt:9.4.1` cannot be resolved in this environment. The target branch's prior CI results do not establish full-build success for this feature branch. Full integration and device acceptance remain required. This review does not merge or publish either branch.
