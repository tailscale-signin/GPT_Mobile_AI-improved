# Diagnostics Reliability — Design and Implementation Plan

**Project:** GPT Mobile AI Improved
**Design date:** October 12, 2026, America/Toronto
**Status:** Proposed implementation; documentation only. No runtime fixes or device qualification are claimed.
**Audited application:** `0.9.51.0`, build `120`, debug, package `dev.melo.gptmobile.improved`
**Audited repository commit:** `1d4dcd8698356abddc5f1be946bb0811017b7d7a`
**Repository:** `tailscale-signin/GPT_Mobile_AI-improved`

## 1. Executive decision

Fix admission and failure classification before increasing concurrency, retries, context sizes, or research limits. The dominant failure is a local CPU engine initialization error: **32 model requests fail with `Input tensor not found`**, comprising **28 benchmark requests and four primary requests**. Repeating this deterministic failure is not a useful benchmark and cannot repair an incompatible artifact/runtime contract.

The other important problems are overly short provider rate-limit retries, MCP authentication health that can be overwritten by a successful connection observation, very large tool schemas, cancellation without a recorded cause, unsuccessful source reads, and diagnostics that multiply stack frames into issue counts while losing multiline event relationships. Two tool-error records also expose search query text; the public design deliberately does not reproduce those queries.

Build on the existing process-isolated runtime, capability inspection, context planner, MCP health ordering and circuit breakers, benchmark safeguards, research persistence, cancellation propagation, and diagnostic redaction. Do not replace working components with parallel systems. The required improvement is a consistent contract between them.

The implementation must make these outcomes visibly different: **ready, waiting, unsupported, authentication required, rate limited, unavailable, partially completed, cancelled, and failed**. A transport connection is not evidence that a tool can execute; a completed worker is not evidence that semantic memory enrichment completed; an initialization failure is not a model intelligence score.

## 2. Evidence, scope, and audit method

### 2.1 Evidence identity

The reviewed attachment is `app-diagnostics-266568010219317601.jsonl`.

- SHA-256: `36520c493ccc9b088a70c2abf5808533747e0c8ace077c2828c7d2f24c36b68f`.
- Size: **3,144,089 bytes**; **7,261 valid JSONL records**, schema 3.
- Export: **2026-10-13 02:55:30.122461 UTC**, equivalent to **October 12, 2026, 10:55:30 p.m. Toronto**.
- Recorded activity spans approximately **02:23:46–02:55:22 UTC**. Export ordering includes a critical-log preamble; physical file order is not a universally chronological event order.
- Two application process epochs are present. Their presence alone does not establish a crash.
- The export declares `droppedRecords=0` and `vendor_log_available=false`. Those values do not establish complete native-worker, vendor, or preceding-session coverage.

**Evidence references below use original, one-based JSONL physical row numbers**, not source-code line numbers or a viewer's metadata lines. The raw log is intentionally not committed to the repository. It includes user-specific diagnostic material, so reproduction fixtures must be synthetic or separately sanitized.

### 2.2 Verified counts

| Measurement | Observed result | Interpretation |
| --- | ---: | --- |
| JSONL records | 7,261 | Includes continuation lines and duplicates |
| Event records / continuation records | 2,687 / 4,574 | Not all records represent separate operations |
| Records marked duplicate | 3,650 | Content-based continuation deduplication needs correction |
| Nonduplicate event records | 2,661 | Base population for event-level counts |
| Nonduplicate event levels | 1,783 I; 565 E; 203 D; 108 W; 2 V | Severity is not a distinct-defect count |
| Records with `countAsIssue=true` | 669 | Not 669 independent defects |
| Model terminal events | 94 | 58 completed; 36 failed |
| Benchmark terminal events | 76 | 47 completed; 29 failed |
| Primary terminal events | 17 | 10 completed; seven failed |
| Synthesis terminal events | 1 | Completed |
| Local CPU initialization failures | 32 | 28 benchmark; four primary |
| Other model failures | 4 | One missing local model; two HTTP 429; one DNS failure |
| Local adapter Android error lines | 512 | Repeated stack/error lines associated with the 32 local failures |
| Recorded HTTP 429 response headers | 7 | Response count, not seven unique user requests |
| Provider retry warnings | 7 | Five HTTP 429; one socket abort; one DNS failure |
| MCP authentication-required observations | 6 | Same connection, not six independent integrations |
| Frame-timing windows | 176 | Window summaries, not individual traces |
| Frames / janky frames reported in those windows | 89,342 / 777 | About 0.87% by summed counters; not a release-build performance result |
| Highest window `maxMs` | 458.730008 ms | A slow frame; do not label it an ANR or a >700 ms frozen frame |

All 93 captured model request IDs have a terminal event. One additional failed benchmark terminal event has no matching request-start record in this export. Do not infer an orphaned live request from that difference.

The summed recorded durations of the 32 local-failed requests are **184,421 ms**. This is cumulative request duration, not guaranteed non-overlapping wall-clock delay or measured CPU time. Individual durations range from **1,107 to 6,814 ms**.

### 2.3 Confidence rules

**Observed** means directly represented in the log. **Code-confirmed** means demonstrated by the inspected pinned source. **Hypothesis** means a plausible explanation requiring a targeted test. **Enhancement** means a proposed improvement, not an assertion that an unobserved feature is broken.

No `FATAL EXCEPTION`, `Fatal signal`, `ANR in`, or `OutOfMemoryError` signature was found in the deduplicated event scan. This does not prove that none occurred outside the export's coverage. The exact failed model artifact, its checksum, and the bundled native runtime build identity are not sufficiently established by the recorded error to prescribe a particular replacement model or dependency version.

## 3. Prioritized finding register

Priorities are local to this remediation plan: **P0** blocks a core feature; **P1** affects reliability, privacy, or trustworthy results; **P2** affects efficiency, usability, or diagnosability. Related findings are separated where they require different implementation or acceptance work.

