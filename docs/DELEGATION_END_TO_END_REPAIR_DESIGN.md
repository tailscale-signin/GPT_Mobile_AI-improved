# Delegation: End-to-End Repair Design and Implementation Plan

**Status:** Proposed implementation; this change adds documentation only.  
**Date:** October 12, 2026, America/Toronto.  
**Repository baseline:** `1d4dcd8698356abddc5f1be946bb0811017b7d7a` on `main`, the merge for release `0.9.51.0`.  
**Incident build:** `0.9.51.0`, build `120`, application ID `dev.melo.gptmobile.improved`.  
**Scope:** Delegation admission, workers, research, independent review, handoff, final writing, recovery, permissions, accounting, persistence, and their UI.  
**Release gate:** Every confirmed issue below must have a reproducing test and a verified resolution. Risks require an explicit test result, not an assumption that they occur.

## 1. Decision and intended outcome

Repair delegation as a complete workflow, not as a collection of larger timeouts. A helper producing text is not the same as completing the user's task. Discovery, passage verification, independent review, final-response delivery, and recovery must have separate states and durable outputs.

The supplied incident demonstrates a useful distinction: the helper model executed successfully, but the overall conversation failed. The first response performed substantial research before the primary provider returned HTTP 403. A continuation then created a new research state, stopped without new evidence, and called the same failing primary route again. Older research survived in saved response context; it was not correctly reused as the new research workflow's checkpoint.

The repair must make these outcomes explicit:

- Completed evidence survives a failed reviewer, writer, network connection, or application restart.
- Continuing resumes unfinished stages under the original task identity; it does not silently start research over.
- A known blocked writer or ineligible required reviewer is reported before expensive preparation.
- Evidence must address the task. Readable-source counts and a model's `Supported` verdict are not sufficient proof of coverage or independent verification.
- Permissions remain authoritative. Recovery cannot secretly enable cloud workers, change destinations, or repeat external side effects.

This is a closure plan for all delegation issues identified in the inspected export and code, plus a broader regression audit. It is not a claim that two retained runs expose every possible defect.

## 2. Evidence, provenance, and limits

### 2.1 Sources of truth

**E1 — Private diagnostic export.** Schema `gptmobile.conversation.debug.v1`, exported `2026-10-13T03:28:51.062840Z`, which is October 12 at 23:28 EDT. SHA-256:

```text
491e72a599f8f90f88c9fe681ceb980f0a6c8491ae3b16d899291a5a64204d05
```

The original export is intentionally not committed. It contains conversation text, remembered-context material, settings, and connection identifiers. This document uses Run A and Run B as incident aliases. Reproduction fixtures must use synthetic content and identities.

**C1–C9 — Repository source inspection.** Immutable source links and inspected ranges are in section 15. The repository release and export version correspond, but the export does not establish that the running binary is bit-identical to the inspected commit.

**X1–X2 — External implementation references.** Official provider and coroutine documentation informs the proposed repair. It does not establish the cause of this incident.

Evidence labels used throughout:

| Label | Meaning |
| --- | --- |
| Observed | Directly present in E1, or calculated from its retained records. |
| Confirmed code behavior | Visible in the inspected source at the pinned commit. |
| Risk | Plausible failure mode requiring a dedicated reproduction. |
| Proposal | New behavior to implement; not a description of current behavior. |

E1 explicitly describes retained records rather than a complete wire capture. Missing credentials, response bodies, deleted records, and unrecorded events cannot be reconstructed. Estimated tokens are not provider-reported usage or billed cost. A `LLAMA` profile identifies a configured route; it does not prove that inference ran on the Android phone rather than a private server.

### 2.2 Incident measurements

The export contains 2 runs, 4 messages, 2 top-level tool events, 27 model invocation records, 220 diagnostic events, 4 workspace records, and 2 research snapshots. The diagnostic levels are 212 informational, 5 warnings, and 3 errors; these are event counts, not eight independent root causes.

| Retained measurement | Run A: initial request | Run B: continuation |
| --- | --- | --- |
| Overall status | Failed | Failed |
| Run duration from recorded epoch seconds | 115 seconds | 9 seconds |
| Helper invocations | 24 completed | 1 completed |
| Helper estimated input / output tokens | 40,549 / 6,948 | 530 / 446 |
| Primary invocation | Failed, about 0.4 seconds | Failed, about 0.4 seconds |
| Primary estimated input / output tokens | 6,240 / 0 | 12,896 / 0 |
| Primary terminal failure | HTTP 403, generic access-denied message | Same generic HTTP 403 message |
| Research snapshot | `Complete`, `complete=true` | `Stopped`, `complete=false` |
| Effective recorded source target | 12 | 12 |
| Sources retained | 207 | 0 in the new research snapshot |
| Readable sources | 12 | 0 |
| Saved research questions | Empty | Empty |

Run A source statuses are 129 `Metadata only`, 47 `Discovered`, 19 `Failed`, 7 `Partially read`, and 5 `Read`. Its snapshot records 31 read attempts, 3 searches, 22 engine responses, and 9 engine failures. These are different accounting dimensions; do not combine them into one tool success rate.

Run A retains 14 claims: 5 `Supported`, 6 `Insufficient`, and 3 `Unreviewed`. Its final handoff contains nine source references and a warning that some references were omitted to fit the brief. No independent reviewer invocation appears in the retained invocation list; the handoff explicitly reports reviewer unavailability.

All 25 retained helper invocations completed. The failure is therefore not evidence that this helper runtime cannot generate. Run B's primary input estimate is approximately 2.07 times Run A's; this warrants recovery-context accounting, but does not prove duplicate billing or a context overflow.

**E1 inspection pointers:** `runs`, `invocations`, `toolEvents`, `messages[*].timeline`, `research[*].evidence`, `workspace[*].payload`, `chatTools`, and `featureSettings`. Decode each workspace payload before examining its stored request context.

### 2.3 What must not be inferred

