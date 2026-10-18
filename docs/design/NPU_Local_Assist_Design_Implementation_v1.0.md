# NPU Local Assist — implementation specification

DESIGN IMPLEMENTATION  /  01
NPU
Local Assist
Multimodal Reading, Safe Tool Use
& Extended Context
One simple helper. Qualified local execution.
Source-linked evidence that reaches the primary AI.
REPOSITORY  tailscale-signin/GPT_Mobile_AI-improved
BASELINE  main · a13b8b5a46ece251b42818ed01e3bf651ac2cc3f
TARGET DEVICE  ASUS ROG Phone 9 Pro · Snapdragon 8 Elite SM8750
DOCUMENT  Version 1.0 · 17 October 2026
STATUS  Proposed specification · implementation and device tests pending
DELIVERY PACKAGE
Repository-ready Markdown and editable Word document.
48 acceptance tests · 8 work packages · qualification-gated rollout

Document map
Use sections 1–4 for product review, 5–13 for implementation, and 14–17 for delivery and release decisions. All proposed changes must be rebased onto the current repository before development.
01  Product outcome and scope
02  Verified baseline and implementation gaps
03  Requirements and invariants
04  Simple settings and user experience
05  Architecture and execution lifecycle
06  Model and accelerator qualification
07  Read-only tools and web research
08  Images, documents and visual evidence
09  Native context expansion and budgeting
10  Retrieval, evidence contracts and handoff
11  Scheduling, performance and failure recovery
12  Repository implementation map
13  Persistence, migration and diagnostics
14  Work packages and dependency order
15  Acceptance tests
16  Performance qualification and release gates
17  Rollout, risks and definition of done
18  References and verification boundaries
Reading note
“Verified” describes the inspected source baseline. “Proposed” describes development requirements. Context tiers, latency thresholds and model candidates are not device performance results.
Suggested repository path
docs/implementation/
npu-local-assist-vision-tools-context-2026-10-17.md

1. Product outcome and scope
Local Assist should reduce unnecessary work sent to a primary AI while improving the information available to it. A user should be able to attach a screenshot, ask about a document, or request research without managing separate accelerator, delegation and efficiency systems. The assistant should use local inference when it is useful and qualified, not simply because an NPU exists.
The product must support three connected capabilities: reading text and images; planning and interpreting approved read-only tools; and handling more material through an appropriate native context plus a searchable evidence collection. A local model does not itself perform an HTTP request or acquire permission to read a file. The host application performs those operations and supplies bounded results.
1.1 In scope
Implement NPU-backed text assistance, multimodal assistance with separately qualified vision and language backends, image and screenshot understanding, selected document-page reading, query planning, web-result evaluation, and supervised search/read cycles. Add artifact-aware context selection, real token accounting, source-preserving chunking, clear readiness states and end-to-end inclusion receipts.
Simplify Local Assist by removing the Execution & Efficiency settings section and its competing policy overrides. Retain the runtime's necessary memory admission, tool authorization, cancellation and crash protection; those are safety mechanisms, not a second user-facing efficiency system.
Support remote-primary conversations first. Extend local-primary conversations only after a safe serialized handoff between the primary session and helper is implemented. An installed local model must never be evicted from an active conversation merely to perform optional assistance.
1.2 Out of scope
Image generation, video understanding, screen-control automation, arbitrary browser JavaScript, autonomous account changes, purchases, repository writes and shell execution are excluded. General-purpose model conversion, unverified rotary-position scaling and arbitrary GGUF-to-NPU conversion are not part of this change.
Do not introduce another search engine marketplace, memory database, MCP transport, background task system or native inference process solely for this feature. Extend existing owners. A separate, restricted document-rendering worker is justified only for parsing untrusted documents.
1.3 Success definition
A successful request has a traceable chain: authorized task, qualified package, actual local execution, validated evidence, and confirmed inclusion in the primary request. Loading a model, proposing a query or producing a plausible description alone is not success.
The expected benefits are fewer unnecessary remote input tokens, better selected evidence and useful local image reading. These are hypotheses to measure, not promised improvements. For tasks requiring an image, a visual description is not assumed to preserve every detail the primary model would obtain from the original.
2. Verified baseline and implementation gaps
The inspection was pinned to the commit on the cover. Source files, not historical design notes, determine the observations below. The code already provides useful foundations; development should extend them rather than implement a parallel stack. [R01–R11]
Area | Verified implementation | Required change
Policy | LocalAssistPolicy v3 defaults to 4,096 context tokens; validation clamps requests to 1,024–16,384. Optional waiting defaults to two seconds. [R02] | Replace the numeric-only ceiling with requested, supported and qualified context decisions. Keep optional waiting separate from actual execution deadlines.
Preparation | LocalAssistPreparation checks artifact metadata, backend, availability and readiness. A fixed-context export uses its declared capacity. [R03] | Prepare against a complete task-specific engine specification and report logical budget separately from fixed allocation.
Vision wiring | LocalEngineSpec defaults isVisionEnabled to false and has a separate vision accelerator. Assist preparation constructs a spec without enabling vision. [R03, R05] | Add multimodal task inputs and explicitly select a supported vision backend before loading.
Warm reuse | Preparation's early reuse check compares model path, language accelerator and loaded health, rather than a newly resolved full vision/context specification. [R03] | Compare normalized engine configuration, artifact identity, vision configuration and required capacity. Do not report a stale text-only engine as vision-ready.
Assist binding | LocalAssistToolBinder wraps approved shareable read-only tools; query planning is separately gated. [R04] | Preserve the wrapper, add explicit task orchestration and prevent a second wrapper from processing its own results.
Primary integration | The reviewed ChatRepositoryImpl call uses allowTools = false, a text task and a warm-only preparation override. Local-primary Assist binding is excluded. [R08] | Add a typed multimodal helper route and host-supervised tool proposals. Do not globally change the flag to true.
Capabilities | LiteRtModelInspector reads context, dynamic-context support, text/vision backends, tool support, SoC and runtime requirements. [R06] | Extend evidence granularity and use it in Assist admission. Unknown capabilities must not silently qualify a new route.
Runtime | LocalRuntimeImpl passes maxNumTokens and a separate visionBackend; its tool callbacks use the host bridge. [R07] | Preserve this separation. Verify language and vision execution independently.
IPC | RuntimeWire v1 includes image bytes. Large serialized messages already travel by an app-private file descriptor, with a 32 MiB transport ceiling. [R09] | Avoid claiming this needs a new Binder workaround. Profile serialization and introduce bounded binary media descriptors only if useful.
Evidence | AssistCoordinator already chunks record/passages, validates selected IDs, preserves original provider data and distinguishes validation from inclusion. [R11] | Extend those contracts to visual regions, summaries, action receipts and precise coverage.
Dependencies | The version catalogue pins LiteRT-LM 0.18.0 and QNN 2.51.0. [R10] | Preserve the audited dependency set unless a separately reviewed change is necessary.

