# Research and free provider reliability implementation

GPT Mobile AI

Design and implementation record for Emanuel Melo

24 October 2026

## 1 Decision and scope

Research must preserve useful evidence when an individual model is unavailable, fit each request to the selected provider, and distinguish a valid delivered report from a request that merely finished. The immediate repair addresses shared Research stage ownership, repeated admission failures, provider output reservations, missing profile statistics, invalid report repair classification, and Hugging Face Space error handling. The remaining sections specify the durable scheduling, capability, performance, and evidence work needed for reliable operation across free providers.

The configured Hugging Face Space has a separate upstream failure. Two schema-correct, synthetic requests to Qwen/Qwen3-Demo reproduced the exact reported `NoneType` and `choices` exception. Both received HTTP 200 and a queue receipt before generation failed. The Android client can correctly classify and contain that failure; restoring the remote model service requires a functioning hosted deployment. No tested alternative produced a successful answer. This remains an external release blocker for claiming that the Hugging Face provider is operational. [H1, H2, P1]

This document separates observed defects, client repairs, proposed implementation, and external dependencies. It does not label every warning as a distinct defect or claim that a third-party free service can be made permanently available. The required outcome is predictable progress, honest limits, preserved work, and a clear recovery path.

### Evidence baseline

| Item | Verified value |
| --- | --- |
| Input | app-diagnostics-6813773505710046264.jsonl |
| Export size | 5,167,565 bytes |
| Physical records | 4,859, all parsed successfully |
| Record structure | 3,169 events and 1,690 continuations; 3,168 unique event IDs |
| Issue flags | 748 repeated event manifestations, not 748 distinct bugs |
| Installed application | 0.9.56.1, build 127, debug |
| Installed commit | 6facea90a3ef5edaed0cc51bd34a17f779970c42 |
| Retained interval | 24 October 2026, 10:48:12 to 10:54:54 Toronto time |
| Repository baseline | 9ad2dba8614c15e326817bd01027cdb6a796c47c; its complete source tree matches the installed commit |
| Main at inspection | 62eb4f0812180ac8d1ac93cba2d9c46a88fce789; the newer stability work was in PR 739 |
| Repair branch | fix/research-free-provider-hf-2026-10-24 |

The export reports no dropped records, no orphan continuations, and no source or export truncation flags. It contains no Hugging Face transaction; the reported Space error is separately corroborated by the live probes. There is no retained fatal exception, ANR record, or out-of-memory exception. Process-exit coverage is empty, so the file cannot establish the absence of failures outside this interval. Local Assist being disabled is configuration, not a defect. [D1]

## 2 Main findings

Research scheduled 118 model stages: 112 extraction stages, four gap checks, and two planning stages. The recorded outcomes are 116 failures and two cancellations, with no successful structured stage. Fifty failures came from a local terminal-stage guard, and 64 BlockRun attempts hit a saved provider-unavailable state. These repeated local blocks dominate the failure count. The network capture contains only 22 model-generation POSTs. [D2, D3]

The shared-stage defect is concrete. Each contributor called `finishStage` for a task-wide Research stage after completing its own acquisition. The first contributor could therefore close and release a stage still needed by other accepted contributors. The correct unit of completion is each accepted worker's share, followed by one aggregate task-stage completion after all shares settle. [C1]

All six retained report validations were rejected by structural or numerical evidence checks; semantic coverage remained unverified. A separate repair defect converted nonempty but invalid repair responses into `EMPTY_REPORT`: the parser discarded its rejection reason, and delivery validated an empty string. The repair must preserve valid original paragraphs and saved evidence, retain the initial validation issues, and expose a typed rejection without weakening source hashes, block identity checks, or citation validation. [D4, C4]

### Issue inventory

Priority P0 means a core correctness or progress blocker. P1 means a substantial reliability, evidence, or responsiveness problem. P2 means lower-impact diagnostic or maintainability work. The priorities are implementation judgments based on the evidence below.

| ID | Priority | Observed issue | Evidence |
| --- | --- | --- | --- |
| R01 | P0 | One worker closes a shared Research stage and later workers receive terminal-stage denials | 50 local refusals; examples at log lines 1700, 1986, 2631, 2996; source confirms shared closure |
| R02 | P0 | A cooling-down BlockRun profile is scheduled repeatedly | One reasoning-only plan, followed by 64 cooldown blocks |
| R03 | P0 | Structured Research stages produce no accepted result | 118 starts, 116 failures, two cancellations, zero successes |
| R04 | P0 | Report delivery fails structural and numerical checks; semantic coverage remains unverified | Six validations rejected; each is partial and lacks verified semantic coverage |
| R05 | P1 | Invalid repair text is mislabeled as an empty report | Four completed responses with 3,324 to 6,129 visible bytes precede empty-report repair outcomes |
| R06 | P1 | Generic stage allowances conflict with real provider behavior | Requested 512 tokens; the anonymous Horde adapter caps output at 256, Pollinations requires its fixed 1,500 allowance |
| R07 | P1 | Context planning uses unknown or effectively unbounded inputs | 118 child queues report Int.MAX_VALUE; repair context reaches 10,200 estimated tokens |
| R08 | P1 | Genuine quota waits need shared durable scheduling | LLM7 reports 76,059 seconds Retry-After; Cehpoint receives HTTP 429 |
| R09 | P1 | Service outages and quota failures need different treatment | Vireonix HTTP 502; Pollinations HTTP 503 with a ten-second retry hint |
| R10 | P1 | Publisher access failures leave unread evidence | MDPI and ResearchGate return 403; two ScienceDirect requests return 400 |
| R11 | P1 | Independent review is unconfigured and acquisition remains partial | EXAMINER_UNSELECTED; 30 no-op review-worker results; 13 incomplete acquisitions |
| R12 | P1 | Recovery material grows while only a small slice enters the prompt | Four reused payloads grow from 392,740 to 642,854 characters; inline portion stays 6,000 |
| R13 | P1 | Repeated user recovery actions do not establish final synthesis | 13 combine actions, nine accepted retries, four failed-stage retry actions |
| R14 | P1 | Long UI stalls affect interaction | 170 stalls above 100 ms; worst measured frame about 1.493 seconds |
| R15 | P1 | Settings construction performs a main-thread disk read | StrictMode records 61 ms through MarketplacePackageStore construction |
| R16 | P2 | Combined preparation exceeds an Android compiler instruction threshold | prepare method warning reports 22,164 instructions |
| R17 | P1 | Debug statistics lack visibility and reliable correlation | The inspected baseline Research attempt card omitted profile statistics; all 236 stage events show invocation=none |
| R18 | P2 | Low-value platform warnings increase diagnostic noise | 29 JobInfo warnings, four missing-frame errors, one IME unregister warning |
| R19 | P0 | An origin-linked duplicate can replace the saved original during resume | Frozen-source regression returned NO_USEFUL_OUTPUT; restoration then dropped the original's valid claims [C8] |
| H01 | P0 | Hosted Hugging Face generation fails after queue acceptance | Live default and smallest published Qwen models reproduce the reported exception |
| H02 | P1 | An error-bearing Space update can be accepted as an answer | Client accepts last assistant snapshot on complete without checking its retained diagnostic |

