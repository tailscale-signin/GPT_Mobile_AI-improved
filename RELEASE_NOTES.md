# Android 0.9.66.13 — smoother voice chat and reliable execution

Version code: **147**. Coordinated publication with Gateway **18.1.1**; retains the Gateway 18 contract.

## Recovery and request consistency

- Fix a verification crash when GitHub evidence contains missing or malformed timestamps. Keep unread saved pages eligible for recovery after an invalid cursor call.
- Show token shortfalls after generation and offer a valid discovery query for an unavailable URL or evidence tool. Incident reports now distinguish unsent budget and prerequisite refusals and record the single-pass Combined repair stop when observed.
- Reject altered saved tool-result bytes with the typed recovery-integrity error, without fabricating observed receipt counts or digests.
- Apply the same evidence requirements to requests beginning with “Please,” preserving their original wording and source references.

## Voice chat and captions

- Give microphone and typed voice messages a dedicated conversational response policy: answer first in short natural sentences, ask one useful clarification when needed, and retain the selected profile's facts, permissions and privacy rules across queues, retries and recovered turns.
- Keep provider reasoning and explicit reasoning blocks outside the spoken answer, including split streaming tags, literal delimiters inside reasoning and incomplete reasoning. Preserve quoted and code examples in the answer, and keep each streamed choice's parser independent.
- Show the same spoken-answer projection used by playback and captions, with expandable visual details and an explicit **Read full details** action. Ordinary replay stays concise, and selecting a different message cancels the previous output.
- Improve pronunciation of dates, clock times, signed amounts, measurements, rates and IP addresses while retaining exact caption source ranges. Remove raw URLs and table structure from ordinary speech, preserve descriptive link labels, and retain the full original answer for inspection.
- Add a **Natural Speech** preset with a 1.1× rate, natural pauses and adaptive phrase pacing. Keep saved choices and the faster preset; prefer complete opening clauses with bounded fallback while speech is generated.
- Correct live transcription word ranges when recognition replaces an indented phrase, and retain paragraph shaping for supplementary right-to-left scripts during streaming.
- Keep a continuous five-line caption view with stable line identities, smooth position/scale/opacity changes, manual scrolling and Return to live, while retaining text selection, accessibility and reduced-motion behavior.
- Keep readable answer previews visible and retain the reader's position when audio begins. Start each new reply or explicit replay with its own caption presentation, rejecting stale playback cursors even when the same text is replayed.
- Fade spoken words into the theme colour over 250 ms and release their colour over 120 ms; keep only the current word bold and reuse glyph geometry so emphasis does not shift or rewrap neighbouring words.
- Drive captions from monotonic presented-audio frames using validated AudioTrack timestamps with playback-head fallback; freeze progress during pause/refill, bound interpolation by accepted audio and retain route and reply-generation fences.
- Reserve accepted PCM against measured next-phrase synthesis time within a bounded 5.9-second reserve inside the existing six-second PCM queue, preserve natural first clauses, and align follow-up phrase targets to Quality 24 words, Balanced 16 and Fast 12 with adaptive Auto pacing. Begin playback when all queue slots are occupied, including when short phrase tails leave its duration below the target.
- Separate first visible text, publishable phrase, queued speech, prepared PCM and presented audio timings; distinguish warm-at-request from engine reuse and show measured buffering, refill and effective-provider evidence.
- Save a frozen voice measurement report through the document picker with bounded timing series and selected/effective backend evidence, excluding conversation text, audio and device identifiers. Correct outer-caption row spacing during fractional transitions and explain the shipped English voice and measurement boundaries.

## Kokoro GPU, NPU and recovery