HTTP 403 is confirmed; a bad key, exhausted credit, budget restriction, model restriction, moderation decision, or endpoint-specific denial is not established by the retained message. Do not label this incident as a proven quota failure or rewrite the user's endpoint automatically.

The continuation did not erase all old evidence: its stored primary request includes a saved-response resource containing prior research. The defect is the split between prose-level recovery and structured research-state recovery. Similarly, a cancellation message does not prove the user pressed Stop: three engine events say the cancellation origin was not reported.

A chat research depth of 10 exists alongside legacy delegation research depth 100 and global exhaustive settings. This is a configuration-resolution and explanation problem to audit, not proof that the effective target of 12 violates the user's active chat selection. The 16-call delegation setting and 48 local-model-call setting also represent potentially different limits; 24 child calls alone do not prove a budget bypass.

## 3. Prioritized repair register

Priorities describe implementation order and impact. Each row is closed only when its acceptance test passes against the production execution path.

| ID | Priority and finding | Evidence and qualification | Repair and closure condition |
| --- | --- | --- | --- |
| D01 | P0: final writer failure strands completed preparation | Observed: both primary calls fail with 403; useful preparation precedes the first. C1 performs preparation before opening the primary session. | Add writer readiness and known-failure admission, preserve a finalization checkpoint, and resume writing without repeating successful research. T01–T03. |
| D02 | P0: recovery does not resume the research checkpoint | Observed: Run B has a new empty research snapshot while old evidence remains in primary recovery context. C1 derives a new turn key; C4 loads by chat, turn, and authority; C5 compares the task string. | Introduce a durable root work ID and explicit resume descriptor. Keep recovery commands outside the task text. T04–T06. |
| D03 | P0: cancellation provenance is inconsistent | Observed: engine cancellation origin unknown, but handoff says stopped by user. C4/C5 expose and interpret Boolean stop state. | Persist cancellation origin, scope, and initiating event. Distinguish user stop, policy revocation, deadline, parent failure, and deletion. T07–T10. |
| D04 | P1: independent reviewer eligibility is checked too late or inadequately explained | Observed: configured reviewer is the primary profile; cloud permission is denied at a narrower scope; review unavailable. C2 resolves the reviewer at review time. C3 preview lacks full primary/reviewer context. | Admit all roles before work; show exact exclusion reasons and unknown readiness. Do not weaken independence or cloud rules to make the warning disappear. T11–T13. |
| D05 | P1: planner question objects are silently dropped | Observed: planner returns objects containing `question` and `evidence_needed`; saved questions are empty. C5 expects strings; C6 `strings()` discards non-primitives. | Version and validate the planner contract, preserve both question text and evidence requirement, and reject silent empty plans. T14–T16. |
| D06 | P1: readable-source quota substitutes for task completion | Observed: snapshot Complete despite empty questions and weak task relevance. C5 marks success from readable count. | Track question coverage independently from collection progress; page target satisfaction is not answer completion. T17–T19. |
| D07 | P1: passage support is mistaken for sufficient task evidence | Observed: supported handoff includes pricing, prevalence, and questionnaire-method claims unrelated to the requested benefit analysis. C5/C6 validate quotes and passage entailment, not full task relevance. | Add relevance, publication-type, scope, and contradiction gates before selecting findings. T18–T20. |
| D08 | P1: incomplete verification cannot be resumed precisely | C5 excludes sources already present in `claimedIds`, including sources whose claims remain unreviewed. | Persist extraction and each verification stage separately. Revisit failed/unavailable stages without regenerating accepted claims. T21. |
| D09 | P1: handoff and recovery context lose prioritization and metadata | Observed: source omissions and large recovery context. C7 serializes source listings before later tool results; C1 uses a prefix window. | Use a structured manifest, ranked evidence, complete source lookup, and explicit omitted-item metadata. Never discard review status to fit a brief. T22–T24. |
| D10 | P1: provider errors are over-generalized | C8 classifies 401/403 through shared string matching as authentication failure. The export lacks a precise provider cause. | Introduce typed failures with safe provider metadata, phase, retry guidance, and an unknown-forbidden fallback. T02, T25. |
| D11 | P1: policy and tool-capability consistency needs closure | C1 reuses parent-authorized tools in child runs; selective helper restrictions are not visibly intersected in that branch. No forbidden execution is proven in E1. | Audit every child path using one effective capability contract and execution-time authorization. Test both private-server and cloud boundaries. T26–T29. |
| D12 | P1: nested budgets and reservations need one explanation | Observed: multiple output, call, research, and timeout settings. C1 has separate acquisition and ordinary tool budgets; C2 has several counters. | Name each counter and unit, record reservations, and prove retries cannot double-charge or manufacture allowance. Preserve final-answer capacity. T30–T33. |
| D13 | P1: transport, semantic, and delivery success are conflated | Observed: research diagnostic says SUCCESS while independent review is unavailable and final delivery fails. | Store orthogonal stage outcomes; useful partial results are neither verified success nor disposable errors. T17, T34. |
| D14 | P2: trace identifiers cannot be directly joined | Observed: 25 timeline delegation IDs have no overlap with the 27 invocation IDs. Distinct IDs can be valid, but the export lacks a straightforward explicit relationship. | Export stable task, step, attempt, and invocation relationships, including role and source evidence contributions. T35. |
| D15 | P2: large runs have hidden review and serialization limits | C5 review examines up to 12 unclaimed sources and 30 claims; C6 also truncates claim rows to 30. This is not evidence that larger acquisition goals receive proportionate review. | Use resumable review batches and paged persistence; expose any selected review sample and its unreviewed remainder. T36. |
| D16 | P2: partial output, native execution, and lifecycle risks need regression coverage | E1 does not show helper empty-output failures, native crashes, or output-cap mismatch. Existing repairs must not regress. | Exercise malformed JSON, reasoning-only output, tool-only output, interrupted streams, native admission, process death, and exactly-once terminal transitions. T37–T40. |
| D17 | P2: UI and configuration feedback are not sufficient for this failure | Observed: user receives an access error after helper work. Trace display was enabled, so raw helper JSON is not proof of a default-mode leak. | Provide stage-specific status, effective-policy explanations, saved-evidence access, and focused recovery actions. T41–T43. |
| D18 | P2: diagnostic and recovery privacy requires explicit safeguards | E1 contains private context; C7 builds broad saved-context resources. No cross-user disclosure is established. | Redact public fixtures, minimize exports, reauthorize recovery, and test deletion/temporary-chat boundaries. T29, T44. |

