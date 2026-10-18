# Integrated implementation status

Branch: `feat/integrated-runtime-workspace-reliability-2026-10-18`
Base: `42f5f1597d4bab2ff0fe2a016548dd6ed4c8df0b`; includes PR 713 at `99e47ae215697ffbca223f2f89b925c03dffec8b` and reconciles its merge into main at `a13b8b5a46ece251b42818ed01e3bf651ac2cc3f`.

This branch implements a substantial portion of the four supplied specifications and prioritized audit. It is a development draft, not a completed release. Source implementation is distinguished from Android compilation, real-provider tests and handset qualification. No NPU performance, hardware observation, visual acceptance or full completion claim is made.

## Audit requirement mapping

| Item | Status | Implementation and remaining validation |
| --- | --- | --- |
| 1 | Core stores implemented; wider restore gate open | Encrypted chunked core-store journal before mutation; unconditional DB commit marker; recovery before startup consumers; committed benchmark import is recoverable. Full cross-feature restore semantics and process-kill matrix remain required. |
| 2 | Source implemented | Structured document/chunk receipts; scope, content and consent-generation validation inherited by physical dispatch, delegates and continuations. |
| 3 | Source implemented | Legacy update_file requires branch/head and routes through guarded atomic commit_files; selected branch disagreement rejected. |
| 4 | Source implemented | Memory admission sums validated actual manifest assets plus KV/compute/vision estimates; estimates remain conservative and unmeasured. |
| 5 | Source implemented | New local profiles retain app tool bridge. Existing explicit denials preserved; ambiguous legacy defaults are not silently migrated. |
| 6 | Source implemented; focused tests passed | Ordinary code/JSON classification across chunk splits; bounded tool capture; native failure flush for established ordinary content. |
| 7 | Source implemented; Android test pending | Only completed repairs passing policy/decoder checks replace a completed original. Failure notice retains original. |
| 8 | Source implemented; provider matrix pending | Groq, Anthropic and Gemini require terminal receipts and stop collection on terminal frames. |
| 9 | Source implemented; focused tests passed | Anthropic complete JSON normalizes text, thinking/signatures, tools, usage and terminal state into shared chunks. |
| 10 | Source implemented | Groq, Anthropic and Gemini rotation is prohibited after text, thinking or tool payload delivery. |
| 11 | Source implemented; broader MCP classification pending | Built-in search capability metadata and current-run successful exchanges initialize answer guard. Other MCP engines need explicit capability metadata. |
| 12 | Partial; repository administration needed | Added post-merge contract workflow. Required-check branch protection is not changed: available connector has no administration mutation. |
| 13 | Partial | Managed hashed image references reach Responses/compatible/Anthropic inputs; Gemini supports image/audio input. Text-only routes report unsupported media. Native local tool-media transport and cross-process reference recovery remain. |
| 14 | Source implemented | Groq message-level tool_calls uses the shared assembler, including Streaming OFF. |
| 15 | Source implemented; focused tests passed | Bounded local JSON-pointer resolution; escaped pointers; missing/external/cyclic references reject rather than widen constraints. |
| 16 | Source implemented | Manifest assets use kernel inode/change-time receipts plus installation epoch; unavailable strong identity falls back to hashing. Device bytes/time measurement remains. |
| 17 | Source implemented | Cheap durable generation check precedes full vault decryption; changed generation reloads authoritative state. |
| 18 | Source implemented | Benchmark mirror excluded from generic settings capture and restore, including legacy incoming shared preferences. |
| 19 | Source implemented; device gate open | Cancellation watcher reaches JNI zero-handle load cancellation. Slow-load cancellation and worker reuse require Android tests. |
| 20 | Source implemented | Ollama continuation includes the exact published assistant segment before continue. |
| 21 | Source implemented; experimental until device qualification | JNI compares actual tokens, truncates divergent suffix or resets, and records reused/new prefill counts. Failed/cancelled generations invalidate reuse. |
| 22 | Open | Existing qualification gates remain enforced. Full production qualification orchestration and usable energy measurement are not completed; quick measurements cannot grant QUALIFIED. |
| 23 | Source implemented | Removed misleading global engine selector; resolved artifact routes participate in planning; requested placement remains distinct from observed hardware. |
| 24 | Source implemented | Center unread preference read at scroll; hidden inactive global batch control; delegation UI states supported single level; removed unused sufficiency slider. |
| 25 | Source implemented; focused tests passed | One canonical overlap-aware range reader for pagination and project sharing; bounded SQL chunk ranges. |
| 26 | Source implemented; Android lifecycle gate open | Same-connection open is idempotent; private encrypted base/repository/branch, staged files and editor draft recovery; stale bases retained for review. |
| 27 | Source implemented | Activity Runs has its own route into run workspace independent of GitHub connection. |
| 28 | Source implemented; API fixture gate open | Search selected commit tree/blob snapshot in bounded 24-file pages, returning commit and explicit incomplete coverage. |
| 29 | Source implemented; API fixture gate open | Native PR update, ready, review and head-checked green-check merge operations. No automatic merge performed by this branch. |
| 30 | Source implemented | Checks report total/returned/truncated; PR file summary reports explicit continuation; snapshot search exposes remaining scope. |
| 31 | Partial | Dispatch source/workflow/tag/time/correlation receipt; status refuses unrelated recent runs and accepts explicit matched run/source. Automatic exact dispatch-run correlation and release-asset source attestation remain. |
| 32 | Integrated from PR 713; device gate open | Release/workflow APK catalog, multi-APK extraction, download progress/cancel/retry, save/share/remove and ABI ordering inherited with original tests. |
| 33 | Source documentation updated; installation gate open | Primary README now names complete v14 manifest/package and correct launcher; clean Windows/Linux installation not performed. |
| 34 | Source implemented | Task-selected general/technical/product/scientific sections applied to instruction, validator, chapter repair and fallback. |
| 35 | Source implemented; budget fixtures passed | Per-ABI APK budget and largest compressed-entry reporting added to build workflow; no measured binary reduction or speedup claimed. |