| ID | Priority | Finding and evidence | Required correction |
| --- | --- | --- | --- |
| D01 | P0 | **Local CPU engine initialization fails 32 times.** Representative rows 24–44, 54–71, 5747–5766, 7033–7052 contain the missing-input-tensor failure. | Qualify the artifact/runtime/backend contract; return a typed nonretryable compatibility failure and an actionable compatibility report. |
| D02 | P1 | **Benchmark execution amplifies the same local failure.** 28 failed benchmark requests continue across speed, instruction, logic, context, extraction, recall, JSON, and intelligence cases; rows 24–778. | One readiness qualification per immutable configuration; block remaining dependent cases after deterministic initialization failure. Keep availability separate from quality scores. |
| D03 | P1 | **Diagnostic redaction is inconsistent.** Rows 6280–6281 include raw search queries inside nested tool-error JSON, although other headers and paths are redacted. | Redact structured fields before formatting and truncation; do not log arbitrary tool-result strings. No credential leak or remote exfiltration is established by this finding. |
| D04 | P1 | **MCP health conflates transport/catalog success with authenticated execution.** Authentication required at row 5816 is followed by connected at row 5844, about 21 ms later; authentication required recurs at 6315, 6764, 6839, 6898. | Separate connection, catalog, authorization, quota, and per-tool readiness. An unrelated success cannot clear an authorization block. |
| D05 | P1 | **HTTP 429 retry delays are too short for dependable rate-limit recovery.** Five delays are 204, 538, 266, 227, and 404 ms at rows 3380, 3405, 6066, 6550, 6599. Two primary requests terminate with HTTP 429 at 3430 and 6624. | Preserve typed status and retry metadata; honor `Retry-After` when supplied and coordinate a shared provider/account cooldown. The log does not establish that a supplied Retry-After header was ignored. |
| D06 | P1 | **Local MCP transport is intermittently unreachable or interrupted.** Connect-timeout health events at 1312, 1314, 1484, 1486; unexpected end of stream at 5843; circuit-open event at 5970. | Align discovery/transport deadlines, diagnose endpoint reachability, and expose useful recovery actions. Preserve the working circuit breaker rather than bypassing it. |
| D07 | P1 | **A missing local installation reaches execution.** Row 1836 reports that the selected model is not downloaded. | Resolve installation readiness before launching a chat or benchmark. Distinguish missing, importing, corrupt, unqualified, and incompatible assets. |
| D08 | P1 | **Provider socket/DNS failure interrupts a benchmark.** Socket abort and DNS retries at 4912, 4923; failed model segment at 4934. | Network-aware backoff and an infrastructure-error benchmark outcome; avoid repeatedly retrying DNS immediately on an unchanged unavailable network. |
| D09 | P1 | **Research work is dispatched after tools become budget-blocked or unavailable.** Brave repeat-limit rejection at 6023; subsequent search failures report cooldown at 6280–6281. | Replan against canonical tool health and remaining budgets before dispatch. Keep legitimate demand resumable; never bypass a limit using aliases. |
| D10 | P1 | **Academic research cancellation has no recorded origin.** Rows 3352 and 6521 explicitly say origin not reported. | Carry cancellation origin, parent run, deadline owner, completed-work count, and resumability through every layer. User cancellation, timeout, and failed execution must not share one generic failure category. |
| D11 | P2 | **Seven source reads fail across transport, access, and size categories.** ReadUrl rows 1702–1704, 6468, 6470–6471, 6497. Three are HTTP 403; two connect failures; one stream reset; one size rejection. | Typed source outcomes, bounded transient retry, and legitimate alternative sources. Never present blocked/unread pages as verified full-text evidence. |
| D12 | P2 | **The 1 MiB reader limit causes a dead-end handoff.** Rows 6496–6497 retain 1,048,576 bytes and then request bounded document extraction. | Provide a resumable document-extraction handoff with byte/page limits and user-visible partial coverage. Do not remove the memory guard or treat a binary prefix as a complete document. |
| D13 | P1 | **Tool schemas dominate several request budgets.** Rows 2514, 2675, 5569, 5586, 5645, 6773, 6853, 6903 show roughly 38,895–45,677 estimated schema tokens; total request budget reaches 49,039. | Capability- and task-scoped tool discovery; stage-specific preselection and actual serialized-request budget reporting. These are estimates, not confirmed billable tokens. |
| D14 | P2 | **Capability rejection occurs after unnecessary tool preparation.** Four local requests receive 85, 86, 86, and 75 bound tools, then select zero because the artifact is chat-only. They emit 332 individual exclusion records. | Filter before schema construction and tool binding. Log one exclusion summary, with optional detail. The tools were excluded, not actually registered or executed natively. |
| D15 | P2 | **Resume/context assembly can reintroduce large prior text.** Row 6343 reuses 110,355 characters inline; row 6523 estimates 38,407 current-message tokens. Five run-continuation actions also share a source message at 4951–4955. | Resume through reference-backed evidence and validated receipts; compact large text and preserve provenance. Multiple actions may be intentional combined-mode fan-out; duplicate side effects are not proven. |
| D16 | P2 | **FTS5 capability probing appears as a hard SQLite error despite recovery.** Row 78 fails to create FTS5; row 79 reports FTS4 ready; row 1457 reuses FTS4 without rebuilding. | Capability-aware index selection and recovered-event classification. Preserve memory data and the working FTS4 fallback. |
| D17 | P2 | **Semantic enrichment waits for a loaded model.** Rows 1742–1751, 5915–5924, 6510–6514 report waiting/deferred while WorkManager eventually reports succeeded. | Persist a semantic pending outcome, show waiting status, and resume on model-ready with deduplication. Worker success is not semantic completion; data loss is not established. |
| D18 | P1 | **Visible performance stalls are recorded.** Five Choreographer warnings report 40, 46, 64, 44, and 33 skipped frames at rows 47, 673, 1000, 1307, 1758; the maximum recorded frame is at 1310. | Profile representative release-like builds, attribute main-thread work, and bound UI/diagnostic update cost using the current refresh-rate budget. |
| D19 | P2 | **The chat coroutine exceeds ART compiler instruction limits.** Six messages name `ChatRepositoryImpl$completeChat$1.invokeSuspend` at 2513, 4956, 5585, 5644, 6770, 6897. | Decompose the orchestration into smaller stateful stages. This is a concrete maintainability/performance risk, not proof that it caused every slow frame. |
| D20 | P1 | **Error accounting counts stack lines as separate issues.** 512 Android local-adapter error records accompany 32 local failures; total issue-marked records are 669. | Separate incident family, occurrence, transport attempt, stack attachment, and capture duplicate. Preserve all useful detail without inflating headline counts. |
| D21 | P1 | **Multiline events lose causal identity.** All 4,574 continuation records lack `parentEventId`; the formatter can append the original event ID only on a later line. Identical continuation content is marked duplicate across unrelated operations. | Persist one structured event before rendering; retain parent and sequence for attachments; deduplicate by capture identity rather than message content alone. |
| D22 | P2 | **Truncation metadata is not end-to-end truthful.** Rows 6280–6281 contain visibly cut-off error JSON yet `truncated=false`; every export record has false. | Carry source truncation, original length, retained length, and reason through the pipeline. Export-only truncation status is insufficient. |
| D23 | P1 | **MCP exception statistics can stay UNKNOWN.** Code-confirmed: `McpClientManager.callTool` initializes outcome UNKNOWN, assigns it only after a returned result, and records it in finally; exception/cancellation branches do not assign a terminal outcome in the inspected code. | Record failure/cancellation explicitly. Track preflight rejection separately from a started transport attempt. The exact effect on the displayed aggregate denominator requires integration testing. |
| D24 | P2 | **Process/coverage metadata cannot explain a restart.** Two application epochs occur; vendor logs are unavailable; the recorder starts PID-filtered logcat. | Add supported process-exit history and explicit app/native-worker capture coverage. Do not infer crash cause from a restart or promise inaccessible vendor logs. |

