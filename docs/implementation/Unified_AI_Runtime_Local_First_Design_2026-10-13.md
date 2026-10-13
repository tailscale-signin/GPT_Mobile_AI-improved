# Source design transcription

Unified AI Runtime & Local-First Optimization
Repository design and implementation specification
Repository: tailscale-signin/GPT_Mobile_AI-improvedPrepared for: Emanuel MeloDate: October 13, 2026Baseline: main at 1d4dcd8698356abddc5f1be946bb0811017b7d7aStatus: Proposed implementation; source-informed design, not an implemented or device-qualified release.Suggested repository destination: docs/implementation/unified-ai-runtime-local-first-2026-10-13.md
Core decision: Consolidate the application’s existing execution components behind one request policy and accounting contract. Keep LiteRT-LM, Qualcomm acceleration and the GenieX preview behind the existing LocalRuntime boundary. Use local inference only when it improves the complete task without weakening evidence, permissions or user control.
This specification covers unified orchestration, local assistance, remote-token optimization, shared research context, runtime qualification, diagnostics, settings, recovery, testing and rollout. It does not promise a particular token-saving percentage or NPU speedup. The earlier 100,000-to-45,000-token example was hypothetical, not a repository benchmark.
Reading guide. Sections 1–5 establish the baseline and runtime architecture. Sections 6–10 define optimization, evidence, recovery and accounting. Sections 11–14 specify the interface, contracts and implementation sequence. Sections 15–18 contain tests, release controls and source references.
1. Executive decision and boundaries
The objective is lower remote cost per successful answer, with answer quality, completion reliability, latency and phone resource use visible alongside that cost. “Local-first” means considering local work first when it is eligible and useful; it does not mean forcing every request through a small model.
The repository already contains substantial relevant infrastructure: a local runtime interface, execution planning, context selection, bounded delegation and a shared read-only tool broker. The work should join these capabilities rather than create a second local engine manager or replace the entire agent stack. [R02–R07]
In scope. A shared per-turn execution context; atomic cross-worker budgets; bounded local classification, passage selection and compression; evidence-preserving context assembly; consistent usage attribution; runtime eligibility and fallback explanations; a unified efficiency interface; paired evaluation and release gates.
Out of scope for the first release. General-purpose GPU/NPU tensor splitting, arbitrary GGUF-to-QNN conversion, automatic installation of runtime binaries, concurrent native helper inference without independent qualified handles, silent switching of selected AI profiles, autonomous replay of external actions, and moving all memory or research processing into a new database.
Keep these user concepts distinct:
Feature
Intended behavior
Must not imply
Local Assist
A bounded local helper prepares evidence or context for the selected primary model.
The helper replaces the final model or may access every tool.
Delegation
Explicit subtasks go to approved local or remote workers, within one parent budget.
Unlimited recursive agents or free extra requests.
Combined mode
The selected models contribute, with one controlled synthesis path.
Dropping a selected participant merely to reduce cost.
Runtime execution policy
Chooses an eligible backend/configuration for a local model.
Changing the user’s cloud provider or enabling research.
Efficiency policy
Controls whether preprocessing, reuse and compression are worth doing.
Overriding privacy, hardware qualification or explicit profile settings.

2. Verified baseline and implementation gaps
The inspected main commit is 1d4dcd8, whose merge message identifies Local AI, research, OpenRouter and release 0.9.51.0 work. The dependency catalog pins LiteRT-LM 0.18.0, QNN 2.51.0 and GenieX 0.8.0. These are the repository’s pins, not a claim that they are universally compatible or the latest releases. Preserve them until an independently tested upgrade is approved. [R01, R08]
Existing component
What was actually inspected
Required extension
LocalRuntime.kt
Engine/conversation lifecycle, streaming events, estimated and native metrics, actual runtime state, exclusive-access hooks.
Add stable task/segment attribution without creating a competing lifecycle owner.
PlannedLocalRuntime.kt
Qualification-aware selection, admission, warm-state identity, fallback qualification reset.
Feed helper workload intent into the existing planner and expose skip/decision reasons.
SharedToolCallBroker.kt
Same-turn read-only single-flight sharing, canonical JSON, 30-second default TTL, producer attribution.
Bind authorization scope and parent budgets; distinguish reuse from physical execution.
LocalDelegationCoordinator.kt, inspected first 240 lines
Per-turn coordinator, input limits, worker controls, runtime readiness caches, reviewer and failure accounting.
Give all children one parent context and budget; unify availability identity and terminal reporting.
ContextBuilder.kt
Profile-specific history, recent-turn window, character-budget trimming, attachment window.
Assemble token-budgeted task evidence without discarding protected instructions or source identity.
ChatRepositoryImpl.kt, inspected first 160 lines
Imports and integration seams for provider sessions, tools, local runtime, context, persistence and diagnostics.
Connect the shared request context at entry points; trace remaining call sites before implementation.

