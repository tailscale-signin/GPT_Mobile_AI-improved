# Combined Mode Final Context specification

Source: GPT_Mobile_Combined_Mode_Final_Context_Design_Implementation_2026-10-17.docx

Combined Mode Final Context Improvements

Design and Implementation Specification

GPT Mobile AI  |  Version 1.0  |  Prepared for Emanuel Melo  |  17 October 2026

Implementation baseline  App 0.9.53.0, build 122, debug variant
Repository tailscale-signin/GPT_Mobile_AI-improved
Installed commit aab12f900e46e1da6a97ebe081c778e4d5fa9b2b

1 Recommended change

Combined mode should build every follow-up from the same previously accepted combined answer, account for each relevant unique contribution, and verify the completed answer before declaring its quality checks passed. The highest-value changes are a canonical conversation record, an executable content and provenance audit, and a repair path that also works for unrestricted streaming answers.

The five observed syntheses completed at the provider level, but every one recorded COMPLETED_WITH_ISSUES. The source confirms that broad streaming answers bypass editorial repair and that helper profiles receive different prior assistant replies from the lead. These are concrete implementation gaps. The reported lexical misses are diagnostic hints; they are not counts of proven missing facts. [L1 lines 1338–1339, 2513–2514, 3741–3742, 5256–5257, 6521–6522; R1–R4]

The implementation must preserve relevant unique facts, qualifications, alternatives and unresolved disagreements without imposing an artificial short-answer limit. Explicit user constraints still govern scope and length. Source agreement, semantic similarity and provider completion must remain separate from external factual verification.

Fix diagnostic export and UI-thread logging early so the quality changes can be measured. The current conversation producer emits v4, while both the Android bundle validator and the offline replay reader accept only v2/v3. The log also captures 54 StrictMode disk violations, including 50 on the five Send actions. [L1 lines 82–891, 6758–6770, 6839–6851; R10–R14]

2 Evidence and measured behavior

Capture scope

The capture covers 17 October 2026, 22:23:35–22:37:13 in Toronto, equivalent to 18 October, 02:23:35–02:37:13 UTC. All 6,919 lines parse as JSON. There are 5,252 event roots after removing 16 flagged duplicate roots, plus 1,651 continuation records. The 177 rows marked countAsIssue are not 177 independent defects: Android exception stacks are split across multiple incident roots. [L1]

There are 41 provider invocations with matching terminal records: 38 COMPLETED, two FAILED and one PARTIAL. These comprise 33 primary segments, five synthesis calls, two output-limit continuations and one combined-coverage repair. Both failed/partial output-limit paths subsequently obtain successful continuations. All generation requests use cloud profiles; all 30 Local Assist transitions explicitly say ASSIST_OFF. Successful CPU embeddings are a separate activity. [L1 lines 922–1261, 2232–2458, 3707–3740, 6213–6522]

Results for the five combined turns

All five rows include LEXICAL_COVERAGE_UNRESOLVED_HINTS and UNVERIFIED_PASSAGES. Every final record explicitly reports semanticCoverageVerified=false and factualAccuracyVerified=false. Tentative matches are already included in unresolved hints. Raw per-source claim decisions and deduplicated candidate units use different denominators. Neither 314 decisions against 285 units nor duplicatesRemoved=0 establishes duplicate final prose. [L1 quality events; R3, R5–R7]

Turn 3:45 additionally used a repair request with an estimated 8,655 input tokens, 925 output tokens and 4.262 seconds of provider time. Send-to-quality includes contributors, tools, state settlement and repair; it is not time to first visible text. Provider visibleBytes can include PASSAGES_V2 metadata, so it is not a reliable measure of displayed answer length. [L1 lines 4, 10, 11, 16, 18, 1302–1339, 2472–2514, 3671–3742, 5221–5257, 6484–6522]

Every synthesis request received a 16,384-token allowance and stopped with finishReason=stop below that allowance. The largest input estimate was 28,815 tokens. No context-overflow rejection is recorded. Catalog context sizes are explicitly server-unconfirmed. Increasing the context or output cap alone therefore does not address the demonstrated finalization problems. [L1 lines 1301, 2471, 3667, 5220, 6483 and matching Model Finished records]

3 Prioritized issue register

P1 denotes a release-priority correctness, usability or diagnostic defect. P2 denotes a reliability or performance improvement requiring targeted implementation or measurement. P3 denotes lower-impact hardening. Each item separates an observed result from a source-confirmed cause or a remaining hypothesis. Fixes below are design requirements; the acceptance tests in Section 6 are implementation gates.

01 Canonical history differs across profiles

Priority and confidence  P1  Confirmed source behavior

Synthesis selects historical assistant messages for the lead UID. ContextBuilder selects each helper profile’s own prior answer. Synthesis persistence replaces only the lead answer slot, retaining the original as a revision and combined source. Thus the lead receives the prior merged answer while helpers continue from their own drafts. Later history-token differences corroborate these distinct routes. [R1 lines 2274–2275; R2 lines 24–28, 97–108; R4 lines 359–396; L1 lines 6226–6231, 6267, 6484]

Implementation  Persist one canonical combined assistant turn and supply it to every authorized contributor and the synthesizer. Keep per-profile answers as provenance records. Bind the canonical choice to the user turn, accepted revision and digest so switching the lead cannot change conversational history. Test follow-ups that refer to a recommendation originally supplied only by a helper.

02 Semantic completeness is not an executable completion gate

Priority and confidence  P1  Confirmed quality limitation

All five results have unresolved lexical hints and explicitly unverified semantic coverage. CombinedContentCoverage performs normalized sentence and fingerprint matching. Exact source inventories and structural/provenance validators already exist, but the active delivery route does not use them as an exhaustive semantic completion check. [L1 five quality-event pairs; R3, R5–R8]

Implementation  Account for every relevant unique claim with an included, merged, conflict, scoped exclusion or unresolved disposition. Validate quantities, conditions, negation and source-to-answer support. Treat lexical misses as candidates for review, and reserve the completed quality state for a successfully checked contract. Agreement across models must not certify truth.

