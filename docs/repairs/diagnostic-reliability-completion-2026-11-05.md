# November 5 diagnostic reliability implementation

## Scope and evidence

This branch addresses the 92 findings in the supplied review of
`app-diagnostics-3996514748589439279.jsonl`, version 0.9.66.4/build 138.
The inspected base was `main` at
`9715e5c5992fcadf7df34c70fde324077d192986`. The working branch is
`fix/diagnostic-reliability-completion-2026-11-05`.

The review contains failures, repeated warning types, normal diagnostic activity,
and proposed hardening. A row below is a disposition of a finding, not a claim
that it was an independently reproduced defect. The raw diagnostic export is not
committed. Existing recovery, task persistence, privacy boundaries, and provider
selection are retained and extended.

**Disposition:** `Implemented` identifies a source change on this branch;
`Existing` identifies inspected behavior already present at the base;
`Environment` identifies acceptance evidence that requires the affected server,
provider, Android runtime, or physical device. A combined disposition means both
code coverage and an outstanding environment check apply.

## Resulting behavior

- Provider and tool failures carry stable error categories, failing phases,
  retryability, and dispatch evidence. Unknown transmission remains explicit.
  Confirmed partial answers remain visible after a stream interruption.
- Known HTTP rejections and local cooldowns retain their status and dispatch
  evidence; unclassified failures do not invent a transport phase.
- Known network outages block further provider dispatch through the shared
  connectivity gate. Recovery remains bounded, and possible mutations are not
  automatically repeated after an uncertain outcome. A remote tool capability or
  name alone does not establish approval to replay it.
- MCP reports connection and validated tool readiness separately. Invalid or
  duplicate tool catalogs cannot be published as successful discovery.
- Explicit local inference testing measures a real generation response and, for
  streams, time to first payload. Health/catalog metadata does not imply that
  inference or gateway tools were verified.
- Search aggregation preserves provider and refill receipts, failed-engine
  codes, and successful sibling evidence. Access restrictions remain enforced.
- URL retrieval streams into bounded buffers or a complete bounded temporary
  document. PDF continuation first delivers the remainder of the current page
  range, then advances pages. A SHA-256 identity prevents combining excerpts
  from changed source bytes. The model output allowance includes continuation
  metadata. Missing text, page ranges, and extraction warnings remain explicit.
- Speech recovery keeps partial transcripts available for review. Composer and
  frame-observer cleanup close lifecycle gaps. Diagnostic events preserve
  cross-line incident identity without merging separate failures.

## Final review and release acceptance

The final review corrected dispatch-accounting and recovery-delivery boundaries
and added explicit regression coverage:

- URL retrieval must retain evidence of an earlier HTTP response when a later
  redirect fails a domain, DNS, or request-budget precheck. A rejected follow-up
  request cannot refund the already consumed tool operation.
- An MCP tool's remote error text cannot prove that the tool was never
  dispatched. In particular, a write followed by an error about an unknown
  nested tool must remain unreplayable without reconciliation. Only verified
  local catalog rejection can supply the corresponding no-effect evidence.
- A local gateway recovery rejection must drain the queued privacy isolation
  notice before delivering its terminal error. A typed rejection now completes
  the producer normally, while unresolved earlier effects still block all new
  dispatch and private saved context remains excluded. The regression requires
  notice-before-error ordering as well as zero provider calls.

The release candidate is version `0.9.66.5`, build `139`. Its scoped manifest
starts at the last published `v0.9.66.4` commit,
`3de77a00946f5c621443dc7c8668dc8879ef0d1c`, so release notes also account for
changes already merged to main since that publication. The completed CI at
`ec69d04b8c08820269726a9d054c7390ec074f88` is historical implementation evidence;
the final candidate must pass its own PR checks, and the publication pipeline
must verify the actual merged commit and signed artifacts. The corresponding
GitHub check runs and published release are the authoritative completion
receipts.

Candidate CI also identified a missing JSON-builder import and two cold compiler
heap failures. The import was corrected; PR lint and GenieX compilation now
release generated-source processing state before compilation, and release
validation applies the same bounded separation to its debug and release paths.
GenieX also isolates its observed KAPT processing phase. Its compiler has a
measured 4 GiB job limit after the 3 GiB process exhausted its heap; the global
3 GiB default and all mandatory verification gates remain in force.
The approved-read retry regression now requests its retry after the failed
receipt returns in a later provider round; same-batch dependency rejection and
write replay fences remain separate, required behaviors.

