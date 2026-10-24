# Research Mode Stability and Recovery Design

Implementation document for GPT Mobile AI

Prepared October 23 2026 Toronto time

**Status:** Research stability design based on the attached Research Chat export and repository inspection. The core scheduler, durable-attempt, budget, and export repairs below remain proposed implementation work. Appendix D records the main-menu and free-provider changes added to this branch following the user's implementation request, including restored anonymous LLM7 access.

**Repository:** `tailscale-signin/GPT_Mobile_AI-improved`  
**Inspected source:** main commit `46413444b62c3f0d6db78b09432cd874b29fa6d6`  
**Exported app:** version `0.9.56.1`, build `127`. The export does not identify the installed app commit. The inspected revision is therefore the source baseline, not a claimed APK identity.

## 1 Purpose and decision

Research failed before evidence collection in this session. Seven selected online profiles waited approximately 60 seconds for inference resources held by a local benchmark, then failed without dispatch. Pollinations and Hugging Face were rejected immediately because the routes in the exported build did not expose an enforceable text output bound for Combined mode. Every assistant answer is empty. No research session, source collection, provider invocation, or final report is retained. The Pollinations route is updated by the follow-up in Appendix D. [E1–E4]

The first implementation priority is resource admission and recovery. Replace the global benchmark gate with admission that understands the requested resource and the benchmark measurement policy. Persist waiting as a task state, preserve the accepted question, and resume eligible work once resources are available. Check route capabilities before accepting participant roles. A benchmark must not turn a temporary wait into seven apparent AI failures. [C1–C4]

The next priority is to make Research a durable task with explicit execution attempts. Keep the existing immutable accepted-task envelope, evidence manifests, authority checks, and source validation. Add an execution record that explains whether acquisition has started, what is blocking it, what evidence remains available, and which recovery action is valid. Source collection and analysis should continue through isolated provider failures when the accepted plan permits it. [C5–C8]

This document also specifies improvements to planning cost, diagnostic usefulness, source coverage, report quality, interface clarity, and benchmarking isolation. Findings marked **Observed** are supported by this export. **Code finding** identifies a source behavior that was not necessarily exercised here. **Enhancement** is a proposed improvement. These distinctions prevent an empty research run from being misdiagnosed as a crawler, citation, or synthesis-quality failure.

## 2 Evidence and review limits

All five archive entries were inspected. All four payload sizes and SHA256 digests listed in the manifest match. The conversation, nine run records, ten messages and their timelines, policy snapshots, budget state, 500 retained app records, 106 local runtime incidents, and twelve diagnostic records were examined. [E1–E7]

| Evidence | Retained amount | Interpretation |
| --- | ---: | --- |
| User requests and assistant messages | 1 request and 9 empty answers | No delivered research answer to assess |
| Contributor runs | 9 failed | 7 admission failures and 2 capability rejections |
| Accepted research tasks | 1 required task | Original intent and participants survive |
| Research sessions and retained evidence | 0 | No recorded acquisition or analysis result |
| App log snapshot | 500 records | Includes concurrent app-wide benchmark activity |
| Local runtime incidents | 106 records | Includes earlier history; these are not 106 research defects |
| Diagnostic event records | 12 informational palette records | These do not explain the admission failures |
| Event journal and context manifests | 0 events and an empty array | Full execution reconstruction is unavailable |
| Journal counters | 104 dropped events | Counter scope and cause need explicit metadata |
| Capture settings | Diagnostics collection disabled | Respect this setting when redesigning exports |

The retained app snapshot spans **11:39:12.559 p.m. to 11:41:25.737 p.m. on October 23, Toronto time**. Export occurred at **11:41:26.647 p.m.** The app names the conversation Research Chat, while its stored mode is `COMBINED` and its Research toggle is enabled. Depth 50 resolves to a source goal of 90 under the current settings mapping. The selected scope is `WEB_ONLY`. [E1, E2, E4, C11]

The arrays named `researchLifecycle.jobs` and `jobTransitions` contain Examination-job data. Their emptiness does not independently prove that a top-level Research scheduler lost a job. The stronger evidence is the empty `research` collection, no retained acquisition, and explicit pre-dispatch failures. [C5]

No provider outage, network failure, Java heap out-of-memory error, Android ANR, GPU driver crash, or failed source extraction is established by this turn. Token usage fields are **null**, so zero delivered characters must not be reported as measured zero tokens. There is no answer on which to score citation accuracy, source relevance, factual coverage, or profile-color attribution.

## 3 Reconstruction of the failure

| Toronto time | Recorded event | Consequence |
| --- | --- | --- |
| 11:39:30 p.m. | Research accepted for nine profiles | Required task and Combined policy persisted |
| 11:39:30 p.m. | Pollinations and Hugging Face rejected | Neither run receives a start timestamp |
| 11:39:30 to 11:40:30 p.m. | Seven other profiles await admission | No provider request is dispatched |
| 11:40:27 p.m. | Local benchmark case ends after 90.666 seconds | Cancellation acknowledged after 407 of 1,079 prompt tokens; zero sampled tokens |
| 11:40:27 p.m. | Next local benchmark case starts | Research remains blocked across the trial boundary |
| 11:40:30 p.m. | Seven admission waits expire | Runs become failed with the benchmark-held error |
| Before export | Combined delivery becomes recoverable failure | No evidence or report exists; unused work remains |

The seven blocked profiles are **Kilo, OVHcloud, BlockRun, AI Horde, Vireonix, BridgeNode, and Cehpoint**. Pollinations and Hugging Face have a separate, deterministic route restriction. Treating all nine as provider outages would send repair work in the wrong direction. [E2, E3]

Current main contains the matching causal chain. `InferenceAdmission.shared` has no provider or resource identity and uses a fixed 60,000 ms wait. `ChatRepositoryImpl` wraps all providers with that shared gate. `ProfileBenchmarkViewModel` holds exclusive admission around a complete benchmark block, which can include a batch. `AgentRunCoordinator` marks a run running before admission and converts the thrown admission exception to a failed run. Its outer admission wrapper also sits outside the repository's usual flow error handler. [C1–C3]

## 4 Prioritized issue register

P0 blocks useful Research execution. P1 affects recovery, accuracy of status, or sustained reliability. P2 improves efficiency, presentation, or diagnostic precision. The register groups related symptoms rather than counting each repeated log entry as a new bug.

