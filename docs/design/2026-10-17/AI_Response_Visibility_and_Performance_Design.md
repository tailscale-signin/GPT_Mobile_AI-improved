# AI Response Visibility and Performance Design

Text requirements imported from the supplied design document. Original diagrams and visual references remain in the DOCX. This specification is not an implementation-completion claim.

AI Response Visibility and Performance

Design Implementation Document

Prepared for Emanuel Melo  \|  GPT Mobile AI  \|  17 October 2026

Implementation decision: make successful completion depend on a valid user-facing answer, move title creation outside the answer stream, and reduce latency within the existing readiness, streaming, and runtime systems. This addresses the recorded blank reply while preserving tools, research, long answers, background generation, and local model support.

1  Findings and implementation decision

The first reply to “hi” was not an empty network response. Its entire stored answer was a 31-byte title comment. The display transformation removed that private metadata, leaving zero answer bytes, but the run still completed successfully. A second, separately submitted “hi” produced a normal greeting. The primary defect is a mismatch between raw provider text and what the user can actually receive. [E1]

The recorded first answer was exactly:

<!-- chat_subject: Greeting -->

The first request explicitly instructed the model to put that marker at the beginning and then answer normally. The provider stopped after the marker. The trace establishes that sequence; it does not establish why the model chose to stop. Reliability must therefore come from application validation and recovery, in addition to removing the fragile title instruction. [E1]

| Priority | Change | Required result |
| --- | --- | --- |
| P0 | One canonical answer projection and completion check | Metadata-only output never becomes a successful answer. |
| P0 | Independent title update and one bounded empty-answer recovery | The first turn produces an answer or a clear error without requiring another user message. |
| P1 | Immediate first answer publication and render | Short answers stream promptly while all accepted content remains intact. |
| P2 | Readiness reuse, prompt scope, provider and local tuning | Measured latency gains retain the same functional capabilities and answer quality. |

Baselines: the export identifies version 0.9.53.0, build 122, package dev.melo.gptmobile.improved, standard chat 714, and OpenRouter model xiaomi/mimo-v2.6-flash. Repository recommendations use main commit 7060f3245cb4012ed71043a09aa5595f9629563b. The export does not attest the installed APK commit, so current source findings are identified separately from measured behavior. Proposed performance targets are release criteria, not measured improvements. [E1, R0]

2  Evidence and measured timing

2 1  The exact failure boundary

User message 7863 contains “hi”. Assistant message 7864 contains only the title marker. Provider, reducer, and committed-output fingerprints match that marker. At the renderer boundary, 31 bytes become zero. The run ends with successful=true, and no first-visible-text event is recorded. The zero-byte transformation and retained body provide direct evidence; the diagnosis does not depend only on a missing telemetry event. [E1]

| Evidence | Recorded value | Location in the export |
| --- | --- | --- |
| First assistant content | Only title metadata | conversation.json messages[1].content |
| Provider and reducer output | 31 UTF-8 bytes with matching fingerprints | events.jsonl lines 22 to 24 |
| Render input and transformed output | 31 bytes to 0 bytes | events.jsonl lines 25 to 26 and 29 to 30 |
| Terminal outcome | 31 bytes counted as visible and successful=true | events.jsonl line 28 |
| Finish and usage | stop; 2,485 input tokens; 8 output tokens | conversation.json invocations[0] |
| Effective output cap | 4,096 tokens | conversation.json diagnosticEvents[4].fields |

The output cap was not exhausted: the model used eight output tokens and returned finishReason=stop. Increasing the cap, selecting an NPU, or waiting longer after completion would not repair this recorded output. These two requests used direct preparation, zero tools, no delegated calls, and Local Assist off. Their blank-answer diagnosis does not implicate GGUF, LiteRT, or QNN. [E1]

2 2  First and second request comparison

All times below are measured from the earliest retained run event, not a Send-button timestamp. The events occurred on 17 October 2026 in America/Toronto: the first run begins at 18:33:53.498; the second begins at 18:34:06.479. Invocation metrics use their own start boundary. [E1]

| Metric | First request | Second request |
| --- | --- | --- |
| Run start to HTTP call | 93 ms | 95 ms |
| HTTP call to request body sent | 73 ms | 1 ms |
| Body sent to response headers | 1,141 ms | 621 ms |
| Headers to first provider text | 88 ms | 4 ms |
| Invocation time to first text | 1,321 ms | 646 ms |
| Invocation completion time | 1,419 ms | 752 ms |
| First provider text to first visible marker | No visible answer | 143 ms |
| Run start to first visible marker | No visible answer | 864 ms |
| Provider input and output tokens | 2,485 / 8 | 2,410 / 10 |

