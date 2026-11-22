# Android 0.9.66.13 — smoother voice chat and reliable execution

Version code: **147**. Coordinated publication with Gateway **18.1.1**; retains the Gateway 18 contract.

## Voice chat and captions

- Correct live transcription word ranges when recognition replaces an indented phrase, and retain paragraph shaping for supplementary right-to-left scripts during streaming.
- Keep a continuous five-line caption view with stable line identities, smooth position/scale/opacity changes, manual scrolling and Return to live, while retaining text selection, accessibility and reduced-motion behavior.
- Fade spoken words into the theme colour over 250 ms and release their colour over 120 ms; keep only the current word bold and reuse glyph geometry so emphasis does not shift or rewrap neighbouring words.
- Drive captions from monotonic presented-audio frames using validated AudioTrack timestamps with playback-head fallback; freeze progress during pause/refill, bound interpolation by accepted audio and retain route and reply-generation fences.
- Reserve accepted PCM against measured next-phrase synthesis time within a bounded five-second reserve, preserve natural first clauses, and align follow-up phrase targets to Quality 24 words, Balanced 16 and Fast 12 with adaptive Auto pacing.
- Separate first visible text, publishable phrase, queued speech, prepared PCM and presented audio timings; distinguish warm-at-request from engine reuse and show measured buffering, refill and effective-provider evidence.
- Save a frozen voice measurement report through the document picker with bounded timing series and selected/effective backend evidence, excluding conversation text, audio and device identifiers. Correct outer-caption row spacing during fractional transitions and explain the shipped English voice and measurement boundaries.

## Kokoro GPU, NPU and recovery

- Choosing a speech engine reuses its saved measurements. Compare and Recheck explicitly refresh the measurement on the next reply.
- Repair Kokoro QNN partition proposals with a convex, static-interface graph adapter; select the concrete GPU or NPU device and keep dynamic duration/alignment sections on CPU within the same audio-and-timing inference.
- Qualify backend execution from valid PCM, aligned durations and actual kernel profiles for each prepared token shape; retain bounded device/model/runtime/lexicon/thread-scoped capability receipts and explicit rechecks. Auto retains CPU unless measured accelerator cost, including preparation, is at least 10% lower.
- Retire a failed accelerator and retry only its current unplayed phrase on CPU; preserve typed source, target, outcome and original failure when CPU or permitted Android system speech recovery occurs, including optional Gateway CUDA failures. Fence duplicate and stale fallback callbacks.
- Move deferred speech preference reads off the UI path and retain explicit cancellation origins, bounded cleanup and separate synthesis/playback ownership.

Capability receipts are limited to 24 entries. Successful measurements expire after seven days and failed qualifications after six hours; clock rollback invalidates the receipt. **Compare engines on next reply** measures CPU, GPU and NPU with two phrase shapes, including preparation. **Recheck engine on next reply** explicitly retries the selected backend. Per-shape profiles identify actual accelerator and CPU participation; selecting an engine alone does not qualify it.

## Setup, model routes and recovery

- Profile details keep the route shown in the picker. Research source switches and citation import stay available if activity history fails to load. Research presets, report options and feature settings respect the editor’s read-only and saving states.
- Distinguish saved configuration and downloaded weights from a verified complete response; guide the first chat and expose existing response checks from profile settings.
- Show configured phone inference, recognized cloud APIs, observed Gateway routes and other network services separately. A private address or engineering Gateway binding does not establish where a different inference endpoint runs.
- Separate requested local processors from native execution receipts. Failed, recovering and released runtimes do not display retained receipts as current readiness.
- Explain unavailable research adapters, document text recovery and backup transfer boundaries alongside the existing controls, including credentials, model downloads and device-specific NPU packages.
- Apply built-in tool changes atomically across settings and imports. Consolidate duplicate built-in grants within selected restore data while preserving other profiles, tools and connection-backed bindings.

## Task efficiency and provider consistency