Important correction to the exploratory proposal: 8K, 16K and 32K are potential qualification tiers, not established capacities on this phone. Some legitimate packages use nonstandard capacities, including Google's documented SM8750 Gemma 3 1B package with a 1,280-token context. Preserve exact package values in the interface. [E03]
3. Requirements and invariants
ID | Requirement
LA-01 | One Local Assist policy owns user intent. Removed efficiency settings cannot alter the effective policy after migration.
LA-02 | Every task has explicit input types, source scope, permission epoch, deadline and runtime identity.
LA-03 | Vision, text generation, native tool calling and structured proposals are independently qualified capabilities.
LA-04 | Android or the approved gateway executes tools. Model output never grants permission or carries credentials.
LA-05 | Initial action scope is read-only. Network requests still require privacy and destination checks.
LA-06 | Ordinary remote chat does not wait for a cold optional helper; explicit research may perform visible, cancellable preparation.
LA-07 | User-requested image interpretation is not silently downgraded to text-only inference.
LA-08 | Context admission covers the rendered prompt, images, tool schemas, history, result reserve and output reserve.
LA-09 | Requested capacity, supported capacity, allocated capacity and used tokens are distinct values.
LA-10 | Larger context and backend fallback require compatible artifacts and recorded qualification, not relabeling.
LA-11 | Original evidence, conflicting findings and partial coverage survive selection or summarization.
LA-12 | A primary-request inclusion receipt is required before the UI says Assist material was used.
LA-13 | Permission revocation and conversation deletion invalidate queued work, caches and late results.
LA-14 | Native crashes cannot trigger automatic tool replay or take down the main conversation process.
LA-15 | Every visible setting has a migration test and an execution-path test. Unsupported controls are disabled with a reason.
LA-16 | No latency, energy, NPU execution or token-saving claim is inferred from model names or configuration alone.

4. Simple settings and user experience
4.1 Navigation and primary controls
Use Settings → AI → Local Models → Local Assist as the canonical page. Other entry points should deep-link to the same state rather than duplicate settings. Remove the Execution & Efficiency destination and redirect old deep links to Local Assist.
Present a master Local Assist switch, a Helper model row, a compact Tasks group, Context, Privacy, and Test Local Assist. Place technical details under one collapsed Advanced section. Preserve an existing user's explicit Off choice.
Control | Proposed behavior
Local Assist | Enables only already approved tasks. Turning it off cancels optional work and invalidates undelivered results.
Helper model | Shows an installed compatible profile and capability chips: Text, Images, Tool proposals, Native tools. Chip state includes Unverified or Unavailable where necessary.
Tasks | Read tool results; help research the web; read documents; understand images. Each task requires its own actual capability and source permission.
Context | Auto or a qualified capacity. Show exact values such as 1,280 or 4,224, not only powers of two.
Privacy | Separate local processing, web-query sharing and sharing derived evidence with a remote primary. No combined blanket permission.
Test Local Assist | Uses bundled synthetic fixtures first. Network testing is a separate explicit action. Report load, generation, validation and inclusion separately.

