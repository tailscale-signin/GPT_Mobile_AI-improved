# Comprehensive engineering backlog — October 26, 2026

Target branch: `fix/local-tools-answers-gguf-gpu-2026-10-26`.

This preserves all 165 requested acceptance tasks with stable IDs. An unchecked task is an open acceptance gate, not a claim that the feature has no implementation. Existing mechanisms and previous evidence are documented in [integration and runtime acceptance](2026-10-26-integration-and-runtime-acceptance.md). Do not mark a broad task complete from a source search or a smaller unit test. Attach the tested commit, scenario, environment, result and retained evidence before checking it. Live-provider, authenticated GitHub, emulator, physical-device and release tests require their respective environments.

## Repairs added in this change

- SYN-09 / UI-14: preserve fenced and indented code, repeated list steps, quotations, tables and case-sensitive identifiers during duplicate prose cleanup. Exact repeated prose and standalone headings are still removed.
- RES-03 / RES-17: classify malformed output separately from capacity denial; retain wrapped retrieval failure identity, redirect chain and retry deadline; distinguish reasoning-only output from empty output. Unchanged deterministic reasoning-only failures use the existing user-directed retry gate.
- FREE-06 / LOCAL-17: reject conflicting call envelopes, name/tool aliases and argument aliases; preserve supported string-named, native and tagged calls. Existing authorization and argument-schema checks still apply at dispatch.
- FREE-13–15 / RES-17 / GH-18: honor Retry-After dates and waits beyond one day without deadline overflow. Academic 429 persistence uses the same parser. Engineering API auth/permission errors do not incorrectly wait for ordinary quota-reset headers. The GitHub workspace manager also preserves long waits and safely handles oversized quota resets.
- LOCAL-04: repeated preparation callbacks cannot extend the useful-progress watchdog; repeated prefill-begin callbacks cannot reset the throughput observation window.
- DIAG-16 / DIAG-19 / BENCH-01: check cancellation at each benchmark export buffer write; synchronous large serialization stops at the next buffer and uses existing partial-file cleanup.

- SYN-01 / UI-01: repair the newer remote commit's nonexistent fallback publication method call; route queued/running fallback through the established fenced terminal transaction and extend Room persistence/exact-once regressions.

These repairs address specific paths within the tasks below. They do not certify all acceptance requirements.

## Combined Mode — Final synthesis (18; P0)

Relevant implementation: `CombinedSynthesisPlanner`, `CombinedDeliverySession`, `CombinedAnswerPublication`, `CombinedWriterPolicy`, `CombinedTaskContract`, `CombinedFinalCheck`, and continuation journals under `data/agent`.

- [ ] SYN-01 Guarantee a nonempty primary answer when usable contributors exist.
- [ ] SYN-02 Never mark empty or whitespace-only final output successful.
- [ ] SYN-03 Invoke the configured eligible primary writer.
- [ ] SYN-04 Synthesize completed responses despite other contributor failures.
- [ ] SYN-05 Preserve every usable answer in synthesis preparation.
- [ ] SYN-06 Preserve the requested country, subject and topic.
- [ ] SYN-07 Enforce word count, structure and formatting.
- [ ] SYN-08 Detect substantial missing information before publication.
- [ ] SYN-09 Prevent repeated paragraphs, headings and sections.
- [ ] SYN-10 Preserve qualifications, numerical claims and disagreements.
- [ ] SYN-11 Keep single-pass synthesis nonempty and complete.
- [ ] SYN-12 Reserve sufficient writer budget for long answers.
- [ ] SYN-13 Label partial or evidence-only fallback explicitly.
- [ ] SYN-14 Prevent duplicate final bubbles after retry or recovery.
- [ ] SYN-15 Test four/five contributors including failed profiles.
- [ ] SYN-16 Replay the failing France and Portugal cases.
- [ ] SYN-17 Continue long answers without repeated content.
- [ ] SYN-18 Reject stale output after ownership changes.

## Combined Mode — Attribution and UI (16; P1)

Relevant implementation: publication validation, final message/manifest/block entities, claim mapping, quality inspector, progress snapshots and `presentation/ui/chat/CombinedFailuresDialog.kt`. Acceptance must include Room reopen and actual Compose interactions.