| ID | Priority | Evidence class | Finding and required response |
| --- | --- | --- | --- |
| R01 | P0 | Observed and code | A local benchmark blocks online Research through one global gate. Introduce resource-aware admission and a benchmark handoff policy. |
| R02 | P0 | Observed and code | A 60-second local wait becomes a failed AI run before dispatch. Persist waiting and separate queue time from provider time. |
| R03 | P0 | Observed and code | Two incompatible routes reach run creation before rejection. Preflight capabilities for each assigned role and record skipped incompatibility. |
| R04 | P1 | Observed and design | Accepted intent has no explicit root execution outcome. Add a durable attempt with blocked, waiting, active, and terminal states. |
| R05 | P1 | Observed | Recovery is summarized as merge unavailable although no acquisition is retained and all contributor dispatches were blocked or rejected. Aggregate causes and offer actions appropriate to the failed stage. |
| R06 | P1 | Observed | Immediate capability failures lack the message timeline error item present for the seven later failures. Drive all projections from one typed outcome. |
| R07 | P1 | Observed | The field completedWork includes 45 failed stages. Separate terminal work from successful work in schema and progress. |
| R08 | P1 | Observed and code | Full extraction and passage-review allowances are planned for every profile. Allocate analysis by explicit roles and actual work assignments. |
| R09 | P1 | Observed and code | Empty journal is labeled retained, with no reasoned capture summary. Export capture policy, event count, loss reasons, and functional failure receipts. |
| R10 | P1 | Observed | Empty-contribution screening repeats 155 times; four frame stalls exceed 100 ms. Cache projections and reduce repetitive logging, then profile the UI. |
| R11 | P2 | Observed | Nine identical accepted-turn snapshots duplicate 405,387 bytes before JSON escaping. Store one immutable snapshot and references. |
| R12 | P1 | Observed and code | Slow local benchmark trials prolong resource ownership; requested GPU is reported as CPU with fallback NONE. Separate trial status and backend-resolution reasons. |
| R13 | P1 | Code finding | A timeout in the separate local comparison suite can cancel remaining candidates. Convert child timeout to a trial outcome while preserving user cancellation. |
| R14 | P1 | Code finding | Planner evidence requirements can disagree with accepted REQUIRED intent and fail after initial repair handling. Validate against immutable intent at first parse. |
| R15 | P2 | Code finding | The exact prompt selects GENERAL report structure despite its scientific subject. Resolve report type from task metadata and evidence, with a user override. |
| R16 | P2 | Observed | App commit provenance is absent and a one-millisecond record-order reversal is flagged as a clock discontinuity. Export build identity and distinguish reordering from clock adjustment. |
| R17 | P2 | Observed | App-wide worker success and selected-chat emptiness are easy to confuse. Label worker scope, processed-item counts, and no-op runs explicitly. |

### 4 1 Recovery and accounting details

The budget is `RECOVERABLE_FAILURE`, `retryEligible=true`, `closed=false`, with no terminal outcome and `PARTIAL:MERGE_UNAVAILABLE`. Keeping reserves open may be intentional for retry. This is not evidence of a leaked lock. Add an explicit distinction between a retryable **task** and a terminated **attempt**, together with the event or user action that can restart it. The earliest stored failure is a capability rejection; the root summary must also explain the benchmark blockage affecting seven other participants. [E4, C7]

The 45 `completedWork` entries have 45 failed outcomes: nine contributors and 36 associated Research stages. They represent terminal bookkeeping, not successful analysis. A separate finalization outcome is partial. Display successful, failed, skipped, waiting, and unfinished work independently. Existing exports must remain readable through a schema migration that maps legacy completedWork to terminalWork and uses stageOutcomes to determine success. [E4, C7]

The plan grants Research analysis allowances of **8,449,110 input tokens and 3,373,056 generated tokens** across participants. The total baseline, including other roles, is **19,168,580 input and 4,332,544 generated tokens**. These are planning allowances, not consumption or charges. The source already funds physical acquisition once and shares evidence. The problem is potentially excessive repeated analysis allocation and unclear role assignment, not proven duplicate downloads. [E4, C6, C8]

### 4 2 Diagnostic and performance details

`CombinedScreening` accounts for 155 of 500 retained app records, or 31 percent. Palette records account for another 66, or 13.2 percent. Repeated empty-state processing obscures useful failures in a small snapshot window. One frame sample records a maximum of 198.54 ms and four stalls over 100 ms. The logs do not establish that screening caused those stalls; verify that hypothesis with traces after caching and deduplication. [E3, E6]

Diagnostics collection is disabled. The 104 dropped-event counter cannot be attributed solely to that setting: current journal recording returns early when disabled, while other validation, capacity, and storage paths increment dropped counts. Preserve privacy and capture preferences. Functional task outcomes should remain available because the application needs them for recovery; verbose diagnostic capture should remain optional. [E1, C9]

The export's `journalAvailability=retained` currently reflects the presence of a journal object, even when it contains zero events. Add explicit values such as DISABLED, EMPTY, PARTIAL, AVAILABLE, and STORAGE_FAILED, with event counts and reason counters. A success result from `ResearchExaminationWorker` means that worker invocation completed; it must not imply this chat acquired evidence or delivered a report. [C9, E7]

The retained `LAST_NATIVE_ERROR` at localRuntimeIncidents[96] reports a graph-compute or decode error adjacent to acknowledged cancellation. The execution shown is CPU. Preserve the native return code, cancellation origin, and operation identity together so expected cancellation can be distinguished from an independent compute failure. This record does not establish a GPU crash. [E7]

## 5 Target execution model

Separate the immutable user request from mutable execution. Keep a stable task identity across retries and preserve the user's scope, source target, participants, model selections, and authority decisions. Each attempt receives a fresh identity, a generation fence, and typed status. A generation fence prevents a late callback from an obsolete attempt from overwriting current state.

| Record | Responsibility | Required fields |
| --- | --- | --- |
| AcceptedResearchTask | Immutable user intent | Existing task and root IDs, prompt digest, requested scope, settings and authority revisions |
| ResearchAttempt | Current execution and recovery | Attempt ID, task ID, generation, state, blocked reason, checkpoint ID, next action, terminal outcome |
| ResourceLease | Current resource ownership | Lease ID, owner, resource keys, generation, acquired time, cancellation acknowledgement |
| ParticipantOutcome | Capability and execution status | Profile and model, assigned role, outcome category, dispatched flag, retry policy, error code |
| EvidenceWorkItem | Acquired and pending sources | Canonical identity, source version, acquisition state, assigned worker, attempt history, checkpoint |
| ReportDeliveryReceipt | Verified report delivery | Evidence revision, contract revision, report hash, completion status, validation findings |