03 Unrestricted streaming answers bypass repair

Priority and confidence  P1  Confirmed source cause

All four unrestricted runs are progressive=true and repairRounds=0. CombinedDeliverySession requires progressive to be false before editorial retry; coverage-based retry additionally depends on a word target. Stronger prompt wording alone cannot change that branch. [L1 lines 1303, 2473, 5222, 6485 and matching quality events; R3 lines 33–41, 112–118]

Implementation  Allow repairs for confirmed missing required material, invalid provenance or unmet instructions in broad answers. Validate staged sections before commit, or complete a new validated answer revision before switching to it. Preserve existing ownership fences and committed-prefix protection. Retain the best usable answer if a repair fails.

04 Contribution links and evidence states remain unresolved

Priority and confidence  P1  Confirmed metadata defect with evidence limitations

Turn 3:51 reports MISSING_CONTRIBUTION_LINK. All five persisted runs have evidenceRecords=0, and the visible external retrieval attempts all fail or are denied. Zero evidence is therefore not proof that stored evidence was lost. Missing model contribution links are distinct from missing web citations. [L1 lines 1290, 2461, 3660, 5211, 5257, 6473; R6–R9]

Implementation  Validate passage, claim, profile and evidence references against one frozen manifest. Reconcile missing links without inventing an owner. Keep source fidelity, external evidence availability and factual verification as separate states. Carry failed or denied research into synthesis, and preserve uncertainty when fresh evidence was required.

05 The word target remains unmet after a repair attempt

Priority and confidence  P2  Confirmed application word target failure

The constrained result records WORD_TARGET_UNDERSHOT:43/50 after one repair attempt. The original wording of the request is redacted. The source distinguishes a tolerance around targetWords but later also uses targetWords as a hard ceiling; that inconsistency is a separate risk, not a proven cause of this 43-word result. [L1 lines 3672, 3707–3742; R3 lines 107–158; R15]

Implementation  Represent EXACT, MAXIMUM, MINIMUM and APPROXIMATE length contracts separately. Count final displayed prose after protocol removal. Record repair attempted, accepted and rejected separately: repaired=true currently means a repair was attempted. Do not turn an allowed approximate response into an under-target sentence fragment.

06 Chat debug export rejects its current schema

Priority and confidence  P1  Confirmed failure and exact source cause

Two export attempts fail with UNSUPPORTED_EXPORT_SCHEMA. ChatDebugExport emits conversation.debug.v4; DebugExportBundle.conversation accepts only v2/v3. The Python replay reader also accepts only v2/v3. The producer additionally reports conversation=3 in its schemaVersions object. The bundle container’s version is a separate contract. [L1 lines 6758–6770, 6839–6851; R10–R12]

Implementation  Centralize conversation version constants and explicit supported decoders. Update producer, Android writer/reader and offline replay together. Test real producer output through the entire bundle path, including v2/v3 compatibility and unknown-version rejection. Publish a ZIP only after its content, hashes and schema validate.

07 Send and export perform disk work on the UI thread

Priority and confidence  P1  Confirmed StrictMode defects and source cause

The log contains 54 disk violations: 44 reads and 10 writes. Fifty occur as ten violations on each Send tap through AppLogRecorder.checkpoint; four occur during export directory resolution. checkpoint synchronously writes and syncs two files under a lock. The actual ZIP writer already runs on IO, but path preparation precedes that switch. [L1 lines 82–891, 1401–2207, 2576–3376, 3806–4599, 5403–6204, 6704–6757, 6785–6838; R13–R14]

Implementation  Queue ordinary interaction markers to one ordered IO writer. Make genuinely durable checkpoints explicit suspend operations on IO. Cache or prepare directory paths off the main thread; move export path preparation into the same IO stage as serialization. Preserve privacy filtering, ordering and flush-failure reporting.

08 Web search fails to provide usable evidence

Priority and confidence  P1 when the task requires research  Confirmed tool failures

Three native web_search calls fail. The first tries DuckDuckGo Lite and HTML routes and receives HTTP 202 without usable results. The later failures finish in 18 and 41 milliseconds without new matching requests in this capture. HTTP 202 does not by itself prove a challenge page; cache or cooldown behavior is not explained. [L1 lines 4906–4961, 5020–5021, 5109–5110]

Implementation  Return typed outcomes for no results, throttling, challenge, parser failure, timeout and unavailable backend. Route through already-authorized healthy engines, record cache and cooldown provenance, and stop redundant model/tool rounds against the same failed route. Pass retrieval outcome and retained successful evidence into combined context.

09 Brave fallback is blocked by permissions

Priority and confidence  P2  Confirmed denial with configuration cause unresolved

The Brave search call is denied or revoked in 41 milliseconds. The log does not identify which grant changed or prove the denied tool was still advertised. The execution guard may be functioning correctly. [L1 lines 5169–5175]

Implementation  Keep the execution-time permission check. Bind catalog entries and queued calls to grant revisions, remove or mark unavailable entries after changes, and offer a clear connection/grant repair action. A fallback may use only an already-authorized alternative. Never auto-grant access to recover a research request.

10 Unreachable MCP servers block discovery

Priority and confidence  P2  Confirmed availability and latency issue

Ports 8101 and 8102 each have three observed eight-second connection timeouts. The failure counters 7, 8 and 9 are cumulative. Two simultaneous discovery calls take about 8.54 seconds; a later call takes 8.27 seconds. These overlapping waits must not be added together. Another MCP service responds successfully. [L1 lines 1103–1156, 4817–5073, 6771–6783]

Implementation  Serve cached authorized healthy catalogs promptly, perform per-server refresh in the background, and return a partial-discovery state while offline servers back off. Reuse existing health/backoff machinery and coalesce identical probes. Record connectivity, permission and catalog freshness separately; the underlying network/server cause still needs endpoint diagnostics.

11 A provider tool batch is rejected without sufficient detail

