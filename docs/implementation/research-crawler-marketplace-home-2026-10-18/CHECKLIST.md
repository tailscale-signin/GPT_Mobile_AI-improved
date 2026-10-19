# Document coverage checklist

Each source block below retains its original ordering in `SOURCE_INDEX.json`. Checked-in code is not completion evidence: an item stays unchecked until its wiring and applicable acceptance tests have been reviewed. Future increments and explicitly optional adapters retain the scope stated by their source documents.

## GPT Mobile Combined Mode Reliability Design Implementation 2026-10-18

Source SHA-256: `414c3700ae87658f3706d805e9507475c3aa92a00aa4d2fc2989329f1b4f59ca`

- [ ] B004 · 18 October 2026   |   Design version 1.0
- [ ] B006 · Purpose and implementation decision
- [ ] B010 · Required outcomes
- [ ] B016 · Contents
- [ ] B017 · 1 Diagnostic baseline	3
- [ ] B018 · 2 Scope and invariants	4
- [ ] B019 · 3 Architecture and component ownership	4
- [ ] B020 · 4 Valid conversation requests	6
- [ ] B021 · 5 Contribution eligibility and visible output	6
- [ ] B022 · 6 Frozen context and compact claim contracts	7
- [ ] B023 · 7 Resumable structured passage delivery	9
- [ ] B024 · 8 Quality assessment and useful repairs	10
- [ ] B025 · 9 Evidence collection and factual verification	11
- [ ] B026 · 10 Adaptive output budgets and admission	12
- [ ] B027 · 11 Orchestration and performance	13
- [ ] B028 · 12 Network recovery and lifecycle states	14
- [ ] B029 · 13 User interface and interaction	15
- [ ] B030 · 14 Diagnostics and accounting integrity	16
- [ ] B031 · 15 Persistence and migration	17
- [ ] B032 · 16 Repository implementation map	18
- [ ] B033 · 17 Development phases and release gates	19
- [ ] B034 · 18 Acceptance test matrix	20
- [ ] B035 · 19 Rollout and completion criteria	22
- [ ] B036 · 20 Evidence and source references	23
- [ ] B037 · 1 Diagnostic baseline
- [ ] B041 · Failures that determine the design
- [ ] B045 · Recorded performance and diagnostic limits
- [ ] B049 · 2 Scope and invariants
### B051: ID | Required invariant

- [ ] CM01 — Current pending assistant state never becomes semantic model history.
- [ ] CM02 — Request validation applies to serialized provider messages and current capabilities.
- [ ] CM03 — Only eligible visible answers enter the contribution inventory.
- [ ] CM04 — A passage is delivered only after its complete record is validated and committed.
- [ ] CM05 — Continuation preserves sequence and repair swaps a complete candidate atomically.
- [ ] CM06 — Synthesis and repair share the same relevant frozen conversation context.
- [ ] CM07 — Factual verification requires retained supporting evidence for the assessed claim.
- [ ] CM08 — User limits and permission revocations remain authoritative during recovery.
- [ ] CM09 — Every terminal workflow resolves all required work items with explicit outcomes.
- [ ] CM10 — Diagnostics distinguish measured usage, estimates, reservations, and unknown usage.

- [ ] B052 · Boundaries
- [ ] B055 · 3 Architecture and component ownership
- [ ] B060 · 4 Valid conversation requests
- [ ] B061 · History projection
### B064: Request kind | Required tail and treatment

- [ ] INITIAL — Current user message last; no current assistant placeholder.
- [ ] TOOL_CONTINUATION — Preserve the provider specific tool call and result pairing; validate all required IDs.
- [ ] OUTPUT_ CONTINUATION — Use the selected delivery protocol and resume cursor; never infer intent from an arbitrary trailing assistant string.
- [ ] REPAIR — Carry frozen task and relevant history plus the target revision and specific defects.
- [ ] INTENTIONAL_PREFILL — Allowed only through an explicit supported capability and dedicated caller.

- [ ] B065 · Provider validation and admission
- [ ] B068 · Request regression cases
- [ ] B070 · 5 Contribution eligibility and visible output
### B072: Outcome example | Eligibility | Required handling

- [ ] Substantive answer — ELIGIBLE — Add relevant claims and preserve contributor identity.
- [ ] Short correct greeting — ELIGIBLE — Accept without a minimum word threshold.
- [ ] Internal status echo — INELIGIBLE — Record INTERNAL_STATUS_ECHO and exclude from claims.
- [ ] Pure prompt echo — INELIGIBLE — Record NON_ANSWER unless repetition was requested.
- [ ] Reasoning only — INELIGIBLE — Record NO_VISIBLE_OUTPUT and consider permitted recovery.
- [ ] Valid partial answer — PARTIAL — Preserve useful committed material and the delivery limit.
- [ ] Provider request failure — INELIGIBLE — Expose retry only when appropriate to the failure class.

- [ ] B075 · Reasoning and response normalization
- [ ] B078 · 6 Frozen context and compact claim contracts
### B081: Field group | Required content

- [ ] Content identity — Snapshot ID, schema version, accepted user ID, selected history revision IDs, digest.
- [ ] Policy — Requested profiles, output contract, mode settings, user limits and effective planning version.
- [ ] Contributions — Run and attempt IDs, source body digest, eligibility and exclusion reason.
- [ ] Claims — Short model reference, proposition, essential qualifiers, contributor references and source ranges.
- [ ] Evidence — Stable evidence IDs, retrieved excerpts, source URLs, retrieval time and applicability status.
- [ ] Delivery binding — Selected protocol; delivery sessions reference this snapshot and own revisions, cursors and fragments.

- [ ] B082 · Context supplied to the editor
- [ ] B093 · Context stability during repair
- [ ] B096 · 7 Resumable structured passage delivery
- [ ] B097 · Protocol selection
- [ ] B108 · Commit and resume algorithm
- [ ] B118 · Recovery example
- [ ] B121 · Atomic answer repair
- [ ] B123 · 8 Quality assessment and useful repairs
- [ ] B126 · Required topic coverage
- [ ] B129 · Qualifier extraction
- [ ] B131 · Repair decision policy
- [ ] B135 · 9 Evidence collection and factual verification
### B138: Evidence record | Required information

- [ ] Identity — Stable evidence ID, canonical work or page identity, URL and retrieval timestamp.
- [ ] Content — Retained supporting excerpt and its digest, location, and available content type.
- [ ] Applicability — Claim assessed, relevant population or scope, qualifier match and contradictory passages.
- [ ] Verification — Supported, contradicted, insufficient or not assessed, with assessment method and time.
- [ ] Provenance — Physical retrieval attempt, tool event and derived records without duplicate source inflation.

- [ ] B142 · 10 Adaptive output budgets and admission
- [ ] B144 · Budget calculation
### B147: Event category | Accounting and recovery contract

- [ ] Initial request — Reserve permitted work; record the actual dispatched attempt and provider usage when observed.
- [ ] Network retry — Link to the original attempt and error class; preserve unknown usage when delivery is ambiguous.
- [ ] Output continuation — Reserve the next required records; retain committed text and the same protocol.
- [ ] Empty output recovery — Record visible output failure, changed supported settings, and a separate attempt.
- [ ] Quality repair — Identify the material defect, base revision, and acceptance requirements.
- [ ] Admission rejection — Record NOT_DISPATCHED with a typed reason and no fabricated provider answer.

- [ ] B148 · Typed rejection handling
- [ ] B151 · 11 Orchestration and performance
- [ ] B152 · Per turn readiness
- [ ] B155 · Measurement plan
### B156: Interval | Required events

- [ ] Contributor latency — Queued, dispatched, first provider output, first visible text, provider terminal and run committed.
- [ ] Preparation overhead — Last required contributor terminal, synthesis eligible, snapshot start and end, claim compilation start and end.
- [ ] Validation overhead — Delivery assessment, requirement assessment, repair decision and persistence spans.
- [ ] Final delivery — Synthesis dispatch, first complete passage committed, first passage rendered, final committed and root closed.
- [ ] Waiting — Reason, responsible dependency, start, end and AUTOMATIC or USER merge trigger.

- [ ] B158 · Local work reduction
- [ ] B161 · Proposed engineering targets
- [ ] B164 · 12 Network recovery and lifecycle states
- [ ] B166 · Coordinated retries
- [ ] B171 · Cancellation and restart
- [ ] B174 · 13 User interface and interaction
- [ ] B178 · Details view
- [ ] B181 · Presentation and accessibility
- [ ] B184 · 14 Diagnostics and accounting integrity
### B186: Event family | Required fields

- [ ] Turn accepted — Turn ID, immutable request ID, build revision, policy version, selected participant IDs, merge trigger.
- [ ] Snapshot sealed — Snapshot ID, turn ID, predecessor snapshot, selected record digests, protocol and sealing time.
- [ ] Request validation — Purpose, serializer and validation versions, effective role tail, capability provenance and result.
- [ ] Admission and dispatch — Attempt ID, permitted request digest, result, typed rejection, dispatch time and accounting category.
- [ ] Provider result — Normalized and native finish reason, visible characters, reported reasoning usage and tool outcomes.
- [ ] Passage handling — Revision, sequence, record ID, validated digest, commit or parser failure and recovery cursor.
- [ ] Quality and repair — Dimension, requirement IDs, target defect, base revision, candidate result and decision.
- [ ] Closure — Root outcome, child outcomes, remaining blocked work and resumable checkpoint.