## Per-finding coverage

Source keys in the tables refer to the files in the source map below.

### Critical failures

| # | Finding | Disposition and implementation evidence |
|---|---|---|
| 1 | Software-caused connection abort during generation | **Implemented.** N1 classifies nested socket aborts and EOF, records the stream phase, bounds recovery, and preserves partial output. Replaying a generation that could run gateway tools remains prohibited. |
| 2 | OpenRouter DNS failure | **Implemented / Environment.** N1 uses the shared connectivity gate before dispatch and distinguishes DNS from provider responses. Live DNS availability must be checked on the affected network. |
| 3 | Another unresolved model host | **Implemented.** N1 surfaces an actionable typed failure while preserving the original turn and received output; existing response recovery resumes from durable state. No alternate AI profile is silently selected. |
| 4 | Failed web search calls | **Implemented.** S1 produces provider, phase, request identity where supplied, transmission certainty, and retryability receipts. S2 preserves authorized successful siblings and all child attempts. |
| 5 | Failed tools with `errorCode=none` | **Implemented.** A1 normalizes missing/placeholder failure codes; S2 fixes the aggregate all-engines-failed path that previously dropped the code. |
| 6 | Failed/incomplete run | **Implemented / Existing.** A2 preserves dispatch receipts in checkpoints and distinguishes confirmed prechecks from unresolved effects. Existing continuation storage and G1/G2 recover confirmed steps. |
| 7 | Partial/incomplete run | **Implemented / Existing.** A3 labels response-flow completion independently of task verification. G1/G2 retain requested objective coverage and incomplete objectives. |
| 8 | Unknown failing stage | **Implemented.** A1/A3 prefer typed provider details and tool-phase receipts. Truly missing historical evidence is labeled `stage_not_recorded`; no exact phase is fabricated. |
| 9 | Unknown dispatch metadata | **Implemented.** A1/A2 and S1 represent precheck rejection, completion, failure, and unknown outcome explicitly. Persisted receipts survive result compaction and restart. |
| 10 | ASUS protected setting read in finalizer | **Environment.** No app access to `asus_leak_detect_proj` was found. D1 retains vendor attribution limits and grouped exception evidence. A framework/vendor stack and affected-device reproduction are required before attributing or changing this call path. |
| 11 | Speech service error 10 | **Implemented / Environment.** V1 separates binding-stage failure from post-readiness rate limiting, records the stage and attempt identity, applies cooldown, and preserves transcript review. Device recognizer availability still requires a real service. |
| 12 | MCP local unexpected EOF | **Implemented / Environment.** M1 recognizes wrapped abort/EOF failures and uses existing bounded read recovery. The actual listeners on 8101/8102 require separate deployment checks. |

### Network, gateway, and MCP