- Choosing a speech engine reuses its saved measurements. Compare and Recheck explicitly refresh the measurement on the next reply.
- Repair Kokoro QNN partition proposals with a convex, static-interface graph adapter; select the concrete GPU or NPU device and keep dynamic duration/alignment sections on CPU within the same audio-and-timing inference.
- Qualify backend execution from valid PCM, aligned durations and actual kernel profiles for each prepared token shape; retain bounded device/model/runtime/lexicon/thread-scoped capability receipts and explicit rechecks. Auto retains CPU unless measured accelerator cost, including preparation, is at least 10% lower.
- Retire a failed accelerator and retry only its current unplayed phrase on CPU; preserve typed source, target, outcome and original failure when CPU or permitted Android system speech recovery occurs, including optional Gateway CUDA failures. Fence duplicate and stale fallback callbacks.
- Move deferred speech preference reads off the UI path and retain explicit cancellation origins, bounded cleanup and separate synthesis/playback ownership.

Capability receipts are limited to 24 entries. Successful measurements expire after seven days and failed qualifications after six hours; clock rollback invalidates the receipt. **Compare engines on next reply** measures CPU, GPU and NPU with three phrase shapes, including preparation. Saved comparisons are bound to that workload and scoring protocol; older two-phrase measurements cannot influence the current Auto ranking. **Recheck engine on next reply** explicitly retries the selected backend. Per-shape profiles identify actual accelerator and CPU participation; selecting an engine alone does not qualify it.

## CPU, GPU and NPU speech benchmark

- Run **Quick** or **Full** comparisons from Voice settings using the same fixed short, medium and long samples for every selected processor. Keep cold session/graph preparation separate from warm synthesis, and show first PCM, real-time factor, median and p95 results.
- Report requested versus actual execution, accelerator and CPU kernel counts and timings, thermal state and typed failures. Mixed CPU/accelerator work is visible; CPU fallback cannot count as a successful GPU or NPU result.
- Stop from settings or when leaving the foreground, release native resources, and pause live voice while a benchmark owns the runtime. Save the latest five reports and export bounded JSON without conversation text, audio or exported device identifiers.
- Bind comparison results to the model, runtime, voice, rate, thread setting and app build. Show configuration changes and a measured recommendation; keep the normal Auto selection gate and its minimum 10% measured benefit.

The speech benchmark measures silent inference. First PCM is the completed phrase's first returned audio buffer, not sound reaching the speaker; acoustic latency, playback underruns, battery consumption and hardware utilization percentages remain unmeasured. GPU/NPU results require a compatible real device and actual accelerator execution; unsupported or failed trials retain their observed status.

## Setup, model routes and recovery

- Profile details keep the route shown in the picker. Research source switches and citation import stay available if activity history fails to load. Research presets, report options and feature settings respect the editor’s read-only and saving states.
- Distinguish saved configuration and downloaded weights from a verified complete response; guide the first chat and expose existing response checks from profile settings.
- Show configured phone inference, recognized cloud APIs, observed Gateway routes and other network services separately. A private address or engineering Gateway binding does not establish where a different inference endpoint runs.
- Separate requested local processors from native execution receipts. Failed, recovering and released runtimes do not display retained receipts as current readiness.
- Explain unavailable research adapters, document text recovery and backup transfer boundaries alongside the existing controls, including credentials, model downloads and device-specific NPU packages.
- Apply built-in tool changes atomically across settings and imports. Consolidate duplicate built-in grants within selected restore data while preserving other profiles, tools and connection-backed bindings.
- Retain saved Gateway transcripts when a linked local room is deleted. Reopening stops before network access while the original conversation's privacy policy is unavailable; a missing room cannot be assumed ordinary or silently assigned to a new conversation.

## Task efficiency and provider consistency

- Expanded benchmark evidence keeps one copy of each summary and reveals the supporting measurements below the disclosure control.
- Centralize provider session creation across ordinary chat, Research, delegated work and benchmarks while preserving the selected profile, request constraints, authorization and accounting.
- Allow explicitly Automatic preparation to avoid optional helper setup for narrowly identified greetings and short inline text transformations. Explicit helpers, reviews, Research, plans and local-first ownership retain their accepted work.
- Keep two contributors for routine tasks only when adaptive Combined participation is already enabled; preserve explicit participants, required diversity, accepted plans and hard budgets.
- Report elapsed effort and configured API cost per successful benchmark fixture, including unsuccessful attempts and work skipped after dispatch. Preserve unknown timing, price, retry and participant usage instead of displaying partial totals as complete costs. Expose the measurement scope and exportable evidence; these measurements do not establish real-world task quality or physical-device savings.