The 63 frame windows include 37,876 measured frames and 678 jank frames, about 1.79 percent. This aggregate hides the severe isolated stalls. The 131 skipped-frame warnings include a maximum of 180 skipped frames. These observations justify profiling, but do not identify one exclusive cause. [D8]

## 3 Hugging Face repair

### Root cause and proof

The configured route is `https://qwen-qwen3-demo.hf.space`, backed by Qwen/Qwen3-Demo. The inspected application calls DashScope and dereferences `chunk.output.choices[0]` before validating the returned status and output object. An upstream response with no output triggers the reported exception. The exception handler writes a distinctive red diagnostic into the assistant bubble and rethrows it. The underlying reason DashScope returned an invalid result is not exposed by the public failure; missing authorization, upstream availability, and provider rejection must remain possibilities rather than claimed causes. [H1]

| Probe | Toronto time | Outcome |
| --- | --- | --- |
| Qwen3 235B A22B on configured Space | 11:00:51 | HTTP 200 submission and stream; terminal NoneType choices error; no answer |
| Qwen3 0.6B on the same Space | 11:01:06 | Same terminal error; no answer |
| Official SmolVLM2 alternative | 11:02:21 | Schema-correct submission accepted; terminal error with null details; no answer |

The prompt was a short synthetic request to reply with OK. No user content was sent. The Qwen failures completed in approximately 0.84 and 1.77 seconds; they were not long queue timeouts. The alternative was investigated as an explicit possible replacement, not used to forward a failed user's request. Two other official candidate Spaces were paused. There is no verified replacement to make the default. [P1]

### Client implementation

`FreeQueuedTransport.readGradio` must give a trusted hosted diagnostic precedence over transport completion. An error-rendering assistant record must never become a usable snapshot, a successful health event, a cached answer, or a report contribution. This applies when the Space sends `error`, when it sends `complete` after the diagnostic, and when the connection ends after the diagnostic without the final error event. A plain disconnect without an explicit diagnostic remains an uncertain result, with any safe partial text and its receipt retained.

The patch keeps the existing durable receipt and no-duplicate-submission protections. A terminal receipt cannot resume. A fresh user-authorized generation gets a new attempt identity. Reopening the same failed receipt performs no generation POST. Error details are bounded and credentials are redacted before display and persistence. Ordinary model text discussing a programming error is still an answer; classification uses the verified Qwen diagnostic structure, not a loose keyword search. An old assistant message or diagnostic before a newer user message is not reused for that newer turn. [C2]

Schema discovery remains necessary before dispatch. The current integration correctly uses `/config`, `/gradio_api/info`, `/gradio_api/call/add_message`, and the returned event ID. Hidden server State positions must remain present as null placeholders where the actual component layout requires them. Do not send a fabricated session hash or invoke an apparent cancel generation endpoint as though it were a cancellation API. The documented Gradio contract and the deployed version's behavior determine these details. [H3, H4]

### Capabilities that must remain honest

The Space's public settings expose a model, a system-prompt field, and a thinking budget. Its source multiplies the thinking budget by 1,024; a value of one is not a one-token reasoning limit. The generation call does not pass an output-token ceiling, and its system-message construction is commented out. Adding arbitrary `max_tokens` or `enable_thinking` fields to the public form does not establish that the backend honors them. [H1, H2]

Consequently, this route must report its enforced output cap and route context as unknown unless a verified deployment changes that contract. The app's requested amount, a local safety budget, and a server-enforced cap are different values. The client repair now rejects bounded Research model stages on this route with OUTPUT_LIMIT_UNSUPPORTED before a generation request is submitted. Searches and crawling can still preserve evidence, and other accepted eligible profiles can continue. Ordinary chat and Combined contributor requests retain the Space's existing server-controlled limits; this change does not invent a max_tokens control. A compatible verified deployment is required to admit this route to bounded Research.

The Space owner should validate each DashScope status and output before reading choices, return a sanitized structured error with a request identifier, validate the served model, implement and document output limits, apply the system instructions correctly, and test the deployed credential and model permissions. A null guard alone makes the failure clearer; it does not restore an unavailable upstream model. This external repair is required before marking the provider operational. [H1, H5]