### 3.1 Secondary warnings: disposition rather than blanket fixes

| Signature | Evidence | Disposition |
| --- | --- | --- |
| ASUS hidden-setting finalizer SecurityException | Rows 988–999 | Vendor/framework stack involving a restricted setting. Keep as a vendor observation; do not request system-only permissions or claim an app crash from it. |
| Inactive input connection | 25 text-before-cursor, four cursor-update, three selected-text warnings | Group by keyboard/navigation transition. Add focus, disposal, and keyboard regression tests; not 32 independent defects. |
| Missing IME callback | Row 1450 | Input lifecycle warning. Correlate with the affected screen; no crash demonstrated. |
| Feedback tensors disabled for multi-signature model | Rows 93, 1501, 4971 | Optional feature disabled. Not equivalent to the language model's missing-input-tensor initialization failure. |
| Restricted SoC property lookup | Rows 90, 1495 | Use public platform/device capability data or an explicit unknown state. Do not require root. |
| Autofill session cancellation | Rows 840, 885 | Usually a lifecycle observation; test only if user-visible autofill breaks. |
| Finalizer-only reference / monitor inflation warning | Rows 678, 2816 / 652 | Preserve low-priority runtime evidence. No memory leak or OOM is proven. |
| READ_DEVICE_CONFIG unavailable to jank monitor | Recorded Android warning | Platform monitoring capability unavailable; use supported app-level metrics rather than privileged permission workarounds. |
| JNI thread attachment notices | Nine informational observations | Attachment/detachment logging alone is not a JNI crash. |
| Embedding engine releases on memory pressure | Critical preamble, rows 3–17 | Verified first input completes with dimension 100; released handles return to zero. Preserve resource release. Subsequent creation alone does not prove a leak. |
| MCP discovery legacy fallback | Negotiation status 200/400/404 appears before successful connections | An individual discovery probe status is not automatically an invocation failure. Distinguish unsupported probe from failed initialization. |
| Memory NO_ELIGIBLE_FACTS and KEEP enqueue policy | Memory lifecycle records | A skipped semantic extraction or coalesced enqueue can be correct. Test the persisted outcome rather than treating every worker that adds no facts as broken. |

## 4. Repository integration map