- [ ] UI-01 Persist final body and attribution atomically.
- [ ] UI-02 Restore colours after restart and process death.
- [ ] UI-03 Bind mappings to the current answer revision.
- [ ] UI-04 Match source-tab and final-answer colours.
- [ ] UI-05 Preserve attribution for copied and overlapping passages.
- [ ] UI-06 Attribute paraphrases without claiming exact provenance.
- [ ] UI-07 Separate actual omissions from detector false negatives.
- [ ] UI-08 Assess the final displayed answer.
- [ ] UI-09 Show progress in the main answer bubble.
- [ ] UI-10 Restore failure actions with the conversation.
- [ ] UI-11 Restrict Retry/Remove to the selected contributor.
- [ ] UI-12 Hide internal/debug contribution records.
- [ ] UI-13 Keep contributor tabs at the answer bottom.
- [ ] UI-14 Preserve Markdown, lists, headings and emojis.
- [ ] UI-15 Fence stale Compose callbacks after replacement.
- [ ] UI-16 Test attribution after continuation, exclusion, retry and restart.

## Research Mode and Examine (23; P0)

Relevant implementation: `ArticleExaminationCoordinator`, `ExaminationOutput`, `ExaminationOutputBudget`, `ExaminationFailure`, authorization/checkpoints, scientific contracts and the unique `ResearchExaminationWorker`. Provider retrieval is in `data/research/academic`.

- [ ] RES-01 Complete usable assessments without indefinite capacity waits.
- [ ] RES-02 Accept usable grounded assessment output instead of EXTRACTION_EMPTY.
- [ ] RES-03 Distinguish unreadable input, invalid output and insufficient evidence.
- [ ] RES-04 Fit appraisal chunks to context and output allowances.
- [ ] RES-05 Retry only the failed assessment stage.
- [ ] RES-06 Retain extracted evidence after provider/writer failure.
- [ ] RES-07 Checkpoint before stage transitions.
- [ ] RES-08 Resume pending research/assessment after process death.
- [ ] RES-09 Do not rotate writers while a remote job remains pending.
- [ ] RES-10 Commit accepted assessments exactly once.
- [ ] RES-11 Retain deferred cursors until evidence commits.
- [ ] RES-12 Preserve partial-read status.
- [ ] RES-13 Extract useful claims from partially read sources.
- [ ] RES-14 Validate citation identity, exact quotations and numbers.
- [ ] RES-15 Strengthen semantic claim-support checks.
- [ ] RES-16 Tie summaries to the actual evidence used.
- [ ] RES-17 Show accurate provider waiting/failure/retry states.
- [ ] RES-18 Prompt for papers/studies consent when needed.
- [ ] RES-19 Offer Accept/Decline instead of a permanent toggle.
- [ ] RES-20 Persist and respect consent and declined access.
- [ ] RES-21 Test PubMed, Europe PMC and Google Scholar paths.
- [ ] RES-22 Prevent stale/unrelated Research Archive reuse.
- [ ] RES-23 Execute real-provider end-to-end Examine tests.

## Free providers and tool execution (24; P1)

Relevant implementation: `FreeProviderPreflight`, provider adapters, `LocalPromptToolBridge`, tool schema/dispatch validation, `ProviderPrivacyPolicy`, cooldown/limit records and response continuation planning. Live tests must identify provider, model, date, native/text route and returned usable evidence.

- [ ] FREE-01 Live-test every enabled Free provider.
- [ ] FREE-02 Test native and text-only web search.
- [ ] FREE-03 Test eligible multi-engine search.
- [ ] FREE-04 Test crawler retrieval and usable returned content.
- [ ] FREE-05 Parse provider JSON tool calls correctly.
- [ ] FREE-06 Reject malformed, unknown, disabled and unauthorized calls.
- [ ] FREE-07 Keep tool output separate from final user answers.
- [ ] FREE-08 Apply permissions, budgets and approvals consistently.
- [ ] FREE-09 Enable authorized public web tools by default.
- [ ] FREE-10 Gate personal-data tools with explicit informed consent.
- [ ] FREE-11 Distinguish anonymous/data-retaining providers.
- [ ] FREE-12 Show privacy warnings before sensitive activation.
- [ ] FREE-13 Display accurate cooldown countdowns.
- [ ] FREE-14 Disable limited profiles and restore at the deadline.
- [ ] FREE-15 Honor Retry-After and provider quotas.
- [ ] FREE-16 Avoid deterministic HTTP 4xx retry loops.
- [ ] FREE-17 Reject reasoning-only HTTP 200 as visible success.
- [ ] FREE-18 Separate upstream generation and local parsing failures.
- [ ] FREE-19 Retain pending job receipts without duplicate submissions.
- [ ] FREE-20 Continue long answers on small-context models.
- [ ] FREE-21 Preserve prior text and original task during continuation.
- [ ] FREE-22 Retain completed contributions after provider failure.
- [ ] FREE-23 Clear stale routing after model/provider switches.
- [ ] FREE-24 Recover after cancellation/connectivity loss.