## 4. Preserve existing working behavior

Do not replace working mechanisms with parallel implementations. Extend the existing coordinator, policy resolver, research journal, activity store, and invocation ledger.

The inspected code already preserves usable child output after a trailing transport failure, excludes recursive `delegate_to_model` calls from child tools, gives reviewers a tool-free request, passes parent authorization/budget wrappers into child execution, uses atomic research storage, preserves completed reads during cancellation, and treats unavailable independent review as unverified rather than a pass. These are important invariants. [C1, C2, C4, C5]

Primary output budgeting is already separate from the helper brief in `streamPrimaryAnswer`. Do not apply `remoteSynthesisOutputTokens=256`, the helper's 2,048-token output setting, or a short evidence-handoff budget to the user's final answer. Keep actual provider/context constraints explicit. [C1]

A target-site access denial is not a signal to evade the denial through another reader. Preserve C5's terminal handling of explicit page access refusal. A private server must not gain additional tools merely because it is classified as local.

## 5. Execution model and data contracts

### 5.1 Stable identity and durable ownership

Introduce the following concepts as additions to existing records, not replacements for public message IDs:

| Identity | Lifetime and purpose |
| --- | --- |
| `rootWorkId` | Original user task and authorized recovery lineage; stable across Continue and retry of unfinished work. |
| `generationId` | One response-generation attempt; new on each retry or explicit continuation. |
| `stepId` | Stable planned unit: discovery batch, source read, extraction, verification, independent review, or writing. |
| `attemptId` | One execution attempt of a step. |
| `invocationId` | One physical model request, linked to its step and attempt. |
| `artifactId` and revision | Immutable evidence, review report, handoff, or partial answer produced by a step. |
| `effectId` | External side-effect intent and receipt; never inferred from argument equality alone. |

Use a generation lease with a monotonically increasing fencing token. Only the current owner may advance run state or commit new tool effects. A stale stream may contribute a correctly attributed completed artifact when safe, but may not overwrite a newer answer or reactivate stopped work. Deduplicate terminal events with a compare-and-set or transactional unique key.

C1 currently derives `turnKey` from the latest user message; C9 retains `sourceMessageId` for recovery. Use that existing recovery reference to find the original work record rather than incorporating the recovery instruction into the task's identity. Do not infer a root solely by matching prompt text across unrelated conversations. [C1, C9]

### 5.2 Separate status dimensions

Proposed conceptual contract, to be adapted to the existing Room/serialization conventions:

```kotlin
// Design sketch, not compiled production code.
data class DelegationCheckpoint(
    val schemaVersion: Int,
    val rootWorkId: String,
    val generationId: String,
    val policyFingerprint: String,
    val revision: Long,
    val activeStage: Stage,
    val stageStates: Map<String, StageState>,
    val artifactRefs: List<ArtifactRef>,
    val continuation: ResumeDescriptor?,
    val cancellation: CancellationRecord?
)

data class StageState(
    val execution: ExecutionState,
    val output: OutputQuality,
    val review: ReviewStatus,
    val failure: ProviderFailure?,
    val retryAfterEpochMillis: Long?
)
```

Execution states include queued, running, paused, completed, failed, and canceled. Output quality distinguishes none, malformed, partial, and usable. Review status distinguishes not requested, pending, unavailable, passed, rejected, and incomplete. These fields must not be collapsed into a single success Boolean.

A legal example is `research execution=completed`, `output=partial`, `review=unavailable`, `writer execution=failed`. That is a recoverable workflow with saved evidence, not successful final delivery and not an empty helper failure.

### 5.3 Stage progression

```text
Resolve task identity and requested policy
    -> Resolve effective policy and role eligibility
    -> Check known readiness and obtain generation lease
    -> Load/validate checkpoint and artifact manifest
    -> Execute unfinished planning/acquisition/extraction steps
    -> Validate evidence and assess question coverage
    -> Independent review, when requested and eligible
    -> Build final-writer handoff
    -> Write or resume final response
    -> Validate final citations/coverage and mark delivery
```

Any stage can pause with artifacts intact. A rejected claim is not a rejected research session. A failed final writer does not invalidate successful extraction. “No usable evidence” is a legitimate outcome when supported by the work, but must not be produced merely because the wrong checkpoint namespace was selected.

## 6. Admission, roles, and policy resolution

### 6.1 One effective policy for UI and execution

Add a proposed `DelegationAdmissionResolver` that calls the existing scoped policy logic and returns a typed admission result: selected primary, helper, reviewer, execution destinations, effective tool sets, runtime readiness, effective limits, and per-field provenance.

The same result drives settings preview, chat overrides, the readiness inspector, benchmarks, and execution. Supply the primary and reviewer context missing from `DelegationPolicyResolver.preview`. Explain why each excluded candidate is ineligible instead of merely showing “No eligible reviewer.” [C2, C3]

Audit and document the precedence of global defaults, primary-profile overrides, chat delegation overrides, chat research controls, and legacy migration. Normalize old aliases only once. Never let a permissive global default override a narrower explicit cloud denial. Ordinary changes apply to the next generation; live revocations can only restrict admitted work.

For this incident, display the effective research depth and source goal alongside the legacy/global values, the private helper destination, and the configured reviewer's eligibility result. Do not increase the goal to 300 solely because a legacy field still says 100.

### 6.2 Review mode is an explicit choice

Support `Off`, `Best effort`, and `Required independent review` as clearly defined policy states. Preserve existing unverified-evidence behavior during migration unless the stored user choice unambiguously requires strict review; explain ambiguous legacy configuration rather than silently changing it.