## Design document mapping

- APK workspace: PR 713 integrated; guarded mutations, lifecycle, selected-revision search, retention and explicit completeness extended. Dispatch correlation remains partly open.
- Answer visibility: primary title directives removed; canonical answer predicate used by runner, persistence, history and timeline; one existing bounded empty-answer recovery reused; first visible Markdown bypasses the sampling delay. Ordered conflated checkpoint persistence and per-run/fence render-receipt deduplication are now implemented; restart-aware title state and invariant prompt caching remain open.
- Local runtime: actual asset accounting, cached validation, prompt bridge, artifact planning, exact token-prefix reuse and optional selected fixed-context primary preparation. Two primary hub tabs and corrected marketplace filters/runtime labels. Full qualification, native GGUF multimodal/tools, experimental Hexagon and native NPU tuning remain open.
- Glass appearance: shared 85% background tokens with contrast adjustment, shared wrappers, bubble menu, profile picker, source dialog, archive sheet and Amazon detail plate; Glass/Solid and Auto/Off draft/persistence/backup/legacy mapping plus preview. Custom root-plate inventory/migration, remaining icon migration, qualified bounded blur renderer and full visual/device matrix remain open.

## Validation evidence

- 132 Python script tests passed, including the two new APK budget fixtures.
- 21 isolated Kotlin/JUnit parser/schema/range tests passed using production algorithms and boundary stubs.
- 4 isolated Kotlin/JUnit Anthropic DTO and restore-journal tests passed using production implementations and a fake vault boundary.
- 12 isolated Kotlin/JUnit text-guard tests passed, including explicit search capability metadata and trusted preceding-round evidence.
- 2 isolated Kotlin/JUnit model-memory and asset-receipt tests passed, including a sparse 4 GiB model and same-size in-place modification with preserved mtime.
- Android resource, regex, exported Room-schema and QNN provenance checks passed.
- Ktlint 1.3.1 formatting passed. Production GGUF JNI compiled against the pinned llama.cpp source; chat-template test and both ChatML/Jinja real-inference smoke fixtures passed (repeat-prefix reuse, partial cancellation counters, context rejection, stale handles and reopen).
- The gateway launcher validated its complete v14.2.0 manifest; 180 gateway Python tests and the Node contract suite passed after updating the reviewed README checksum.
- Full Gradle Android compilation was attempted with JDK 21/Gradle 9.8.0; dependency resolution could not reach the configured Maven repositories from Java in this environment. No Android unit/lint/build success is claimed.