For a new installation, keep network research and remote sharing ungranted until the user approves them. Enabling image understanding must not automatically enable camera capture, screen capture or access to the entire photo library. Use individually selected attachments.
4.2 Readiness and progress
Display Not configured, Checking compatibility, Preparing, Ready, Working, Paused for device limits, or Unavailable, based on events. Show a short reason and one relevant recovery action. A successful load is not a successful vision test.
Examples of truthful detail text: “Text: NPU; image encoder: GPU; verified for this package,” “8,192 requested; this export supports 4,096,” or “Vision execution not verified.” When only the requested backend is known, say so. Do not show a green NPU badge merely because the profile requests NPU.
In a normal chat, use a compact expandable status row, not a blocking popup. Show separate evidence coverage such as “Read 6 pages; 2 skipped” and result delivery such as “Included in answer request.” Completed helper profiles must not create new-message counts in Combined mode.
4.3 Visual design
Reuse the app's theme tokens, outline icons and shared surface components. For menus that use the planned themed-glass surface, use the shared 85%-opacity token rather than a feature-specific rendering implementation. Provide a solid-surface fallback when blur is unsupported, reduced transparency is selected or device pressure is high.
Keep normal text at accessible contrast; never copy a decorative low-opacity profile-label treatment to explanatory text. Include TalkBack labels, scalable text, keyboard focus, at least 48 dp touch targets and descriptions that do not depend on color alone. Verify dark/light themes and narrow screens.
4.4 Advanced settings without conflicting presets
Expose preparation behavior, allowed local fallback, task timeout and context details only when needed. Do not recreate Fast Response, Balanced or Efficiency presets that secretly rewrite several other values. The system may reject work because of heat, memory, permission or an explicit latency contract; it must show that reason without overwriting the saved choice.
5. Architecture and execution lifecycle
5.1 Ownership
The conversation/research controller owns the root request. Existing tool admission owns grants, destination validation, execution identity and shared budgets. Local Assist owns bounded analysis tasks and validated proposals. The local runtime owns native inference and lifecycle. The evidence store owns immutable source snapshots and their access policy. The primary request builder owns the final inclusion decision.
Extend the existing AssistCoordinator, binder, runtime and evidence contracts. Proposed components below are responsibilities, not a requirement to create a large class hierarchy. Combine small components where that reduces complexity without weakening ownership.
5.2 Request sequence
Authorize and classify. Determine whether the task is text selection, visual reading, query planning or required research preparation. Capture the user goal and allowed sources; do not copy an entire private conversation into every task.
Resolve capabilities and budget. Build the exact artifact/backend/context/media plan. Reserve output and result space. Refuse unsupported configurations before native loading.
Prepare or reuse. Reuse only a matching healthy engine. Optional work may use an available warm engine; explicit work may visibly prepare one when ownership permits.
Infer a bounded result. Run text/vision interpretation or produce a structured proposal. The model receives only admitted evidence and a small task-specific schema.
Validate. Check structure, source membership, exact IDs, budget, permission epoch and runtime identity. Reject unsupported or stale output.
Execute approved reads. Only the host can execute admitted search/read actions. Save the receipt and source snapshot before continuing analysis.
Deliver evidence. Return source-linked selections, observations and unresolved gaps. The primary builder confirms exactly what fit into its request.
Finalize and clean up. Record coverage and delivery; release media and native resources. Preserve original evidence even when Assist fails.
5.3 State and identity contract
Task progression is CREATED → AUTHORIZED → PLANNED → PREPARING/QUEUED → RUNNING → VALIDATING → DELIVERABLE → INCLUDED.
Terminal alternatives are SKIPPED, REJECTED, CANCELLED, FAILED and EXPIRED. Execution completion and evidence delivery are separate state dimensions. A completed job may remain VALIDATED_NOT_INCLUDED because the primary request was already built or its context budget was exhausted.
Every event includes root run ID, task ID, subscriber ID where applicable, source snapshot IDs, permission epoch, engine fingerprint and monotonic sequence. A late event cannot reopen a terminal task, change an already dispatched primary prompt or update another conversation's progress.
5.4 Optional versus required work
Ordinary chat must continue without a cold optional helper. Explicit research or an image-specific request may depend on local processing; show that dependency and allow cancellation. On a required visual failure, report that the image was not read, or offer a separately authorized compatible route. Never produce a supposedly image-grounded answer from text-only input.
When a primary request has already started, keep late evidence as a visible supplementary result or an explicitly requested follow-up. Do not quietly launch a second paid primary request.
6. Model and accelerator qualification
6.1 Qualification identity
A qualification record must identify the artifact SHA-256, package format, tokenizer/template and processor identities, model revision, compiled shapes, text and vision backends, context tier, tool protocol, runtime/dispatch versions, native library digest, device SoC, firmware and application build. A material change invalidates affected qualification results.
The inspector already differentiates metadata declarations from device qualification. Extend that model rather than interpreting vision = true or a model name as proof of a working NPU pipeline. [R06]
6.2 Multimodal execution plans
Support verified combinations of NPU language plus NPU vision, NPU language plus GPU vision, and compatible GPU/CPU fallback plans. Display the observed split. The image encoder, projection and language model may have different execution requirements; framework-level support does not establish that a specific export supports every combination. Google's Kotlin interface explicitly separates vision from language backend selection. [E02]
Do not load vision for a text-only task unless a measured reuse benefit justifies retaining an already loaded compatible engine. Do not concurrently keep two helper models resident by default. A two-model design is an optional later experiment after serial model switching, memory peaks and latency are measured.
6.3 Initial candidate strategy
Candidate class | Use in this project | Qualification boundary
Existing installed, verified SM8750 LiteRT-LM text export | Establish the baseline for selection and simple query planning. | A text baseline is not a vision or native-tool qualification.
Compatible multimodal LiteRT-LM package, including Gemma-family candidates | First choice for extending the runtime already integrated in the app. | Require actual package metadata, compatible modality backends and physical-device visual tests. Keep existing crash quarantine intact.
SmolVLM2-2.2B-Instruct Qualcomm candidate | Evaluate visual reading and compact image interpretation. | Qualcomm describes text/image understanding and lists Snapdragon 8 Elite for Galaxy among supported chipsets. This is not proof of an ASUS-compatible LiteRT-LM artifact or native tool support. [E04]
Separate compatible local fallback package | Allow the task to run on GPU/CPU when explicitly enabled. | A QNN-compiled package is not automatically usable on GPU/CPU.

