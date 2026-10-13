# GGUF + Local Assist Integration design

GPT Mobile AI-improved | Repository investigation and implementation specification

> Decision: add a qualified GGUF runtime to the normal application, make Local Assist prepare and use that runtime when appropriate, and route its output and token usage into the existing diagnostics.

## Verified baseline

Repository: tailscale-signin/GPT_Mobile_AI-improved
Branch inspected: main
Commit: 1d4dcd8698356abddc5f1be946bb0811017b7d7a
Version in source: 0.9.51.0 (120)
Normal package: dev.melo.gptmobile.improved
Investigation date: October 13, 2026 [R1, R2]

## What the error actually means

The exact message is thrown by LocalImportPreview.inspect() when GGUF is detected and the GenieX build flag is false. The installer repeats the same restriction. This happens before native inference; it is not evidence that the selected model is corrupt. The normal and GenieX builds deliberately use different application IDs and native-library selections. [R2–R4]

## What is already implemented

The repository already has GGUF import validation, manifest routing, a GenieX adapter, read-only Local Assist processing, and an invocation ledger. The work should extend these components rather than install a parallel, untracked inference pipeline. [R4–R10]

## Completion boundary

This document records a static source investigation and an implementation plan. No application code was changed, no APK was built, and no device run was performed. The user’s model file and current device log were not supplied. All acceptance targets below are proposed tests, not reported results.

---

# 01 | Findings: loading and assistance

## F01 / P0 — The installed-build gate blocks GGUF

Confirmed: LocalImportPreview checks the geniex argument before accepting a GGUF header. GgufImportAdapter.install() separately requires BuildConfig.GENIEX_ENABLED. Changing only the displayed error or removing only the preview check leaves the installation/runtime problem unresolved. Replace these checks with actual packaged-runtime capability checks only after an engine is present. [R3, R4]

## F02 / P0 — Combining both full native distributions is unsafe

Confirmed: app/build.gradle.kts selects src/geniex/kotlin or src/litertOnly/kotlin and changes the package ID. Its comments explicitly prohibit merging competing QAIRT distributions through pickFirst. Preserve the existing LiteRT/QNN distribution while adding GGUF; do not globally turn on geniexRuntime and call that an in-place repair. [R2]

## F03 / P1 — Local Assist is wired, but activation is conditional

Confirmed: ChatRepositoryImpl.completeChat() passes authorized tools into LocalAssistToolBinder for non-LITERT_LM primary profiles. The binder wraps only shareableReadOnly tools. AssistCoordinator processes qualifying returned evidence; it is not a preprocessor for every remote prompt. A small result, a failed tool, or a tool-free exchange may produce no local generation. This is legitimate when explained to the user. [R7–R9]

## F04 / P1 — Cold models and open conversations prevent assistance

Confirmed: runtimeEligibleForAssist() requires an existing engineSpec for the same selected path, a non-busy runtime, no open conversation, acceptable memory admission, and no throttling. It does not initiate loading. Therefore an enabled Assist toggle alone cannot make an unloaded model help. Replace the Boolean-only readiness interface with explicit readiness states and an admitted preparation step. [R8]

## F05 / P1 — Repeated assistance needs a session-ownership test

Inferred risk: the readiness check rejects open conversations, while the provider adapter keeps successful conversation state for reuse. Verify whether an assist session remains open and blocks subsequent tasks. Introduce explicit assist-owned session cleanup; never close an interactive local chat to make an optional helper eligible. [R8, R11]

---

# 02 | Findings: output and token accounting

## F06 / P1 — Assist bypasses the streamed helper-preview wrapper

Confirmed: the Assist binder calls delegateToProfile(... requestRole = "assist", allowTools = false) directly. The separate generateDelegate() wrapper emits ApiState.DelegationText and passes a text-progress callback. The Assist call does not use that wrapper or provide an equivalent preview callback. Counts and logs may exist while generated helper text is absent from the helper UI. Add a shared observable child-invocation wrapper without giving Assist worker tools. [R8, R9]

