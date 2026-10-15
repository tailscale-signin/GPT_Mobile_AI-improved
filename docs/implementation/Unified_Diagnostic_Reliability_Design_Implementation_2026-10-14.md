# Unified Diagnostic Reliability, Research Integrity and Fast-Response Runtime
## Comprehensive design and implementation specification — October 14, 2026

> **Document status:** DESIGN ONLY. No feature implementation, runtime refactor, schema migration, application-version change, merge, pull request or release is authorized by this document.
>
> **Implementation branch:** **feat/unified-diagnostic-reliability-2026-10-14**
>
> **Base:** **main** at **91b497f58824c7cdf4d624e46ce10532342d5cd3** (merge PR #686: Smart Readiness / Local Assist).
>
> **Target application:** GPT Mobile AI Improved, Android package **dev.melo.gptmobile.improved**; comparison diagnostic build **0.9.52.0**, build **121**. Release/version decision deferred.
>
> **Implementation rule:** ALL workstreams in this specification belong on the above single branch after explicit implementation approval. Do not start parallel feature branches, merge to main, publish APKs, or claim fixes are verified based on this design document.

> **Subsequent implementation:** The user requested implementation on October 15. See [implementation and validation status](Research_3_Unified_Diagnostic_Implementation_Status_2026-10-15.md) for changed code, retained safeguards and open gates. The original design-only status above describes this document’s creation.

## Executive decision

The two October 14 diagnostic audits expose a connected set of failure mechanisms, not isolated cosmetic warnings: (1) local capacity/output allocation and Local Assist metadata rejection; (2) expensive or invalid tool advertising and model-emitted tool batches; (3) request admission and stale authorization races; (4) built-in search returning empty results despite apparently completed transport; (5) academic research accumulating large amounts of metadata without verified evidence making it to synthesis; (6) connectivity, authorization and MCP protocol negotiation failures; (7) excessive conversation-replay compression; (8) memory-worker busy loops; and (9) serious Android rendering stalls. The remedy is a single, state-owned, measurable request pipeline that keeps optional preparation off the critical path, preserves evidence, and fails predictably.

**Do not replace Smart Readiness or the current AgentRunner.** The October 14 main-branch merge already added ReadinessScheduler, SendPreparationPlanner, ReadinessSnapshotStore, ProgressiveToolCatalog, LocalAssistPreparation, MCP metadata caching, native lifecycle protections, and diagnostic timing. This project must adapt, extend and test those owners. The diagnostic builds may predate some merged changes or have readiness disabled. Prove present-day regressions before labeling an already-shipped protection as absent.

### Non-negotiable outcomes

1. A basic remote chat with no required tools is dispatched promptly, independent of unrelated MCP discovery, model-catalog refresh, memory enrichment, or optional Local Assist.
2. No request is sent with an output reserve that exhausts its actual verified context capacity. Unknown artifact capacity must not be invented or silently treated as unlimited.
3. No externally visible tool action occurs without an authorized, immutable, per-request policy snapshot and a last-moment authorization check.
4. Generated tool-call storms are bounded before execution and, where stream semantics permit, interrupted promptly before an expensive invalid batch completes.
5. Search is classified as useful evidence, legitimately empty, unhealthy, unauthorized, or parse-invalid; repeated empty outputs are not endlessly retried.
6. Retrieved/reviewed research evidence is preserved and correctly identified when a run is interrupted. Metadata alone never becomes a verified citation.
7. Research stops, resumes and synthesis use one checkpoint, work budget, ownership fence and cancellation provenance chain.
8. Rendering, memory processing and research data transformations do not block user input or visual streaming.
9. Diagnostics distinguish what is measured from what is estimated, what was attempted from what executed, and a completed answer from completed research.
10. Existing permissions, privacy controls, local-only boundaries, accelerator compatibility, source hashes, provider limits, model selection, native quarantine, and user cancellation must remain intact.

## 1. Evidence, confidence and scope

### A. Conversation diagnostic archive (directly available)

Source: **export_benefits of nicotine_1792030968676.debug.zip**, bundle schema **gptmobile.conversation.debug.bundle.v3**. Contains manifest, conversation snapshot, 1,435 retained run-journal events, context manifests and response-provenance snapshot. Export explicitly states it is **not complete provider-wire capture** and not a replayable reducer; source completeness is unknown. The retained journal reports zero dropped events and no missing sequences.

| Observed dimension | Evidence |
| --- | --- |
| Run outcomes | Four requests; one failed final answer; three answered, with the research execution stopped before normal completion |
| Run durations, approximate | Initial ~42 s; continue ~16 s; full report ~42 s; research report ~213 s |
| Built-in web search | 28 completed calls with zero parsed sources / EMPTY_VALID; another repeat-limited request; multiple independent queries |
| Initial unsuccessful run | 22 remote model invocations; 25 web-search calls including rejected last call; ~84,786 cumulative model tokens; terminal no-visible-answer |
| Academic acquisition | 775 source records, of which 654 metadata-only, 70 failed, 46 partial text, 4 full text and 1 abstract |
| Research handoff | 8 omitted findings, 775 omitted sources, empty findings/sources; question coverage 0/4; no verified claim handoff |
| Academic topicality | Only 260 of 775 titles mention nicotine; unrelated papers appear in source records |
| Acquisition execution | Nine successive pages used an overly broad paper query; 70 read failures including 11 unsupported PDF cases |
| User interrupt | Parent stop reason USER_STOP_RESEARCH_AND_SUMMARIZE; six child cancellations lacked explicit origin |
| Rendering | 20 flagged windows; worst sampled frame ~574.6 ms; one window 120/120 janky frames |
| Preparation | Discovery took ~2.9–5.6 s in these requests; logged readiness cache hits/misses were zero |
| Local Assist | Two LiteRT-LM assists completed (~1.67 s and ~12.26 s); no local native crash evidenced by this archive |
| Recovery/context | Short “continue” produced ~25,343 characters of recovery context; repeated output reservation estimates of 32,768 versus smaller effective request caps |
| Provenance | No contribution mapping; semanticCoverageVerified=false; final research answer lacks source-linked claims |

### B. General app diagnostic audit (provided as an audit summary)

Source: **app-diagnostics-3312699197672148121.jsonl**, October 14 audit summary supplied with this request, diagnostic commit **ed0a1963d6ba**. The summary reports 4,434 records (2,134 primary, 2,300 continuation), 343 flagged entries (12 errors, 331 warnings). **Its raw JSONL was not attached to this design request; line 773 and numerical claims below come from the supplied audit and must be revalidated from raw logs when the implementation starts.** The two exports are different captures and their counts must not be added together as though they were one session.

| Issue | Reported baseline |
| --- | --- |
| LiteRT context | 8,192-token total context; requested/effective output cap also 8,192; input estimated ~851 |
| Local Assist admission | Five ASSIST_PREPARATION_REJECTED events due to unverified artifact context |
| Tool batches | Three TOOL_BATCH_OVERSIZE failures after ~31.1 s, ~31.0 s and ~22.8 s of generation |
| Dispatch permission race | One DISPATCH_POLICY_CHANGED terminal failure |
| OAuth | discover_available_tools reported expired authorization |
| Tool execution | Three Brave/uSearch/Jina permission or dispatch errors; distinguish causes |
| Request preparation | 17 sends: direct 1 at ~0.19 s; contextual 9 avg ~5.19 s/worst ~12.68 s; required 7 avg ~4.52 s/worst ~9.69 s; all avg ~4.62 s |
| Tool advertising | 78 tools from 88 resolved candidates, ~39,679 estimated schema tokens for ~23 prompt tokens; other rounds 25 tools/~7,481 schema tokens |
| Compaction | 25 PRIMARY_REPLAY_COMPACTED entries; example 9,283 to 3,978 tokens |
| MCP | Broken pipes, five-second timeouts on ports 8101/8102, HTTP 400/404 protocol fallbacks, two Jina DNS failures |
| Web search | 59 dispatches, plus three suppressed repeated calls |
| Research | 131 reported completed calls, 50 pages read, six canceled, reviewedClaims=0, incomplete handoff |
| Memory | 14 enrichment worker starts, 11 RUNTIME_BUSY deferrals; three eventual successes with zero new records |
| UI | 2.21% sampled janky frames; 285 stalls >100 ms, worst sampled ~1.65 s; 86 inactive InputConnection warnings |
| Process | Two historical SIGKILL (signal 9) exits; memory elevated, but OOM/native crash not established |

### C. Current checked main architecture (verified)

Read and preserve these existing paths; this document names proposed additions separately.

- **data/readiness/**: ChatReadinessCoordinator, ReadinessScheduler, ReadinessSettings, ReadinessSnapshotStore, ReadinessInvalidation, SendPreparationPlanner, ReadinessWorkloadSignals. ReadinessSettings.enabled currently defaults false; contextual optional budget currently 250 ms.
- **data/agent/**: AgentRunner with existing per-run/per-round budget, repeat suppression and final-answer fallback; ProgressiveToolCatalog currently allows up to 24 initially advertised tools and 8,000 estimated schema tokens.
- **data/agent/tool/**: AgentToolResolver, McpClientManager and permission-aware dynamic tool acquisition. The MCP manager already includes health states, single-flight metadata cache and expiry backoff.
- **data/assist/**: LocalAssistPreparation with verified-artifact inspection, dynamic-context checks, loaded runtime state and recovery cooldown; AssistReadinessResolver.
- **data/localruntime/**: LocalInferenceService, LocalRuntimeImpl and LocalWorkloadAdmission (native lifecycle and compatibility safeguards).
- **data/context/**: ContextBudgetService, RequestDispatchContext and existing request-admission/permission guards. ContextBudgetService uses estimated input accounting and default output reservation.
- **data/network/**: ModelRequestPreparation, NetworkClient and ReadinessHttpTrace.
- **data/research/**: ResearchSessionStore; **data/research/academic/**: AcademicNativeClient; **data/research/feature/**: ArticleExaminationCoordinator and ResearchArchiveRepository.
- **data/model/**: DeepResearchSettings and its 0/25/50/75/100% source targets. **data/research/engine/**: ResearchEngineRegistry with explicit provider readiness.
- **data/memory/**: MemoryEnrichmentWorker and its source validation / background scheduling.
- **presentation/ui/chat/**: ChatViewModel, ChatBubble, ChatMarkdown, ReadinessDiagnosticsCard and LocalAssistDiagnosticsCard.
- **docs/implementation/Smart_Readiness_Design_Implementation_2026-10-14.md** and **Smart_Readiness_Local_Assist_Audit_Implementation_Status.md**: baseline architecture and pending Android/device test gates.

Code package root throughout is **app/src/main/kotlin/dev/chungjungsoo/gptmobile/**. Symbol and package paths must be rechecked at implementation time after updating the branch from main; no assertion here implies the proposed fixes are already coded.

## 2. One architecture: request admission and evidence ownership

### 2.1 Intended request flow

~~~text
User send / queued send / retry / combined / research
  -> immutable AcceptedTurn + runId + cancellation owner
  -> classify DIRECT | CONTEXTUAL | REQUIRED_WORK (existing planner)
  -> capture PolicySnapshot (profile, grants, conversation scope, epochs, budget)
  -> resolve ONLY mandatory dependencies, concurrently where safe
  -> optionally read already-warm readiness cache within hard deadline
  -> VerifiedCapacitySnapshot + measured/upper-bound TokenPlan
  -> task-ranked, policy-filtered ProgressiveToolCatalog
  -> provider dispatch (monotonic timestamps: accepted/serialized/body/headers/token/draw)
  -> bounded AgentRunner rounds / result dedup / controlled finalization
  -> evidence-aware synthesis / durable completion and diagnostics
  -> asynchronous, deduplicated memory enrichment when native runtime idle
~~~

**Ownership:** ChatRepositoryImpl owns run acceptance and common Send; permission owner issues revisions; RequestAdmission validates capacity and policy; AgentRunner owns tool-round budgets; McpClientManager owns endpoint sessions/health; ResearchSessionStore owns checkpoint/evidence generation and cancellation; LocalRuntime owns native engine lifecycle; UI consumes immutable state. No second owner is introduced for a competing scheduler, model session, permissions cache or research journal.

### 2.2 Immutable per-request snapshot

Design **AcceptedRequestPolicy** (new value object, location in data/context/ or existing request-authorization contract), containing request/run ID, profile identity/model/endpoint fingerprint, selected scopes and grants, policy revision, capabilities, effective tool identities, output/context allowance provenance, research depth and global budgets, cancellation generation, and deadline timestamps. Credentials stay in secure owner stores; a hash/reference belongs in telemetry, not the secret itself.

- Capture once at accepted send, outside the render thread.
- Recheck epoch before provider serialization AND before each tool side effect. On a policy change, invalidate old authorization and rebuild once with current permissions; do not replay already-executed tools or silently expand grants.
- A second change, revoked grant, or changed private destination ends the affected operation with a typed error/partial answer.
- Freeze provider/model identity and shared work budgets for a run; progressive discovery never grants new permissions.
- Preserve temporary-chat, private destination, local-only recall, consent and user cancellation semantics.

### 2.3 Independent deadlines and budgets

Separate clocks and budget owners:

| Clock or allowance | Start | Rule |
| --- | --- | --- |
| Send-to-dispatch | accepted turn | Optional prep has strict deadline; no optional child blocks completion |
| Mandatory preparation | accepted turn | Explicit attachment, research, recovery, permission prompt or local model uses required-work budget with visible status |
| Provider network | physical attempt | Connect, request body, response headers, first content and completion logged independently |
| Local Assist | actual native attempt | Native load/prep and generation have distinct cancellable budgets |
| Tool call | authorized dispatch | Per-host, per-tool and shared physical-call budgets; no duplicate execution |
| Research | research session generation | End-to-end calls, bytes, records, pages, depth, wall clock and synthesis reserve |
| Final synthesis | when acquisition is cut off | Guaranteed reserved answer window; incomplete status is preserved |

Do not classify a slow required research request as failed “fast response.” Direct/conversational requests and explicit research use different service-level objectives.

## 3. P0 — Correct local context and Local Assist admission

**R01. Single authoritative verified capacity.** Establish one immutable **VerifiedModelCapacity** record shared by runtime inspection, Local Assist preparation, readiness admission, benchmark configuration and local conversation creation. Include artifact fingerprint, installed path revision, runtime/backend, measured/declared context capacity, fixed/dynamic context semantics, capacity provenance, output capability, and verification timestamp. Invalidate on install, removal, backend/model selection, runtime update, quarantine or package change. Reuse existing artifact inspection; do not trust a profile text field or advertised cloud capacity as device verification.

**R02. Shared local output plan.** Always satisfy:

~~~text
usableContext = min(verifiedArtifactContext, verifiedRuntimeContext, acceptedProfileContext)
safetyMargin  = max(256, ceil(usableContext * 0.03))  // proposed starting guard; tune by tokenizer evidence
inputBudget   = measuredOrConservativeUpperBound(system + history + user + tool schemas + template)
allowedOutput = max(0, usableContext - inputBudget - safetyMargin)
effectiveOutput = min(userOutputCapIfAny, modelOutputLimitIfVerified, workloadCap, allowedOutput)
accept only when effectiveOutput >= workloadMinimum and all required input fits
~~~

All arithmetic uses checked Long until bounds are established. Account for tokenizer uncertainty, chat template, cached prefixes, reasoning buffers, tool results, reserved BOS/EOS/control tokens and backend constraints. Never reserve exactly 8,192 output tokens on an 8,192 total-context model with nonempty input. Example: at 8,192 context, 851 input and 256 margin, absolute output ceiling is 7,085; a 4,096 requested output fits if additional measured overhead permits. Reject or safely reduce only OUTPUT, never silently truncate the user's current input.

**R03. Unknown-capacity fallback without guessing.** For optional warm/preload or Local Assist, skip with VERIFIED_CAPACITY_REQUIRED and actionable artifact verification. For explicit inference, validate/import metadata, then offer an explicit compatible profile or verification flow; do not manufacture a CPU path for GPU-only weights. Previously verified, fingerprint-matching conservative envelopes may be reused; stale or mismatched metadata fails closed. Distinguish LOAD_READY from GENERATION_TESTED.

**R04. Atomic admission.** Revalidate output plan when conversation/template grows, tool schemas expand, or native runtime changes. Recompute after dynamic discovery, and recheck again at actual native session creation to eliminate time-of-check/time-of-use mismatch. Log requested/effective caps and both input measurement and provenance; never equate an estimate with tokenizer-measured tokens.

**Primary paths:** LocalAssistPreparation.kt, LocalRuntimeImpl.kt, LocalInferenceService.kt, LocalWorkloadAdmission.kt, ContextBudgetService.kt, existing request-admission contracts; add an isolated shared calculator under data/context/ if needed. Keep current native-thread, isolation, JNI cancellation and backend-quarantine invariants.

## 4. P0 — Remove blocking preparation and oversized tool advertising

**R05. Respect the existing Smart Readiness owners.** Keep SendPreparationPlanner's DIRECT/CONTEXTUAL/REQUIRED_WORK classification and ReadinessScheduler's bounded foreground warmup. Extend classifiers based on actual request requirements, not just matching greeting strings: short conversational turns may skip optional discovery even with history, but never skip explicit tools, consent, attachments, retrieval requested in the user message, or recoveries. Preserve the 250 ms optional contextual upper bound across ALL optional tasks combined, not 250 ms per child. An expired optional read uses available snapshots or continues without that optional enhancement.

**R06. Do not require idle warming.** Readiness is currently opt-in and disabled by default. Normal sending must work quickly when warming is disabled, while startup, restore, lock, metered-network, battery/thermal pressure, and background gates remain respected. Idle preparation never performs a paid provider generation, acquires new consent, transmits drafts without opt-in, or opens a heavy model without an explicitly configured local-preload policy.

**R07. Tool advertisement tiers.** Keep ProgressiveToolCatalog and discover_available_tools. Replace the current broad 24-tool / 8k-token initial limit with task-sensitive *default* windows, initially proposed 4–8 relevant tools / ~1,500–2,000 schema tokens for ordinary chats; permit larger authorized windows for explicit advanced/research tasks, subject to an effective provider context allowance. Always include an affordable discovery facade when cold tools exist. The 78-tool/39,679-schema-token incident must be impossible in a short default chat. Rank by explicit user intent, currently enabled scopes, provider ability, recent successful tools and typed capability relevance. Never modify the executable permission set solely because a schema was hidden.

**R08. Cache correct things.** Continue caching MCP metadata keyed by endpoint, auth/profile/grant/config epochs, sanitized transport version, TTL and schema hash. Cache compiled immutable model/provider schemas, not user permission decisions across revisions. Prewarm at most the already-authorized selected profiles, with single-flight producers; cache invalidation must fence late results and avoid blocking a simultaneous foreground send.

**R09. Move optional provider discovery off send path.** Continue the existing nonblocking OpenRouter catalogue behavior. Use a verified model capability snapshot or conservative previously accepted request policy; unknown huge context values must not expand replay budgets without proof. Do not switch models invisibly to gain speed.

**Primary paths:** SendPreparationPlanner.kt, ChatReadinessCoordinator.kt, ReadinessSnapshotStore.kt, ReadinessScheduler.kt, ProgressiveToolCatalog.kt, AgentToolResolver.kt, ModelRequestPreparation.kt, ChatRepositoryImpl.kt, existing cache/consent modules.

## 5. P0 — Bound tool storms and make dispatch safe

**R10. Generation-time tool-call guard.** Keep AgentRunner's MAX per-round observation, existing planToolBatch, repeated-tool suppression and final-answer reserve. Extend provider streaming adapters to count completed tool identities as they arrive, with robust handling for partial JSON chunks and provider-specific call-index reuse. Use a controlled stream stop when a bounded per-round limit is exceeded *if* the provider adapter can cancel reliably. Rejected calls must not be dispatched, and completed authorized actions must not be repeated on recovery. A provider that cannot be interrupted still gets bounded parsing/memory and no side-effect execution.

**R11. Per-run budget and escape hatch.** Define centralized **ToolRunBudget**: total physical calls, per-round calls, concurrent calls, per-tool repeats, per-host search/reader repeats, wall-time, bytes and final-answer reserve. Proposed interactive defaults: at most 6–8 calls per round and 24 total, tuned by actual tests; existing explicit research settings may allow larger counts but never an unlimited value inherited from Int.MAX_VALUE profile settings. Convert an exhausted/invalid batch into an explanatory tool-free finalization request *once*, with retained successful evidence. Distinguish “no visible answer” from quota/auth/transport failure and retain progress safely.

**R12. Incremental batch rejection feedback.** TOOL_BATCH_OVERSIZE must produce a typed, early diagnostic with observed calls/limit, model/provider adapter, elapsed generation, canceled-or-not and whether any side effects started. For the three reported 31/31/23-second failures, the target is rejection promptly after crossing the safe count rather than after complete generation; if provider streaming prevents early interruption, document the limitation and assert no execution.

**R13. Prevent permission races.** Replace terminal DISPATCH_POLICY_CHANGED for a harmless intermediate setting refresh with immutable policy acquisition and *one* coherent rebuild. Revocation is immediate for future dispatch; in-flight results from old epochs cannot be published as newly authorized tools. Execute-owner selection (gateway versus client) is deterministic, prohibits mixed-owner batches and duplicate aliases, and is logged.

**R14. Distinguish failures.** Tool result reasons include PERMISSION_DENIED, REAUTH_REQUIRED, POLICY_REVISED, CONNECT_TIMEOUT, DNS_FAILURE, PROVIDER_EMPTY, PARSE_FAILURE, ALIAS_CONFLICT, DUPLICATE_CALL, RESULT_UNCERTAIN, RATE_LIMITED, USER_CANCELED and BUDGET_EXHAUSTED. Do not retry mutations after an unknown outcome. Provider/model error versus app runner failure must be visible in both run state and diagnostics.

**Primary paths:** AgentRunner.kt, AgentToolResolver.kt, ProgressiveToolCatalog.kt, RequestDispatchContext.kt, relevant provider stream adapters and immutable policy/auth guard.

## 6. P0/P1 — Fix built-in web search and avoid redundant research calls

**R15. Search transport/result correctness.** Reproduce 28 consecutive EMPTY_VALID responses with test fixtures spanning broad queries, exact terms and different sources. Record end-to-end request status (HTTP/transport/provider), response bytes, parser extraction outcome, canonical URLs, content dedup and source count. Mark genuinely empty results only after a successful, schema-valid provider response. Classify empty-with-content-schema, malformed response, no provider configured, network failure, blocked search provider, permission expiry and zero actual matches separately. Never claim the provider was down based on an empty parsed list alone.

**R16. Provider health and fallback.** Circuit breakers are scoped to provider/endpoint/config, with cooldown and one low-cost half-open probe. For distinct broad queries with repeated apparently-empty results, optionally switch to an already-authorized healthy engine, without initiating new permissions or bypassing disabled providers. If no search route is working, stop further search attempts and synthesize from available verified context, explicitly stating coverage limits.

**R17. Query dedup and useful-result budget.** Cache normalized (query, locale, filters, permissions and privacy scope) result sets with brief TTL, distinguish empty-result cache entries from errors, and share completed results within the same run. Count logical and physical dispatches separately; the 59 search dispatches and repeated-suppression events should be traceable. New varied queries are allowed when clearly needed, but the planner must not issue superficial rewordings of the same unsuccessful query indefinitely. Treat searches as retrieval for the user's question, not as permission to browse unrelated sensitive context.

**R18. Source recording.** Search outcomes carry provider name, URL, canonical identity, query linkage, status, observed timestamp, text/abstract availability and provenance. If a source is unreadable or uncitable, preserve only its discovery metadata rather than implying its claims were verified.

**Primary paths:** existing web_search aggregate tool implementation/resolver, agent search results and research adapters, source-identity and telemetry stores. Do not add unauthorized search providers to the catalog.

## 7. P1 — Evidence-first research, partial synthesis and resumability

**R19. Question-driven acquisition.** Decompose each explicit research objective into questions and query variants. Allocate per-question source and reading slots; prioritize substantive primary/peer-reviewed/open sources rather than raw search-result volume. Stop unrelated nine-page repetition. Apply title/abstract relevance, source type, deduped DOI/PMID/arXiv/work ID, temporal filters, language/coverage, and the user's chosen Web/Papers source scope. The 0/25/50/75/100% source goals (0/30/90/180/300 targets) remain goals, not mandatory downloads or evidence-quality claims.

**R20. Distinct evidence states.** Introduce an additive **EvidenceLedger** entry with:
- immutable work/source/version identity, origin and authorized owner;
- acquisition stage DISCOVERED / METADATA_ONLY / READ_PARTIAL / READ_COMPLETE / READ_FAILED;
- captured passage with exact hash and locator when available;
- quote-match VERIFIED_EXCERPT versus unverified passage/metadata; this only proves quote occurrence, not scientific truth;
- relevance to question IDs, claim review status and confidence/limitations;
- privacy/storage scope, model contribution mapping, checkpoint generation and diagnostic receipt.

Acquired count, readable-text count, reviewed claim count and final selected claims must be separate metrics. Do not promote the 775 acquired records to 775 verified sources; the observed 0 reviewed claims must remain explicit. Never fill evidence with fabricated citations.

**R21. Nonempty, truthful synthesis contract.** Add an explicit **ResearchHandoffPacket** containing reviewed passages, unreviewed-but-available passages identified as such, discovered citation leads, per-question coverage, failures, stop reason, checkpoint token, remaining budgets and top source links. If review found no verified support, final text states that the requested evidence could not be verified, and may offer separately labeled tentative context. Do not discard 8 findings and 775 sources without retention/selection reasons. Size handoff by relevance and token allowance, with source-index references to the durable ledger rather than stuffing all results into the prompt. The final answer may be generated when research was stopped; answer status and research status must remain distinct.

**R22. Checkpoint + resume.** Extend existing ResearchSessionStore generation fences, stopOrigins and work lease records. Persist evidence as soon as each acquisition/review stage completes, atomically with an idempotent source/version key; resume from the exact unfinished stage without repeating successfully fetched pages or using a fresh budget. User “continue” and “stop research and summarize” are distinct actions; a canceled tool gets the parent cause (USER_STOP, SUPERSEDED_GENERATION, DEADLINE, POLICY_REVOKED, PROCESS_RESTART, etc.). Never publish stale-generation work.

**R23. Shared work budgets.** Keep a single research parent budget spanning academic queries, web search, MCP/gateway readers, page downloads, reference expansion, assisted review and final synthesis. Charge actual physical provider calls (including failed requests) and bytes; reserve resources for evidence review before starting more discovery. Bound max open parallel reads, per-provider rate limits, links, citations and text length. If the run is near the limit, finish review/synthesis instead of searching for yet more metadata.

**R24. Evidence-to-answer mapping.** Each factual claim with retrieved support maps to source/version/passage IDs; combined mode preserves unique contributions, URLs and caveats. Multiple profiles agreeing with one another is not external source verification. If citations cannot be verified, omit invented reference labels and show transparent uncertainty.

**Primary paths:** ResearchSessionStore.kt, DeepResearchSettings.kt, AcademicNativeClient.kt, ArticleExaminationCoordinator.kt, ResearchArchiveRepository.kt, existing ResearchBudget/ResearchJournal/ResearchSource abstractions and final synthesis integration. Proposed new classes live under data/research/evidence/ after code-owner review; do not create a parallel research database unless current stores cannot meet atomicity.

## 8. P1 — Academic extraction and domain safety

**R25. Improve evidence availability without false full-text claims.** Preserve existing open-access HTML/XML readers. For metadata-only or unsupported PDF: attempt allowed official OA HTML/XML, Europe PMC/PMC for eligible papers, arXiv HTML/source if supported, publisher abstract, or explicitly configured authenticated gateway PDF extraction. Maintain DOI/PMCID identity and version hashes, handle encoding failures and rate limits, and label all fallback content accurately. PDF processing must remain optional and isolated; do not package heavyweight native PDF/OCR dependencies into Android solely for this feature.

**R26. Reading quality gates.** Measure percentages of discovered sources with relevant abstracts, readable passages, failed attempts, verified excerpts and final cited claims. For the observed 654 metadata-only / 70 failed / 51 readable snapshot, optimize meaningful evidence coverage and reject unrelated papers, not just increase number of API calls. A paper “read” is not complete merely because its metadata loaded.

**R27. Search-engine readiness and service terms.** Use ResearchEngineRegistry readiness flags, quotas and provider policies; Google Scholar browser-assist is NOT an undocumented automated free search API. Do not silently enable pending engines or external MCP services. Respect fixed origins, redirects, bounded responses, host validation and user-set include/exclude filters.

## 9. P1 — MCP transport, OAuth, tool ownership and network resilience

**R28. Endpoint qualification.** Extend the existing McpClientManager state machine with explicit per-endpoint transport negotiation cache, advertised protocol version, validated /mcp path, capabilities and auth state. Differentiate genuine 400 protocol mismatch from invalid request, 404 wrong path, 401/403 authentication and transport timeout. Legacy fallback is allowed only when security/capability negotiation proves compatibility; verify listTools after fallback. Do not endlessly toggle between unsupported transports.

**R29. Connection health and cooldown.** Bound connect/protocol/health timeouts, reuse healthy sessions and close broken pipes. Recover from 8101/8102 failures and Jina DNS outages with circuit-breaker cooloffs and cancellable exponential jitter. The foreground direct-chat path must not wait for an unreachable optional endpoint. Health check is lightweight and obeys background/battery/network gates.

**R30. OAuth expiry.** REAUTH_REQUIRED removes that connection's tools from eligible advertised catalogues and sets an actionable status in Plugins/Tools. Silent refresh occurs only with an existing authorized grant. Do not auto-open login during idle warming; never classify expired credentials as a zero-result search or generic model failure. Reauthorization must explicitly invalidate cache, auth owner and policy revision.

**R31. Execution ownership.** Log gateway/client owner, original tool name, approved alias, connection UID and physical attempt. Deduplicate alias collisions; no mixed owner batch, no gateway-to-native execution retry after uncertain completion, and no bypass of conversation-scoped tool consents. Cached metadata cannot itself confer permission.

**Primary paths:** McpClientManager.kt, AgentToolResolver.kt, ToolConnectionRepository.kt, existing transport/auth coordinators and gateway health monitors. Preserve bearer-token, endpoint and secret-redaction protections.

## 10. P1 — Context replay, continuation and provider capacity

**R32. Capacity-proven replay.** Compute replay allowances from verified provider limits or explicitly conservative accepted ceilings, effective output cap, current serialized input measurement and required current-turn/tool/evidence budgets. Do not expand capacity using an unverified advertised maximum. Track estimate versus tokenizer measurement and actual wire size separately.

**R33. Do not compact blindly.** Avoid the repeated ~4,000-token replay cap when reliable capacity permits full retention. Compact only once the calculated request would exceed its accepted limit. Protect user instructions, current turns, unique combined-profile details, relevant tool results, cited evidence, source URL and temporal qualifiers. Compaction must be semantic-safe and auditable, not “summary of a summary” that drops provenance. Deliberately constrained models may still need small windows.

**R34. Efficient continuation.** Replace the ~25,343-character recovery prompt for a one-word “continue” with a bounded continuation manifest: last committed assistant segment, exact resume/cursor ID, compact recent context, tool/evidence ledger references, omissions with retrieval handles and output overlap fingerprint. Avoid reinserting tens of thousands of characters of failed empty-search attempts. Never repeat already completed external actions. Preserve full original content in durable local records and make it selectively retrievable.

**R35. Consistent output reserve.** Diagnostic context estimates must reflect actual effective provider output cap; do not display 32,768 reserved when the physical call was configured for 4,096 without explicitly labeling an earlier planning stage. Account for reasoning, function-tool representations and combined-mode reserved output.

**Primary paths:** ContextBudgetService.kt, AgentRunner.kt, ToolExchangeCompactor, ModelRequestPreparation.kt, provider capacity repositories and existing Combined synthesis/recovery contracts.

## 11. P1 — Memory worker fairness and correct outcomes

**R36. Unique work per source.** Use a stable unique WorkManager key per accepted, persisted message + revision/hash + scope; deduplicate equivalent scheduled enrichment after send/restart/restore. Retain exact-source revalidation, encrypted vault ownership and temporary-chat exclusions. No memory created if the model returned no eligible facts.

**R37. Event-driven idle.** The observed 11 RUNTIME_BUSY attempts across 14 starts should become one deferred outstanding job rather than repeated 15-second polling. Subscribe to safe runtime-idle/admission transition via existing ReadinessWorkloadSignals, with WorkManager background persistence and a bounded wake/deadline fallback. Do not compete with foreground local inference or benchmarks, reopen a quarantined worker, or spin on thermal pressure.

**R38. Honest result accounting.** Distinguish PROCESSED_NO_NEW_FACTS, ENRICHED_WITH_WRITES, DISABLED, SOURCE_CHANGED, DEFERRED_BUSY, NO_LOADED_MODEL, INVALID_OUTPUT and terminal error. A WorkManager success is not evidence that new facts were written. Charge explicit native-inference time and zero-write events in diagnostics.

**Primary paths:** MemoryEnrichmentWorker.kt, MemoryEnrichmentContract.kt, FactVaultRepository.kt, existing scheduling and admission/idle signals.

## 12. P1 — Compose rendering, input lifecycle and process memory

**R39. Frame diagnosis before guesswork.** Instrument major UI trace slices: message reducer, database observation, incoming chunk fanout, Markdown parse, source-link enrichment, Compose measure/layout/draw, chat scrolling and keyboard focus. Correlate with first-token and first-draw events. The 574.6 ms and 1.65 s frames are symptoms, not proof that Markdown alone is responsible.

**R40. Stream efficiently.** Collect provider data immediately without delaying model output. Coalesce *presentation updates* at a bounded frame-aware rate (starting ~16–33 ms while visible), preserve the exact text buffer and cancellation semantics, and avoid recomposing historical bubbles on every token. Cache stable Markdown and code-block layout keyed by message body/revision/theme; use item keys and appropriately scoped state, with background parsing for expensive transforms. Keep accessibility announcements and completion status timely.

**R41. Heavy work off main.** Run large JSON decoding, research provenance sorting, transcript transformation, memory-index work, source metadata and archive pagination on IO/Default with backpressure. Bound intermediate arrays and decoded text; never hold large unbounded streams inside a single Compose state object. Use existing database/readiness flows rather than adding independent polling loops.

**R42. Keyboard and navigation.** Audit 86 inactive InputConnection warnings, looking for actual lost typing, composer reset, focus fighting or delayed gesture. These messages alone are not a defect. Preserve a stable composer/input owner; do not reset the keyboard on every navigation or send. Measure back-swipe-to-interaction and tap-to-keyboard latency on actual devices.

**R43. Process/memory evidence.** The two historical signal-9 exits require ActivityManager historical exit details, PSS/RSS/native heap/high-water sample, ART/native logs, model load/unload ownership and LMK pressure to determine cause. Do not label SIGKILL as definitely OOM or native crash. Preserve model isolation, temperature/memory admission and process restart recovery. Sample and cap diagnostics to avoid worsening UI jank.

**Primary paths:** ChatViewModel.kt, ChatBubble.kt, ChatMarkdown.kt, ChatRepositoryImpl.kt, HomeScreen.kt, memory/research UI presentation, RunDiagnosticJournal.kt and native memory observers. Only add tracing after quantifying its cost.

## 13. P2 — Comprehensive diagnostics, provenance, and UI feedback

**R44. Correlation contract.** Every accepted run, provider invocation, tool call, search request, MCP session attempt, local inference, research journal, cancellation and rendered reply must carry a stable parent run/correlation ID (or redacted token), logical-versus-physical attempt identity, monotonic times, final state and explicit authority. Propagate parent stop cause to all children.

**R45. Timing milestones.** Record SEND_ACCEPTED, PLAN_DECIDED, POLICY_SNAPSHOT, OPTIONAL_PREP_START/DEADLINE, REQUIRED_PREP_FINISHED, TOOL_CATALOG_SELECTED (count/schema tokens), REQUEST_SERIALIZED, HTTP_START/BODY_END/HEADERS, FIRST_TEXT_RECEIVED, FIRST_VISIBLE_TEXT_DRAWN, FINALIZED, CANCEL_REQUESTED and CANCEL_ACKNOWLEDGED. Use monotonic time for duration, wall clock only for display. Render breakdown in existing Preparation & Readiness and response diagnostics cards without clutter.

**R46. Diagnostic outcome vocabulary.** Track model success, tool dispatch success, research completion, search source validity, local runtime state, and UI draw independently. “Run completed” cannot imply “research complete”; “local model loaded” cannot imply “local generation verified”; “source retrieved” cannot imply “claim scientifically verified”. Include physical call counts and result counts in export.

**R47. Privacy and export integrity.** Extend retained debug bundle only through current redact-and-hash pipeline: no bearer tokens, auth headers, raw private memory, provider secrets or user documents inadvertently written to public diagnostics. Record capture completeness (source/limitation/version), source hashes and omitted-record totals. Preserve no-drop journal properties; truncation is explicitly disclosed. Keep temporary conversations ephemeral.

**R48. Lightweight user feedback.** In debug mode show only useful statuses: “Search unavailable — trying an authorized alternative”, “Research stopped; verified findings preserved”, “Local Assist skipped: context capacity unverified”, “Plugin needs reauthorization”, and “Finishing answer with existing evidence”. Normal mode shows nonintrusive progress, not verbose technical trace.

## 14. Concrete execution contracts and pseudocode

### A. Capacity planner

~~~kotlin
data class VerifiedCapacity(
    val artifactFingerprint: String,
    val backendId: String,
    val contextTokens: Long,
    val provenance: CapacityProvenance,
    val dynamicContext: Boolean,
    val generationOutputLimit: Long?
)

fun planLocal(
    capacity: VerifiedCapacity,
    userInput: TokenCount,
    requestedOutput: Long,
    safety: Long,
    workloadOutputCeiling: Long
): LocalPlan {
    require(capacity.contextTokens > 0 && capacity.provenance.isVerified)
    require(userInput.upperBound > 0 && safety >= 0)
    val available = capacity.contextTokens - userInput.upperBound - safety
    require(available > 0) { "LOCAL_CONTEXT_EXHAUSTED" }
    val output = listOfNotNull(
        requestedOutput.takeIf { it > 0 },
        capacity.generationOutputLimit,
        workloadOutputCeiling,
        available
    ).min()
    require(output > 0 && userInput.upperBound + output + safety <= capacity.contextTokens)
    return LocalPlan(outputTokens = output)
}
~~~

*The pseudocode describes behavior, not a drop-in compilable patch; actual project type names and token source policy govern implementation.*

### B. Optional preparation deadline

~~~text
accepted = monotonicNow()
plan = SendPreparationPlanner.classify(fullAcceptedTurn)
policy = captureImmutablePermissionsAndBudget()
required = mandatoryWorkFor(plan, policy)
optional = readAlreadyWarmAuthorizedSnapshots()

if plan == DIRECT:
    dispatch(requiredOnly)
else if plan == CONTEXTUAL:
    await optional only until accepted + 250ms TOTAL
    dispatch(required + anyValidOptionalResult)
else:
    show required-work progress; await only explicitly mandatory dependencies
    dispatch with policy fences, measurable deadline and cancellation

Never await an independent abandoned optional task's native cleanup on the network-send path.
~~~

### C. Research handoff and cancellation

~~~text
ResearchHandoffPacket {
  parentRunId, researchGeneration, checkpointId, status,
  stopOrigin, wallBudgetSpent, physicalCallsSpent, bytesSpent,
  questionCoverage: [{ questionId, reviewedClaims, unresolvedReason }],
  verifiedExcerpts: [{ claimId, sourceVersionId, contentHash, locator }],
  unreviewedPassages: [{ sourceVersionId, availableTextHash }],
  discoveredLeads: [{ sourceId, url, reasonNotReviewed }],
  limitations: [reasonCode],
  omitted: { byRelevance, byTokens, byPermissions, unreadable, unreviewed }
}
~~~

Handoff must fit the actual provider context budget; full evidence remains in a separately scoped journal, and source IDs are resolvable only with the same authorization and privacy constraints.

## 15. Workstream-to-source mapping

| ID | Priority | Source files / area | Primary deliverable |
| --- | --- | --- | --- |
| R01–R04 | P0 | data/localruntime, data/assist, data/context | Verified capacity and safe local output admission |
| R05–R09 | P0 | data/readiness, ProgressiveToolCatalog, ChatRepositoryImpl | Nonblocking Send and small, accurate tool catalogues |
| R10–R14 | P0 | AgentRunner, provider adapters, auth/dispatch guard | Early tool-batch limits, stable permissions and reliable finalization |
| R15–R18 | P0/P1 | Built-in web_search aggregate + source adapters | Reliable results, provider classification and deduplicated requests |
| R19–R24 | P1 | ResearchSessionStore, evidence/archive, synthesis | Relevant evidence, provenance, partial results and resume |
| R25–R27 | P1 | AcademicNativeClient, ResearchEngineRegistry | Better OA text availability and academic source quality |
| R28–R31 | P1 | McpClientManager, AgentToolResolver, gateway monitors | Protocol qualification, OAuth and per-connection health |
| R32–R35 | P1 | ContextBudgetService, replay/continuation | Capacity-aware, evidence-preserving replay |
| R36–R38 | P1 | MemoryEnrichmentWorker, vault/admission signals | Unique deferred jobs and accurate memory outcomes |
| R39–R43 | P1 | Compose chat/home, background transforms, diagnostics | Smooth rendering, stable composer and exit attribution |
| R44–R48 | P2 | RunDiagnosticJournal, chat diagnostics UI, exports | Measured spans, truthful statuses, high-fidelity privacy-safe evidence |

**Do not** move security decisions into UI layers, modify persisted memory ownership or install unverified PDF/MCP providers. New contracts should be reusable and unit-testable, and should not compete with existing owners.

## 16. Implementation phases and sequencing

### Phase 0 — Establish baseline and regression fixtures (documentation-to-code transition)

1. Refresh this feature branch against main only after explicit implementation instruction, record exact merge base, catalog already-shipped safeguards and list newly failing tests.
2. Re-ingest both source logs where available and create redacted synthetic fixtures; validate timestamps, line numbers, counters and statuses. Do not commit personal conversations, API keys or private memory.
3. Record control measurements on same device, profile, runtime and network: direct/contextual/required send-to-HTTP, first byte, first visible token, research review coverage, tool dispatch count, native context plans and jank.
4. Add isolated contract tests before implementation changes. The first mergeable implementation phase must have repro tests failing on main or a documented nondeterministic device trace.

### Phase 1 — P0: make send, context and tools reliable

- R01–R04 capacity/source verification and Local Assist re-admission.
- R05–R09 readiness deadline and initial tool-schema gate.
- R10–R14 early batch interruption, global limits, policy-snapshot rebuild.
- R15–R16 built-in search result correctness and fail-fast, as necessary to end tool loops.
- Run full unit, lint, APK and basic smoke gates before phase completion.

### Phase 2 — P1: trustworthy research and integrations

- R17–R18 search dedup/provenance; R19–R24 question-driven research and checkpointed evidence handoff.
- R25–R27 academic extraction fallback and provider quality.
- R28–R31 MCP/auth/owner contracts and bounded recovery.
- R32–R35 replay and continuation improvements, preserving the entire current user request and unique combined evidence.
- Add cross-component no-double-dispatch and cancel/resume integration tests.

### Phase 3 — P1/P2: background fairness, UX and proof

- R36–R38 memory worker idle scheduling.
- R39–R43 UI streaming, main-thread profiling, composer and process diagnostics.
- R44–R48 telemetry, provenance, privacy-safe export and response UI.
- Validate warm/cold start, battery/thermal, metered/offline, and failure paths on at least one Qualcomm and one non-Qualcomm Android device.

### Phase 4 — Integrate, evaluate, roll out

- Compare against Phase 0 using same test conditions and report regressions honestly; keep optional behavior behind existing settings or narrow feature flags during qualification.
- Prefer single coherent owner per service, small reviewable commits WITHIN THIS BRANCH, no alternate feature branch.
- Submit a PR only after user approval; CI/build green alone does not establish device performance, native stability, research completeness or authorization correctness.
- Version bump and release packaging are separate, explicitly authorized tasks, not part of this design milestone.

## 17. Mandatory regression and acceptance matrix

Each test must assert behavior AND relevant telemetry. Synthetic failures must never trigger real external account actions.

| Test | Required scenario / assertion |
| --- | --- |
| T01 | Local 8,192 context + ~851 prompt + requested 8,192 output -> safe reduced cap or explicit rejection; never native overflow |
| T02 | Context exactly at safety boundary, one token above/below; no overflow/negative reserve |
| T03 | Chat template/system/tool overhead included in upper-bound measured input |
| T04 | Unknown, stale, mismatched and dynamically limited local artifacts -> deterministic safe skip or verified repair |
| T05 | GPU-only artifact is never treated as CPU-supported by settings alone |
| T06 | Loaded-versus-generation-tested Local Assist status is truthful |
| T07 | Direct “Hi” with no required tools/attachments -> no optional MCP/memory/network-catalog wait |
| T08 | Contextual optional work never delays network start past single 250 ms budget (allow scheduler/test-clock jitter); old tasks detach |
| T09 | Explicit attachment/research/local request still runs required preparation even if message is short |
| T10 | Offline, metered, trim, restore, lock and background events invalidate caches without leaking work |
| T11 | Tool-rich short prompt advertises task-sized schemas; not 78 tools / ~39k tokens |
| T12 | Progressive discovery adds newly authorized tools without adding permissions or duplicate aliases |
| T13 | Tool-call stream exceeds per-round allowance -> early bounded rejection, zero unauthorized dispatch |
| T14 | Provider not interruptible -> no tool execution after cap, explicit limitation and correct finalization |
| T15 | Three former oversized-batch replay fixtures terminate without tens of seconds of unbounded avoidable generation where adapter permits early interrupt |
| T16 | Policy revision while planning -> one coherent rebuild, no stale-schema dispatch |
| T17 | Policy revocation immediately before a tool side effect -> no execution; typed reason |
| T18 | Side-effect tool uncertain after timeout -> no automatic duplicate call |
| T19 | Expired OAuth -> AUTHENTICATION_REQUIRED, excluded tools and reauth UI; no blank-search misclassification |
| T20 | MCP wrong path, protocol 400/404 and 8101/8102 timeout -> bounded health classification and no direct-chat stall |
| T21 | Jina DNS failure -> cooldown, cancellation and no repeated immediate reconnects |
| T22 | Two genuine empty-result queries versus malformed/unauthorized search responses are classified differently |
| T23 | Reproduce EMPTY_VALID cascade with 28 search results -> circuit opens and answer finalized using existing evidence |
| T24 | 59 redundant query dispatch fixture -> logical/physical dedup without losing requested distinct research |
| T25 | Broad research query decomposes into four question scopes; irrelevant abstracts filtered |
| T26 | 775 discovered records, 51 readable, zero verified -> metadata retained but no invented sourced claims |
| T27 | Partial read and citation quote hash mismatch -> cannot be marked VERIFIED_EXCERPT |
| T28 | Stop with six in-flight research tools -> each child reason maps to explicit parent cause |
| T29 | Stop-and-summarize with useful verified evidence -> selected evidence and citations survive final handoff |
| T30 | Resume uses checkpoint and remaining budget; completed physical calls not replayed |
| T31 | Research parent can be answer COMPLETED while research STOPPED; both statuses displayed distinctly |
| T32 | Unsupported PDF route attempts only allowed alternatives; metadata-only outcome remains truthful |
| T33 | Disabled and pending academic engines never start an unsolicited request |
| T34 | Repeated compaction fixture keeps unique source URL, temporal qualifier and earlier decisive tool result |
| T35 | One-word continue uses small recovery manifest and no replay of successful tool side effects |
| T36 | Provider advertised huge but unverified context -> conservative replay and effective output cap |
| T37 | 14 queued enrichment attempts while native busy -> one pending unique job; no 15-second polling storm |
| T38 | Enrichment writes zero facts -> PROCESSED_NO_NEW_FACTS, not “new memory saved” |
| T39 | Deleted/edited/temp-scope source before background enrichment -> nothing recorded |
| T40 | 1,000-message conversation stream and heavy Markdown -> smooth scroll/input with bounded recompositions |
| T41 | Large research response does not cause main-thread JSON parse or 500ms–1.65s stalls in comparable synthetic test |
| T42 | IME open/send/back/rotate and hardware keyboard transitions -> no lost focus/text, irrespective of benign InputConnection warnings |
| T43 | Process signal-9 history without OOM evidence -> diagnostic reports UNKNOWN_CAUSE, not invented native crash |
| T44 | Profile/model/permission/auth changes fence late readiness/cache results |
| T45 | Combined response retains unique relevant contributions and maps only genuinely attributable source claims |
| T46 | Export manifest distinguishes measured, estimated, truncated, absent wire data, and semantic coverage unverified |
| T47 | Secret redaction and temporary-session privacy hold across new evidence, auth and telemetry |
| T48 | Cancellation during local native cleanup does not release engine ownership until acknowledged or quarantined |
| T49 | Research result/source budgets across gateway and native owners cannot double-spend or duplicate paper work |
| T50 | Run finishes no-visible-answer after failed searches -> bounded tool-free finalization, with useful error if provider again emits nothing |
| T51 | Verify no regression to other modes: standard, queue, Combined, delegation, deep research, benchmark and local native run |
| T52 | Rebuild from clean dependency cache, strict verification and no accidental native dependency or APK-size expansion |

### Quantitative targets (initial acceptance hypotheses, not measured claims)

| Metric | Diagnostic reference | Proposed gate under controlled repeatable conditions |
| --- | --- | --- |
| Warm direct send-to-physical-network-start | ~0.19 s (one sample) | p95 <= 0.5 s; no mandatory preparation skipped |
| Warm contextual send-to-physical-network-start | avg ~5.19 s, worst ~12.68 s (9 samples) | p95 <= 0.75 s when no required work; optional budget <= 250 ms |
| Required-work preparation | avg ~4.52 s, worst ~9.69 s (7 samples) | No global fixed SLA; visibly report required spans and bound each dependent stage |
| Tool catalog for simple chat | 78 tools/~39,679 schema tokens once | Normally 4–8 advertised tools, ~2k estimated schema tokens or less; exception logged |
| Empty search retries | 28 empty results | Bounded circuit and truthful finalization; no endless same-provider empty loop |
| Oversized tool round | three reported failures after ~23–31 s | Bounded early detection where adapter supports it; zero invalid dispatches |
| Research handoff | 775 sources + 8 findings omitted | 100% of selected verified passages represented by ID, or explicit evidence-size/permission reason |
| Research coverage | 0 reviewed claims, 0/4 questions | Coverage shown per question, with no fabricated minimum; quality-first review reserve |
| UI long frames | up to ~1.65 s in audit | On qualified devices, no >250 ms app-caused frame in scripted warm stream; p95 input/scroll responsiveness improves |
| Memory duplicate work | 14 starts / 11 busy | One unique pending job per message revision; no repetitive busy polling |

These values are starting performance targets. Run cold/warm, battery, thermal and network variants; compare medians/p90/p95 with sample counts and confidence bounds. Do not promise a remote model's time to first token, a specific provider's search yield or an OS-controlled process lifetime.

## 18. Required test, CI and device gates

1. Kotlin/JUnit tests for pure capacity math, tool-batch parser, discovery rank/budget, authorization state machines, search outcome classifier, research ledger/selection, checkpoint resume, replay compaction and worker outcome codes.
2. Robolectric/Hilt/WorkManager tests for sends, cache invalidation, connection health, process restarts, saved work, composer state and real DAO transactional guarantees.
3. Fake stream-provider contract tests with partial tool-call JSON chunks, out-of-order events, cancel-at-boundary, empty final answer, explicit provider finish state and retried tool-free generation.
4. Controlled web/MCP fixture servers for HTTP 200 empty/invalid, 400, 401/403, 404, broken pipe, DNS unavailable, delayed headers, protocol mismatch, stale OAuth, and gateway/native duplicate ownership.
5. Offline academic fixtures with DOI/PMID/PMC aliases, irrelevant hits, abstracts, OA XML/HTML, PDF-not-supported, quote hash mismatch, partial source availability and permission-revoked fetch.
6. Replay of the four conversation export requests with independently recorded logical/physical calls and full captured status; never commit raw personal content.
7. Android device tests with LiteRT supported backend and a non-Qualcomm reference, including actual context 8,192 admission, model loading/cancel, streaming jank/keyboard and memory-pressure behavior. Run native/packaged validation under both relevant runtime variants.
8. Observe existing repository gates in docs/validation.md: JDK 21, Android SDK 37, strict Gradle dependency verification, Kotlin lint, app unit/lint/APK, androidTest APK, Room schema checks if changed, script preflight, native inventory checker, gateway contract tests. No silent dependency-verification bypass, destructive migration or automatic release.
9. Review security/permissions, local-only and temporary-chat behavior, privacy-safe export, battery/workload gating and explicit consent in each workstream.
10. A feature may not be called “fixed” until the exact before/after failure fixture passes AND the integration/device gates applicable to it have been reported. Pending-device coverage stays labeled PENDING, not green.

## 19. Integration hazards, compatibility and rollback

- **Smart Readiness collision:** Extend the already merged readiness owner; never add an independent startup service or second MCP session cache. The feature remains safe when disabled.
- **Local execution:** Do not free or recreate native contexts on a callback thread, bypass quarantine, infer unsupported CPU/NPU compatibility or retry unknown-outcome JNI work.
- **Permission security:** Cache capability metadata, not unconditional grants. Revocation/profile change must fence stale work; avoid new OAuth prompts from speculation.
- **Research records:** Preserve schema/backups/archive and temporary scope; migrations must be additive, versioned, verified and tested, not destructive. Only introduce a new ledger table if existing persistence cannot provide version/atomicity requirements.
- **Search provider behavior:** Healthy real zero-result answers exist; do not permanently ban a provider because one query was empty. Avoid unauthorized fallback and comply with rate limits/source usage terms.
- **User intent:** Explicit research/attachments/combined mode may take longer; reducing delay must not remove evidence or functionality without a visible, authorized fallback.
- **UI performance:** Coalescing display updates must not drop streamed tokens, reorder text, erase user drafts or change completion semantics.
- **Metric integrity:** Distinguish captured-vs-missing events, identical-vs-distinct failures, source metadata-vs-readable papers, user-cancel-vs-runtime fault.
- **Rollback:** Each logical phase uses guarded configuration defaults and one authoritative owner. Reverting performance optimizations must not roll back the security/permission correctness of already-tested gates.

## 20. Definition of done for the future implementation

The branch will be ready for review only when: all 48 R items are dispositioned as implemented/verified or clearly excluded with rationale; all 52 test scenarios have an outcome with real evidence; no known P0 correctness regression remains; required CI checks are green; Android/native validation and performance observations are documented rather than assumed; the four-request debug-export scenario no longer exhibits unbounded search loops or evidence disappearance; UI and memory worker improvements are measured; tool auth and research citations remain trustworthy; and the only changed product code is deliberately kept on **feat/unified-diagnostic-reliability-2026-10-14**.

### Current deliverable (October 14, 2026)

**This document is the sole requested change.** A new branch from main and a single Markdown specification are the deliverables. No implementation, PR, merge, version bump, release or claims of passed runtime tests are included.

### Existing repository references

- docs/implementation/Smart_Readiness_Design_Implementation_2026-10-14.md
- docs/implementation/Smart_Readiness_Local_Assist_Audit_Implementation_Status.md
- docs/deep-research-implementation.md
- docs/implementation/debug-export-combined-integrity.md
- docs/validation.md
- README.md

*Design source notes: numeric findings are from the supplied October 14 diagnostic summaries and the available conversation debug archive. Their role is to define reproducible tests; a log symptom alone does not prove its root cause. Current architecture statements were cross-checked against main at the pinned base commit.*
