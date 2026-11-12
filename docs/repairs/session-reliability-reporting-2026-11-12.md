# Session reliability, reporting, voice and audit repairs

## Scope and evidence

Branch: `fix/session-reliability-reporting-2026-11-12`

Base: `97f10ff7a1f3789fb82d958b2dc6b22c8074dfdf`

Requested scope: all findings supplied in the session, including the reporting-service investigation, app audit, and later GitHub/voice diagnostic analysis.

The original session diagnostics archive was available for direct inspection. The later `app-audit-1794498632783.json` and `app-diagnostics-6729730838505374983.jsonl` were supplied as detailed analyses, not as their original files. Findings from those analyses are repair inputs; this branch does not claim to have independently revalidated their rows, hashes, timings or capture-specific aliases.

Implementation and regression evidence must remain separate from a successful deployment or physical-device acceptance. In particular, a source fix cannot restore an inaccessible private listener, reauthorize a user's connection, establish which gateway artifact is deployed, or measure phone rendering.

## Repair register

“Implemented” below describes the source change. Actual validation results and external acceptance are recorded separately.

### Reporting and distributed APKs

| ID | Finding | Implemented solution | Remaining acceptance |
| --- | --- | --- | --- |
| R01 | Distributed debug/review builds could contain no reporting destination. | Builds advertise an explicit reporting capability. Hosted reporting remains supported; an enabled native GitHub route uses the user's verified account. CI packaging rejects builds with neither capability, with an explicit local-only development opt-out. | Build and install an APK from this branch. Confirm the selected route is usable with the user's credentials. |
| R02 | Send became available before service/account availability was known. | The reporting screen resolves availability before submission and offers connection setup or saving for later. It distinguishes the approved hosted-service route from native GitHub delivery. | Exercise the actual account and device navigation. |
| R03 | A locally rejected report received the warning that GitHub might already have received it. | Delivery warnings distinguish an attempt that never dispatched from an uncertain remote outcome. Retry/confirmation actions reflect the retained route and dispatch state. | Verify the UI for missing configuration, unauthorized account and lost-response cases. |
| R04 | Saved-report recovery could be unhelpful or risk duplicate submission. | Approval binds report ID, body digest, account/repository and destination. A durable marker precedes native issue creation. Once a POST might have occurred, recovery performs read-only exact reconciliation; an absent or incomplete search never grants permission to create again. | Submit one approved disposable report, interrupt the response, restart and confirm the same issue/report ID is recovered. |
| R05 | A hosted service still needs real deployment and credentials. | Hosted route validation and readiness requirements remain explicit. No endpoint, GitHub App permission, credential or live success is invented. Native delivery provides an alternative when enabled and explicitly approved. | Configure a real service origin and verify `/health/ready`, or use the approved native account route. |

Primary implementation: `IssueReportRouting`, `IssueReportRoute`, `IssueReportGitHubSubmission`, `IssueReportOutbox`, `IssueReportWorker`, `IssueReportHost`, `IssueReportViewModel`, `app/build.gradle.kts`, and the APK workflows. See [reporting setup](../../authentication/README.md).

### GitHub review, execution and recovery