| # | Finding | Disposition and implementation evidence |
|---|---|---|
| 13 | Port 8101 timeout | **Existing / Environment.** N2/M1 provide endpoint-scoped health, connect/initialization deadlines, cooldown and explicit retry. Listener binding, firewall and server responsiveness must be checked on that host. |
| 14 | Port 8102 timeout | **Existing / Environment.** The same mechanisms are scoped independently to this endpoint; no shared cause is presumed. |
| 15 | `mcp.jina.ai` unresolved | **Implemented / Existing / Environment.** N1/N2 preserve DNS classification and prevent correlated outage amplification. S2 can use other already selected/authorized sources. Actual DNS/service repair is external. |
| 16 | MCP discovery HTTP 400 | **Existing.** M2 distinguishes unsupported discovery/protocol negotiation from authorization and transport failure, with compatible post-only and legacy routes. |
| 17 | MCP discovery HTTP 404 | **Existing.** M2 caches unsupported discovery and M1 records initialization-route configuration failure rather than repeatedly probing it as a transient model failure. |
| 18 | Repeated legacy negotiation | **Existing.** M2 caches negotiated behavior by endpoint/auth/network identity with expiry and explicit refresh. It does not claim instantaneous notification of arbitrary server-side capability changes. |
| 19 | Connected with unknown tool count | **Implemented.** M1 adds `NOT_DISCOVERED`, `DISCOVERING`, `READY`, and `STALE`; `toolsReady` requires a connected state and validated discovery. Invalid catalogs cannot publish success. |
| 20 | Discovery latency | **Existing / Implemented.** M1/M3 already perform independent discovery concurrently, share in-flight work, and cache schemas. New catalog validation occurs before cache/readiness publication. Physical-network latency has not been benchmarked here. |
| 21 | llama `/models` EOF | **Implemented / Existing.** N2 classifies wrapped abort/EOF; existing dedicated health transport, bounded recovery, and local connection policy are retained. L1 reports metadata failure independently of inference. |
| 22 | Local HTTP 404 | **Existing.** L1 and G3 keep unsupported health/control/dashboard routes distinct from model generation. Only definitive unsupported status codes downgrade capabilities. |
| 23 | Missing end-to-end gateway readiness | **Implemented.** L1 probes authenticated, model-specific gateway readiness even when administrative routes are accessible. Explicit inference testing separately proves generation; tool discovery readiness is separately exposed. |
| 24 | `gatewayReady=Not measured` | **Implemented.** L1 distinguishes server/model/gateway/tool and inference evidence; unperformed probes remain unmeasured instead of inheriting server health. |
| 25 | Loaded model without inference proof | **Implemented.** L1's explicit one-token generation test validates response model, payload, completion and timing. Polling normal health does not generate paid requests. |
| 26 | Unknown gateway capabilities | **Implemented / Existing.** L1 and gateway health expose unknown/unsupported states and `inferenceVerified: null` for catalog-only evidence; existing versioned capability negotiation remains in G3. |
| 27 | HTTP 302 | **Existing / Implemented.** N3 disables automatic redirects for general/MCP clients. R1 validates each reader redirect and scope, rejects loops/private destinations, and now scopes response resources correctly. A redirect alone is not classified as failure. |
| 28 | Retrying during DNS failure | **Implemented.** N1/N2 coordinate connectivity recovery, preserve bounded retries and endpoint cooldown, and clear state on network change. S1 stops redundant frontend retries on DNS/connectivity failures. |

### GitHub and objective completion

| # | Finding | Disposition and implementation evidence |
|---|---|---|
| 29 | Provider round does not prove repository objective | **Existing / Implemented.** G1 binds objective evidence to repository/branch/revision and accepted task coverage. A3 explicitly marks response flow separately from task verification. |
| 30 | `PROVIDER_FLOW_FINISHED` used as task completion | **Implemented.** A3 records `RESPONSE_FLOW_COMPLETED` with independent `taskVerification=NOT_MEASURED`; G1 supplies a separate verified objective verdict. |
| 31 | Many GitHub calls | **Existing.** G2 provides typed operations and independent read scheduling, while mutation receipts and recovery remain individually traceable. Call count is not treated as task count. |
| 32 | Repeated repository context requests | **Existing.** G4 caches repository/session responses using revision and request identity. Mutable refs must be rechecked before consequential operations. |
| 33 | Missing per-step failure attribution | **Implemented / Existing.** A1/A2 persist normalized per-call receipts and timestamps alongside run IDs. Existing G2 journals preserve GitHub operation and reconciliation evidence. |
| 34 | Partial continuation behavior | **Implemented / Existing.** A2 recognizes confirmed no-effect receipts without reclassifying unknown outcomes as safe. G2 retains durable plans and exact pending operation identities. |
| 35 | Insufficient evidence after tool errors | **Implemented / Existing.** S2/R1 preserve explicit partial/missing evidence. G1 and R2 gate objective/research conclusions on receipts and source coverage rather than provider prose. |
| 36 | Ambiguous GitHub writes | **Existing / Implemented.** G2's Git saga, remote reconciliation, and durable effect records prevent blind commit/PR/merge retries. A2 strengthens restart handling without promoting uncertain effects to safe retries. |
| 37 | Long-running GitHub operations | **Existing.** G2 uses persisted work queues, recovery workers/services, and CI receipts. Activity/provider lifetime does not define task lifetime. |
| 38 | Verification before implementation success | **Existing / Implemented.** G1 checks objective evidence, revision identity, required test and CI receipts. A3/G1's lifecycle metadata uses typed failure information and never substitutes flow completion for acceptance. |

### Tool discovery and execution

