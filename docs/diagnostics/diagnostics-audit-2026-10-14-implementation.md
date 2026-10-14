# October 14 diagnostics repair implementation

Branch: `fix/diagnostics-audit-2026-10-14`
Base: `ca554d092b45ab7096995821d265165945c5730d` (current main, includes restore-progress repair).
Evidence: supplied GPT_Mobile_AI_Diagnostics_Audit_2026-10-14.md, logged source `455d6a0`.

## Implementation plan and delivered changes

1. Resolve output allowance centrally before physical dispatch. Absent/zero app caps use 4096 tokens per round; explicit writing caps remain available. Bound by known output limits and final serialized context headroom. Write the positive allowance into OpenAI-compatible, Responses, Anthropic or Gemini JSON, then measure and admit those bytes. Keep the original input, word goal and existing bounded continuation pipeline. Log default/explicit/ceiling provenance; do not imply the serving route confirmed catalog metadata.
2. Stabilize authorization preparation. Capture/recheck policy up to three times before work begins; never retry a stale physical send without rebuilding. Ignore memory usage bookkeeping only when exact content, revision, source, scope and privacy still match a currently visible fact. Retain the dispatch authorization checks and completed-action ledger. Provide distinct actionable memory/policy/permission/output preparation errors.
3. Retain MCP protocol decision caching, network-epoch invalidation, endpoint health and explicit reconnect already present on main. Add bounded reconnect jitter; classify wrapped DNS failures as connectivity failures. Preserve authorization and quota cooldowns independently of connection health. Never replay uncertain side effects.
4. Preflight enrichment on an IO coroutine, including enable/learning/local-model policy, temporary/suppressed scope, user-message identity and eligible statements. Persist only source references/digests when a model is absent or busy; resume via existing runtime/work-state observations. Keep WorkManager unique-work deduplication and the worker's final consent checks.
5. Select the progressive catalog before context planning, retaining the separate authorized executable set for later discovery. Separate authorized schema accounting from the advertised catalog and physical serialized JSON. Log selected catalog sizes each round and wire schema bytes/token estimates at the physical boundary. Counts remain explicitly heuristic and are not provider tokenizer measurements.
6. Add fixed-size frame histograms reporting p95/p99 upper bounds, >100 ms stalls and overflow; add opt-in named memory DAO timing without SQL/arguments/data. Move queue database/preference work to IO. Extract catalog normalization from AgentToolResolver.resolve to reduce the large coroutine's generated code.
7. Add regression tests and retain existing modern/legacy MCP and dispatch-authority coverage. Publish a draft PR to exercise full Android tests/lint/build.

## Audit coverage and remaining qualification

| IDs | Outcome | Qualification still needed |
|---|---|---|
| 01–02 | Positive adaptive physical caps; known ceiling and context headroom bounds; retained input/continuation | Real OpenRouter short/long/custom-routing requests and provider usage/cost comparison |
| 03 | Bookkeeping-only updates accepted under live visibility checks; actual content/consent changes block safely with retry guidance | Automatic reconstruction after an actual memory edit is not implemented; user retries from current state |
| 04 | Bounded atomic policy capture; existing display-only settings exclusion retained | Changes after preparation still fail closed and require fresh user retry |
| 05 | Permission category and settings/fresh-request instructions; existing recovery block and completed-result ledger preserved | A new Grant & Retry / Retry without evidence three-action popup is not implemented |
| 06–08 | Existing cached protocol/network-epoch recovery retained; jitter and wrapped DNS classification added | Actual phone gateway route, workstation sleep, Wi-Fi/VPN transitions, unsupported legacy GET behavior require device/mock transport qualification |
| 09 | Selected/wire-schema accounting distinguishes actual advertisement from preview | Planner now budgets the initial selected schemas; verify initial and later discovered rounds against provider token usage |
| 10 | Pre-enqueue eligibility and persisted model/idle deferral | Device WorkManager readiness/privacy races and exactly-once enrichment |
| 11–12 | Frame percentile/stall counters; named DAO elapsed timings; queue work moved off caller thread | Perfetto/Macrobenchmark profiling; elapsed DAO coroutine time includes scheduling, not SQL-only time; no speculative index changes |
| 13 | AgentToolResolver final normalization extracted | Compiler instruction warning measurements; full completeChat coroutine stage refactor remains follow-up work |
| 14 | No speculative IME lifecycle modification | Reproduce focus/back/edit on ASUS before changing input behavior |
| 15 | Output/source provenance, selected/wire estimates, explicit preparation categories, frame/DAO correlation | Provider-effective settings remain unverified unless reported by provider |
| 16 | JNI informational messages and user-requested process exit remain observations | Native crash/ANR artifact required before claiming a native crash |

## Validation

Local: Android regex/resource preflights passed; unified reliability inventory check passed; 116 Python script tests passed after integrating current main; `git diff --check` passed. Script test fixture messages about instrumentation are fixture verification, not a real device test.

Android Gradle tests were attempted but could not start: downloading Gradle 9.8.0 from services.gradle.org failed with `Network is unreachable`. This workspace also has JDK 17 and no configured Android SDK; CI supplies JDK 21/SDK 37. New Kotlin tests are committed but are not reported as passing until CI executes them.

Device qualification: short chat and requested long writing; mutate/revoke memory while queued; change authorization during send; disable learning and verify zero new jobs; load a local model and confirm one deferred enrichment; gateway stop/restart and network switch; record Perfetto and compare frame p95/p99/100 ms stalls; rapid keyboard/edit/back navigation. Never log bodies, credentials, SQL parameters or fact text.

No main merge, version bump or release is part of this branch request.