## F07 / P1 — Native metrics do not feed the ledger’s usage branch

Confirmed: GenieX onComplete produces NativeInferenceMetrics with promptTokens and generatedTokens. The inspected provider completion path emits ProviderEvent.LocalMetrics. InvocationLedger accumulates ProviderEvent.Usage, but its event switch does not consume LocalMetrics. Without a normalization step, the ledger falls back to byte-based estimates rather than using those native counters. [R6, R10, R11]

## F08 / P1 — Failed completion can hide available metrics

Confirmed: LiteRtLmAdapter stores latestMetrics, then emits them only inside if (!failed). GenieX emits metrics before an output/context-limit Error. A request can therefore generate useful text and native counts, fail at a limit, and lose the metrics at the adapter boundary. Publish final usage independently of success and preserve the completion reason. [R6, R11]

## F09 / P1 — Retaining only the latest metrics risks undercounting

Confirmed structure; scenario requires a regression test: the provider stores a single latestMetrics value, including a further generation after a local tool-limit event. Earlier native segments can be replaced. Accumulate distinct segment IDs, count each physical generation once, and keep retries separate. Do not sum repeated cumulative snapshots. [R11]

## F10 / P2 — Error classification and visibility need refinement

The importer maps ordinary exceptions, including missing-build capability, to INVALID_MODEL. The ledger already retains request kind and estimates, and respects LocalDiagnosticsPolicy. Add runtime-specific failure reasons, per-field count provenance, Assist skip reasons, and role-aware views. Diagnostics must explain unavailable work without inventing zero-cost token savings. [R4, R10]

---

# 03 | Runtime integration decision

## Recommended production architecture

Keep dev.melo.gptmobile.improved as the upgrade identity. Add a dedicated QAIRT-independent llama.cpp GGUF runtime module to the normal application. Ship a qualified CPU path first and enable GPU only for a tested build/device combination. Continue using the existing LiteRT/QNN path for compatible LiteRT-LM packages. This separates file-format support from the GenieX preview flag.

The official llama.cpp Android implementation demonstrates app-private model loading, GGUF metadata inspection, chat-template handling, and token collection through Kotlin Flow. It is a suitable implementation reference, not proof that every GGUF architecture will work in this application. Pin an immutable engine revision and test it. [U2]

## Why not simply merge the GenieX preview?

The current adapter is pinned to GenieX 0.8.0, has explicit tool/thinking/speculative-decoding restrictions, and belongs to a different native-library configuration. These restrictions must remain truthful until separately qualified. A normal-app GGUF release does not imply support for every compiled GenieX QAIRT bundle or GGUF-on-NPU. [R2, R5]

## Alternative allowed only after a packaging qualification

Upstream GenieX describes dynamically loaded llama_cpp and qairt backend plugins. A demonstrably llama_cpp-only Android artifact could reuse the current adapter while excluding the conflicting QAIRT stack. Treat this as an alternative build investigation, not an already verified property of the pinned AAR. The default implementation remains the independent GGUF module unless that alternative passes all dependency and device gates. [U1]

```text
Imported model + validated manifest
  -> Runtime capability registry
  -> Existing LocalRuntime abstraction / process boundary
     -> LiteRT-LM / existing QNN
     -> New GGUF CPU / qualified GPU runtime
     -> GenieX QAIRT preview (separately qualified)
  -> Existing provider adapter
  -> Shared invocation ledger + diagnostics
```

## Non-negotiable packaging gate

Audit every packaged native library by ABI, SONAME, hash, transitive dependency and symbol ownership. Reject conflicting duplicates; do not hide them with pickFirst. Verify minimum-device support, release R8/JNI retention, Android page-size compatibility and cancellation. A process boundary alone does not fix conflicting files within one APK.

---

# 04 | Import, routing and compatibility

## One capability model from import to execution

