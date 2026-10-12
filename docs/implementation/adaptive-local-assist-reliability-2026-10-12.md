# Adaptive Local Assist and reliability implementation status

Base branch: `feat/deep-research-overhaul-completion-2026-10-12`

Base commit: `2a9564da8fc81f0e53e3d2d9a76302143fd87a1d`

Implementation branch: `feat/adaptive-local-assist-reliability-2026-10-12`

This branch contains the first implementation of the safe preparation and recovery foundations from the two supplied design specifications. It is not the completed Adaptive Local Assist overhaul. No app version or runtime dependency was changed. Advanced task classes remain unavailable until their implementations and qualification gates are completed.

## Implemented behavior

- Legacy Local Assist remains off by default and keeps record selection when restored. Preparing exact passages is a separate explicit opt-in; no existing enabled switch grants new document processing automatically.
- Global disable overrides profile and conversation modes. Profile and conversation controls retain their original scopes. Unknown future mode values decode to Off; unknown policy versions disable Assist without resetting unrelated preferences.
- Helper selection matches the loaded artifact. Optional inference acquires the existing runtime lease without waiting, checks warm identity and absence of another conversation while holding it, and never loads or replaces an engine. Assist conversations are closed before lease release.
- Document preparation occurs before inline context validation. Only exact quotations with validated character ranges enter a projection. Partial attachment coverage is explicit. The original user message remains unchanged.
- Authorized original evidence is retained under opaque response handles with revisions, bounds and expiry. An expansion tool uses the parent's execution budget and rechecks the master policy. Raw evidence is memory-only and not added to backup files.
- Delegated children inherit the parent's bound base tools rather than Assist-wrapped tools. They cannot re-enter the parent's preparation interceptor.
- Source selection and passage extraction share a response deadline and task count. Cancellation propagates, malformed or invented results preserve original evidence, and generated conclusions do not become personal memory.

## Reliability requirements

| ID | Implementation in this branch | Remaining evidence or work |
| --- | --- | --- |
| F01 | Native dispatch lifecycle distinguishes terminal outcome from quiescence; synchronous startup failures are reported; uncertain shutdown retires the process; stale journal tokens cannot clear newer requests; typed IPC decoding preserves configuration versus tool errors | Full asynchronous callback/process integration tests; actual artifact/template smoke test; exit-cause reproduction |
| F02 | Quarantine, uncertain shutdown and memory deferral stop further suite dispatch; skipped records retain the reason using the existing serialized enum | Device verification action and qualification; complete score/backup migration matrix |
| F03 | Explicit required tool names survive compact fallback and are rejected if unavailable; synthetic benchmark tools are explicitly required | Persistent tool capability qualification and its UI; explicit research requirement propagation through every restored route |
| F04 | Shared local context planner handles explicit schemas and memory enrichment; warm Assist respects loaded context capacity | Complete context snapshots and tokenizer/device boundary fixtures |
| F05 | Continuation planning uses resolved provider output caps; learned completion ceilings expire by route; configured segment events remain authoritative | Complete immutable budget snapshot contract and additional continuation/device fixtures |
| F06 | Component-based conservative memory range, checked arithmetic, separate warm demand, diagnostic shortfall and unchanged reserve | Model architecture calibration and measured peak estimates; explicit idle-engine release action |
| F07 | Cached catalogs no longer advance fresh request health; fresh catalog timestamps and provenance are separate; modern tool successes update request health without overwriting credential failures | Endpoint-generation race guards and full optional GET 405 integration matrix |
| F08 | MCP response status classification distinguishes authentication, rate limits, unavailable sources and server failures; existing safe retry/unknown-outcome protections retained | End-to-end typed failure envelope and Retry-After propagation across every reader/provider |
| F09 | Tiny allowances preserve useful discovery; existing durable research accounting retained; telemetry history bounded and rollups cached; nullable evidence yield, latency and cancellation metrics | Full physical-request allowance migration, end-to-end fan-out ledger, 300-source fixture and UI metrics wiring |
| F10 | Memory uses shared context planning, strict output schema and actual committed change counts; no-change is skipped rather than falsely reported saved | Statement batching, pending-review outcome counts, complete deletion/revocation fixtures |
| F11 | Bounded frame sample distributions, p50/p95/p99, refresh rate and build type | Release-device profiling; measured rendering changes; adopted performance threshold |
| F12 | Additive incident identifiers correlate scoped stage failures while retaining distinct events/continuations; rendering/no-change classification corrected | Full typed incident envelope and native exit-correlation evidence |

## Local Assist phases and release gates

| Area | Status |
| --- | --- |
| Existing exact-record primitive | Preserved and extended with optional original handles |
| Exact excerpt preparation and original expansion | Implemented; Android integration validation pending |
| Legacy-compatible settings, mode and scope controls | Implemented; backup and unknown-value tests pending |
| Atomic optional runtime admission | Implemented; contention/cancellation integration tests pending |
| Full-document batching and chunk coverage ledger | Not implemented |
| Qualified summaries, comparisons, requirement checks and bounded reasoning | Not implemented |
| Qualified autonomous read-only tool workflows | Not implemented; unavailable in all modes |
| Separate paired Assist Comparison protocol | Not implemented |
| Authenticated PC gateway assistance | Deferred optional phase; no new gateway destination enabled |
| Device and held-out quality/latency qualification | Not run |

Adaptive and Local first currently select the same bounded preparation primitives; their advanced scheduling behaviors are not enabled. The UI states this limitation. None of these mode choices permits engine eviction, cold loading, cloud escalation, autonomous writes or another worker coordinator.

## Validation

- Python repository tests: 79 passed.
- Android regex and resource preflights: passed.
- Kotlin formatting/parser checks: passed for changed files.
- Isolated executable Kotlin smoke checks: 33 passed against the real evidence store, native reducer, journal, memory estimator and research budget classes. This is not a full Android compilation.
- New Android unit regression tests cover source ranges/expiry/scope, invented passages, journal tokens, terminal races, unknown settings, memory range overflow, strict memory outcomes, required tools, tiny research budgets and ceiling expiry. These await the repository's Gradle test runner.
- Full Gradle tests, Android lint and APK build: blocked locally by unavailable Gradle download, JDK 21 and Android SDK.
- GitHub CI: not run; automatic approval review rejected the branch push because publishing was not explicitly authorized. The branch remains local.
- Connected-device, native smoke, release rendering and paired evaluation: not run.

## Continue from this branch

Approve pushing the reviewed branch, then run the existing PR validation workflow on its exact head and fix any compile/test findings. Complete the remaining implementation matrix before marking the overhaul finished. Device tests and qualification must retain the actual artifact hash, runtime, backend, firmware and settings. Do not merge, release, upgrade runtimes or label native execution qualified merely because isolated checks pass.