The stored invocation durations differ from the finish events by one millisecond, reflecting separate timing boundaries. The successful second answer was “Hi! How can I help you today?” Its first visible marker arrived 37 ms after provider completion. The 143 ms from first provider text to that marker is a useful optimization target, although this export cannot divide it precisely among buffering, parsing, state publication, and drawing. [E1]

About 13.845 seconds elapsed from the first tracked run start to the second answer’s first visible marker. That interval includes the user waiting and submitting another message. It is an end-to-end recovery experience, not provider inference time. Removing the need for that manual recovery is the highest-priority improvement supported by this incident. [E1]

2 3  Other evidence affecting the design

The first request estimates 3,768 system tokens and 17 current-message tokens, totaling 3,785; actual provider input is 2,485 tokens. The second estimates 3,670 and reports 2,410. The selected custom Deep Research prompt is 8,287 characters, and the first assembled system is 11,248 characters. This supports auditing app-generated instructions and optional templates. It does not authorize silently removing the user’s custom prompt. [E1]

The second request includes the first hidden answer in model history, and the conversation still has the title “New Chat” at export. Its first-visible-text event appears three times, at events lines 59, 62, and 65. Canonical history, independent title updates, and idempotent visibility metrics all need explicit acceptance tests. [E1]

The bundle reports 236 dropped journal events, contiguous retained sequences, no export truncation, incomplete provider wire capture, and nontransactional component snapshots. DNS, TLS, connection reuse, gateway queueing, and model prefill cannot be separated from this sample. The first request’s longer network interval is not proof of a cold connection. [E1]

3  Matching failure path in the repository

Current main contains a concrete path consistent with the incident. Two different completion checks inspect text before private subject metadata is removed. UI code subsequently removes the metadata while another representation retains the original timeline entry. Fixing one renderer condition would leave the completion and history defects intact. [R1, R2, R3]

| Component | Current behavior | Implementation change |
| --- | --- | --- |
| ConversationSubject and ChatRepositoryImpl | A first-turn directive requests title metadata before the answer. | Remove title generation from primary answer instructions. |
| AgentRunner collectRound | Any nonblank TextDelta sets textEmitted. | Track semantic answer content after shared normalization. |
| StreamingMessageBuffer hasResponse | Checks text after thinking extraction, without subject removal. | Use the same canonical answer classifier as the runner. |
| ChatScreen and chronological content | Cleaned body and original TEXT timeline can disagree. | Derive both from the same accepted projection. |
| ChatMarkdown and ChatBubble | Late metadata removal produces an empty tree; timeline or controls can suppress fallback. | Make fallback depend on answer outcome, independently of menus and process details. |
| AgentRunCoordinator | Flow completion can commit COMPLETED and emit completion notification. | Validate the terminal payload before success and notification. |

Relevant locations are ConversationSubject.kt lines 7 to 29; ChatRepositoryImpl.kt line 1704; AgentRunner.kt lines 229 to 242 and 643; ApiStateFlowExtensions.kt lines 179 and 194; ChatScreen.kt lines 1182 to 1204; ChatBubble.kt lines 359 to 373; and AgentRunCoordinator.kt lines 464 to 472. All source links in section 10 are pinned to the reviewed commit. [R1, R2, R3]

Current main also buffers up to a 96-character tail in AssistantTextMarkupGuard when no successful web-search result exists. That can defer short replies until finalization. The supplied APK trace shows early reducer chunks, so this source finding must be tested as a current-main optimization opportunity; it cannot be assigned as the measured cause of this particular delay. [R4, E1]

4  Canonical answer and completion design

4 1  A shared answer projection

Introduce one pure Kotlin projection layer in the data pipeline, provisionally AnswerContentProjector. It converts accepted provider events into visible answer parts, private metadata, reasoning, tool state, and diagnostics. The runner, streaming buffer, repository, history builder, export, and UI consume that result. Keep raw provider content separately for diagnostic retention and token accounting.

The projection should carry runId, messageId, attemptId, revision, ownership fence, sequence, projection version, visible parts, metadata, and an outcome classification. Visible parts include prose, valid code, math, supported media, attachments, and explicit user-facing action receipts. Classifications include answer present, metadata only, reasoning only, empty, invalid control payload, and failure. A visible refusal is valid answer content. A tool call in progress is work in progress, not a finished answer.