| ID | Finding | Implemented solution | Remaining acceptance |
| --- | --- | --- | --- |
| R06 | Review payload exceeded the selected context cap. | Review allowance includes the complete serialized instruction and proportional admission safety. Identical result content is represented once while preserving every receipt's origin, outcome and freshness. Evidence batches split within the verified input allowance. | Replay the large captured evidence on the selected backend and inspect its actual tokenization/admission. |
| R07 | Reviewer/preflight completion repairs exhausted their allowance. | Compact structured verdicts, bounded output repair, smaller batches and a persisted pass limit change a relevant input before retrying. Explicit reasoning settings and selected profile remain authoritative. Compatibility preflight is reused only across matching model/template/policy/capability revisions. | Exercise the installed model/template with reasoning ON and the real context cap. |
| R08 | Successful generation became inaccessible after private review failed. | The candidate is retained before review. Typed failed-stage receipts and checkpoints preserve invocation identity and recovery state. Deterministically verified observations can be delivered as partial work while review remains incomplete, without replaying successful tools. | Inject review failure through the complete installed app path and resume its saved checkpoint. |
| R09 | Automatic retry eligibility and explicit user resumption were conflated. | Failure details distinguish automatic retry, saved user-resumable work and the need for changed evidence/configuration. The bounded format retry survives restart. | Verify the user-facing Resume action under each failure kind. |
| R10 | Five workflow calls repeatedly observed page 1 of a 55-workflow inventory. | The orchestrator follows a native-issued, authorized continuation for the same operation/scope and next page. Durable consumed-page identities and a 32-read bound prevent loops even after replay eviction. | Live two-page enumeration against the intended repository. |
| R11 | Repeated reads stopped the whole task despite other useful actions and remaining budget. | Duplicate protection remains scoped to the repeated operation. Other authorized GitHub operations can continue within the accepted call, context and time budgets. | Replay a task combining pagination, status and additional reads. |
| R12 | Omitted CI actions produced blank identities and ambiguous cached results. | Default workflow actions and supported aliases normalize before dispatch, hashing, receipts and progress. A reused observation has a current invocation receipt and separate source provenance. Mixed current reads retain their actual counts. | Inspect live receipts for both pure reuse and mixed read/cache operations. |
| R13 | Repository counts, workflow definitions, cached reads or skipped checks could imply unsupported health. | Status planning acquires concrete head, activity, checks and workflow observations. Deterministic partial reports distinguish definition inventory, counts, observed execution, missing policy and unfinished work. | Confirm exact-head check and policy evidence for the repository being assessed. |
| R14 | Old task budgets polluted the current request. | The current accepted contract and remaining call allowance are provided explicitly; historical budgets become authoritative only for an explicit resume. Review includes the current contract inside its measured payload. | Exercise a new request after an older larger-budget task. |
| R15 | Broad tool schemas dominated a narrow status prompt. | Scoped read-only status preserves the original request through workspace and telemetry setup, then starts with context/PR tools plus discovery and required recovery controls. All authorized definitions remain discoverable/executable. Engineering, source, integration and mutation requests retain their appropriate catalogue. | Measure prompt size and preparation time on the same backend/workload. |
| R16 | Integration sampling stopped with fixture/setup work unverified. | Preparation records missing scope and prerequisites, explicit fixture authority, ownership, cleanup reserves and supported lifecycle steps. Completed/current-head workflow watches are bounded. Unsupported or unapproved fixture operations remain blocked. | Run applicable fixture-dependent criteria with creation, use, cleanup and final-absence receipts. |

Primary implementation: `GitHubStageBudget`, `GitHubReviewPayloads`, `GitHubReviewedSession`, `GitHubReviewCheckpoint`, `GitHubReplayRecovery`, `GitHubInvocationReceipt`, `GitHubActionIdentity`, `GitHubOperationPlanner`, `GitHubPartialReport`, `GitHubIntegrationPreparation`, `ProgressiveToolCatalog`, `ToolCatalogAdmission` and `AgentRunner`.

### Voice