## GitHub App and engineering agent (21; P1)

Relevant implementation: device auth/workspace client, engineering state machine/controller, authorization, Room ledger, remote reconciliation and CI policy/workers. Never record tokens in acceptance evidence. Real writes require the app's existing explicit review/approval flow.

- [ ] GH-01 Support secure token renewal or supported credential refresh.
- [ ] GH-02 Handle expired/revoked credentials clearly.
- [ ] GH-03 Live-test GitHub App device-flow sign-in.
- [ ] GH-04 Verify authorized private-repository discovery/access.
- [ ] GH-05 Check permissions before writes.
- [ ] GH-06 Test create/update/delete on authorized branches.
- [ ] GH-07 Test multi-file patch generation/application.
- [ ] GH-08 Guard concurrent updates with current blob SHAs.
- [ ] GH-09 Respect protected/default branch policy.
- [ ] GH-10 Require AWAITING_APPROVAL before sensitive execution.
- [ ] GH-11 Validate patches before PATCH_READY.
- [ ] GH-12 Check syntax, secrets and blast radius.
- [ ] GH-13 Recover branch/commit/ref operations.
- [ ] GH-14 Create draft PRs with summaries and test results.
- [ ] GH-15 Bind CI evidence to the exact latest SHA.
- [ ] GH-16 Block merge on pending/failed required checks.
- [ ] GH-17 Preserve review feedback and unresolved threads.
- [ ] GH-18 Recover from rate limits/API failures.
- [ ] GH-19 Audit repository modifications.
- [ ] GH-20 Avoid duplicate commits after interruption.
- [ ] GH-21 Test branch-to-PR-to-merge end to end.

## GGUF, GPU, NPU and local inference (23; P0)

Relevant implementation: `GgufRuntimeAdapter`, native-holder lifecycle, deadline/memory policies, runtime capabilities/router, import/template checks, NPU adapters and `local-gguf-runtime/src/main/cpp`. Physical success must record the actual execution backend; CPU/emulator receipts cannot qualify GPU/NPU.

- [ ] LOCAL-01 Safely cancel native GGUF workers.
- [ ] LOCAL-02 Confirm cleanup before reloading a model.
- [ ] LOCAL-03 Recover deterministically after timeout/cancellation.
- [ ] LOCAL-04 Bound prefill without useful progress.
- [ ] LOCAL-05 Reduce CPU GGUF first-token latency.
- [ ] LOCAL-06 Verify Vulkan flags/conditional SDK discovery.
- [ ] LOCAL-07 Build without Vulkan SDK when Vulkan is disabled.
- [ ] LOCAL-08 Check Android device Vulkan support before selection.
- [ ] LOCAL-09 Verify OpenCL support/capability checks.
- [ ] LOCAL-10 Explain GPU failure and any CPU fallback.
- [ ] LOCAL-11 Distinguish CPU/GPU/NPU/unsupported execution.
- [ ] LOCAL-12 Report actual GenieX/LiteRT CPU backend labels.
- [ ] LOCAL-13 Verify QNN on Snapdragon 8 Elite.
- [ ] LOCAL-14 Reject absent/incompatible NPU builds before launch.
- [ ] LOCAL-15 Fix NPU constrained-decoding benchmark crashes.
- [ ] LOCAL-16 Reduce unnecessary prompt prefill.
- [ ] LOCAL-17 Execute app-managed tools without native support.
- [ ] LOCAL-18 Detect unsupported GGUF templates before inference.
- [ ] LOCAL-19 Bound unavailable MCP endpoint attempts.
- [ ] LOCAL-20 Improve memory admission and recovery.
- [ ] LOCAL-21 Apply explicit fallback policies with truthful receipts.
- [ ] LOCAL-22 Test repeated load/cancel/unload/reload cycles.
- [ ] LOCAL-23 Measure throughput/first-token latency on the ASUS device.

