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
| R05 | A hosted service still needs real deployment and credentials. | Hosted readiness negotiates the exact report contract before dispatch. Original v1/v2 remain supported; new review fields use v3. Retained extended-v2 reports require both v2 and v3 support without rewriting their approved bytes. Native delivery provides an explicitly approved alternative; no live endpoint or credential is invented. | Configure a real service origin and verify `/health/ready`, or use the approved native account route. |

An old report known to have failed before dispatch can bind a newly approved route without changing its ID or approved payload. An older uncertain attempt whose destination was never recorded remains stopped for manual verification; the update cannot safely guess where it might have been submitted.

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
| R31 | Failed accepted model attempts lost known usage or borrowed another invocation's output. | Each physical attempt contributes idempotently to its owning invocation. Bounded typed backend-attempt receipts preserve serialized output allowances, finish categories, observed response state, known byte counts and terminal status without response text. Diagnostic receipts remain available independently of token usage. Retained known failed-attempt usage contributes to a known subtotal; missing usage remains null/unknown. Output provenance carries the invocation identity. | Compare every physical request and repair attempt with backend receipts, retaining observed bytes, effective limits, finish reason, known usage and terminal error where available. Keep missing values unknown and never present the known subtotal as a complete total. |
| R32 | Capacity failure became generic INCOMPLETE/stage_not_recorded. | Typed capacity exceptions retain admission stage, component budgets and no-dispatch evidence through provider diagnostics. Private review keeps its own stage alongside backend generation details. | Reproduce local admission rejection and inspect the compact/exported receipt. |
| R33 | Completed runs retained failure recovery metadata. | Successful terminalization has completion-specific dispatch scope, no failure stop code, no failure evidence pointer and no resume-failure instruction. | Inspect completed and failed terminal records from the updated APK. |
| R34 | Runtime identity parsing included punctuation and hid the source of context caps. | Runtime identity emits structured JSON with distinct declared/server/profile/effective context values and their source/revision where known. No formatted-string parsing is used. | Confirm exact backend identity values; declared values remain declarations unless independently bound. |
| R35 | Compact failure summaries could confuse observed head with tested SHA or the wrong successful output. | Only explicit tested-SHA evidence means a tested revision. Observed/expected head is separate. Same-run, exact-task checkpoint projections preserve causal failure identity rather than borrowing unrelated records. | Inspect a new review-failure export with both successful primary output and failed review. |
| R36 | Export work could block the main thread. | Chat export snapshots its inputs and performs grouping, thinking separation, redaction and formatting on a computation dispatcher. Existing off-main markdown preparation and streaming diagnostic export remain in use. | Profile export and navigation on the same device/workload. |
| R37 | Debug frame stalls and resource behavior lacked causal or release evidence. | No smoothness, memory or GPU/NPU success is inferred from source changes. Existing instrumentation and device qualification remain required; operation timings are investigation targets rather than proof of causation. | Replay streaming/navigation/workspace/voice journeys on a release/profileable APK with frame and memory traces. |

## Audit findings and execution still required

These are the counts reported in the user-supplied analysis of `app-audit-1794498632783.json`, not a newly executed audit: 56 passed, 4 failed, 4 blocked, 10 unavailable and 67 untested, totalling 141. The displayed 86.2% was 25/29 measured checks. It did not mean that 86.2% of the complete 141-check plan or application was verified. The revised presentation keeps measured quality and execution coverage separate.

| Capture-local connection | Observed prerequisite | Dependent execution | Required operational repair |
| --- | --- | --- | --- |
| target-7 | check-36: connection refused; dispatch uncertain | check-37 | Check the exact saved host, port and listener, then perform bounded discovery and an actual tool assertion. |
| target-8 | check-39: generic discovery failure with missing phase/revision | check-40 | Retry independently with the repaired typed receipt; determine its actual cause. |
| target-9 | check-42: HTTP 404/protocol negotiation rejection | check-43 | Repair the configured endpoint or supported discovery adapter/lifecycle before retrying. |
| target-10 | check-45: generic discovery failure with missing phase/revision | check-46 | Diagnose independently of target-8 and retain the configuration binding and terminal cause. |
| target-11 | check-48: local credential invalid; no dispatch | check-49 | Reauthorize the exact saved connection, then discover and execute a bounded tool. |

Aliases are valid only within that capture. The in-app affected-connection action uses the saved local identity; it does not guess a server from an alias in another export.