## 4 Research lifecycle and scheduling

### Accepted work and ownership

An accepted Research task must freeze its selected profiles, permission revision, provider destination, evidence policy, resource allowance, and final-delivery requirements. Its task-wide work stages may be shared, while every participating profile retains an independent stage outcome. The accepted worker set must be known before contributors start so a fast worker cannot finalize the stage before slower or queued workers have registered.

`CombinedTurnBudget` now records terminal worker shares. Completing one share leaves peer shares admissible. The aggregate stage settles once all accepted participants complete, fail, cancel, or are explicitly excluded through the existing accepted membership policy. A contributor that ends before finishing its acquisition settles an incomplete share; it does not fabricate successful acquisition. Terminal callbacks are idempotent and cannot release a reservation twice. Restored snapshots default the new field safely, and persistence remains under the budget's existing synchronization. Delegation preserves the accepted worker identity even when a selected helper executes the model call. An authorized contributor retry reopens only that worker's shared-stage membership and preserves previously charged work. [C1]

The scheduler should process logical work items such as one source passage, one extraction batch, one review, or one report repair. Each item needs stable task, stage, profile, attempt, and evidence identifiers. A retry must preserve successful evidence and only repeat work that is eligible under the accepted policy. Failed HTTP calls, local admission blocks, invalid response schemas, canceled attempts, and rejected final reports must have distinct outcomes.

### Failure isolation

Before placing another model stage on the execution queue, check the current profile configuration, permissions, provider health, shared cooldown, model capability, context fit, and available task budget. Repeat the authorization and final payload checks immediately before physical dispatch. A profile blocked at admission is a local skip or wait, not a network attempt.

The immediate patch adds free-provider preflight to Research eligibility and correctly classifies wrapped cooldown, budget, terminal-stage, hosted-generation, and reasoning-only failures. Repeated empty, invalid, or transport results should suppress that profile for the remainder of the current model-stage workflow according to the bounded failure policy. Other eligible profiles continue. A normal user cancellation is not counted as provider unreliability. [C3]

| Condition | Required transition | Continue other work |
| --- | --- | --- |
| Local stage already settled | Skip the duplicate logical work; preserve original outcome | Yes |
| Provider quota with known reset | Persist waiting state and next eligibility | Yes |
| Provider unavailable without a known reset | Temporary failure with bounded recovery policy | Yes |
| Context cannot fit | Split the evidence packet or choose an accepted eligible role | Yes |
| Insufficient remaining hard budget | Budget-blocked state; never raise the user's limit | Yes, within remaining reservations |
| Accepted queue job has uncertain result | Retain receipt and inspect only supported status routes | Yes |
| All model profiles blocked | Save evidence and suspend model stages with a clear reason | Acquisition only where still permitted |
| User stops or removes a profile | Cancel owned work and reject late results | Remaining selected profiles only |

Long waits must not hold a foreground coroutine, model slot, database transaction, or per-profile semaphore for hours. Persist the next eligible time, release the slot, and schedule one wake-up. Recheck the task's current existence, permissions, membership, and budget when waking. A task that has been stopped must not revive merely because a quota expires.

## 5 Provider limits and context design

### Capability record

Replace scattered defaults with a versioned capability record keyed by the effective route. The record should identify provider, endpoint, model or resolved alias, credential scope, source of the observation, verification time, and transport serializer revision. It should separately represent input context, adjustable output limit, fixed output allowance, reasoning control, tool support, structured-output support, queue protocol, concurrency, and quota scope.

Use explicit states such as verified, declared, locally observed, application default, and unknown. Do not represent unknown capacity as Int.MAX_VALUE or unknown usage as zero. A model's advertised training context does not prove that a public gateway accepts that same context. Lower limits observed from a concrete rejection may be cached for that route; a catalog refresh should not silently erase them. [C5]

### Prompt admission

For a route with a known context, the complete serialized request must fit:

`prompt tokens + output reserve + safety reserve <= effective context`

The prompt estimate includes system instructions, message history, evidence, citation identifiers, structured-output instructions, and tool schemas that are actually transmitted. Final admission occurs after provider-specific transformations. Use the provider tokenizer where available; label byte-based estimates and retain a safety allowance when an exact tokenizer is unavailable.

For unknown free-route context, use a finite conservative application default until measured route evidence is available. The immediate patch uses 4,096 tokens for this guard and labels it `conservative_application_default`; this is a local risk-control value, not a claim about the provider's server limit. A smaller user or learned route ceiling takes precedence. The policy should later become a centrally versioned capability setting with clear provenance.

### Output budgets by role

Research's desired answer length and the provider's effective generation reservation serve different purposes. A short extraction may ask for a concise result while reserving the larger amount a fixed-output service can actually generate. Never serialize a false lower ceiling, lower a user-authorized hard budget without accounting, or assume an output default is a verified server cap.

| Provider or route | Evidence from this review | Research treatment |
| --- | --- | --- |
| Pollinations Classic | App contract is a fixed 1,500 generation-token allowance; a 512 request is rejected before dispatch | Reserve the full allowance when permitted; otherwise mark this role ineligible |
| AI Horde anonymous text | Adapter caps output at 256 tokens; a contributor brief completed after about 87 seconds | Assign small text extraction tasks; preserve queue receipt; do not promise a long final report |
| BlockRun selected reasoning route | A 512-token plan used all reported output on reasoning and produced no visible answer | Select a suitable planning budget or verified reasoning control; suppress repeated failures |
| LLM7 | Recorded quota response specifies a 76,059-second wait | Persist exact eligibility; continue other selected profiles; optional credentials remain isolated |
| OVHcloud | Recorded failures include local terminal-stage denials | Repair local ownership first; retain shared provider pacing and current route limits |
| Vireonix | Local stage denials, nonempty report text rejected by validation, and a later 502 | Separate admission, content acceptance, and transient provider availability |
| Hugging Face Qwen Space | No enforceable output cap in the inspected public contract; generation currently fails | Reject bounded model stages before dispatch; retain evidence and use other accepted eligible profiles |
| Cehpoint | 429 high-demand response with an estimated local wait | Label reset as estimated; avoid claiming an authoritative expiry |
| Kilo and BridgeNode | Some network responses succeeded; that did not establish a valid delivered research report | Use live route capability and evidence validation; do not equate HTTP success with task success |