Use a semantic renderability check rather than raw String.isNotBlank(). A hidden title, whitespace, invisible markup, or a reasoning-only body cannot satisfy completion. A valid image, file, or action receipt can satisfy its task contract without prose. A successful search receipt cannot satisfy a question that still needs synthesis. Menus, copy buttons, retry controls, token statistics, and collapsed debug content do not count as the requested answer. If an artifact has a readable attachment tile, that tile is part of the answer; its absence must not be masked by debug metadata.

4 2  Incremental metadata parsing

Replace duplicated stripping with a bounded incremental parser. It holds only an ambiguous app-owned metadata prefix, emits confirmed answer text promptly, and handles delimiters split across arbitrary chunks. A complete recognized title produces a metadata event; it never becomes answer text or a TEXT timeline entry. At terminal input, incomplete metadata produces an explicit classification and recovery decision.

Parsing must be context aware. Preserve fenced code, inline code, HTML examples, and exact-output requests that legitimately contain the marker. Interpret the reserved leading marker as private metadata only when the request contract or recorded legacy provenance marks it as app metadata. Preserve legacy raw records. For ambiguous historical content without that provenance, avoid destructive rewriting and expose a recoverable display choice. Never strip every HTML comment globally.

Keep normalization linear in newly arrived content where practical. Store parser state and append-only accepted parts; avoid rescanning the full answer on every token. Any safeguard that withholds unparsed tool markup must continue to operate. A speed change must not turn serialized control instructions into normal chat prose or execute them without a valid tool schema.

4 3  Terminal outcome and foreground delivery

| Condition at terminal input | Run outcome | Visible interface |
| --- | --- | --- |
| Valid answer text or supported result | COMPLETED after canonical validation and persistence | Answer or result tile; normal actions |
| Only private title metadata | Recoverable empty-answer outcome | Preparing the answer, then recovered text or explicit error |
| Reasoning only or no answer | Classified incomplete answer; bounded recovery if eligible | Clear progress or failure state |
| Active tool dependency | Continue orchestration | Task progress and completed action receipts |
| Provider error or rejected control payload | FAILED or recoverable error | Reason and Retry action |
| Valid answer generated while backgrounded | COMPLETED; render receipt pending | Display on return; no foreground dependency |

Separate generation success from foreground delivery. The coordinator can validate and commit an answer while no composable is mounted. It must not wait for FIRST_VISIBLE_TEXT to complete a background run. Conversely, a successful HTTP request and a raw body hash are insufficient for functional completion. Notification and title jobs start only after the canonical answer passes validation.

Normalize body, timeline, revisions, and Combined contributions using the same projection version. For historical metadata-only replies, retain the original raw body and recorded usage, classify the display outcome as empty, and show a Retry state. Exclude the metadata-only assistant body from normal conversational history while retaining the associated user request and failure record. Preserve tool receipts and valid attachments.

5  Title generation and bounded recovery

5 1  Title updates after a valid first answer

Remove ConversationSubject.INSTRUCTION from the primary answer prompt. After the first valid answer commits, create a short deterministic title using the existing generateDefaultChatTitle and ConversationSubject.clean helpers, based on the first user request and validated answer. Title selection must not delay visible delivery. Reuse these helpers instead of creating a required extra model call. [R1, R5]

If richer titles are enabled, the existing ConversationTitleSummarizer can run as optional background work after answer delivery. It is a provider-inference component, not a universal on-device summarizer; current code returns no result for LiteRT profiles. Stage the deterministic title as a fallback, then make exactly one final automatic title write when the provider returns or its bounded deadline expires. The default policy writes the deterministic title once and performs no later refinement. Respect local-only and cloud permissions, and do not cold-load or evict the active primary merely to name a chat. [R5]

Persist explicit titleSource and titleGenerationState, with the first valid answer revision and expected title version. Use a compare-and-set update scoped to chat ID. Apply it only while automatic title generation is pending. A manual rename always wins. Later jobs cannot replace a customized or finalized title. Title-only failures do not consume the first-valid-answer opportunity. Restart must resume the recorded state without regenerating a finalized title; cancellation or chat deletion must invalidate a pending job. A title change must not change the activity timestamp or reorder the list.

5 2  One recovery for metadata-only completion