Do not publish an exact SmolVLM context or a default download recommendation until the selected export manifest is verified. A generic Qualcomm AI Hub or Genie package must use an actual supported adapter; it must not be renamed .litertlm or routed through LiteRT-LM merely because it runs on Qualcomm hardware.
6.4 Capability levels
Track Declared, Load tested, Generation tested, Task tested, and Sustained tested. Record failures as well as passes. Recommend a configuration only after the relevant task and sustained tests pass. Text success cannot qualify image reading, and valid JSON alone cannot qualify trustworthy tool selection.
7. Read-only tools and web research
7.1 Two supported model protocols
Native tool mode: Use the runtime's tool interface only when the exact model/export supports it and passes the app's contract tests. Preserve the existing bridged host executor. Any SDK automatic dispatch must still enter that host authorization path; it cannot contact tools directly. Google's API distinguishes tool-capable models and also documents manual tool handling. [E02]
Structured-proposal mode: A model without native tools may return a narrowly defined search/read plan. The host validates the plan and decides whether to execute it. Label this “Tool proposals,” not “Native tools.” A model that cannot reliably satisfy the proposal schema remains an evidence reader only.
The initial implementation should use structured proposals for Assist research. This avoids enabling arbitrary tools throughout generateDelegate. Native mode is a later qualified optimization, not a prerequisite for local web assistance.
7.2 Proposed action contract
Permit only SEARCH_WEB, READ_SOURCE, READ_SELECTED_DOCUMENT, INSPECT_IMAGE and FINISH. Use opaque source/image handles issued by the host. Network URLs should ordinarily come from admitted search results or a user-authorized URL, not arbitrary strings invented by the model.
{
  "schemaVersion": 1,
  "actions": [
    {
      "type": "READ_SOURCE",
      "sourceId": "source-17",
      "purpose": "Check the stated context limit"
    }
  ],
  "stop": false
}
Treat this as a proposed contract, not an existing API. Enforce a discriminated schema: reject unknown action types, extra keys, invalid IDs and fields inappropriate for an action. For SEARCH_WEB, admit only a bounded query string; the host chooses the provider and credentials. Never accept callback URLs, executable text or model-provided permission flags.
7.3 Execution and loop control
Each root request has one physical-call ledger shared by the primary model, Assist, research and gateway activity. Retries and provider fan-out consume the same ledger. Deduplicate by permission scope, execution owner, normalized arguments and source version. Do not merge distinct authenticated contexts merely because their URLs match.
For ordinary Assist, initially allow up to three query proposals per planning result and one bounded search/read wave. Explicit Research mode uses the existing research owner's limits and source goals; Assist must not impose a new hidden five-source ceiling on the whole investigation. Batch sizes and model context determine each local task, not total research scope.
End a loop on sufficient evidence, repeated actions without new information, budget exhaustion, cancellation, revoked authorization or a missing required capability. Invalid structured output may receive one local repair attempt within the same deadline and ledger. Do not repeatedly retry native initialization, unsupported reasoning or invalid tool templates.
7.4 Web acquisition rules
Reuse existing HTTP clients, search providers, parsers, quotas and evidence storage. Search queries can leave the phone even when inference is local. Show which service receives them. Sending a private sentence as a public search query is an information disclosure even though the operation is read-only.
Validate HTTPS destinations and redirects; reject local/private network targets unless they belong to a separately approved connector. Apply resolved-address checks at connection time. Reject file/data/javascript URLs, credential-bearing URLs, excessive redirects, oversized decompression and unexpected content types. Search snippets remain “discovered,” not “page read.”
Prefer extracted HTML text. A website requiring authenticated browser interaction or arbitrary script execution is unsupported in the first release; return the limitation instead of starting an unrestricted browser. Gateway fallback remains a separate explicit permission and must not duplicate a completed client operation.
8. Images, documents and visual evidence
8.1 Input preparation
Accept user-selected images, approved tool images and selected rendered document pages. Validate MIME type and decoded content, inspect dimensions before allocation, normalize orientation, remove unneeded metadata from derived copies and preserve the original source hash. Do not expose EXIF location to search or the primary model unless authorized.
Proposed initial limits are 8 MiB encoded per image, 8 megapixels decoded before preparation, one active visual item, and at most four crops per task. These are safety defaults for testing, not model capabilities. The effective limit is the minimum of application limits, artifact processor limits, context and measured available memory.
Downsample or tile according to the package's image processor. Do not force a universal 224/384/1024 resolution. Preserve text legibility, aspect ratio and crop-to-original transforms. If a valid preparation cannot fit, return a bounded failure with an explicit smaller-region or compatible-backend option.
8.2 Read text before rendering pages
Use the existing document text extraction and page locators where they are usable. Render only relevant pages or regions when text is missing, corrupted or insufficient for diagrams/tables. Android PdfRenderer supports page rendering; untrusted inputs should run in a separate restricted process with seekable descriptors and explicit cleanup. [E05]
Do not assume all Android versions expose identical PDF text APIs. Feature-gate platform-specific functionality and preserve the existing extractor path. The dependency catalogue already includes PDF-related libraries; inventory actual usage before adding another parser. [R10]
For a large document, record selected pages and unread pages. Never say “read the document” after processing a small sample. Password-protected, malformed or restricted files receive a clear status. No bypass of document protection is part of this feature.
8.3 Visual result contract
Produce an image observation with source ID, image hash, page/crop identity, task prompt version, observed content and limitations. Keep transcription separate from interpretation. A diagram description, a numerical reading and a guessed relationship are different evidence types.
For charts, retain title, axis labels, units, legend and whether values were printed or estimated visually. For tables, retain headers and row/column relationships. Unreadable content must remain unknown. Model-generated confidence scores are not calibrated probabilities and must not establish reliability by themselves.
Bounding boxes supplied by the preprocessing pipeline are exact crop coordinates; boxes invented by a model are approximate observations. A transcription cannot pass the existing exact-text verification merely by matching the model's own output. Require independently extracted text or mark it as visually transcribed and unverified.
8.4 Injection and media privacy
Instructions printed in a screenshot or embedded in a web page are source content, not user commands. A vision result cannot trigger a new permission grant, execute a tool or send an image to another service. Treat QR codes and embedded URLs as untrusted data until admitted by the host.
Keep derived media app-private and bounded. Remove temporary crops when the task ends and invalidate retained observations when their source is deleted. Sharing a local summary with a cloud model still shares information from the image; local processing permission does not imply remote-sharing permission.
9. Native context expansion and budgeting
9.1 Four different quantities
Expose requested context, artifact-supported context, engine allocation, and actual/estimated usage. Add a separate qualified recommended context. Dynamic KV-cache allocation is not the same as a dynamically resizable compiled graph, and neither establishes useful reasoning at a larger sequence length.
LiteRT-LM 0.18.0 release notes describe model introspection and on-demand NPU KV-cache growth. This is a useful runtime capability, not permission to exceed an export's ceiling. The repository already pins that release; this design does not require a speculative version upgrade. [E01, R10]
9.2 Context decision algorithm
Resolve the artifact's supported shapes/range, runtime/backend constraints and device-qualified tiers. Estimate the complete task footprint and memory requirement. Choose the smallest qualified context that fits, subject to the user's ceiling and latency policy. For Auto, prefer a measured task profile rather than always allocating the largest tier.
For a dynamic export, choose an admitted supported size no greater than the known ceiling. For a fixed-shape export, allocate a supported compiled shape even when the logical input budget is smaller. Display that distinction; a “2K task budget” must not imply reduced allocation for a fixed 4K package.
For an explicit manual capacity that is unsupported, report the mismatch and present supported options. Do not silently edit the saved preference. Auto may select a smaller safe tier with a recorded reason. If even the minimum instruction/output/image footprint cannot fit, reject before loading or split the task where semantically safe.
Suggested test tiers are the installed artifact's exact baseline, then 4K, 8K and 16K when supported. Treat 32K and above as experimental until an actual export, runtime and device configuration pass the full gates. Never raise the current 16,384 policy clamp alone and call that NPU context support.
9.3 Complete request accounting
Use the installed artifact's tokenizer on the fully rendered request whenever the runtime provides access. Count system instructions, role/template delimiters, current user goal, selected history, tool definitions, tool-call records, admitted tool results, text evidence and image tokens. Image token cost comes from the artifact processor or a conservative calibrated upper bound, not compressed byte size.
Reserve output, any enabled reasoning tokens, the next tool result and validation headroom before admitting evidence. Recompute the ledger after every tool round and before each native call. The authoritative runtime limit remains a final guard even when the planner predicts a fit.
availableEvidence = usableContext
  - renderedInstructionsAndGoal
  - selectedHistoryAndToolSchemas
  - admittedMediaTokens
  - outputAndReasoningReserve
  - nextToolResultReserve
  - safetyMargin