These are observations and implementation contracts for the inspected revision. Provider limits can change, so the scheduler must consume current metadata and authoritative response headers instead of treating this table as a permanent global quota registry. The review did not perform a successful live Research task on every listed provider; local contract tests and historical diagnostics establish different kinds of evidence.

### Small context and small output workflows

Large page collections must remain outside model context. Store canonical source text and citation identifiers, then send bounded packets. A source packet should contain the question, a source identifier, a relevant passage, extraction instructions, and the few fields that the current output allowance can return reliably. Adaptive extraction must reduce the number of requested claims and quote length for 256- and 512-token routes. It must not ask a small route for six detailed claims or several thousand response characters.

The implemented packet builder accounts for the task, questions, source metadata, extraction instructions, and exact passage within the effective input allowance. It asks for one compact claim at 256 or 512 output tokens and expands only when the allowance supports more. Packet receipts include the source version, question scope, allowance, and exact character span, so a later acquisition pass can move beyond an already processed prefix. Original captures remain intact.

Keep unprocessed passages available for later work; a small sample is not a full source analysis. Use page, chunk, and claim coverage counters so a source's status can be partial. A final writer consumes accepted claims with their references and concise contrary evidence. The current repair advances completed packets within the existing verification budget. A packet is acknowledged only when the model explicitly returns packetComplete=true, every supplied claim row passes grounding, and no claim overflow was discarded. The final acknowledged packet can complete source extraction even at 256 or 512 tokens. An omitted or false completion flag, invalid row, or excess claims keeps the exact packet available for retry while retaining its valid claims. Small final tails are included in a valid packet rather than stranded below the minimum span. Question coverage and final delivery validation remain separate gates; a model completion flag is not a guarantee of semantic completeness. If the requested report cannot fit a capable selected writer, retain the evidence and offer an eligible writer or a smaller authorized deliverable. Do not silently discard evidence to report success.

Claim review now uses the same finite-context discipline. Bounded routes review one claim at a time with its exact quote and bounded neighboring context that fits. The packet retains the full saved capture's version and hash, the supplied passage range, and a packet ID bound to the source, claim, task, and questions. The reviewer must echo that ID. Counterevidence outside the supplied passage is rejected even if it occurs elsewhere in the full source. The saved capture is never replaced with the excerpt.

Accepted reviews record their bounded passage scope and retain the existing independent-review rules. If the mandatory task, questions, claim, or quote cannot fit, no review generation is sent. Invalid or missing responses preserve the claim, and unfinished reviews block complete Research status. A later retry processes only unfinished claims. This establishes passage review; omitted source text is not represented as reviewed.

Native tool support must be verified independently of text generation. The host can search, crawl, and assemble text evidence for a provider without native tool calls. Tool results remain untrusted evidence and cannot alter permissions or application instructions. The current queued text adapter's rejection of native tool calls must not be interpreted as a ban on all host-orchestrated Research.

## 6 Quotas and recovery

Quota scope and service-health scope are different. A shared IP or account quota may span several profiles and models. An upstream generation failure may affect one endpoint or model. The follow-up design should persist these scopes separately rather than using one provider-level health object for every condition. A success from model B must not clear model A's failure, and creating another profile must not bypass a shared quota. [C5]

For HTTP 429, prefer a valid `Retry-After` or documented reset field. Parse both delta seconds and HTTP dates, retain the observed server date where useful, and keep reset provenance. If no deadline is supplied, use a bounded local backoff labeled estimated. A 502 is not automatically a quota error. A generic rate-reset header on a failed request is not proof of the service's recovery time.

The recorded LLM7 deadline is 25 October 2026 at 08:00:02 Toronto time, derived from the exported response. It is evidence for that captured request, not a statement that the provider remains blocked for the reader now. Cehpoint's 60-second wait is a local estimate. Pollinations' recorded 503 supplies a ten-second retry hint. The scheduler must preserve these distinctions. [D5]

The normal UI should show a short actionable state such as `Rate limited: 34 minutes left`, `Temporarily unavailable`, or `Waiting for an eligible profile`. The profile picker should disable or clearly mark currently unavailable routes, while existing Research retains its results. Debug details can expose original status, scope, next eligible time, reset provenance, and skipped-work counts.

Retries need a task-wide ceiling, backoff, and a reason. A provably rejected request may be attempted again when permitted. An accepted or uncertain queued request must not be re-enqueued automatically. A terminal hosted failure requires a new explicit generation rather than a false Resume action. Recovery should reuse successful search, crawl, extraction, and review artifacts by identity instead of starting the entire Research workflow over.

## 7 Evidence and report delivery

### Source states

Model and UI state must distinguish discovered, metadata-only, abstract-read, full-text-read, extracted, reviewed, and independently reviewed evidence. A publisher 403 does not justify marking the source read. The crawler may seek lawful public mirrors, a publisher abstract, DOI metadata, or an open repository copy while keeping the access limitation visible. It must not bypass access controls. [D6]