Extend the existing empty-answer recovery path to use the canonical classifier. For this incident class, allow one immediate corrective physical attempt within the same logical run. Remove the app-owned title directive, preserve the original user request and selected profile, and require an ordinary answer. The correction is a generation repair, not a new user message. Record both physical attempts and their actual usage. [R2]

Recovery eligibility must inspect the task and action ledger. For a greeting or a task already satisfied by collected results, finalize from the existing context without repeating tools. If essential information is still missing, continue only the authorized pending workflow. Completed mutating actions cannot be replayed, and uncertain action outcomes must be reconciled before any new execution. Keep tool permissions, ownership, user constraints, source evidence, and model identity intact.

The existing fallback disables tools for empty finalization. Retain that behavior only when the answer can be completed from already available context. Use a distinct metadata-only recovery reason so the app does not globally disable research or tool capabilities to make responses faster. Persist consumption of the one-correction allowance before physical dispatch, so process restart cannot reset it. The recovery shares cancellation, deadline, and spend budgets with its parent and cannot recursively retry itself.

While recovery is active, retain a readable status such as “Preparing the answer.” If the correction also has no valid result, end with “The model finished without returning an answer” and an explicit Retry control. Retry must retain the user message. Never show an empty success bubble, substitute a canned greeting as if the model generated it, or hide the failed attempt’s billed usage.

6  Performance implementation design

6 1  Show the first answer promptly

Extend StreamingMessageBuffer with a first-displayable-answer flag independent of prior progress or reasoning publications. Publish the first accepted answer immediately, then use the existing adaptive update cadence. Add a cancellable trailing-flush timer: a safe pending chunk must appear even if the provider stops sending for several seconds. Coalesce immutable snapshots, never discard accepted answer bytes. Preserve the existing final flush. [R2, R6]

The renderer currently samples streaming preparation at 120 ms and performs parsing on a worker. Give the first displayable fragment an immediate path through the same semantic renderer, then sample later updates according to document size and measured layout cost. Final content remains unsampled. Keep code, math, links, source cards, and Combined wording support. Avoid stacking a full renderer delay on top of the buffer cadence. [R3, R6]

Keep the selected visual style while making new text readable on its first frame. A one-second fade must not postpone readability, and reduced-motion settings should remove motion without affecting content. For long outputs, update only changed blocks and stable UI identities; memoize expensive preparation and keep it out of composable bodies. Android’s Compose guidance supports moving expensive calculations outside composition and limiting unnecessary state-driven recomposition. [R6, W4]

Replace the unconditional 96-character withholding policy with incremental matching of genuinely ambiguous markup or claim prefixes. Flush clearly safe text such as “Hi!” immediately. Preserve cross-chunk detection and successful-tool evidence checks. Test quoted tool syntax and legitimate code samples explicitly. This is a source-based candidate improvement requiring benchmark confirmation on the exact release build. [R4]

6 2  Keep persistence off the stream collector

AgentRunCoordinator publishes live state before awaiting a database checkpoint roughly every 500 ms. A slow checkpoint can still backpressure later stream events. Add one ordered, coalescing persistence writer per run. It receives immutable snapshots identified by message, run, fence, revision, and sequence. Older intermediate snapshots can be replaced by newer ones; accepted answer content and terminal state cannot be dropped. [R2]

Continue persisting the accepted user message and run identity before physical dispatch. Persist the first meaningful answer checkpoint promptly, then checkpoint at a measured cadence. Terminal commit must drain or supersede the latest pending write transactionally, reject stale fences, and record the final outcome with its final answer. Cancellation, app backgrounding, process death, and restart must preserve ownership and partial results. Measure queue age and write duration to prevent an unbounded writer backlog.

6 3  Reuse existing readiness work

The repository already has a shared Ktor/OkHttp transport, a bounded public-origin HEAD warmer, selected-profile readiness, single-flight work, and foreground, network, and hardware guards. Extend this pipeline. Composer focus and selected profiles should trigger useful preparation early, while an accepted Send takes priority and never waits for optional warming. A warm origin connection can reduce setup work; it does not guarantee that the remote model or its cache is warm. [R7]

Add observability for warm attempts, connection reuse, hits, cancellation, and time saved on actual requests. Reuse the same transport that performs inference. Maintain the existing policy of sending no draft or credentials in the origin warm request. Prefer selected profiles, cap speculative concurrency, and preserve metered-network and thermal controls. Do not increase connection counts or fire speculative completions without evidence that they improve the task.

