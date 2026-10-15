# Smart Readiness, Local Assist and October 14 diagnostic repairs

Branch: `feat/smart-readiness-local-assist-2026-10-14`

Base: current main at task start, `99407f05ac7685c519d189692028a1353b191580` (PR #685), newer than the design's pinned `91312450…` baseline. No version bump or release publication.

## Implemented production paths

| Area | Implementation and state ownership |
| --- | --- |
| Common Send preparation | Whole-message DIRECT / CONTEXTUAL / REQUIRED_WORK planner in ChatRepositoryImpl, shared by UI, durable queue, retries and combined paths. DIRECT skips optional discovery and memory work. Required attachments, selected research/delegation, explicit tool selection, local generation and recovery retain their required paths. Contextual memory uses one remaining 250 ms budget. |
| Foreground priority | Managed SupervisorJob scope, bounded scheduler with two permits/eight queued keys, idempotent generation and UI-persistence leases, main-process/startup/usable-frame gates, existing foreground tracker, metered-network controls and trim handling. UI signals carry IDs/booleans and do not resolve the readiness Hilt graph on the main thread. |
| MCP metadata | One bounded memory-only catalogue above modern/legacy transports. Refreshes are single-flight with detachable consumers; cache reads do not join network locks. Revocation epochs fence late results. Trim evicts metadata without canceling active required consumers. OAuth prewarming uses only existing installed authorization and silent refresh. |
| Lazy capabilities | Existing discover_available_tools facade extended for small/cold catalogues. Schemas are acquired only when required, outside selection monitors. Later rounds gain freshly authorized and budget-bound tools. Alias collisions and oversized metadata are rejected. Repeated discovery can advance beyond the first four connections. Existing context and serialized admission remain authoritative. |
| Provider catalogue | Common Send no longer awaits OpenRouter catalogue refresh. Existing provider limits retain their declared/cached provenance and final request admission. Prewarming reuses the existing repository refresh owner. |
| Memory | Read-only metadata load and lexical snapshot recall do not write facts, prune storage, embed drafts or perform network/model work. Draft previews are opt-in, debounced, IME-aware, scope-checked and ephemeral. Autosave no longer cancels its own preview. Automatic capture is deduplicated by accepted message/content identity. Native indexing uses small slices and yields to accepted work and benchmarks. |
| Local Assist | Shared preparation/admission leaf used by real tasks, explicit tests and optional idle preload. Explicit preload works with Fast Response without changing its per-task warm-only policy. Device protection remains; diagnostics retain recovery cause/countdown and requested versus observed accelerator/backend. A loaded model is distinguished from tested generation. |
| Assist budgeting | Exhausted physical allowances are rejected before child construction; actual generation alone reserves attempts. Research uses separate preparation/physical-start generation clocks. Optional subscribers detach on their deadline without waiting for independent native cleanup. Wrappers without validated artifacts are reported as skipped, not completed local inference. |
| Native lifecycle | LiteRT lifecycle calls use one owner thread. Terminal metric collection is deferred outside the SDK callback. Canceled blocking OPEN results are closed before publication. Synchronous JNI submission errors retain unknown-outcome ownership until an actual callback or existing isolated-worker quarantine; they are never falsely acknowledged as stopped. Failed cleanup invalidates readiness, records quarantine evidence and requires worker restart. Existing crash isolation, artifact checks and exact backend quarantine remain intact. Adapter coroutine classes are retained in minified builds. |
| News contract | Optional city/region `location` is declared, validated and folded into the bounded query. It is not interpreted as a two-letter country or an endpoint. |
| Enrichment | Existing exact-source validation and bounded model repair remain. Added bounded deterministic JSON/fence/kind normalization and duplicate-quote removal, without inventing facts. Foreground work and benchmarks defer enrichment. Prerequisite deferrals remain distinct from invalid model output. |
| Timing and response UI | Existing journal extended to schema 6 with monotonic timestamps, preparation plans, cache observations, benchmark wait, actual OkHttp call/body/header milestones, parsed text/reasoning/tools and first markdown draw. The one-use internal transport correlation header is removed by an application interceptor before network transmission. Provider cooldown is visible and cancellable. Existing response details show Preparation & Readiness plus saved assist/backend state. |
| Settings and recovery | Versioned nested settings use atomic mutation, serialization defaults and Advanced Settings/search UI. Pilot foreground warmup defaults off; prediction and metered warmup default off. Derived cache is memory-only, so no schemas, draft previews, session handles or health are added to backups. Restore/policy changes invalidate state before cleanup; backup/restore holds a speculation-priority lease. |

No new model download, generating warmup call, independent inference runner, ongoing Android service, telemetry SDK or persistent draft store was introduced. Local-only and temporary-chat boundaries, source validation, profile selection, provider limits and benchmark exclusivity remain enforced by their existing owners.

## Diagnostic-audit interpretation

The supplied audit refers to an older `455d6a0…` build. Main already contained `.single()` inspection consumption, native-process isolation, crash journals/quarantine, declared-CPU checks, bounded output allowance and one memory schema repair. Those protections were retained and hardened, rather than removed to force a green readiness state.

A GPU-only package cannot gain CPU support through a setting. The existing fallback remains restricted to artifacts that explicitly declare compatible CPU execution, with a visible instruction to install a CPU-capable export otherwise. This change does not claim that a different model's weights are an equivalent fallback.

Inactive InputConnection messages, user-canceled tools/maps, unverified optional capabilities and ignored platform scheduling hints do not independently prove an application defect. No forced keyboard reset, retry of a canceled action, or unsupported capability advertisement was added. On-device traces remain necessary to attribute individual janky frames and identify the precise ART `openSession` abort and finalizer stacks.

## Verification

| Check | Result |
| --- | --- |
| Kotlin/JUnit readiness, scheduler, settings serialization and HTTP correlation | 15 passed in standalone harness. |
| Admission and original-cause/countdown protection | 4 passed in standalone harness; Android system APIs were stubbed. |
| News query, memory output contract and durable native quarantine | 9 passed in standalone harness. |
| Progressive catalogue selection and suspended cold discovery | 9 passed in standalone harness; unrelated Android/provider contracts were fixtures. |
| Shared assist ownership and detached cleanup deadline | 5 passed in standalone harness; unrelated policy serialization was a fixture. |
| Repository Python tests | 116 passed. Some output includes fixture-generated device/APK messages; those are not live device results. |
| Android regex compatibility, reliability scope checks, resources/icon/native provenance preflight | Passed. Static/provenance checks do not qualify execution on a phone. |
| ktlint 1.3.1, changed Kotlin files; whitespace validation | Passed after formatting. |
| Full Android unit tests, Hilt graph, lint, APK builds including GenieX | Not executed successfully: Gradle distribution download is blocked by this environment's network. |
| Robolectric integration fixtures and native lifecycle regression tests added/updated in this branch | Pending full Android Gradle execution. |
| Physical-device cancellation/crash/GPU tests and release-like latency/jank measurements | Pending; no Android device was available. No claimed fix-rate or latency measurement. |

The standalone harness compiles the actual selected production logic and tests with narrowly scoped fixture dependencies. It is not a replacement for the production Android dependency graph, native execution, both supported runtime builds, or the design's complete T01–T56 device/race matrix.

## Publication status

The user explicitly approved publication to `tailscale-signin/GPT_Mobile_AI-improved` and a draft PR. The branch is being published through the connected GitHub API because the local Git transport has no credentials. No merge or release is included. Android CI and device qualification remain pending.

Do not enable release-default speculation or describe this change as release-stable before Android compilation, integration tests and the physical-device acceptance gates pass.
