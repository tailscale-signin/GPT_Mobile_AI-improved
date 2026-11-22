# Repository review implementation — November 22, 2026

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Base branch: `main`; inspected base: `c023735c02368fef4c748cf7be97b9fcfe2cd513`.
Shared PR: **#2254**, `fix/diagnostic-execution-completion-2026-11-22`.
Inherited head: `f225c208e80393f0232ba7849340461fd23901b9`.
Integration branch: `work/review-implementation-2026-11-22`.

## Authorized outcome

Implement the actionable improvements from the repository/ChatGPT review, preserve
the existing diagnostic repairs and concurrent changes, publish the changes on the
same draft PR, require successful current-head verification before merging, then
qualify and publish the coordinated Android and Gateway release. Never force-push,
discard another worker's commits, weaken required checks, or replace published
release assets. Recheck both shared head and main before every remote mutation.

## Acceptance map

| Area | Required result | Owner / evidence |
| --- | --- | --- |
| Completion and persistence | Preserve typed atomic checkpoints, comparison coverage, provider causes, bounded continuation, and saved waits | Existing PR diagnostic repair ledger; integration regressions |
| Caption motion | Keep line identity through continuous following, multi-line moves, manual browsing, and reduced-motion settings | Voice implementation and behavior/instrumented tests |
| Spoken word | Bold theme colour fades in over 250 ms; preserve speech-start fade and generation hiding | Voice animation policy and caption tests |
| Voice readiness | Make installed language and actual speech provider clear; preserve qualified accelerator fallback | Voice readiness and runtime evidence |
| First successful chat | Distinguish configured, available, and actually observed readiness with actionable existing controls | Readiness and setup presentation |
| Data route | Explain phone, PC, and cloud routes using available evidence; preserve profile-selection freedom | Runtime/status presentation |
| Optional orchestration | Avoid unnecessary adaptive work while preserving explicit participants, required review, and hard budgets | Task-aware delegation regressions |
| Architecture | Extract a stable provider boundary with behavior preservation | Provider dispatch regressions |
| Outcome efficiency | Expose successful-task outcomes, elapsed effort, retry overhead and known cost/usage provenance | Benchmark metrics, UI, and export regressions |
| Research and documents | Preserve implemented-versus-pending engines, partial extraction and scan handling without false full-read claims | Capability/readiness guidance and existing extraction contracts |
| Portability | Make deliberate backup/restore and recovery paths discoverable; preserve encrypted export boundaries | Existing backup flow and setup guidance |
| Gateway setup | Explain shared network access and authenticated owner capabilities separately | Existing identity boundary and readiness presentation |
| Hardware measurement | Provide repeatable observations and retain unknown results until compatible hardware executes | Voice/runtime benchmark evidence; no invented device measurements |
| Publication | Exact PR/main checks, artifact identity and immutable public release readback | CI and publication receipts |

The comparison's observations about different product architectures are retained
as context. Native full-duplex speech, new multilingual model distributions,
additional operating-system clients, external model quality and Windows signing
certificates require their own compatible runtime or credentials. Existing support
must remain accurately described; a UI option, passing unit test, or static source
inspection cannot qualify those external capabilities.

## Validation journal

- Intake: main and the active draft head matched the revisions above. Main's
  published versions are Android 0.9.66.12/build 146 and Gateway 18.1.0.
- Current candidate versions inherited from PR #2254 are Android 0.9.66.13/build
  147 and Gateway 18.1.1; publication is not yet complete.
- Local environment has JDK 21. Full Android/device qualification is owned by the
  existing hosted CI until an Android SDK/device is available locally.


## Outcome efficiency implementation

`benchmark-task-efficiency-v1` reports the total recorded non-warmup elapsed effort divided by fixtures graded PASSED. Incorrect answers, interrupted work and dispatched cases subsequently skipped retain their elapsed effort; a completed request alone does not count as a successful task. Retry duration is disclosed without adding it twice. Missing durations and overflow keep totals unknown. Setup, downloads and demonstrably unattempted skipped cases remain outside this fixture measurement.

Configured token prices are captured immutably at run creation. API cost estimates require matching profile/model identity, valid unexpired rates bound to the selected endpoint, transport, model and routing context and final reported input/output usage. An incomplete participant or retry usage population stays unknown, rather than reporting a misleading partial total. Local API charge is distinct from device energy/hardware cost. Cost is displayed with six decimal places and exported with its scope, evidence gaps and policy.

Validation: 17 focused Kotlin/JVM behavior tests passed using the production efficiency, coverage, price arithmetic and presentation formatter source with narrow unrelated Android/serialization/model boundaries. Added a serialization/export regression for full Android CI. These checks do not replace Android compilation, Compose/Robolectric tests or device qualification.