Illustrative 8,192-token budget: 900 instructions/goal, 600 history/tools, 1,024 media, 768 output, 600 next result and 300 safety leave 4,000 tokens for evidence. The values are an example only; no package's image token count is asserted.
When tokenizer/media accounting is unavailable, label estimates, use measured conservative bounds and avoid near-ceiling admission. Track estimation error from actual runtime counts where available. Do not use character count as a universal exact tokenizer.
9.4 Memory and larger exports
Admission must cover text/vision weights, KV cache, native workspaces, prefill buffers, decoded pixels, serialization overhead, existing primary allocations and a system reserve. Check available memory, not only installed RAM. Peak memory during engine replacement must include both old and new allocations until the old engine is released.
For a conventional transformer, KV storage grows approximately with sequence length, layer count, KV heads, head dimension and storage precision. Actual allocation also depends on the runtime and export. Prefer measured peak memory; do not equate a 4-bit weight file with a 4-bit KV cache.
If an export cannot support a larger tier, obtain or build a compatible export using its supported conversion pipeline, quantization/calibration data, tokenizer, positional configuration, vision processor and target runtime. Verify integrity and license, compare against a reference, and test long-context accuracy. No file rename, forced metadata edit or unqualified positional scaling is acceptable.
10. Retrieval, evidence contracts and handoff
10.1 More material without a larger attention window
Store authorized source snapshots outside the active model context. Split text along headings, paragraphs and table boundaries; retrieve relevant spans using the existing lexical/semantic retrieval stack. Use overlap only where continuity requires it and deduplicate overlapped evidence during delivery.
Extend AssistChunkPlanner and AssistHandoffContract rather than introducing a separate chunking framework. Preserve the complete goal and stable global source IDs in every batch. Process additional batches within the root research budget and record unprocessed spans. Existing Assist already chunks selections and leaves the original provider payload intact. [R11]
A 4K helper can examine a much larger collection over multiple tasks, but this is retrieval and staged analysis—not a 100K native context. Do not store source material as permanent personal memory without a separate memory-capture grant.
10.2 Proposed evidence envelope
Field group | Required information
Identity | Evidence ID, root/task IDs, producer invocation, source snapshot hash and runtime fingerprint.
Location | Canonical source ID; fetched URL where authorized; document/page/span or image/crop locator.
Content | Exact extracted text and/or separately marked derived observations; never a summary-only replacement of the source.
Coverage | Discovered/read/partially read/failed; selected and unprocessed spans; visible uncertainty and contrary evidence.
Authorization | Source scope, local processing grant, remote-sharing grant, permission epoch and retention class.
Delivery | Validated, included, excluded for context, revoked or too late; primary request ID and included evidence IDs.

Validate IDs and hashes deterministically. A quotation must match the captured source version; matching text does not establish the truth of its claim. Preserve contradictions rather than selecting only agreeing sources.
10.3 Primary request inclusion
The primary builder may deliver exact excerpts plus a labeled local interpretation. It must preserve source IDs and references in the final source registry. Log the actual included IDs after request serialization; selection and validation do not count as inclusion.
Apply the primary provider's tokenizer and media policy independently. A local result that fits 4K may still be excessive or inappropriate for the remote request. Compute savings only for comparable content and the same provider/tokenizer; record estimates separately from billed usage. Do not fabricate a counterfactual saving for a run that was never compared.
11. Scheduling, performance and failure recovery
11.1 Protect the first visible answer
Keep cold loading outside ordinary remote chat's optional path. A foreground Prepare action or explicit research can warm the helper. Text-only prompts with no useful Assist task should not initialize vision or start speculative searches.
The existing two-second optional wait is a ceiling, not a reason to delay every tool result. [R02] Dispatch the primary immediately when no ready, useful evidence is available. Where waiting is beneficial, consume only the remaining shared deadline; do not add separate queue, load, generation and retry waits sequentially without a root cap.
Do not hold the UI thread or a primary conversation lock while loading models, rendering pages or performing HTTP work. Maintain structured ownership of asynchronous jobs. If all subscribers abandon optional work, cancel it and clean up native resources instead of allowing indefinite hidden work.
11.2 Engine reuse and local-primary handoff
Reuse a normalized engine configuration including artifact digest, text/vision backends and sufficient capacity. A text-only engine cannot serve a visual task. A warm 4K engine cannot be declared 8K-ready because the user changed a setting. Changes apply between tasks and only after native completion is acknowledged.
Initially serialize local inference. For a local primary, implement an explicit yield/checkpoint protocol before enabling helpers; preserve the primary session or rebuild it from authorized context with a visible cost. Keep the current exclusion until that gate passes. Never solve this by removing PRIMARY_RUNTIME_CANNOT_YIELD_TO_HELPER without new ownership behavior. [R04, R08]
11.3 Thermal and memory controls
Use the existing hardware governor and admission service. Refuse new optional work under severe pressure and use recovery hysteresis. Android thermal reporting varies by device, so unknown sensor support must remain unknown; status and qualified headroom readings inform admission, not absolute temperature guesses. [E06]
Recheck memory before rendering, loading and large prefill. Downscope only an Auto task when its declared contract permits it; report the chosen tier/coverage. Do not silently discard a required image, shrink a manual context or reduce the primary model's output budget.
11.4 Failure classification and fallback
Failure | Required behavior
Unsupported package, template, vision backend or tool protocol | Stop before repeated loads; explain the exact incompatibility and keep the artifact unqualified.
Native crash or stuck cancellation | Preserve the native failure identity, stop the worker safely and quarantine the exact configuration. Never replay tools automatically.
Context overflow | Retain evidence; replan only before the next legal request boundary. A smaller task must preserve instructions and required semantics.
Invalid structured output | Reject; permit at most one bounded repair when authorized. No action executes from partially parsed text.
Tool timeout, rate limit or blocked page | Record actual attempt and partial coverage. Respect retry policy and the shared ledger.
Optional helper timeout | Continue without its result; do not mark it used or fail an otherwise healthy primary response.
Required vision failure | Say the image was not read. Use only an explicitly allowed compatible route, or stop that part of the task.
Permission change, deletion or edited prompt | Invalidate pending delivery and affected caches. Never inject a result from the previous scope into the new request.