Priority and confidence  P1  Confirmed blocked round

Xiaomi has a ToolBatchOversizeException with no calls dispatched. The associated telemetry says rejectedBatchObserved=0, permittedPerRound=8 and cumulativeObserved=2. It does not reveal whether the failed limit concerned count, bytes, streamed fragments or parsing. It is not evidence that nine or more complete calls were actually emitted. [L1 lines 1190–1197]

Implementation  Preserve all-before-dispatch admission. Count distinct complete call IDs separately from stream fragments, report the specific threshold and observed value at rejection, and regenerate a bounded smaller batch when safe. Preserve previous results and permissions. Do not simply increase the limit or dispatch a rejected batch piecemeal.

12 One output limit is consumed without visible text

Priority and confidence  P1  Confirmed segment failure with recovery

A Xiaomi segment runs for 15.155 seconds, reports 4,096 output tokens and finishReason=length, yet has zero visible and reasoning bytes. Tool-call tokens are unknown. A following continuation succeeds with 347 visible bytes. Another length-limited segment retains 17,412 visible bytes and continues successfully. The failed segments are real, but neither is an unrecovered whole-turn failure. [L1 lines 1226–1261, 2421–2458]

Implementation  Normalize text, reasoning, tool arguments and unknown usage as separate channels. Recognize zero-visible length exhaustion, retain successful text/results, and recover without restarting completed work. Record exactly what the continuation inherits and why it is safe. Do not infer hidden reasoning from the current zero-valued reasoning fields.

13 Output budget precedence overrides profile intent

Priority and confidence  P2  Confirmed policy behavior

Gemini records profileOutputCap=12096 but requested/effectiveOutputCap=4096 on all eight invocations. That is an explicit policy override, not a demonstrated provider clamp. No Gemini cap exhaustion occurs here; the two observed cap events are Xiaomi segments. Syntheses receive 16,384 and stop normally. [L1 lines 973, 2284, 3441, 4669, 5076, 5113, 5173, 6269; R16]

Implementation  Define one documented output-budget resolver with profile intent, request role, provider limit provenance and operational allowances. Log the winning rule and requested versus server-confirmed settings. Preserve continuation for genuine caps. Do not impose a new short-answer cap on combined content, and do not treat a catalog context size as a confirmed output limit.

14 Source settlement causes replaced snapshots and extra waiting

Priority and confidence  P2  Confirmed trace pattern with source explanation

Eight input-freeze events lead to five persisted syntheses. Three earlier snapshots are replaced after 5.105, 5.139 and 5.298 seconds without a superseded reason. The source has a five-second recheck on an uncommitted attempt. Candidate-unit counts change from 47 to 48 and from 17 to 20; the source count stays at five. There is no provider request for the abandoned IDs, so duplicate billed synthesis is not established. [L1 lines 1262–1289, 3655–3658, 6469–6472; R1, R17]

Implementation  Freeze after terminal contributor revisions are durably committed. Prefer event-driven settlement, retain bounded safety rechecks and existing ownership fences, and log old/new digests plus the failed predicate. Last contributor to synthesis dispatch currently takes 0.808–6.215 seconds; reduce the unexplained part without losing late continuation text.

15 The interface shows measurable jank and long frames

Priority and confidence  P2  Confirmed performance symptoms

Across 97 captured windows, 1,292 of 37,857 frames are marked jank, a weighted 3.41%. There are 15 stalls above 100 milliseconds and a maximum of 236.274 milliseconds. The recorded frame budget is 6.060606 milliseconds, consistent with 165 Hz. These are debug-build observations, and run labels do not prove which component caused a frame. [L1 Rendering records, especially 1319, 2506, 3743, 6525, 6686]

Implementation  Remove proven UI-thread IO first. Then profile Markdown parsing, attribution, state updates, list layout and WebView startup. Parse and diff off-thread where measurements justify it, retain stable block identity, and coalesce UI updates without dropping content. Benchmark profileable release-equivalent builds at supported refresh rates. [W2]

16 Large coroutine methods exceed compiler limits

Priority and confidence  P2  Confirmed debug compiler observations

Seven ART messages identify five coroutine methods with 17,476–31,404 compiler instructions: two ChatRepositoryImpl paths, OpenAICompatibleAdapter.streamRound, InvocationLedger.streamRound and AgentRunner.executeLoop. These values are compiler metrics, not AI tokens, and do not establish a crash or a measured release slowdown. [L1 lines 1300, 2208, 3440, 4615, 4685, 5109, 5219]

Implementation  Split cohesive preparation, admission, stream normalization, tool execution, continuation and accounting stages into typed components. Preserve cancellation and ownership semantics with parity tests. Check method-size and device traces after refactoring; do not promise a speed percentage from this log.

17 Graphics compilation fails near WebView startup

Priority and confidence  P2  Confirmed platform failures with visible impact unproven

WebView startup is followed by 37 skipped frames and three Adreno shader/pipeline failure pairs. No active model generation is recorded in that window. The native library loads successfully and no renderer death is shown. These are not evidence of GGUF or NPU inference failure. [L1 lines 6544, 6558, 6640, 6680–6686]

Implementation  Record the active rendering surface and recovery outcome. Reproduce source/WebView viewing with device and driver details, then use a targeted surface fallback if required. Preserve readable text and the source URL. Do not disable GPU inference or rendering globally to silence these messages.

18 Issue classification misses real defects and inflates others

Priority and confidence  P1 for reliable diagnosis  Confirmed export behavior

Every combined quality event, all 54 StrictMode violation headers and all seven compiler-limit messages are informational and excluded from countAsIssue. Conversely, two chat-export exceptions create 26 error roots. There are 4,073 StrictMode rows, mostly stack frames, which dominate the file. Top-level event-ID deduplication cannot merge separately assigned stack IDs. [L1; R18–R20]