- [ ] B187 · Export contract
- [ ] B190 · Usage semantics
- [ ] B193 · 15 Persistence and migration
### B195: Record or extension | Key contract | Migration behavior

- [ ] CombinedInputSnapshot — Immutable content identity and selected protocol — Preserve old snapshot readers; new fields use explicit unknown values when absent.
- [ ] ContributionAssessment — Run plus selected body digest — Classify old bodies conservatively; do not remove historical visible messages.
- [ ] Delivery checkpoint — Snapshot plus candidate revision — Retain protocol and last committed sequence; store uncommitted fragment separately.
- [ ] Passage commit — Revision plus sequence and record type — Unique identity prevents duplicate commits; host computes canonical digest.
- [ ] Quality assessment — Answer revision plus algorithm version — Old reports remain readable with their original limitations.
- [ ] Request and lifecycle events — Turn, run and physical attempt — Version fields and reason codes are additive; preserve unknown values.

- [ ] B196 · Revision consistency
- [ ] B199 · Legacy conversations
- [ ] B201 · 16 Repository implementation map
### B203: Work | Existing files | Required change

- [ ] W1 — ContextBuilder.kt CanonicalCombinedHistory.kt — Identify the current user by ID and exclude active assistant entries while preserving prior selected finals.
- [ ] W1 — ProviderAttachmentEncoder.kt ProviderAdapters.kt RequestAdmission.kt — Validate serialized roles, tool pairing and capabilities before request measurement and admission.
- [ ] W2 — AgentRunner.kt CombinedContributionQuality.kt — Separate transport from visible answer and synthesis eligibility; preserve exclusion reasons.
- [ ] W3 — CombinedInputSnapshot.kt CombinedSynthesisInputs.kt — Extend immutable input identity and keep repair context consistent.
- [ ] W3 — CombinedSynthesisPrompt.kt — Use short wire references, required topics and versioned passage protocol.
- [ ] W4 — CombinedPassageDelivery.kt OutputLimitRecoverySession.kt — Add structured cursor based continuation and durable record identity.
- [ ] W4 — CombinedDeliverySession.kt — Commit candidate repairs atomically and distinguish delivery from provider completion.
- [ ] W5 — CombinedCoverage.kt CombinedContentCoverage.kt CombinedAnswerPolicy.kt — Separate material requirements, qualifiers, lexical hints and evidence checking.
- [ ] W6 — CombinedResponseBudget.kt InvocationLedger.kt — Budget protocol overhead and emit typed local admission failures.
- [ ] W7 — AgentRunCoordinator.kt CombinedSynthesisGate.kt ChatViewModel.kt — Move readiness to durable per turn state and instrument waiting and preparation.
- [ ] W8 — Diagnostic export and journal writers Existing chat status components — Align schema versions, expose meaningful states, preserve accessible presentation.

- [ ] B204 · Proposed focused additions
- [ ] B206 · 17 Development phases and release gates
- [ ] B207 · Phase 1 Request integrity and result classification
- [ ] B209 · Phase 2 Durable passage delivery
- [ ] B211 · Phase 3 Quality and context consistent repair
- [ ] B213 · Phase 4 Efficient planning and orchestration
- [ ] B215 · Phase 5 UI integration and compatibility release
- [ ] B217 · Delivery approach
- [ ] B220 · 18 Acceptance test matrix
### B222: Test | Fixture or fault | Pass condition

- [ ] T01 — Current pending assistant notice across provider serializers — Current user appears once and last for INITIAL; no active assistant or app status enters the payload.
- [ ] T02 — Retry or regenerate the same user turn — Old active answer excluded; selected prior delivered history retained.
- [ ] T03 — User deliberately quotes internal status text — Quoted user content survives; typed application status remains excluded.
- [ ] T04 — Attachment only prompt and valid tool continuation — Final user input and required tool call/result roles remain valid.
- [ ] T05 — Unsupported prefill capability — Local typed rejection; zero network dispatch and no unchanged retry.
- [ ] T06 — France contributor set from export — Exactly two useful history drafts; internal and prompt echoes excluded with reasons.
- [ ] T07 — Reasoning only and empty completed responses — No successful visible answer claim; explicit recovery or failure outcome.
- [ ] T08 — Valid short greeting and requested repetition — Both remain eligible; no generic minimum length or echo false positive.
- [ ] T09 — Portugal cutoff inside the revolution paragraph — The interrupted section returns once, before later chronology, with its required meanings preserved.
- [ ] T10 — Cuts through Unicode escapes quotes and record delimiters — Decoder keeps valid text, framing and attribution; no silent record loss.
- [ ] T11 — Repeated IDs identical or changed payloads — Identical replay is idempotent; changed committed payload is a typed conflict.
- [ ] T12 — Missing sequence malformed record or unfinished end — Delivery remains partial or invalid; complete state is impossible.
- [ ] T13 — Process death immediately around passage commit — Text and cursor recover atomically to one valid committed state.
- [ ] T14 — Two continuations or finalizations race — One owner commits; no duplicate answer or incompatible cursor.
- [ ] T15 — Repair context dependent greeting — Prior France and Portugal requests remain available to repair assessment.
- [ ] T16 — Repair removes already covered required section — Candidate rejected; prior visible revision remains selected.
- [ ] T17 — Limited powers limited rights limited evidence — Restrictions and evidential uncertainty classified separately.
- [ ] T18 — New user revision, late contribution or evidence — Successor snapshot supersedes stale repair; sealed inputs and newer selected revisions remain intact.
- [ ] T19 — Allowance or permission rejection before dispatch — Typed NOT_DISPATCHED reason; no generated answer text or fabricated measured usage.
- [ ] T20 — All contributors fail DNS — Root closes consistently; retries are coordinated and failed history remains inspectable.
- [ ] T21 — Cancellation and permission revocation — No new unauthorized dispatch; committed answer and recovery state remain coherent.
- [ ] T22 — Old V2 and plain text conversations — Read behavior preserved; no silent conversion of an in progress protocol.
- [ ] T23 — Metadata journal without payload capture — Lifecycle and errors retained; availability reason and schema versions are consistent.
- [ ] T24 — Provider reported estimated reserved and unknown usage — Categories remain distinct and aggregation does not double count child attempts.
- [ ] T25 — Reference performance fixture and greeting suite — Dispatch overhead and compaction targets pass; no unnecessary greeting repairs.
- [ ] T26 — Phone widths font scale themes and screen reader — Status and controls remain usable; answer and composer are unobstructed.

- [ ] B223 · Fault injection method
- [ ] B226 · Provider and device verification
- [ ] B228 · 19 Rollout and completion criteria
- [ ] B229 · Controlled rollout
- [ ] B232 · Rollback behavior
- [ ] B235 · Definition of done
- [ ] B242 · 20 Evidence and source references
- [ ] B244 · Diagnostic evidence
- [ ] B247 · Repository sources
- [ ] B257 · Provider and factual references
## GPT Mobile Crawler Design Implementation 2026-10-18

Source SHA-256: `5b310989b99542040579b734fb97eeed12d89627d5fb763592e91789d8cbdfa6`

### B004: Component | Implementation decision

- [ ] Core reading — HTTP extraction first; browser rendering only when it adds usable content.
- [ ] Multiple engines — Adapters share one queue, cache and accounting boundary.
- [ ] Large crawls — Persistent jobs with reconnectable status and paginated results.
- [ ] Research evidence — Versioned content with provenance and explicit completeness.
- [ ] Deployment — Computer hosted MCP companion with optional browser and document components.

- [ ] B005 · Document baseline
- [ ] B009 · Contents
- [ ] B011 · 01 Scope and requirements
- [ ] B012 · 02 Primary engine selection
- [ ] B013 · 03 Additional technology options
- [ ] B014 · 04 Current repository findings
- [ ] B015 · 05 Architecture and component ownership
- [ ] B016 · 06 Routing and extraction pipeline
- [ ] B017 · 07 MCP operations and transport
- [ ] B018 · 08 Evidence and result contract
- [ ] B019 · 09 Durable jobs and recovery
- [ ] B020 · 10 Scheduling and visible limits
- [ ] B021 · 11 Combined mode work accounting
- [ ] B022 · 12 Research and Examine integration
- [ ] B023 · 13 Data model and retention
- [ ] B024 · 14 Android interface and settings
- [ ] B025 · 15 Packaging and deployment
- [ ] B026 · 16 Errors and diagnostics
- [ ] B027 · 17 Source access and execution boundaries
- [ ] B028 · 18 Repository implementation map
- [ ] B029 · 19 Delivery milestones
- [ ] B030 · 20 Acquisition and app acceptance tests
- [ ] B031 · 21 Recovery and accounting acceptance tests
- [ ] B032 · 22 Performance evaluation and release gates
- [ ] B033 · 23 Operation schema details
- [ ] B034 · 24 Example requests and responses
- [ ] B035 · 25 Transaction and implementation notes
- [ ] B039 · 01 Scope and requirements
### B041: ID | Required behaviour