| # | Finding | Disposition and implementation evidence |
|---|---|---|
| 39 | Omitted tools | **Implemented.** A4's consolidated trace includes deferred catalog entries even when filtering occurred before round admission. Deliberate omission remains distinct from unavailability. |
| 40 | `CHAT_TOOL_SELECTION` | **Existing / Implemented.** A4 retains task-specific required tools and reserves the discovery route under context pressure. Existing explicit required-tool failures remain visible. |
| 41 | `NOT_ADMITTED_TO_ROUND` | **Implemented.** A4 reports the actual admission stage and protects progressive discovery from optional schemas consuming all capacity. |
| 42 | 6,000-token schema allowance | **Existing / Implemented.** A4 keeps compact catalog descriptions, task admission, and lazy full schemas; discovery is prioritized within the accepted context budget. The budget is not blindly enlarged. |
| 43 | Authorized tools exceed advertised tools | **Implemented / Existing.** A4 reports authorized, admitted, and advertised counts separately and retains the existing progressive catalog. |
| 44 | Omitted `read_url` | **Implemented / Existing.** A4 preserves the path to activate authorized deferred tools just in time. Missing permission or unavailable configuration is not bypassed. |
| 45 | Similar search tool names | **Existing / Implemented.** S2 uses selection/provider identity and capability adapters, deduplicates selected entries/results, and adds provider health/error evidence. Equivalent-looking names alone do not authorize a provider. |
| 46 | GitHub tools missing from a round | **Existing / Implemented.** A4 identifies repository-operation requirements in planning and retains progressive discovery within the round budget. |
| 47 | Limited search failure classification | **Implemented.** S1/S2 retain DNS, timeout, authorization, provider response, parsing, and availability distinctions with provider/phase/retryability. |
| 48 | Redacted/truncated failure previews | **Implemented / Existing.** A1/A2 retain private execution receipts independently of bounded model/UI previews; D1 keeps exported messages redacted. Secrets are not added to public diagnostics. |
| 49 | Empty omission summaries despite filtering | **Implemented.** A4 merges catalog candidates and round decisions into one omission view, with explicit reasons and regression coverage. |
| 50 | Completed tool with unusable output | **Implemented / Existing.** S1 validates required source fields, M1 validates schemas/catalogs, and R1 labels incomplete document evidence. Existing contract/output/evidence validation stays active. |

### Android performance and lifecycle

| # | Finding | Disposition and implementation evidence |
|---|---|---|
| 51 | 45 skipped frames | **Implemented / Environment.** D1 precompiles hot-path privacy regexes and R1 streams/does document I/O off the UI thread. A physical Perfetto trace is required to attribute the recorded stall. |
| 52 | 36 skipped frames | **Existing / Implemented / Environment.** Existing lazy/state-based UI is retained; V2 cleans composer focus on disposal/stop. This branch does not claim a measured recomposition/frame-time improvement. |
| 53 | 40 skipped frames | **Environment.** D2 keeps bounded frame metrics; reproduce the specific transition with concurrent workload to identify contention. No unsupported causal patch is applied. |
| 54 | 309.7 ms frame | **Environment.** D2 retains frame-time evidence; a trace on the affected device is the acceptance criterion, not a unit-test timing assertion. |
| 55 | 257.3 ms frame | **Environment.** Same measured acceptance requirement, including allocation/rendering analysis. |
| 56 | 39/120 janky frames | **Implemented / Environment.** D1 removes repeated regex compilation and D2 fixes observer retention. The observed sample must be remeasured before declaring its jank resolved. |
| 57 | 24/120 janky frames | **Existing / Environment.** Existing UI/frame telemetry remains; benchmark the affected transition and extended session under the same device conditions. |
| 58 | Multiple stalls above 100 ms | **Existing / Environment.** Existing frame metrics and performance/heap checks provide regression evidence; runtime performance budgets still require device measurements. |
| 59 | Cursor update after input detach | **Implemented.** V2 tracks composer focus and releases it before navigation/stop/disposal. Cleanup is limited to the composer that owned focus. |
| 60 | Text-before-cursor on inactive input | **Implemented / Environment.** V2 cancels the focused input lifecycle and hides the keyboard on departure. OEM IME behavior needs device coverage. |
| 61 | Finalizer-only reachable reference | **Implemented / Environment.** D2 fixes a concrete listener closure that retained an Activity through a weak-map value and cleans it on destruction. This does not prove it caused the vendor finalizer warning. |
| 62 | GC histogram window reduction | **Implemented / Environment.** D1 treats this as diagnostic information rather than a demonstrated leak. Allocation/heap profiling remains necessary for a leak claim. |
| 63 | WorkManager `onNetworkChanged` warning | **Existing / Environment.** The repository already uses WorkManager 2.12.0. D1 keeps a compatibility warning without hiding fatal errors. Android 16 network/job lifecycle behavior must be exercised; no speculative dependency upgrade was made. |