If no examiner is configured, the normal UI should say that review is not configured and avoid repeated no-op scheduling. Whether review is optional is an accepted task policy. An optional review must not block useful partial output, but unreviewed claims cannot acquire an independently-reviewed label. The captured acquisition data has zero reviewed and independently reviewed claims; its partial state must remain honest.

Restoring a source group must retain its original capture ahead of an origin-linked duplicate. A regression test exposed that an alias inserted last could replace the readable original, after which claim filtering discarded the original's accepted claims. The repair prefers the original in either insertion order and preserves existing ordering behavior between two originals or two aliases. Retained claims and completed extraction receipts survive without another model extraction. [C8]

### Claim and numerical integrity

Store each accepted claim with its source ID, quoted passage or source span, provenance, and verification status. Numerical claims need typed value, unit, comparator, population, timeframe, and relation. A percentage from one population cannot silently support a claim about another. Table rows must preserve the same binding as prose. The final writer should receive these bindings, not just unstructured snippets.

The six rejected reports show numerical relation or unit mismatches, unsupported numerical claims, unsourced passages, missing studies or direct references, metadata represented as read, and missing disclosure of partial or review status. The acceptance checks should continue to reject those errors. The repair work must improve generation and diagnostics without weakening the validation gates. [D4]

### Typed repair outcomes

`ResearchReportRepair` should return a structured result with either an applied correction or a specific rejection. The implementation preserves a compatibility wrapper while exposing causes such as invalid JSON, unsupported patch structure, unknown block, hash mismatch, invalid replacement, duplicate target, or empty response. A raw provider response, a parsed patch, and an accepted report revision are separate artifacts.

`ResearchDeliverySession` must preserve valid original paragraphs and saved evidence while retaining the initial validation issues if a repair fails. The complete rejected draft is only an in-memory fallback input during this attempt; this repair does not add a durable archive of unsupported paragraphs. It should record a `REPAIR_*` reason rather than validating a fabricated empty string. Empty provider output, request failure, timeout, and cancellation remain different outcomes. The resulting evidence fallback may remain visible as a clearly labeled partial report, with unsupported paragraphs removed; it must not be relabeled accepted merely because the repair failed. Valid unchanged paragraphs and source bindings remain available for a later authorized repair. [C4]

### Recovery payload growth

The captured reusable recovery resource grows by 250,114 characters across four attempts, about 64 percent, while each inline excerpt stays at 6,000 characters. This establishes amplification and a narrow inline view; it does not by itself prove recursive nesting. The follow-up implementation should store one immutable corpus reference, record revision hashes, deduplicate prior messages and source records, and retrieve focused passages by ID. Avoid embedding an earlier recovery bundle inside a later bundle. [D7]

## 8 Debug statistics for every Research profile

Debug Mode must expose each selected Research profile's work while it runs and after partial completion or failure. The immediate UI repair passes profile statistics and the Debug Mode flag into the durable Research attempt card as well as the legacy progress card. Accepted profiles appear before their first model event, so a queued, skipped, or blocked profile does not vanish from the statistics. Normal mode keeps the technical panel hidden while preserving actionable failure and wait states. [C6]

### Implemented display

| Group | Displayed values |
| --- | --- |
| Profile identity | Selected profile name, with stable UID as fallback |
| Evidence contribution | Sources, unique sources, sources read, studies examined, pending examinations |
| Model records | Recorded invocations, running, completed, failed, and canceled records |
| Structured stages | Accepted, failed, and skipped stage counts; latest stage and outcome |
| Tokens and time | Retained input and output totals, estimate indication, aggregate model duration |
| Failure detail | Latest failure category and available invocation identifier |

A completed model invocation is not an accepted Research stage, and an accepted stage is not a validated final report. Those counts must remain distinct. Historical records without attribution must not be assigned to a selected profile by guesswork. Snapshot restoration and repeated flow emissions must not duplicate counts.

### Follow-up telemetry contract

The displayed initial counters are useful, but the captured log exposes deeper accounting gaps. All 236 Research stage events lack an invocation ID, and local preflight blocks may carry estimated usage. The next telemetry revision should join events using root task, accepted attempt, work item, stage, profile, invocation, physical request, queue job, and evidence revision identifiers. Persist event identity so replays update an existing observation instead of incrementing it again. [D9]

| Metric | Required meaning |
| --- | --- |
| Logical stages | Unique stage work items, independently of retries |
| Physical generations | Requests actually transmitted; exclude local skips and status polls |
| Queue polls | Retrieval requests for an existing accepted job |
| Admission blocks | Locally blocked work, grouped by cooldown, context, budget, permission, or terminal ownership |
| Output | Visible answer tokens, reasoning tokens, and tool-call tokens separately where reported |
| Token provenance | Measured, provider-estimated, application-estimated, or unknown |
| Timing | Queued, waiting, network, first useful text, and generation durations where supported |
| Source progress | Discovered, read, partially analyzed, fully analyzed, cited, and inaccessible |
| Delivery | Draft received, repair rejected, validation rejected, partial published, or accepted published |
| Limits | Requested and effective allowance, context provenance, cooldown reason and reset source |

The UI should use a compact expandable section per profile, stable keys, and coalesced updates. A suggested initial refresh ceiling is four updates per second while active and immediate terminal updates; this is a design target to measure, not a verified performance result. Do not retain raw prompts, credentials, sensitive tool payloads, or full provider stack traces in visible statistics. Export the same typed counters with schema version and measurement provenance.

## 9 Responsiveness and diagnostic quality

The follow-up performance work should start with measured main-thread traces during typing, switching profiles, opening tool settings, stopping Research, and loading saved results. Keep deterministic prompt preparation, snapshot serialization, evidence transformations, and expensive markdown preparation off the main thread. Avoid recomputing all profile statistics independently on every small streaming change.