The application should commit accepted task metadata, the first attempt, and a pending scheduling action in one authoritative durable transaction. The current task envelope uses atomic files, and ChatDatabaseV2 and ResearchDatabase are separate Room databases. Adding an execution table does not make all three stores transactional together. Choose one database as the authority for acceptance and its outbox, then update the other database and files through idempotent projections. Retain the complete immutable accepted payload in that transaction, or reference a canonical payload that is already durable. A digest alone cannot reconstruct a lost question or its scope. A reconciler completes pending links and file writes after restart. Alternatively, a versioned atomic execution record can be the single authority if the team deliberately retains file-based scheduling; choose one ownership model before implementation.

Temporary chats continue to use temporary storage. Chat deletion revokes execution and removes its durable data. Existing source-authority checks remain mandatory during resume and sharing. An older request must not gain access to a newly enabled private source through a retry.

### 5 1 Task state transitions

| State | Meaning | Allowed next action |
| --- | --- | --- |
| ACCEPTED | Intent and attempt persisted | Resolve capability and resource requirements |
| BLOCKED_CONFIGURATION | No permitted role assignment can proceed | Review profiles or explicit configuration change |
| WAITING_RESOURCE | Work is eligible but a resource is occupied | Resume on release, pause benchmark, or cancel |
| SEARCHING and FETCHING | Acquisition has actually started | Checkpoint source results and isolate item failures |
| ANALYZING | Valid evidence is being extracted or reviewed | Persist claims and coverage by evidence revision |
| SYNTHESIZING | Final report request was dispatched | Finish one report for the accepted evidence snapshot |
| VALIDATING | Returned report is checked locally | Deliver, or retain a labeled incomplete result |
| COMPLETED or PARTIAL | Report delivered with explicit coverage | Export, inspect sources, or user-requested follow-up |
| FAILED_NO_EVIDENCE | Attempt ended without usable evidence | Retry acquisition with the retained task |
| FAILED_DELIVERY | Evidence exists but report delivery failed | Retry delivery from the saved evidence |
| CANCELLED or INTERRUPTED | User stopped work or execution was interrupted | Reconcile outstanding requests before any explicit resume |

An attempt has exactly one terminal outcome. A task can remain retryable after an attempt ends. Waiting is nonterminal. Profile failures do not terminate sibling work unless the shared requirement that they depend on is unavailable. A user cancellation propagates to children and prevents delayed queue dispatch. Use structured concurrency and handle each child result; supervision alone does not handle exceptions or decide task outcomes. Kotlin's documented cancellation and supervision semantics are the basis for this requirement. [A1]

## 6 Resource admission and benchmark cooperation

### 6 1 Resource ownership

Replace process-wide shared/exclusive ownership with a resource request assembled before execution. Local model loading, local inference, remote provider quota, and benchmark isolation are separate constraints. A cloud-only request must not require the local model lease. A request using local extraction followed by remote synthesis acquires the resources needed for each stage, in a consistent order, and releases them when that stage finishes.

| Resource | Ownership policy | Research behavior |
| --- | --- | --- |
| Local runtime and accelerator | Exclusive where required by the native engine | Wait or hand off safely; never overlap incompatible load or inference operations |
| Remote endpoint and credential quota | Bounded concurrency and provider rate policy | Continue when local runtime is busy if measurement policy allows |
| Source fetching and extraction | Bounded network and memory concurrency | Continue independent items; checkpoint successes |
| Scored benchmark isolation | Explicit measurement lease | Pause at a trial boundary before allowing interfering work |
| UI and persistence | Short operations outside long inference leases | Keep status and recovery controls responsive |

Benchmark measurement integrity matters. Even remote Research can create CPU, network, memory, and thermal load on the phone. The recommended default is to finish the current scored trial, persist its result, yield before the next trial, and run the interactive Research request. An optional concurrent mode may allow independent resources, but affected benchmark samples must be labeled as measured under shared load and excluded from isolated comparisons unless explicitly selected.

The admission API should return structured states and expose an observable blocker. Queue time must not count as provider response latency or consume a provider retry. Start transport and first-token timers only at actual dispatch. Respect an explicit whole-task deadline if one exists; use separate queue and execution budgets rather than silently extending that deadline. Measure durations with a monotonic clock and store wall time separately for display. [A2]

### 6 2 Pause and resume contract

Persist the benchmark's selected-profile order, current suite, current trial, completed samples, and next trial before handing off. On a normal boundary, preserve the completed sample and resume at the next unfinished trial. If the user requests immediate pause during a trial, mark that sample INTERRUPTED and exclude it from scored aggregates. Wait for native cancellation acknowledgement or confirmed worker termination before releasing a local lease.

Lease release must check its lease ID and generation so an old owner cannot release a newer owner's resource. Preserve existing non-cancellable cleanup. A nested benchmark operation may reuse only the resources actually held by its owner token; it must not bypass all admission globally. After process death, reconcile worker ownership before granting a replacement lease. An elapsed lease timer alone is insufficient proof that native work stopped.

For this export's replay, the expected behavior is immediate WAITING_RESOURCE status for the seven eligible profiles, followed by one safe handoff when the benchmark reaches its boundary. There must be no burst of seven failed provider requests at 60 seconds and no new benchmark trial starting ahead of an approved interactive handoff.

### 6 3 Per-trial failure isolation

Regular batch benchmarking already catches non-cancellation profile failures and continues. Its per-test timeout is converted to a TIMED_OUT sample. Preserve this behavior. The separate `LocalRuntimeBenchmarkSuite` uses `withTimeout(90_000)` and rethrows every CancellationException, allowing a trial timeout to end the full comparison. Handle a local timeout as a trial result, then verify the parent remains active before advancing. [C10]

A memory rejection should skip or fail the candidate that cannot run. If device-wide pressure blocks every remaining candidate, pause with a reason instead of repeatedly attempting them or claiming completion. Genuine user cancellation still stops the selected benchmark scope. Local prefill progress, backend selection, cancellation origin, and actual acceleration must be attached to each sample.

## 7 Capability preflight and failure handling

Create a capability assessment for the concrete profile, model, transport, and role. A profile may support source extraction but lack the output control needed by a final Combined writer. Record the selected capability revision in the accepted plan and recheck immediately before dispatch if configuration changed.

