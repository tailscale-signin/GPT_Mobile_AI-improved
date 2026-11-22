# Voice update: inherited diagnostic repairs and remaining gaps

## Reviewed baseline

The supplied capture `app-diagnostics-3176999369160508850.jsonl` is from app build 146 / `0.9.66.12`, commit `c023735c02368fef4c748cf7be97b9fcfe2cd513`. It contains 3,459 JSONL records: 2,588 events, 866 continuations, four duplicate batches and one summary. The duplicate batches retain 99 repeated records. Counts represent diagnostic records, not independent defects.

Implementation starts from draft PR #2254 at `2fb4f4434fa80ef26455d84f09e45fc425c6b629`. Its existing repairs were reviewed against the capture commit before extending them. The table records source and regression coverage already present at that head; it does not claim those tests were rerun on a device.

Source paths in the table are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`; named Kotlin tests are under the corresponding `app/src/test/kotlin/` packages.

| Captured concern | Existing repair retained | Regression evidence |
| --- | --- | --- |
| No credits reported as an incomplete retryable generation (line 86); generic processing failures (56, 71) | `data/network/error/ProviderFailure.kt` and `ProviderStreamFailure.kt` classify budget, rate limit and unavailable failures independently. `OpenAIAPIImpl.kt` retains HTTP status and safe provider identities for accepted SSE failures; partial output or an uncertain remote outcome prevents blind request replay. | `ProviderStreamFailureTest`, `OpenAIRecoveryTransportTest`, `ProfileFailureRecordTest` |
| Provider cooldown and saved retry correctness | `data/agent/ProviderWaitSchedulingResult.kt`, `ProviderWaitStore.kt`, `ProviderLimitResumeWorker.kt` distinguish durable waiting, scheduler failure, authority changes and an unknown persistence outcome. The original partial answer and scope remain authoritative. | `AgentRunProviderWaitTest`, `ProviderWaitRecoveryTest`, `ProviderWaitSchedulingResultTest`, `ProviderLimitResumeWorkerTest` |
| Gateway ready/capability retries and MCP connection failures (95, 1968–1971, 3294–3330, 3374–3377) | `data/network/LocalServiceHealth.kt`, `LocalReachabilityProbes.kt`, `LocalEndpointConnectionPolicy.kt` share same-origin probes within a network generation, apply bounded cooldown and retain port/protocol distinctions. `data/network/gateway/GatewayAvailabilitySource.kt` uses typed availability evidence. `gateway/desktop/Inspect-Connection.ps1` and `connection_diagnostics.py` provide separate endpoint checks. | `LocalServiceHealthTest`, `LocalReachabilityProbesTest`, `LocalEndpointConnectionPolicyTest`, `GatewayCapabilityDiscoveryTest`, `gateway/tests/test_gateway_connection_diagnostics.py` |
| Repeated discovery invalidations | `data/readiness/CatalogueInvalidationPolicy.kt` and `data/agent/tool/McpDiscoveryContext.kt` separate catalogue authority from recent-profile/inference changes. Only 20 of the 182 recorded MCP discovery decisions start acquisition; the remaining decisions are not independent network requests. | `CatalogueInvalidationPolicyTest`, `McpClientManagerTest`, `McpDiscoveryDiagnosticsTest` |
| Re-reading an already complete 327-file comparison (11, 13–19, 28) | `data/github/GitHubComparisonCoverage.kt` and `GitHubReviewComparison.kt` retain monotonic coverage for the same immutable comparison identity. Explicit refresh/live verification begins a new generation; changed SHA/binding/query, incomplete traversal and untrusted receipts cannot inherit completion. | `GitHubComparisonCoverageTest`, `GitHubAuditEvidenceTest`, `GitHubReviewBaselineTest` |
| Partial acceptance mistaken for completion | `data/github/GitHubRunAcceptance.kt`, `GitHubAcceptanceExplanation.kt` retain requirement and capability states, exclusions and contract evidence. One of four accepted requirements and blocked/untested capabilities do not become successful completion; zero applicable checks is not proof. | `GitHubRunAcceptanceTest`, `GitHubAcceptanceExplanationTest`, `GitHubIntegrationAcceptanceTest` |
| UI work and process memory attribution | `presentation/ui/chat/ChatRunProjection.kt`, `ChatValueProjection.kt`, `LiteralMarkdownParagraph.kt` retain stable projections and avoid Markdown work for plain text. `data/diagnostics/DiagnosticExportMemory.kt`, `DiagnosticCaptureTimings.kt` distinguish fresh process-wide counter intervals from exclusive allocation attribution; database export paging uses the Room 52 index. | `ChatRunProjectionTest`, `LiteralMarkdownParagraphTest`, `DiagnosticExportMemoryIntervalTest`, `DiagnosticCaptureTimingsTest`, `DiagnosticDatabaseSourceTest`, migration tests |
| Incident grouping | `data/diagnostics/DiagnosticIncidentTracker.kt` groups related evidence with session/process/operation identity. Historical messages and repeated records remain evidence rather than independent issue counts. | `DiagnosticIncidentTrackerTest` |

## Gaps closed by this follow-up

### Nested payloads cannot become the current operation's error code

Capture line 60 is a successful issue-list result whose returned issue title contains `GITHUB_TASK_INCOMPLETE`. `DiagnosticExecutionEvidence.kt` previously searched the complete event message for any `GITHUB_*` token and promoted that nested text into the canonical error code, despite `errorCode=none` and a completed execution state.

The legacy fallback now requires an actual failure event, a separately delimited top-level code, and absence of an explicit error-code field. It scans JSON nesting and string escapes before accepting a candidate. Current structured receipt aliases remain unchanged. `DiagnosticExecutionReceiptTest` covers successful reads, nested codes within a failed operation, a genuine legacy top-level code, explicit unknown, and ordinary prose.

### Bound the captured execution coroutine itself

Capture line 887 reports `AgentRunCoordinator$execute$2.invokeSuspend` at 25,050 code units. The inherited refactor and packaged DEX guard covered Gateway recovery methods, leaving this execution method outside their scope.

`AgentRunCoordinator.RunExecution` now owns each request's publication/checkpoint state. Admission, stream collection, terminal publication, cancellation, failure recovery and successor handoff are separate suspend methods. The enclosing coroutine scope, ownership checks, cancellation origin, NonCancellable persistence, checkpoint drain order and early-return behavior are preserved. The extraction changes method size and state ownership without adding another provider attempt or changing acceptance rules.

`AgentRunExecutionTest` exercises the real coordinator entry path with interleaved independent replies, a lost execution claim, an accepted Gateway handoff, and cancellation after a partial answer. Existing provider-wait tests cover pause/reconciliation and authority revocation paths.

`scripts/check_chat_method_size.py` now measures every coordinator and generated class in addition to chat/voice UI classes. APK workflows require an actual `execute` definition, so a continuation or accessor cannot stand in for the entry point. The parser still reads packaged `code_item.insns_size`; a source-line count is not a performance claim. The regression fixture reproduces the 25,050-unit failure and verifies extracted or renamed phases remain in scope.

## Validation and remaining measurement

- Focused host Kotlin run: `DiagnosticExecutionReceiptTest`, 5 tests passed against the actual parser, redactor, classifier and execution-evidence sources. Only unexercised Android constants / vault / free-provider serialization boundaries were supplied as host shims. The two new regressions fail on the inherited implementation.
- `python -m unittest scripts.tests.test_chat_method_size`: 15 tests passed.
- `git diff --check`: passed at the follow-up checkpoint.
- Canonical `bash scripts/validate-local.sh fast` reached the generated app-audit inventory check and correctly requested regeneration after the new source/test changes. Shared generated files must be refreshed after all implementation branches are integrated.
- Android compilation, the new Robolectric execution tests and existing affected suites, and actual packaged DEX sizes remain release gates for the integrated SHA. Host parser fixtures do not certify those gates.
- The capture's 339 janky frames among 49,004 sampled frames, eleven frames above 100 ms and a 334.082 ms maximum do not establish a GPU bottleneck. The maximum follows voice exit. The native/Java peaks establish memory pressure to measure; subsequent native release prevents claiming a confirmed leak from the peak alone. Device replay must compare frame timing and memory under the same voice workload after the update.

Voice synthesis, caption motion/highlighting and hardware provider qualification are owned by the parallel voice implementation. No issue closures or remote publication are performed by this follow-up branch.

## Voice execution evidence contract

The parallel voice producer now distinguishes the selected backend from an observed effective provider and retains a fallback receipt across an engine switch. `VoiceExecutionDiagnostics.kt`, the incident capsule and authentication contract accept only closed backend/provider/source/target/outcome/failure enums and a typed fallback count in `0..100`. `SYSTEM` is a valid effective provider after Android recovery; `KOKORO` is a valid fallback source when initialization failed before a concrete execution provider was observed. Neither value certifies accelerated synthesis. No speech, raw exception, model path or provider request ID is part of these fields.

The additive `VOICE_EXECUTION_EVIDENCE_V1` feature is advertised only in extended reporter readiness, leaving the legacy readiness response unchanged. Approved reports wait for that feature and reporter protocol 2 before sending their original bytes. Client round-trip and submission tests cover fallback failure retention with a `SYSTEM` engine, private/unknown values, wrong diagnostic areas, omitted evidence and compatibility negotiation.

Focused validation of this addition: three actual-source Kotlin enum/count tests pass (eight tests with the provenance suite); 108 authentication contract/reporter-configuration tests and 69 authentication app tests pass. Android-backed round-trip/submission tests remain part of the integrated Android test gate.