| ID | Finding | Implemented solution | Remaining acceptance |
| --- | --- | --- | --- |
| R17 | Microphone startup failed before audio without a useful reason. | Recorder initialization, recording state and negative read results become typed failures with safe phase/state details. One bounded recorder recreation is allowed only for transient startup failure before any PCM has been captured. Partial PCM also prohibits recreation. | Cold start; permission grant/deny/regrant; privacy toggle; background/resume; repeated toggles; microphone contention. |
| R18 | Recorder replacement could inherit stale ownership or timers. | Capture creation and cleanup belong to the bounded attempt lifecycle. Cancellation and deadline callbacks target their exact recorder/attempt; a stale attempt cannot stop the replacement. | Repeat on the intended Android device and audio route. |
| R19 | Speech stopped just after model completion. | A terminal whitespace trim no longer looks like a content revision. Generation completion seals incoming speech and permits queued playback to drain; explicit stop, barge-in, pause, close and genuine response revision retain interruption behavior. | Short/long real responses must play the final buffered sentence exactly once after model completion. |
| R20 | Playback lacked cancellation caller and first-audio evidence. | Playback binds run/reply identity and records cancellation origin, input completion, first PCM, first output-frame observation, terminal drain state and underruns. | Hardware playback observations are required to establish audibility and latency. |
| R21 | Native voice classification hid failures and promoted configuration headers. | Typed microphone terminal outcomes take precedence. A failed native operation is distinct from its cleanup. Known Sherpa configuration dumps are informational while real initialization failures remain failures. | Inspect a fresh capture containing actual success, failure and intentional cancellation. |

The whitespace issue is a reproduced source behavior, not proof that it caused both interruptions in the supplied later capture. Physical microphone ownership and cancellation callers still require a new correlated trace.

Primary implementation: `VoiceCaptureFailure`, `MoonshineVoiceRecognizer`, `VoiceSpeechBuffer`, `VoiceSession`, `VoicePluginAudio`, `KokoroVoiceOutput` and `DiagnosticClassification`.

### MCP, gateway and audit

| ID | Finding | Implemented solution | Remaining acceptance |
| --- | --- | --- | --- |
| R22 | Generic early MCP discovery errors lacked phase, retryability or configuration revision. | Every terminal path retains available typed failure detail and the exact configuration binding. HTTP status stays absent when there was no response. Genuine uncertain dispatch stays unknown. | Retry each saved affected target independently; capture aliases do not identify servers across audits. |
| R23 | One transport refusal became two unrelated incidents. | An opaque operation ID is assigned before dispatch and propagated through transport and MCP records. No URL/time proximity is used to guess correlation. | Confirm a fresh failed attempt groups both evidence records under its operation. |
| R24 | MCP initialization HTTP 400/404 lacked actionable structured detail. | Safe bounded protocol code/classification survives SDK wrapping; local validation, authentication response, transport and protocol failures remain distinct. Configured URLs are unchanged without evidence of a wrong endpoint. | Inspect actual port 8102 routing/protocol support and Jina initialization/session/header/authentication behavior. |
| R25 | Private MCP port 8101 refused connections; one saved credential was invalid. | The app exposes specific repair guidance and affected-connection navigation while preserving backoff/configuration pauses. | Restore the listener/interface/port and reauthorize the exact saved connection; then discover and execute a bounded tool. |
| R26 | Started probes and network admission were difficult to reconcile. | Per-result admission evidence and separate started/admitted/local-rejection/unknown counts clarify accounting. | Verify a new audit with a locally rejected credential and actual admitted probes. |
| R27 | A high measured score obscured a large untested plan. | Audit presentation separates measured quality, execution coverage, configuration checks, local fixtures and live metadata. Disabled plugins and optional absent plugins are presented as configuration, with dependent untested checks grouped. | Collect actual receipts for the untested capabilities; configuration cannot promote them to pass. |
| R28 | Gateway readiness for one model could be confused with another selected model. | Capability/readiness caches include endpoint, credentials, configuration and runtime revision; model readiness remains tied to exact selected identity. Model preparation uses the configured exact ID. | Refresh the real catalogue and prepare the selected model only if advertised as loadable; rerun its named benchmark. |
| R29 | Burst metadata requests and repeated preflight added overhead. | Matching metadata reads share short-lived results and in-flight work. Explicit audit-owned live reads preserve separate receipts. The read-only engineering preflight has bounded connection recovery; mutating jobs and approvals are not automatically replayed. Duplicate disconnect notifications persist only the first real transition. A failed journal commit restores the prior in-memory state under the existing registry lock so the next notification can retry. | Measure actual request counts and latency under the same navigation sequence. |
| R30 | Gateway guard rejection was described as a changed conversation regardless of cause. | Typed allowlisted input/prerequisite reasons and safe fields replace arbitrary exception text. Unknown guard stage remains explicitly unknown/guard-scoped; previous unknown remote acceptance is preserved. | Exercise the deployed service version and client together. |