Keep the existing guard against unsupported output bounds until a transport-specific implementation is verified. A client-side text cutoff does not prove the server stopped generating or charging. For Pollinations and Hugging Face, either provide tested native output controls and cancellation semantics for the route or show an explicit incompatible-role status. Repeatedly retrying the same unsupported setup cannot repair it. [C4]

| Category | Dispatch occurred | Recovery | Provider reliability impact |
| --- | --- | --- | --- |
| WAITING_RESOURCE | No | Resume on a resource event | None |
| CAPABILITY_INCOMPATIBLE | No | Change the role, route, or selected profile | None |
| ADMISSION_EXPIRED | No | Requeue through an explicit action or deadline policy | None |
| PROVIDER_RATE_LIMITED | Yes or explicit provider preflight | Respect provider retry timing | Track separately from model quality |
| PROVIDER_NETWORK_FAILED | Attempted; acceptance may be unknown | Bounded retry if the request is safe to repeat | Track transport health; do not infer provider outage |
| NO_USABLE_OUTPUT | Yes | Preserve reason and completed sibling evidence | Output-quality failure |
| USER_CANCELLED | Possibly | Do not automatically retry | None |
| APP_INTERRUPTED | Unknown until reconciliation | Check remote/native receipts before resending | Do not guess |

One participant outcome must drive the run record, message timeline, progress card, failed-profile dialog, and export. Current main now includes ProfileFailureRecord for durable classification; extend it with resource, admission, and dispatch fields and cover the immediate eligibility and outer admission bypass paths. Keep text parsing only at the legacy-data boundary. The root recovery summary should group shared causes: for this case, seven waiting on a benchmark and two incompatible with the selected role. Preserve individual details in an expandable view. [C13]

Track local dispatch and confirmed remote acceptance separately. Remote acceptance can be YES, NO, or UNKNOWN. DNS, device connectivity, and TLS failures may precede provider receipt, while a dropped response may leave acceptance uncertain. Reliability scoring and retry decisions must preserve this distinction.

Keep Combined single-pass behavior. Eligible contributions and validated evidence feed one final synthesis for the accepted snapshot. Do not silently rerun all profiles or recompute a final answer when a late result arrives. An explicit retry creates a new attempt or report revision, reuses valid evidence, and records exactly what changed. Unsupported profiles are not silently replaced with unselected providers.

## 8 Research acquisition and planning improvements

### 8 1 Shared evidence and assigned analysis

Extend the repository's existing shared evidence, source identity, and checkpoint mechanisms. Use one task-wide target for unique readable sources. Track discovery attempts, successful reads, equivalent source identities, validated passages, and reviewed claims separately. A failed URL must not erase other sources; a cached page must not count as a new independent study. [C8]

Assign work explicitly. One acquisition coordinator can distribute distinct questions or source groups across eligible workers. Reserve extraction and passage-review budgets for those assignments. Independent verification can revisit selected high-impact claims under an explicit plan, while all workers read the same captured source versions. This preserves useful independent analysis without automatically budgeting an entire 90-source analysis for every selected profile.

The existing `review` stage verifies source-grounded passages. It is distinct from `independent_review`, which is correctly gated by Examination and reviewer settings. Do not remove ordinary passage review because independent review is disabled. Physical acquisition is already funded once; test that the new assignment model preserves source sharing and does not accidentally multiply downloads. [C6, C8]

Depth 50 currently produces a target of 90 sources. Show that mapping before execution. Keep the user's selected target and expose progress toward it. Early report delivery may present useful retained evidence as PARTIAL, with missing coverage and the actual stop reason. Do not silently impose a new page cap, claim the target was reached, or spend unbounded retries trying to make duplicate or irrelevant pages satisfy the count.

### 8 2 Required intent and planner repair

`AndroidResearchWorkflow` initially decides whether evidence is required using prompt keywords. The supplied prompt does not match that expression. Later, `ResearchSessionStore` correctly enforces the immutable REQUIRED intent, so a planner's noResearchNeeded response is rejected. Because that second validation occurs after the initial repair/fallback expression, it can turn a recoverable planning contradiction into workflow failure. This path did not execute in the uploaded turn. [C5, C8]

Pass accepted intent into initial parsing, schema repair, fallback generation, and final validation. The same immutable contract must govern every stage. If the planner returns an invalid empty plan for required research, perform bounded schema repair, then use the existing deterministic plan fallback. Preserve cancellation and actual provider failures as distinct outcomes.

### 8 3 Source and reader resilience

Use per-engine and per-domain concurrency, finite operation timeouts, and reasoned retries. Retry transient failures with backoff and provider timing where supplied. Skip permanent failures such as unsupported formats or inaccessible content, retain their reasons, and continue eligible items. Do not bypass access restrictions. Record whether a source provides metadata, an abstract, or complete readable content.

Reuse current canonical identities, DOI normalization, captured versions, exact-quote verification, numerical grounding, and authority checks. Add claim coverage per research question so the coordinator can prioritize gaps rather than fetching more pages indiscriminately. A coverage view should show supported, disputed, missing, and outdated evidence without claiming that a citation alone proves the statement.

These are enhancements for subsequent execution; this export contains no source reads against which to measure them. Empty enabledProviders inherits searchable defaults. A null gateway with native fallback disabled still selects native ownership normally. WEB_ONLY deliberately excludes academic routes. Do not treat those settings as demonstrated configuration failures or silently broaden the source scope. [C11]

## 9 Report quality and presentation

The existing report contract classifier selects GENERAL for the exact phrase benefits of hydrogen inhalation because its scientific keyword list is narrow. No report was generated here, so this is a prospective structure and coverage risk. Resolve the report contract using explicit user intent, planner metadata, and evidence types; provide a lightweight user override. Preserve domain-appropriate formatting for technical, product, general, and scientific questions. [C12]

For a scientific report, expose study design, population, intervention, comparator, outcome, units, uncertainty, and source limitations when retained evidence supports them. Distinguish human studies, preclinical work, reviews, and marketing pages. Include null or conflicting findings when present. A scientific-source preset can suggest Web and academic coverage, but must respect the current WEB_ONLY selection until the user changes it.

Use headings and comparison tables for parallel evidence. Link material claims to captured source versions and passages. Preserve the existing numerical-grounding and delivery-validation checks. If a report is cut off or fails validation, retain its partial text and evidence; do not label it complete or automatically initiate repeated final synthesis. Recovery should explain whether another explicit delivery attempt is needed.