The StrictMode stack directly identifies MarketplacePackageStore construction through `getNoBackupFilesDir`. Defer path resolution and directory work to an IO-owned initializer, and inject a cheap object during ViewModel creation. The Combined preparation warning requires splitting the oversized method into smaller pure stages without changing request bytes, evidence ordering, or accepted budgets. These are identified follow-up repairs; the log does not prove either is the sole source of every frame stall. [D8, C7]

Keep informational platform warnings in technical details rather than treating them as user-facing model failures. Correlate them with a reproduction before escalating priority. Continue exporting source coverage and truncation metadata, but fix event correlation and distinguish incident counts from repeated warnings. A clean generation should not be shown as failed merely because Android emits a nonfatal JobInfo warning.

## 10 Implementation map and delivery boundaries

| Area | Key files | Delivery status |
| --- | --- | --- |
| Shared worker completion | data/agent/CombinedTurnBudget.kt; data/repository/ChatRepositoryImpl.kt | Client repair implemented |
| Eligibility and failure classification | data/research/DirectResearchModelFactory.kt; ResearchModelCoordinator.kt | Client repair implemented |
| Free-route stage allowances | ChatRepositoryImpl.kt; data/research/ResearchProviderBudget.kt | Client repair implemented |
| Bounded extraction and claim review | data/agent/tool/AndroidResearchWorkflow.kt; data/research/ResearchExtractionPacket.kt; ResearchReviewPacket.kt | Client repair included in this work |
| Space error precedence | data/network/FreeQueuedTransport.kt; FreeErrorDetails.kt | Client repair implemented |
| Space capability explanation | data/free/FreeModelCatalog.kt | Client metadata corrected |
| Source restoration | data/agent/tool/AndroidResearchWorkflow.kt | Client repair implemented; original capture wins over its alias |
| Typed report repair | data/research/delivery/ResearchReportRepair.kt; ResearchDeliverySession.kt | Client repair implemented |
| Profile statistics | data/research/ResearchProfileStatistics.kt; ChatViewModel.kt; ChatScreen.kt; Research progress cards | Client repair implemented |
| Shared quota scheduler and model-scoped health migration | FreeCatalogState, limiter, accepted Research task scheduling | Follow-up design; existing cooldown behavior retained |
| Full physical-request telemetry | Request dispatch, invocation ledger, acquisition and delivery events | Follow-up design beyond the initial display repair |
| Recovery corpus compaction | Research session and recovery storage | Follow-up design |
| Main-thread and compiler hot spots | MarketplacePackageStore; CombinedSynthesisPlanner; rendering paths | Follow-up design |
| Working Qwen hosted generation | Public Space and upstream DashScope service | External blocker, reproduced live |