Fallback is a plan change, not a format conversion. It must select another compatible installed package, recheck permissions and memory, record the new backend and preserve the original error. Cloud or PC fallback requires separate approval; neither is described as on-device NPU execution.
12. Repository implementation map
All Kotlin paths below are relative to app/src/main/kotlin/dev/chungjungsoo/gptmobile/. Existing paths marked verified were read for this document. Proposed names are development suggestions, not claims that these classes exist.
Existing surface | Planned change
Verified: data/​assist/​LocalAssistPolicy.​kt | Version the simple policy; add task scopes, explicit sharing and context selection. Remove efficiency-policy inputs after migration.
Verified: data/​assist/​LocalAssistPreparation.​kt | Accept required task capabilities; resolve the full engine spec; validate warm context/vision compatibility; expose actual prepared capacity.
Verified: data/​assist/​LocalAssistToolBinder.​kt | Preserve approved read-only wrapping; route typed tasks; enforce one Assist owner per workflow and prevent self-processing.
Verified: data/​assist/​AssistCoordinator.​kt | Extend source-preserving analysis to visual evidence, bounded action proposals and delivery states; reuse chunk/handoff helpers.
Verified: data/​repository/​ChatRepositoryImpl.​kt | Add a typed helper invocation instead of text-only delegation for visual tasks. Preserve authorization and optional-response timing.
Verified: data/​localruntime/​LocalRuntime.​kt | Extend task/configuration contracts without breaking existing text calls. Keep media and tool protocols explicit.
Verified: data/​localruntime/​LiteRtModelInspector.​kt | Represent declared and qualified capabilities separately; support exact contexts, modality constraints and processor identity.
Verified: data/​localruntime/​LocalRuntimeImpl.​kt | Prepare supported vision/text combinations; validate output reserve; expose observed stage evidence where the runtime permits.
Verified: data/​localruntime/​ipc/​RuntimeWire.​kt | Keep descriptor-backed transport; add versioned bounded media representation if profiling warrants it; reject version mismatches.
Verified: gradle/​libs.versions.toml | Keep pins; audit any added dependency and optional native payload. Do not bundle downloaded model weights in the base APK.
Integration discovery during WP-01 | Locate settings navigation, effectiveAssistPolicy, resolveAssistPolicy, AssistChunkPlanner, handoff/inclusion consumers, document extraction, research ownership, backup and benchmark call sites. Modify current definitions rather than guessing parallel replacements.

12.1 Proposed small contracts
Prefer a few typed additions: AssistTaskRequest, AssistContextDecision, AssistActionProposal, AssistEvidenceEnvelope and AssistInclusionReceipt. A single resolver may own capability and context decisions if separate classes add no value.
// Proposed contract sketch; adapt to existing source and serialization.
data class AssistTaskRequest(
    val taskId: String,
    val rootRunId: String,
    val permissionEpoch: Long,
    val kind: AssistTaskKind,
    val goal: String,
    val sourceIds: List<String>,
    val imageIds: List<String>,
    val deadlineElapsedMs: Long
)
 
data class AssistContextDecision(
    val requestedTokens: Int?, // null means Auto
    val artifactCeiling: Int,
    val engineAllocation: Int,
    val taskBudget: Int,
    val outputReserve: Int,
    val estimateOnly: Boolean,
    val reason: String
)
The image IDs resolve through an authorized store; they are not arbitrary filesystem paths. Persisted tasks must not reuse an elapsed-time deadline across reboot. Persist relative policy and wall-clock expiry only where necessary, then reconstruct a monotonic deadline for the new process without automatically resuming native work.
12.2 Transport and package discipline
The existing descriptor route avoids sending large raw Binder arrays. [R09] Measure its JSON/image serialization cost before changing it. A versioned binary media descriptor can reduce duplicate copies, but it must enforce length, digest, ownership, decoding bounds and lifetime in both processes. Keep small text commands simple.
Audit package downloads for integrity, archive containment, expected file inventory, runtime format and trust source. Never execute binaries supplied by a model or webpage. Large models and candidate vision packages remain user downloads, not automatic APK payload additions.
13. Persistence, migration and diagnostics
13.1 Policy migration
Introduce Local Assist policy v4 with one authoritative resolver. Migrate explicit enabled state, selected profile, existing task permissions, preparation preferences and manual context intent. Preserve old fields only as deprecated read compatibility; no active read path may let Execution & Efficiency rewrite the new policy.
Use one atomic migration transaction and a version marker. Back up the prior serialized preferences before conversion where the existing settings framework supports this safely. Repeating the migration must be idempotent. Restoring an older backup must not reactivate the removed resolver or expand permissions.
New vision access, autonomous read proposals and remote sharing remain ungranted after migration unless the prior authorization explicitly covers the exact scope. Existing approval to analyze a tool result is not approval to send private attachment text to search engines.
Test every entry point against the same state flow. Test the removed navigation route, restored backups, unknown enum values and a selected helper that is no longer installed. Do not change conversation database versions merely for convenience; add a migration only where schema changes actually require it.
13.2 Persistence and retention
Extend the existing research/evidence storage for source snapshots and derivations. Store qualification results separately from user preferences. On firmware/runtime/artifact changes, mark qualifications stale without deleting the user's selection.
Persist metadata needed for diagnostics, not secrets or unrestricted media. Scope caches by source permission, model/processor version and source hash. Deleted sources invalidate thumbnails, crops, text spans, embeddings and derived evidence; cancellation alone need not delete the user's original attachment.
Temporary conversations do not retain Assist evidence outside the existing temporary-conversation lifecycle. Backup/export follows current explicit privacy sections. Never promote visual observations to long-term memory automatically.
13.3 Diagnostics and export
Record requested and observed text/vision backend separately; qualification ID; context request/capacity/allocation/usage; token estimate method; image/crop counts; load, queue, prefill, vision and decode timings; peak reported memory; thermal state; tool owner and physical attempts; selected/unread sources; failure stage; and inclusion/exclusion reasons.
Distinguish unavailable native metrics from zeros. Status messages and first visible answer have separate clocks. Export standard benchmark summaries normally; include detailed per-stage traces only when Debug mode is enabled and the user explicitly exports them. Redact credentials, private query text, attachment paths and source content by default. Do not dump private model reasoning into general logs.
14. Work packages and dependency order
Work package | Priority / dependency | Implementation and exit condition
WP-01: Baseline and contracts | P0 / none | Rebase against current main; inventory actual settings and handoff owners; add failure fixtures; agree on permission and typed-task contracts. No duplicate architecture.
WP-02: Simple policy migration | P0 / WP-01 | Implement v4, remove competing efficiency route/resolver, redirect old navigation and prove backup/idempotency behavior.
WP-03: Capability/context preparation | P0 / WP-01 | Full-spec warm reuse, per-modality qualification, exact-context admission and truthful readiness. Unsupported loads fail early.
WP-04: Multimodal reading | P1 / WP-03 | Typed image path, bounded preprocessing, selected-page rendering, visual evidence and deletion behavior. Ship only qualified routes.
WP-05: Supervised research actions | P1 / WP-02, WP-03 | Schema-constrained proposals, host-owned execution, one budget/owner, bounded search/read cycles and injection/privacy tests.
WP-06: Retrieval and inclusion | P1 / WP-04, WP-05 | Extend existing chunking and handoff, preserve contradictions, add exact inclusion receipts and partial coverage.
WP-07: Settings and observability | P1 / WP-02–WP-06 | Clean shared UI, task tests, capability details, standard/debug exports and accessible states.
WP-08: Device qualification and rollout | P0 release gate / all prior | Complete Android CI, real SM8750 tests, context tiers and sustained tests; enable qualified subsets independently.