Disabled Crawlers, Deep Research and Google service are configuration choices, not demonstrated implementation failures. The optional absent plugins are ArcGIS, Eventbrite, Foursquare, Google Places, openrouteservice, OpenStreetMap, Ticketmaster, TomTom, Universal Marketplace and Yelp. Enable or install only the integrations the user wants, then audit that new configuration. Missing plugins and their dependent untested checks must not become two independent bugs.

| Untested category in the historical audit | Count | Evidence required to close it |
| --- | ---: | --- |
| Plugin/tool execution | 39 | Bounded actual invocations against the exact saved targets, expected assertions, and matching result receipts. Eighteen had the prerequisites listed above; the other 21 still had no execution proof. |
| Broader source-area suites | 26 | Named executed cases for the matching source/build, with the environment and scope retained. JVM evidence covers its named assertions; external/device cases remain separate. |
| Selected-model generation | 1 | Requested/observed exact identity, load/generation/streaming/finalization assertions, first-output timing and finish reason. |
| Device performance and rendering | 1 | Exact APK/device/workload, timestamps, frame durations, memory and relevant runtime-resource measurements. |

The 21 plugin/tool checks without a recorded prerequisite blocker were five saved connections, the MCP connection advertising 46 tools, Airbnb, Amazon Free, Amazon Search, device location, GitHub, Health Connect, local memory, model delegation, News, Read files, Read URL, Voice chat, Web search, Refuge and Toronto. Tool discovery alone does not qualify their advertised tools.

The 26 broader source areas were Android lifecycle; chat UI; accessibility/localization; agent execution; agent tools; MCP plugins; marketplace plugins; model providers; models/profiles; local inference; gateway client; network; GitHub integration; research; memory/retrieval; persistence; diagnostics/export; benchmarking; authentication/security; voice/media; portable domain code; native bridge; gateway software; authentication service; external integrations; and other production code. The generated qualification artifact ties named harness observations to fresh JUnit and coverage receipts without relabelling the old audit or declaring a whole subsystem verified. Android and portable-domain tests now run fresh within the same recorded window, and the collector accepts both report directories. A stale report in either directory remains unverified.

Installed-APK digests identify the captured artifact but do not independently prove its declared source commit. Gateway capability identity remains declared unless bound to the actual deployed artifact; the engineering gateway's missing instance/source fields remain missing evidence. Reported device properties and emulator tests do not establish physical-phone performance. The supplied capture also recorded successful engineering readiness, inference capabilities, one 46-tool MCP catalogue, 22 narrow local fixtures and complete plan/result reconciliation; the repair does not relabel those successes as failures.

## Cross-reference to the added diagnostic findings

The 23 numbered findings in the later user-supplied diagnostic analysis map to the repair register as follows. Grouping keeps one underlying repair from being counted as several independent bugs.

| Supplied finding | Repair IDs | Disposition |
| --- | --- | --- |
| 1. Review context overflow | R06, R32 | Serialized budgeting, bounded evidence batches and typed admission receipts. |
| 2. Review/preflight repair exhaustion | R07, R31 | Changed-parameter repair, explicit reasoning authority, bounded attempts and retained usage. |
| 3. Candidate withheld after private review | R08, R09, R35 | Persisted candidate, factual partial delivery and separately resumable verification. |
| 4. Repeated incomplete workflow page | R10, R11, R12 | Authorized remote continuation, durable progress and operation-scoped duplicate handling. |
| 5. Microphone startup failures | R17, R18 | Typed recorder outcomes and bounded attempt-owned startup recovery. |
| 6. Speech interrupted after generation | R19, R20 | Terminal whitespace repair, buffered drain and explicit interruption origin. |
| 7. Integration prerequisites and unfinished fixtures | R16, R27 | Preparation and saved qualification preserve fixture authority, ownership and cleanup requirements. |
| 8. MCP port 8101 refusal | R22, R25 | Specific local repair guidance; the real listener still needs operational repair. |
| 9. MCP port 8102 discovery 404 | R22, R24 | Typed protocol evidence; actual route and supported lifecycle need deployment verification. |
| 10. Jina initialization 400 | R24 | Bounded redacted protocol detail; no unsupported URL replacement. |
| 11. Exact selected model unavailable | R28 | Model-specific readiness and preparation; actual catalogue/load/generation remains required. |
| 12. Rendering stalls | R36, R37 | Export work moves off the UI thread; causal tracing and release/device comparison remain required. |
| 13. Schema, preflight and metadata overhead | R07, R15, R29 | Compact status schemas, matching compatibility reuse and shared metadata reads. |
| 14. False/missed native voice incidents | R21 | Typed outcomes before generic text classification, with cleanup correlation. |
| 15. Missing recorder/playback details | R17, R20 | Recorder phase/state, cancellation origin and first-PCM/output/drain observations. |
| 16. Duplicate Network/MCP incidents | R23 | One pre-dispatch operation ID across transport and discovery. |
| 17. Lost capacity code/stage | R32 | Typed context-admission code, component budgets and dispatch evidence. |
| 18. Lost physical-attempt details and usage | R31 | Attempt-owned receipts, known subtotals and explicit unknown usage. |
| 19. Blank CI operation identity | R12 | Canonical default action and aliases before dispatch/caching/receipts. |
| 20. Stale call ID in cached replies | R12 | Fresh invocation receipt separated from source observation provenance. |
| 21. Failure metadata on completed runs | R33 | Completion-specific terminal metadata without failure recovery pointers. |
| 22. Punctuation in runtime identity | R34 | Structured identity values rather than formatted-string parsing. |
| 23. Unexplained effective context cap | R34 | Distinct model/server/profile/effective limits and known sources/revisions. |