Primary implementation: `McpClientManager`, `McpFailurePresentation`, `McpInitializationRejection`, `ModernMcpTransport`, `AppAuditInterpretation`, audit models/export/UI, `GatewayAPIImpl`, `GatewayDashboardClient`, `GatewayInputRejectionDetails`, and `gateway/v14/engineering_rejection.py`.

### Accounting, diagnostics and performance

| ID | Finding | Implemented solution | Remaining acceptance |
| --- | --- | --- | --- |
| R31 | Failed accepted model attempts lost known usage or borrowed another invocation's output. | Each physical attempt contributes idempotently to its owning invocation. Retained known failed-attempt usage contributes to a known subtotal; missing usage remains null/unknown. Output provenance carries the invocation identity. | Compare a fresh export with physical backend receipts; never present the known subtotal as a complete total. |
| R32 | Capacity failure became generic INCOMPLETE/stage_not_recorded. | Typed capacity exceptions retain admission stage, component budgets and no-dispatch evidence through provider diagnostics. Private review keeps its own stage alongside backend generation details. | Reproduce local admission rejection and inspect the compact/exported receipt. |
| R33 | Completed runs retained failure recovery metadata. | Successful terminalization has completion-specific dispatch scope, no failure stop code, no failure evidence pointer and no resume-failure instruction. | Inspect completed and failed terminal records from the updated APK. |
| R34 | Runtime identity parsing included punctuation and hid the source of context caps. | Runtime identity emits structured JSON with distinct declared/server/profile/effective context values and their source/revision where known. No formatted-string parsing is used. | Confirm exact backend identity values; declared values remain declarations unless independently bound. |
| R35 | Compact failure summaries could confuse observed head with tested SHA or the wrong successful output. | Only explicit tested-SHA evidence means a tested revision. Observed/expected head is separate. Same-run, exact-task checkpoint projections preserve causal failure identity rather than borrowing unrelated records. | Inspect a new review-failure export with both successful primary output and failed review. |
| R36 | Export work could block the main thread. | Chat export snapshots its inputs and performs grouping, thinking separation, redaction and formatting on a computation dispatcher. Existing off-main markdown preparation and streaming diagnostic export remain in use. | Profile export and navigation on the same device/workload. |
| R37 | Debug frame stalls and resource behavior lacked causal or release evidence. | No smoothness, memory or GPU/NPU success is inferred from source changes. Existing instrumentation and device qualification remain required; operation timings are investigation targets rather than proof of causation. | Replay streaming/navigation/workspace/voice journeys on a release/profileable APK with frame and memory traces. |

## Verification ledger