Implementation  Classify execution, quality and performance outcomes independently. Group stack continuations under one incident, aggregate repeated causes with count/first/last occurrence, and attach recovery. A missing link or unmet user instruction is actionable; merely unverified general knowledge should not become a false failure. Never count every lexical hint as a separate defect.

19 Structured fields absorb labels and lose types

Priority and confidence  P2  Confirmed export schema defect

The key-value parser captures text until the next key. Consequently fields.eventId contains the canonical ID plus the event label or message suffix in 5,264 of 5,268 event rows; the top-level eventId remains usable. Several counters remain strings because the numeric-key pattern is incomplete. [L1; R19]

Implementation  Record typed event objects at the source: eventId, eventCode, run/turn/attempt IDs, status, numeric dimensions and display message. Keep a versioned compatibility decoder for older messages. Validate field types and exact IDs so downstream tools do not depend on parsing formatted prose.

20 Quality and latency telemetry cannot reconstruct the final answer

Priority and confidence  P2  Confirmed diagnostic gaps

SYNTHESIS_QUALITY and SYNTHESIS_DELIVERY_STARTED omit runId and turnKey. Provider bytes mix transport/protocol output with prose; candidate units, raw claims and decisions use different denominators. There are no equivalent per-stage first-render metrics in this capture. These gaps limit precise diagnosis of lost context, repetition and perceived response time. [L1 five delivery/quality events; R3, R6, R19]

Implementation  Correlate every stage by turn, attempt and snapshot digest. Export required, included, merged, excluded and unresolved unique counts; separate repair attempted/accepted; split prose and metadata sizes. Add first provider token, first decoded passage, first rendered passage and final commit timestamps. Capture permitted final text and provenance in the repaired debug bundle.

21 Account metadata survives diagnostic redaction

Priority and confidence  P3  Confirmed export minimization gap

Authorization credentials, cookies and private payloads are redacted in the inspected records, but organization and workspace identifiers remain in eight Anthropic response header sets. These are account metadata, not authentication secrets, and their values should not be reproduced in reports. [L1 representative lines 1058, 1071, 2353, 2366, 4878, 4891]

Implementation  Use an explicit safe response-header allowlist and stable pseudonyms where correlation is useful. Preserve request troubleshooting without exposing unnecessary account identifiers. Add redaction fixtures for organizational metadata alongside token, query, path and cookie tests.

4 Proposed combined answer design

4 1 One canonical conversation record

Introduce a proposed CombinedTurnContextRecord keyed by conversation ID and user-turn ID. It references the accepted final answer revision, final text digest, source snapshot digest, user constraints, unresolved issues and completion state. It is independent of the lead profile UID. Store contributor outputs and their revisions as separate records that the final answer can reference.

At the next turn, resolve the canonical dialogue once and send the same permitted dialogue to every contributor and the synthesizer. Model-specific system prompts and per-profile settings remain separate. Include the current user message, prior user corrections, referenced final answer sections, relevant attachments and explicit ongoing constraints. Do not inject every old draft merely because it exists. Use exact retained source spans when a follow-up needs additional detail.

Define behavior for a missing final answer, an interrupted answer and a user-selected older revision. A partial result can be represented as partial context, with its known missing work; it must not silently masquerade as a finalized answer. Primary-profile changes, profile deletion, app restart and retry must preserve the canonical record. Reuse ownership fences and a database transaction when switching the accepted revision. [R1–R4]

For mixed local/cloud profiles, canonical context remains subject to existing local-only and attachment permissions. Persist a common permitted core and explicit excluded-span receipts. Compare context digests only across profiles with equivalent authorization; do not satisfy a shared-history test by leaking local-only memory to a remote participant.

4 2 A task contract before generation

Derive a structured contract from the actual user request and canonical dialogue: intent, required questions, required entities, relevant time frame, output language, length semantics, requested examples, research requirement and exclusions. Broad requests default to complete relevant coverage. “One example” and “at most 50 words” legitimately narrow what the final answer must include.

Record the contract revision and input digest. User corrections supersede earlier assumptions. Treat contributor prose, tool output and retrieved pages as data; they cannot modify the user’s task contract. Avoid fixed quotas by profile or arbitrary paragraph limits. A concise contributor’s unique caveat must remain eligible even when another contributor provides most of the words.

4 3 Durable contributor collection and source freezing

Represent each contribution with profile/model identity, run and revision IDs, final or partial status, ordered continuation segments, evidence references and tool outcomes. A terminal status is usable for freezing only after the corresponding content revision has committed. Freeze the exact authorized source corpus, contract and canonical history in one versioned manifest; compute a digest over content and relevant policy revisions.

Use one durable attempt identity through scheduling and dispatch. If a newer source revision invalidates an uncommitted snapshot, emit SUPERSEDED with old/new digests and a reason. If a snapshot is committed, a new attempt must use the existing retry/revision rules. Replace polling delays with notifications from successful persistence where possible, retaining bounded rechecks as a fallback. [R4, R17]

4 4 A content ledger with explicit dispositions

Reuse CombinedClaimInventory, exact source sections, contribution lineage and structural validators. Extract atomic claims with subject, relation, object, quantities, units, dates, modality, polarity and conditions. Cluster equivalent claims while retaining every supporting source span and profile. Keep similar statements separate when a qualifier, time frame, dependency or exception changes their meaning. [R5–R8]

Track raw source claims, unique claim clusters and required unique clusters separately. Validate every disposition reference and ensure there is exactly one terminal disposition per audited unit under the chosen ledger schema. An excluded duplicate must point to a surviving equivalent claim. An irrelevant unit must be excluded for the current task, not removed merely because it originated from an inconvenient profile.

Optimize prompt duplication only after fidelity is established. The current envelope includes candidate sentence text and full contributor responses. Represent original text once with immutable span references and give each physical model request all spans it needs. A hash or ID alone is not usable context for a model that cannot resolve it. Preserve exact source access and audit receipts when using existing multipass processing. [R5, R21]

4 5 Answer planning and source fidelity