The runtime design guide describes the private inference process, quarantine, release gates, benchmark persistence and disabled advanced modes. The research guide describes captured-source versions, permissions, quotas and owner-specific evidence verification. These are documented existing contracts, not independently exercised device behavior in this review. Preserve them. [R09–R10]
Audit limits. This was a targeted source/document review, not a complete security audit. No APK was built, no test suite was executed, and no ROG Phone 9 Pro or gateway was connected. Call-site completeness, native behavior, latest open branches and current CI results remain implementation-time checks. Unread files are not treated as absent features.
3. Architecture: one policy layer, existing execution owners
Introduce a small UnifiedRunCoordinator above the existing agent/provider entry points. It creates the execution context and applies policy; it does not own JNI handles, run its own tool executor or become another provider SDK.
User turn / benchmark / approved recipe                  |       UnifiedRunCoordinator       policy + parent budget + trace                  |       Existing agent/provider workflow          /             |             \ Context assembly   Tool resolution   Delegation / synthesis          |             |             | Shared evidence    Shared broker     Approved provider sessions          | Bounded local helper, when admitted          | Existing LocalRuntime + engine-holder lease          | PlannedLocalRuntime -> process facade -> native worker          | LiteRT-LM / supported QNN path / GenieX preview adapter
Execution context. Every physical model call and tool operation carries runId, turnId, stepId, attemptId, the parent step, canonical model identity, execution owner, authorization-scope digest, evidence references, policy revision and budget reservation. Use provider/model IDs for accounting; profile names are display labels.
Responsibility boundaries. The coordinator owns task-level decisions and the parent ledger. Existing provider runners own request serialization and streaming. The tool resolver remains the authorization authority. The engine holder retains native ownership and cancellation sequencing. The research journal owns source acquisition/checkpoints. The statistics layer consumes normalized events rather than independently guessing outcomes.
A route is selected from facts: capability, permission, installed artifact, health, measured workload performance and remaining budget. A remote planner call is not mandatory. Start with deterministic routing rules; introduce model-based planning only when paired tests establish a net benefit.
Consolidation rule. During migration, an existing component either remains authoritative or delegates a responsibility to the new contract. It must never charge the same request twice, maintain a competing retry loop, or announce a second terminal outcome. Inventory every owner before moving it.
4. Where LiteRT, LiteRT-LM, QNN and GGUF fit
LiteRT provides on-device model execution. LiteRT-LM adds language-model pipeline management on top of LiteRT; this is model-level orchestration, not the application-wide permission, research and cost coordinator proposed here. Qualcomm acceleration can sit underneath a compatible LiteRT route rather than competing with it. [W01–W03, W06]
Technology
Integration role
Eligibility rule
LiteRT-LM
Preferred existing generative local path for compatible language-model packages.
Package metadata, tokenizer/template, runtime version and requested backend must match.
LiteRT utility models
Optional embeddings, classification or reranking where a small task model is useful.
A separate verified artifact and task adapter; do not load a .tflite utility artifact as a chat model.
QNN / QAIRT
Qualcomm runtime/toolchain and acceleration dependencies used by supported local routes.
Validate the exact device/runtime/model/dispatch combination; a Qualcomm chipset alone is insufficient.
GenieX preview
Retain the existing separate preview route and its build/package constraints.
Do not advertise GGUF support in a build that lacks the required adapter and packaged native libraries.
GGUF
Model format requiring an appropriate execution engine, including a supported GenieX plugin route.
Format detection selects a compatible runtime; it never proves NPU eligibility.

Google lists Snapdragon 8 Elite / SM8750 in its Qualcomm LiteRT delegate support material. That establishes a relevant device family, not qualification of this app, its LiteRT-LM export or a particular firmware build. [W03]
Qualcomm’s GenieX material distinguishes a llama.cpp plugin for GGUF coverage from a QAIRT plugin for optimized deployment. Consequently, this design does not permanently label all GGUF inference as CPU/GPU-only, and does not assume that every GGUF model can run on an NPU. Only a supported, packaged and tested route may be offered. [W04]
For new utility-model integration, use the current LiteRT CompiledModel direction after reconciling dependencies with the pinned LiteRT-LM package. Do not paste a legacy delegate example into the generative adapter or independently upgrade transitive native libraries. [W02]
5. Runtime eligibility, QNN diagnosis and fallback
Expose an evidence ladder rather than a single “NPU supported” Boolean:
Discovered → artifact validated → dependencies available → load passed → smoke test passed → workload qualified. Failed, quarantined, unsupported and unknown are separate states. A library’s presence proves only that it is packaged, not that a particular graph executed on the NPU.
Extend the existing runtime evidence identity with the fields needed by assist tasks: model digest; tokenizer/template identity; artifact edition and quantization; runtime and dispatch-library digests; ABI; device/firmware identity; requested modality/backend; context/output bounds; task type; qualification-suite revision. Reuse existing identity fields rather than duplicating them. [R03, R09]
Historical failure to reproduce. The previously reported missing/unsupported QNN package error is a regression scenario, not a confirmed current defect. Reproduce it with the exact installed APK, model and device. Classify failures before offering recovery:
Failure category
User-facing explanation
Permitted recovery
Model incomplete or digest mismatch
“This model package is incomplete or changed.”
Explicit repair/download from the approved catalog; verify before load.
Runtime/dispatch mismatch
“This model requires a different verified runtime package.”
Explain required package; no arbitrary binary download or path guessing.
Unsupported device/artifact tuple
“This edition is not supported on this device.”
Offer an independently compatible edition.
Insufficient memory or thermal pressure
“Local assistance is paused to protect device performance.”
Skip optional helper work; retry only after admission recovers.
Native crash or protected tuple
“This configuration is quarantined after a runtime failure.”
Keep protection; explicit isolated verification only.
Unknown effective backend
“Requested NPU; actual execution not reported.”
Do not label the trial NPU-qualified or infer it from speed.

