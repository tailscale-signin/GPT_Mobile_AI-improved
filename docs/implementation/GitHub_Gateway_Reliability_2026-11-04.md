# GitHub mode and gateway reliability

## Branch provenance

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.

This work started on `fix/github-verdict-tool-gateway-reliability-2026-11-04`, created from the exact head of draft PR #801, `bf35994eff463016a9db2ca5cc44fbd1285753c1`. The parent PR was subsequently merged upstream as `79665ff5121138be149e10424e462ef7414e545b`. This branch preserves the requested starting point and adds the changes below.

Inputs are the build 138 diagnostic remediation checklist, the requested gateway/client debugging and performance improvements, selected-profile-only GitHub execution, first-use GitHub setup, removal of the app Settings workshop shortcut, and the subsequent Status dashboard redesign.

## Behavior and acceptance

| Area | Resulting behavior | Main regression evidence |
| --- | --- | --- |
| PR arguments | Validation explains the actual failing `anyOf`/`oneOf` branch and required fields. A safe read correction uses the registered schema and observed arguments. Rejections remain `PRECHECK_REJECTED`, `dispatched=false`, and `retryable=false`; unchanged invalid arguments reuse the rejection. Unknown write outcomes remain fenced. | `GitHubSchemaRepairTest`, `LocalToolIntegrityTest`, `GitHubOperationToolTest` |
| Reviewer contract | Verdict v1 requires exactly `schema_version`, `verdict`, `reason`, `reviewed_ids`, `issues`, and `verified_claims`. Types, bounds, duplicate fields, evidence coverage and contradictory PASS/FAIL values are validated deterministically. INDETERMINATE remains distinct from malformed syntax. | `GitHubReviewContractTest`, `GitHubReviewedSessionTest` |
| Compatibility | Before substantial Llama GitHub work, a small synthetic review checks the selected profile's actual contract. A separate inference probe proves grammar/schema enforcement where available, even if administrative metadata is restricted. Compatibility is cached by configuration and observed gateway epoch. Incompatibility blocks repository work with same-profile setup guidance. | `GitHubReviewContractTest`, `LlamaCapacityRepositoryTest` |
| Recovery and output limits | Drafts, original evidence and completed review checkpoints survive final extraction failures. Restart compares canonical, authorization-validated receipt identities and resumes the unfinished stage. Changed or revoked evidence invalidates approval. Durable pass budgets cannot reset when failure metadata is written. Complete strict verdict JSON at a completed output-limit boundary remains usable; truncated or interrupted output stays withheld. | `GitHubReviewRecoveryTest`, `GitHubReviewContractTest`, existing output-continuation and verification suites |
| Selected AI profile | GitHub mode pins the selected profile UID and model, disables helper/reviewer/profile fallbacks, and revalidates selection at dispatch. Missing or disabled profiles pause execution. Provider-level model aliases, OpenRouter fallback and Ollama address substitution cannot change the selection. Ordinary chat preferences remain separate. | `GitHubChatPolicyTest`, `ConfiguredDispatchAuthorityTest`, `GitHubProgrammerViewModelTest`, `SelectedProfileRouteTest`, `ProviderAdaptersTest` |
| First use and loading | A welcome screen starts GitHub OAuth/browser or device sign-in and then guides repository, branch and profile selection. Returning sessions render saved workspace state before remote preparation and avoid loading unused inventories. The GitHub workshop entry is removed from app Settings and search. | `GitHubConnectionSetupTest`, `GitHubSetupWelcomeTest`, `GitHubWorkspaceAdmissionTest`, `ToolConnectionsViewModelTest`, `SettingsSearchRegistryTest` |
| Status dashboard | Compact rounded tiles show repository/check summaries and retain drill-down actions. When the selected or explicitly configured gateway is active, the dashboard adds measured tokens, speed, open jobs, recent devices and activity, following the Windows dashboard structure. Unavailable and stale measurements are identified. Details and long lists are collapsed. Compact layouts and larger text adapt without hiding unknown measurements. | `GitHubStatusSummaryTest`, `GitHubStatusDashboardRenderTest`, `GatewayDashboardModelsTest`, `GatewayDashboardMonitorTest`, `GatewayDashboardClientTest` |
| Gateway health | Versioned readiness separates network reachability, exact selected-model availability, inference readiness and metadata access. A protected health/properties endpoint is not an inference failure; a model list is not proof of generation readiness. Safe read recovery and shared capability caches are bounded and invalidated after observed restart/network changes. | `LlamaHealthDiagnosticsTest`, `GatewayControlNegotiationTest`, `test_gateway_v14_readiness.py` |
| Gateway isolation and replay | Missing critical controls on a known gateway and transient capability discovery failures stop isolated requests before inference. Definite unsupported discovery on a direct model endpoint keeps isolation headers intact. An uncertain gateway tool outcome cannot be cleared by a sampling-parameter HTTP error and replayed. | Gateway negotiation, transport retry and outcome regression suites |
| Local diagnostics | Opt-in gateway debug mode retains at most 5,000 sanitized JSONL lines in memory and disk, supports desktop export, and writes through an atomic coalesced background writer. Android sends only bounded client metadata while Debug mode permits it, and records gateway acknowledgements. Temporary conversations suppress client metadata. | `test_gateway_v14_debug.py`, `GatewayClientDiagnosticsTest` |
| Failure records | Minimal typed provider failure receipts persist separately from optional verbose logs, with stage, provider, hashed profile/model identity, response format, approved error category, available HTTP status, finish reason and measured output/reasoning tokens. Unknown provider error text is not retained. Private-run exclusion and export integrity accounting remain enforced. | `RunDiagnosticJournalTest` |
| Voice | Recognition availability, microphone permission and lifecycle checks precede binding. Repeated service failures use a bounded cooldown rather than rebinding repeatedly; typed input remains available. | `RecognitionStartGateTest`, `AndroidRecognitionRecoveryTest` |