| Validation | Command or scope | Observed result |
| --- | --- | --- |
| Host gate | `bash scripts/validate-local.sh fast` | Passed: 472 tests, CI policy, schema/catalog checks, secret scan, regex/resource preflight and whitespace checks. |
| Kotlin formatting | Pinned ktlint 1.3.1 on every changed/new Kotlin file | Passed: all 150 changed Kotlin files before workspace restoration; exact combined-head CI is pending. |
| Authentication | `python -m unittest authentication.test_github_broker authentication.test_app authentication.test_ticket_broker` | Passed: 47 tests. |
| Gateway | `python gateway/check_gateway.py`, followed by the renamed engineering guard suite | Earlier complete run: 895 passed, 2 host-dependent skips across 897 Python cases; browser bridge 7/7 passed. Final disconnect changes: 30 affected soak/journal tests passed. The new 900-case canonical run had one corrected test-double error; a clean rerun was interrupted after 47 modules without failures. Fresh combined validation is pending. |
| Generated source inventories | App audit generator/check; gateway source-package manifest builder/check | Passed. Dependency artifact pins were unchanged. |
| Android production compilation | Existing CI split generation/compilation workflow using JDK 21 and SDK 37 | Passed: source generation, Kotlin compilation and Java compilation. |
| Android JVM behavioral tests | Centralized execution against the final working tree | At `82dfad5c`, the complete rerun produced 9,519 passes, two identified follow-ups and five dedicated-heap skips, with no environment failures. The usage-event identity expectation and resolver original-intent path are now corrected. Final combined-head validation is pending. |
| Installed app/device/service acceptance | Exact APK and intended phone/deployments | Not run; prerequisites are listed below. |

The two gateway skips require Windows Credential Manager and a bindable LAN IPv4 interface. An initial gateway run found stale source-package hashes, which were corrected with the canonical source-manifest generator before the successful complete rerun. An initial combined Android generation/compile attempt encountered the repository's documented 3 GiB compiler heap pressure and was interrupted; the final run uses separate phases and the existing CI-bounded 4 GiB production compiler setting, without changing repository memory defaults.

The local JVM environment requires the existing pinned ByteBuddy agent at test-worker startup because dynamic attachment cannot find its process socket. Additional Robolectric SDKs were provisioned against Maven publisher SHA-512 sidecars. No dependency pins, application behavior or test assertions were bypassed for that environment repair. The Windows streaming fixture now measures backend repair readiness separately from its unchanged three-second ASGI-delivery deadline; a deliberately buffered negative control still fails. The connection soak uses separate admission, disconnect and completion phases, each retaining its 15-second limit and an explicit elapsed-time assertion. It still verifies 48 unique jobs, 144 disconnect checks, durable reloads and no model replay. Delayed journal writes reproduced the old aggregate-deadline failure; both asynchronous-disconnect and synchronous-admission stall controls still fail.

The temporary execution workspace became unavailable after the final source commit was preserved. Its clean canonical rerun did not return a final result. The checkout was restored from the immutable commit; no unobserved completion is promoted to a pass. Exact combined-head CI will provide the fresh Android/JVM, coverage and APK results. The prior successful compilation and test counts above remain tied to their stated revisions.

A generated source inventory, a named test file or an unexecuted qualification plan is never execution coverage. JVM fixtures do not substitute for installed-app, physical-device or real-service acceptance.

## External completion checklist

1. Install the branch APK as an update and retain its digest, build commit and exact configuration. A repository variable cannot change an address compiled into an older APK.
2. Approve a real reporting route/account, send a disposable report and verify the matching report ID, body, author and issue number. Exercise uncertain-delivery recovery without creating a second issue.
3. Open the affected MCP connections locally; repair their real listeners/routes and reauthorize the invalid credential. Run discovery followed by a bounded actual tool assertion.
4. Run selected-model generation with requested/observed identity, first-output timing, final-output assertion and finish reason. A passing gateway capability response alone is insufficient.
5. Resume integration qualification with explicit owned fixtures and sufficient cleanup reserve; retain creation/use/deletion/final-absence evidence. Keep sampling and missing prerequisites visible.
6. Run the device voice, lifecycle, permission, rendering and memory journeys. Compare the same declared workload on the same device, with release/profileable traces.
7. Run the applicable external source-area suites and attach their exact source/build qualification. The audit's 67 untested entries remain untested until their own required evidence is collected.

Optional marketplace integrations and deliberately disabled plugins are user choices. This repair does not silently install, enable or grant them access. The supplied captures do not establish an app crash, ANR, OOM, rate-limit cancellation, diagnostic truncation, or the availability of every advertised MCP tool; none is claimed as an additional confirmed defect or as fixed by this branch.
