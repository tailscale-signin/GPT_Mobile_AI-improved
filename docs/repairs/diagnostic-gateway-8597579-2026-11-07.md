# Gateway and MCP reliability audit — capture 8597579

## Scope and evidence

This work starts from `91dfce15bc481c4bbdb088bcadd28489436dbb1b` on `work/diagnostic-gateway-8597579`. It changes the existing Android MCP supervisor and network health accounting, and adds a bounded Python gateway regression. No new transport manager or job coordinator is introduced. Private log contents, prompts, credentials, profile IDs and raw diagnostic exports are not included here.

The following positions refer to the original diagnostic export, not repository line numbers. Multiple records about the same request must not be counted as independent failures.

| Evidence | Interpretation | Action or limit |
| --- | --- | --- |
| Lines 113, 1892 and 1896 describe one model segment and its parent terminal result: `TLS_FAILURE`, zero output, failed stage TLS, nonretryable, verification 0/1. | One failed model transport appears at several layers. The request metadata identifies OpenRouter and the selected DeepSeek model; transport completion and provider identity remain unverified. | Preserve TLS validation and model selection. This change does not repair or claim to diagnose the remote certificate chain from an exception label. |
| Lines 1871–1891 contain the requested-model, serialized output allowance and partial provider receipt. | A requested model, declared context limit and serialized output setting are not evidence that a runtime accepted or executed them. The receipt lacks a server request ID, finish reason, usage and runtime confirmation. | Retain the current distinction between requested identity and verified runtime observations. No inferred fallback or successful inference. |
| Lines 241–242 show `TOOL_RESOLUTION_FAILED`, `PROTOCOL_CONFIGURATION`, `McpProtocolConfigurationException`, health `UNREACHABLE`. | The retained resolver record omits the exception's safe diagnostic code. The initiating health failure is outside this window, so the exact invalid protocol/schema field cannot be recovered. | Expose typed error code and retryability in the health snapshot. The resolver owner should include these fields in its repeated summary record. Do not guess that a valid protocol schema caused these particular failures. |
| Lines 1908 and 1918 contain DNS failures for Jina's configured MCP URL, with successful HTTP responses elsewhere before and after them. | This capture supports temporary DNS failure. It does not establish that the public service or configured default is permanently unavailable. | Keep the endpoint and the existing host-wide DNS coordination. No public endpoint is changed. |
| Line 209 contains a stale inventory precheck rejection with no dispatch. | This is separate from the failed model transport and protocol configuration. | Inventory/execution owner handles this path; this branch does not modify generic resolver or execution ownership. |

## Confirmed source fixes

### Preserve the original failure and its recovery policy

`McpClientManager.kt` now retains `lastErrorCode` in `McpConnectionHealth` and exposes `failureRetryable`. Codes are selected from internal protocol diagnostics and fixed transport categories. Existing redaction and message limits remain in effect. A verified successful response clears stale failure code and retry status.

`LocalServiceHealth.classify` now recognizes nested `SSLException` and `CertificateException` before interpreting generic words such as “initialization” or “timed out.” The MCP manager uses that result to mark the TLS stage and pause automatic discovery in its existing reconnect gate. An explicit reconnect can perform another fully validated request. Time passing alone cannot resume a certificate-failed connection or trigger legacy transport fallback.

`LocalServiceHealth.recordFailure` now ignores a `LocalServiceUnavailableException` anywhere in the bounded cause chain. Previously an SDK-wrapped cooldown rejection could be counted repeatedly even though no network attempt occurred, extending the retry window. The new behavior regression failed against the previous implementation and passed after this guard was corrected. Cancellation remains excluded from transport failure accounting.

The existing endpoint/protocol/authentication pauses, catalogue concurrency, DNS coordination, transport proof checks and selected-model bindings are retained.

### Bounded disconnect and journal reload qualification