Build a topic outline from required unique units. Assign each unit to a section before writing prose, group overlapping explanations and retain minority caveats. The writer should synthesize one coherent answer, preserving unique details and avoiding a sequence of separate profile summaries. Long answers can use headings and tables when they help; length follows the requested scope.

Run source-fidelity checks independently of style and external factual support. Check numbers, dates, units, negation, conditions and required alternatives deterministically where possible. Use a bounded semantic comparison for entailment and contradiction when lexical checks are insufficient. Store actual verifier revision, inputs and completion receipts; never accept model-declared verification or confidence as a substitute for completed checks.

An externally supported assertion must point to readable evidence appropriate to the claim. A search result or snippet may help discover a source without proving the full assertion. General knowledge and creative answers can remain useful without unsolicited research. When current evidence was explicitly required, missing retrieval must result in an honest limited-evidence state. [R7–R9]

4 6 Streaming and repair without losing completed work

Keep responsive streaming by validating complete section blocks off-thread before making them durable. Each block has an attempt owner, stable passage ID and content digest. Transport framing, attribution decisions and raw claim inventories stay outside the normal prose stream. The UI can display a clear progress state while a draft section or final completeness check is pending.

When review identifies a real missing requirement, repair that requirement against the frozen source corpus. A proposed default allows up to two automatic repair attempts, constrained by provider limits, permissions, cancellation and measurable improvement. This operational retry budget is independent of answer length. Stop early when the contract passes or a further attempt makes no progress; never loop just to improve lexical similarity.

Reuse the existing committed-prefix and revision protections. A repair that changes earlier committed text must be a new complete validated revision, then an atomic revision selection; it cannot overwrite another attempt’s prefix. A failed, timed-out or interrupted revision must leave the best usable answer available. Do not append unedited source fragments as an “omissions” dump. [R3–R4]

Record repair trigger, targeted units, attempted/accepted outcome, before/after issues, token/time cost and final selected revision. If essential work remains unresolved, preserve the answer with an accurate incomplete-quality state and an available continuation action. Completion of the provider stream alone does not mean the task contract passed.

4 7 Provenance that follows the final text

Treat model contribution, exact wording lineage, shared meaning and external evidence as different relationships. A passage can have several contributors. Neutral attribution is valid when exclusive ownership cannot be established. Missing or inconsistent IDs require reconciliation or an unresolved marker, not a fabricated profile assignment.

After normalization and each accepted repair, rebuild and validate final UTF-16 ranges, grapheme boundaries and digests against the exact selected answer revision. Commit text and its matching metadata together. Reuse CombinedProvenanceValidator qualification and physical-comparison receipts when making strong semantic or exclusive-attribution claims. [R6–R8]

Normal combined chat should present readable prose with accurate theme/profile color treatment. Keep “Text match,” “From profile,” raw source comparisons and detailed attribution diagnostics inside Debug. Preserve external source links where they substantiate the answer. Support shared attribution without forcing a single contributor color or claiming exclusivity from a substring match.

4 8 Context and output budgeting

Resolve input capacity and output allowance separately for each physical request. A proposed budget record includes role, requested output, profile setting, provider capability source, server confirmation state, estimated/exact input usage and the rule that selected the effective allowance. The synthesis cap in this capture was not exhausted. The contributor policy that selects 4,096 deserves explicit review because it overrides a larger configured profile allowance.

Reuse ContextBudgetService full-input planning and CombinedCorpusMultipass/Session for actual overflow. Preserve a manifest of original spans, processed spans, carried-forward claims and omitted-by-policy spans. Do not silently truncate an entire contributor, history turn or attachment to make a budget fit. Budget instructions and protocol metadata as well as prose. [R16, R21]

Provider tokens can cover visible text, reasoning or tool content depending on the provider. OpenRouter documents that an output allowance can be consumed by reasoning on supported models; this is a possible failure class for tests, not a demonstrated explanation for the Xiaomi segment in this log. Keep unknown channels unknown and preserve original provider usage for reconciliation. [W1]

4 9 Search and tool recovery

Use typed search and permission outcomes before synthesis. A successful discovery call only provides tool definitions. A successful retrieval produces an evidence receipt with source URL, retrieval time, content digest, readability and applicable ownership/permission. Failed search text is operational metadata, not factual evidence for the answer.

Return available healthy tools promptly when optional MCP servers are offline. Defer unavailable endpoint probes behind existing backoff, share concurrent probes and bind cached catalogs to grant revisions. Treat count limits, argument-size limits, malformed streams and revoked permissions as different admission failures. Preserve atomic validation and use authorized fallback or a bounded regenerated batch.

Keep final editing free of independent tool execution, as in the existing design. If the task contract requires additional research, request it through the existing authorized contributor/research workflow and create a new explicit source snapshot. This prevents the synthesis stage from silently widening permissions or changing its evidence corpus mid-answer.

4 10 Typed diagnostics and performance instrumentation

Every event needs eventId, eventCode, conversationId, turnId, runId, attemptId, stage, snapshotDigest and relevant revision IDs. Quality outcomes and provider outcomes must be separate fields. Add logical incident identity for stack traces and repeat counts, with the original source and recovery attached. Retain the raw formatted message for human debugging, not as the sole structured-data source.

Use the repaired conversation bundle for permitted final prose, contributor snapshots, quality sidecars and stage manifests. Preserve default payload redaction and private-session behavior. Add a safe header allowlist to avoid retaining account metadata. Record dropped events, missing sequences, storage failures and capture limitations explicitly; droppedRecords=0 does not mean full vendor or provider-wire visibility.

For performance, replace synchronous Send logging before changing renderer settings. Profile the long combined answer, active scrolling, source viewing and foreground transitions with system/composition traces. Refactor oversized suspend methods behind behavioral parity tests. Evaluate native allocations and embeddings separately from provider latency. Android recommends traces for locating Compose costs and profileable non-debuggable builds for accurate timing. [W2]

5 Repository implementation map