The reporting investigation maps to R01–R05. The audit's four MCP failures and credential blocker map to R22–R26; disabled and absent optional integrations, measured-score interpretation, all 67 untested checks and source/deployment/device evidence limitations map to R27–R28 and the external acceptance below. A plan, fixture catalogue, source inventory, or successful capability response does not upgrade those historical checks to verified execution.

The current source checkpoint is `6ba181bd98a33446f208ee1b5370c17d407cdec2`, preserving combined backend/accounting source `53e31398` and concurrent updates through `7aaa9731`, including incomplete-task and review-recovery evidence. Legacy context-v1/v2 approvals remain byte-stable. The hosted service now advertises versions `[1, 2, 3]`; original v2 reports still work with v2 services, while review-extension reports require v3. A retained extended-v2 approval requires both versions 2 and 3. Unsupported readiness stops before the dispatch marker and POST; a later upgrade sends the same approved bytes. Actual HTTP 422 rejection remains nonretryable. Gateway source is independently bound to `9155a1a2`; all 218 manifest file hashes were rechecked after integration. Exact combined-head Android execution remains pending at this checkpoint. Pending-report reconciliation tests use original-v2 and extended-v2 issue-body digests independently reproduced from immutable publisher `7aaa9731`; they find the existing issue without another publish. The actual sender readiness/byte-preservation and native approval regressions await Android CI execution.

## Verification ledger

| Validation | Command or scope | Observed result |
| --- | --- | --- |
| Host gate | `bash scripts/validate-local.sh fast` | Passed: 473 tests, CI policy, schema/catalog checks, secret scan, regex/resource preflight and whitespace checks. |
| Kotlin formatting | Pinned ktlint 1.3.1 on every changed/new Kotlin file | Passed: all 185 changed Kotlin files with the pinned formatter after the final receipt and qualification changes. |
| Authentication | `python -m unittest authentication.test_github_broker authentication.test_app authentication.test_ticket_broker` | Passed: 56 tests after the context-v3 compatibility repair; seven configuration-checker tests also passed. |
| Gateway | `python gateway/check_gateway.py`, followed by the renamed engineering guard suite | Final backend-attempt-receipt run: 908 passed and 2 host-dependent skips across 910 Python cases in 69 modules; browser bridge 7/7 passed. All 218 gateway source files match immutable code commit `9155a1a2` and its canonical manifest. This includes the 50,000-event durable stress case, disconnect recovery, exact attempt caps and byte counts, unknown usage, and failed second HTTP requests. The 9-test incremental-delivery suite also passed separately. |
| Generated source inventories | App audit generator/check; gateway source-package manifest builder/check | Passed. Dependency artifact pins were unchanged. |
| Android production compilation | Existing CI split generation/compilation workflow using JDK 21 and SDK 37 | Passed at baseline `ddbea3a9`: source generation, Kotlin compilation and Java compilation. The final receipt and context-v3 changes await their own hosted run. |
| Android JVM behavioral tests | Centralized execution against the final working tree | Latest published baseline `ddbea3a9`, tested as merge `956d50d3`: 9,546 passed, five deliberate heap skips and zero failures/errors across 1,437 Android JUnit reports. The separate constrained-heap gate passed all five deliberately skipped cases (10 tests at 256 MiB and one at 64 MiB, with overlap; these are not 11 additional unique tests). The final backend-attempt-receipt and context-v3 compatibility changes await their own hosted run. |
| Emulator baseline | API 34 and API 36 at `ddbea3a9` | 66 instrumented passes on each emulator. Android 16 native smoke: three passes, two unsupported-accelerator skips. These are emulator observations, not physical microphone or performance qualification. |
| Installed app/device/service acceptance | Exact APK and intended phone/deployments | Not run; prerequisites are listed below. |