## Repository map on this phone

- Open a revision-bound file and folder map directly from GitHub without configuring Gateway. Tree preparation and cached-file scanning run off the UI thread. Search files and indexed symbols, inspect source links, and explore caller, callee, path and impact candidates in the existing map interface.
- Build source relationships progressively on the phone using the encrypted source cache and reusable per-blob index. Each foreground session advances through bounded batches, reports coverage, supports Pause and resumes saved progress at the same revision.
- Distinguish observed tree structure from lexical symbol, import and call candidates. Keep unsupported files, missing sources, truncation and partial coverage visible instead of presenting an incomplete map as compiler-resolved analysis.
- Make the phone map available to authorized native GitHub model tools without requiring an engineering Gateway or persistent engineering memory. Account, connection, profile, permission and revision checks apply to cached reads and pagination as well as downloads.
- Preserve Privacy Mode rejection and recovery guidance in the map. Record whether any GitHub request actually left the phone, retain earlier completed reads, and stop queued tool authority when the policy rejects a request.
- Retain Gateway as an explicit optional source for its additional analysis and durable background indexing. Source switches discard stale results and cursors, and failures never silently change the selected provider.
- Preserve that selected source in saved-job status checks, uncertain-index reconciliation and continuation recipes. Phone observations cannot consume Gateway cursors or finish Gateway jobs; a typed retained Gateway uncertainty receipt stays bound to its original source.
- Keep automatic phone-map checks within the available native actions. Additional contract, diagnostic and knowledge analysis remains available when Gateway is explicitly selected.
- Preserve GitHub cooldown timing and earlier request evidence when a phone-map operation stops locally. A confirmed HTTP response remains observed, and local indexing never acquires Gateway write-reconciliation claims.
- Classify Gateway map privacy rejection using actual request evidence. A proven unsent index attempt clears only its own checkpoint; earlier responses, interrupted exchanges and unresolved jobs remain recorded.

## Execution, GitHub and diagnostic reliability

- Compile compound engineering requests into stable source-linked acceptance criteria with explicit dependencies and evidence producers; preserve original scope and budgets when refining saved contracts.
- Require actual pinned source content, complete instruction coverage, a changed regression at the tested revision, complete diff coverage and the matching live draft pull request before their individual obligations can pass.
- Bind provider cooldown recovery to the complete atomic durable tool journal independently of compacted provider replay; retain local projection identity, original failure provenance, settled-write replay and unknown-write reconciliation.
- Validate each retained projection hash against the saved result body before recovery, replay or an explicit budget retry. Older entries without this hash retain their existing compatible recovery path; changed saved results cannot authorize another dispatch.
- Keep task acceptance, delivered responses and output attempts independent; reconcile same-task recovery refusals and require observed scheduler ownership instead of inferring a live executor from saved state.
- Execute every currently admissible GitHub checklist lane, discover exact missing schemas within the accepted budget, retain candidate admission reasons and correct a proven legacy invalid status recipe once.
- Route instruction discovery through native path search, replay exact saved continuation selectors, convert search receipts to pinned source reads and derive CI run/job selectors from observed identities.
- Request one bounded replan after three unproductive source rounds or sixty seconds of unproductive work while preserving exact incomplete search frontiers and useful source progress; continue eligible work beyond three correction rounds within the original finite budget.
- Accept an observed empty Copilot task collection without confusing it with malformed or incomplete responses, and preserve exact comparison, CI, reserve and optional-service evidence through full paged diagnostic export.