- [ ] CR01 — Provide one Crawler service entry, profile opt in and a clearly selected execution owner.
- [ ] CR02 — Read ordinary public HTML without a mandatory model, paid crawler key or managed browser service.
- [ ] CR03 — Select among HTTP, browser, alternate extraction and document processing using explicit routing rules.
- [ ] CR04 — Maintain one persistent URL frontier across engines, with deduplication, cancellation and restart recovery.
- [ ] CR05 — Expose small MCP operations for fetch, start, status, results and cancel.
- [ ] CR06 — Integrate with quick search enrichment, both Research workflows, Examine and Combined accounting.
- [ ] CR07 — Return source identity, retrieval receipts, content hashes, completeness and stable chunks.
- [ ] CR08 — Show requested and effective limits, actual work, partial results and specific failure reasons.
- [ ] CR09 — Install heavy dependencies on the server through versioned components and report their readiness.
- [ ] CR10 — Keep extraction quality separate from a study appraisal and preserve the app's archive version model.

- [ ] B042 · Scope boundaries
- [ ] B046 · 02 Primary engine selection
- [ ] B049 · Selection rationale
- [ ] B054 · Licence handling
- [ ] B056 · 03 Additional technology options
- [ ] B062 · 04 Current repository findings
### B064: Area | Observed behaviour | Required change

- [ ] Reader admission — Android and Local Research recognize different hardcoded reader names. [R02] [R03] — Create one reviewed page/document-reader capability binding.
- [ ] Quick enrichment — SearchCrawlStage clamps pages to 20; overall post-search enrichment has a 30-second timeout. [R04] [R05] — Keep bounded fetches here and route deep work through persistent jobs.
- [ ] Combined inspection — CombinedToolWork identifies inspections using another name list. [R13] — Migrate this consumer to the same capability binding.
- [ ] Examine — Uses native academic reading or approved deep_research operations and validates evidence before archival. [R06] — Add a Crawler-backed academic evidence adapter.
- [ ] Gateway jobs — Durable outer jobs protect terminal states but do not represent a crawler URL frontier. [R08] — Add a crawl store with separate identity and continuation rules.
- [ ] Marketplace — Downloads allow 96 KiB per asset, 256 KiB per package and a fixed companion asset set. [R09] — Add a real server installer and versioned component manifests.
- [ ] Plugin identity — Shared service catalog has no dedicated Crawler entry. [R10] — Add identity, profile control, connection mapping and settings.

- [ ] B065 · New Combined requirements
- [ ] B069 · 05 Architecture and component ownership
- [ ] B075 · 06 Routing and extraction pipeline
- [ ] B078 · Validation before success
- [ ] B081 · Link and identity policy
- [ ] B085 · 07 MCP operations and transport
### B087: Operation | Required input | Contract

- [ ] crawler_fetch — url — Read one URL without recursive discovery. Optional query, format, refresh and output limit have defaults. Return evidence or a typed failure within the synchronous deadline.
- [ ] crawler_start — seeds or resumeFromJobId — Validate scope, budgets and engine availability; persist a job before returning its ID and effective plan. A new crawl needs nonempty seeds.
- [ ] crawler_status — jobId — Return persisted state, outcome, stop reason, progress and cumulative accounting. Polling never launches work.
- [ ] crawler_results — jobId — Return a bounded evidence page and opaque cursor. A running job may temporarily have no new records.
- [ ] crawler_cancel — jobId — Persist cancellation, stop admitted work, and report cancelling or cancelled. Repeated calls are safe.

- [ ] B088 · Transport and caller identity
- [ ] B091 · Compatibility and timeouts
- [ ] B095 · 08 Evidence and result contract
### B097: Field group | Required semantics

- [ ] Identity and provenance — schemaVersion, sourceId, documentRevision, requestedUrl, resolvedUrl, canonicalUrl, fetchedAt, HTTP status, MIME type and language when known.
- [ ] Extraction — Selected engine and version, extractor and version, ordered attempt IDs, method such as HTML, rendered HTML, PDF text or OCR, and retrieval status.
- [ ] Coverage — fullText, abstractOnly, section and page coverage when verifiable, missing sections, OCR use, tables or supplementary material limitations. Unknown coverage stays unknown.
- [ ] Content — Immutable contentRef, content hash, chunk IDs, title, text or Markdown, citation metadata and safe links. Full stored content and delivered excerpts have separate lengths.
- [ ] Delivery — outputTruncated, continuation cursor, content version and token estimate method. A token estimate never silently becomes a character count.
- [ ] Accounting — Receipt ID, monotonic sequence, request and byte totals, grant identity and finality. Status and results may carry the same receipt without double charging.

- [ ] B098 · Stable content and citations
- [ ] B101 · Pagination
- [ ] B103 · 09 Durable jobs and recovery
### B105: State or transition | Required behavior

- [ ] queued to running — A worker atomically claims an eligible frontier item and reserves its allowed work. Persist lease owner, expiry, attempt ID and generation.
- [ ] running to completed — No active claims remain and a declared stopping condition is met. Record outcome complete, partial or empty and a precise stop reason.
- [ ] queued or running to cancelling — Persist the cancellation epoch before signalling workers. Reject new claims and late commits from earlier epochs.
- [ ] cancelling to cancelled — All claims are stopped or safely fenced; retain committed evidence and final known accounting. Unknown in flight work remains explicitly reserved.
- [ ] running to failed — A fatal service or policy error prevents useful continuation. Preserve partial results and the actionable error.
- [ ] terminal job to linked new job — Resume uses start with resumeFromJobId. Reuse allowed completed evidence and enqueue eligible unfinished items under a fresh grant. The original remains terminal.

- [ ] B106 · Idempotency and crash recovery
- [ ] B109 · Cancellation and reconnects
- [ ] B111 · 10 Scheduling and visible limits
- [ ] B114 · Initial tunable configuration
- [ ] B117 · Honest progress
- [ ] B120 · 11 Combined mode work accounting
- [ ] B122 · Budget grant and receipt protocol
- [ ] B127 · Browser accounting must be enforceable
- [ ] B130 · Repository integration
- [ ] B132 · 12 Research and Examine integration
### B134: Consumer | Required integration

- [ ] Quick search enrichment — Use crawler_fetch for selected result URLs within the existing quick time and page budget. Preserve SearchCrawlStage cache and deduplication semantics; distinguish acquisition from a cached result. [R04]
- [ ] Explicit deep research — Use crawler_start, poll and consume bounded result pages. Existing search providers can supply seeds; Crawler owns the admitted link frontier for the job.
- [ ] Local profile — Use the same reviewed schemas and defaults. The local model receives bounded excerpts and continuation choices without a large vendor tool surface. [R03]
- [ ] Examine — Import a chosen document revision into the existing evidence and source version path before review. Enforce the current profile and server permissions and existing appraisal workflow. [R06]
- [ ] Research archive — Upsert source revisions and retrieval receipts using stable IDs. Group related URLs by validated DOI or existing study identity; do not create duplicate papers from mirrors.
- [ ] Combined mode — Register accepted child work, preserve the parent budget and cancellation boundary, and reconcile physical acquisition receipts. [R14] [R16]

- [ ] B135 · Separate acquisition from appraisal
- [ ] B138 · Completion and user control
- [ ] B140 · 13 Data model and retention
### B142: Entity | Keys and invariants

- [ ] jobs — job_id; principal; parent_job_id; accepted run and grant; request fingerprint; effective settings; state; outcome; generation; timestamps. Terminal state is immutable.
- [ ] idempotency — Unique principal plus idempotency_key, linked to request fingerprint and job_id. Insertion and job creation share one transaction.
- [ ] frontier — Unique job_id plus scoped URL identity; depth; priority; state; lease owner and expiry; generation. A claim requires an eligible state and valid grant.
- [ ] attempts and receipts — Attempt ID; URL; engine; request IDs; stage; timestamps; error; physical request and byte counters. Receipt identity is unique and sequence increases.
- [ ] documents and chunks — Immutable document revision and content hash; artifact reference; extraction version; stable chunk offsets. Publication checks job and deletion generations.
- [ ] events and grants — Monotonic event sequence; receipt and import event IDs; accepted policy and lease. Events permit replay without duplicate accounting or archive import.

- [ ] B143 · Cache and content ownership
- [ ] B146 · Deletion and privacy behavior
- [ ] B149 · 14 Android interface and settings
### B151: Surface | Behavior

- [ ] Crawl — URL or seed list; Quick, Smart or Deep mode; scope; desired sources; visible limits. Smart is the proposed default. Topic requests use existing search discovery to create seeds.
- [ ] Jobs — Durable status, source counts, active phase, effective limits, stop reason and partial results. Cancel and resume remain available when valid; reconnect restores the same job.
- [ ] Results — Title, URL, fetched time, retrieval method and coverage, with Open source, Cite and Examine actions. Appraisal conclusions appear only after the established review path runs.
- [ ] Settings — Server connection and health, profile permission, installed engines, retention, concurrency and advanced routing overrides. Engine selection is advanced configuration.
- [ ] Diagnostics — Distinguish offline service, expired authentication, missing browser, missing optional PDF worker and exhausted grant. Provide one relevant recovery action for each.

- [ ] B152 · Interaction details
- [ ] B155 · State and permissions
- [ ] B158 · 15 Packaging and deployment
### B160: Install component | Contents and behavior

- [ ] Core — MCP façade, coordinator, SQLite, HTTP reader, Trafilatura and Crawl4AI integration. Ordinary extraction does not require a paid API or language model key.
- [ ] Browser — Pinned Playwright compatible browser and system dependencies; a separately verified health stage. Reuse browser processes with isolated contexts.
- [ ] Alternative reader — Scrapling adapter installed as an optional extra. Its versioned API remains behind the same evidence and accounting contracts.
- [ ] Documents — Reuse available pdftotext first; optional Docling environment and locally stored OCR or layout models. Track model licenses separately from MIT code. [R07] [E09]
- [ ] Mobile catalog entry — Small metadata, setup instructions, connection discovery and health state. Mark runtime as COMPANION through the correct catalog path. [R18]