Test the accepted-preview handoff: current code clears speculative memory on availability changes and when the draft is cleared, while submitted requests try to consume the matching preview. Add an atomic claimAcceptedPreview before composer clearing and workload-state invalidation. Transfer an immutable result to the run, then cancel unclaimed draft work. Revalidate memory scope, profile, content digest, and permissions at consumption and dispatch. This is a source-supported race risk, not a measured cause of the greeting failure. [R8]

Extend the existing preparation planner rather than applying a blanket short-prompt shortcut. Keep required tool access, research mode, recovery state, attachments, relevant history, and mandatory delegation. For local models, separate mandatory runtime admission from optional enrichment so a greeting does not force unrelated work. When a tool catalogue is cold, retain authorized on-demand discovery and an honest loading state instead of declaring tools unsupported. [R8, R10]

6 4  Reduce prompt work without changing the task

Build a stable app-owned prompt prefix from versioned components, with dynamic time and turn-specific data placed later. Cache serialized invariant profile instructions and tool schema blocks by revision, and serialize the final accepted payload once. Revalidate the exact final request at physical dispatch; cached data must never bypass credential, permission, memory, history, model, template, or context-capacity checks. [R9]

Audit app-generated boilerplate about unavailable location tools, unrelated research behavior, follow-up formatting, and first-turn metadata. Keep only relevant app guidance while preserving user instructions. Offer a clear profile choice between ordinary chat and the user’s existing Deep Research template; changing that custom template’s scope is an explicit preference. The attached profile’s 8,287-character custom prompt cannot simply be removed as an optimization. [E1]

Where the selected provider supports prompt caching, a stable prefix can improve cache reuse. OpenRouter documents provider stickiness and cache-usage reporting, with support depending on the model and provider. Add an opaque conversation-scoped session key when supported and compatible with the user’s routing policy; use actual cache metrics to evaluate it. Do not assume the Xiaomi endpoint cached these requests, and do not substitute cached final answers for fresh research. [W2]

Preserve requested answer length, reasoning requirements, and tool-result context. The observed 4,096-token output reserve is a ceiling, not a command to generate that many tokens. Reducing it cannot fix a title-only response ending normally after eight tokens. Any output-budget change needs quality and completeness validation rather than a speed-only benchmark. [E1]

6 5  Tune remote routing and tool workflows

OpenRouterProviderRouting already exposes latency and throughput sorting, fallback controls, parameter requirements, price, and privacy constraints. Connect clear performance preferences to that existing model and verify their serialized effect. Use latency preference for interactive starts and throughput preference for longer answers when desired. OpenRouter treats those as different routing goals, and its performance thresholds are preferences rather than guarantees. Preserve the selected model, required features, price limits, and permitted providers. [R11, W1]

Reuse current streaming resilience. Handle keepalive comments separately from answer text, parse arbitrary SSE boundaries, retain usage-only events, and surface midstream errors even when HTTP status is 200. OpenRouter documents those conditions. Heartbeats can maintain a connection without proving answer progress. Use distinct connection, first-answer, tool, and idle-generation timers. Respect rate-limit backoff and avoid retrying accepted output or completed side effects. [W3]

For research, reuse the existing progressive tool catalogue and broker for identical read-only calls. Parallelize independent searches and page reads under per-service limits; deduplicate URLs and duplicate content without losing unique findings. Keep dependent tool calls, writes, and action confirmations ordered. Reuse collected evidence for synthesis and retry. Preserve all enabled capabilities and the user’s selected research depth. [R10]

For Multi Chat and Combined Mode, start eligible profile work concurrently within device and provider admission limits. Display each profile’s progress promptly. Start one final synthesis at the correct readiness barrier, reuse completed profile results, and retain unique facts and attribution. Do not improve the timing metric by silently dropping slower selected profiles, omitting evidence, limiting the final answer, or duplicating synthesis jobs.

6 6  Improve local performance with qualified runtime plans

The current local stack already retains matching engines, validates conversation reuse, and ranks qualified execution plans. The QNN wrapper routes through LiteRT-LM’s Qualcomm dispatch; it is not a general conversion path from any GGUF file to NPU execution. Continue treating GGUF, LiteRT, and compatible QNN artifacts as distinct runtime contracts. Use requested and observed backend evidence when presenting hardware status. [R12]

Add optional preparation for the selected primary local model when the composer is engaged, using the existing governor, worker boundary, memory admission, and local preparation lane. Warm only an eligible artifact with an exact runtime and configuration match. Preparation must yield to accepted generation and must not preload every selected model at once. A helper should not evict a warm primary just to perform optional enrichment.