Profile colors describe contribution provenance. Source citations establish evidence provenance. Keep both identities stable and distinct. Do not display a Shared profile or contribution-record debug bubble in the normal report. No final output exists in this export to assess actual source-color placement; the proposed UI should test it with delivered fixtures.

## 10 Interface and recovery design

Use one compact Research progress card with the current phase, useful evidence count, and one sentence explaining any blocker. Show completed source reads separately from the target. Detailed engine statistics, error text, lease identifiers, and token planning belong in an expandable diagnostic view.

| Situation | Main message | Actions |
| --- | --- | --- |
| Local benchmark occupies required resources | Research is waiting for the benchmark | Pause after this test; Keep waiting; Stop research |
| Two selected routes cannot perform their assigned role | 2 profiles are unavailable for this research setup | Continue with eligible profiles; Review profiles |
| Some sources fail but useful evidence exists | Research continues with the available sources | View sources; Stop and show partial results |
| All acquisition routes fail | Research could not collect evidence | Retry acquisition; Review settings |
| Writer fails after evidence is retained | Sources are saved and the report needs another attempt | Retry report; View evidence |
| No work ever dispatched | Research did not start | Show the actual blocker and its valid action |

Use a single consolidated failure dialog with clear icons, concise copy, and a glass-style blurred backdrop consistent with the existing app direction. Provide an opaque high-contrast fallback when blur is unavailable or reduced transparency is preferred. Actions must have text labels and adequate touch targets; color must not be the only status indicator.

Preserve the current-main actions to combine available results, retry eligible profiles, and remove failed profiles. Extend that dialog with resource-wait grouping and benchmark pause controls. Its existing cooldown-aware eligibility should continue to govern provider retries. [C13]

The removal action should explicitly exclude selected failed profiles from the current chat and its tabs, update membership in one operation, and preserve the historical outcome for the old attempt. Waiting profiles should not be presented as broken providers. Retrying an incompatible route should be disabled until configuration changes. The progress card should survive navigation and process restoration.

Memoize contribution screening by content revision, terminal status, and policy revision. An unchanged empty response should not produce a new screen result or palette event on every UI update. Parse large policy snapshots and prepare source tables away from the main thread. Use measured traces to confirm performance improvements before claiming that the frame stalls are solved.

## 11 Persistence and export contract

Store the accepted-turn policy once with a content digest and schema version. Runs reference that immutable record. The current export embeds nine identical 45,043-byte policy strings, totaling 405,387 bytes before escaping. Replacing eight copies with references avoids 360,344 duplicated bytes in this case before reference overhead; ZIP compression may already reduce the on-disk difference. This optimization targets parsing and persistence as well as export size. [E5]

Preserve backward compatibility by reading inline snapshots and normalizing them during import. Do not rewrite historical policies from current settings. Validate digest, task identity, and authority when resolving a reference. Missing policy data should produce a recoverable import or execution error rather than an invented policy.

Add a compact operational receipt per state transition: task and attempt IDs, generation, profile and role, resource blocker, dispatch flag, outcome code, retry action, and elapsed queue/execution time. These fields support task recovery and should follow existing privacy and temporary-chat policies. They do not require recording prompt bodies, provider payloads, or secret values. Keep optional diagnostics and full capture controlled separately.

Exports must identify selected-chat versus app-wide data, app commit versus native runtime commit, event counts, capture reasons, and counter scope. Report suppressed, rejected, capacity-dropped, storage-failed, and retention-pruned events separately where available. Retain the original event sequence and a stable sorted display projection. A wall-clock discontinuity requires an offset change against a monotonic clock; one reversed pair of adjacent records is insufficient. [C9, A2]

WorkManager completion and progress should be mirrored into the task's own lifecycle using job/task correlation. Android provides observable progress and stop reasons; use those values for scheduling diagnostics while keeping application completion tied to evidence and delivery receipts. A worker with no eligible work should export processedItems=0 and a no-op outcome. [A3]

## 12 Repository implementation map