In Required mode, no eligible independent reviewer means pause before costly acquisition. In Best effort mode, preparation may proceed, with an upfront warning and unverified handoff. Unknown canonical model independence is not equivalent to independent. The primary can synthesize unverified evidence only under the selected policy; it must not be relabeled as an independent reviewer merely to satisfy a gate.

Alternative reviewer or writer selection follows the user's approved fallback policy. Never automatically authorize a cloud destination to resolve a local-only configuration.

### 6.3 Readiness checks must be honest and inexpensive

Use cached metadata and recent structured failures first. An optional explicit connection test can perform a disclosed minimal model request. A catalog lookup, account lookup, or reachable endpoint proves only its own result, not that the selected generation request will succeed.

Record readiness as ready, blocked, unknown, or stale with a reason and last-check time. A known credential/model/route failure prevents repeating expensive preparation on Continue until a relevant change, explicit recheck, or documented provider reset. Key the cache by a non-secret credential revision, endpoint, model, and routing-policy revision; never store or log a raw key.

For OpenRouter, the official limits endpoint can provide key and effective-budget information. Structured limit metadata distinguishes budget failures from transient in-flight spending pressure; a 403 is not necessarily invalid authentication. This informs adapter handling, not the incident diagnosis. [X1]

## 7. Recovery and finalization repair

### 7.1 Resume descriptors, not a larger rewritten prompt

Extend `ResolvedTaskRecovery` with a root work reference and a requested resume mode while retaining its user-visible action names. Preserve the original objective and constraints separately from control instructions. `CONTINUE_RUN` loads the checkpoint; `RETRY_FAILED_STAGE` retries eligible failed stages; `CONTINUE_PRIMARY_ONLY` records an explicit policy transition and still preserves evidence. [C9]

C4's authority scope is a security boundary. Root identity alone must not make protected evidence shareable across profiles or MCP connections. When a destination, permission set, or authority changes, validate every reusable artifact and create a successor checkpoint with an audit trail. Do not bypass the existing policy fingerprint check or reset its retained budget.

A Continue action must perform this sequence:

1. Resolve the original work and latest valid checkpoint in the current chat.
2. Verify generation ownership, current authorization, artifact integrity, content version, and applicable freshness.
3. Restore completed steps, source frontier, claim-verification progress, and budget reservations.
4. Identify the next unfinished dependency. Do not rerun a completed planner solely because the visible user text is “continue.”
5. Recheck the failed destination before spending on new preparation.
6. Resume only eligible work and append a new generation record; keep previous records immutable.

### 7.2 Correct behavior for the supplied incident

After Run A, the application should retain the research manifest, its five passage-supported claims, all explicit uncertainty and review limitations, and the writer failure. It should show **“Evidence saved; final writer unavailable”** with the HTTP status and an unknown specific cause when no provider detail exists.

On Continue, the application should resolve Run A's task lineage instead of creating an empty research workflow. It should not regenerate all research just to encounter the same known writer failure. New research is justified only for an explicitly recorded coverage gap, expired evidence, changed task, or requested refresh.

Actions should be **Check connection**, **Resume final response**, **Choose an approved writer**, and **View saved evidence**. Primary-only recovery is a separate, explained action. Without a working authorized writer, show a deterministic saved-evidence/status panel rather than promise that the failed primary will explain the limitation.

### 7.3 Recovery context and side effects

Keep `read_recovery_context` as a paged, read-only resource. Improve its default prefix to prioritize the objective, stage status, supported/relevant claims, unresolved questions, review report, and artifact manifest. Do not front-load hundreds of low-value source listings before critical review metadata. Preserve the complete corpus behind source/artifact lookup. [C1, C7]

C7 reuses completed tool calls by tool, connection, and equal arguments. For recovery, add explicit root-work and effect-intent checks. The same arguments in a genuinely new user request may represent a new authorized action and must not be suppressed. An unknown external-action outcome requires receipt/status reconciliation before retry. Read-only caches need content-version and authorization validation; never reuse health or other sensitive results outside their allowed policy.

## 8. Research and evidence-quality repair

### 8.1 Typed planning contract

Replace generic `strings("questions")` with a versioned planner object containing `questions: [{id, text, evidenceNeeded}]` and `queries: [{id, text, questionIds}]`. Accept legacy strings through an explicit adapter. For the observed legacy object form, map `question` to `text` and `evidence_needed` to `evidenceNeeded` without discarding either.

Validate required nonblank fields, lengths, and referential integrity. Malformed output gets a schema-specific repair attempt within the existing budget; if that fails, record invalid planning and create a conservative deterministic task question with an explicit fallback marker. Do not treat a malformed empty plan as “research not needed.” Prefer structured-output capabilities only where the selected provider/model supports them; retain validation on every route. [C5, C6]

### 8.2 Relevance and evidence status are separate

Before model extraction, rank candidates against the task's entities, question IDs, requested evidence type, and source metadata. After extraction, require each proposed finding to name the question it answers and the passage that supports it. Keep rejected and background-only items in the corpus but exclude them from answer-evidence quotas.

Persist separate fields for topic relevance, source readability, exact-quote verification, passage entailment, publication type, retraction/preprint status, scope limitations, and independent review. Unknown peer-review status stays unknown. A DOI, repository host, systematic-review title, or readable abstract alone does not satisfy a request for a particular evidence standard.

Use synthetic tests in which an on-topic study, a product-price article, an unrelated prevalence paper, a review comment, a protocol, and an irrelevant questionnaire paper all contain overlapping keywords. Only the applicable evidence may satisfy the substantive question. Preserve limitations and contrary findings rather than selecting only claims that support the user's framing.

### 8.3 Completion criteria

Track three separate progress measures: collection goal, question coverage, and verification coverage. A source target is a collection objective, not proof that research is complete. At target satisfaction, unresolved questions either schedule targeted missing work within policy or yield an explicit partial-coverage result. The UI must not show the user's task as completed merely because enough pages were readable.

