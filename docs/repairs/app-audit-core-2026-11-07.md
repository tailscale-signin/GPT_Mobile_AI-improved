# Durable app audit core

Base: `c980ad3b4c13f8d9dd8bb4b5a393f4263d3b197f`, stacked after PR 811.
This feature extends the existing benchmark system. It does not alter the qualification gates for the preceding diagnostic repairs.

## Accepted scope and evidence

An app audit is an `APP_AUDIT` `BenchmarkBatch` with an `AppAuditRun` payload and an empty model queue. It requires no AI profile and creates no `BenchmarkRun`. The existing `BenchmarkExecutionCoordinator`, foreground service, `BenchmarkBatchRunner`, `BenchmarkStore`, database transaction and event journal remain the execution and persistence owners.

The catalogue freezes check IDs, component/source-area associations, optional selected profile IDs, accepted network policy, limits, configuration/fixture identities, source commit and source inventory counts before execution. App/source/catalogue drift blocks the remaining accepted checks. Resume never appends a new catalogue or substitutes a different profile. Missing or malformed audit payloads produce an explicit failed batch.

`PASS`, `FAIL`, `SKIPPED`, `BLOCKED`, `UNAVAILABLE`, `UNTESTED` and `CANCELED` remain distinct. A completed batch means traversal ended; it does not mean all app behavior was exercised. Each result records the named evidence kind, actual observation source, dispatch evidence, fixed diagnostic codes and numeric metrics. Configuration inventory and cached observations cannot earn the measured functional score. Live metadata checks prove their named handshake/schema assertion, not tool execution or model inference.

The functional score is `100 × passed weight / measured pass-and-fail weight`, and is unavailable when that denominator is zero. Results also expose exercised/planned and eligible-passed/planned counts. Unavailable, skipped, blocked, untested and canceled checks never become successful checks. Audit data is excluded from the model score population.

Source areas connect checks to the generated production inventory. Every area without an installed check receives an explicit external-harness entry. File and line counts describe indexed source; `lineCoverageAvailable` remains false. A fixture associated with an area does not prove every file or line in that area executed.

## Real local checks

These fixtures call current production code on fixed non-personal inputs. They do not read user files or make network requests.

| Check | Production path exercised | Assertion |
| --- | --- | --- |
| Calculator | `CalculatorTool` | Arithmetic result and missing-argument refusal |
| Date | `CurrentDateTool` | Fixed leap-day date, time and UTC zone |
| Text slice | `ReadFileSliceTool` | Clamped end line and invalid lower-bound refusal |
| Selected route | `InferenceRouting` | Selected destination retained; pressure does not enable unauthorized fallback |
| Usage math | `mergeTokenCount`, `tokenCostMicros` | Cumulative counters, saturation and exact monetary arithmetic |
| Tool receipts | `ToolResultEnvelope` | Actual unknown outcome remains distinct from non-dispatched permission rejection |
| Diagnostic redaction | `DiagnosticRedactor` | Fixture credentials and query values removed |
| Research identity | `SourceIdentity` | URL/DOI normalization and credential-bearing URL refusal |
| Gateway features | `GatewayCapabilities` serializer/predicate | Mixed values decode; only literal JSON `true` enables a flag |
| GitHub ref admission | `GitHubRefAvailability` | Exact ref refusal, no alternate ref substitution, and explicit successful recovery |
| Result codec | `AppAuditResult` serializer | Evidence survives a JSON round trip; this is not a database health claim |

Plugin probes, complementary production-contract fixtures, UI and sanitized export are independent integrations using the shared `AppAuditProbeSource` contract. This core report does not claim their separate tests passed.

## Bounded execution and interruption

The default accepted limits are 128 executed probes, 16 logical network probes, 15 seconds per probe, 180 seconds for the original run deadline, and 12 numeric metrics per result. Existing clients retain their physical HTTP/retry limits. The audit's network count is explicitly a logical probe count rather than a claim about individual HTTP requests. Model requests are not enabled by the app-audit options; the existing model benchmark modes remain separate.

The active probe is checkpointed before invocation. A remote probe checkpoints `beforeDispatch()` immediately before calling its existing client. Storage failure at that boundary prevents the call. A missing or inconsistent live dispatch receipt stops the remaining queue and retains uncertainty. Actual results are checkpointed before advancing.

Cancellation, process interruption and a lost result checkpoint retain an in-flight probe as interrupted, with unknown dispatch outcome when applicable. A started probe never becomes pending again. Completed result prefixes, accepted manifests and original budgets cannot be overwritten by a later checkpoint. Resume visits only untouched entries, preserves the original deadline and counters, and never replays an uncertain probe. Recovered active durations are null because their monotonic duration was not retained.

Typed authority invalidation stops all subsequent probes. An invalidation after the dispatch marker retains unknown outcome and execution-stage provenance; it cannot become a fresh non-dispatched rejection. Concurrent Stop survives a later probe checkpoint. Caller cancellation remains cancellation rather than a generic failed check.

## Source and regression mapping

| Area | Implementation | Behavioral regressions |
| --- | --- | --- |
| Catalogue, score and frozen source evidence | `AppAuditModels`, `AppAuditCatalog` | `AppAuditEvidenceTest`; catalogue/timeout/source-drift cases in `AppAuditRunnerTest` |
| Bounded admission and actual receipts | `AppAuditRunner` | Dispatch-before-read, default no-network/no-model, preconditions, missing receipt, authority, timeout and original-budget cases |
| Recovery and no replay | `AppAuditLifecycle` | Caller cancellation, pre-dispatch storage failure, lost result checkpoint and process recovery |
| Actual local production behavior | `AppAuditLocalProbeSource` | Eleven fixtures exercised by `AppAuditEvidenceTest` |
| Existing foreground/database integration | `BenchmarkExecutionCoordinator`, `BenchmarkBatchRunner`, `BenchmarkStore` | `AppAuditStoreIntegrationTest`: profile-free foreground ownership, database reopen/export, process recovery, immutable manifest/concurrent Stop and legacy batch decoding |
| Separate model score population | `DynamicScoreEngine` | `appAuditRecordsCannotEnterOrRescaleTheModelScorePopulation` |

## Validation and limits

The focused JVM harness compiles the complete current catalogue, engine, lifecycle, models, batch model and local-probe implementation, together with the production logic exercised by the fixtures. Existing compiled agent contracts, receipt classification and authority metadata are reused only after checking their source hashes. It runs with Java assertions, coroutine debug and stacktrace recovery enabled. Android/Room persistence, unrelated model benchmark DTOs, BuildConfig, vault and unused saved-job DTO boundaries are explicit adapters; disabled paths throw. These adapters are not device or Room execution evidence.

The final focused result is **19 tests passed in 0.920 seconds**, including the eleven actual local fixtures. Canonical fast validation passed **387 tests in 27.028 seconds**, with the existing dynamic-regex and physical-device limitations reported by the command. Pinned ktlint 1.3.1 and diff checks passed before the implementation commit. Independent review found no dispatch/replay blocker; its frozen build-identity omission was corrected and added to the Room regression. The final source manifest and logs are retained in the task scratch harness.

The four new Room/coordinator integration tests and the model-score isolation regression require full Android unit CI. No Android SDK or device runtime is available in the local host. Full compilation, unit, lint, APK/package and device gates remain required after integration. Generated source inventory outputs are refreshed locally for validation but are owned by the integration task and must be regenerated after all feature source changes are integrated.