Add a RuntimeCapabilityRegistry that reports PACKAGED, UNAVAILABLE_FOR_ABI, DISABLED, UNQUALIFIED and LOAD_FAILED states for each runtime/backend. Import preview, marketplace filtering, profile readiness, runtime routing and diagnostics must consume the same snapshot. Keep the legacy ClientType.LITERT_LM profile identifier initially; use a new isOnDeviceProfile() helper rather than immediately rewriting stored provider identities.

## Preserve the existing importer’s safety and data

Retain bounded metadata parsing, SHA-256 verification, staged copying, cancellation cleanup, free-space checks and atomic installation. Continue rejecting unsupported shard/projector combinations unless an explicit manifest provides their required assets. Do not rename a .gguf file to .litertlm. Add a runtime-specific compatibility check for architecture, quantization, context, template and companion assets. [R4]

The current importer creates a schema-2 manifest pointing to model.gguf, stores GENIEX_LLAMA_CPP and returns a record pointing to manifest.json. Keep that managed layout. Add a versioned, backward-compatible schema for a neutral LLAMA_CPP runtime and explicit engine requirements. Existing manifests remain readable and are never destructively overwritten. [R4]

## Migration and routing rules

For an existing GENIEX_LLAMA_CPP manifest, allow the new engine only after inspecting the artifact and confirming compatible GGUF semantics. Retain original engine/version information as provenance; do not treat GenieX-specific capabilities or qualification as portable. Leave GENIEX_QAIRT manifests on their existing route. Unknown runtime enums, future schemas, and missing assets return a typed error, not an automatic LiteRT fallback.

Extend LocalRuntimeRouter’s manifest-based dispatch and its actual-backend state. Record requested backend, selected backend and any fallback reason. CPU fallback is allowed only within a compatible GGUF engine when enabled by the user. Preserve current format-aware QNN fallback behavior. [R7]

## User-facing outcomes

Supported import: “GGUF model ready · CPU” or a verified GPU label. Missing native module: “This app build does not include a GGUF runtime.” Unsupported export: identify the architecture, tensor type or missing asset. Low resources: offer a smaller model/context without declaring the file invalid. Advertise a backend as “available” only after packaging and device readiness checks.

---

# 05 | Make Assist activation operational

## Explicit states, not a silent Boolean

```text
OFF -> CHECKING -> READY -> QUEUED -> RUNNING
                -> PREPARING -> READY
                -> UNAVAILABLE(reason)
RUNNING -> SUCCEEDED | PARTIAL | FAILED | CANCELED
Any eligible decision may become SKIPPED(reason).
```

Resolve the selected local profile by stable profile ID and model digest. Report missing selection, unloaded model, changed configuration, insufficient memory, thermal throttling, an interactive session, an unsupported runtime or revoked policy separately. Validate model path, actual backend, context and tuning, not path identity alone.

## Admitted automatic preparation

When Assist is enabled, prepare the selected runtime only after existing hardware/workload admission succeeds. Add “Prepare automatically” and “Warm only” policies. Preparation must not evict a live interactive local conversation. Use the existing engine lock, process runtime and cancellation mechanisms; never create a second uncontrolled native instance to bypass contention.

Use a bounded queue wait and a separate task deadline. Recommended initial defaults are a 2-second optional queue wait and a 10-second transform deadline, adjustable within supported limits. Loading has its own cancellable deadline and visible status. These are proposed starting values, to tune with device measurements, not performance claims.

## Dedicated helper session lifecycle

Create an assist-owned conversation with no worker tools and no recursive delegation. Close that conversation in a non-cancellable cleanup block while retaining the loaded engine where safe. Keep owner tokens for interactive chat, assist, benchmark and memory jobs. Cancellation can close only the session it owns. An interactive owner takes precedence over an optional task.

## Permission and policy rechecks