Fallback is conditional, not a universal NPU → GPU → CPU ladder. An NPU-only compiled artifact cannot simply be reopened on a CPU. Attempt another backend only when the same artifact supports it, or select a separately installed and approved edition. Preserve explicit fallback settings and private-destination rules. A different configuration receives its own evidence identity. [R02–R03]
Cache readiness failures by the full tuple, with typed invalidation on installation or runtime changes. Preserve durable crash quarantine across app upgrades. A harmless optional assist failure should produce a skip reason and continue with the approved primary route, not repeatedly reload the same broken package.
6. Token optimization policy
Remote tokens, provider cost and local compute are different measures. Shared downloads may save network calls without reducing model input. A cheaper provider may lower cost without using fewer tokens. Provider prompt caching can discount repeated input without removing it. Local token generation must never be subtracted directly from a different model’s token count.
Apply optimization in this order:
Avoid unnecessary model calls. Use deterministic filtering, parsing, deduplication and permission checks first.
Reuse eligible tool results and captured evidence. Do not pass every retained source to every model.
Select task-specific passages and relevant memory. Preserve exact evidence and user constraints.
Use a qualified warm local model for bounded ranking or compression when it is likely to help.
Reserve approved remote models for synthesis, difficult reasoning and unsupported tasks.
The shared evidence store is not shared transformer memory. Each remote request is billed for the context actually sent to that model; a source ID is useful only when the receiving workflow can resolve it through an authorized retrieval operation.
Mode
Proposed behavior
Default boundary
Off
Retain existing explicit behavior; record comparable metrics when diagnostics are enabled.
No new helper or compression steps.
Balanced
Deterministic reuse, bounded passage selection, eligible warm local assist.
Recommended opt-in policy; no automatic cold load.
Maximum remote savings
More aggressive passage selection and optional qualified cold-load assistance.
Explicit opt-in; quality and resource gates remain mandatory.
Fast response
Prefer warm reuse and minimal orchestration overhead.
Skip local work when it delays the primary response.
Custom
Expose task-level budgets, helper tasks and approved fallback order.
Show effective settings and conflicts, not hidden overrides.