- Complete comparison coverage when GitHub pages arrive out of order; retain the original failed page receipts and exact remaining fragment obligations, and honor the requested bounded comparison page size.
- Retain the requested comparison sample when its page size would require more than 512 pages. Report incomplete coverage and offer an explicit restart of the same pinned comparison with a sufficient page size when possible; never issue an unusable continuation or silently change the request.
- Distinguish full tool-result delivery from compacted evidence summaries. Use exact delivered-content proof to resume saved pages across restarts without skipping unread fragments or changing an explicit reread.
- Plan stage-aware output capacity with a visible-output target and allow one materially changed empty-output repair. Preserve explicit reasoning choices, accepted context limits, durable provider waits and unknown-operation reconciliation; effort-only provider APIs cannot guarantee a fixed visible-token reserve.
- Split oversized finite source reads into exact windows of at most 2,000 lines within the eight-range contract, preserving the requested tail and original invocation. Invalid actions and unsupported search fields receive schema-specific correction guidance.
- Separate complete authorized catalogues from missing representative live reads, keep unobserved verification reserves unknown, classify application guards separately from provider failures, and preserve hash-only identity for original uncertain operations.
- Report a complete empty failure query for the selected commit as zero observed failed workflow runs. Keep historical failure observations distinct from the latest workflow and do not infer passing required checks or newly executed tests.
- GitHub choices retain separate drafts as you page between them. On narrow screens, options and actions wrap, and expanding a choice reveals the full question. Gateway host inspection reports an observed missing firewall rule without requiring properties of an absent rule.
- Separate immutable approval, classification and recheck records and preserve typed local persistence conflicts with safe correlation.
- Correlate provider retry checkpoints and permit one durable bounded repair for reasoning-only or empty completions while preserving unknown-write reconciliation.
- Issue larger exact source-page alternatives, retain path/query/branch facts through compaction, and move repeated exploration toward implementation milestones.
- Preserve exact review-comparison page chains and explicit deadline-renewal evidence; correct invalid fuzzy-search requests.
- Use conservative reasoning effort for mechanical issued-cursor rounds while retaining selected behavior for coding, review and explicit choices.
- Classify HTTP and streamed provider failures consistently, make exhausted credits action-required, and preserve transport and upstream error identity.
- Share local Gateway transport cooldown across aliases, support explicit reconnect, and scope MCP catalogue invalidation with actionable cache-miss evidence.
- Keep one compact active GitHub decision above the composer, support queued detail, and answer notification choices through durable identity-checked actions.
- Remove the overlapping red unread badge, add themed Gateway cloud indicators, and improve bounded Markdown/cache rendering with capture allocation evidence.
- Retain explicit provider-wait refusal and scheduler outcomes, non-overlapping work/wait timing, and closed diagnostic evidence across restart and report submission.
- Make immutable evidence writes atomic with explicit stored, duplicate, rejected-owner and conflict results; preserve completed comparison coverage across duplicate reads.
- Retain exact search frontiers and typed rule failures, match replanning to task intent, and explain missing acceptance contracts without treating zero-of-zero as complete.
- Isolate transcript row updates, profile per-row layout, index diagnostic paging with Room migration 52, and retain query/hydration/redaction/serialization/write/checkpoint timings.
- Split saved Gateway job recovery and the main run execution coroutine into bounded phases; enforce packaged ART code-unit limits for the coordinator and generated classes while preserving ownership, cancellation and terminal publication.
- Coalesce exact-service TCP probes per network generation, show actionable offline guidance, and add read-only Gateway host connectivity observations.
- Unify operation incident aliases within their process/session context and distinguish owned metadata deadlines from expected observer cancellation.
- Keep nested GitHub payload text from becoming the current operation's failure code; require genuine top-level failure provenance and preserve explicit success or unknown observations.
- Prevent duplicate built-in tool entries, retain existing settings when replacement fails, and restore older backups with equivalent duplicate bindings safely.

## GitHub agent workspace and optional runtimes