## Release gates

Do not merge as a completed implementation of all four documents. Complete the open software packages above; run full Android compilation/unit/lint, process-kill restore and workspace tests, private/public APK delivery, provider completion and rotation fixtures, composite-opacity screenshots, and target-device local/NPU qualification. Repository required-check protection needs a separate authorized administration-capable path.

## Wiring review follow-up

All files changed from current main were reviewed again, including supplied specification exports, implementation, tests, gateway manifest and CI. The follow-up corrected Android compiler integration errors, inherited document authorization for child requests, staged-versus-editor draft recovery/reset/navigation, load-time startup access, reordered prompt-tool envelopes, Anthropic GIF MIME mapping, prefix retention after a length stop, and policy availability using the planner’s current eligible candidates. Added GitHub mock-transport regressions for branch rejection and selected-revision blob search. These tests still require the full Android unit job; isolated tests do not substitute for that job.

CI caught the original native receipt assertion and then the missing reuse after a length stop. The new seven-field assertion checks reused plus newly processed tokens against total prompt tokens. Final Android/native CI and device qualification remain release gates.

## Main menu and NPU Assist specifications (added October 18)

Both additional source documents are retained under `docs/design/`. They extend the existing four-document scope. The draft is not a claim that every acceptance or physical-device gate is complete.

Implemented source paths:

- Room v41 reply publications commit with the owned terminal answer. Direct/Multi replies and the Combined final writer publish; contributors and internal work do not. One logical reply receives revisions across partial recovery. Acknowledgement requires the captured revision and displayed answer digest. Navigation does not acknowledge replies. Legacy inbox migration imports only provable surviving owned replies; Boolean flags do not create history. Workspace children retain precise targets while Home groups them under their visible root.
- Home selection uses chat IDs, transactional folder moves and operation-specific Undo. Creating and moving is atomic; newer moves survive Undo. Bulk pinning changes only the requested subset. Archive entry points share a receipt and Undo flow. Profile names are sanitized for display, subtitles retain 60% opacity, mode/unread icons retain their shape, and list fades are spatial overlays.
- The retired Execution Efficiency screen, live overrides and shared physical-attempt admission are removed. Independent spend/token accounting remains. Local Assist has one settings card with explicit stable helper identity, task switches, context, processing and separate derived-evidence disclosure, Test and collapsed Advanced preparation.
- Policy v4 normalization is idempotent and preserves explicit Off, helper UID, manual contexts, preload denial and prior text grants. New scopes cannot inherit legacy grants. Live edits advance a consent epoch so revoke/regrant cannot revive cached work.
- Assist context admission preserves exact export capacities, distinguishes native allocation from logical task size, compares the full engine configuration for warm reuse, and budgets the estimated rendered worker input plus output/result/safety reserves before inference. Unsupported saved values remain saved and fail with a reason. Extended contexts above 4K remain unavailable without the requested qualification system.
- Ordinary remote chat never waits for the optional helper. Opportunistic artifacts are bounded, source-digest checked, runtime/permission checked, and cancelled on parent cancellation or live revocation. Late artifacts can only enter a following existing round; no second paid request is scheduled.