Paths below are relative to app/src/main/kotlin/dev/chungjungsoo/gptmobile/ unless a scripts or test prefix is shown. Within a row, bare filenames inherit the preceding directory. These targets were verified at the installed commit. Proposed record/component names in Section 4 are new design elements; integrate them with existing persistence and validators.

Preserve existing exact-corpus multipass support, no-tools synthesis, local-only filtering, source revisions, ownership fences, cancellation behavior and partial-answer recovery. The prompt already asks for unique detail retention and coherent synthesis. The new work is the runtime contract, shared context, validation and recovery path; a prompt-only change is insufficient. [R1–R9, R16–R21]

6 Acceptance and regression tests

Use deterministic fixtures for structural and lifecycle guarantees, plus a held-out, human-reviewed semantic set for meaning preservation. Synthetic content examples below are test inputs, not claims about the redacted conversation. Do not certify semantic truth from a self-reported score. Suggested numeric gates are engineering targets to establish before release, not performance improvements already measured.

Extend existing CombinedDeliverySessionTest, CombinedSynthesisPromptTest, ContextBuilder tests, claim/provenance tests, CombinedSynthesisGate tests and export/replay tests. Add an end-to-end combined-turn fixture spanning collection, canonical history, synthesis, accepted repair, final persistence and export. The current bundle unit fixtures default to v3; the production producer-to-consumer test is essential. [R10–R12, R22]

7 Delivery phases and completion gates

Phase 1 Restore observability and remove proven blocking work

Implement issues 06, 07, 18, 19 and 21 first: align export schemas across Android and Python, move ordinary logging/export preparation off the UI thread, repair incident grouping and typed fields, and tighten header redaction. Add stage/run correlation while touching event producers. Completion gate: actual current conversation exports open and replay, no routine main-thread disk violations, and issue summaries match logical incidents.

Phase 2 Correct the shared combined conversation

Introduce canonical accepted-turn selection with revision ownership and privacy-aware history. Wire every contributor, initial synthesis and frozen retry to it. Preserve original source drafts for provenance. Completion gate: T01–T02 and restart/primary-switch coverage pass; all equivalent-authorized participants receive the same canonical history digest.

Phase 3 Make final quality accountable and repairable

Integrate claim dispositions, source-fidelity review, passage metadata reconciliation and distinct length contracts. Add repair for broad answers while preserving the best accepted revision and existing prefix fences. Completion gate: deterministic fixtures have 100% accounted required units and valid structural links; semantic holdout evaluation meets a predeclared threshold with separately reported precision, recall and uncertainty.

Phase 4 Improve tools and source settlement

Repair typed search routing, permission/catalog reconciliation, MCP partial discovery, oversize-batch diagnostics, budget precedence and continuation accounting. Replace unexplained five-second rechecks with durable source-settlement notifications where possible. Completion gate: tool failures do not fabricate evidence, known-offline optional servers do not block healthy catalogs, and stale attempts cannot dispatch or commit twice.

Phase 5 Measure performance and release safely

Refactor oversized coroutine stages and profile rendering, WebView startup and native allocations after correctness changes. Use the affected device and representative 60/120/165 Hz configurations with debug and release-equivalent builds. Completion gate: no regression in first rendered content, canonical history, answer coverage, permissions, export or partial recovery. Set measured handoff and frame budgets against a reproducible baseline, rather than promising a speed-up from this single capture.

Roll out schema/read compatibility before enabling a new canonical record or quality sidecar. Keep new record fields versioned and preserve older snapshots. Feature flags may stage the new selection, audit and repair paths independently for evaluation, but a rollback must retain user-visible final answers and their source revisions. Never remove privacy, admission or ownership checks as a rollback shortcut.

A release can report provider completion and remaining quality limitations separately. Do not make “all log warnings gone” a gate: benign media probes and optional-feature messages should remain correctly classified. Track source fidelity, task-constraint compliance, external evidence, repair acceptance, perceived latency and device performance as distinct outcomes.

8 Other logged conditions and their treatment

The following observations complete the log inventory. They should not be promoted into unsupported diagnoses. Their proposed actions are proportionate to the evidence.

9 Evidence and reference index

Log identity and exact locators

L1  app-diagnostics-5974421491069373041.jsonl
Size 5,422,780 bytes. Physical JSONL lines 1–6919.
SHA256 fae2dff320f651a1032f605ac37bf7b066bef9071132886fc01f458d9bd45eba.

Pinned repository sources

All repository links below resolve to the installed commit aab12f900e46e1da6a97ebe081c778e4d5fa9b2b. Findings at this baseline must be reconciled with the target development branch before implementation; a different main commit is not evidence that these exact paths are already fixed.

R1 Combined scheduling, historical lead selection, source freezing and dispatch. Open pinned source

R2 Per-profile historical assistant selection and history construction. Open pinned source

R3 Progressive delivery, constrained repair, length checks and quality reporting. Open pinned source

R4 Atomic persistence, revision preservation and ownership fences. Open pinned source

R5 Exact source inventory and structural coverage validation. Open pinned source

R6 PASSAGES_V2 decoding, claim decisions, offsets and contribution-link checks. Open pinned source

R7 Lexical matching, unique-unit counting and tentative-match semantics. Open pinned source

R8 Digest, range, permission and qualified-verifier receipt checks. Open pinned source

R9 Current-turn evidence intake and readable passage status. Open pinned source

R10 Current v4 producer and schemaVersions metadata. Open pinned source

R11 v2/v3 acceptance, bundle layout, integrity checks and archive reader. Open pinned source

R12 Offline conversation reader with v2/v3 acceptance. Open pinned source

R13 Direct checkpoint file writes and queued ordinary logging. Open pinned source

R14 Export directory preparation and IO write boundary, lines 1635–1680. Open pinned source

R15 Request-derived scope and word-target tolerances. Open pinned source

R16 Synthesis output allowance and continuation orchestration. Open pinned source

R17 Attempt reservation and uncommitted retry coordination. Open pinned source

R18 Severity and actionable-issue classification. Open pinned source