Paths below are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`. Code review is pinned to the commit in section 2; implementers must recheck changed contracts when rebasing.

| Existing component | What is already present | Intended change |
| --- | --- | --- |
| `data/agent/provider/LiteRtLmAdapter.kt` | Installed-model resolution, metadata/backend checks, exclusive inspection, native context planning, tool filtering, compact fallback | Move readiness/capability decisions earlier; pass typed failures; summarize exclusions; preserve inspection and engine locking. [S1] |
| `data/localruntime/ProcessLocalRuntime.kt` | Separate inference process, LOAD/INSPECT commands, cancellation, worker shutdown, typed remote errors/load attempts | Extend IPC failure/readiness data; preserve cancellation acknowledgement and process isolation. [S2] |
| `data/localmodel/ModelArtifactManifest.kt`, `ModelCompatibilityRecord.kt`, `LocalModelInstallationEpoch.kt`, `LocalModelLocator.kt`, `LocalModelReconciler.kt` | Existing artifact/installation/compatibility components verified in the tree | Reuse these identities for qualification and cache invalidation; verify APIs before edits. Do not introduce an unrelated installation registry. |
| `data/benchmark/BenchmarkRunner.kt` | Synthetic-only fixtures, unsupported-tool exclusion, deadline-bounded reconnect before output/tool activity | Add typed infrastructure outcomes at the suite coordinator; retain these existing safety properties. [S3] |
| `data/benchmark/BenchmarkExecutionCoordinator.kt`, `BenchmarkStore.kt`, `BenchmarkScoreboard.kt`, `RuntimeReleaseGates.kt` | Existing execution, storage, comparison, release-gate files | Implement qualification, blocked cases, versioned result semantics, and publication eligibility. |
| `data/agent/tool/McpClientManager.kt` | Session and catalog caching, in-flight setup, `McpHealthObservationOrder`, auth/quota cooldowns, transport statistics | Fix semantic health precedence and UNKNOWN exception outcomes; reuse existing single-flight and observation ordering. [S4] |
| `data/agent/tool/AgentToolResolver.kt` | Tool selection, OAuth refresh, native integrations, research sessions, statistics dependencies | Resolve only eligible tools for the task, capability, permissions, and owner; coordinate catalog refresh. [S5] |
| `data/network/ResilientStreamingClient.kt`, `NetworkRetryUtils.kt` | Retry/backoff and cancellation handling already exist | Share typed classification, Retry-After, parent deadlines, and account cooldowns. Avoid stacked retries multiplying attempts. [S6][S7] |
| `data/repository/ChatRepositoryImpl.kt` | Combined/delegated execution, saved output reuse, context and persistence integration | Extract orchestration stages; perform readiness before expensive expansion; resume from receipts and evidence references. [S8] |
| `data/agent/tool/SharedToolCallBroker.kt`, `data/agent/ToolExecutionBudget.kt`, `data/context/ContextBuilder.kt` | Existing broker/budget/context integration identified by imports | Enforce canonical work identity and stage-scoped budgets without bypassing consent or limits. |
| `data/research/ResearchSessionStore.kt`, `ResearchCorpus.kt`, `ResearchJournal.kt`, `ResearchResultCache.kt`, `SharedResearchSources.kt`, `SourceIdentity.kt` | Existing research persistence and source-identity files verified in the tree | Add durable task outcomes and leased acquisition where missing; reuse the existing source store rather than build a second one. |
| `data/memory/MemoryGraphRepository.kt` and its index/enrichment collaborators | Memory repository integration identified in the resolver; log proves FTS4 and deferred outcomes | Verify concrete collaborator APIs during implementation, then add capability-aware index telemetry and persisted semantic waiting/resume behavior. |
| `data/diagnostics/AppLogRecorder.kt`, `data/security/DiagnosticRedactor.kt` | Opt-in recording, private-session pause, queue batching, PID-scoped Android capture, crash checkpoint | Structured event persistence, field-based redaction, typed coverage, and incident aggregation. Preserve the private-session pause. [S9] |
| `data/telemetry/tool/ToolStatisticsRepository.kt` | Existing statistics integration | Separate logical invocations, physical attempts, cache hits, blocked requests, failures, and cancellations. |

Files introduced below, including readiness, admission, and orchestration stage classes, are **proposed names**, not assertions that they already exist. Prefer extending an equivalent current abstraction after checking the implementation.

## 5. Local runtime and benchmark design

### 5.1 Resolve the actual tensor-contract failure

A filename, extension, catalog badge, or successful metadata read is insufficient to prove executable compatibility. The log proves executor initialization fails; it does not identify which required tensor is missing or whether the cause is an export/runtime mismatch, unsupported graph layout, incorrect backend package, or damaged installation.

The investigation sequence is: record artifact digest and installation epoch; record the loaded LiteRT-LM/native library identities; inspect metadata and graph/signature requirements using APIs available in the pinned runtime; verify the intended backend and companion assets; reproduce in the isolated worker using a minimal prompt; compare with a separately verified known-good artifact for that backend. Current official LiteRT-LM documentation describes model-introspection facilities; adopt them only after checking the app's pinned API and native ABI, not through an unqualified dependency bump. [U1]

Do not silently select CPU for an NPU-specific export, rename an artifact to change its type, delete installed files, or globally disable all local models. A replacement artifact or runtime upgrade is accepted only with recorded successful qualification on the intended device/backend.

### 5.2 Readiness identity and state

Introduce or extend `LocalReadinessRepository` with a key containing:

`artifactDigest + installationEpoch + runtimeBuildFingerprint + backend + relevantEngineConfiguration + deviceCapabilityFingerprint`.

Relevant configuration includes context shape, enabled modalities, companion assets, and any execution setting that changes the compiled engine. Do not include prompt contents or secrets in persisted keys.

States: `MISSING`, `IMPORTING`, `UNVERIFIED`, `VERIFYING`, `READY`, `TEMPORARILY_UNAVAILABLE`, `INCOMPATIBLE`, and `STALE`. Store the last successful verification, typed failure code, evidence digest, retry eligibility, and invalidation cause.

A bounded isolated qualification performs metadata validation, initialization, a minimal generation, and orderly close. Tool/vision workloads have separate readiness evidence. Concurrent callers share one qualification future. Cancellation detaches a waiter; it cancels the shared probe only when no owner still needs it. A deterministic tensor-contract failure is cached for that key and does not trigger repeated native loads on every benchmark case.

Invalidate on artifact replacement, installation epoch change, relevant runtime/configuration change, or explicit user verification. An explicit recheck permits one new probe; it does not permanently remove compatibility controls. Resource pressure and temporary worker failure must not permanently quarantine a valid artifact.

### 5.3 Admission order

Use the same admission service for chat, combined profiles, delegation, local assist, and benchmarks:

1. Validate profile identity and installed asset.
2. Resolve permissions, capabilities, and readiness from lightweight metadata/cache.
3. Acquire local runtime ownership or show a cancellable queue.
4. Select only task-relevant tools and evidence within the effective context budget.
5. Execute and persist a typed terminal outcome.

For combined mode, reject only the unavailable child profile; keep healthy children running. Any switch from local to cloud, or to a differently priced provider/model, must follow existing explicit user permission and cost/privacy policy. The benchmark must never substitute a different model under the original model's identity.

### 5.4 Benchmark validity

Add suite-level `BLOCKED_BY_PREFLIGHT` and case-level `SKIPPED_DEPENDENCY`, while retaining existing supported/unsupported distinctions. Classify `ENGINE_INITIALIZATION`, `MISSING_ASSET`, `NETWORK_UNAVAILABLE`, `RATE_LIMITED`, and `CANCELLED` separately from a generated wrong answer.

Stop remaining cases after a nonretryable initialization failure; a shared profile/backend qualification must prevent the 28 repeated failed loads seen here. Preserve the runner's existing reconnect restriction: only before visible output or tool activity, and within the original deadline. [S3]

Maintain separate metrics: quality among valid completed cases, infrastructure availability, timeout rate, and completion coverage. Never display a perfect or comparable overall score from a cherry-picked subset of completed cases. An incomplete run must not replace a valid best result or enter the community scoreboard as a fully comparable run. Persist suite version, runtime/artifact identity, effective settings, warm/cold state, and queue/load/generation timings. Historical runs keep their original schema/eligibility; do not silently rewrite their scores.

## 6. MCP, provider, and research execution design

### 6.1 Semantic MCP health

`McpClientManager` already has observation ordering, cached catalogs, in-flight session setup, and cooldowns. Extend them instead of adding a second manager. In the inspected `callTool`, a returned result currently triggers `recordSuccess` before `isError` is classified; move semantic classification before readiness updates. Handshake success can update transport state, but cannot establish authorization or successful tool execution. [S4]

Represent independent transport, protocol, catalog, authorization, quota, and per-tool states. Key authorization by connection and credential revision, and scope restrictions to affected tools where the protocol provides that distinction. Use a generation/revision on each observation so stale work cannot clear a newer authorization failure. A same-credential catalog refresh must not implicitly clear an indefinite authentication block.

Keep cached tool descriptions visible but mark them unusable when blocked. Only credential change, a successful authorized validation, or a deliberate bounded user recheck may transition that state. Replace `Long.MAX_VALUE` presentation with `Requires action`; retain a nullable retry time or an explicit never-auto-retry value in the model.

OAuth recovery remains through the existing coordinator. Preserve authorization scope, token audience, protected-resource discovery, and approval boundaries according to the supported MCP protocol version. Do not solve failures by forwarding gateway credentials to models, removing authorization, or blindly replaying mutations. [U2]

### 6.2 Attempt and cancellation accounting

A logical invocation may be blocked before transport, served from cache, or have multiple physical attempts. Store these separately. Set the attempt terminal outcome inside each success, protocol-error, exception, and cancellation branch. The finally block persists that outcome exactly once. A preflight rejection increments a rejected-invocation metric, not a failed network-attempt metric.

Suggested outcomes are `SUCCESS`, `FAILED`, `CANCELLED`, `TIMED_OUT`, `REJECTED`, `CACHE_HIT`, and `UNKNOWN_LEGACY`. Preserve unknown only for genuinely unclassified historical records. Track cancellation origins such as user, parent cancellation, acquisition deadline, idle timeout, worker shutdown, and budget stop; a missing origin remains explicitly unknown rather than guessed.

### 6.3 Retry policy and provider coordination

Introduce or extend a typed `FailureEnvelope` containing category, HTTP status, retry-after, provider request ID, retry eligibility, received-output state, side-effect state, and sanitized diagnostic code. Current retries already use jitter, but the log shows subsecond 429 delays and the shared utilities do not expose a common typed HTTP retry contract. [S6][S7]

For 429, parse Retry-After when available. Otherwise use a conservative configurable cooldown with jitter, not an immediate burst. Maintain one limiter per relevant provider/account or credential scope, shared across combined profiles and benchmarks. Separate endpoint-wide outages from model-specific capacity failures. Retries consume the parent's original deadline and attempt budget; lower layers cannot each restart an independent allowance.

Pause or fail clearly when network validation/DNS is unavailable. Re-enter through a connectivity signal or a bounded delayed retry. Preserve valid partial output and completed tool receipts. Replay of a whole agent run is unsafe after side effects; resume at the failed stage instead. OpenRouter's own guidance distinguishes transient retries from permanent request/account failures and warns against replaying completed tool side effects. [U3][U4]

A TLS trust or certificate failure is not a reason to disable verification. Align the generic and streaming classifiers so certificate failures cannot accidentally be treated as ordinary transient transport errors. This is a code-hardening requirement, not an observed TLS incident in this log.

### 6.4 Research scheduler and evidence

Extend the existing research session/source store with canonical work identity, state, lease owner, attempt count, eligibility reason, and evidence provenance. Suggested identity combines session, canonical source/query identity, operation, and compatible credential/privacy scope. Share only read-only work whose permissions and freshness policy permit reuse.

Before dispatch, check engine readiness, tool repeat budget, provider cooldown, session deadline, and already completed evidence. A rejected Brave call must disappear from the current eligible plan rather than reappear through another alias. An unavailable engine is not an empty successful search.

Round-robin may distribute eligible jobs fairly, but must not multiply every URL by every engine and every profile. Lease fetch/extract jobs once, store results, and give profiles references to the same evidence. Track unique retrieved sources separately from engine attempts and duplicate search hits. Retain pending demand and allow explicit continuation when limits expire or the user extends the research session; do not silently override limits.

Use typed terminal acquisition states: `SUCCEEDED`, `PARTIAL`, `NO_RESULTS`, `AUTH_REQUIRED`, `RATE_LIMITED`, `ACCESS_DENIED`, `TRANSPORT_FAILED`, `TOO_LARGE`, `CANCELLED`, and `SKIPPED_BUDGET`. Every pending item needs a reason, owner or eligibility condition, and a next action. No permanently spinning generic pending rows.

Local assist is optional and readiness-gated. Deterministic local parsing, deduplication, and ranking can run without a language model. A healthy local model may summarize or rerank bounded text under device resource policy. It must not repeatedly try the incompatible engine from D01, turn a failed tool call into a fabricated source, or silently move private material to a remote fallback.

### 6.5 Source access and large documents

HTTP 403 should produce an access-restricted outcome, not repeated scraping attempts. Search legitimate alternative publishers, open repositories, abstracts, or user-provided accessible copies. A metadata-only record must retain that coverage label.

For the 1 MiB rejection, offer `Extract document` using the existing bounded document pipeline. Stream to a controlled temporary location, validate content type, enforce compressed and expanded byte/page limits, and preserve extraction progress. Binary formats require a valid document, not an arbitrary truncated prefix. Text prefixes can be exposed only as explicitly partial evidence. Failed extraction leaves the original source record and its failure reason intact.

## 7. Context, memory, and performance enhancements

### 7.1 Smaller task-specific context

Do not build 40,000-plus schema-token catalogs for a request that will eventually select a few tools or none. Maintain searchable tool metadata, choose categories from the task, then provide only the necessary full schemas. Keep an explicit authorized discovery route for additional tools; do not hide essential functionality permanently.

Measure context at both preselection and the final provider/native boundary. Report selected/omitted tool counts, estimated versus provider-reported token basis, compression ratio, and omission reasons. A stage estimate is not evidence that the entire catalog reached the model or was billed. Preserve effective output limits and the current local compact-fallback notices.

Replace large inline saved responses with durable evidence references, concise summaries, and targeted slices. Preserve unique facts and source links during compaction. Never compact away permission boundaries, current instructions, or evidence coverage labels. The five continuation actions in the log require a shared user-action identity plus per-profile child identities, not an assumption that combined mode should run only one profile.

### 7.2 Memory indexing and enrichment

Select FTS capability without repeatedly executing a known unsupported production DDL statement. Prefer supported capability metadata where reliable, otherwise use one contained probe per database/runtime identity. Preserve FTS4 and transactional index rebuild/recovery; test tokenization, escaping, diacritics, update/delete, and backup restore across both engines. FTS extensions are build-dependent; availability must not be inferred from SQLite version alone. [U5]

Separate scheduler state from semantic enrichment outcome. `SUCCEEDED + WAITING_FOR_MODEL` means the worker handed work back successfully, not that the facts were enriched. Persist pending item identity and the waiting reason, and resume through a model-ready notification or explicit action. Avoid battery-draining polling and avoid auto-loading a large model solely because a background worker ran. KEEP/coalescing is preserved; semantic completion, skipped-no-facts, and deferred outcomes must remain distinguishable.

Embedding engines already release under memory pressure in this log. Preserve that behavior, track handles by process and generation, and add hysteresis before optional background reacquisition. Do not interpret process-wide native allocation as the size of one embedding model.

### 7.3 Orchestration decomposition and UI cost

Extract proposed stages such as `RunAdmission`, `ContextPreparation`, `ResearchAcquisition`, `PrimaryExecution`, `CombinedSynthesis`, and `RunFinalization` from the oversized `completeChat` coroutine. Use a typed immutable run context and explicit state transitions; avoid replacing one huge method with a new global mutable coordinator.

Preserve atomic persistence and existing completed-output recovery. Character-count-only usability is not a new acceptance criterion: validate the appropriate terminal result/receipt and mark partial output distinctly. Do not change a failed no-output request into success merely because an error message or tool trace is nonempty.

Profile before attributing all jank to any one component. Reproduce launch, local-model rejection, keyboard dismissal, tool discovery, combined resume, streaming, and diagnostics scrolling. Use release-like builds and current-refresh-rate frame budgets; Android documents the lower per-frame budget at higher refresh rates and the distortions introduced by debug builds. [U6]

The recorder already batches work and caps diagnostic snapshots at approximately 20 per second. Preserve that improvement. Further reduce publication frequency when the diagnostics screen is hidden, aggregate repetitive exclusions, avoid parsing/log formatting on the main thread, and use stable UI state/list keys. Performance targets in section 10 are proposed acceptance targets, not measured improvements.

## 8. Diagnostics schema and privacy

Introduce a versioned structured event schema without making legacy exports unreadable. A root event contains identity, process epoch, timestamp and monotonic timing where available, run/invocation/attempt IDs, stage, outcome, typed failure, recovery state, safe dimensions, and optional attachment references. Render human-readable text from this event, not the reverse.

Stack frames belong to an exception attachment. An incident family groups equivalent failures; an occurrence records a real attempt; a capture duplicate records the same event copied into another source. These three numbers must remain independent. Two distinct identical failures must not deduplicate merely because they share an error line.

For legacy multiline import, reconstruct groups conservatively by capture source and boundaries. Never attach a shared `Input tensor not found` line to another request merely because the content matches. Mark unresolved parentage as unknown. Normalize status to an enum and store explanatory text separately instead of embedding it into the status value.

For HTTP events, store status and semantic outcome, method, safe host/category, timing, bytes, and approved request identifiers. Preserve discovery-probe intent so a deliberate legacy negotiation 404 is not counted as a failed tool invocation. Response headers/body fragments are not independently meaningful success/error counts.

Redact at the structured boundary and again on export. Explicitly handle nested query, prompt, arguments, result, document, URL query-string, authorization, cookie, and error-body fields. Preserve only allowlisted safe metrics. Do not copy user search strings into a public issue, test fixture, or this document. Represent sensitive task correlation with random or keyed identifiers, not a plain hash that invites dictionary recovery.

Carry `truncatedAtSource`, `truncatedAtExport`, original/retained length, encoding, and truncation reason. Do not serialize truncated JSON as though it were a complete structured result. D22's cut-off payloads must retain their truncation marker even when the exporter itself performs no additional shortening.

Capture coverage must state sources, periods, process epochs, dropped/evicted records, native-worker availability, and unknown intervals. Add supported `ApplicationExitInfo` collection with API gating and redacted exit metadata; Android exposes distinct exit reasons, including Java crash, native crash, ANR, low-memory/resource-related outcomes, and other termination reasons. [U7] Do not require privileged vendor logs or infer the exit reason from the ASUS finalizer warning.

## 9. Clean recovery UI

Enhance existing Local AI, Benchmark, Plugins/Tools, and Diagnostics screens rather than introduce another disconnected settings subsystem.

Use a shared health card with an icon, a short status, one-sentence explanation, and one primary recovery action. Examples: `Model incompatible → View compatibility`; `Not installed → Download or relink`; `Authorization required → Manage connection`; `Rate limited → Resume when available`; `Source too large → Extract document`.

For richer detail, use three consistent views: **Overview**, **Activity**, and **Details**. Overview shows useful readiness and blockers. Activity groups actual operations and their outcomes. Details contains runtime/artifact identity, source coverage, timing, and safe export controls. Advanced explanation belongs behind a help button, not in every card.

Benchmarks show preflight separately from scored work and explain why remaining cases were blocked. Research lists show waiting reasons and completed-source counts rather than undifferentiated spinners. Memory distinguishes indexed, waiting for model, no eligible facts, and completed enrichment. MCP cards distinguish connected transport from authorized executable tools.

Use theme-aware Material icons with accompanying text, accessible contrast, a 48 dp touch-target design target, scalable text, reduced-motion behavior, and TalkBack labels. Color alone must not encode success or failure. Keep selection and scroll position stable during health updates. Repeated errors collapse into a card such as `Engine initialization failed · 32 occurrences`, with expandable evidence instead of 512 stack-line alerts.

## 10. Implementation work packages and acceptance

### 10.1 Delivery sequence

| Package | Scope | Dependencies | Exit gate |
| --- | --- | --- | --- |
| W0 — Reproducible evidence | Synthetic fixtures, log grouping checks, failure taxonomy, baseline metrics | None | Counts and representative failure families reproduce without private content |
| W1 — Local readiness | D01, D02, D07, D14; qualification and installation integration | W0 | Incompatible configuration produces one bounded qualification, then no repeated loads; valid artifact generates successfully |
| W2 — Remote/MCP semantics | D04–D06, D08, D23; auth precedence, typed retries, statistics | W0 | Deterministic auth/rate/timeout tests pass; no premature auth-state clearing |
| W3 — Research/evidence | D09–D12, D15; canonical jobs, cancellation, document handoff | W2 | Completed evidence survives cancellation/resume; blocked work is not redispatched through aliases |
| W4 — Context and orchestration | D13, D14, D18, D19; early filtering and stage extraction | W1, W3 | Quality/coverage preserved with smaller context; release-like traces meet agreed targets |
| W5 — Memory state | D16, D17; FTS selection and semantic waiting | W1 | FTS4/5 migration tests and exactly-once deferred completion pass |
| W6 — Diagnostic correctness/privacy | D03, D20–D22, D24 | W0; integrate each package incrementally | No query/secret leakage in fixtures; truthful grouping/truncation/coverage |
| W7 — UI and release qualification | Shared recovery states and device matrix | W1–W6 | Accessible UI, failure-injection, backup/restore, and physical-device gates pass |

Start privacy containment in W0/W6 immediately; it need not wait for the runtime work. Each implementation PR must include tests and map changed files to the relevant D IDs. This design PR does not change application behavior, dependencies, version numbers, or release artifacts.

### 10.2 Required tests

Proposed test names describe new or expanded tests, not confirmed existing files.

| Test / scenario | Required assertion |
| --- | --- |
| `LocalReadinessRepositoryTest`: 30 simultaneous callers, incompatible tensor fixture | One qualification per key; all callers receive the same typed incompatibility; no 30 native load attempts |
| Runtime/artifact/configuration update | Old qualification invalidates; a changed valid package can recover; unrelated healthy models remain available |
| Missing/relinked/deleted file and interrupted import | Correct installation state; no execution against incomplete assets; no silent deletion or cloud substitution |
| Valid CPU/GPU/NPU backend fixtures | Test only qualified artifacts for each backend; unsupported backends fail at preflight, not arbitrary fallback |
| Worker death and cancellation during initialization/generation | Main UI survives; bounded acknowledgement/shutdown; terminal outcome once; no repeated completed tools |
| `BenchmarkPreflightTest` | Failed qualification blocks dependent cases; incomplete run cannot replace valid best score or publish as comparable |
| `McpHealthPrecedenceTest`: failed tool auth plus concurrent successful listTools | Authorization remains blocked for the unchanged credential; transport/catalog may independently remain healthy |
| Credential revision / explicit reconnect | One controlled validation; previous-generation successes cannot overwrite new failures |
| `McpAttemptOutcomeTest` | Success, tool error, transport exception, cancellation, timeout, and preflight rejection update the correct counters exactly once |
| `ProviderRetryAfterTest` | Supplied delay honored; absent delay uses configured backoff; same account shares cooldown; parent deadline never resets |
| DNS loss / network transition / TLS trust error | Network-aware recovery; no blind tight loop; no certificate-verification bypass |
| `ResearchBudgetAdmissionTest` | Canonical aliases share applicable limits; rejected work does not execute; remaining demand is resumable |
| `ResearchCancellationTest` | Origin and owner recorded; already verified evidence survives; user cancellation is not a provider failure |
| Access denied / empty results / transport failure | Distinct outcomes and counts; unread pages never become verified full-text citations |
| `LargeDocumentHandoffTest` | Valid bounded extraction, cancellation cleanup, decompression/page limits, explicit partial coverage |
| `ToolCatalogBudgetTest` | Chat-only local artifact builds no executable tool schema; selective tasks retain essential tools and permission checks |
| `ResumeEvidenceTest` | Large prior text replaced with references/slices; unique facts retained; same read-only evidence reused only within permission/freshness boundaries |
| `MemoryIndexCapabilityTest` | FTS4 and FTS5 create/update/delete/query/restore behavior; unsupported capability reported once and recovered |
| `MemoryDeferredResumeTest` | Waiting item survives worker success and process restart; model-ready resumes once; no unnecessary periodic model loading |
| `DiagnosticIncidentGroupingTest` | One stack becomes one occurrence with attachment; 32 actual attempts remain 32; duplicate capture does not add occurrences |
| `DiagnosticRedactionTest` | Nested queries, credentials, URLs, exceptions, and truncated payloads cannot leak fixture secrets |
| `DiagnosticTruncationTest` | Original/retained lengths and reasons survive formatting/export; incomplete JSON is not marked complete |
| Process-exit metadata unavailable / available | Supported reasons displayed accurately; unknown and inaccessible sources stay explicitly unknown |
| UI and performance journeys | Keyboard/focus stable; cards accessible at large font sizes; state updates do not shift controls or block cancellation |

### 10.3 Proposed measurable release gates

Use an agreed reference device, a release-like build, fixed scenario inputs, and recorded thermal/power conditions. These are targets to validate and tune, not results from this audit.

- **Correctness:** zero repeated automatic native loads for an unchanged deterministically incompatible readiness key after its first qualification; zero missing terminal outcomes in deterministic fixtures.
- **Privacy:** zero raw fixture queries, credentials, document text, or private endpoint identifiers in the default diagnostic export.
- **Recovery:** zero duplicate mutating tool executions during tested retry/resume scenarios; preserve all completed evidence receipts in those tests.
- **UI:** cached blocked/ready state appears within 250 ms under normal reference-device conditions; cancellation acknowledgement is immediate in the UI even while bounded worker cleanup continues.
- **Rendering:** no >700 ms frozen frame in scripted acceptance journeys; target at least a 50% reduction in reproducible >100 ms stalls relative to a measured comparable baseline; report p95/p99 by active refresh rate.
- **Efficiency:** for the audited high-schema scenarios, target at least an 80% reduction in selected schema tokens without losing task-required capabilities. Measure request bytes and model-reported usage where available; do not equate estimates with billed usage.
- **Diagnostics:** incident counts remain stable when the same source events are exported from multiple capture files; source truncation and missing coverage are always represented.

An emulator can validate Kotlin logic, database migration, HTTP fixtures, and UI states. It cannot qualify the affected phone's native inference backend. Physical-device tests must use the exact installed artifact and relevant native libraries; no native-runtime success claim is permitted from unit tests alone.

## 11. Migration, rollback, and open questions

Use additive migrations for readiness metadata, attempt outcomes, pending research/enrichment state, and diagnostic schema. Retain readers for legacy schema 3. Mark historical unclassified outcomes as legacy unknown rather than manufacturing accuracy. Benchmark eligibility/scoring changes require a new explicit schema/suite-policy version; preserve original results and timestamps.

Feature flags may stage the new scheduler/context selection and UI. Rolling back must preserve stored chats, memory, evidence, and installed models. Do not roll back by disabling authentication, TLS validation, consent, or byte/resource limits. A stale qualification becomes unverified after rollback to another runtime; it does not become ready automatically.

Unresolved implementation inputs are the exact failed model artifact and digest, the bundled native runtime versions, backend-specific graph requirements, actual provider retry metadata at the failing boundary, semantic enrichment wake-up behavior outside the captured interval, and release-like performance traces. Resolve these through targeted instrumentation and tests. They are not grounds to assume a universal QNN problem, a corrupted download, a broken memory database, a whole-provider outage, or a proven native crash.

**Definition of done:** every D finding has an implemented correction or documented expected/vendor disposition, corresponding regression coverage, truthful UI/telemetry, and successful integration gates. D01 additionally requires demonstrated generation on a qualified target artifact/backend. Documentation publication alone does not satisfy implementation completion.

## 12. Source references

Repository links below are pinned to the audited commit. File-tree-verified integration targets in section 4 are intentionally distinguished from source ranges inspected in detail.

[S1]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/provider/LiteRtLmAdapter.kt#L100-L355
[S2]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/ProcessLocalRuntime.kt#L36-L220
[S3]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/benchmark/BenchmarkRunner.kt#L72-L200
[S4]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/McpClientManager.kt#L35-L240
[S5]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/AgentToolResolver.kt#L46-L95
[S6]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/ResilientStreamingClient.kt
[S7]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/NetworkRetryUtils.kt
[S8]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L1-L210
[S9]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/AppLogRecorder.kt#L25-L160
[U1]: https://developers.google.com/edge/litert-lm
[U2]: https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization
[U3]: https://openrouter.ai/blog/tutorials/text-to-speech/
[U4]: https://openrouter.ai/blog/tutorials/create-agent-harness-with-agent-sdk/
[U5]: https://www.sqlite.org/fts5.html
[U6]: https://developer.android.com/topic/performance/issues/render
[U7]: https://developer.android.com/reference/android/app/ApplicationExitInfo

External references were checked during this audit. U3 is OpenRouter's production retry guidance in its audio tutorial, used here only for its general status/Retry-After guidance; it is not treated as evidence that audio endpoints were involved in the log. The observed failures are chat/model, MCP, and source-acquisition operations.