- Open the accepted task's workspace on Android or Windows to inspect scoped workers, delegated agents, pinned source, paged candidate diffs, executed checks and private review drafts.
- Delegate through configured Codex, Copilot cloud/SDK, Jules, Cursor, OpenHands, Cline, Claude SDK, Kiro/Devin ACP or Aider adapters. Explicit user commands and durable run receipts retain the accepted source and account; optional setup remains visible.
- Use graph-ranked context, role-specific tool discovery, dependency-ready requirement plans, compact evidence summaries, selective context exports and optional local next-edit proposals. Approved checks and final validation remain required before publication.
- Configure exact Context7, Playwright and complementary GitHub MCP tools in the existing host repository policy. Phone-only GitHub workflows remain available independently.
- Keep model benchmarks separate from complete agent-system comparisons. Unexecuted agent candidates have no test or model-quality score; candidate comparisons require the same actual executed check contract.
- Recover interrupted workspace requests by original command identity. Unknown outcomes are retained without replay, safe maintenance can inspect paused tasks, and parent cancellation cleans up isolated runtimes through the existing controller.

See the [workspace guide](docs/engineering/github-agent-workspace.md) and [runtime adapters](docs/engineering/coding-agent-adapters.md) for configuration, supported controls and qualification boundaries.

## Qualification and limitations

- Optional coding agents require installed pinned runtimes, explicit host configuration and compatible provider accounts. Protocol fixtures and host builds do not qualify paid provider execution, installed SDK compatibility, model quality or remote cancellation when the provider does not support it.
- Phone repository relationships are bounded lexical candidates, not compiler-resolved references or executed-test evidence. Unscanned, unsupported, generated and dynamic code can leave relationships unknown; current GitHub authorization and connectivity are required, and physical-phone performance remains unqualified.
- Unified Reliability v2 is not complete; this scoped release does not mark the broader milestone or unexecuted scenarios as passed.
- Physical Android performance, selected-provider behavior and the user's Windows/Gateway network require live device/service qualification. Automated fixtures do not establish those conditions.
- External provider rate limits, account credit balances and unavailable servers require their service or configuration conditions to recover; client changes improve classification, safe continuation and diagnostic evidence.
- The recorded QNN graph qualification used the official x86 HTP emulator and observed 39 QNN kernels per tested token shape with remaining graph work on CPU. It did not run on physical Adreno GPU or Hexagon NPU hardware and does not establish on-device acceleration, speedup, thermal behavior or battery use.
- Kokoro GPU/NPU execution is partial and device/model/runtime dependent. A registered provider or selected hardware label is not proof of accelerator execution; failed qualification or stale measurements retain CPU until a successful device recheck.
- Physical audio-route latency, caption alignment, interruption recovery, long-reply continuity and frame/memory behavior still require speaker, wired and Bluetooth replay on the target phone. Bounded buffering cannot guarantee uninterrupted speech when synthesis remains slower than playback.
- Gateway CUDA remains optional and requires a reachable, compatible configured Gateway. Local fixtures do not qualify the selected desktop GPU, account, network or live service.
- The host QNN probes verify finite nonzero PCM and timing from the same inference, not waveform or perceptual equivalence. The 83-token probe differs from its CPU reference by 50 ms of total predicted duration; the recorded host emulator uses ORT 1.28.0 while native/JNI validation uses the pinned application ORT 1.28.2.

The recorded graph regression covers 17-, 31- and 83-token probes of the exact shipped model, each with 39 QNN kernels plus CPU partitions and valid same-inference PCM/duration alignment. This mixed execution is separate from the recovery path that replaces a failed accelerator with CPU. See the [QNN graph evidence and reproduction](docs/repairs/evidence/kokoro-qnn-graph-2026-11-22/README.md) and [retained diagnostic repair ledger](docs/engineering/voice-update-nonvoice-scope-2026-11-22.md) for the measured scope and remaining device checks.

The exact release commit must pass the required main workflows, artifact identity, signer and provenance verification before publication. The scoped manifest retains the existing release checks; host tests, emulator evidence and source review do not replace exact-head Android, packaging or publication verification.

## Privacy visibility and local protection

- Keep mixed private memories unavailable to other conversations when one source is forgotten. Removing source records cannot broaden recall, and forgetting the final source still removes the memory.