- Expanded benchmark evidence keeps one copy of each summary and reveals the supporting measurements below the disclosure control.
- Centralize provider session creation across ordinary chat, Research, delegated work and benchmarks while preserving the selected profile, request constraints, authorization and accounting.
- Allow explicitly Automatic preparation to avoid optional helper setup for narrowly identified greetings and short inline text transformations. Explicit helpers, reviews, Research, plans and local-first ownership retain their accepted work.
- Keep two contributors for routine tasks only when adaptive Combined participation is already enabled; preserve explicit participants, required diversity, accepted plans and hard budgets.
- Report elapsed effort and configured API cost per successful benchmark fixture, including unsuccessful attempts and work skipped after dispatch. Preserve unknown timing, price, retry and participant usage instead of displaying partial totals as complete costs. Expose the measurement scope and exportable evidence; these measurements do not establish real-world task quality or physical-device savings.

## Repository map on this phone

- Open a revision-bound file and folder map directly from GitHub without configuring Gateway. Search files and indexed symbols, inspect source links, and explore caller, callee, path and impact candidates in the existing map interface.
- Build source relationships progressively on the phone using the encrypted source cache and reusable per-blob index. Each foreground session advances through bounded batches, reports coverage, supports Pause and resumes saved progress at the same revision.
- Distinguish observed tree structure from lexical symbol, import and call candidates. Keep unsupported files, missing sources, truncation and partial coverage visible instead of presenting an incomplete map as compiler-resolved analysis.
- Make the phone map available to authorized native GitHub model tools without requiring an engineering Gateway or persistent engineering memory. Account, connection, profile, permission and revision checks apply to cached reads and pagination as well as downloads.
- Retain Gateway as an explicit optional source for its additional analysis and durable background indexing. Source switches discard stale results and cursors, and failures never silently change the selected provider.

## Execution, GitHub and diagnostic reliability

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

## Qualification and limitations

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

- Keep enabled calculator, current-date and supplied-text slicing tools available in Privacy Mode through trusted local execution. Each model continuation masks tool arguments and results and retains profile, plugin and personal-data permissions.
- Report privacy-blocked GitHub and MCP requests as local policy decisions with known non-dispatch. Preserve earlier uncertain operations and genuine connection recovery state; a local denial does not create a network cooldown or invalidate a working catalogue.
- Preserve bounded DNS, TLS, connection, timeout and HTTP evidence in audit results. Record fixed assertion identities, attempted and passed counts, safe expected/observed states and privacy-policy revisions without exporting request bodies, private terms or credentials.
- Show the original cached failure, observation age and recorded retry eligibility. Repair actions keep the exact saved connection identity, and untested tools, models and device checks retain explicit qualification steps.
- Make Privacy Mode changes effective immediately while saving on a background writer shared by Settings and New Chat. Keep toggles and navigation responsive during disk writes, preserve the latest choice across rapid changes, and expose save failure/retry without cancelling saves when a screen closes.
- Load provider verification status and run account setup away from the UI thread. Confine loading and recoverable errors to account setup; retain authority checks, uncertain-write reconciliation and cancellation cleanup.
- Keep profile selection and draft chat creation independent of unfinished or failed provider verification, including opening a local GitHub workspace or Research draft. Enforce privacy when external work is actually dispatched. Add regression coverage for privacy status changes, pending verification, stale save completions and main-thread responsiveness.
- Render words kept local by actual privacy substitutions in yellow, with contrast-aware foregrounds and preserved Markdown/link behavior. Store only content digests and ranges for display evidence; do not persist the replacement dictionary.
- Show a small privacy lock at the bottom-right of each saved conversation type icon when the chat was created or used with Privacy Mode. Preserve Gateway and work indicators; Room 53 adds the saved flag without guessing historical privacy state.
- Detect introduced single names, partner/family relationships and parenthesized self aliases, seed identity references across the whole request, and recheck privacy settings after evidence persistence and queued preparation.
- Keep indicators conservative across retry, edits, selected revisions, mixed masking policies and later unmasked sends. The prior diagnostic export lacks raw request bodies, so historical words are not retroactively claimed to have stayed local. Automatic category detection remains heuristic; custom private terms remain available.