All Kotlin paths below are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`. New types are proposed, not existing implementations.

| Area | Existing files to modify | Intended change |
| --- | --- | --- |
| Admission | `data/localruntime/InferenceAdmission.kt`; `data/repository/ChatRepositoryImpl.kt` | Resource keys, typed waits, dispatch boundary, complete admission receipts |
| Benchmark handoff | `presentation/ui/setting/ProfileBenchmarkViewModel.kt`; `data/benchmark/BenchmarkExecutionCoordinator.kt` | Trial-boundary leases, durable cursor, pause and resume |
| Local comparison | `data/benchmark/LocalRuntimeBenchmarkSuite.kt`; `data/benchmark/BenchmarkRunner.kt` | Consistent timeout and candidate-failure isolation |
| Participant execution | `data/agent/AgentRunCoordinator.kt`; `data/agent/CombinedProviderEligibility.kt` | Preflight roles and structured participant outcomes |
| Recovery | `data/agent/CombinedRecoveryFailure.kt`; `presentation/ui/chat/CombinedProfileFailure.kt` | Resource and capability categories; correct recovery actions |
| Research attempts | `data/research/ResearchTaskStore.kt`; `data/research/ResearchSessionStore.kt`; `data/research/feature/ResearchDatabase.kt` | Add durable execution attempts and scheduling outbox without changing accepted intent |
| Budget and work | `data/agent/CombinedPolicyResolver.kt`; `data/agent/CombinedTurnBudget.kt`; `data/agent/CombinedTurnBudgetStore.kt` | Assigned analysis quotas and typed terminal accounting |
| Planner and evidence | `data/agent/tool/AndroidResearchWorkflow.kt`; `data/research/ResearchPlanContract.kt`; existing shared-source classes | Enforce accepted intent early and schedule evidence gaps |
| Delivery | `data/research/delivery/ResearchReportContract.kt`; existing validator and receipt classes | Better contract selection, partial delivery, preserved grounding |
| Interface | `presentation/ui/chat/ChatViewModel.kt`; `ResearchProgressCard.kt`; `CombinedFailuresDialog.kt` | Persistent status, consolidated recovery, cached projections |
| Diagnostics and export | `data/diagnostics/RunDiagnosticJournal.kt`; `presentation/ui/chat/ChatDebugExport.kt`; `StreamingDebugBundle.kt`; `DebugExportBundle.kt` | Honest capture state, scope, normalized policy references, outcome receipts |

Proposed new types are `ResearchAttempt`, `ResearchAttemptDao`, `ResearchDispatchOutbox`, `ResearchExecutionCoordinator`, `ResourceRequest`, `ResourceLease`, and `AdmissionOutcome`. Extend the existing ProfileFailureRecord and related run models to represent ParticipantOutcome rather than introducing a competing failure authority. Final package placement should follow current dependency boundaries. Avoid creating a second authoritative execution system beside the existing agent coordinator; the Research coordinator should compose it and own the research-specific state.

ResearchDatabase is version 6 in this export and inspected source. Any implementation migration must start from the actual schema at implementation time, include the exported schema file, preserve archives and evidence, and test upgrade from the previous supported version. Legacy task-file import must be idempotent and must not reactivate cancelled or deleted tasks.

## 13 Implementation sequence

| Phase | Work | Exit condition |
| --- | --- | --- |
| 1 Restore truthful admission | Typed waiting and failure categories; capability preflight; grouped recovery UI | Reproduced nine-profile scenario has explicit waits and skips, with no false provider failures |
| 2 Add safe benchmark handoff | Trial-boundary yield, cursor persistence, resource keys, comparison timeout repair | Interactive Research resumes once; remaining benchmark trials and profiles continue correctly |
| 3 Persist Research execution | Attempts, outbox or reconciler, cancellation fences, process recovery | No accepted runnable task loses its scheduling outcome across injected crash points |
| 4 Improve work allocation | Explicit evidence assignments, shared corpus, typed stage counters, normalized policy snapshots | Source target is task-wide; successful evidence and planned allowance are measured separately |
| 5 Improve quality and delivery | Accepted-intent planning, source coverage, report contract selection, partial-result UX | Required evidence cannot be waived and saved evidence survives delivery failure |
| 6 Verify performance and export | Cached projections, scoped logs, capture summaries, build provenance | Failure export explains the run with diagnostics disabled; targeted UI trace meets agreed thresholds |

Phases 1 and 2 resolve the demonstrated primary failure. Phases 3 through 6 make long Research sessions more recoverable and easier to diagnose. Use small changes with focused tests and preserve the current single-pass, source-authority, and cancellation contracts throughout.

## 14 Acceptance test matrix

These are required implementation tests. They were not run as part of this documentation change. Use virtual time for coroutine scheduling, transport fakes for dispatch counts, database migration tests for persistence, and device tests for native cancellation and UI responsiveness.

| Test | Scenario | Required result |
| --- | --- | --- |
| T01 | Local benchmark holds resources beyond 60 seconds | Research remains waiting; zero dispatched-provider failures; status is visible immediately |
| T02 | Benchmark reaches a trial boundary while Research waits | Result and cursor saved once; approved handoff occurs before the next trial |
| T03 | User interrupts a native trial for immediate pause | Trial marked interrupted; native acknowledgement precedes lease release |
| T04 | Cloud and local work require different resources | Scheduling follows measurement policy; no unsafe local overlap or mislabeled isolated score |
| T05 | Replay seven eligible and two incompatible profiles | Two typed skips; seven proceed or wait; sibling work is preserved |
| T06 | All selected roles are incompatible | One configuration outcome; no phantom running or provider-dispatched states |
| T07 | Cancel a queued Research request | No later dispatch and no owner or permit leak |
| T08 | Kill process between acceptance, file creation, and database linkage | Full accepted payload reconstructs exactly one attempt; no cross-database transaction is assumed |
| T09 | Kill process after remote dispatch | Reconcile receipts where available; record uncertainty before any resend |
| T10 | One contributor or source reader fails | Completed evidence survives; permitted siblings continue; task coverage remains accurate |
| T11 | Every contributor fails before useful evidence | FAILED_NO_EVIDENCE with real reasons; no empty successful report |
| T12 | Final writer fails with evidence retained | Retry delivery from saved evidence without repeating acquisition |
| T13 | Replay 45 failed terminal stages | UI and export show zero successful stages and 45 failed terminal stages |
| T14 | Plan 90 sources across nine participants | Unique target remains 90; analysis budget follows assignments; no automatic ninefold acquisition |
| T15 | Local comparison trial times out or exceeds memory | Candidate outcome retained; next eligible candidate runs; user cancellation still stops the session |
| T16 | Planner returns noResearchNeeded for REQUIRED intent | Initial validation rejects it into repair or fallback; research is not silently waived |
| T17 | Exact supplied prompt selects report structure | Scientific metadata resolves the intended contract without broadening source authority |
| T18 | Diagnostics disabled and failure occurs | Functional outcome survives; verbose capture remains off; manifest says why journal is empty |
| T19 | Restore immediate capability failures | Run, timeline, dialog, and export retain the same typed reason |
| T20 | Recompose unchanged empty contributions repeatedly | One screening result per revision; no duplicate log flood |
| T21 | Requested GPU executes on CPU | Result labels actual backend and resolution reason consistently |
| T22 | App records arrive one millisecond out of order | Reordering recorded without inventing a wall-clock adjustment |
| T23 | Import legacy inline policy and task records | Stable identity, correct outcomes, no duplicate scheduling or archive loss |
| T24 | Deliver a partially supported report | Citations resolve to captured versions; omissions and coverage are visible; no fabricated completion |

Initial measurable targets should be validated on the user's ASUS device and a representative lower-memory test device. Proposed acceptance thresholds are: show admission or configuration status within 500 ms; start eligible work within 1 second of a confirmed lease handoff under the test fixture; preserve one terminal outcome per attempt; and produce no frame over 100 ms attributable to the controlled failure-dialog replay. These are design targets, not measured improvements. Network and model latency require separate distributions.

Run the repository's required lint and CI checks for each implementation phase. Extend `InferenceAdmissionTest`, `BenchmarkExecutionCoordinatorTest`, `BenchmarkRunnerTest`, Research task-store tests, Combined lifecycle tests, and export tests where they already represent the behavior. Add a specific local comparison suite test rather than assuming the regular benchmark tests cover that path.

## 15 Rollout and operational measures

Introduce typed outcomes and capture summaries first, with compatibility readers for existing exports. Roll out the resource scheduler behind an internal feature switch while preserving a safe exclusive policy as fallback. Persisted queued tasks must remain readable if scheduling is rolled back; pause them with an explicit status instead of dropping them. Do not roll back a database schema by deleting new records or archives.

Track task acceptance, waiting duration, admission outcome, actual dispatch count, usable source count, evidence reuse, report delivery, and retry reason. Provider availability must use requests that actually reached the provider; local waits, skipped routes, and user cancellations belong in separate counters. Research success must require a delivery receipt and truthful coverage, not a WorkManager success or terminal stage count.

Prioritize later enhancements by measured need: adaptive source concurrency based on memory and latency; question-level coverage; per-engine success and failure reasons; a searchable evidence archive; source-version comparison; report previews; and exportable source tables. Keep advanced controls in settings and use a concise progress card during ordinary research.

## 16 Existing behavior to preserve

The export and source show useful safeguards already in place. Empty contributions are screened out. Accepted task identity, policy digests, source-authority boundaries, and evidence manifests exist. Shared source merging and source-version validation exist. Passage review is separate from optional independent review. Regular benchmark batches already continue through ordinary profile failures. Native cancellation was acknowledged in the observed benchmark case. The foreground service entered foreground promptly, and the export digests match. [E1–E7, C1–C12]

Keep those protections during the repair. The practical result should be a Research system that explains waiting immediately, starts when permitted, retains useful evidence through failures, and delivers a report whose status matches the work actually performed.

## Appendix A Export evidence locators

Locators use zero-based JSON array indices in the supplied `export_Research Chat_1792813286641.zip`. The archive is evidence for this review; it is not included in the repository change.

| Ref | File and locator | What it supports |
| --- | --- | --- |
| E1 | `manifest.json`; `conversation.json` version, build, exportedAt, capture, runJournal and featureSettings | Export identity, completeness, capture settings and counts |
| E2 | `conversation.json` runs[0] through runs[8]; messages[0] through messages[9]; provenance responseMapping | Nine failures, error reasons, timestamps and empty answer bodies |
| E3 | `conversation.json` appLogSnapshot[125–127], [430–434], [499] | Benchmark cancellation after 90.666 seconds, next trial, effective CPU route and ongoing prefill |
| E4 | `conversation.json` combinedBudgets[0] and researchLifecycle.acceptedTasks[0]; research and workspace | Accepted intent, source goal, planned allowances, terminal-stage accounting and recovery state |
| E5 | `conversation.json` runs[*].synthesisSnapshot | Nine identical 45,043-byte decoded policy strings |
| E6 | `conversation.json` appLogSnapshot by tag; frame sample [359]; diagnosticEvents | Repeated screening, palette noise and measured frame stalls |
| E7 | `conversation.json` localRuntimeIncidents; app-wide WorkManager records; `provenance.json` clockReconciliation | Native history, worker-scope caveats and record-order issue |

## Appendix B Repository and platform references

Repository references are pinned to the inspected commit so later changes do not alter the evidence behind this design. Line locations are navigation aids for that revision.

- **C1** [InferenceAdmission](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/InferenceAdmission.kt#L15-L125), especially shared admission at lines 37–59 and cleanup.
- **C2** [ChatRepositoryImpl](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt#L2125-L2156), outer admission wrapper and successful-admission telemetry.
- **C3** [AgentRunCoordinator](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/AgentRunCoordinator.kt#L478-L632) and [ProfileBenchmarkViewModel](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/ProfileBenchmarkViewModel.kt#L645-L654).
- **C4** [CombinedProviderEligibility](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/CombinedProviderEligibility.kt#L11-L27) and [CombinedProfileFailure](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/CombinedProfileFailure.kt).
- **C5** [ResearchTaskStore](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/ResearchTaskStore.kt) and [ResearchSessionStore](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/ResearchSessionStore.kt#L451-L463).
- **C6** [CombinedPolicyResolver](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/CombinedPolicyResolver.kt#L129-L194), role funding and Research-stage planning.
- **C7** [CombinedTurnBudget](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/CombinedTurnBudget.kt#L288-L316), stage settlement and recoverable delivery state.
- **C8** [AndroidResearchWorkflow](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/AndroidResearchWorkflow.kt#L244-L263), planner validation; the same file's review implementation and [SharedResearchSources](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/SharedResearchSources.kt) cover shared evidence.
- **C9** [RunDiagnosticJournal](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/RunDiagnosticJournal.kt#L225-L275), [StreamingDebugBundle](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/StreamingDebugBundle.kt#L128-L142), and [DebugExportBundle](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/DebugExportBundle.kt).
- **C10** [BenchmarkRunner](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/benchmark/BenchmarkRunner.kt) and [LocalRuntimeBenchmarkSuite](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/benchmark/LocalRuntimeBenchmarkSuite.kt#L105-L175).
- **C11** [DeepResearchSettings](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/DeepResearchSettings.kt), [DeepResearchTool](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/DeepResearchTool.kt), and [MultiSearchResearchSelection](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/MultiSearchResearchSelection.kt).
- **C12** [ResearchReportContract](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/46413444b62c3f0d6db78b09432cd874b29fa6d6/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/delivery/ResearchReportContract.kt#L31-L42).
- **C13** [Main update from PR 737](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/62eb4f0812180ac8d1ac93cba2d9c46a88fce789), including ProfileFailureRecord, CombinedFailuresDialog, CombinedProfileFailure, and single-pass reserve changes. See Appendix C for the scope of revalidation.
- **A1** [Kotlin coroutine exception handling](https://kotlinlang.org/docs/exception-handling.html), cancellation propagation and supervision semantics. Consulted October 23 2026.
- **A2** [Android SystemClock](https://developer.android.com/reference/android/os/SystemClock), monotonic elapsed-time measurement and wall-clock differences. Consulted October 23 2026.
- **A3** [Android WorkManager progress](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/observe), observable progress and stop reasons. Consulted October 23 2026.

## Appendix C Current main verification

Main advanced during this review to `62eb4f0812180ac8d1ac93cba2d9c46a88fce789`, merging PR 737. The documentation branch starts from that revision. The primary analysis remains pinned to the earlier source baseline, and relevant changes in the new main were checked before publication. The installed APK commit remains unknown.

The resource gate, benchmark suites, Research task and session stores, Research planner workflow, report classifier, and diagnostic export files are unchanged between these two revisions. The admission blockage and core Research recommendations therefore remain applicable. The repository's newer AgentRunCoordinator and ChatRepositoryImpl changes do not remove the outer benchmark-admission gate.

New main adds durable ProfileFailureRecord categories, cooldown-aware aggregate retry and removal controls, an action to combine available results, retry provenance, and improved Combined writer allowances. This document extends those features instead of proposing them as wholly absent. The classifier still has no benchmark-resource category, and the immediate eligibility return and outer admission exception paths still need consistent structured receipts. The failure dialog remains a standard AlertDialog in the inspected file.

The updated writer reserve protects one final generation for single-pass turns and changes reasoning/output budgeting. All numerical allowance totals in this document describe the uploaded export; they are not presented as recalculated defaults for the newer main. Preserve the merged GGUF, Combined delivery, provider recovery, and responsiveness improvements while implementing this plan. [C13]

## Appendix D Main-menu and free-provider implementation

This follow-up was requested and implemented on the same branch on October 24 2026, Toronto time. It addresses six interface and provider requirements. It does not claim completion of the resource scheduler or all Research acceptance cases T01–T24. The export remains evidence of the older build's behavior.

### D 1 Conversation progress and presentation

The Home progress indicator belongs inside the conversation's text column. Its leading edge aligns with the subject title, and its trailing edge aligns with the AI-profile subtitle layout box. Moving it into the shared column avoids independent padding calculations and keeps it aligned as screen width, font size, or leading icons change. A themed gradient pans across measured work; animation does not invent additional progress.

Contributor completion alone must not produce 100 percent. Combined completion requires the current turn's successful final run, saved nonblank primary output, and delivery receipt. Research also requires its report to be accepted. An indicator already visible during generation briefly reaches 100 percent before disappearing. Opening a previously completed chat does not replay the completion animation. Failure, interruption, or user stop removes the indicator immediately. A retry must be evaluated from its current attempts, without an older failed final run suppressing fresh work.

The equals-shaped drag handle is removed from the main menu. Profile subtitle opacity is half its previous computed theme opacity. The subtitle's RGB theme treatment is retained; this is a requested visual change, not a claim that contrast remains at the previous level.

### D 2 Shared conversation icons and unread state

Home and New Chat use one mode-to-icon resolver for individual, multi-profile, Combined, and Research chats. A new conversation and a read conversation use the themed outline. A published, unseen answer uses the filled counterpart of the same shape. Research receives an explicit outline/filled pair instead of falling through to a different chat type.

Unread state continues to come from durable reply publication and seen acknowledgements. Run activity, timestamps, empty placeholder messages, and failed contributors are not evidence of an unread answer. The Home projection retains visible publication identity and maps hidden workspace children to their visible parent chat. Tests cover acknowledgement, current-turn identity, and invalid or hidden publication records.

### D 3 Pollinations in Combined

The legacy text GET route is replaced with the documented Classic OpenAI-compatible POST route at `https://text.pollinations.ai/openai`. It accepts anonymous requests. The current upstream implementation always applies a generation ceiling of 1,500 tokens, including when a client requests a smaller value. Both its source and regression test establish this behavior. The app therefore records a fixed allowance of 1,500; it must never pretend that a smaller requested number is enforced. [P1–P3]