## Review budgets

| Stage | Preferred output allowance |
| --- | ---: |
| Synthetic compatibility check | 512 tokens |
| Evidence verdict / consistency | 1,536 tokens |
| Draft correction | 4,096 tokens |
| Final claim extraction | 4,096 tokens |

Each allowance also respects the selected profile's admitted output ceiling and at most one quarter of the current context. Input admission reserves output and 1,536 safety tokens. Review inputs contain the original task, bounded draft/evidence sections and receipt IDs; they do not replay the entire GitHub conversation. Cached successful passes and a completed review are reused only under their validated identity and configuration.

## Debug and dashboard contracts

Desktop **Settings → Advanced → Debug mode** enables local transport diagnostics. **Export debug log** exports the bounded retained history. Manual installations can use `GATEWAY_DEBUG_MODE=true` and an optional `GATEWAY_DEBUG_LOG` path. Disabling logging stops new records and preserves existing exportable history.

Debug records contain status, duration, byte counts, request/principal digests and validated Android app/SDK/network/profile/model metadata. They exclude prompt/response bodies, source code, credentials, raw model/profile names and device identifiers. The Android app log is never uploaded. Logging/storage failure must not fail or replay inference.

`GET /gateway/dashboard` and `/v1/gateway/dashboard` use the configured admitted identity. Devices, activity and job history are scoped to that identity; aggregate measured usage and worker load are explicitly scoped to the installation. Missing counters remain null. Dashboard polling is excluded from device/activity accounting. Desktop management endpoints remain restricted.

## Validation

The following checks completed before publication. Focused JVM groups overlap and are not added together as a full-app total.

| Check | Result |
| --- | --- |
| Canonical `bash scripts/validate-local.sh fast` | Passed: 311 host tests, Kotlin regex audit, repository policies, dependency and secret checks, resources and native provenance |
| Gateway Python suite | Passed: 412 tests; one existing opt-in test skipped |
| Authentication service | Passed: 15 tests |
| Browser authentication bridge | Passed: 7 tests |
| Reviewer, route authority, diagnostics and capacity focused group | Passed: 86 tests |
| Reviewer recovery and onboarding focused groups | Passed: 65 and 59 tests |
| Tool-schema and voice focused groups | Passed: 64 JVM and 5 Robolectric tests |
| Current dashboard client, model, monitor and summary group | Passed: 26 tests, including gateway restart invalidation and credential isolation |
| Actual production Status Compose UI | Compiled; 2 native Robolectric render/interaction tests passed |
| Native visual inspection | Light 392 dp and dark 320 dp at 1.4× text inspected; compact metrics stack, device/activity tiles wrap, unavailable speed remains explicitly unmeasured |
| Changed Kotlin style, Room schemas and whitespace | Passed |
| Gateway manifest | 137 source hashes verified |

The full Android compilation, app/core unit suites, coverage, constrained-heap gate and APK checks run separately from these focused checks. Their final results belong to the pull request's matching-head validation record and check runs; the table above does not imply they passed. Hardware acceptance is qualified below.

## Qualification boundary

Automated tests use simulated GitHub/provider responses, real production state machines, native Robolectric Compose rendering and the gateway host runtime. They cannot certify a particular phone's recognition service, real network latency, a live model's instruction quality, or a Windows installation on user hardware. Physical microphone interaction, real gateway restart/disconnection recovery and Windows installation/export acceptance remain separate live-device checks. A strict failure returns retained work and a recoverable reason; it must not fabricate task completion.