For a candidate helper, estimate the input it can remove, output/handoff overhead, expected retry risk, warm/cold initialization time and local resource cost. Skip it when no eligible candidate exists or the estimated benefit is negative. Treat forecasts as estimates; revise them from paired observations by task and model, not from a fixed universal percentage.
Never shorten a requested final answer merely to hit a savings target. Preserve the selected Combined participants and explicit primary model. “Maximum” does not override hardware safety, context limits, research permissions or external service quotas.
7. End-to-end request and budget contracts
Request sequence. Create a run context; resolve current permissions and preferences; collect eligible cached evidence; determine the task and budget; admit optional local work; validate its output; build the primary prompt; execute the approved model/delegates; synthesize once where required; finalize usage and quality status.
Use a state machine with explicit transitions:
CREATED -> PLANNING -> PREPARING -> EXECUTING -> FINALIZING                  |                    |             |                  +-> WAITING_USER     +-> PARTIAL   +-> COMPLETEDAny nonterminal state -> CANCELLING -> CANCELLEDUnrecoverable interruption -> INTERRUPTED / FAILED
A local helper can be SKIPPED while the overall turn completes. A failed optional source does not automatically fail useful sibling work. Conversely, accepted partial text is not evidence of complete task success.
Atomic parent budget. Maintain separate limits for remote input, remote output, provider spend, local generated work, physical tool requests, result bytes, elapsed time and concurrent work. Reserve before dispatch; reconcile against actual usage after termination. Children receive allowances from the parent rather than independent copies of the full budget. Include system prompts, tool definitions, histories, helper handoffs and retries.
Reserve a synthesis allowance before starting fan-out. Stop new preparation when that reserve is reached. A request cancelled before dispatch can release its reservation; a request already sent may incur usage. Missing provider receipts remain unknown with a conservative reservation until reconciled, not zero-cost success.
Owner and retries. Give transport retries one clearly defined owner. The orchestrator may choose a new step only after the prior physical attempt is terminal. Classify transient failures separately from invalid credentials, incompatible artifacts, content/schema failures and denied permissions. Repeated empty output must hit a finite policy limit. Record every charged attempt.
Cancellation. Propagate cancellation through local generation, providers, broker subscribers and tool owners. A subscriber leaving shared read-only work must not cancel other valid subscribers; cancel the physical producer only when none remain or the parent scope ends. Preserve the existing native cleanup fence before admitting another generation. Do not swallow coroutine cancellation as a normal retryable exception.
8. Shared research, evidence and memory
Extend the existing research journal and tool broker, not a parallel crawler or an unbounded global context store. The research implementation already documents snapshot hashes, version distinctions, permissions and execution-owner constraints. [R04, R10]
Shared evidence record. Store canonical source identity, captured version, source owner, content hash, retrieval time, expiry/freshness policy, read status, location spans, authorization scope and provenance. URL normalization may remove known tracking noise but must preserve meaningful query parameters, versions and access boundaries. Similar titles are not enough to merge papers or source text.
Local assist contract. First offer exact passage selection rather than free-form summarization. The helper receives only an authorized, bounded slice and returns source/span IDs, short relevance reasons and explicit unresolved questions. Resolve IDs against the stored snapshot, verify quotes and enforce output limits before the primary model sees the result. A malformed or unsupported helper result falls back to deterministic selection.
Optional summaries must preserve quotations, dates, names, units, code identifiers, contradictory findings and stated limitations. Retain original source text for inspection. The helper cannot promote metadata into a full-text read, declare a study peer reviewed without metadata, or convert model agreement into independent evidence.
Parallel research. Use a shared source queue with leases and explicit ownership. Dispatch only eligible engines, prioritize marginal information gain and stop rediscovering the same material. Do not blindly round-robin every URL through every engine and model. A deduplicated read can be consumed by several workers, but each worker receives only the passages required for its task.
Memory. Apply existing recall permissions before retrieval and again before remote dispatch. Rank relevance locally where qualified, retain fact provenance and conflict markers, and separate suggested new memories from authoritative facts. Turning off cloud recall must also block cloud handoff through summaries, caches and delegates. Temporary conversations must not leave durable helper memory.
Cache boundaries. Key reusable outputs by task/input hash, source snapshot, runtime/model identity, prompt/schema revision, language and authorization scope. Recheck permission on every hit. Cache possession does not grant access. Do not share private evidence between unrelated accounts or conversations simply because the URL matches.
9. Resource scheduling and recovery
Keep one native ownership authority through the existing engine holder and LocalRuntime exclusive-access contract. Optional helper work must never unload an active chat model, queue indefinitely behind generation or open an independent engine against the same native handle. Prefer tryRunExclusive behavior that skips optional work on contention. [R02, R09]
Initial policy. Balanced uses an already loaded eligible model. Cold-load assistance is a separate opt-in. Use one local helper at a time; preserve foreground chat priority. Concurrent CPU/GPU/NPU helper execution remains disabled until independent handles and device measurements qualify it. A remote request can overlap local preprocessing only when the dependency graph and overall budget permit it.
Reuse an engine where safe, but create task-scoped conversations or prove conversation isolation before reuse. A warm engine is not permission to retain another user turn’s hidden context. Do not serialize native handles, live KV caches or in-flight tool callbacks into backups.
Use existing memory and thermal admission rules. Do not estimate available capacity from total RAM alone. Record warm/cold load, available memory, process memory samples, thermal state, battery state and charge state separately. Android’s Thermal API can provide thermal state/headroom; unavailable or unreliable readings must stay unknown, and polling must respect the documented frequency limits. [R03, W05]
Worker death. Preserve the private-process crash-isolation design. On death, invalidate sessions and pending handles, finalize affected steps once, and retain verified completed evidence. Recreate only permitted read-only steps under explicit recovery policy. An external action with an uncertain outcome requires reconciliation, not replay. Same-UID process isolation is crash containment, not a separate security boundary. [R09]
Checkpoint logical task state and evidence references through the existing persistence layer. Resume from completed validated steps after rechecking credentials, permissions, source freshness and model identity. Do not assume an interrupted remote stream is resumable or free to restart. Separate “Continue from saved research” from “Retry the model request” in the UI.
10. Usage accounting and diagnostics
Extend the current metrics/event pipeline. LocalInferenceMetrics already distinguishes estimated character-based counts from native prefill/decode metrics and labels native metrics as last-segment scope. Whole-turn totals must aggregate unique segments, not sum repeated snapshots or assume the last segment equals the entire tool-assisted run. [R02]
Metric
Source and aggregation rule
Remote input/output
Provider receipt per physical request; estimated fallback clearly labeled. Include failures and retries with reported usage.
Cached input
Normalize provider semantics. Mark whether cached input is a subset of total input; do not double count.
Reasoning tokens
Keep provider-reported inclusion semantics. Do not add them twice when already included in output.
Local input/output
Native counters where available; otherwise tokenizer estimate or character estimate with provenance.
Local performance
Load time, prefill, decode, first visible answer, full step duration and warm/cold state.
Shared tool reuse
Logical consumers, one physical producer, cache/single-flight hit type and producer invocation ID.
Cost
Provider/model/route, price timestamp, currency and billable categories. Unknown price produces unknown cost, not zero.
Benefit
Paired baseline-versus-optimized differences; forecasted savings labeled separately from measured comparisons.