Combined eligibility admits Pollinations contributions when the plan can reserve that allowance. The planner, final dispatch guard, and physical dispatch receipt must agree on the same bound. Explicit hard allowances below 1,500 are rejected with an actionable capacity message. Writer capacity remains bounded, so larger final-writing requirements need a capable selected writer. GET-era URL and context reductions are removed. The transport retains finish reasons, usage, actual served-model identity, and truncation handling.

Classic limits anonymous text requests by IP. HTTP 402 with Retry-After represents exhaustion of the next free slot and is treated as a cooldown. This integration does not initiate the separate paid request mechanism. The existing model alias is retained where supported, with the current GPT-OSS 20B identity shown to the user. [P1]

### D 4 Restore free LLM7 with an optional token

The user's recollection of earlier free releases is supported by repository history. PR 510 reported a successful anonymous LLM7 response. Commit `6244bff2e84716ffa2b4703c53a54b15034caee8` later introduced required-token classification while retaining the same API host and route. The restoration separates whether a provider accepts a user token from whether it requires one. [P4, P5]

On October 24, a direct check of the public model catalog returned HTTP 200 with five chat models explicitly classified as `tier=turbo` and `usage_based_only=false`. A subsequent request to `https://api.llm7.io/v1/chat/completions`, using `codestral-latest`, no Authorization header, a 16-token output limit, and the harmless prompt “Reply with OK.” returned HTTP 200 and a valid answer. This verifies anonymous operation of the ordinary public endpoint at the time of testing; no alternate endpoint or shared credential was used.