- Separate **Enable private chats** in Options from the **Private chat** choice in New Chat. Options controls availability for new chats; each selected profile receives its own permanent conversation policy. Changing Options does not change saved conversations or another open chat.
- Preserve a private conversation across reopening, restart, retries, branches, copies and backup restore. Room 54 adds the permanent policy separately from masking history and conservatively locks conversations with saved privacy history. Private drafts and conversations retain screen protection independently of the Options switch.
- Restoring an older backup cannot remove an existing conversation's permanent privacy lock. Keep restored masking-history evidence tied to the content actually included in that backup.
- Protect restored voice captions while the saved conversation's privacy policy is loading after process recreation. Keep the window protected for a private room and release that loading protection only after an ordinary room is known.
- Allow ordinary memories to move between personal and project scopes while keeping private and unshared memories permanently restricted.
- Bind provider, local-tool, queued-work and voice requests to the originating conversation. Recheck the captured policy at transport dispatch, prevent unresolved conversation ownership from sending, and keep ordinary conversations independent of concurrent private work.
- Keep private memories and documents local to their source conversation, including older mixed-source records, edits, deletion and restore. Prevent private source material from entering shared research indexes or external Gateway, GitHub and MCP workflows; preserve trusted local tools and explicit provider masking.
- Keep enabled calculator, current-date and supplied-text slicing tools available in Privacy Mode through trusted local execution. Each model continuation masks tool arguments and results and retains profile, plugin and personal-data permissions.
- Keep each turn bound to its saved conversation privacy and captured masking policy through preparation, configuration checks and native callbacks. Changes to masking policy require a fresh turn; Options availability never unlocks a private conversation. Recheck captured authority and cancellation immediately before tool execution.
- Report privacy-blocked GitHub and MCP requests as local policy decisions with known non-dispatch. Preserve earlier uncertain operations and genuine connection recovery state; a local denial does not create a network cooldown or invalidate a working catalogue.
- Preserve uncertainty when a legacy TLS response read is interrupted and its reconnect is later blocked locally. Typed handshake and peer-admission failures remain separate; certificate checks and retry policy are unchanged.
- Propagate cancellation wrapped by an MCP transport error before reconnect or replay, preserving the original cancellation and any earlier exchange evidence.
- Preserve bounded DNS, TLS, connection, timeout and HTTP evidence in audit results. Record fixed assertion identities, attempted and passed counts, safe expected/observed states and privacy-policy revisions without exporting request bodies, private terms or credentials.
- Show the original cached failure, observation age and recorded retry eligibility. Repair actions keep the exact saved connection identity, and untested tools, models and device checks retain explicit qualification steps.
- Make Options availability changes effective immediately while saving on a background writer. Keep toggles and navigation responsive during disk writes, preserve the latest choice across rapid changes, and expose save failure/retry without cancelling saves when a screen closes. New Chat keeps its privacy selection independently.
- Load provider verification status and run account setup away from the UI thread. Confine loading and recoverable errors to account setup; retain authority checks, uncertain-write reconciliation and cancellation cleanup.
- Keep profile selection and draft chat creation independent of unfinished or failed provider verification, including opening a local GitHub workspace or Research draft. Enforce privacy when external work is actually dispatched. Add regression coverage for privacy status changes, pending verification, stale save completions and main-thread responsiveness.
- Render words kept local by actual privacy substitutions in yellow, with contrast-aware foregrounds and preserved Markdown/link behavior. Store only content digests and ranges for display evidence; do not persist the replacement dictionary.
- Show a small privacy lock at the bottom-right of each saved conversation type icon, distinguish permanent protection from historical masking evidence, and identify private chats in the conversation header. Preserve Gateway and work indicators; Room 53 retains observed masking history and Room 54 records the permanent policy.
- Detect introduced single names, partner/family relationships and parenthesized self aliases, seed identity references across the whole request, and recheck privacy settings after evidence persistence and queued preparation.
- Keep indicators conservative across retry, edits, selected revisions, mixed masking policies and later unmasked sends. The prior diagnostic export lacks raw request bodies, so historical words are not retroactively claimed to have stayed local. Automatic category detection remains heuristic; custom private terms remain available.