Benchmark cold load, warm first answer, prefill, decode, sustained performance, memory, cancellation, and tool correctness on the actual device. The existing planner already considers switching cost, p95 visible latency, sustained decode, and matching qualification evidence. Extend its evidence rather than selecting NPU unconditionally. Compare eligible CPU, GPU, and NPU paths on the same workload; preserve quality and tool support. [R12]

Reuse sessions or KV state only when model, artifact, runtime, template, tokenizer, system instructions, history, sampler, reasoning settings, and tool fingerprint match the runtime contract. Invalidate dirty sessions and changed templates. Enable speculative decoding or split execution only for qualified combinations with demonstrated gains. Retain bounded, lossless native-event delivery, safe cancellation, worker recovery, and thermal limits. [R12]

7  Interface and diagnostic requirements

7 1  A clear response state

Every accepted Send immediately creates a visible response area. It shows a concise preparation state, then the first answer as soon as the canonical pipeline provides it. Tool progress remains accessible while work continues. A terminal error is always readable even when retry controls or hidden timeline items exist. A valid answer remains visible during final persistence, title generation, and optional suggestions.

Keep primary controls simple: performance preference, prepare selected models, and response animation. Place provider-specific routing, timing details, recovery history, and local runtime evidence in the existing advanced or debug sections. Explain when a preference affects cost or resource use. Do not expose internal parser names, ownership fences, or event schemas in the normal chat experience.

7 2  Measure useful output at distinct boundaries

| Metric | Definition and rule |
| --- | --- |
| Time to first provider text | Physical invocation start to first text delta; may contain metadata. |
| Time to first answer content | Accepted Send to first canonical displayable answer part. |
| Time to first visible answer | Accepted Send to first foreground draw of the accepted answer; record once. |
| Answer completion time | Accepted Send to validated terminal payload and final persistence. |
| Preparation and transport stages | Queue, normalization, recall, serialization, DNS, connect, TLS, body sent, headers, first SSE event. |
| Output integrity | Raw text bytes, canonical answer bytes, metadata bytes, reasoning bytes, render result and outcome. |
| Recovery cost | Logical run, physical attempt count, reason, total tokens, elapsed time, and replay prevention. |

Place the first-visible latch at the run or accepted-answer delivery level, not in each composable instance. Preserve the earliest event across recomposition, navigation, and block splitting. Capture foreground eligibility separately, so background completion and offscreen content do not create misleading visibility timings. Track generated media and action receipts with a first-visible-result event rather than forcing them into a text-only counter. [E1, R3]

Include the APK commit, runtime revision, provider route when reported, model artifact digest, template revision, network type, device thermal state, and cold or warm classification. Distinguish provider-reported tokens from estimates. Add DNS/TCP/TLS and connection-acquired information to the existing transport listener; do not label headers-minus-body time as pure model computation. [R7, R12]

Protect terminal outcomes, canonical-byte counts, failures, and first-answer events from diagnostic sampling. Lower-priority repeats may be coalesced. Report loss per stream and process, make the manifest’s declared event schema match actual event versions, and retain sufficient boundary fingerprints to locate future content-loss defects. Keep payload capture opt-in and redact credentials. The incident’s 236 reported drops and repeated visibility events justify these changes. [E1]

8  Implementation work packages

The sequence below produces reviewable changes with narrow responsibilities. Role labels describe engineering ownership, not staffing commitments. Complete P0 before using response-time dashboards to claim improvements; otherwise a hidden eight-token reply can incorrectly look like a fast success.

| Package | Owner and scope | Completion criterion |
| --- | --- | --- |
| A  P0 | Core pipeline: canonical projection, runner and buffer guards, terminal validation | Exact title-only fixture cannot produce functional success. |
| B  P0 | Conversation and UI: title decoupling, canonical history, visible fallback | First valid answer displays; title updates once; legacy blank replies have Retry. |
| C  P0 | Orchestration: one classified recovery, action ledger, attempt accounting | Recovery preserves context and causes zero repeated mutations. |
| D  P1 | Streaming and UI: immediate first answer, trailing flush, bounded parser, render cadence | Slow-stream and short-answer fixtures meet display targets losslessly. |
| E  P1 | Storage and diagnostics: ordered writer, terminal drain, idempotent first-visible metric | Restart preserves content; stale writes and duplicate metric events are rejected. |
| F  P2 | Readiness and context: preview claim, prompt components, selected transport preparation | Warm work is reused when valid; required features remain available. |
| G  P2 | Providers and tools: existing routing options, read-only concurrency, evidence reuse | Capability and quality checks pass with measured end-to-end improvement. |
| H  P2 | Local runtimes and QA: selected-primary preparation and qualified plan ranking | Existing local qualification and release gates pass on actual hardware. |