R19 Event identity and formatted-message field extraction. Open pinned source

R20 Diagnostic grouping and incident presentation. Open pinned source

R21 Full-input budget and exact-corpus multipass entry points. Open pinned source

R22 Bundle fixtures that currently default to v3. Open pinned source

Official implementation references

W1  OpenRouter API parameters. Output allowances, supported reasoning controls and parallel tool-call parameters. Use capability-specific behavior; this reference does not identify the cause of an unaccounted token segment. Open reference

W2  Android Developers Composition tracing. Use composition/system traces to locate rendering work and a profileable non-debuggable build for accurate timing. Keep heavy trace capture dependencies out of ordinary production packaging. Open reference


TABLE


Turn | Candidate
units | Exact and
paraphrase | Unresolved
lexical hints | Repair
attempts | Additional result

3:33 | 48 | 14 + 5 | 29 | 0 | Common quality flags

3:39 | 285 | 121 + 16 | 148 | 0 | Common quality flags

3:45 | 20 | 1 + 2 | 17 | 1 | 43 words against target 50

3:51 | 88 | 10 + 8 | 70 | 0 | Missing contribution link

3:57 | 93 | 13 + 17 | 63 | 0 | Common quality flags


TABLE


Turn | Input token
estimate | Provider output
tokens | Synthesis
seconds | Send to quality
seconds

3:33 | 8,236 | 1,674 | 3.303 | 45.655

3:39 | 28,815 | 13,911 | 25.098 | 58.359

3:45 | 7,182 | 559 | 2.352 | 25.021

3:51 | 16,409 | 2,553 | 7.977 | 39.341

3:57 | 16,538 | 2,888 | 6.922 | 25.389


TABLE


Disposition | Required record | Completion meaning

INCLUDED | Final passage ID and source span IDs | Relevant claim appears faithfully

MERGED | Surviving claim and passage IDs | Equivalent meaning retained once

CONFLICT | Opposing claims and final treatment | Disagreement preserved or resolved with evidence

EXCLUDED | Scope, duplicate or permission reason | Justified exclusion, not silent loss

CONSTRAINT
OMISSION | Explicit user constraint and priority | Allowed only by the task contract

UNRESOLVED | Failure reason and next action | Cannot count as completed coverage


TABLE


Measurement | Required distinction

Content coverage | Raw claims / unique clusters / required units / terminal dispositions

Evidence | Not requested / attempted / readable / failed / denied / insufficient

Repair | Eligible / skipped with reason / attempted / accepted / rejected

Output | Transport / protocol metadata / decoded prose / rendered text

Latency | Preparation / tools / settlement / provider first token / first render / commit

Incidents | Occurrence / repeated cause / stack continuation / recovered status


TABLE


Workstream | Existing targets | Required result

Canonical history | presentation/ui/chat/ChatViewModel.kt
 data/context/ContextBuilder.kt
 data/database/dao/AgentPersistenceDao.kt | One accepted combined history independent of lead profile

Completion and repair | data/agent/CombinedDeliverySession.kt
 CombinedContentCoverage.kt
 CombinedAnswerPolicy.kt | Executable quality gate and accepted repair outcomes

Claims and provenance | data/agent/CombinedClaimInventory.kt
 CombinedPassageDelivery.kt
 CombinedProvenanceValidator.kt
 CombinedEvidenceLedger.kt | Complete claim dispositions and valid final-text links

Snapshot lifecycle | presentation/ui/chat/CombinedSynthesisGate.kt
 CombinedSynthesisInputs.kt
 ChatViewModel.kt | Durable source settlement and explained supersession

Budget and continuation | data/agent/CombinedResponseBudget.kt
 data/context/ContextBudgetService.kt
 CombinedCorpusMultipassSession.kt
 data/repository/ChatRepositoryImpl.kt | Explicit budget precedence with no silent source loss

Debug export | presentation/ui/chat/ChatDebugExport.kt
 DebugExportBundle.kt
 ChatScreen.kt
 scripts/replay_conversation_debug.py | Production v4 bundle and offline reader compatibility

Diagnostics | data/diagnostics/AppLogRecorder.kt
 DiagnosticClassification.kt
 StructuredDiagnosticExport.kt
 GroupedAppLog.kt | No ordinary UI-thread IO and correct typed incidents

Tool and provider stages | data/agent/AgentRunner.kt
 Existing provider adapters and tool routing | Observable safe admission, recovery and search outcomes

Chat presentation | presentation/ui/chat/CombinedPassageIndicators.kt
 CombinedQualityInspector.kt
 CombinedTextMatchVisibility.kt | Clean prose, stable validated attribution and debug details


TABLE


Test | Scenario | Acceptance condition

T01 | Shared final context | A helper-only recommendation becomes the combined final. Every authorized next-turn profile resolves a follow-up to that same accepted answer.

T02 | History and privacy | Change lead, remove a profile, restart and select a prior revision. Canonical ownership stays valid; local-only spans never enter unauthorized remote requests.

T03 | Complete unique coverage | Five profiles contribute shared points plus ten distinct required details. All ten required details survive once, with valid dispositions and final links.

T04 | Semantic precision | 10 versus 100, may versus must, before versus after, and negated support remain distinct. A shared vocabulary cannot manufacture entailment.

T05 | Minority qualification | Four repetitive answers cannot erase a concise unique caveat or unsupported-majority conflict. Important disagreement is represented accurately.

T06 | Scoped exclusions | One-example, maximum-length and irrelevant-source fixtures exclude only what the task contract permits, with valid reasons.

T07 | Broad answer repair | An unrestricted draft omits a required caveat. Targeted repair restores it without dropping other required content or exposing raw inventories.

T08 | Repair failure safety | Timeout, cancellation, malformed output or a worse revision retain the best usable answer. Attempted and accepted counters differ correctly.

T09 | Length contracts | Exactly 50, at most 50 and about 50 use distinct checks. Cover 43, 45, 50, 55 and 56 words; count only final displayed prose.