- [ ] B161 · Repository packaging constraints
- [ ] B163 · Supported deployment path
- [ ] B167 · 16 Errors and diagnostics
### B169: Code or condition | User message and behavior

- [ ] PERMISSION_DENIED or AUTH_FAILED — Explain which connection or profile permission must be restored. Do not silently use another profile or unauthenticated route.
- [ ] SERVER_UNREACHABLE — Keep the known job ID and last confirmed state. Reconnect and reconcile before offering to restart.
- [ ] ENGINE_UNAVAILABLE — Show the missing component and allowed fallback. A job can continue within policy using installed engines; record reduced capability.
- [ ] LOGIN_REQUIRED or ROBOTS_DISALLOWED — Show the source access condition. Do not call it a successful article or bypass it through another engine.
- [ ] NETWORK_TIMEOUT or PARSE_FAILED — Return attempt details and any partial evidence. Retry only within the shared grant and backoff policy.
- [ ] BUDGET_EXHAUSTED — Show the actual request, byte, page or deadline constraint and retained sources. Resume requires a new accepted allowance.
- [ ] ACCOUNTING_ UNAVAILABLE — Keep uncertain reservations and reconcile the existing job. Do not duplicate acquisition through fallback.
- [ ] STORAGE_FULL or RESULTS_EXPIRED — Stop new publication safely or explain the expired artifact. Do not return an empty success or corrupt evidence reference.
- [ ] SCHEMA_INCOMPATIBLE or IDEMPOTENCY_CONFLICT — Reject the request before new work and show the required version or conflicting start parameters.

- [ ] B170 · Operational visibility
- [ ] B173 · Quality signals
- [ ] B175 · 17 Source access and execution boundaries
- [ ] B177 · Navigation scope and network access
- [ ] B182 · Worker and credential boundaries
- [ ] B185 · MCP connection
- [ ] B187 · 18 Repository implementation map
### B189: Area and target | Change and ownership

- [ ] New mcp/crawler/crawler_server/ — server.py exposes the façade; contracts.py validates schemas; coordinator.py owns lifecycle; store.py owns transactions; scheduler.py and accounting.py enforce grants.
- [ ] New mcp/crawler/crawler_server/ adapters/ — http_reader.py, crawl4ai_reader.py, scrapling_reader.py, pdf_reader.py and docling_reader.py implement single item reads through a common network boundary.
- [ ] New mcp/crawler/ packaging and tests — pyproject.toml, pinned dependency groups, installer scripts, migration fixtures, local corpus and contract or integration tests. Separate optional heavy dependencies.
- [ ] gateway/v14/tools.py and optional crawler.py — Bind one approved connection and canonical tool names; propagate trusted context, deadlines and receipts. Keep extraction out of the gateway. [R12]
- [ ] data/agent/tool/ workflows and stages — Add a shared ReaderCapabilityRegistry; migrate AndroidResearchWorkflow, LocalResearchWorkflow, SearchCrawlStage and MultiEngineSearchTool. [R02] [R03] [R04] [R05]
- [ ] data/agent/ Combined components — Update CombinedToolWork inspection classification, accepted child work, reservation and receipt handling. Respect AgentContracts semantics. [R13] [R15] [R16] [R17]
- [ ] data/research/feature/ coordinator — Add a reviewed Crawler source import path in ArticleExaminationCoordinator, preserving evidence hashes, source versions and automatic versus user initiated context. [R06]
- [ ] data/model and data/catalog — Add Crawler service and plugin identity; use COMPANION runtime; provide connection, capabilities and setup metadata. [R10] [R18]
- [ ] New data/crawler/ and ui/crawler/ — CrawlerClient, models and repository; lifecycle aware screens for Crawl, Jobs and Settings. Reuse existing persistence, navigation and theme components.
- [ ] data/marketplace/MarketplacePackageStore.kt — Introduce the supported companion setup route with pinned metadata and tests. Preserve the existing small asset policy for other package types. [R09]

- [ ] B191 · 19 Delivery milestones
### B193: Milestone | Deliverable and exit condition

- [ ] M0 Contracts and admission — Freeze schema version 1, source version mapping, error and accounting definitions. Add service identity and shared reader capability tests across all three admission or inspection consumers.
- [ ] M1 One useful local read — Install the core companion; fetch static HTML with Trafilatura and render a JavaScript fixture with Crawl4AI. The selected profile calls the discovered tool and receives a traceable bounded source without paid keys.
- [ ] M2 Durable multi engine jobs — Add frontier, idempotent start, shared routing, Scrapling extra, results pagination, cancellation and recovery. Every engine obeys one physical request and byte grant.
- [ ] M3 Research and document integration — Import source revisions into Research and Examine, group archive sources correctly, support basic PDF extraction and optional Docling, and preserve partial or abstract only limitations.
- [ ] M4 Product and deployment — Complete Crawler screens, companion installation metadata, health diagnostics, migration and rollback handling, permission controls and release corpus evaluation.

- [ ] B194 · Parallel work after contracts
- [ ] B196 · Rollout
- [ ] B198 · Definition of done
- [ ] B200 · 20 Acquisition and app acceptance tests
- [ ] B204 · 21 Recovery and accounting acceptance tests
- [ ] B207 · 22 Performance evaluation and release gates
- [ ] B209 · Evaluation corpus
- [ ] B212 · Proposed initial service targets
- [ ] B214 · Release gates
- [ ] B218 · 23 Operation schema details
- [ ] B221 · Defaults and effective plan
- [ ] B224 · Success and error envelopes
- [ ] B226 · 24 Example requests and responses
- [ ] B228 · Start a bounded deep crawl
- [ ] B239 · Acknowledgement and effective work
- [ ] B254 · Partial completion remains useful
- [ ] B267 · 25 Transaction and implementation notes
- [ ] B268 · Admission and publication
- [ ] B275 · Implementation decisions to close in M0
### B276: Decision | Required output

- [ ] Parent byte counter compatibility — Document whether downloadedBytes matches transferred body bytes before decompression and how the gateway’s current allowance is reconciled. Maintain decoded and retained counters separately.
- [ ] Network accounting implementation — Choose an enforceable egress boundary that covers the selected browser and HTTP engines. Prove resource, redirect and stream cancellation behavior with fixtures.
- [ ] Source version conversion — Specify the exact content representation and hash used by the existing Examine path and the artifact retained for later verification.
- [ ] Service registration and schemas — Confirm canonical upstream operation names, gateway prefix ownership, trusted context propagation and the supported MCP protocol version.
- [ ] Deployment and retention defaults — Record the actual server OS, dependency locks, data directory, retention values and measured concurrency defaults before enabling the feature.

- [ ] B278 · Repository references
- [ ] B298 · Repository references continued
- [ ] B317 · Technology references
- [ ] B341 · Technology references continued
## GPT Mobile Main Menu and Home Tab Design Implementation 2026-10-18

Source SHA-256: `513d6245a6662a2cc705b773ac6e02c34754c8bde5c38ce1b7da326e71e2ceba`

- [ ] B004 · 18 October 2026
- [ ] B005 · 1 Purpose and delivery contract
### B009: ID | Required result | Acceptance value

- [ ] M1 — Generation ring surrounds the existing conversation icon — Concentric layers; unchanged natural 24 dp icon
- [ ] M2 — Unread badge sits at the icon stack bottom right — Glass material alpha 0.80; crisp foreground
- [ ] M3 — Unread subject becomes very bold and softly pulses — Title alpha 1.00 to 0.80 and back
- [ ] M4 — All main-menu AI profile names are 50 percent transparent — One effective text alpha of 0.50
- [ ] M5 — Folder chips have a thin colored outline and transparent fill — Folder override or theme outline; theme-colored label
- [ ] M6 — Shared soft darkening covers the lower 30 percent — Transparent at 70 percent from top; darkest at bottom
- [ ] H1 — Home reuses saved provider management access — One persisted secret reference owned by the provider
- [ ] H2 — Wallet refresh follows actual OpenRouter responses — Cached display; no navigation, timer, or enable refresh
- [ ] H3 — One AI Profiles switch opens a multiselect checklist — Selections remain within one Home category
- [ ] H4 — Plugins and Tools shows selected subjects and real status — Checklist plus green, red, or explicit unknown state

- [ ] B011 · 2 Findings in the current repository
- [ ] B018 · 3 Main menu conversation indicators
- [ ] B019 · 3 1 Fixed icon geometry
- [ ] B025 · 3 2 Generation and unread state
- [ ] B032 · 3 3 Glass unread badge and title pulse
- [ ] B038 · 4 Profile labels and folder presentation
- [ ] B039 · 4 1 Profile name opacity
- [ ] B043 · 4 2 Folder outline rules
- [ ] B048 · 4 3 Folder data migration
- [ ] B053 · 5 Shared lower screen gradient
- [ ] B066 · 6 Home Tab categories and selection flows
### B068: Category | Configuration control | Information bubble behavior

- [ ] Wallet — Select a saved OpenRouter connection; hide amount option — One cached balance for the selected account
- [ ] AI Profiles — One switch and Choose profiles checklist — Selected profile status preview inside one category
- [ ] Plugins and Tools — One switch and Choose plugins and tools checklist — Selected connection/tool status preview inside one category
- [ ] Other existing categories — Existing category controls — Continue to count as one category each