Use an idempotent usage key such as (runId, stepId, attemptId, segmentId, producerId, metricKind). Upsert cumulative receipts rather than append every update as incremental usage. Preserve provenance and coverage when final usage is missing. Do not count gateway progress as another model/tool execution.
Definitions. For matched evaluated tasks, remote token delta is baseline remote tokens - optimized remote tokens, including all physical model attempts and synthesis. Reduction percentage is that delta divided by baseline remote tokens when baseline is positive. Report per-model counts as well as the operational total because tokenizers differ. Provider cost reduction is calculated separately. Never display “tokens saved” on an ordinary live turn as if the unrun baseline had been observed.
Debug presentation. Show a row for each local helper exactly as for other model work: profile/model, purpose, requested/effective runtime, input/output provenance, elapsed time, first-token time, outcome and skip/fallback reason. Do not expose private chain-of-thought text. A developer export may include a consented bounded transcript only after explicit redaction controls.
Metadata-only diagnostics are the default: no API keys, raw source text, personal memory or complete prompts. Source URLs can themselves be sensitive; strip credentials and signed parameters and support hashed identity. Export schema version, effective policy, all attempt outcomes and unresolved usage coverage.
11. Interface and interaction design
11.1 Settings structure
Create Settings → AI → Execution & efficiency as the consolidated policy entry, with four tabs: Overview, Policy, Statistics, Diagnostics. Existing Engine & performance entry points should deep-link to the same runtime controls rather than maintain duplicate preference fields. Preserve Local Models → Assist as a focused view of the same local-assist policy.
Execution & efficiency                         (?)[ Overview ] [ Policy ] [ Statistics ] [ Diagnostics ]AI token optimization                           On[ Off ] [ Balanced ] [ Maximum savings ] [ Fast ]Local Assist                                ReadyModel: selected local profileRuntime: requested NPU / effective not reported[ Configure local assist ]  [ Check compatibility ]Recent turnPrimary answer completed; local selection usedRemote usage: provider reportedSavings: not measured for this turn[ View execution ]
Overview. Use a compact status card with one primary action. Display the primary profile, local-helper readiness and active policy. Prefer “Ready,” “Busy,” “Paused: device warm,” “Needs setup,” “Not supported,” and “Quarantined” over a generic red error. Hardware state must never be inferred from a selected toggle.
Policy. Group controls under Local assistance, Context and reuse, Budgets, and Fallback/privacy. Show each group’s effective setting and an information button. Advanced controls expand in place; do not expose raw model context fields in the main summary. Changing a preference does not trigger a download, benchmark, cloud request or memory recall.
11.2 Local Assist settings
Control
Proposed first-release default
Enable Local Assist
Preserve current user choice; new behavior opt-in.
Select relevant passages
Enabled within opted-in Balanced mode.
Compress long tool output
Off until task-specific quality fixtures qualify it.
Rank relevant memories
Honor existing memory/recall permissions; no new grant.
Prepare search queries
Optional, separately qualified; cannot increase research scope silently.
Load a helper when none is ready
Off in Balanced; explicit opt-in elsewhere.
Keep helper warm
Only during permitted active work and existing idle policy.
Prefer NPU
Display the current runtime policy; do not override Profile or qualification.
Remote fallback after local failure
Preserve existing approved policy; no implicit cloud grant.
Protect battery and temperature
On; hardware protection cannot be disabled by savings mode.