Preserve current profile/configuration rechecks before processing and before sharing derived output. Do not broaden access by changing memory exclusions: replace unrelated gating only with explicit equivalent data-sharing policy. Turning Assist off cancels its owned work, rejects late results and permits the remote request to continue from authorized original evidence. [R8, R9]

## No fabricated activity

A simple remote question with nothing useful to offload may show “Assist ready · no eligible task.” It should not load or generate merely to make a token counter move. Disabling diagnostics must not disable assistance, and enabling diagnostics must not authorize additional data access.

---

# 06 | Ensure local work benefits remote models

## Phase-one tasks: keep the successful existing design

Retain exact-record selection, retrieved-text summarization and query suggestions over already authorized read-only outputs. Keep original evidence available for recovery. Derived summaries are unverified data, never higher-priority instructions. Preserve source IDs, URLs, uncertainty and conflicting evidence. Do not silently drop unique facts while compressing. [R9]

## Delivery is part of success

Track three separate outcomes: generated, validated, and consumed by the intended remote request. A helper can generate valid text after the parent deadline; that is generated-but-unused, not effective assistance. Attach a handoff ID and parent run ID to the evidence supplied to the remote session. The receiver records whether it included or rejected that handoff.

Define a typed AssistHandoff with task kind, model identity, source IDs, coverage, omissions, structured output, validation status and usage reference. Validate numeric selection against exact candidate IDs. Validate summaries/query suggestions with bounded JSON and source checks. Do not require native constrained decoding for an engine that does not support it; parse and validate text output, then use the original result on failure.

## Optional extensions after the repair

Add explicit attachment extraction/summarization and long-context compaction hooks before the remote request, using existing access and cloud-sharing permissions. Treat task decomposition as an advisory plan, not new tool authority. These are additional features; the existing read-only result wrapper does not establish that these paths are already implemented.

## Combined mode and delegated work

Use one physical local task per identical, permitted evidence transformation. A cache key includes turn scope, normalized source digest, goal, model digest, sampler/prompt version, policy revision and effective authority. Different privacy scopes cannot share a result. Each remote consumer receives a separate handoff record; the local token usage remains attached to one physical invocation.

Use reference-counted subscribers for shared work. Canceling one remote profile must not cancel a local task still needed by another. Cancel the worker when all subscribers leave or authority is revoked. Do not allow Assist -> delegate -> Assist recursion, including tools inherited by delegated workers.

## Demonstrate benefit rather than assuming it

Compare remote input tokens, end-to-end latency, retained source coverage and correctness against an Assist-off run using the same evidence. A reduction in transmitted evidence tokens is measurable; a reduction in billed provider tokens requires provider usage from comparable runs. Do not equate local output tokens with remote tokens saved.

---

# 07 | Unified usage and event contract

## Extend the existing ledger; do not duplicate it

delegateToProfile already wraps its session with InvocationLedger using requestRole and profileUid. Keep that integration. Normalize native usage at the provider boundary into ProviderEvent.Usage with source metadata; keep ProviderEvent.LocalMetrics for timings and engine details. The ledger counts Usage exactly once and does not independently add the same LocalMetrics counters. [R8, R10, R11]

## Identity and dimensions

Allocate a canonical invocation ID before dispatch and share it with provider constraints, UI events, ledger rows and logs. Retain attempt ID, segment ID, parent run ID, turn key and consumer handoff IDs separately. Store role=assist, logical provider, actual runtime/backend, profile ID, model ID/digest, engine revision and terminal status. Avoid merging two different local quantizations simply because their display names match.

## Token semantics

Store inputTokens and outputTokens with independent provenance: native exact, provider reported, tokenizer computed, estimated or unavailable. Text callbacks are chunks, not necessarily individual tokens. Bytes/3 remains only an explicitly labeled fallback estimate. Unknown counts are null, not zero. Do not reject valid token counts solely because an optional speed/timing metric is unavailable.

Define every usage update as a segment delta or a segment cumulative snapshot. For cumulative snapshots, replace with the latest nondecreasing total; for deltas, add once using event sequence identity. Final exact usage replaces estimates for that segment. Retries and continuations create new physical segments and count the work they actually performed.