- [ ] B073 · 6 1 Layout rules
- [ ] B076 · 7 Saved OpenRouter management access
- [ ] B077 · 7 1 One credential owner
- [ ] B083 · 7 2 Scope and credential lifecycle
- [ ] B088 · 7 3 Wallet display contract
- [ ] B092 · 8 Wallet refresh from response events
- [ ] B093 · 8 1 Qualifying event boundary
- [ ] B100 · 8 2 Strict refresh policy
- [ ] B108 · 8 3 Identity and concurrency
- [ ] B129 · 8 4 Error states
- [ ] B133 · 9 Profile and plugin health
- [ ] B134 · 9 1 Evidence for profile status
- [ ] B138 · 9 2 Plugin and tool status
- [ ] B145 · 10 Persistence and migration
### B147: Record | Required fields | Storage rule

- [ ] Home configuration v2 — Category IDs, selected profile UIDs, selected typed tool subjects, selected wallet connection, hide amount — Portable display preferences; max five category IDs
- [ ] Management access binding — Connection UID, management secret reference, binding revision, credential revision, endpoint revision — Secret bytes remain in SecretVault
- [ ] Wallet cache — Scoped identity, total credits, total usage, remaining decimal, fetched time, last attempted event, error metadata — Local cache; no raw credentials
- [ ] Generation refresh record — Stable event identity, account binding, acceptance/claim/result state, timestamp — Local durable deduplication; never a restart trigger
- [ ] Folder color mode — Existing color plus CUSTOM or THEME — Room and backup schema migration

- [ ] B149 · 10 1 Home preference migration
- [ ] B157 · 10 2 Backup and account data
- [ ] B160 · 11 File implementation map
### B162: Existing area | Implementation work

- [ ] presentation/ui/home/HomeScreen.kt — Replace leading Row with status stack; add badge and subject pulse; preserve selection behavior
- [ ] presentation/ui/home/HomePresentation.kt — Shared 0.50 profile alpha; retire lower list fade as page darkening
- [ ] presentation/ui/home/HomeViewModel.kt — Map active runs and unread publications to the same visible root
- [ ] presentation/ui/home/ConversationFolders.kt and data/chat folder files — Outline-only material, theme color option, persistence migration
- [ ] presentation/common glass and navigation files — Local 0.80 badge material and one shared 30 percent background gradient
- [ ] data/model/SettingsHomeConfiguration.kt — Versioned category-only selection and nested checklists
- [ ] presentation/ui/setting Home screen, summary, and view model — Grouped selectors, adaptive cards, repository-observed wallet and health
- [ ] presentation/ui/setting/SettingScreen.kt — Remove destructive session cleanup for persisted wallet data
- [ ] presentation/viewmodel/OpenRouterSettingsViewModel.kt — Provider-owned saved management access and shared cache reads
- [ ] data/security/SecretVault.kt — Reuse existing vault API; add no parallel secret store
- [ ] data/openrouter/account/OpenRouterReceipts.kt and provider/network adapters — Stable finalized accounting event after actual transport response
- [ ] data/worker/OpenRouterBatchWorker.kt — Fresh remote response event; exclude cache-hit branch
- [ ] data/agent/tool and data/telemetry/tool — Shared current health projection for selected subjects

- [ ] B165 · 12 Delivery sequence and validation
- [ ] B166 · 12 1 Implementation sequence
### B167: Phase | Deliverables | Completion condition

- [ ] 1 — Shared state and migrations. Management access binding; Home schema; folder color mode; scoped wallet/event storage — Upgrade and account-isolation tests pass
- [ ] 2 — Wallet integration. Receipt finalization, fresh batch path, persistent cache, provider settings ownership — Every eligible response path obeys the strict request policy
- [ ] 3 — Home controls and health. Profile/tool checklists, category layouts, status projection — Selections persist without changing execution permissions
- [ ] 4 — Main menu visuals. Status stack, glass badge, title pulse, label alpha, folder outlines, shared gradient — Visual and accessibility cases pass on target device
- [ ] 5 — Integration review. Backup/restore, lifecycle, concurrent completions, performance and diagnostics — No unresolved regression in the acceptance matrix

- [ ] B169 · 12 2 Functional acceptance matrix
### B170: Test | Required observation

- [ ] Saved management access — Save in provider settings once; Home uses it after reopen and process restart without another key field
- [ ] No automatic navigation refresh — Open, close, foreground, recompose, switch Home categories, enable Wallet, and restore; credits request count remains zero
- [ ] One real OpenRouter completion — Finalized response causes one account request attempt when enabled and correctly bound
- [ ] Streaming terminal frames — Repeated finish_reason and usage-only final frame produce one accounting event
- [ ] Independent response attempts — Concurrent contributors, retries, and tool-only responses each retain their own identity and serialized refresh attempt
- [ ] Other provider and cache paths — Local response, other-provider response, and batch cache replay cause no OpenRouter credits read
- [ ] Refresh failure — Prior balance and successful timestamp remain; no timer, reconnect, or screen retry
- [ ] Process death at each dispatch boundary — Restart never replays a historical wallet job; next new response reconciles
- [ ] Disable while work is pending — Pending requests are discarded; rapid off/on changes the activation revision and never releases old events
- [ ] Account switch or key rotation — Old results cannot populate another account; replacing an inference key also requires a valid management association
- [ ] Home category migration — Legacy individual profiles become one category; order and explicit selections survive
- [ ] Checklist permissions — Selecting or clearing profiles/tools changes display only; execution settings remain byte-equivalent
- [ ] Plugin status truth — Configured-only and stale connections do not show green; child tool disappearance is represented
- [ ] Unread publication race — Acknowledging an older revision does not clear a newer publication; contributors do not create unread
- [ ] Folder migration and restore — Existing outlines keep their stored colors; new theme-mode folders follow theme changes

- [ ] B171 · 12 3 Visual and performance validation
- [ ] B177 · 12 4 Tests and diagnostics
- [ ] B181 · 13 Source register
## GPT Mobile Research Repair Design Implementation 2026-10-18

Source SHA-256: `1b8d55db31958b369db91d718d7a6375fbfcbf9f31afe6401eb28935621ad44e`

- [ ] B004 · 18 October 2026
- [ ] B005 · 1 Decision and implementation scope
- [ ] B010 · 2 What the incident proves
- [ ] B011 · Source acquisition and model outcomes
- [ ] B016 · The final handoff
- [ ] B019 · Configuration and capture distinctions
- [ ] B023 · 3 Repair priorities
### B025: ID | Priority | Problem | Required outcome

- [ ] R01 — P0 — Full profile equality rejects accepted overrides — Research dispatch uses valid registered authority and accepted settings
- [ ] R02 — P0 — Latest Research snapshot replaces the turn corpus — All eligible readable evidence survives contributor failure
- [ ] R03 — P0 — Service lifetime ends before synthesis — Root execution covers successors and supports lawful resume
- [ ] R04 — P0 — Retry clears visible content before success — Failed finalization leaves a useful visible state
- [ ] R05 — P0 — Citation and report contracts conflict — One trusted contract governs generation and validation
- [ ] R06 — P0 — Initial packet budget excludes all readable sources — Useful source excerpts take priority over leads and metadata
- [ ] R07 — P0 — Explicit Research is optional Combined work — One funded Research plan reflects the accepted source goal
- [ ] R08 — P0 — Research roles share verification capacity — Planning extraction review and writing retain separate assignments
- [ ] R09 — P1 — Duplicate collection and generic failure labels — Shared acquisition and accurate engine and budget outcomes
- [ ] R10 — P1 — Process completion is presented as Research completion — Acquisition analysis review and delivery have separate states
- [ ] R11 — P1 — Examine readiness and archive progress are difficult to diagnose — Persistent jobs and visible version bound results
- [ ] R12 — P2 — Source quality and scale need stronger controls — Study aware ranking resumable crawling and efficient collections

- [ ] B026 · 4 Repair profile admission
- [ ] B027 · Confirmed cause
- [ ] B030 · Implementation
- [ ] B035 · 5 Preserve the shared turn corpus
- [ ] B036 · Confirmed cause
- [ ] B038 · Implementation
- [ ] B044 · 6 Make evidence packets useful and inspectable
- [ ] B045 · Confirmed initial packet defect
- [ ] B047 · Implementation
- [ ] B053 · 7 Unify citation and report delivery
- [ ] B055 · One trusted contract
- [ ] B059 · Validation and recovery
- [ ] B065 · 8 Keep the turn alive and the result visible
- [ ] B066 · Root lifecycle ownership
- [ ] B071 · Transactional visible delivery
- [ ] B076 · 9 Fund the accepted Research plan
- [ ] B078 · Accepted settings contract
- [ ] B084 · Accurate limit feedback
- [ ] B086 · 10 Repair search and reader behavior
### B088: Outcome | Scheduler behavior | UI and diagnostic meaning

- [ ] HTTP 401 or 403 — Stop that credential route until corrected — Connection needs attention
- [ ] HTTP 429 — Respect retry timing and backoff — Rate limited with eligible retry
- [ ] Budget denial — Do not call the provider — Show the binding budget and origin
- [ ] Duplicate in flight — Join the authorized result — Shared request
- [ ] Successful empty response — Preserve zero result outcome — No matching content
- [ ] Partial readable response — Retain usable passages and missing sections — Partial source coverage
- [ ] Timeout or network loss — Checkpoint and apply bounded retry — Interrupted retrieval