## Diagnostics, reliability and performance (21; P1)

Relevant implementation: structured diagnostics/redaction, provider failure records, stage/correlation receipts, off-main-thread export stores and lifecycle recovery. Script fixture success is not an actual app crash/performance measurement.

- [ ] DIAG-01 Export valid JSON for every JSONL record.
- [ ] DIAG-02 Prevent bare JSON literals corrupting profile lists.
- [ ] DIAG-03 Reopen/parse exported chat bundles.
- [ ] DIAG-04 Redact credentials while preserving useful context.
- [ ] DIAG-05 Preserve stacks, nested errors and timings.
- [ ] DIAG-06 Deduplicate repeated failure reporting.
- [ ] DIAG-07 Share stable UI/diagnostic failure classifications.
- [ ] DIAG-08 Correlate request/recovery/synthesis/publication stages.
- [ ] DIAG-09 Trace main-thread disk I/O to call sites.
- [ ] DIAG-10 Move blocking storage/preparation off the main thread.
- [ ] DIAG-11 Measure frame p50/p95/p99.
- [ ] DIAG-12 Reduce frames over 100 ms.
- [ ] DIAG-13 Reduce streaming Compose recomposition.
- [ ] DIAG-14 Avoid Markdown parsing on animation frames.
- [ ] DIAG-15 Start foreground services within Android deadlines.
- [ ] DIAG-16 Cancel stale asynchronous work.
- [ ] DIAG-17 Test process-death lifecycle recovery.
- [ ] DIAG-18 Add regressions for observed crashes.
- [ ] DIAG-19 Export large diagnostics without UI freezes.
- [ ] DIAG-20 Reduce logging/serialization overhead.
- [ ] DIAG-21 Measure allocation and GC in Combined/Research.

## Benchmarks, testing and release readiness (19; P1)

Relevant implementation: benchmark metrics/runtime observations/scoreboard, streaming and cold exports, durable export artifact store, Android acceptance tests, and `.github/workflows`. Full acceptance remains on the final tested commit/artifact.

- [ ] BENCH-01 Fix benchmark export crashes.
- [ ] BENCH-02 Verify NPU constrained decoding cannot crash.
- [ ] BENCH-03 Separate execution and model-quality failures.
- [ ] BENCH-04 Separate skips/cancellations/timeouts/partial responses.
- [ ] BENCH-05 Separate model execution and preparation time.
- [ ] BENCH-06 Exclude admission failures from intelligence scoring.
- [ ] BENCH-07 Score the final displayed answer.
- [ ] BENCH-08 Preserve provider/backend benchmark detail.
- [ ] BENCH-09 Check identical-case repeatability.
- [ ] BENCH-10 Run Android unit tests on the final merge commit.
- [ ] BENCH-11 Run lint, CodeQL and native checks.
- [ ] BENCH-12 Verify DEX method-size constraints.
- [ ] BENCH-13 Require nonempty executed-application coverage.
- [ ] BENCH-14 Run Combined/Research emulator acceptance.
- [ ] BENCH-15 Run physical-device local-inference acceptance.
- [ ] BENCH-16 Bind signed release artifacts to validated commits.
- [ ] BENCH-17 Test process-death recovery/exact-once publication.
- [ ] BENCH-18 Document release limitations.
- [ ] BENCH-19 Require evidence before declaring completion.

## Validation of this change

Results and environment limits are recorded in the follow-up validation entry in [integration and runtime acceptance](2026-10-26-integration-and-runtime-acceptance.md). No broad task above is checked merely because its smaller regression passed. Required next evidence includes full Android compilation/tests, final-SHA CI, real-provider Examine/search/crawler runs, GitHub device flow/private repository write recovery, and physical ASUS GPU/NPU/performance trials.