Define `Complete` only when the required workflow stages and configured coverage conditions are satisfied. Define `Partial useful` when relevant material exists but required questions or review remain unresolved. Define `No useful evidence` only after evaluating admitted evidence, not from transport failure alone.

Do not replace the current uncapped acquisition policy with an undocumented source cap. Use the resolved user policy, provider limits, device-resource constraints, cancellation, and a reported evidence-gain stop reason. Report which constraint ended collection. At 100% delegation, role ownership remains distinct from research depth and does not mean infinite retries or unlimited cloud permission.

### 8.4 Resume review without repeating extraction

Replace source-level `claimedIds` skipping with per-source-version extraction state and per-claim verification state. A failed academic verifier can be retried after it becomes available without creating duplicate claims. A completed, schema-valid low reviewer score is an evaluation result, not a transport error to retry until the score rises.

Batch review and checkpoint its cursor. Remove fixed 12-source/30-claim persistence loss as an implicit coverage limit; use paged claim storage and expose intentionally sampled review. Preserve source hashes, quotation locations, review model identity, schema version, and the exact artifact revision reviewed. An edited claim or changed passage invalidates only dependent verification, not the entire corpus. [C5, C6]

## 9. Failure, cancellation, and resource contracts

### 9.1 Typed provider failure

Add a proposed `ProviderFailure` with origin role, stage, HTTP status, safe provider error code, safe structured metadata, request ID, endpoint identity, retryability, retry time, and whether output or a side effect was already committed. Preserve unknown values instead of substituting a guess.

| Failure class | Proposed handling |
| --- | --- |
| Invalid credentials or explicit permission denial | Block the affected destination; request configuration/permission repair. No blind repeat. |
| Explicit budget or credit restriction | Explain the specific known restriction and reset/remedy metadata. Do not label every case authentication failure. |
| Unknown 403 | Display access forbidden with cause unavailable; retain evidence and require an explicit check/change before repeating known failed finalization. |
| Rate limiting or explicitly transient in-flight budget restriction | Respect provider retry metadata; retry within the same work budget and deadline. |
| Transient server/network failure | Bounded backoff with jitter; resume at the failed stage. |
| Context overflow | Rebuild the handoff using actual limits, preserving sources and review metadata; do not silently truncate structured JSON. |
| Partial valid output followed by stream failure | Keep validated completed output; reconcile finish state before retrying. |
| Malformed/empty/reasoning-only output | Classify separately and perform a bounded, role-specific repair without claiming success. |

Parse structured fields rather than English remedy text. If X1's optional budget fields are absent, readiness is unknown rather than exhausted. Never send credentials to a different host during an account check, auto-normalize the user's endpoint, or infer zero cost from zero visible output. [C8, X1]

### 9.2 Cancellation origin and scope

Proposed `CancellationRecord`: origin, scope, root work ID, generation ID, step ID, initiating event ID, time, and resumability. Origins include user whole-run stop, user stop-research-and-summarize, user primary-only, permission revoked, parent failure, operation deadline, resource pressure, process interruption, superseded generation, chat deleted, and unknown.

Whole-run Stop cancels all work and does not start final synthesis. Stop research and summarize ends acquisition while preserving permitted read-only access to completed evidence. Primary-only is an explicit handback, not an independent-review success. Revocation stops affected outbound requests and prevents further disclosure. Deletion stops work and invalidates access to artifacts; it must not produce a saved-evidence panel that resurrects deleted content.

Do not infer user intent from a generic `CancellationException` or stop Boolean. Preserve cancellation through every wrapper and child operation. Supervision is appropriate for independent search-engine failures, but it is not a substitute for handling child exceptions and joining cleanup; parent cancellation must still propagate. [C4, C5, X2]

### 9.3 Shared budgets, units, and deadlines

Keep logical tool operations, physical network attempts, model invocations, retries, reviewed claims, output bytes, and tokens as distinct counters. One attempt must charge each applicable dimension once. Record budget owner, reservation ID, and reason for admission/rejection. A retry must not reset the original work allowance by changing generation, task wording, or profile.

Use explicit types or suffixes for tokens, UTF-8 bytes, characters, and milliseconds. Audit `delegationInputBudget`, `boundedConfig`, `capPrompt`, and reviewer prompt construction before changing arithmetic: the current interfaces mix differently named capacity concepts. Structured evidence must be chunked on semantic boundaries, not cut across quotes or JSON with head/tail truncation. [C1, C2]

Reserve configured room for the independent review and final writer. An ordinary tool slot reservation is not a reservation of provider availability or money. Provider output limits and the user's requested final-answer length remain independent from a helper brief limit.

Worker queue time, runtime loading, first-token delay, idle streaming, and total active work need separate clocks and stop reasons. Pause should not consume an active-work deadline, but must retain its budget and ownership semantics. Use bounded operation retries and resource-aware scheduling, not global retry multiplication across nested layers.

## 10. Tools, destinations, and local assistance

Every worker receives an effective capability snapshot derived from parent authority, chat exclusions, helper restrictions, current connection consent, and role restrictions. Resolve aliases to a unique owner and reject ambiguity. Do not let gateway-local tools escape a disabled-tool policy because a child request has an empty visible tool list.

The inspected parent-authorized child path must be tested against selective helper restrictions; the export does not prove an actual privilege bypass. Text transforms and reviewers intentionally receiving zero tools are valid. Therefore `childTools=0` is not a blanket defect. [C1]

Local Assist, general delegation, private-server inference, and on-device native inference must identify their execution owner and share work rather than independently searching the same corpus. Use one source frontier per authorized work scope and leased source-version steps. Readers may be assigned round-robin within eligibility and health rules; do not send every URL to every engine/model just to satisfy a utilization target.

Run on-device inference through the existing native admission mechanism and measure queueing separately from generation. Avoid simultaneous incompatible model loads. A private server's upstream forwarding is a separate disclosed capability, not automatically trusted as on-device processing. No new remote fallback may occur without the applicable approval.

## 11. UI and diagnostics specification

Use the existing theme and Material components. This is a focused repair to delegation UI, not a replacement of unrelated app navigation.