11.3 Statistics, diagnostics and chat feedback
Statistics uses This turn / Today / 7 days / 30 days / All time and filters for profile, actual provider/model, local/remote, workload and outcome. Separate cards show remote usage, local work, provider spend, shared-result reuse, and measured A/B benefit. Missing data is “Unavailable” with an explanation, never a fabricated 0 or 100%.
Diagnostics has Runtime, Attempts, Evidence, and Export sections. “Check compatibility” performs metadata checks; “Run isolated verification” is a separate explicit action with its expected resource impact. A quarantine notice must not offer a generic one-tap bypass.
Under a response, use a small themed chip such as Local Assist · selected 12 passages or Local Assist skipped · model busy. Tapping it opens a bottom sheet containing purpose, evidence count, runtime, usage source, elapsed time and result status. Use “Worked for …” for complete execution duration; keep native decode speed in details. Avoid filling the chat with intermediate helper messages.
Use Material theme tokens, restrained elevation, readable spacing and icons with text labels. Specify 48 dp minimum touch targets, scalable text, screen-reader descriptions, keyboard focus and reduced-motion support. Disabled controls retain an explanation; opacity alone is not the state indicator. On compact screens use scrollable tabs; on larger layouts use a two-pane detail view. Permission errors and lack of hardware support must remain actionable and distinct.
12. Proposed data and integration contracts
The following names are proposed, not verified existing classes. Place them in a small data/orchestration/ package only after checking the implementation branch for equivalents. Keep contracts independent of Compose and native SDK types.
// Design sketch: integrate with existing IDs/serialization conventions.data class RunExecutionContext(    val runId: String,    val turnId: String,    val policyRevision: Int,    val authorizationScope: String,    val budgetId: String)enum class AssistTask {    PASSAGE_SELECTION, TOOL_COMPRESSION,    MEMORY_RANKING, QUERY_PREPARATION}data class EvidenceSpanRef(    val sourceId: String,    val versionId: String,    val contentHash: String,    val startOffset: Int,    val endOffsetExclusive: Int)sealed interface AssistOutcome {    data class Accepted(        val spans: List<EvidenceSpanRef>,        val usageRef: String    ) : AssistOutcome    data class Skipped(val reasonCode: String) : AssistOutcome    data class Rejected(val reasonCode: String) : AssistOutcome}
Offsets use one documented indexing convention shared with the source snapshot. Do not mix UTF-16 offsets from Kotlin with UTF-8 byte offsets from a gateway. Validate bounds, content hash, version and source permissions when resolving each span.
Suggested contracts. RunBudgetLedger.reserve/reconcile/release, AssistEligibilityEvaluator.evaluate, LocalAssistExecutor.execute, EvidenceHandoffBuilder.build, and RunUsageAggregator.accept. Return typed decisions with reason codes rather than Boolean success. Use cancellation-aware suspending APIs; translate provider errors once at the boundary.
Storage. Extend existing run/step persistence and statistics repositories. Add schema-versioned policy snapshots, usage provenance and evidence references where needed. Do not choose a new Room version from this document; inspect the actual merge target, allocate the next migration and commit generated schemas plus migration tests. Keep compact preferences in the current settings repository, not a new preferences file for each screen.
Backups may include policy and redacted history but must not restore live native sessions, positive hardware qualification for a different device, consent tokens or credentials from plain-text exports. Import prior measurements as historical. Deleting a source or revoking a permission invalidates dependent helper caches and remote handoff eligibility.
13. Repository integration map
All app paths below are relative to app/src/main/kotlin/dev/chungjungsoo/gptmobile/. “Inspect further” means the path is an identified integration seam, not a claim that its complete behavior was audited.
Path / area
Work to perform
data/repository/ChatRepositoryImpl.kt
Create and propagate the run context at existing turn entry points; inspect the complete file before editing. Keep persistence and final synthesis behavior intact.
data/agent/tool/LocalDelegationCoordinator.kt
Inject parent budget and normalized usage sink; unify readiness keys, finite retries, reviewer costs and cancellation outcomes.
data/agent/tool/SharedToolCallBroker.kt
Add authorization-aware sharing, subscriber ownership and explicit logical/physical attribution without changing read-only eligibility.
data/context/ContextBuilder.kt
Preserve current history semantics; add task evidence assembly through a separate collaborator rather than embedding native calls.
data/localruntime/LocalRuntime.kt
Extend metrics/state attribution compatibly; preserve the established engine/conversation interface.
data/localruntime/PlannedLocalRuntime.kt
Supply helper workload labels, expose effective selection and preserve qualification resets on fallback.
data/localruntime/LocalEngineHolder.kt and InferenceAdmission.kt
Existing paths confirmed in the runtime directory; inspect further before connecting optional-helper leases.
data/agent/provider/LiteRtLmAdapter.kt
Existing provider seam identified from imports and runtime documentation; trace segment metric propagation and cancellation before modifying.
data/research/ and existing research journal
Extend current source queues/checkpoints and source-to-prompt selection; do not duplicate acquisition or snapshot stores.
Existing settings, telemetry and diagnostics areas
Bind a single policy model to runtime settings, Local Assist, statistics and debug export. Trace exact UI/repository files before editing.
Proposed data/orchestration/
Add only missing coordinator, policy, budget and normalized event contracts.
gradle/libs.versions.toml
Preserve current runtime pins initially; require native-package verification for any change.
docs/gpu-npu-execution-plans.md, research guide and validation docs
Cross-link this specification; update completion status and evidence boundaries, not just feature descriptions.

Existing workflow files visible in the inspected tree include pr-validation.yml, device-validation.yml, native-memory-smoke.yml, geniex-preview.yml and release-0950-acceptance.yml. Inspect their contents and required-check wiring before extending them; their presence is not proof of a passing release gate. [R12]
Suggested implementation branch name: feat/unified-runtime-local-first-2026-10-13. Create it from the intended, newly verified merge base at implementation time. This document does not create that branch or modify the repository.
14. Implementation sequence and pull-request boundaries
Stage
Scope
Exit evidence
0 — Baseline and ownership map
Trace complete entry points for chat, Combined, delegation, research, local inference and benchmarks. Capture existing behavior and exact build tuple.
Call graph, owner matrix, baseline fixtures and known gaps reviewed.
1 — Unified accounting
Add run context and normalized events; reconcile current local/provider/tool metrics. No routing change.
One physical request counted once; retries and missing usage represented; current tests remain green.
2 — Parent policy and budgets
Attach the ledger to existing runners and broker. Centralize effective policy resolution and cancellation ownership.
Concurrency tests cannot overspend reserved budget; no action replay or permission expansion.
3 — Warm local assistance
Add deterministic selection first, then qualified passage selection through existing exclusive runtime access.
Busy/unready helpers skip cleanly; exact source preservation; no forced model reload.
4 — Research/context integration
Add authorization-scoped reuse and validated evidence handoffs; memory obeys existing controls.
Fewer duplicate physical reads; no source-version merge or cross-scope leakage.
5 — UI and optional policies
Bind tabs, local settings, diagnostics and measured comparisons to the same state. Add optional compression/cold loading behind flags.
Accessibility and lifecycle tests; no request on settings entry; clear fallback/unknown states.
6 — Qualification and release
Run paired evaluations and required physical-device gates for every enabled route.
Signed release artifacts, retained raw results, quality/cost/latency report, rollback verified.