- [ ] B092 · 11 Improve study cards Examine and archive
- [ ] B094 · Clean Research interface
- [ ] B098 · Examine readiness and workflow
- [ ] B102 · Scores and version integrity
- [ ] B106 · 12 Data and component contracts
### B108: Contract | Identity and required content

- [ ] AcceptedResearchIntent — Root turn ID, settings revision, scope, depth, source goal, origin per value, limits and authority partition
- [ ] ResearchStageAssignment — Root ID, work item ID, role, profile authority, resource reservation and checkpoint
- [ ] TurnEvidenceManifest — Corpus revision, eligible work and source version IDs, coverage, aliases and provenance
- [ ] EvidencePacketReceipt — Physical attempt ID, corpus digest, contract version, included and omitted evidence, size and output reserve
- [ ] ResearchStopReason — Origin, scope, dimension, owner, binding limit, denied operation and next action
- [ ] DeliveryArtifact — Role, generation ID, source manifest, validation outcome, visible body and fallback classification
- [ ] ResearchTransition — Root revision, old and new phase, successor obligations, ownership fence and outbox state
- [ ] ExaminationReceipt — Job ID, source version, reviewed summary hash, rubric and profile, outcome and archive transaction

- [ ] B111 · 13 File level implementation plan
### B113: Work area | Existing files and symbols | Main change

- [ ] Profile admission — DirectResearchModelFactory and ResearchModelCoordinator — Registered authority plus request overlay and typed readiness
- [ ] Authority reuse — ConfiguredDispatchAuthority — Extend existing validation to Research stages
- [ ] Turn admission — ChatRepositoryImpl.prepareCombinedTurn — Include accepted Research intent before allocation
- [ ] Work funding — CombinedPolicyResolver and CombinedWorkInventory — Required Research stages and realistic resource allocations
- [ ] Work ownership — CombinedWorkPlan and delegateToProfile — Preserve stage work item IDs and distinct owner roles
- [ ] Acquisition — AndroidResearchWorkflow and SearchCrawlStage — Single scheduler ownership and typed outcomes
- [ ] Evidence collection — ResearchSessionStore and ResearchCorpus — Authority aware union across participant sessions
- [ ] Final synthesis — ChatViewModel.startCombinedSynthesis — Move orchestration to root coordinator and consume corpus manifest
- [ ] Packet composition — ResearchDeliveryPacket and ResearchDeliverySession — Incremental excerpt fitting and final packet receipts
- [ ] Prompt construction — ChatRepositoryImpl and LiveToolContext — One proposed CitationPolicy and output contract
- [ ] Service lifecycle — AgentRunForegroundService and AgentRunCoordinator — Root lease covering pending successors
- [ ] Visible delivery — AgentPersistenceDao and AgentRunCoordinator — Preserve displayed artifact until replacement commit
- [ ] Card binding — CitationBindingResolver and ResearchCitationCards — Corpus driven cards and version correct assessment
- [ ] Examination — ArticleExaminationCoordinator — Visible readiness, review policy and durable receipts
- [ ] Archive — ResearchArchiveRepository and ResearchDatabase — Idempotent review commit and verified migrations
- [ ] Export — Existing conversation exporter and run journal — Effective contract, final packet and archive evidence

- [ ] B114 · 14 Diagnostics and failure classification
- [ ] B119 · 15 Regression and acceptance gates
### B121: Test | Scenario | Required assertion

- [ ] T01 — Three exported temperatures plus accepted override — PLAN and EXTRACT dispatch with the accepted temperature
- [ ] T02 — Legitimate chat model override — Request model changes without false profile revocation
- [ ] T03 — Endpoint credential or permission changes — Dispatch blocks with the exact authority reason
- [ ] T04 — Disabled deleted or duplicate profile — No unauthorized request occurs
- [ ] T05 — Research on depth 20 and Combined effort 50 — Goal 24 appears in funded accepted Research work
- [ ] T06 — Research off — Ordinary Combined optional work remains valid
- [ ] T07 — Hard user budget below requested goal — Partial admitted plan identifies the actual limit
- [ ] T08 — Three compatible profiles request the same source — One physical read and shared authorized results
- [ ] T09 — Different account scoped source permissions — Corpus and cached bodies remain isolated
- [ ] T10 — Later failed read after a readable version — Retained readable evidence is not downgraded
- [ ] T11 — Luna writer fails and newer Xiaomi corpus is empty — Eight eligible readable sources survive synthesis freeze
- [ ] T12 — Initial 2,048 token packet with unread leads — At least one fitting supported excerpt precedes lead metadata
- [ ] T13 — Packet smaller than minimum feasible evidence — Typed packet failure and no unsupported writer call
- [ ] T14 — Delivery wrapper changes the packet — Exported receipt matches the actual dispatch composition
- [ ] T15 — Minimal citation display plus Research — One coherent internal citation contract and valid card binding
- [ ] T16 — Unknown source ID invented URL or changed number — Validator rejects with precise repairable issue
- [ ] T17 — Zero readable evidence — Discovery result appears without repeated full report rewrites
- [ ] T18 — Output limit with zero visible bytes — Recoverable result remains visible and evidence persists
- [ ] T19 — Background during last contributor handoff — Root service lease remains until successor is registered
- [ ] T20 — Forced service restart denial — Paused state and lawful Resume with no empty final bubble
- [ ] T21 — Process death between save and callback — One successor and one final commit after recovery
- [ ] T22 — Final writer failure after previous candidate — Candidate or partial artifact remains accessible
- [ ] T23 — Finalization fails or user cancels — Root settles once without double charge or refund of uncertain work
- [ ] T24 — HTTP 401 HTTP 429 empty result and budget denial — Distinct engine remedies and truthful attempt counts
- [ ] T25 — Research review with Combined Delegation suppressed — Explicit reviewer policy still works and is attributed
- [ ] T26 — Missing crawler profile — Choose review profile is visible and no silent job failure
- [ ] T27 — Re examine same version and updated version — No duplicate work record and correct review history
- [ ] T28 — Abstract only unknown domain or retraction — Scores remain limited or suspended as required
- [ ] T29 — New assessment for an older cited source — Original version remains stable and latest review is labeled
- [ ] T30 — Archive backup restore and deletion during job — References survive restore and late jobs respect deletion
- [ ] T31 — Partial collection enters final writing and fails — Recovery preserves evidence without restarting acquisition
- [ ] T32 — Permission revoked during writer recovery — Cached private evidence is not disclosed

- [ ] B124 · 16 Performance and rollout
- [ ] B125 · Proposed performance targets
- [ ] B129 · Delivery sequence
- [ ] B136 · 17 Evidence and source index
- [ ] B138 · Uploaded incident evidence
- [ ] B144 · Repository implementation references
- [ ] B157 · Platform guidance
## GPT Mobile Research Study Cards Design 2026-10-18

Source SHA-256: `eba8efa0f6792531e708acfa7051cb9e8da242360703d7e4aa7f79a38a9def65`

- [ ] B005 · Product requirements
- [ ] B012 · Current implementation baseline
- [ ] B014 · 1  The response experience
- [ ] B016 · Card hierarchy
- [ ] B019 · Collection presentation
- [ ] B022 · Visible updates
- [ ] B024 · 2  Discovery and distinct studies
- [ ] B026 · Three identity levels
- [ ] B029 · Broad search with honest progress
- [ ] B035 · Collection independent of prose
- [ ] B037 · 3  What the score means
- [ ] B040 · A conservative combined value
- [ ] B043 · Eligibility and missing evidence
### B044: Situation | Display and scoring behavior

- [ ] Metadata only — No conclusion inferred from the title. No Reliability or combined score.
- [ ] Abstract read — Abstract-grounded preview. Summary accuracy may be checked against the abstract; Reliability and combined score remain pending.
- [ ] Required full-text evidence reviewed — Score only when all required domains are grounded and the assessed outcome is explicit.
- [ ] Required evidence unavailable or conflicting — Limited review with reasons. Unknown stays unknown; never remove it from the scoring denominator to improve the score.
- [ ] Critical flaw or material summary error — Set the publishable overall score to null. Show Needs correction or Critical concern; exclude it from numeric ranking.
- [ ] Retraction or unresolved integrity notice — Show publication status prominently, suspend the normal overall badge and preserve the previous assessment as history.

- [ ] B045 · 4  The proposed app rubric
- [ ] B047 · Reliability domains
- [ ] B049 · Summary accuracy domains
- [ ] B053 · 5  Examine and the crawler profile
- [ ] B054 · Research settings
- [ ] B058 · Examination pipeline
- [ ] B067 · 6  Review state and failure handling
### B069: State | Card behavior | Recovery

- [ ] Discovered or preview ready — Show available metadata/conclusion; pending assessment. — Examine.
- [ ] Queued — Show selected profile and queue position when known. — Cancel queued work.
- [ ] Fetching or reading — Show meaningful stage and completed work counts. — Pause or cancel; retain checkpoints.
- [ ] Assessing or validating — Keep the previous completed score visible with Review in progress. — Resume from validated evidence.
- [ ] Saving — Do not show Saved before the database transaction commits. — Retry persistence without recrawling.
- [ ] Reviewed — Show current score, component values and review scope. — View review or Re-examine.
- [ ] Limited or blocked — Show why assessment is limited; retain usable source content. — Retry access or choose a working profile.
- [ ] Failed or cancelled — Keep the last completed assessment and clear failure state. — Resume or start an explicit new attempt.