Local-primary yielding, a second resident helper model, native-tool-mode optimization and 32K+ are follow-on work packages behind their own gates. They do not block a useful remote-primary release, and must not be advertised as shipped with the initial scope.
Recommended implementation order is contracts and tests first, then policy and context correctness, then vision, host-supervised research, handoff, UI and qualification. Coordinate overlapping changes to ChatRepositoryImpl, settings and runtime files before implementation; this document does not reserve a branch or supersede newer fixes.
15. Acceptance tests
The following cases are required fixtures or device checks, not tests executed during this document task. Use synthetic tools and bundled original images/documents for deterministic CI; keep live network tests separate.
15.1 Policy, authorization and tools
Test | Scenario and expected result
T01 | Migrate v3 with Assist Off: v4 remains Off; no load or network request occurs.
T02 | Old efficiency preferences conflict with saved Assist values: new values survive unchanged; only one resolver is active.
T03 | Restore old backup twice: migration is idempotent and cannot add image, network or cloud grants.
T04 | Permission revoked during generation: reject the late output and invalidate affected cached delivery.
T05 | Chat-only model returns a valid proposal: host may admit it only in qualified proposal mode; native-tool support remains false.
T06 | Unknown keys, action types or source IDs: reject the entire invalid action; no partial execution.
T07 | Page/screenshot tells the model to upload secrets: source remains data; no new grant or outbound disclosure.
T08 | Private content becomes a search query: deny without the appropriate query-sharing authorization.
T09 | Primary and helper request the same authorized read: one eligible physical acquisition; individual delivery identities remain intact.
T10 | Client and gateway both offer a tool name: resolve one approved owner; no mixed-owner duplicate execution.
T11 | Provider fan-out or retry: every physical attempt consumes the root ledger and retains its result status.
T12 | Redirect to a private address or oversized compressed body: block acquisition before content enters inference.

15.2 Vision and media
Test | Scenario and expected result
T13 | Visual task uses a warm text-only engine: reject reuse or legally rebuild; never claim image reading from text-only execution.
T14 | NPU language with GPU vision: UI/diagnostics show both actual stages, not all-NPU execution.
T15 | Vision declaration is unknown or false: ordinary automatic vision is unavailable until independently qualified.
T16 | Rotated screenshot, tiny text and transparent image: processor preserves orientation/content or returns explicit unreadability.
T17 | Image bomb, malformed media or excessive dimensions: bounded rejection before large allocation.
T18 | Multi-crop table/chart: preserve crop coordinates, headers, units and overlap deduplication.
T19 | Generated transcription matches itself: it is not promoted to independently verified exact source text.
T20 | Mixed text/scanned PDF: extract usable text, render only needed pages and report unread coverage.
T21 | Password-protected or malformed PDF: renderer fails safely; primary process and original file remain intact.
T22 | Delete attachment during processing: release derived media and prevent later inclusion.
T23 | Vision fails on a required image question: no image-grounded answer is fabricated; fallback needs authorization.
T24 | Large media IPC: use bounded descriptor transport, reject excessive payloads and close descriptors on every exit.

15.3 Context, execution and evidence
Test | Scenario and expected result
T25 | Fixed 4K export with 8K request: no invalid initialization; explain required compatible export.
T26 | Dynamic 8K export with 4K Auto plan: admitted setting, allocation and observed usage remain distinct.
T27 | Exact 1,280/4,224 capacity: retain the real package limit; no incorrect rounding up to a power of two.
T28 | Warm 4K engine after 8K preference change: no false readiness; rebuild only at a safe ownership boundary.
T29 | Tool schemas/images/output reserve push request over capacity: replan or reject before inference.
T30 | Actual token count exceeds estimate: record error, prevent further overflow and tighten the estimator's qualification.
T31 | Source collection exceeds context: process permitted chunks, preserve the complete goal and report unprocessed spans.
T32 | Selection includes a nonexistent or altered source span: validation fails without replacing original evidence.
T33 | Contrary evidence appears in separate chunks: preserve it in the handoff and evaluate it in fixture scoring.
T34 | Validated evidence does not fit the primary request: mark excluded, never included or used.
T35 | Evidence completes after primary dispatch: no silent prompt mutation, duplicate paid call or cross-turn injection.
T36 | Conversation changes during shared work: subscribers receive only evidence permitted for their own scope.

15.4 Lifecycle, UI and rollout
Test | Scenario and expected result
T37 | Cold helper on ordinary remote chat: primary dispatch does not wait for loading.
T38 | All optional subscribers cancel: shared job cleans up; terminal status remains stable.
T39 | Native worker dies after a tool receipt: retain the receipt and partial evidence; never replay the action automatically.
T40 | Severe thermal/memory pressure: refuse optional work; recover with hysteresis and no hidden setting changes.
T41 | Local primary owns runtime: helper remains disabled until the explicit yield protocol passes its separate gate.
T42 | Runtime, firmware or artifact digest changes: stale qualification cannot retain a verified badge.
T43 | Dark/light themes, large text and TalkBack: controls remain readable and operable; states do not depend on color.
T44 | Combined-mode helper completes: no new-message count; one correctly attributed Assist receipt.
T45 | Standard versus Debug benchmark export: only authorized diagnostic detail appears; secrets/media are not exported by default.
T46 | Feature kill switch or rollback: original evidence and chats survive; new Assist jobs cannot start.
T47 | Process restart/reboot: do not reuse elapsed deadlines or auto-resume sensitive work; saved settings remain intact.
T48 | Unsupported UI control: disabled with an actionable reason; toggling every supported setting changes its actual execution behavior.