### Setup

Show a compact role chain: **Primary → Helper → Independent reviewer → Final response**. Each role has an icon, model identity, destination label, and ready/blocked/unknown state. Provide a single effective-policy card with disclosure rows for the source of each override. A help button explains delegation amount versus research depth and requested versus effective limits.

Buttons: **Check readiness**, **Choose helper**, **Choose reviewer**, and **Reset this override**. A blocked role remains inspectable; do not hide its reason behind a disabled button. Connection tests distinguish metadata checks from a billable generation test before execution.

### Activity

Show current stage, elapsed active time, completed/pending/failed steps, question coverage, relevant readable sources, review status, and whether a final response was delivered. Expand a stage to see its attempts and preserved artifacts. Present **Pause**, **Resume**, **Stop research and summarize**, **Primary-only**, and **Stop all** with different labels and behavior.

Failure card example:

> Evidence saved. Final writer unavailable. HTTP 403; the provider's specific reason was not retained. Independent review unavailable. Your completed evidence has not been discarded.

Its actions are driven by actual eligibility; a writer still blocked cannot show an enabled “Resume final response” action without explaining the required check. Viewing saved evidence remains available unless deletion, privacy, or permission policy forbids it.

### Diagnostics

Default to an intelligible summary rather than raw helper JSON. Show provider/model identity, execution owner, role, invocation counts, separate success metrics, retry and cancellation reasons, latency breakdown, estimated versus reported tokens, and unknown versus measured cost. Advanced trace details remain opt-in; the incident had both delegation and reviewer traces enabled.

Icons must have meaningful accessible labels. Status must use text as well as color. Test large fonts, narrow layouts, dark/light themes, screen-reader focus, and stable scroll position during updates. Avoid a misleading single green “Success” badge for a research stage when delivery failed.

## 12. Repository implementation map