```text
Local runtime: TextDelta / Metrics / terminal reason
  -> Provider adapter normalizes Usage(segment, source)
  -> InvocationLedger persists and aggregates
  -> Debug UI / model comparison / structured export
  -> AppLogRecorder receives a redacted summary
```

## Useful metrics, honestly labeled

Show generated tokens, prompt tokens, output estimate while streaming, first visible answer latency, native decode time and tokens/second when available. Separate queue wait, loading, prefill and decoding. Keep total token throughput distinct from decode speed. Local provider API cost can be zero; device compute, memory and energy are not thereby free.

---

# 08 | Streaming diagnostics and terminal reliability

## Show Assist beside every other model

Add a role-labeled Assist row to the existing debug model list and token comparison. Display profile/model, engine/backend, task, status, generated output count, usage provenance and parent remote profile. Expand the row for bounded streamed output, timings, source coverage and failure/skip details. Use a clear icon plus text; color must not be the only status indicator.

Unify generateDelegate and Assist observation through a reusable child-invocation observer. It must not alter tool permissions, worker prompts or the primary answer. Add an Assist output event or extend the existing delegation-text event with a role. Keep UI snapshots throttled and bounded; send a reliable final snapshot. User-visible helper output is not the remote model’s answer. [R8]

## Do not lose usage on partial, failed or canceled work

Replace the single latestMetrics slot with a segment accumulator. Emit available usage before reporting terminal failure, and finalize every dispatched attempt exactly once. Length/context-limit output is PARTIAL with preserved text, usage and finish reason. Native failure may still have known counts. Cancellation preserves measured usage already received and labels missing final counts as estimated or incomplete.

Make terminal usage/control delivery reliable independently of the text queue. The current GenieX callback queue deliberately fails rather than silently dropping accepted text when full; retain that transparency. Add a terminal side channel or accumulator so a full text queue cannot also discard the final counters. Native stop, drain, session cleanup and unload must respect engine ownership. [R6]

## Persistence and export

Extend the existing accounting schema and StructuredDiagnosticExport, with an explicit export schema version. Correlate UI, JSON/JSONL and log records by invocation ID. Persist final accounting atomically and recover abandoned RUNNING rows as interrupted. Diagnostic recording errors must not crash inference; enforced token/spend reservation failures must still fail closed rather than silently allowing unbounded work. [R10, R12]

## Privacy defaults

Retain diagnostics opt-out. Token counts and redacted metadata do not require storing full prompts or output. Make content capture an explicit diagnostic option with bounded retention and a clear export warning. Apply existing redaction to secrets, private source text and file paths. Do not add cloud telemetry or record hidden reasoning by default.

> Example acceptance fixture, not a real run: native input=240 and output=37 must produce one Assist row, 37 output tokens in export, and 37 physical local output tokens even when two remote profiles consume the same handoff.

---

# 09 | Implementation map: runtime and Assist

Paths below are relative to app/src/main/kotlin/dev/chungjungsoo/gptmobile/ unless marked otherwise. “New” identifies proposed files, not files found during inspection.

## WP1 — Package a real GGUF engine

Modify app/build.gradle.kts and the existing build-specific NativeArtifactRuntimeFactory integration. Add new :local-gguf-runtime module and GgufRuntimeAdapter. Retain the normal package identity and existing LiteRT/QNN libraries. Qualify CPU, then GPU. Native-library inventory and release smoke tests block publication. Do not make a successful preview build a substitute for a working normal APK. [R2, R5]

## WP2 — Make import and routing runtime-aware

Modify data/localmodel/LocalImportPreview.kt, GgufImportAdapter.kt and ModelArtifactManifest.kt; extend data/localruntime/LocalRuntimeRouter.kt. Add new data/localruntime/RuntimeCapabilityRegistry.kt. Preserve staging/digests and current manifest routing; add typed import failures and backward-compatible runtime resolution. Test the same artifact through preview, import, load and profile selection. [R3, R4, R7]