Each stage should be an independently reviewable PR with additive changes and a migration note. Separate dependency upgrades from orchestration behavior changes so failures can be attributed. Do not combine a native runtime update, database redesign and broad UI rewrite in the first PR.
Ownership checklist. Assign one implementation owner and one reviewer for run budgets, native lifecycle, evidence/security, UI/settings and test/benchmark infrastructure. A feature is complete only when its code path, metrics path, failure path and acceptance test all exist.
Feature flags. Proposed flags are unifiedRunAccounting, sharedRunBudget, warmLocalAssist, evidenceHandoff, assistCompression and coldLoadAssist. Keep initial behavior-preserving accounting independently switchable from routing. Disabling an optimization stops new helper steps but does not remove quarantine, permissions, native cleanup or records of already incurred usage.
15. Test matrix and measurable acceptance
Use existing unit, instrumentation, native-memory, benchmark and gateway harnesses wherever they already cover these contracts. Add cases under the existing test source set matching data/orchestration/, plus focused regression tests beside the touched components. A fake adapter test proves orchestration logic, not NPU execution.
ID
Scenario
Required outcome
UAR-01
Two delegates reserve budget concurrently
Atomic reservations; no independent full-budget grants.
UAR-02
Repeated cumulative usage events
One final total; no double-counted cached or reasoning tokens.
UAR-03
Shared read with three consumers
One physical producer, three logical consumers; permissions checked per consumer.
UAR-04
One shared-read consumer cancels
Other valid consumers complete; final subscriber cancellation cleans up producer.
UAR-05
Local model busy/unloaded
Balanced skips optional help, without loading/unloading or stalling primary chat.
UAR-06
Invalid QNN tuple / NPU-only artifact
Typed failure, no invalid CPU/GPU fallback and no retry storm.
UAR-07
Native worker dies or stalls
Main UI survives, lease clears only after cleanup, one terminal event, no action replay.
UAR-08
Provider fails after partial output
Preserve accepted text and known usage; label partial; do not claim complete success.
UAR-09
Helper returns invented span/altered number
Reject handoff and use verified deterministic evidence; record rejection.
UAR-10
Paper versions or similar URLs differ
Distinct snapshots remain distinct; metadata never becomes a read.
UAR-11
Cloud recall revoked mid-run
No further remote handoff of memory, including cached summaries.
UAR-12
Tool/source prompt injection
Text cannot grant permissions, change budgets or request arbitrary tool execution.
UAR-13
Process restart / backup import
Logical checkpoints recover safely; hardware evidence becomes historical when required.
UAR-14
Low memory, severe heat, unknown sensor
Optional work pauses or skips; no fabricated energy or thermal certainty.
UAR-15
Combined mode selected profiles
Contributions preserved, one intended synthesis, no recursive helper/delegate loop.
UAR-16
Settings, rotation, large font, screen reader
Stable state, no unintended requests, readable layout and accessible actions.
UAR-17
Failed/cancelled helper and reviewer
Their known usage appears in totals and debug details, not only successful work.
UAR-18
Main versus GenieX preview APK
Unsupported formats remain unavailable; preview route tested separately.