The current public documentation says an API token is required, which conflicts with this observed behavior. The implementation preserves the working anonymous route and allows an optional personal token. A later authentication rejection should explain how to add a free token; it must not silently switch to a different or paid route. The app's existing owner-approved integration control is preserved. [P6]

Generation resolves against the live free-allowance catalog. Paid-only, Pro-only, missing-classification, and removed models cannot bypass that check through an imported or stale profile. The automatic choice is local app state and is resolved to a real allowed model before dispatch. A provided token is attached only to LLM7's validated endpoint and contributes to credential-specific request limiting. Blank token values send no Authorization header. Free-model eligibility does not override billing rules of a paid or topped-up account; the free allowance applies to the service's free access. [P7]

### D 5 Verification and implementation references

Verification covers Kotlin compilation, focused Home and provider regression tests, resource validation, and changed-file formatting. Live LLM7 evidence establishes the anonymous route's current behavior; it does not establish long-term uptime. No Android device or emulator is available in the validation environment, so rendered menu alignment and animation still require device review. Exact executed test results are recorded in the branch implementation summary and pull request.

Primary provider and history references:

- **P1** [Pollinations Classic API](https://github.com/pollinations/pollinations/blob/master/APIDOCS.md), anonymous POST routes, model identity, free-slot limits, and HTTP 402 behavior. Consulted October 24 2026.
- **P2** [Pollinations origin implementation](https://github.com/pollinations/pollinations/blob/master/text.pollinations.ai/cloudflare-cache/src/origin.js), MAX_TOKENS and the upstream request body. Consulted October 24 2026.
- **P3** [Pollinations origin regression tests](https://github.com/pollinations/pollinations/blob/master/text.pollinations.ai/cloudflare-cache/test/origin.test.js), client limit 99 still yields upstream limit 1,500. Consulted October 24 2026.
- **P4** [Earlier anonymous LLM7 release, PR 510](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/510).
- **P5** [Required-token change](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/6244bff2e84716ffa2b4703c53a54b15034caee8), merged through PR 732.
- **P6** [LLM7 published API access terms](https://github.com/chigwell/llm7.io/blob/main/TERMS.md), contrasted with the live public-endpoint check described above. Consulted October 24 2026.
- **P7** [LLM7 Models API](https://docs.llm7.io/guides/models-api), dynamic catalog, free Turbo access, and account-dependent billing. Consulted October 24 2026.