### Voice and input

| # | Finding | Disposition and implementation evidence |
|---|---|---|
| 64 | Recognition binding/error 10 cooldown | **Implemented.** V1 uses readiness stage to distinguish binding from rate-limit failure and retains controlled reinitialization/cooldown. |
| 65 | Availability independent of microphone permission | **Existing / Implemented.** V1 checks recognition service and permission separately and reports actionable availability state. |
| 66 | Pending keyboard operations during navigation | **Implemented.** V2 releases composer input on lifecycle stop/disposal without discarding the draft. |
| 67 | Voice failure feedback | **Implemented.** V1 preserves partial transcript review during cooldown, even when the microphone cannot restart, and retains the text-input path. |

### Research and PDF handling

| # | Finding | Disposition and implementation evidence |
|---|---|---|
| 68 | Academic source HTTP 403 | **Existing / Implemented.** R1 records blocked access and avoids repeated denied-host requests. S2 uses only selected authorized alternatives. No access-control bypass is introduced. |
| 69 | Source beyond 1 MiB | **Implemented.** R1 uses Ktor scoped streaming rather than a prebuffered GET; reads one extra byte to distinguish an exact limit. Complete document handoff streams to bounded temporary disk and PDF pages are segmented. |
| 70 | Incomplete evidence after truncation | **Implemented.** R1/R2 expose source byte coverage, page range, exact text offsets, warnings and continuation. Changed document bytes invalidate continuation. Missing text remains unavailable evidence. |
| 71 | Times-Bold fallback | **Implemented / Environment.** R3 selects a matching serif/bold system font when available and otherwise delegates safely. Device fonts and exact layout require rendered acceptance evidence. |
| 72 | Times-Italic fallback | **Implemented / Environment.** R3 selects compatible serif italic rather than regular sans when available. |
| 73 | Times-BoldItalic fallback | **Implemented / Environment.** R3 keeps both serif and bold/italic properties in candidate selection. |
| 74 | Helvetica-Bold fallback | **Implemented / Environment.** R3 selects compatible sans bold candidates. |
| 75 | Helvetica-Oblique fallback | **Implemented / Environment.** R3 selects compatible italic/oblique sans candidates. |
| 76 | Helvetica-BoldOblique fallback | **Implemented / Environment.** R3 selects combined bold/italic sans candidates. |
| 77 | Symbol fallback | **Implemented / Environment.** R3 checks representative Greek/math cmap coverage before accepting a symbol substitute; insufficient candidates delegate to the existing mapper. Text decoding alone is not proof of rendered glyph coverage. |
| 78 | ZapfDingbats fallback | **Implemented / Environment.** R3 treats dingbats separately and checks appropriate cmap coverage instead of assuming a symbol font covers both. |
| 79 | Repeated Helvetica-Bold warning | **Implemented / Existing.** R3 centralizes initialization/candidate mapping and reuses parsed fonts. A repeated fallback message alone is not counted as another defect. |

### Diagnostics and architecture