8 1  Source changes by responsibility

Core and recovery: extend AgentRunner, ApiStateFlowExtensions, AgentRunCoordinator, and ResponseRecoveryContext. Add the shared projector and its pure regression fixtures. Preserve raw accounting and existing run fences. [R2]

Conversation and UI: update ConversationSubject, primary prompt assembly in ChatRepositoryImpl, title scheduling, history selection, ChatScreen, ChatBubble, AssistantChronologicalContent, and ChatMarkdown. Retain display support for legacy metadata while preventing new title comments from entering answer content. [R1, R3, R5]

Performance: extend AssistantTextMarkupGuard, StreamPresentationPolicy, the readiness coordinator and preview coordinator, ReadinessHttpTrace, ModelRequestPreparation, existing OpenRouter options, and the qualified local execution planner. Prefer one implementation per responsibility instead of parallel replacement subsystems. [R4, R6, R7, R8, R9, R11, R12]

Benchmarks: add answer-content, paint, blank-success, recovery, stage timing, and write-queue metrics to BenchmarkModels and BenchmarkRunner. Extend the existing Macrobenchmark flows and local RuntimeReleaseGates. Add capability and quality assertions to any speed comparison. [R13]

8 2  Compatibility and rollout

Version the projection and diagnostic schema additively. During migration, preserve original message content, user titles, revisions, tool receipts, and run identities. New code reads older records through a compatibility projection; it must not relabel uncertain historical provider content destructively. New canonical fields can be written alongside legacy data until all readers are migrated.

Ship the completion invariant, visible fallback, and independent titles together. Roll out streaming, asynchronous persistence, prompt scope, and local preparation under separate feature controls with clear defaults. A performance rollback must retain the no-empty-success invariant. Retain the previous complete reader/writer contract so reverting an optimization does not corrupt already stored conversations.

9  Verification and release criteria

9 1  Reproduce the exact incident

Create a deterministic fake-provider fixture with the exact first request contract and title-only response, including finishReason=stop, eight output tokens, and arbitrary chunk splits. Test the runner, projector, reducer, repository, and final UI together. Expect one eligible recovery or a readable terminal error, never COMPLETED with no valid answer. Verify that the hidden title cannot re-enter the timeline or the next request’s ordinary assistant history.

Repeat with a normal greeting, title plus answer, incomplete marker, every marker split position, whitespace-only output, reasoning-only output, a visible refusal, structured JSON, code containing the marker, math, images, files, and action receipts. Keep literal content intact when it belongs to the user’s requested output format. These tests check functional behavior rather than duplicating implementation details.

9 2  Timing and lifecycle scenarios

Use a fake clock and controlled streaming server to pause after the first safe chunk, split SSE frames, send keepalive comments, return usage-only events, and fail midstream after HTTP 200. Validate first-answer publication, timer-based flush, unsampled final content, no lost characters, and no completed-answer status for an error. Exercise slow database writes to verify that the collector and UI remain responsive.

Run foreground, background, screen rotation, navigation away and back, process death, worker death, cancellation, manual title rename, changed permissions, and multi-profile scenarios. The second incident’s three first-visible events must collapse to one per accepted answer. A valid background answer must complete without a mounted renderer. A recovery must not duplicate any completed mutating tool or replace a newer ownership fence.

9 3  Proposed performance gates

Use a non-debuggable, profileable build close to release settings on a real device. Android recommends Macrobenchmark for Compose UI interactions and supplies trace and JSON outputs for analysis. Add release traces around accepted Send, first canonical answer, first draw, and terminal commit. Use controlled provider fixtures to measure app overhead separately from live-provider variability. [W5]