16. Performance qualification and release gates
16.1 Test matrix
Use the actual ROG Phone 9 Pro as the primary device. Record build fingerprint, SoC, app version, artifact digest, runtime/dispatch versions, RAM available before each trial, charging state and thermal condition. Test the minimum supported Android version through compatibility/instrumentation coverage and at least one non-NPU fallback device where available.
Compare Assist Off against text-only Assist and multimodal Assist on identical fixed sources. Test warm and cold starts separately, single chat and Combined mode, quiet and constrained-memory conditions, and every supported native context tier. Do not compare different source collections as a claimed runtime speedup.
Use at least 30 representative paired tasks with repeated runs for comparative latency/quality, plus 100 lifecycle cycles and a 20-minute sustained session per candidate recommended configuration. These are proposed initial release gates, not proof of a universal failure rate. Keep all failures in the results.
16.2 Metrics and proposed gates
Metric | Gate or reporting rule
Security and permission | Zero unauthorized reads, writes, outbound disclosures or automatic tool replays in the required test suite. Any failure blocks release.
Lifecycle correctness | No main-process crash, leaked task or stuck worker in the required cycles. Preserve native failures; a load pass is insufficient.
Ordinary no-benefit chat latency | No cold-helper wait. Paired p95 first-visible-answer regression must be no worse than the larger of 100 ms or 5% before enabling automatic assistance.
Required research/vision latency | Report preparation, time to first usable evidence, first visible answer and total time separately. Do not compare status text with answer tokens.
Task quality | Predefine ground-truth checks for numeric/table reading, source selection and required facts. Automatic selection must show no more than a two-percentage-point absolute loss against the approved reference set.
Tool proposals | At least 98% schema-valid proposals and 95% correct allowed actions on the versioned fixture set; every unsafe action must be rejected. Report denominators and uncertainty.
Context fidelity | Boundary cases fit with required reserves; long-range fact/contradiction checks pass at each advertised tier. Maximum-load success alone is insufficient.
Memory and heat | Stay within the measured admission reserve; no pressure-related process loss in the qualification run. Report peaks and sustained throttling rather than one short speed sample.
Remote-token impact | Report actual primary input usage where available, estimates separately. Recommend automatic selection only when paired results demonstrate useful savings or quality gains without unacceptable latency.
Energy | Measure comparable sessions. Do not claim reduced battery use from NPU selection or tokens/second alone.

The percentages above are design targets for an initial fixture suite and may be tightened during review; they are not current results. Report quality and reliability per task/capability rather than averaging a broken vision route into a passing overall score.
16.3 Build and physical-device gates
Run the repository's current unit tests, lint, resource/schema checks, native packaging and provenance checks, followed by instrumented tests and the fixture suite. Confirm the exact Gradle/CI commands during WP-01 rather than bypassing failing checks or weakening verification.
Validate signed release packaging, ABI/native alignment, parser isolation, cancellation, app background/foreground behavior and real image/tool round trips. A green PR is necessary but does not certify NPU performance. Record native-stage observations and unknowns in the qualification artifact.
17. Rollout, risks and definition of done
17.1 Staged enablement
Release the simple policy and truthful capability/context display first. Enable text assistance, image reading, supervised research and extended context independently through qualification-backed feature gates. A failed experimental 16K route must not disable a working 4K reader.
Default new capabilities to ungranted/unqualified. Let an explicit Test action establish eligibility for the installed package. Recommend 8K only when it benefits the tested workload; do not silently change all existing profiles. Keep 32K+, concurrent helpers and local-primary yielding visibly experimental until their own gates pass.
Provide a feature-level kill switch that stops new Assist work while preserving ordinary chat and existing evidence. Prefer forward-compatible disabling to database downgrade. Rollback must not revive removed efficiency overrides, restore revoked grants or delete source material.
17.2 Principal risks and mitigations
Risk | Mitigation
No compatible NPU vision package for the desired model | Keep model selection open; qualify a real compatible package or an explicitly allowed mixed-backend plan. Never fake support with labels.
Larger context reduces speed or triggers memory pressure | Select the smallest fitting qualified tier; retain chunking and measured admission.
Search/image content injects instructions | Treat sources as untrusted data; validate proposals and enforce permissions outside the model.
Small model omits key or conflicting evidence | Preserve originals, evaluate coverage/contradictions, and retain an evidence-without-Assist path.
Parallel repo work changes integration points | Rebase implementation and repeat call-site inventory; keep source observations pinned to this document's baseline.
A “successful” helper result never reaches the primary | Require inclusion receipts from the actual serialized request.
Added models/renderers inflate APK or peak memory | Download models separately, reuse dependencies and profile media transport/rendering.

17.3 Definition of done
The change is complete when the removed efficiency system no longer overrides Assist; the same policy drives every entry point; an eligible visual task reaches a correctly prepared model; permitted research actions execute through one host-controlled owner; source-linked evidence is verifiably included; unsupported contexts/backends fail before unsafe work; all required tests and the device matrix are recorded; and the UI makes no unsupported capability or performance claim.
The implementation PR must include updated developer/user documentation, migrations and fixtures, before/after measurements, screenshots of actual UI states, native qualification records and clearly labeled remaining experimental routes. Do not merge or publish automatically as part of creating this document.

18. References and verification boundaries
Repository evidence: All R references are pinned to a13b8b5a46ece251b42818ed01e3bf651ac2cc3f. File locations and summarized behavior were inspected through the connected GitHub source. This was a targeted design review, not a full repository audit. No Android build, emulator run, physical-device benchmark, model download or app implementation was performed for this document.
External evidence: Official primary documentation was checked on 17 October 2026 in the project's America/Toronto timezone. Model availability, upstream API details and device support must be rechecked when implementation starts. External framework support does not certify the project's installed artifact or ASUS device.
Repository references
R01 — Reviewed main commit.
R02 — LocalAssistPolicy.kt.
R03 — LocalAssistPreparation.kt.
R04 — LocalAssistToolBinder.kt.
R05 — LocalRuntime.kt.
R06 — LiteRtModelInspector.kt / LocalModelCapabilities.
R07 — LocalRuntimeImpl.kt, particularly engine configuration and conversation/tool setup.
R08 — ChatRepositoryImpl.kt, Assist binding at lines 1829–1888.
R09 — RuntimeWire.kt.
R10 — Version catalogue.
R11 — AssistCoordinator.kt, selection, chunking and handoff at lines 225–319.
External primary references
E01 — Google LiteRT-LM v0.18.0 release notes: model introspection and on-demand NPU KV-cache growth.
E02 — Google LiteRT-LM Android/Kotlin guide: separate modality backends, multimodal inputs and native/manual tool handling.
E03 — Google LiteRT-LM NPU setup: SoC-specific Qualcomm packages and declared contexts. Example SDK versions in that guide are not instructions to downgrade the repo's audited pins.
E04 — Qualcomm SmolVLM2-2.2B-Instruct model page: candidate model purpose and listed device/chipset families; not project-specific qualification.
E05 — Android PdfRenderer reference: page rendering, descriptor ownership and restricted-process guidance for untrusted documents.
E06 — Android Thermal API guidance: thermal status/headroom behavior and device limitations.