| # | Finding | Disposition and implementation evidence |
|---|---|---|
| 80 | 1,107 continuation records | **Implemented.** D1 associates interleaved continuations with the originating tag/process context; exports preserve primary incident identity. |
| 81 | Seven duplicate-reference batches | **Existing / Implemented.** D1 retains exact duplicate references while keeping distinct occurrences separate. Correlated exports preserve incident links. |
| 82 | Stack lines counted as independent errors | **Implemented.** D1 groups stack continuations under their exception; intervening records from another source do not steal the incident context. |
| 83 | Missing causal links | **Implemented.** A1/A2 preserve run/call receipt identities; V1 adds recognition-attempt identities; D1 exports incident/parent and typed failure fields. |
| 84 | No vendor log coverage | **Existing.** D1 preserves `vendor_log_available=false` and explicit coverage limitations. App telemetry is not relabeled as vendor evidence. |
| 85 | Redacted diagnostic payloads | **Implemented / Existing.** D1 redacts before enqueue/export, reuses compiled patterns, and retains safe structured categories. Private receipt data stays in the execution checkpoint/journal boundary. |
| 86 | Gateway readiness unmeasured | **Implemented.** L1 separates health/catalog/tool discovery from authenticated gateway readiness and explicit inference verification. |
| 87 | Model success confused with task success | **Implemented / Existing.** A3 labels flow termination separately; G1/R2 provide objective and evidence acceptance. |
| 88 | Incomplete-task recovery | **Implemented / Existing.** A2/G2 persist confirmation/uncertainty and recover outstanding work without duplicating ambiguous effects. |
| 89 | Missing retryability/dispatch certainty | **Implemented.** N1/A1/S1 use typed categories and receipts. Unknown outcomes remain reconcilable rather than automatically retryable. |
| 90 | Large tool-catalog overhead | **Implemented / Existing.** A4 combines compact catalog discovery, task-based admission, schema budgeting and explicit omissions. |
| 91 | Service/process lifecycle recovery | **Implemented / Existing / Environment.** V1/V2/D2 fix voice/input/observer lifecycle handling; A2/G2 retain durable execution state. Forced process termination and physical service recreation remain required acceptance checks. |
| 92 | Research provider interruptions | **Implemented / Existing.** S1/S2 use bounded provider health and authorized sibling routing; R1/R2 preserve source evidence and explicit continuation. Upstream provider uptime remains external. |

## Source map

Paths are relative to the repository root. Production roots are
`app/src/main/kotlin/dev/chungjungsoo/gptmobile/`; corresponding regression tests
are under `app/src/test/kotlin/dev/chungjungsoo/gptmobile/`.