Open implementation and qualification gates:

- NPU vision preparation/processor identity, bounded image/PDF rendering and descriptor transport, typed supervised action proposals and acquisition, qualification-backed task/context eligibility, real serialized-request inclusion receipts and the full T01–T48 matrix are **not complete**. Image reading/autonomous reads remain unavailable; no verified badge or NPU speed/battery benefit is claimed. Existing network/research owners remain authoritative.
- The Home circular control currently uses the shared opaque/material fallback. A qualified backdrop sampler/blur renderer, actual screenshots, accessibility/device lifecycle testing and performance comparisons remain outstanding.
- Room v41 must include the actual KSP schema from Android CI. Full unit/lint/build results on the final commit are required; local subset checks cannot replace them.

Additional validation: 132 Python tests, Android resources and regex checks passed. Three isolated production context-planning tests passed. New Room regressions cover partial-to-final revisions, stale owners/acknowledgements, Combined contributor exclusion, 105 unread publications, deletion, legacy import idempotency and folder rollback/Undo conflicts; these require Android CI. Earlier Android CI failures were investigated and fixes retain the stricter provider-terminal and repair-acceptance requirements.

Response-delivery follow-up: the first canonical answer now publishes independently of prior progress; a bounded timer flushes pending suffixes during provider silence. A single conflated checkpoint writer keeps at most one pending immutable snapshot, drains before normal terminal commit, and is cancelled/joined before a cancellation/failure supersedes it. Three isolated writer tests passed (slow writer, terminal ordering, cancellation and failure reporting). The committed Room v41 schema is the actual Android KSP export from CI, not a handwritten approximation.

Final wiring pass: all 171 changed files were inventoried and all 146 changed Kotlin files passed ktlint. Follow-up fixes additionally reject error-only unread publications, acknowledge meaningful partial text despite an appended stop notice, invalidate standalone model identities on kernel change time, and test separate Assist disclosure in the serialized provider tool catalogue. Four standalone production digest tests passed. Android CI compiled production source and identified a missing test-fixture argument; that fixture was corrected. Final Android unit/lint/build results must be read from the latest PR revision.

Startup worker audit: all seven CoroutineWorker entry points now await startup recovery before reading repositories or credentials. Added delayed-recovery coverage to ensure a background consumer cannot observe state before reconciliation completes. This strengthens the core-store gate; it does not close the separately listed cross-feature transaction package.

Four isolated production startup-boundary tests passed, including a deliberately immediate dispatcher and a blocked background consumer. Three isolated stream-pulse boundary tests passed for partial text before failure, timed flush during silence and immediate terminal cancellation. Context admission also rejects overflowing token totals.

The inbox now composes rows lazily and identifies the profile or Combined channel. Database paging and aggregate unread-count queries remain an open scalability requirement. The latest completed Android run compiled production/tests and passed 3,303 of 3,304 tests; its one failure found upstream work continuing after terminal delivery in the new buffered timer wrapper. The producer now enforces the terminal boundary itself; the existing regression is retained. Final CI must validate this correction and the startup follow-up.

Added document-only recall regression fixtures: one exercises actual Room source revision/deletion validation; another revokes cloud consent or document access at the physical provider boundary with an empty fact vault and no research recall. Revoked cases must make zero outgoing requests. These fixtures await the latest Android run.

Document receipt review also moved the consent-generation capture before source retrieval and made the existing document revision timestamp monotonic across changes/deletion/restore. Fresh v4 policies cannot infer text-processing consent from memory eligibility. UI follow-up wraps folder colour choices on narrow screens and states that unimplemented Assist capabilities are unavailable in this build.

Required current images no longer fall through to text generation on a text-only local route. Missing/empty prepared image bytes also fail before inference. Added a warm text-only engine versus vision catalogue regression and retained supported vision round-trip fixtures. This does not implement or qualify the separate NPU Assist vision pipeline.