`gateway/tests/test_gateway_connection_soak.py` exercises production registration, durable claim, disconnect marking, orphan handling, final commit, SQLite restoration and the authenticated HTTP result route. Six rounds admit eight jobs each. Every live job receives three disconnect notifications before its single worker completes. After each round, the in-memory job registry is discarded and restored from SQLite; every accumulated result is read twice.

Measured result: **48 admitted jobs, 144 disconnect notifications, six journal reloads, 336 result reads, 48 unique completion executions, 48 completed durable records, zero extra model dispatches during result reads**. Each batch has a 15-second test timeout. Model completion is a deterministic fixture; the production coordinator and journal are real. This is a bounded host qualification, not a long-running private deployment, radio/network outage test or device soak. Real subprocess death is separately covered by the existing stability suite.

## Existing implementation and meaningful test coverage

The following behavior was already present at the pinned base and should not be represented as newly implemented by this patch.

| Requested area | Existing source | Existing validation and remaining qualification |
| --- | --- | --- |
| Selected software/model identity, profile binding and no silent reroute | `gateway/v14/execution_binding.py`, `inference_capabilities.py`, `inference_execution.py`; Android gateway and provider transport receipts | `test_gateway_selected_execution.py` sends actual outbound fixture requests, holds model A for roles/repair/restart, rejects queued configuration changes, checks revoked binding/cancelled lease and blocks writes after contradictory or missing identity receipts. Live private backend identity still needs its own observed receipt. |
| Heartbeat, readiness, bounded reconnect and supervision | Existing gateway liveness/readiness routes; `McpClientManager.kt`, `ModernMcpTransport.kt`, `LocalServiceHealth.kt`; `gateway/v14/mcp.py` | `test_gateway_v14_stability.py` keeps liveness separate from backend/journal readiness, bounds queue/idle/absolute deadlines, checks circuit recovery and one probe, and kills silent/oversized/flooding MCP subprocesses. This patch adds TLS pause and wrapped-preflight accounting regressions. |
| Typed MCP validation and discovery | `ModernMcpTransport.kt`, `McpClientManager.kt`, `gateway/v14/mcp.py` | Existing modern/legacy negotiation, unsupported-version, catalogue/schema, and execution-safety tests. The new `McpFailureDiagnosticsTest.kt` checks a malformed discovery response remains paused with `MCP_DISCOVERY_SCHEMA_INVALID`, then clears that code only after explicit successful rediscovery. The captured failures' exact protocol codes remain unknown. |
| Interrupted streams, disconnects and restart without repeated inference | Existing singleflight worker and durable job/journal code | `test_gateway_singleflight.py` coalesces reconnections of one attempt and distinguishes new inference attempts. `test_gateway_v14_jobs.py` retains one durable completion through disconnect and recovers client-tool handoff. `test_gateway_v14_stability.py` uses real process exit to retain committed results and mark active work interrupted. The new bounded soak covers repeated disconnect/reload cycles. |
| Failure during durable admission or final commit | Existing gateway job registration and result journaling | `test_gateway_v14_jobs.py` rejects registration before model dispatch on write failure, does not publish a successful answer after final-commit failure, and shares the failed commit with followers. Stability tests cover SQLite busy handling, rollback and terminal state immutability. |
| Token/context/output reserves, cache and batch controls | `gateway/v14/inference_budget.py`, `inference_capabilities.py`, `inference_scheduler.py`, and existing local-runtime tuning | Selected-execution tests preserve explicit output and completion reserves, count physical requests, bound projection traversal and release cancelled admission. Capabilities cache is bounded and uses backend identity. These are correctness guarantees; no new throughput, cache-hit or batching benchmark is claimed here. |
| Safe retry and output reconciliation | Existing gateway provider retry and replay cursor code | `test_gateway_reliability_faults.py` verifies one physical attempt on TLS, server cooldown lower bounds, no malformed cooldown guess, replay gap reconciliation, and bounded missing-range metadata. MCP interrupted-read tests check bounded safe-read recovery while mutations are not automatically replayed. |
| GPU/NPU fallback and memory admission | `LocalRuntimeRouter.kt`, `InferenceAdmission.kt`, and the current local-runtime implementations | Router and inference admission tests exist. This capture's OpenRouter TLS failure does not establish a GPU/NPU issue. This child did not run hardware inference, measure thermal behavior, or qualify QNN/GPU performance. |
| Secure configurable binding and authorization | `gateway/v14/config.py`, `identity.py`, `execution_binding.py`, existing admin and request boundaries | Existing scoped identity, endpoint binding and admission tests remain authoritative. No insecure TLS mode, wildcard listener, credential logging or authorization relaxation was added. |
| Correlated bounded diagnostics | `gateway/v14/diagnostic_events.py`, `engineering_diagnostics.py`; Android `GatewayDiagnostic*` classes | Existing diagnostic event/delivery/stress suites cover durable ingestion and retention. `test_gateway_diagnostic_stress.py` is the separate 50,000-event retention/restart test; this child does not include it in its 97-test focused count. Parent owns the raw-export inspector and diagnostic counts. |
| Verified visual attribution | Existing selected execution and transport proof pipeline | This branch preserves the strict `transportVerified` requirement. Renderer/UI changes and their device/screenshot verification belong to the UI owner. |