## WP3 — Admit and own Assist work

Modify data/repository/ChatRepositoryImpl.kt at runtimeEligibleForAssist(), completeChat() and delegateToProfile(); modify data/assist/LocalAssistToolBinder.kt and AssistCoordinator.kt. Add new AssistReadinessResolver.kt and AssistInvocationObserver.kt in data/assist. Reuse LocalWorkloadAdmission, the runtime lock and existing process/service lifecycle rather than creating another scheduler outside them. [R8, R9]

## WP4 — Make handoffs measurable

Add new data/assist/AssistHandoff.kt and a scoped task-result store, or extend the existing coordinator cache where appropriate. Preserve source retention and consent checks. Track physical worker usage separately from each consumer. Enforce no-recursion and side-effect-free helper requests in direct, delegated and combined flows.

## UI integration rule

Connect the existing Local AI Assist settings page and debug screens to these domain states. Locate their current composable/ViewModel consumers by symbol during implementation; this audit does not invent uninspected screen filenames. Keep settings for preparation policy, local profile, task kinds, deadlines and output capture together. Add “Test Assist” using a deterministic fixture, not a paid remote request without disclosure.

---

# 10 | Implementation map: accounting and rollout order

## WP5 — Normalize and preserve usage

Modify data/agent/provider/LiteRtLmAdapter.kt, data/accounting/InvocationLedger.kt and ModelTokenComparison.kt; extend ProviderEvent/LocalRuntimeEvent contracts where required. Modify the GenieX adapter under app/src/geniex/kotlin/.../data/localruntime/GenieXRuntimeAdapter.kt. Add new NativeUsageNormalizer.kt and tests. The new GGUF adapter must implement the same normalized contract. [R6, R10, R11]

Audit ProcessLocalRuntime.kt, LocalInferenceService.kt and data/localruntime/ipc for serialization of new fields, segment identity and final metrics. These files exist in the repository; their full IPC behavior was not audited here. Round-trip tests must prove preservation across the actual inference-process boundary, including Binder/service death.

## WP6 — Diagnostics and migrations

Extend data/diagnostics/StructuredDiagnosticExport.kt and the existing AppLogRecorder/redactor integration. Add migration coverage for invocation fields and export compatibility. Trace all UI consumers of InvocationLedger.diagnostics and model comparison, ensuring role=assist is neither excluded nor relabeled as the primary remote model. Do not replace existing accounting with log parsing. [R10, R12]

## Recommended pull-request sequence

PR1: regression fixtures, typed readiness/failure states and usage normalization on current runtimes. PR2: isolated GGUF module, native audit, manifest migration and normal-app import/load. PR3: preparation policy, session ownership and observable handoffs. PR4: role-aware debug UI/export and end-to-end device acceptance. Keep each PR independently testable; wire feature gates before enabling new execution paths.

## Migration guarantees

Do not delete models, histories, profiles or diagnostic records during upgrade. Preserve digest-based identities and schema-2 manifests. Add nullable/defaulted diagnostic columns; avoid destructive database fallback. Old exports remain readable; unknown future fields are tolerated. A failed model migration rolls back metadata transactionally and leaves the original artifact intact.

## Feature rollback

Use independent gates for GGUF execution, automatic Assist preparation and content diagnostics. Disabling one must not break LiteRT/QNN chat. New-model formats unsupported by an older app should become unavailable with a reason rather than being deleted. Keep imported weights local during rollback; do not copy private app data between package IDs implicitly.

---

# 11 | Automated acceptance suite

## Import and compatibility

T01: normal APK imports a supported text GGUF and reaches READY without the separate-build message. T02: a build without a GGUF engine returns runtime-not-packaged before copying gigabytes. T03: malformed metadata, unsupported quantization, missing template, missing shards, hash mismatch and canceled copy leave no READY record or orphan staging data. T04: schema-2 existing GGUF records migrate/read safely; QAIRT bundles never route into the standalone engine.