T10 | Final provenance | Unicode, Markdown changes, reordered passages and inserted text retain correct digest-bound spans. Missing/wrong IDs cannot receive certified ownership.

T11 | Missing web evidence | All retrieval routes fail or are denied. No fabricated citations or verified badge appears; useful supported context and uncertainty are retained.

T12 | Evidence quality | Search snippets, readable pages, stale sources, conflicting dates and shared URLs receive the correct distinct evidence status.

T13 | Source settlement | Last delta arrives after UI completion but before durable completion. Freeze includes it; duplicate callbacks dispatch only one synthesis.

T14 | Restart and ownership | Background/foreground, process recreation and stale snapshot attempts cannot overwrite a newer committed revision. Every supersession has a reason.

T15 | Input overflow | Full corpus fits or uses existing exact-span multipass with complete receipts. No contributor, required history or attachment disappears silently.

T16 | Output channels | Visible cap exhaustion, reasoning-only output, tool-only completion and unknown usage each retain truthful status and safe nonduplicating continuation.

T17 | Budget precedence | A configured 12,096 cap and each request role resolve through the documented rule. Requested, effective and confirmed values remain distinct.

T18 | Tool admission | Stream fragments, nine calls with limit eight, large arguments, duplicate IDs and incomplete JSON produce correct counters and no unsafe partial dispatch.

T19 | Search outcomes | 202 unusable body, 200 no results, changed markup, 429 and timeout yield typed outcomes and only authorized healthy fallback.

T20 | MCP and grants | Healthy tools remain available while two endpoints time out. Revocation before and after advertisement blocks dispatch and refreshes the catalog.

T21 | Production export | Actual v4 producer output passes Android write/read and Python replay. v2/v3 fixtures remain supported; unknown incompatible versions fail clearly.

T22 | Archive integrity | Check hashes, byte counts, required files, duplicates, unexpected paths, size limits, redaction and storage failures; no partial ZIP is shared as success.

T23 | Send and export IO | Repeated taps with StrictMode, slow storage and rotation produce zero main-thread disk violations from routine logging or export preparation.

T24 | Diagnostic replay | Recover 54 disk violations, two export exceptions, six MCP timeout observations and four failed retrievals; deduplicate 16 copied roots.

T25 | Typed events | IDs exclude labels, counts are numbers, booleans stay booleans, quality incidents have correlation IDs, and stack lines share incident identity.

T26 | Smooth presentation | Long answers at 60/120/165 Hz preserve text, selection and scroll while streaming. Measure first render and frames in a release-equivalent build.

T27 | Coroutine parity | Refactored admission, accounting and provider loops preserve cancellation, permissions, continuation and finalization; inspect compiler-limit recurrence.

T28 | Graphics and redaction | Reproduce WebView startup/failure with surface attribution; keep readable fallback. Header fixtures remove account IDs without removing safe correlation.


TABLE


Observation | Evidence | Interpretation and action

Native allocation pressure | One Scudo size-class message at line 2565; four NativeAlloc GCs at 5323, 6210, 6703, 6784 | Profile if repeated. No overall OOM is shown. Concurrent GC totals of about 105–114 ms are not equal to stop-the-world pauses.

Embedding lifecycle | Two engine creations in 39/38 ms; successful 100-dimensional input; handles return from 1 to 0 | No embedding leak or failure established. Releases say memory_pressure while lowMemory=false; add exact trim/lifecycle cause before changing retention.

Optional native features | Three GetEnv not-attached messages at 899, 3615, 3617; two feedback warnings at 898, 3613 | Successful embedding follows. These do not establish JNI crashes; keep component-specific severity and recovery context.

Provider reachability probes | Seven HEAD 404 responses on api.anthropic.com root; provider POST responses recorded as 200 | A root HEAD 404 is not a failed messages request. Label reachability, authentication and API capability separately.

Codec capability probes | 19 unsupported codec capability messages, 17 HEVC-profile messages and three cr_VAUtil profile messages | No attempted media operation fails in the capture. Record as capability observations rather than user-facing generation incidents.

Camera and Bluetooth warnings | Six ignored camera-status messages and three Bluetooth permission warnings during WebView startup | No demonstrated requested camera/audio failure. Do not add broad permissions solely to suppress these messages.

WebView startup details | Two missing seed info messages and one non-executable image warning; native library load succeeds | Track only a reproduced initialization or rendering failure. Correlate with issue 17 rather than inventing a model-backend cause.

Keyboard callback | One ignored ImeBackDispatcher callback at line 1287 | No demonstrated keyboard failure. Include foreground/keyboard transition coverage if reproducible.

Capture boundaries | ProcessExit coverage=no_records; vendor_log_available=false; droppedRecords=0 and no truncation flags | No crash or ANR is demonstrated here. Stream completeness does not certify unavailable OS/vendor evidence.

Local generation | All provider invocations cloud-backed; Local Assist OFF in 30 transitions | This capture cannot validate GGUF, QNN or LiteRT-LM generative inference or local tool calling. Do not change those backends on this evidence.


TABLE


Evidence family | Physical line locators

Build and capture metadata | 1–3, 23, 6914

Final combined quality | 1338–1339; 2513–2514; 3741–3742; 5256–5257; 6521–6522

Synthesis inputs and timing | 1289–1337; 2459–2512; 3658–3740; 5210–5255; 6472–6520

Contributor history | 2226–2233; 3398–3459; 4623–4635; 6226–6231 and 6267

Tool batch and continuations | 1190–1261; 2421–2458

Search and permission failures | 4906–4961; 5020–5021; 5109–5110; 5169–5175

MCP connection and discovery | 1103–1156; 4817–5073; 6771–6783

UI thread disk violations | 82–891; 1401–2207; 2576–3376; 3806–4599; 5403–6204; 6704–6757; 6785–6838

Failed debug exports | 6758–6770; 6839–6851

Compiler and graphics events | 1300, 2208, 3440, 4615, 4685, 5109, 5219; 6544–6686