Concurrent voice work arrived on the shared PR while this implementation was in progress; remote head `2dfb90432b2981fdb3183b71883df2e3217f77d2` is being preserved before integration of our review deltas.

## Integrated validation checkpoint

Preserved the complete concurrent voice candidate through `2dfb9043`, including the QNN host qualification limitations. Integrated setup/runtime route guidance, provider dispatch and routine automatic preparation, outcome-efficiency reporting, route-bound price snapshots and private-source redaction, plus the voice measurement export and fractional-caption spacing regression. Pricing capture uses the existing account/provider catalogue without a network refresh. Positive costs below one micro-currency unit per successful fixture render as a bound rather than zero.

The active main ruleset is `27005122` (Verified main changes): a pull request and strict successful `Required verification` and `Analyze (java-kotlin)` checks are required; the repository lists no bypass actors. Publication additionally waits for all exact-main release workflow gates. These policies are preserved.

The final combined local host pass (including concurrent recovery commit `3d672c04`) completed 605 script tests successfully, with generated architecture/audit/agent/diagnostic metadata, dependency catalogue, secret scan, Android regex, resource/icon/native-provenance and diff checks passing. All 51 changed Kotlin files passed ktlint, and the scoped release validator passed. The integrated pricing/efficiency/export-helper/formatter harness passed 43 tests; the separate readiness, dispatch/routing and voice suites passed 12, 12 and 41 tests respectively (108 focused tests across these suites). Full Android/Hilt/Compose/serialized-export and device regressions remain mandatory in current-head CI. No physical performance result or publication is claimed at this checkpoint.

## Full-suite repair and canonical Room 53 handoff

The earlier f225c208 full Android run executed 12,256 tests and exposed 29 failures. Candidate failures were reproduced and inspected against the newer source before changes. Gateway fixture isolation and explicit connection rechecks now pass 37 focused tests, preserving all HTTP/cancellation/TLS and retry safeguards. Bounded cause traversal, safe upstream provider identity and compact GitHub descriptions pass 11 focused tests; original schema budgets remain 1600/2000 and every input constraint is preserved. The diagnostic recovery fence fix and scoped incident/grouping helpers pass 14 focused tests, with the production fence regression confirmed red before the fix. Export timing mocks and Markdown foreground observations retain their existing privacy and rendering assertions.

Concurrent privacy highlights and conversation badges at eeb2a205 advance Room to 53; their source and tests are preserved. The genuine KSP export was recovered from Diagnostic audit run 45058161842, job 137318034810, artifact 14768098633. Its source head is 2fa6f59f; GitHub's tested merge 7676c6df has an identical source tree. The artifact SHA256 is c601674748694d9511ab3b85673ee84ece8ef476cbdb6fb02926b90d5b23b3e6. The 164660-byte schema has SHA-256 4d9294e56972dd23bb99841b263e026d9f4d97faa0acf71d4d40da4f9b28afdb and Room identity 489fe6ad9e5f86a5af203af446e77dbf. Downloaded bytes match the lossless KSP job-log copy. Database entities, converters and migrations are unchanged from that artifact source; subsequent DAO transaction changes preserve authoritative privacy evidence across stale snapshots. Current-head KSP verification remains required. The missing-schema handoff gate was retained and no schema was fabricated. Generated metadata now derives from that export. Full current-head Android CI remains mandatory before merge.

## Concurrent full-suite repair reconciliation

The shared branch advanced through 7c7634df while the repair upload was prepared. The expected-head check stopped the branch mutation, and the changes were merged normally. Equivalent schema, traversal, fixture and renderer fixes retain the stronger incoming assertions. The combined source also preserves selected-service-only Gateway rechecks, transactional privacy receipts, conversation ownership checks, and required Room/caption instrumentation on both emulator variants. The subsequent 6e03f1e0 Research settings callback fix is included.

After reconciliation, the actual-client Gateway harness passes 39 tests, the provider/policy/schema harness passes 11, and the diagnostic helper harness passes 14. Unique supplemental tests retain shared cooldown enforcement across clients, cache-only refresh behavior, non-completion of blocked GitHub work, process-scoped incident isolation and malformed recovery-fence rejection. Provider diagnostics preserve safe numeric 401/429 codes and bounded hash-only correlation for unrecognized upstream codes. All 12 Kotlin files differing from 6e03f1e0 pass ktlint; release-scope and committed Room-schema checks pass. These focused results do not replace the full current-head Android suite.

Canonical validation after reconciliation: `bash scripts/validate-local.sh fast` passes all 608 host tests and the generated metadata, dependency, secret, regex, resource, icon, provenance and diff checks. Full exact-head Android CI and publication remain pending.