## Runtime ownership and assistance

T05: enable Assist with an unloaded model; permitted automatic preparation runs once and produces a consumed remote handoff. Warm-only instead reports a visible skip. T06: perform three consecutive assist transformations; no stale conversation blocks the second or third. T07: an active interactive local chat is not closed or replaced. T08: disable Assist or change profile/configuration mid-run; reject the old result and preserve authorized original evidence.

## Token and output accounting

T09: deterministic native input/output counts match the ledger, role-aware UI and export. T10: duplicate cumulative events do not double-count; two physical segments both count. T11: exact final counts replace streaming estimates, including valid counts with unavailable speed. T12: length limit, timeout, cancellation, native error and queue saturation retain all available usage and bounded output. Missing final usage is explicitly incomplete.

## Integration, authorization and sharing

T13: a remote fixture receives the validated local handoff exactly once; malformed helper JSON returns the original tool result. T14: two combined profiles share one authorized transform and create two consumption records but one physical usage total. T15: differing data permissions cannot share a cache result. T16: writes, recursive delegation and sensitive data outside consent remain inaccessible to Assist. T17: tool-free trivial chat completes without fake local activity.

## Persistence and presentation

T18: process death recovers active requests as interrupted without replay billing or deleting history. T19: new usage fields round-trip through the inference IPC boundary and both diagnostic exports. T20: diagnostics off retains necessary enforced budget behavior but omits diagnostic content. T21: release-minified JNI paths load; long model names, screen readers, large fonts and dark/light themes remain usable.

> A test passes only when the intended remote request consumes the local result and the same physical invocation is visible in accounting. A green import test or an illuminated Assist toggle is not end-to-end proof.

---

# 12 | Build and physical-device qualification

## Reproducible engineering checks

Run on the implementation branch after rechecking the baseline diff. The following commands are proposed validation commands, not commands executed in this investigation. Use the repository’s required SDK/JDK, signing process and existing CI gates.

```text
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug
# Separate existing preview regression only:
./gradlew :app:assembleDebug -PgeniexRuntime=true
```

Also run the existing release-minified/native smoke configuration and published-artifact verification. Confirm the actual target task names from the current branch. Pin the GGUF native revision and capture toolchain, artifact checksums, packaged libraries and model digest with the result. Debug success alone does not qualify release packaging.

## Device scenarios

Test the user’s target phone as the primary acceptance device; verify its model, SoC, OS, available RAM and installed app build from diagnostics rather than assuming a memory profile. Include a lower-memory device or constrained case and an ABI without the optional engine. Where supported, exercise CPU and GPU separately. Do not describe a CPU run as NPU execution.

Run cold and warm imports/loads, three consecutive assists, alternating local chat and remote Assist, two combined remote profiles, background/foreground transitions, rotation, cancellation during prefill and decoding, memory pressure, thermal throttling and inference-process death. Test a small supported GGUF first, then the user’s exact artifact. Preserve source evidence when the optional helper cannot complete.

## Measure value

Collect at least 20 paired Assist-on/off trials per representative task using fixed evidence and consistent profiles. Report median and tail end-to-end latency, time spent loading/queued, local prompt/output usage, remote provider usage, retained source IDs, correctness/coverage and failure/fallback rates. Where remote results are nondeterministic, repeat trials and report uncertainty; do not infer savings from one sample.

## Suggested acceptance targets

Accounting reconciliation must be exact for deterministic native fixtures. No text or metrics may vanish silently. Every skipped task has a reason, every dispatched task has one terminal record, and no active interactive session is displaced. Performance targets should be set from measured device behavior; on a workload with no demonstrated benefit, keep the original evidence path or disable that task policy.

---

# 13 | Release criteria, risks and scope

## Release only when all three outcomes are demonstrated