| Key | Source and responsibility |
|---|---|
| N1 | `data/network/{NetworkFailureKind,ProviderConnectivityPreflight,NetworkRetryUtils,ResilientStreamingClient,OpenAIAPIImpl,ProviderExceptionDetails}.kt`; `util/ApiStateFlowExtensions.kt`; `data/research/academic/ResearchConnectivityGate.kt` — typed transport recovery and preserved output. |
| N2 | `data/network/{LocalServiceHealth,LocalConnectionPreflight,LocalEndpointConnectionPolicy}.kt` — endpoint/DNS health and bounded local recovery. |
| N3 | `data/network/NetworkClient.kt` — caller-controlled redirects and HTTP policy. |
| M1 | `data/agent/tool/{McpClientManager,McpCatalogueAvailability}.kt` — session lifecycle, read-only recovery and validated readiness. |
| M2 | `data/agent/tool/ModernMcpTransport.kt` — protocol/discovery negotiation and cache identity. |
| M3 | `data/agent/tool/AgentToolResolver.kt`, `data/agent/tool/McpCatalogStore.kt` — concurrent discovery and private cached schemas. |
| L1 | `data/llama/{LlamaHealthDiagnostics,LlamaInferenceProbeEvidence}.kt`; `gateway/v14/{catalog,health}.py` — separate measured readiness and generation evidence. |
| S1 | `data/agent/tool/{WebSearchTool,SearchFailureReceipt,SearchServiceHealth}.kt` — provider receipts, classification and health. |
| S2 | `data/agent/tool/{MultiEngineSearchTool,WebSearchEngineAdapter,SearchResultMerger}.kt` — authorized routing, aggregation, refill evidence and deduplication. |
| A1 | `data/agent/{ToolFailureMetadata,ToolReplaySafety,ToolExecutionBudget,ToolResultEnvelope,ProfileFailureRecord,AgentContractValidation}.kt`; `data/agent/tool/MeasuredAgentTool.kt` — normalized outcomes and truthful metadata. |
| A2 | `data/agent/{ToolResultCheckpoint,ToolBatchCheckpoint,ResponseRecoveryContext,CombinedContinuationCheckpoint}.kt`; `data/repository/ToolEventRecorder.kt` — persisted execution and restart interpretation. |
| A3 | `data/agent/AgentRunTerminalDiagnostics.kt`; `data/repository/ChatRepositoryImpl.kt`; `data/dto/ApiState.kt`; `util/ApiStateFlowExtensions.kt` — typed metadata propagation and flow termination distinct from verified objectives. |
| A4 | `data/agent/{ToolCatalogAdmission,ProgressiveToolCatalog,AgentRunner}.kt` — task-aware schemas, recovery path and consolidated omission traces. |
| G1 | `data/github/{GitHubRequestCompletion,GitHubTaskCoverage,GitHubTaskLifecycle,GitHubWorkflowJobEvidence}.kt` — revision-bound objective/CI acceptance. |
| G2 | `data/agent/tool/{GitHubGitSaga,GitHubEngineeringRecovery,GitHubEngineeringRemoteReconciliation,GitHubEngineeringWorkQueue,GitHubEngineeringRecoveryService,GitHubEngineeringCiWorker}.kt`; `data/github/GitHubExecutionJournal.kt`; `data/agent/GitHubTaskRecovery.kt` — durable effects, remote reconciliation and background work. |
| G3 | `data/network/gateway/{GatewayAPIImpl,GatewayControlNegotiation}.kt`; `data/network/gateway/GatewayDashboardClient.kt` — distinct unsupported control/metadata routes. |
| G4 | `data/github/{GitHubResponseCache,GitHubSessionCaches,GitHubTaskContinuity}.kt` — identity-aware context reuse. |
| V1 | `data/voice/{AndroidVoiceAudio,VoiceSession}.kt` — service stage, cooldown, stale callback fencing and transcript review. |
| V2 | `presentation/ui/chat/ChatScreen.kt` — composer lifecycle cleanup. |
| D1 | `data/diagnostics/{AppLogRecorder,DiagnosticPrivacy,DiagnosticClassification,DiagnosticIncidentTracker,DiagnosticExportDeduplicator,CorrelatedDiagnosticExport,StructuredDiagnosticExport}.kt` — private safe records and distinct incidents. |
| D2 | `data/diagnostics/FrameTimingRecorder.kt` — weak Activity ownership and lifecycle cleanup. |
| R1 | `data/agent/tool/ReadUrlTool.kt`; `util/{DocumentTextExtractor,PdfPageTextWriter}.kt` — bounded streaming, complete binary parsing and resumable page/text ranges. |
| R2 | `data/research/{ResearchDocumentResult,ResearchEvidenceRequirement,ResearchEvidenceManifest,ResearchJournal}.kt` — source coverage and evidence sufficiency. |
| R3 | `util/{AndroidPdfFonts,PdfFontFallbackPolicy}.kt` — style-aware, glyph-checked fallback policy. |

## Validation and acceptance

The canonical host command is `bash scripts/validate-local.sh fast`. Android
validation uses the existing PR workflows, including actual Kotlin/Ktor/Android
compilation, the complete unit suite, bounded-heap recovery tests, lint, packaged
runtime/APK checks, and the `Required verification` aggregate. Passing a local
subset does not replace those gates. Test counts and exact checked commit are
recorded in the PR after the corresponding jobs finish.

Behavior regressions added or extended include:

- nested DNS/abort classification, connectivity leases, no unsafe generation
  replay, preserved answer text, and explicit preflight dispatch certainty;
- invalid MCP catalog rejection before publishing readiness, interrupted reads,
  and separate inference/gateway/tool readiness;
- provider/aggregate/refill error receipts, successful sibling preservation,
  request identity sanitization, and preflight failures without dispatch;
- source-byte boundaries, PDF page/text cursor continuation, changed-source
  rejection, output-budget metadata retention, one-pass bounded PDF extraction,
  and glyph/style fallback validation;
- incident reconstruction with interleaved sources, correlated exports,
  structured redaction, voice error stages and retained transcript review;
- objective/flow metadata, persisted no-effect versus unresolved receipts, and
  preservation of progressive discovery under schema pressure.

External acceptance remains concrete: test ports 8101 and 8102 independently;
disconnect/reconnect DNS during generation and search; verify an authenticated
gateway/tool/inference round trip; exercise recognizer binding/cooldown and
navigation with the target IME; reproduce the ASUS finalizer stack; collect
Perfetto/frame/allocation evidence for the reported transitions; and exercise
Android job/service/process recreation. These are not represented as completed
by host tests or by health HTTP 200 responses.