Settings migration now rejects malformed policy objects and malformed/negative version fields before mutation, rather than treating them as missing legacy defaults. Explicit helper intent and permission maps remain recoverable.


## Added benchmark audit and Combined Final Context specification

The seventh supplied design specification is retained at `docs/design/GPT_Mobile_Combined_Mode_Final_Context_Design_Implementation_2026-10-17.md`. It adds 21 issues and T01–T28 acceptance scenarios; retaining the specification is not a claim that all acceptance scenarios have passed.

- Intelligence fixture v3 / suite v7 separates raw JSON compliance, unambiguous object recovery and per-question correctness. One complete outer JSON fence preserves semantic credit. Historical v2 grading explicitly accepts B or C for the ambiguous item, retaining its denominator; the new fixture has a checked unique solution. Historical manifests and score fixture versions remain distinct. Nine isolated production grader tests passed. The original benchmark export has not been supplied, so its reported 17/36 result has not been independently replayed here.
- Timing fixtures retain the 128-reference-unit minimum, request a wider output margin, and report insufficient samples separately from model quality. Timing eligibility is per complete block: older unsuccessful attempts remain in history without disqualifying later eligible blocks. Native tokens and reference units remain distinct.
- Final-response JSON schemas travel through RequestConstraints, the local adapter, conversation configuration and runtime IPC to LiteRT-LM 0.18.0 enableResponseFormat / ResponseFormat.json. The SDK retains tool-phase handling. Intelligence schemas contain shape only, never answers. Reasoning-enabled local intelligence reserves 2,048 output tokens; actual resolved settings remain recorded. Packaged SM8750 JSON/tool/cancellation verification remains pending.
- Workload vision labels now reflect actual current/history image input. Isolated exports explicitly report observed metrics without a cohort grade and explain absent anchors. Batch details show correct/total and format results. Remaining benchmark work: assisted-vs-raw and individual-vs-batch execution UI; expanded deterministic tool, image, context-boundary and separately labelled live tests; per-round metrics, complete modality/backend reporting and qualification presentation.
- Combined context now resolves the persisted combined-synthesis revision for every contributor, independent of current lead, retaining local-only provenance and explicit partial status. Conflicting owners and digest mismatch stop preparation. Existing revision/ownership persistence is reused. Dedicated new history records, explicit excluded-span receipts and all restart/lead-deletion scenarios remain pending.
- Conversation export versions are centralized (current conversation v4, archive v3). Android accepts v2/v3/v4; Python replay accepts the same versions and rejects unknown versions. Real producer-to-archive regression added. Directory preparation and ZIP writing/validation run on IO; only validated staged files are published. Send markers use the ordered async logger. Other durable startup/crash checkpoints remain explicit.
- Combined length contracts now distinguish minimum, maximum, exact and approximate bounds; approximate targets no longer become hard ceilings. Typed diagnostic event IDs no longer absorb labels; numerical fields retain numeric types. Main-thread disk violations, compiler limits and confirmed Combined contract failures receive actionable classifications. Organization/workspace header values are redacted.
- Exact GitHub workflow-dispatch responses are requested and retained as bounded host receipts. Two same-branch dispatches correlate by returned run ID, workflow and source SHA; arbitrary recent runs remain unknown. Receipts are currently process-local; durable receipt recovery and release-asset source attestation remain open. Reference: https://docs.github.com/en/rest/actions/workflows#create-a-workflow-dispatch-event.

Android CI for a196a030 passed unit tests, Room schema verification and Android lint; the APK build was still running when this update was written. New changes above require a fresh CI run. Python replay compatibility: five tests passed. Remaining Combined work includes executable complete claim dispositions, qualified semantic fidelity, progressive accepted revision repair, typed search/MCP recovery, incident grouping, detailed stage telemetry and measured release-equivalent rendering/coroutine qualification. These are release blockers, not implied completed features.