Paths below are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`. Existing symbols were inspected; explicitly proposed files are new. Locate the current settings-screen callers during implementation rather than assuming an unverified UI filename.

| Existing file or proposed addition | Required work |
| --- | --- |
| `data/repository/ChatRepositoryImpl.kt` | Integrate admission before preparation; propagate root identity; resume unfinished stages; keep writer errors and evidence separate; repair recovery prefix selection; audit child capability intersection; preserve independent primary output limits. |
| `data/agent/tool/LocalDelegationCoordinator.kt` | Consume resolved role admission; use typed stage outcomes; record role/attempt relationships; unify cancellation metadata; retain existing useful-output and readiness behavior. |
| `data/delegation/DelegationPolicyResolver.kt` | Give preview the same primary/reviewer/candidate context as execution; return structured exclusion reasons and override provenance. |
| `data/agent/TaskRecoveryAction.kt` | Extend recovery with explicit root-work and stage references without changing the original task string. |
| `data/agent/ResponseRecoveryContext.kt` | Build a manifest-first evidence resource; preserve review metadata and privacy; scope tool-result reuse to intent and authorization. |
| `data/research/ResearchJournal.kt` | Add explicit checkpoint identity, revision, cancellation, and resumable stage contracts through backward-compatible adapters. |
| `data/research/ResearchSessionStore.kt` | Persist checkpoint lineage, ownership, cancellation, review cursor, and integrity; support migrations without resetting budgets or crossing authority boundaries. |
| `data/agent/tool/AndroidResearchWorkflow.kt` | Validate planner schemas, measure relevance/coverage, resume verification, batch review, and return semantic outcomes independently of source quotas. |
| `data/research/ResearchEvidence.kt` | Add versioned question and evidence-state adapters; eliminate silent object loss and implicit 30-claim persistence loss through paged storage. |
| `data/network/error/ErrorClassification.kt` | Accept typed failures before legacy string fallback; preserve unknown provider causes and explicit cancellation. |
| `data/accounting/InvocationLedger.kt`, `data/repository/ToolEventRecorder.kt`, delegation activity store | Implementation audit touchpoints for linking IDs and independent execution/quality/delivery metrics; inspect exact mutation APIs before changing them. |
| Proposed `data/delegation/DelegationAdmissionResolver.kt` | Pure admission and policy-explanation result shared by UI, execution, and benchmarks. |
| Proposed `data/delegation/DelegationCheckpoint.kt`, `DelegationResumePlanner.kt`, `DelegationCancellation.kt` | Typed checkpoint, stage dependency/resume planning, and cancellation provenance. |
| Proposed `data/research/ResearchPlanContract.kt`, `ResearchCoverageAssessment.kt` | Typed planner parsing and task/evidence coverage evaluation. |
| Existing delegation settings/activity callers plus proposed shared repair/status components | Setup, Activity, Diagnostics presentation, accessible actions, and effective-policy explanations. |

Do not implement a second invocation ledger, a second research corpus, or an independent retry loop inside every layer. Existing models and serializers need one explicit schema-evolution plan.

## 13. Regression and acceptance matrix

Tests below are proposed additions, not claims about tests already present or executed. Put unit tests under the repository's Kotlin test tree and device tests under its Android test tree. Use injected clocks, fake profiles, deterministic model scripts, controlled tool outcomes, and temporary storage. No real API key or private conversation is needed for CI.

| Tests | Fixture and expected result |
| --- | --- |
| T01 | Successful helper and saved evidence followed by writer 403: writer stage fails, evidence persists, no false helper failure. |
| T02 | Unknown 403 versus explicit budget, authentication, and route errors: distinct messages and retry decisions; unknown stays unknown. |
| T03 | Continue after known blocked writer: readiness/recovery UI is shown; no repeated planner or successful read calls merely to retry the writer. |
| T04 | Continue creates a new message/generation but restores the same root-work checkpoint, frontier, claims, and spent budget. |
| T05 | Two unrelated chats or new requests with identical text never share a root or private artifacts implicitly. |
| T06 | Restart during writing: resume finalization from retained handoff without repeating successful research; corrupt/missing artifacts produce an explicit recoverable error. |
| T07 | Actual whole-run Stop produces a user-stop record and launches no later writer request. |
| T08 | Stop research and summarize preserves completed evidence and permitted read-only recovery access while canceling acquisition. |
| T09 | Unknown cancellation remains unknown; engine timeout, parent failure, and revocation never become “Stopped by user.” |
| T10 | Paused or superseded generations cannot commit late tool effects or overwrite a newer terminal state. |
| T11 | Reviewer is primary/same canonical model or has unresolved independence: correct eligibility result, never false independent pass. |
| T12 | Global ALLOW plus narrower cloud DENY: no cloud worker/reviewer dispatch; preview explains the effective restriction. |
| T13 | Required versus Best effort review: strict admission blocks early; best effort preserves evidence with explicit unverified status. |
| T14 | Legacy string questions, observed object questions, and new typed questions all preserve question/evidence requirements through save/load. |
| T15 | Mixed malformed planner rows do not silently become empty successful planning; repair/fallback is recorded. |
| T16 | Intentional research-not-needed result is distinguishable from parsing failure and cannot bypass a required evidence request. |
| T17 | Source target met with unresolved task questions: collection target satisfied but workflow partial, not complete. |
| T18 | Keyword-overlapping irrelevant sources cannot satisfy answer coverage or displace relevant evidence in the handoff. |
| T19 | Reaching user/device limits preserves partial evidence and reports the actual stopping constraint without inventing complete coverage. |
| T20 | Exact quote, passage entailment, publication type, relevance, and independent review remain separate; unknown publication status is not peer-reviewed. |
| T21 | Academic verifier unavailable then restored: retry verification only; no duplicate claims or repeated completed extraction. |
| T22 | Small handoff budget retains source references for included claims and mandatory review/limitation metadata; omitted artifacts remain retrievable. |
| T23 | Recovery from a corpus with hundreds of sources prioritizes relevant findings over a long metadata listing and remains within writer context. |
| T24 | Unicode and boundary cases retain valid JSON, complete source IDs, and safe paging offsets; no quote is truncated into a false claim. |
| T25 | Retry-After/transient metadata, nonretryable denial, and cancellation messages exercise the same classifier in direct, helper, reviewer, and writer paths. |
| T26 | Parent tool allowed but helper selective restriction denied: all child routes enforce the effective intersection. |
| T27 | Duplicate aliases/multiple gateway owners are rejected or explicitly resolved; tools cannot escape through gateway-local forwarding. |
| T28 | Mid-run credential, endpoint, permission, or profile changes revoke affected work without redirecting its task to a new destination. |
| T29 | Changed authority/private scope cannot reuse prohibited cached evidence; deletion prevents further reads, writes, or resurrected UI artifacts. |
| T30 | Parallel attempts reserve and settle logical/physical/model counters once; backoff and retries share the original work budget. |
| T31 | A 16 logical-call limit and 48 model-call limit are displayed and tested as separate dimensions, not accidentally equated. |
| T32 | Helper brief cap does not truncate a longer requested final answer; actual provider/context constraints remain honored. |
| T33 | Input units, schema overhead, byte accounting, and integer boundaries are correct at just-below/at/above configured limits. |
| T34 | Useful partial evidence, failed independent review, failed writer, and completed delivery yield different aggregate and stage statuses. |
| T35 | Every displayed model contribution joins to an invocation and attempt; exports reconstruct the relationship without matching timestamps heuristically. |
| T36 | More than 12 sources and 30 claims survive batched review, save/load, backup/restore, and continuation without silent tail loss. |
| T37 | Empty output, invalid schema, reasoning-only output, tool-only completion, and output limit finish reason produce correct bounded repair decisions. |
| T38 | Valid completed child output followed by stream reset is preserved; no duplicate side effect or repeat successful invocation. |
| T39 | Local Assist plus delegation obey native admission/resource constraints; incompatible loads never overlap; queue time is distinct from runtime. |
| T40 | Process death, late callbacks, profile removal, and rotation/background transitions leave one consistent terminal state and no orphan active work. |
| T41 | UI shows evidence saved/writer blocked, useful partial/unverified, and missing reviewer states with correct actions. |
| T42 | Preview and runtime select the same roles and limits for every scoped override, including the incident's mixed legacy/current depth settings. |
| T43 | Accessibility, large text, themes, narrow layouts, trace opt-in, and rapid progress updates do not hide reasons or misroute recovery actions. |
| T44 | Public audit/fixture export contains no raw keys, private endpoints, original prompts, remembered facts, or full sensitive tool responses. |

Add a replay fixture that captures the incident shape without its medical content: 24 successful helper requests, 207 discovered records, 12 readable records, 14 claims with the recorded verdict distribution, unavailable independent review, writer 403, then Continue. Assert both failure localization and evidence reuse. Add negative fixtures that prove existing working behaviors remain intact.

### Measurement and device acceptance

Measure end-to-end delivery success, relevant evidence retention, question coverage, independent-review availability, repeated completed-work count, time to first meaningful status, finalization latency, queue time, token usage provenance, and peak memory. Separate direct model success from workflow success.

For paired benchmarks, use equivalent tasks, provider/model identity, policy, cached-corpus state, and reproducible fixtures. Compare delegation enabled versus primary-only, and recovery versus fresh execution. Report quality together with latency and cost; faster but irrelevant evidence is not an improvement. Billable live tests require the configured approval and spending policy.

Run Android acceptance with on-device local inference, private-server helper, and authorized cloud helper separately. Test interruption while queued, loading, researching, reviewing, and writing. Include airplane mode/reconnect, low storage, memory pressure, temporary chats, backup/restore, and application process death. These tests have not been run as part of this documentation change.

## 14. Delivery sequence, migration, and rollback

| Work package | Dependency and deliverable | Required gate |
| --- | --- | --- |
| WP1: incident harness and contracts | Sanitized regression fixture, typed outcomes/failures, cancellation metadata, stable identifiers. | T01–T02, T09, T34–T35 reproduce the current defect and then pass with the contract change. |
| WP2: admission and known-failure recovery | Shared role preview/admission, policy provenance, writer/reviewer readiness, safe retry policy. | T03, T11–T13, T25, T42. |
| WP3: durable resume | Root-work mapping, checkpoint revision/lease, artifact manifest, intent-aware recovery, migration. | T04–T10, T22–T24, T29, T40. |
| WP4: research correctness | Typed planning, relevance and evidence gates, question coverage, resumable verification, paged claims. | T14–T21, T36. |
| WP5: capability and budget closure | All execution paths, owner routing, explicit units, shared reservations, native assistance. | T26–T33, T37–T39. |
| WP6: UI, diagnostics, and qualification | Stage-focused UI, linked telemetry, privacy checks, device tests, paired benchmark report. | T41–T44 and end-to-end incident replay. |

Implementation PRs must name the D-items and T-tests they close. Keep the documentation and contract names synchronized with implemented files. Before coding, recheck `main` and any concurrent memory/research branches; this design is pinned to the stated baseline, not an instruction to overwrite newer changes.

Migration must preserve old messages, source IDs, review limitations, tool receipts, and cumulative budgets. Map historical runs to a root only when the relationship is provable. Ambiguous legacy records remain readable but cannot resume automatically with guessed authority. Never upgrade old `Supported` claims into independently reviewed claims. Unknown cancellation origins remain unknown.

Export a new explicit schema version while retaining a read adapter for the incident format. Add migration fixtures for empty plans, mixed question shapes, oversized claim lists, interrupted writes, missing corpus chunks, and legacy policy fingerprints. Use explicit Room migrations when database changes are needed and atomic versioned files for existing file-backed research history; verify the current database version during implementation rather than copying a stale number from guidance.

Roll out behind a versioned delegation-orchestration flag with a safe disable path. Disabling new orchestration must stop new delegated dispatch, preserve artifacts, and present approved primary-only or saved-evidence recovery. It must not send partially migrated work into a legacy path that repeats external effects. Keep a checkpoint reader available even when dispatch is disabled.

Build and qualification commands should follow repository guidance: run the relevant unit tests, `./gradlew test`, `./gradlew assembleDebug`, and `./gradlew connectedAndroidTest` on an appropriate device/emulator; run the repository's configured Kotlin lint checks. No APK, version increment, merge to main, or published release is part of this design-only change.

**Definition of done:** all P0/P1 findings have passing production-path regressions; every risk has a recorded disposition; the synthetic incident resumes without repeated completed work; no unauthorized destination/tool executes; saved evidence remains accessible under its valid policy; semantic coverage and review status survive compaction/recovery; the final answer is never declared delivered when only helper work succeeded; and device/benchmark results are attached to the implementation PRs.

## 15. Reference map

The following are the actual source inspection anchors. Repository paths use the source namespace `dev/chungjungsoo/gptmobile`, not the application's package ID.

- **C1 — Chat orchestration and child execution:** [ChatRepositoryImpl.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt), inspected lines 1–190, 650–860, and 1300–1960. Key symbols: `resolveDelegatedChildResult`, `delegateToProfile`, `compactForRemoteSynthesis`, `effectiveDelegationSettings`, `prepareDelegatedEvidence`, `finalizePreparedContext`, and `streamPrimaryAnswer`.
- **C2 — Helper/reviewer coordination:** [LocalDelegationCoordinator.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/LocalDelegationCoordinator.kt), inspected beginning through reviewer logic and lines 360–570. Key symbols: `reviewerTarget`, `runReviewer`, `reviewWithinBudget`, `researchAvailable`, `localTarget`, `candidateInputBudget`, `cancelDelegation`, and `capPrompt`.
- **C3 — Scoped admission/preview:** [DelegationPolicyResolver.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/delegation/DelegationPolicyResolver.kt), full file.
- **C4 — Research identity and persistence:** [ResearchJournal.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/ResearchJournal.kt), full file; [ResearchSessionStore.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/ResearchSessionStore.kt), inspected lines 1–240, especially `journal`, `scoped`, `load(task)`, `goalBudget`, and `stopRequested`.
- **C5 — Research execution:** [AndroidResearchWorkflow.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/AndroidResearchWorkflow.kt), inspected lines 1–440 and 700 through the end. Key symbols: `runLocked`, `model`, `operation`, `search`, `searchPapers`, and `review`.
- **C6 — Evidence parsing and validation:** [ResearchEvidence.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/ResearchEvidence.kt), inspected lines 220 through the end. Key symbols: `strings`, `validatedResearchClaims`, snapshot deserialization, and `readableWorkCount`.
- **C7 — Saved response resource:** [ResponseRecoveryContext.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/ResponseRecoveryContext.kt), full file; `content`, `prefix`, `reuseCompletedTool`, and `tool`.
- **C8 — Failure classification:** [ErrorClassification.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/error/ErrorClassification.kt), full file.
- **C9 — Recovery command resolution:** [TaskRecoveryAction.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/TaskRecoveryAction.kt), full file.
- **Repository guidance:** [AGENTS.md](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/1d4dcd8698356abddc5f1be946bb0811017b7d7a/AGENTS.md), inspected before preparing this implementation plan.
- **X1 — External provider reference:** [OpenRouter limits documentation](https://openrouter.ai/docs/api_reference/limits), checked during this audit. Optional fields and error metadata must be capability-checked; this is not evidence of the incident's specific 403 cause.
- **X2 — External concurrency reference:** [Kotlin coroutine exception handling](https://kotlinlang.org/docs/exception-handling.html), checked during this audit. Use structured cancellation and explicit handling of independently supervised child failures.

## 16. Validation status of this document

Completed for this documentation change: programmatic inspection of the supplied JSON's run, invocation, diagnostic, research, and identifier data; source inspection at the pinned commit; classification of observed defects versus unproven risks; implementation mapping; and proposed regression/rollout gates.

Not performed by this change: live provider authentication tests, reproduction on the user's Android device or private server, execution of Android/Kotlin tests, runtime code modifications, release publication, or verification that the proposed fixes already work. The next implementation work must supply that evidence.