15.1 Paired evaluation protocol
Use a versioned corpus of at least 100 distinct tasks spanning short chat, long documents, multi-source research, memory retrieval, structured extraction and Combined/delegation. Run baseline and optimized configurations in randomized paired order with repeated trials. Start with fixed model/provider IDs, comparable settings and captured source snapshots. Report all attempts, failures and missing receipts; do not keep only the best run.
Stratify warm versus cold loads, small versus large inputs, local runtime/model, firmware, charge state and task type. Keep live-web variability in a separate cohort. Include multilingual and long-code inputs because character estimates and compression behavior vary.
Proposed release criteria, not measured results. Required quote, identifier, unit and source-version fixtures must pass exactly. The primary task-success comparison must meet a predeclared non-inferiority margin; use 2 percentage points as an initial proposal, with a one-sided 95% interval and an inconclusive result when sample size is inadequate. Expand the corpus when needed rather than treating “no significant difference” as equivalence.
Enable a savings policy by default only for task cohorts showing a positive paired remote-cost benefit with adequate usage coverage and no unacceptable quality, completion or latency regression. Propose a 10% p95 latency-regression ceiling for Balanced, subject to product review before tests. Report p50/p95 task time, remote tokens/cost, helper rejection/failure rates and resource observations together. Do not claim battery savings without a controlled energy-measurement protocol.
Retain the existing native G1–G8 qualification requirements; this policy evaluation does not replace them. The runtime guide’s physical-device requirements include backend confirmation, repeated turns, cancellation/death scenarios and sustained testing. Passing unit tests cannot promote a route to hardware-qualified. [R09]
16. Validation commands, rollout and rollback
The inspected README documents the following build/test entry points; these commands were not executed for this document. Run them in the repository’s required toolchain and inspect current task names before adding new variants. [R11]
./gradlew :app:testDebugUnitTest./gradlew :app:lintDebug :app:assembleDebugpython3 scripts/check_local_runtime_apk.py \  app/build/outputs/apk/debug/*.apk./gradlew :app:assembleDebugAndroidTest# Physical device required:./gradlew :app:connectedDebugAndroidTest./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
A debug APK passing packaging checks is not a qualified production release. Test the actual signed production build and the separate GenieX preview build where applicable; retain runtime/model hashes, firmware, build SHA and suite revision in results.
Rollout. Begin with internal accounting-only runs. Enable warm passage-selection assistance for explicit testers after fixture validation. Promote only measured cohorts, keeping unsupported/unknown tuples on the existing approved path. Use local feature settings unless an already authorized configuration mechanism exists; do not add a remote kill-switch service merely for this feature.
Rollback. Disable new planning/assistance and return to the legacy execution path while preserving privacy checks, crash guards and native cancellation ownership. Keep additive data readable; do not rely on destructive downgrade migrations. Keep pre-upgrade backups and verify that the supported rollback build can read the retained schema, or explicitly document reinstall/restore limitations before release.
Release record. For every flag and enabled runtime, record Implemented, Unit tested, Integration tested, Device tested and Released separately. Include unresolved limitations, denied/skipped cohorts, observed regressions and the exact rollback action. “Merged” and “build green” must not be substituted for device acceptance.
17. Risks and final acceptance checklist
Risk
Required mitigation
Compression loses essential evidence
Prefer exact passage selection; validate protected fields and citations; retain original sources.
Local work increases latency or battery use
Warm-only default, admission controls, paired task measurements and skip thresholds.
Orchestration duplicates existing owners
Ownership map, incremental adapters and single producer/terminal accounting.
Runtime changes break a qualified model
Exact-tuple evidence, dependency verification, quarantine and separate upgrade PRs.
Sharing leaks private evidence
Authorization-scoped cache keys, rechecks on every use and revocation invalidation.
Savings dashboard overstates benefits
Separate actual counts, forecasts, cache discounts, prices and paired comparisons.
Native recovery repeats an external action
Stable operation identity, unknown-outcome state and explicit reconciliation.
Settings become another fragmented subsystem
One persisted policy, shared components and deep links from existing screens.

The release owner should be able to answer yes to all of the following: every physical model/tool attempt has an owner and usage record; local helper failures and cancellation are visible; NPU labels reflect evidence rather than preference; user-selected profiles and privacy grants remain authoritative; source versions and exact passages survive preprocessing; budgets include retries and final synthesis; the ROG Phone 9 Pro route has the required matching device evidence; backup, rollback and disabled-feature behavior have been exercised.
Implementation handoff: start with Stage 0 and Stage 1. The first merge should make execution ownership and token accounting demonstrably correct without changing which model answers the user. Add local optimization only after that baseline is measurable.
18. Source register
Repository references below are pinned to the inspected commit. Source ranges are noted where only an excerpt was read. Documentation-only claims remain distinguished from verified source behavior. External documentation was consulted on October 13, 2026; resolve API and artifact compatibility against the pinned dependency set before implementation.
Repository sources
R01 — Baseline: main commit and merge metadata.
R02 — Runtime contract: LocalRuntime.kt. Lifecycle, events, native/estimated metrics, state and exclusive work.
R03 — Existing planner: PlannedLocalRuntime.kt. Qualification, admission and fallback state.
R04 — Shared operations: SharedToolCallBroker.kt. Same-turn read-only sharing and producer attribution.
R05 — Delegation excerpt: LocalDelegationCoordinator.kt, lines 1–240.
R06 — Context selection: ContextBuilder.kt.
R07 — Chat integration excerpt: ChatRepositoryImpl.kt, lines 1–160.
R08 — Dependency pins: gradle/libs.versions.toml.
R09 — Documented runtime contracts: GPU/NPU execution plans. Process isolation, qualification, disabled experiments and device-test boundaries.
R10 — Documented research contracts: Deep Research academic integration.
R11 — User/build baseline: README.md.
R12 — File inventory: Pinned repository tree. Directory inventory used only for path/workflow presence, not their implementation status.
Primary external sources
W01 — Google: LiteRT-LM project. Language-model pipeline role and supported execution framework.
W02 — Google: LiteRT project. Utility/generative model distinction and current CompiledModel direction.
W03 — Google: Qualcomm NPU integration. Lists SM8750 support; its example dependency versions are not repository upgrade instructions.
W04 — Qualcomm: GenieX architecture. Separate llama.cpp and QAIRT plugin paths; developer-preview qualification remains necessary.
W05 — Android Developers: Thermal API guidance. Thermal monitoring, unknown readings and polling constraints.
W06 — Qualcomm: On-device AI development. QAIRT, LiteRT and deployment-toolchain roles.
No implementation or performance claim is made by delivering this specification. All proposed APIs, defaults, flags, test thresholds and new file names require review and validation against the intended implementation branch.