1. A compatible GGUF loads and generates inside the normal application identity. 2. Enabling Assist on an eligible task prepares or reuses that local model and delivers a validated handoff to the remote request. 3. Its streamed output and native/estimated token usage appear as a distinct Assist invocation in debug diagnostics and exports, including partial and canceled work.

## Risks and controls

Native conflict: inventory and qualify the APK, not merely Gradle resolution. Unsupported model architecture: pin the engine and reject clearly. Memory/thermal pressure: use admission, bounded context and visible fallback. Lossy compression: preserve original evidence and measure coverage. Cancellation races: session ownership and terminal-idempotency tests. Privacy leakage: scope-bound caches, redaction and explicit content capture. UI overload: concise status rows with details on expansion.

## What this change does not claim

It does not make LiteRT load GGUF, guarantee every GGUF tensor/architecture combination, unlock all GenieX QAIRT bundles in the normal app, or provide unqualified GGUF NPU/MTP support. It does not give a helper authority to perform writes, make local reasoning part of a remote model’s internal inference, or guarantee lower latency/cost on every prompt.

## Evidence limitations

Verified findings are tied to main at the stated commit and the specific source paths cited. The current phone build, original model bytes, native dependency artifact contents and complete diagnostics-screen filtering were not available or fully audited. Therefore a missing on-screen counter cannot be attributed solely to the ledger without an end-to-end reproduction. The inspected source nevertheless establishes the import gate, cold-readiness restrictions, direct Assist call, native-usage mismatch and success-only metric forwarding.

## Immediate implementation priorities

First land the usage normalization and terminal-metrics regressions: those improve existing LiteRT/GenieX diagnostics independently. Then qualify a GGUF engine in the normal APK before removing import gates. Finally enable automatic preparation and role-aware output with shared-invocation accounting. Keep optional extensions such as attachment preprocessing behind separate acceptance tests.

> Status: investigation and implementation design complete. Application changes, native packaging qualification and physical-device acceptance remain implementation work; none is represented here as already completed.

---

# Source register

Repository sources are pinned to `1d4dcd8698356abddc5f1be946bb0811017b7d7a`. Upstream documents were checked on October 13, 2026; they are not pinned dependency qualification.

- **[R1]** [main baseline and release identity](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/1d4dcd8698356abddc5f1be946bb0811017b7d7a)
- **[R2]** [Build configuration: source sets, app IDs, native packaging](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/build.gradle.kts)
- **[R3]** [Exact preview error: LocalImportPreview.inspect()](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localmodel/LocalImportPreview.kt)
- **[R4]** [GGUF validation, installation and manifest creation](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localmodel/GgufImportAdapter.kt)
- **[R5]** [GenieX loading and conversation capability restrictions](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/geniex/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/GenieXRuntimeAdapter.kt)
- **[R6]** [GenieX sendMessage(): streaming, native metrics and stop reasons](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/geniex/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/GenieXRuntimeAdapter.kt)
- **[R7]** [Manifest/runtime routing and fallback](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/LocalRuntimeRouter.kt)
- **[R8]** [Assist readiness, completeChat(), generateDelegate(), delegateToProfile()](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt)
- **[R9]** [Assist processing and binding](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/assist/AssistCoordinator.kt)
- **[R9b]** [LocalAssistToolBinder.bind()](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/assist/LocalAssistToolBinder.kt)
- **[R10]** [InvocationLedger.wrap(): usage, estimates and diagnostics policy](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/accounting/InvocationLedger.kt)
- **[R11]** [Local provider: native event forwarding and conversation reuse](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/provider/LiteRtLmAdapter.kt)
- **[R12]** [Existing diagnostics export integration point (file inventory verified)](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/StructuredDiagnosticExport.kt)
- **[U1]** [Qualcomm GenieX SDK: plugins, engines and formats](https://github.com/qualcomm/GenieX/blob/main/sdk/README.md)
- **[U2]** [Official llama.cpp Android integration documentation](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)