## Verification performed in this branch

- **63 Kotlin/JVM tests passed** using production MCP/network classes, Kotlin 2.4.20, Ktor 3.6.0, MCP SDK 0.15.0 and JUnit 4.13.2. Dependencies were checked against the repository's recorded SHA-256 verification metadata. Android persistence, logging and media boundaries are host stubs; this is not a full Android build. Suites: `McpClientManagerTest`, `McpInterruptedReadTest`, `ModernMcpTransportTest`, `ModernMcpRecoveryTest`, `McpHealthObservationOrderTest`, `McpCatalogueExecutionSafetyTest`, `LocalServiceHealthTest`, `McpFailureDiagnosticsTest`.
- **97 Python gateway tests passed in 34.144 seconds** across `test_gateway_reliability_faults`, `test_gateway_v14_stability`, `test_gateway_v14_jobs`, `test_gateway_singleflight`, `test_gateway_selected_execution` and `test_gateway_connection_soak`. This includes real HTTP routes, SQLite, and MCP subprocess fault cases.
- `bash scripts/validate-local.sh fast`: **361 host checks passed**, plus tool-statistics policy, Android regex, XML resource, icon and QNN provenance checks. Mocked retry scenarios print expected HTTP 429/503 messages; they are not actual dependency provisioning failures. QNN provenance does not prove device execution.
- KtLint 1.3.1 checked/formatted the four touched Kotlin files; `git diff --check` is clean.

The child environment has JDK 17 but no Android SDK/adb. The parent owns full Android CI, merged integration checks, architecture-index regeneration, gateway manifest regeneration and release validation. No claim is made about an actual private gateway connection, the remote certificate chain, hardware acceleration, or long-duration stability from these host tests.

## Integration follow-up for the other owners

1. `AgentToolResolver.kt` currently emits only the exception class for `TOOL_RESOLUTION_FAILED`. Add the observed `health.lastErrorCode` and `health.failureRetryable` to its bounded summary; do not add raw exception chains, tokens or endpoint query strings. A permanent TLS pause should request an explicit reconnect rather than promise that waiting for a timer will recover it.
2. `ToolConnectionsViewModel.kt` currently reads `observedHealth.lastError`. Its existing protocol messages already contain a diagnostic code, but the new safe fields can make code/retryability explicit for all paths. Keep catalogue readiness distinct from observed tool execution and transport proof.
3. Root must regenerate the gateway package manifest to include the new soak test, and regenerate the repository index/release scope with all child changes. Those shared ownership files are intentionally not edited in this branch.