Qualified paths in this table are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`; shortened entries are filenames within that tree. The stability baseline was merged into main through PR #739 during this work. Main commit 0dc09d75019f68df3ad42d6f71eae4b97d8a71e1 has the same complete tree as the inspected 9ad2dba baseline. The repair is therefore a focused delta on that source, which also matches the installed diagnostic build.

### Follow-up delivery sequence

1. Land the client correctness repair after source compilation and focused regression checks. Preserve accepted request contracts and existing terminal protections.
2. Complete the capability registry and shared quota scheduler. Migrate existing cooldowns without clearing active restrictions, and prove wake-up ownership across process death.
3. Extend packet continuation for semantically dense passages that cannot finish within one response, complete report provenance and physical-request metrics, and retain explicit partial coverage with all source material.
4. Remove the measured UI hot spots and qualify on the target ROG Phone. Compare traces with Debug Mode enabled and disabled.
5. Requalify the Hugging Face deployment with live generation and a bounded Research task. Mark it operational only after successful answers, valid final delivery, and tested failure recovery.

Use separate rollback controls for optional telemetry and UI presentation where appropriate. Do not roll back mandatory payload admission, privacy checks, queue receipt safety, or source-hash validation to make a provider appear successful.

## 11 Acceptance tests

Acceptance requires observable behavior, not simply a green HTTP request. The following scenarios define the release contract; the validation record identifies which automated cases were executed in this change and which still require live or device qualification.

| ID | Scenario | Required result |
| --- | --- | --- |
| A01 | Worker A finishes before worker B starts extraction | B remains admissible; the shared stage remains open |
| A02 | Every accepted worker settles | Shared stage closes once and releases its remaining reservation once |
| A03 | One worker cancels while another continues | Cancellation settles only its own share; peers continue |
| A04 | Duplicate terminal callback | No duplicate accounting or new generation |
| A05 | Restored older budget snapshot | New optional fields default safely; ownership remains valid |
| A06 | Profile remains in a saved cooldown | No generation is submitted; statistics show a local wait or skip |
| A07 | Two profiles share one quota scope | Combined dispatch respects the common allowance |
| A08 | Quota expires after task was stopped | No automatic restart |
| A09 | Pollinations stage desires 512 tokens | Reserve 1,500 if permitted or reject eligibility before dispatch |
| A10 | User hard cap is below the provider's fixed output | Never silently increase the authorized cap |
| A11 | Horde extraction has a 256-token ceiling | Request a bounded small result and preserve unprocessed evidence |
| A12 | Unknown route context | Apply a finite labeled application guard; never use Int.MAX_VALUE |
| A13 | Complete serialized prompt exceeds known context | Split or reject before transmission; preserve citations and permissions |
| A14 | Reasoning uses the entire output allowance | Classify reasoning-only output; do not credit a visible answer |
| A15 | Provider fails one stage repeatedly | Suppress repeated failing model work; allow other profiles to continue |
| A16 | Space sends trusted diagnostic then error with null data | Preserve sanitized cause and terminal receipt; publish no answer |
| A17 | Space sends diagnostic followed by complete | Failure takes precedence; error markup is not answer text |
| A18 | Space ends after its trusted failure diagnostic | Classify terminal hosted failure, not an ordinary unknown disconnect |
| A19 | Space disconnects without a terminal diagnostic | Keep uncertain receipt and safe partial text; no duplicate POST |
| A20 | Earlier assistant answer precedes a newer user message | Do not answer the newer turn with old content |
| A21 | Model legitimately explains a programming exception | Keep ordinary explanatory text as a valid answer |
| A22 | Space schema changes | Fail before generation with a schema-specific error |
| A23 | User retries a failed accepted queue receipt | No automatic resubmission of that receipt |
| A24 | Repair contains invalid JSON | Preserve valid paragraphs and saved evidence; record typed parse rejection |
| A25 | Repair targets an unknown block or wrong hash | Reject the patch without weakening provenance checks |
| A26 | Repair supplies a valid authorized replacement | Apply the correction, then validate the report; retain stable source bindings |
| A27 | Repair fails, times out, cancels, or returns empty | Distinct outcomes; no fabricated empty-report validation |
| A28 | Publisher denies access | Keep source unread or abstract-only; never claim full-text analysis |
| A29 | No examiner is selected | Show configuration state; avoid repeated no-op jobs |
| A30 | Debug Mode on during Research | Every accepted profile has a statistics section, including waiting profiles |
| A31 | Debug Mode off | Hide technical panel while retaining actionable errors and waits |
| A32 | Partial result, failure, rotation, or restoration | Retain profile statistics and avoid duplicate counts |
| A33 | Local rejection precedes a network request | Do not count it as a physical dispatch or measured usage |
| A34 | HTTP completes but report validation fails | Show rejected or partial delivery, never completed Research |
| A35 | Large recovery corpus and repeated user retries | Reuse immutable evidence references without recursive growth |
| A36 | Target-device navigation with Debug Mode active | Measure frame traces, main-thread IO, and responsiveness before approval |
| A37 | Short source fully analyzed with 256 or 512 output tokens | Explicit valid final-packet acknowledgement can finish extraction; normal coverage gates still apply |
| A38 | Packet completion missing, false, invalid, or over its claim limit | Retain valid claims and leave the exact packet revisitable |
| A39 | Source ends with a short tail after a full packet | Adjust the boundary and process the tail; do not strand it forever |
| A40 | A helper generates for a worker, then that worker retries | Preserve accepted ownership, reopen only the retried worker, and retain previous charges |
| A41 | A small free route reviews a claim from a long source | Use one bounded passage review, preserve the full capture, and fit the whole request |
| A42 | A bounded reviewer cites contrary text outside its supplied packet | Reject the unsupported counterevidence and keep review pending |
| A43 | Mandatory task, questions, or exact quote exceed input capacity | Keep review pending without silently truncating those requirements |
| A44 | Restore an original capture and its origin-linked alias in either order | Keep the original and its accepted claims; do not repeat completed extraction |

## 12 Validation record

The final repaired source passed the focused Android regression run on 24 October 2026. The implementation is recorded in [code commit f6bcf61d30e7](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/f6bcf61d30e74ea4725b7c7e77bfaf2a30ae5f35) and published on [the repair branch](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/tree/fix/research-free-provider-hf-2026-10-24). Its parent is main commit 0dc09d75019f68df3ad42d6f71eae4b97d8a71e1, whose tree matches the inspected stability baseline.

| Check | Result | Scope and limits |
| --- | --- | --- |
| Android compilation and focused unit tests | 223 passed across 28 classes; zero failures, errors, or skips | Includes Kotlin, KSP, ObjectBox and Hilt compilation plus the selected regression suite |
| Provider and queue contracts | Passed | Error precedence, redaction, old-message isolation, accepted receipts, preflight, actual allowances, and failure classification |
| Research workflow and delivery | Passed | Shared workers, retry ownership, protected budgets, packet completion, bounded review, source restoration, typed repair, and delivery boundaries |
| Debug statistics | Passed | Aggregation and Compose/Robolectric rendering; visible after partial delivery and hidden when Debug Mode is off |
| Ktlint 1.3.1 | All 35 changed or new Kotlin files passed | Repository formatting rules |
| Android regex preflight | 831 literal patterns checked; zero errors | 51 dynamic patterns skipped by this static check |
| Resource preflight | 29 XML files passed | Resource and reference checks |
| Tool-statistics manifest | 36 definition producers have audited coverage entries | Inventory consistency, not an end-to-end execution test |
| Source integrity | All 3,189 source, configuration, and fixture fingerprints unchanged during the final run | Published code tree matches the locally staged, verified source tree |
| Live Hugging Face generation | Failed for both tested Qwen models; alternative probe also failed | External availability remains blocked; no successful replacement was verified |
| Device and full provider qualification | Still required | Target-phone frame traces, lifecycle behavior, accessibility, and successful live Research across supported provider roles |

The final regression run completed in 5 minutes 13 seconds. It followed correction of a test-fixture task identifier and the real duplicate-capture restoration defect. Earlier runs against code still being edited are not counted as final verification. The final run used a frozen source snapshot, and the before/after source manifest digest was identical:

`6896c05ef96b2e68989b150ce10f60e118e4a112cf98987e975816ced8d2f336`

The source manifest covers tracked and newly added build inputs outside documentation and image/metadata folders; it excludes Markdown files. The code commit's complete tree is `1b08fbd168a92ca9624ccf2b2430f507f63173e1`. The document is a separate subsequent commit, so publication of the written record does not change the tested implementation.

These tests establish client contracts under controlled inputs. They do not establish continuous third-party availability, successful live Research on every free provider, or target-device performance. The acceptance matrix includes follow-up scheduling, telemetry, recovery-size, and device requirements that are not claimed complete by this focused run. The Qwen Space must produce valid bounded live generations before the Hugging Face provider can be qualified for Research.

## 13 Sources and traceability

### Diagnostics evidence

All D references refer to physical lines in the supplied JSONL export. Continuation records were reconstructed before analysis; physical line references remain stable.

| Ref | Location | Supports |
| --- | --- | --- |
| D1 | Lines 1 to 2 and complete record audit | Build, retained coverage, counts, absence of retained fatal records |
| D2 | Lines 355 to 364; 1700; 1986; 2631; 2996; full stage aggregation | Reasoning-only plan, local terminal refusals, stage outcomes |
| D3 | Complete network and Model Finished aggregation | 22 generation POSTs; 140 finished model records with 121 failed, 16 completed, three canceled; three completed records are intermediate tool-call turns |
| D4 | Lines 3064, 3119, 4158, 4262, 4286, 4531 and preceding model outcomes | Six rejected deliveries and misleading empty-repair classifications |
| D5 | Lines 50 to 52, 65 to 66, 4315 to 4319 | Quota responses, service failures, fixed-output incompatibility |
| D6 | Lines 498, 506, 516, 690, 937, 947, 955 and acquisition outcomes | Publisher access failures and unconfigured review |
| D7 | Lines 3452, 3797, 4064, 4464 | Recovery resource growth and inline limits |
| D8 | Lines 74 to 101, 3765, 4254, 4663, 4829, 4834 and frame aggregation | Main-thread IO, compiler warning, UI stalls |
| D9 | Complete MODEL_STAGE, ACQUISITION_FINISHED and DELIVERY_VALIDATED aggregation | Missing correlation, token-provenance limitations |

### Repository evidence

The following links pin the inspected baseline. The implementation branch contains the corresponding repair delta.

- C1: [CombinedTurnBudget](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/9ad2dba8614c15e326817bd01027cdb6a796c47c/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/CombinedTurnBudget.kt) and [ChatRepositoryImpl](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/9ad2dba8614c15e326817bd01027cdb6a796c47c/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt).
- C2: [FreeQueuedTransport](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/9ad2dba8614c15e326817bd01027cdb6a796c47c/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/FreeQueuedTransport.kt), [GradioInputContract](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/9ad2dba8614c15e326817bd01027cdb6a796c47c/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/GradioInputContract.kt), and [FreeErrorDetails](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/9ad2dba8614c15e326817bd01027cdb6a796c47c/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/FreeErrorDetails.kt).
- C3: [ResearchModelCoordinator](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/9ad2dba8614c15e326817bd01027cdb6a796c47c/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/ResearchModelCoordinator.kt) and [DirectResearchModelFactory](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/9ad2dba8614c15e326817bd01027cdb6a796c47c/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/DirectResearchModelFactory.kt).
- C4: [ResearchReportRepair](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/9ad2dba8614c15e326817bd01027cdb6a796c47c/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/delivery/ResearchReportRepair.kt) and [ResearchDeliverySession](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/9ad2dba8614c15e326817bd01027cdb6a796c47c/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/delivery/ResearchDeliverySession.kt).
- C5: [FreeCatalogState](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/9ad2dba8614c15e326817bd01027cdb6a796c47c/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/free/FreeCatalogState.kt), [FreeAiProvider](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/9ad2dba8614c15e326817bd01027cdb6a796c47c/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/FreeAiProvider.kt), and [ModelRequestPreparation](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/9ad2dba8614c15e326817bd01027cdb6a796c47c/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/ModelRequestPreparation.kt).
- C6: [ResearchAttemptProgressCard](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/9ad2dba8614c15e326817bd01027cdb6a796c47c/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ResearchAttemptProgressCard.kt) and [ResearchProfileStatistics](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/9ad2dba8614c15e326817bd01027cdb6a796c47c/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/ResearchProfileStatistics.kt).
- C7: The StrictMode and compiler records in D8 identify the performance targets; source inspection and device traces remain necessary for the complete causal repair.

- C8: [Source restoration regression and bounded workflow tests](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/f6bcf61d30e74ea4725b7c7e77bfaf2a30ae5f35/app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/AndroidResearchWorkflowTest.kt). The duplicate-capture defect was discovered by the automated regression, independently of the uploaded log.

### Public provider evidence

- H1: [Qwen Space application source](https://huggingface.co/spaces/Qwen/Qwen3-Demo/blob/60e1db0/app.py), inspected 24 October 2026. Primary evidence for the DashScope dereference, exception rendering, output controls, and system-message behavior.
- H2: [Qwen model options and settings](https://huggingface.co/spaces/Qwen/Qwen3-Demo/blob/main/config.py), inspected 24 October 2026. Primary evidence for published models and thinking-budget settings.
- H3: [Gradio HTTP API guide](https://gradio.app/guides/querying-gradio-apps-with-curl). Named endpoint discovery and submission/result events.
- H4: [Gradio 5.27 routes](https://github.com/gradio-app/gradio/blob/gradio%405.27.0/gradio/routes.py) and [Space README](https://huggingface.co/spaces/Qwen/Qwen3-Demo/blob/main/README.md). Version-specific session and stream behavior.
- H5: [Alibaba Model Studio text generation guide](https://www.alibabacloud.com/help/en/model-studio/text-generation). Reference for checking DashScope response status before accessing successful output.
- P1: Synthetic live probe record, 24 October 2026, 11:00 to 11:02 Toronto time. Public config/info responses and SSE results retained as analysis evidence; no user prompt content was transmitted.