The two gateway skips require Windows Credential Manager and a bindable LAN IPv4 interface. An initial gateway run found stale source-package hashes, which were corrected with the canonical source-manifest generator before the successful complete rerun. An initial combined Android generation/compile attempt encountered the repository's documented 3 GiB compiler heap pressure and was interrupted; the final run uses separate phases and the existing CI-bounded 4 GiB production compiler setting, without changing repository memory defaults.

The local JVM environment requires the existing pinned ByteBuddy agent at test-worker startup because dynamic attachment cannot find its process socket. Additional Robolectric SDKs were provisioned against Maven publisher SHA-512 sidecars. No dependency pins, application behavior or test assertions were bypassed for that environment repair. The Windows streaming fixture now measures backend repair readiness separately from its unchanged three-second ASGI-delivery deadline; a deliberately buffered negative control still fails. The connection soak uses separate admission, disconnect and completion phases, each retaining its 15-second limit and an explicit elapsed-time assertion. It still verifies 48 unique jobs, 144 disconnect checks, durable reloads and no model replay. Delayed journal writes reproduced the old aggregate-deadline failure; both asynchronous-disconnect and synchronous-admission stall controls still fail.

The temporary execution workspace became unavailable after the final source commit was preserved. Its then-running canonical rerun did not return a final result. The checkout was restored from the immutable commit, and the complete gateway and authentication gates were rerun successfully with source-content binding. The interrupted run was not promoted to a pass. Exact combined-head CI provides the Android/JVM, coverage and APK results. The prior successful compilation and test counts above remain tied to their stated revisions.

A generated source inventory, a named test file or an unexecuted qualification plan is never execution coverage. JVM fixtures do not substitute for installed-app, physical-device or real-service acceptance.

## External completion checklist

1. Verify the installed and replacement APK signing identities before performing a compatible update that preserves the saved-report outbox. Retain the new APK digest, actual build/checkout commit and configuration. Bind that digest to the build evidence rather than trusting a declaration. A repository variable cannot change an address compiled into an older APK.
2. Approve a real reporting route/account, send a disposable report and verify the matching report ID, body, author and issue number. Exercise uncertain-delivery recovery without creating a second issue.
3. Open the affected MCP connections locally; repair their real listeners/routes and reauthorize the invalid credential. Run discovery followed by a bounded actual tool assertion.
4. Run selected-model generation with requested/observed identity, first-output timing, final-output assertion and finish reason. A passing gateway capability response alone is insufficient.
5. Resume integration qualification with explicit owned fixtures and sufficient cleanup reserve; retain creation/use/deletion/final-absence evidence. Keep sampling and missing prerequisites visible.
6. Run the device voice, lifecycle, permission, rendering and memory journeys. Compare the same declared workload on the same device, with release/profileable traces.
7. Run the applicable external source-area suites and attach their exact source/build qualification. The audit's 67 untested entries remain untested until their own required evidence is collected. Keep measured JVM line/branch coverage distinct from whole-app, live-service and physical-device coverage.
8. Bind each deployed gateway's artifact digest, instance identity and source revision to the observed service. Missing or declared-only identity must remain explicit; capability readiness does not supply independent deployment attestation.

Optional marketplace integrations and deliberately disabled plugins are user choices. This repair does not silently install, enable or grant them access. The supplied captures do not establish an app crash, ANR, OOM, rate-limit cancellation, diagnostic truncation, or the availability of every advertised MCP tool; none is claimed as an additional confirmed defect or as fixed by this branch.

## Follow-up: diagnostic a74d18ed

The subsequently supplied diagnostic session `a74d18ed-d2eb-43d2-bde1-cc82a48cff83`
was independently reconstructed and checked. Its
[15-finding repair log](diagnostic-a74d18ed-2026-11-12.md) records the bugs,
implemented fixes, existing branch coverage, and remaining device acceptance.
It adds retained journal identity and semantic policy recovery, exact terminal
checkpoint reconciliation, provider cooldown admission, immutable first-start
timestamps, shared gateway polling deadlines, truthful integration budgets and
prerequisites, explicit unmeasured claim scope, and bounded transcript observation.
The associated [task checkpoint](diagnostic-a74d18ed-2026-11-12-task.json)
records validation for these additional changes; earlier counts above remain tied
to their original revisions.