- [ ] B070 · Concrete fixes for the current coordinator
- [ ] B074 · 7  Archive records and live card updates
- [ ] B076 · Versioned records
- [ ] B081 · Two views of the same evidence
- [ ] B084 · Storage guarantees
- [ ] B090 · 8  Repository implementation map
### B092: Component | Required change

- [ ] ResearchCitationCards.kt — Render conclusion, limitation, score, coverage and a direct Examine action. Observe a live assessment projection instead of deriving all state from snapshotJson.
- [ ] CitationBindingResolver.kt and ResearchResponseBlock.kt — Keep exact citation provenance. Add a separate structured response collection that includes uncited discoveries and supports paging.
- [ ] ChatScreen.kt and AssistantChronologicalContent.kt — Populate run-to-study attachments from discovery/session observers. Render the live collection outside the citation-only post-generation path.
- [ ] ResearchFeatureSettings.kt and DeepResearchSettingsScreen.kt — Persist default examination profile UID, resolve chat overrides and add a clearly labeled Research selector.
- [ ] ArticleExaminationCoordinator.kt — Add URL-only retrieval, genuine refresh semantics, whole-outcome aggregation, explicit coverage gates and transactional assessment publication.
- [ ] ResearchArticleDialog.kt and ResearchArticleViewModel.kt — Follow the completed job’s assessed version while retaining the original citation. Observe current review changes.
- [ ] ScientificAppraisalContract.kt — Preserve scientific domain judgments and schema/evidence checks. Add structured summary review without asking the model for an overall score.
- [ ] ResearchOutcomeBuilder.kt — Generate a concise conclusion and limitation across supported study types; retain source references and unknown states.
- [ ] ResearchEntities.kt and ResearchDatabase.kt — Add attachment, conclusion and assessment records plus reactive projections and a real Room migration.
- [ ] ResearchArchiveRepository.kt and archive UI — Upsert the same work, append revisions, expose conclusion/score filters and refresh cards after review.
- [ ] ResearchPortableSnapshot and import/export — Extend schema validation, version migration, reference checks and restore rules for assessment artifacts.
- [ ] New ResearchReviewScoreCalculator — Pure deterministic eligibility, rubric mapping, min-component aggregation, five-point values and critical overrides.

- [ ] B094 · 9  Acceptance tests for evidence and scoring
### B095: ID | Scenario | Required result

- [ ] E01 — A DOI appears through several providers and URL variants. — One work; all provider provenance retained.
- [ ] E02 — Preprint, final article and cohort follow-up are discovered. — Versions/reports linked correctly; no false independent replication count.
- [ ] E03 — Similar titles describe unrelated experiments. — No automatic merge without corroborating identity.
- [ ] E04 — A single article describes two independent studies. — Separate study relationships and outcome scopes remain representable.
- [ ] E05 — The answer cites five of fifty discovered works. — All fifty stay in the response collection; citations retain precise passage bindings.
- [ ] E06 — Discovery produces more than 120 cited occurrences. — Collection remains complete and paged; any citation binding limitation is explicit.
- [ ] E07 — Only metadata or an abstract is accessible. — No invented conclusion from a title and no full Reliability/combined score.
- [ ] E08 — A required method or key supplement is unavailable. — Limited review; unresolved fields remain unknown.
- [ ] E09 — The paper reports a null or noncausal result. — The card cannot assert a benefit or causal proof unsupported by the source.
- [ ] E10 — Reliability is 65 and Summary accuracy is 95. — Overall is 65; numeric labels have no percent sign.
- [ ] E11 — A critical method failure or summary contradiction exists. — Overall is null and excluded from score ranking; concern status is prominent.
- [ ] E12 — The same source is summarized differently in another chat. — Reliability reuse is scoped; summary accuracy is recomputed for the new summary.
- [ ] E13 — A page contains instructions to increase its own score. — Source instructions have no execution authority.
- [ ] E14 — A correction or retraction is discovered. — Current status changes, prior score remains historical and affected conclusions are flagged.

- [ ] B097 · 10  Acceptance tests for workflow and persistence
### B098: ID | Scenario | Required result

- [ ] W01 — Choose a default Crawler AI profile, then restart the app. — Selection persists; manual and automatic Examine use it.
- [ ] W02 — The selected profile is deleted, disabled or offline. — Actionable paused/selection state; no silent provider switch.
- [ ] W03 — The source has only a canonical URL. — Validated URL retrieval can feed the existing extraction path.
- [ ] W04 — Examine is tapped twice or from two cards. — One compatible active job; no duplicate archive record or tool cost.
- [ ] W05 — Re-examine is explicitly requested after completion. — A new review revision is created with refreshed source/status checks.
- [ ] W06 — Process death occurs during reading or appraisal. — Resume from durable checkpoints with the same job provenance.
- [ ] W07 — A new examination fails or is cancelled. — The last completed assessment stays available.
- [ ] W08 — An older job finishes after a newer job. — The older result cannot replace the newer selected assessment.
- [ ] W09 — Archive persistence fails after successful review. — No false Saved status; persistence can retry without another crawl.
- [ ] W10 — Examination completes while card and archive are open. — Both update from one committed assessment without refresh/reopen.
- [ ] W11 — A schema 2 backup is restored. — Existing works and citations survive; new review fields are Unscored.
- [ ] W12 — A new backup with history and interrupted jobs is restored. — Evidence references and versions survive; jobs resume only under current policy.
- [ ] W13 — Archive is disabled or capacity is exhausted. — State is explicit; no claim of durable saving and no silent loss.
- [ ] W14 — Long titles, large text and reduced motion are enabled. — Cards and controls remain readable; progress is understandable without animation.

- [ ] B100 · 11  Delivery sequence and measurement
- [ ] B106 · Useful diagnostics
- [ ] B109 · Refresh policy
- [ ] B111 · Release definition
- [ ] B113 · 12  Source references
## GPT Mobile Universal Marketplace Design Development 2026-10-18

Source SHA-256: `8c65df2ab094237ee3891887aa25c031dd0eac105c12eb723118c9c9c1412ef7`

- [ ] B002 · Design & Development Specification
- [ ] B005 · Executive decision
- [ ] B009 · What this specification delivers
- [ ] B012 · Reading guide
- [ ] B014 · 1. Product scope and free-first rules
- [ ] B015 · Required product outcome
### B018: Requirement | Implementation rule

- [ ] No mandatory paid service — The default installation contains no paid API dependency, metered proxy or subscription requirement.
- [ ] Useful without credentials — Search links, manual filters, saved searches and user-created bookmarks work immediately.
- [ ] Useful without AI — The form, search coordinator, sorting and cards are deterministic. AI is optional.
- [ ] Useful without a computer — Native Android behavior remains available when the gateway is absent or offline.
- [ ] One integrated plugin — Source toggles live inside one plugin; do not install four separate user-facing plugins.
- [ ] Honest automatic coverage — Provider access mode and result provenance are present in both the UI and tool output.
- [ ] No surprise AI charges — Remote analysis requires the user's existing profile choice and applicable cost controls.

- [ ] B019 · MVP and later capabilities
- [ ] B024 · 2. Repository findings and integration constraints
- [ ] B028 · Changes that cannot be “just add a provider name”
- [ ] B034 · 3. Marketplace access and open-source strategy
### B035: Source | Default shipping behavior | Automatic retrieval gate

- [ ] Facebook Marketplace — User-opened search; no account secrets collected by GPT Mobile. — Require a documented, permitted integration before enabling retrieval. No reliance on private GraphQL endpoints. [S1]
- [ ] eBay — User-opened search when no approved API route is configured. — Official Browse API, approved production use and valid credentials. Sandbox is development data, not live inventory. [S2]
- [ ] Craigslist — User-opened regional search. — Require authorization and a reviewed integration; do not ship unlicensed automated collection. [S4]
- [ ] Kijiji — User-opened Canadian search. — No approved retrieval route was established in this review. Keep retrieval disabled pending provider-specific permission and technical review.
- [ ] SearXNG — Optional indexed discovery through a configured instance. — Instance must expose JSON search and have an acceptable engine configuration. Label output as discovery, not current listings. [S5]
- [ ] Amazon / other sites — Not part of the initial source set. — Separate adapter review, appropriate data rights and compatibility tests before inclusion.

- [ ] B038 · GitHub projects: use selectively
- [ ] B042 · 4. Target architecture
- [ ] B044 · Native layer
- [ ] B047 · Optional gateway layer
- [ ] B050 · Packaging and update boundary
- [ ] B053 · 5. Plugin lifecycle and tool exposure
- [ ] B054 · Proposed stable identifiers
- [ ] B057 · Required permission chain
- [ ] B060 · Configuration model
- [ ] B063 · Disable and uninstall behavior
- [ ] B065 · 6. Search request contract
- [ ] B068 · Validation and defaulting
- [ ] B073 · Pagination and query identity
- [ ] B076 · 7. Response contract and provenance
- [ ] B081 · Required records
- [ ] B084 · State semantics
- [ ] B087 · 8. Coordinator execution and reliability
- [ ] B088 · Execution sequence
### B091: Proposed initial control | Rule; tune using measured tests