| Gate | Proposed requirement |
| --- | --- |
| False successful blank answers | Zero in all controlled regression cases; alert on any observed occurrence. |
| Accepted Send acknowledgement | p95 at most 100 ms on the controlled reference device. |
| Warm direct Send to HTTP dispatch | p95 at most 150 ms, including required persistence and permission checks. |
| First safe answer to state publication | p95 at most 33 ms at normal load; independent of previous progress updates. |
| First safe answer to foreground paint | p95 at most 100 ms at normal load; no mandatory extra 120 ms sample. |
| Streaming and action integrity | No accepted-byte loss, no stale overwrite, no duplicate mutating tool execution. |
| Functional equivalence | No loss of required tools, answer completeness, research evidence, Combined facts, or local quality. |
| External and sustained performance | Report p50 and p95 by cold/warm, provider, workload, device and thermal class; no universal speed guarantee. |

Collect at least 100 controlled observations per principal performance class after warm-up, and use separate paired live-provider trials where rate and cost policies allow. Report sample sizes, failures, timeout censoring, and confidence intervals. Never exclude blank answers or retries from the experience-level failure and latency accounting. Evaluate cold and warm behavior separately, and record first-answer time alongside full completion and useful-output quality.

Preserve the repository’s existing local qualification gates: 500 controlled turns with at least 99 percent completion, zero crashes and ANRs, 50 cancellation scenarios, 10 worker-death scenarios, 20 memory scenarios, no tool replay, lossless output, MTP and workflow fixtures, quality equivalence, persistence checks, and a p95 first-visible ratio no greater than 1.10 against baseline. Retain the additional gain requirements for experimental execution modes. These are source-defined gates to execute during implementation, not results of this review. [R13]

Approve a performance change only when it reduces measured delay in its target class and passes the functional gates. The first release milestone is dependable visible answers. Subsequent milestones reduce presentation, preparation, and inference overhead while retaining the same task behavior.

10  Sources and trace locations

E1  Supplied diagnostic bundle. export_New Chat_1792276468321.debug.zip. Primary entries: conversation.json, events.jsonl, manifest.json, context-manifests.json, and provenance.json. Key JSON paths: messages[1], messages[3], runs, invocations, diagnosticEvents[2/4/9], featureSettings, and decoded workspace[1/3].payload. Event line numbers refer to the original events.jsonl. Raw title and response fingerprints agree across the boundaries described in section 2.

R0  Repository baseline and contributor guidance

Reviewed commit  \|  AGENTS.md

R1  Title instruction and primary prompt assembly

ConversationSubject.kt lines 7 to 29  \|  ChatRepositoryImpl.kt lines 1700 to 1705

R2  Completion checks recovery and persistence

AgentRunner.kt  \|  ApiStateFlowExtensions.kt  \|  AgentRunCoordinator.kt  \|  ResponseRecoveryContext.kt

R3  Body timeline renderer and fallback

ChatScreen.kt  \|  ChatBubble.kt  \|  AssistantChronologicalContent.kt  \|  ChatMarkdown.kt

R4  Current control markup buffering

AssistantTextMarkupGuard.kt

R5  Existing deterministic and provider title mechanisms

ChatRepositoryImpl.kt  \|  ConversationTitleSummarizer.kt

R6  Streaming update and rendering cadence

StreamPresentationPolicy.kt  \|  ChatMarkdown.kt

R7  Shared transport and readiness tracing

NetworkClient.kt  \|  ProviderTransportWarmer.kt  \|  ReadinessHttpTrace.kt

R8  Readiness preview handoff and preparation

ChatReadinessCoordinator.kt  \|  MemoryPreviewCoordinator.kt  \|  SendPreparationPlanner.kt  \|  ChatViewModel.kt

R9  Final request serialization and admission

ModelRequestPreparation.kt

R10  Progressive tools and shared read work

ProgressiveToolCatalog.kt  \|  SharedToolCallBroker.kt  \|  McpClientManager.kt

R11  Existing provider preferences

OpenRouterAdvancedOptions.kt

R12  Qualified local execution and reuse

LocalExecutionPlan.kt  \|  LocalRuntimeQnnImpl.kt  \|  LocalEngineHolder.kt  \|  LiteRtLmAdapter.kt  \|  LocalEventBuffer.kt

R13  Benchmark contracts and runtime gates

BenchmarkModels.kt  \|  RuntimeReleaseGates.kt  \|  AppPerformanceBenchmark.kt

W1  OpenRouter provider routing  Accessed 17 October 2026.

W2  OpenRouter prompt caching and provider stickiness  Accessed 17 October 2026.

W3  OpenRouter streaming framing and errors  Accessed 17 October 2026.

W4  Android Compose performance best practices  Accessed 17 October 2026.

W5  Android Macrobenchmark guidance  Accessed 17 October 2026.
