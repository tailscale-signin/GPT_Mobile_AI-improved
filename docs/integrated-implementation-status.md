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
- Answer visibility: primary title directives removed; canonical answer predicate used by runner, persistence, history and timeline; one existing bounded empty-answer recovery reused; first visible Markdown bypasses the sampling delay. Ordered asynchronous persistence and idempotent render metrics remain open.
- Local runtime: actual asset accounting, cached validation, prompt bridge, artifact planning, exact token-prefix reuse and optional selected fixed-context primary preparation. Two primary hub tabs and corrected marketplace filters/runtime labels. Full qualification, native GGUF multimodal/tools, experimental Hexagon and native NPU tuning remain open.
- Glass appearance: shared 85% background tokens with contrast adjustment, shared wrappers and bubble menu; Glass/Solid and Auto/Off draft/persistence/backup/legacy mapping plus preview. Custom root-plate inventory/migration, brand-icon migration, qualified bounded blur renderer and full visual/device matrix remain open.

## Validation evidence

- 132 Python script tests passed, including the two new APK budget fixtures.
- 20 isolated Kotlin/JUnit parser/schema/range tests passed using production algorithms and boundary stubs.
- 4 isolated Kotlin/JUnit Anthropic DTO and restore-journal tests passed using production implementations and a fake vault boundary.
- 2 isolated Kotlin/JUnit model-memory and asset-receipt tests passed, including a sparse 4 GiB model and same-size in-place modification with preserved mtime.
- Android resource, regex, exported Room-schema and QNN provenance checks passed.
- Ktlint 1.3.1 formatting and native JNI syntax check against the pinned llama.cpp headers passed.
- Full Gradle Android compilation was attempted with JDK 21/Gradle 9.8.0; dependency resolution could not reach the configured Maven repositories from Java in this environment. No Android unit/lint/build success is claimed.

## Release gates

Do not merge as a completed implementation of all four documents. Complete the open software packages above; run full Android compilation/unit/lint, process-kill restore and workspace tests, private/public APK delivery, provider completion and rotation fixtures, composite-opacity screenshots, and target-device local/NPU qualification. Repository required-check protection needs a separate authorized administration-capable path.