- [ ] Concurrent providers — At most three active network adapters per search.
- [ ] Search budget — Default 15-second foreground deadline for initial retrieval; configurable within provider policy.
- [ ] Retry — At most one retry for a transient, idempotent read within the remaining budget.
- [ ] Rate limiting — Honor Retry-After; do not retry authorization failures, challenges or parse changes automatically.
- [ ] Detail fetching — User-opened details or a small explicit comparison set; never every returned item automatically.
- [ ] Result volume — Default 30 UI items; bounded per-provider pages; maximum 100 for the proposed v1 contract.
- [ ] Payload size — Enforce a configurable decoded response limit before parsing and an image-specific limit before decoding.

- [ ] B092 · Readiness and filter feedback
- [ ] B095 · Single-flight and quota accounting
- [ ] B098 · 9. Prices, location, matching and ranking
- [ ] B099 · Price normalization
- [ ] B103 · Geographic behavior
- [ ] B106 · Duplicate handling
- [ ] B108 · Ranking
- [ ] B111 · 10. Native interface and navigation
- [ ] B112 · Main workspace
- [ ] B116 · Cards and details
- [ ] B120 · Settings: four groups
- [ ] B123 · 11. AI, local tools and combined-mode integration
- [ ] B124 · AI is an enhancement, not the search engine
- [ ] B127 · Tool exposure and local runtime requirements
- [ ] B130 · Bounded evidence handoff
- [ ] B132 · Combined mode
- [ ] B136 · 12. Security, privacy and provider boundaries
- [ ] B137 · Credentials and transport
- [ ] B141 · URL and content handling
- [ ] B145 · User data and consent
- [ ] B149 · 13. Persistence, caching and saved searches
- [ ] B151 · Database integration
- [ ] B154 · Caching rules
- [ ] B157 · Monitoring is a later, separate opt-in
- [ ] B160 · 14. Diagnostics and performance requirements
- [ ] B161 · Instrument the real execution path
- [ ] B166 · Debug export
- [ ] B169 · 15. Acceptance tests and release evidence
### B171: ID | Scenario | Required result

- [ ] T01 — Fresh install, no keys or gateway — Native workspace opens and prepares four source actions; no paid request or AI call occurs.
- [ ] T02 — Plugin/profile disabled — Tool absent from final inventory; direct unauthorized dispatch fails clearly.
- [ ] T03 — Local-model tool use — Schema reaches the final request, executable call is dispatched, and a visible typed result returns.
- [ ] T04 — Remote and combined modes — Same schema semantics; identical allowed requests are coalesced without leaking grants.
- [ ] T05 — eBay approval or credential missing — Source explains the missing requirement; other sources and external action remain usable.
- [ ] T06 — One provider blocked or timed out — Other results survive; failure remains explicit; no fabricated fallback listing.
- [ ] T07 — Every selected source is link-only — Listing count is zero; coverage says links are prepared, not listings searched.
- [ ] T08 — Successful empty provider response — no_results, distinct from blocked, network failure and missing access.
- [ ] T09 — CAD/USD, unknown shipping, auction — Exact money parsing; no false total or cross-currency/offer-kind comparison.
- [ ] T10 — Unsupported radius or region — Applied-filter evidence is accurate; no silent US substitution or precise-distance claim.
- [ ] T11 — Duplicate and similar listings — Exact duplicates removed; possible cross-source duplicates grouped without losing originals.
- [ ] T12 — Cancellation or new query — Calls cancel; old request events cannot populate the new search.
- [ ] T13 — Cursor reuse with changed query — Cursor rejected; no cross-query or cross-account state leakage.
- [ ] T14 — Malicious listing/redirect — No instructions executed, secrets forwarded or unauthorized URL fetched.
- [ ] T15 — Disable, uninstall and restore — Exposure and jobs stop; secrets handled safely; restore does not auto-enable monitoring.
- [ ] T16 — Accessibility/theme combinations — Large text, TalkBack, dark/light themes, reduced motion and keyboard insets remain usable.
- [ ] T17 — Package incompatibility or tampering — Incompatible adapter registration and untrusted integrity data are rejected.
- [ ] T18 — Existing app regression suite — Amazon tools/cards, other native plugins and existing chat modes retain behavior.

- [ ] B172 · Additional adapter certification
- [ ] B175 · 16. Development phases and merge gates
### B176: Phase | Deliverable | Exit gate

- [ ] 0. Integration trace — Locate active tool resolver, profile grants, result renderer, settings navigation, backup hooks and test commands. Record source-access and license decisions. — A call-path map and failing integration tests exist; no guessed wiring locations.
- [ ] 1. Native foundation — Contracts, capability registry, coordinator, explicit catalog entry, structured validation and native dispatch. — T01, T02, T07, T08 and cancellation fixtures pass.
- [ ] 2. Useful link-first UI — Four regional search adapters, filters, statuses, workspace, detail/action presentation and saves. — Phone-only use works with no keys, PC or AI; regional/manual link checks recorded.
- [ ] 3. Tool and chat integration — Local/remote exposure, bounded evidence, shared combined-mode requests and rich rendering. — T03/T04 plus no-blank-response and permission-change tests pass.
- [ ] 4. Authorized eBay — Confidential credential route, approved API adapter, details, quotas and source display rules. — Approval and permitted use confirmed; sandbox/live evidence separated. Otherwise remains disabled.
- [ ] 5. Discovery and comparisons — Optional configured SearXNG, transparent discoveries, deduplication and comparable-price analysis. — No dependence on public instances; unknown fields remain unconfirmed.
- [ ] 6. Hardening and release — Migrations, backup/restore, accessibility, diagnostics, tamper checks and existing-feature regressions. — T01–T18 pass where applicable; unmet API gates remain visibly disabled.
- [ ] 7. Optional expansion — Allowed monitoring, additional approved sources, optional Amazon compatibility. — Separate permission, quality and regression gates for each addition.

- [ ] B177 · Dependency and branch strategy
- [ ] B180 · Definition of done
- [ ] B183 · 17. Proposed file-level implementation map
### B185: File / group | Required work

- [ ] Existing data/​catalog/​GitHubMarketplaceCatalog.kt — Add explicit native preset, correct alias, free-baseline notice and compatibility metadata. Do not reuse the places-oriented helper unchanged.
- [ ] Existing data/​marketplace/​NativeMarketplaceCatalog.kt — Add dedicated search/detail schemas and validator delegation while preserving other providers' strict schemas.
- [ ] Existing data/​marketplace/​NativeMarketplaceClient.kt — Route the master provider to specialized coordination before generic single-request construction.
- [ ] Existing data/​marketplace/​NativeMarketplaceRegistry.kt — Integrate readiness, disable/uninstall and owned-secret cleanup without requiring all subproviders to be ready.
- [ ] New data/​universalmarketplace/​MarketplaceContracts.kt — Versioned requests, money, listings, discoveries, actions, statuses and schema helpers.
- [ ] New data/​universalmarketplace/​MarketplaceProvider.kt — Typed adapter interface and capability descriptors; cancellation-safe contract.
- [ ] New data/​universalmarketplace/​UniversalMarketplaceCoordinator.kt — Planning, bounded fan-out, source events, single-flight and cancellation.
- [ ] New data/​universalmarketplace/​MarketplacePolicyRegistry.kt — Source permissions, quota/cache/ranking restrictions and kill switches.
- [ ] New data/​universalmarketplace/​MarketplaceNormalizer.kt — Exact money, geography precision, unknown-field handling and canonical identifiers.
- [ ] New data/​universalmarketplace/​MarketplaceGrouping.kt — Exact deduplication and non-destructive possible-duplicate groups.
- [ ] New data/​universalmarketplace/​providers/​ — Separate Facebook/eBay/Craigslist/Kijiji link adapters; separately gated eBay and SearXNG adapters.
- [ ] New data/​universalmarketplace/​UniversalMarketplaceSettings.kt — Typed configuration with per-source grants/readiness and vault references.
- [ ] New repository / DAO / entities — Saved queries, bookmarks, allowed observations and migration tests; follow existing database ownership.
- [ ] New presentation/​universalmarketplace/​ — ViewModel, workspace, filters, cards, details, source settings and accessible states.
- [ ] New di/​UniversalMarketplaceModule.kt — Bind coordinator, provider set, repositories and existing networking/security services.

- [ ] B186 · Cross-cutting files to locate before editing
- [ ] B189 · 18. Decisions, risks and release checklist
- [ ] B190 · Architecture decisions
- [ ] B196 · Key risks and mitigations
### B197: Risk | Required mitigation

- [ ] Users expect every source to return live cards — Show mode before searching and actual coverage afterward; use precise release wording.
- [ ] Destination search URLs or filters change — Versioned builders, regional/manual checks and a base-site fallback with explicit filter notice.
- [ ] API approval unavailable or revoked — Keep automatic adapter disabled; retain the useful link-first baseline.
- [ ] Tool installed but absent from local requests — End-to-end exposure/dispatch assertions, not catalog-only tests.
- [ ] A provider fails but UI implies success — Typed statuses, separate actions, fixture isolation and partial-result tests.
- [ ] New code expands APK or creates lag — Native bounded coordinator, no browser runtime, measured APK/UI regressions.
- [ ] Source policies conflict with ranking/history — Capability-specific restrictions; do not normalize away contractual requirements.

- [ ] B198 · Final release checklist
- [ ] B202 · 19. Sources and review register
- [ ] B204 · Repository sources
- [ ] B221 · 19. Sources and review register — continued
- [ ] B222 · External primary sources and code references
- [ ] B239 · Unresolved review items
