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
| R05 | A hosted service still needs real deployment and credentials. | Hosted readiness negotiates the exact report contract before dispatch. Original v1/v2 remain supported; review fields use v3 and recovery/extraction/precheck context uses v4. Retained extended-v2 reports require both v2 and v3 support without rewriting their approved bytes. Native delivery provides an explicitly approved alternative; no live endpoint or credential is invented. | Configure a real service origin and verify `/health/ready`, or use the approved native account route. |

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
| R28 | Gateway readiness for one model could be confused with another selected model. | Capability/readiness caches include endpoint, credentials, configuration and runtime revision; model readiness remains tied to exact selected identity. Model preparation uses the configured exact ID and revalidates captured provider authority after suspended ownership checks, including before loading. | Refresh the real catalogue and prepare the selected model only if advertised as loadable; rerun its named benchmark and permission-revocation regression. |
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

The earlier integrated source checkpoint recorded here is `2ba80781e2be158a76e797420c8ca6c1063b05f4`. It includes the test-fixture corrections below on top of the published `9781a4fb` integration. It preserves concurrent recovery changes through `b330796aa2b5c5a09f233920b87cd423549c60e3` and incorporates the reviewed receipt-set correction `c1495ebc`. The earlier application source at `e1a75433` passed its complete JVM run as checkout merge `f71b0d355a84c46e34d4c88632c86e64d523a643`. The later recovery, polling and integration changes, together with the corrected downstream CI gate, require their own published-head run; earlier results remain tied to their stated revisions.

Legacy context-v1/v2 approvals remain byte-stable. At that earlier checkpoint, the hosted service advertised versions `[1, 2, 3]`; original v2 reports still work with v2 services, while review-extension reports require v3. A retained extended-v2 approval requires both versions 2 and 3. Unsupported readiness stops before the dispatch marker and POST; a later upgrade sends the same approved bytes. Actual HTTP 422 rejection remains nonretryable. Pending-report reconciliation tests use original-v2 and extended-v2 issue-body digests independently reproduced from immutable publisher `7aaa9731`; they find the existing issue without another publish. Sender readiness, byte-preservation, native approval and backend-attempt receipt regressions are included in the successful `e1a75433` JVM run. The later diagnostic-schema enum addition repins gateway source to `52eacb7fa189ae878e06277dfe9c4ef2847fbc61`; all 218 current file hashes were independently checked against both the manifest and that immutable source. The earlier full gateway test result remains bound to `9155a1a2`.

## Historical verification ledger

The entries below retain their original source and execution checkpoints. Later validation does not change those identities; the stable-candidate follow-up at the end records subsequent results separately.

| Validation | Command or scope | Observed result |
| --- | --- | --- |
| Host gate | `bash scripts/validate-local.sh fast` | Passed after the reconciliation correction: 477 tests, CI policy, schema/catalog checks, secret scan, regex/resource preflight and whitespace checks. The focused collector/reconciler suite also passed 41 tests. |
| Kotlin formatting | Pinned ktlint 1.3.1 on changed/new Kotlin files | Passed for `e1a75433` in CI. The earlier local checkpoint checked all 185 then-changed Kotlin files. The subsequent reconciliation correction changes no Kotlin. |
| Authentication | `python -m unittest authentication.test_github_broker authentication.test_app authentication.test_ticket_broker` | Passed: 56 tests after the context-v3 compatibility repair; seven configuration-checker tests also passed. |
| Gateway | `python gateway/check_gateway.py`, followed by the renamed engineering guard suite | Prior backend-attempt-receipt run: 908 passed and 2 host-dependent skips across 910 Python cases in 69 modules; browser bridge 7/7 passed. All 218 gateway source files match immutable code commit `9155a1a2` and its canonical manifest. This includes the 50,000-event durable stress case, disconnect recovery, exact attempt caps and byte counts, unknown usage, and failed second HTTP requests. The 9-test incremental-delivery suite also passed separately. After the incoming schema change at `52eacb7f`, all 28 local diagnostic-protocol tests passed and all 218 source hashes matched. The `6567b4d1` Linux CI contract run also ran 910 gateway cases: 909 passed and one platform-specific case was skipped, 56 authentication tests and seven browser tests. The final integrated-head matrix remains separately recorded. |
| Generated source inventories | App audit generator/check; gateway source-package manifest builder/check | Passed. Dependency artifact pins were unchanged. |
| Android production compilation | Existing CI split generation/compilation workflow using JDK 21 and SDK 37 | Passed at `e1a75433`: production and test Kotlin/Java compilation, debug APK packaging, and review/benchmark compilation. Benchmark compilation does not establish measured device performance. |
| Android and portable-domain JVM tests | [PR validation run 43293697498](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43293697498), checkout `f71b0d35` | 9,600 passed, five deliberate heap skips, zero failures/errors across 1,439 reports / 9,605 cases. App: 9,596 passes and five skips. Portable domain: four passes. The following reconciliation step failed on an incomplete report-directory selection, so the dedicated heap step was skipped on this run. The earlier `ddbea3a9` heap gate passed all five deferred cases; that older proof is not a current-head heap pass. |
| Fresh qualification and measured JVM coverage | Same exact checkout/run/attempt and execution window | All 1,439 reports collected with zero report errors. App JaCoCo: 45.4356% line coverage and 43.8851% branch coverage across 2,376 reported source files. Named-harness observations: 43 passed, 16 not observed; source-area summaries: 16 passed, six partial, four not qualified. These are scoped harness summaries, not whole-subsystem or physical-device verification. |
| Reconciliation correction | Exact `e1a75433` artifact replay with the corrected CLI | Both explicit app and core-domain report directories reproduce the qualification digest `22fdff2c2fa57622b0b2ffdefd2d02b53f755ab057246db95507954e9fe96378`. The app-only negative control still fails. Freshness, source/run/attempt binding, duplicate rejection, combined bounds and exact digest equality remain required. Formal acceptance promotions remain zero; the historical ledger stays 11 PASS / 99 NOT_RUN. |
| Emulator and native acceptance | [API 34/36 run 43293697367](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43293697367), [native/R8 run 43293697636](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43293697636), at `e1a75433` | 66 instrumented passes on each API. Native smoke: three passes, two unsupported-accelerator skips, zero failures. ARM64/universal/x86_64 packaging and 16 KiB LOAD alignment passed. These are emulator observations, not physical microphone or GPU qualification. |
| Installed app/device/service acceptance | Exact APK and intended phone/deployments | Not run; prerequisites are listed below. |

The two gateway skips require Windows Credential Manager and a bindable LAN IPv4 interface. An initial gateway run found stale source-package hashes, which were corrected with the canonical source-manifest generator before the successful complete rerun. An initial combined Android generation/compile attempt encountered the repository's documented 3 GiB compiler heap pressure and was interrupted; the final run uses separate phases and the existing CI-bounded 4 GiB production compiler setting, without changing repository memory defaults.

The local JVM environment requires the existing pinned ByteBuddy agent at test-worker startup because dynamic attachment cannot find its process socket. Additional Robolectric SDKs were provisioned against Maven publisher SHA-512 sidecars. No dependency pins, application behavior or test assertions were bypassed for that environment repair. The Windows streaming fixture now measures backend repair readiness separately from its unchanged three-second ASGI-delivery deadline; a deliberately buffered negative control still fails. The connection soak uses separate admission, disconnect and completion phases, each retaining its 15-second limit and an explicit elapsed-time assertion. It still verifies 48 unique jobs, 144 disconnect checks, durable reloads and no model replay. Delayed journal writes reproduced the old aggregate-deadline failure; both asynchronous-disconnect and synchronous-admission stall controls still fail.

The temporary execution workspace became unavailable after an earlier source commit was preserved. Its interrupted rerun was not promoted to a pass. The restored checkout and subsequent exact-head CI supplied the observed results above. Concurrent commits were preserved; a cancelled run remains cancelled rather than becoming a test failure or a pass.

The `e1a75433` application tests and qualification collection succeeded, but the full unit/heap job did not: qualification included app and core-domain results while reconciliation received only app results. The strict receipt check correctly rejected that mismatch. Commit `c1495ebc`, preserved in integration `1587c7f8`, supplies both explicit directories and aligns enumeration with the collector. It preserves the integrity gate and leaves the heap tests enabled. The corrected published-head run must execute reconciliation, the dedicated heap step and the final required aggregate before an all-checks-passed claim.

### Build artifacts and installation limits

The [debug APK build at `e1a75433`](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43293697690) succeeded and uploaded [artifact 13977964681](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43293697690/artifacts/13977964681). Its build log records native GitHub reporting enabled and an empty hosted-service origin. That is source/build-environment evidence; the APK's embedded flags were not independently extracted. The combined artifact is 732,472,486 bytes and exceeds the available download connector's 512 MiB limit. The smaller size-report artifact was downloaded and hash-verified, but no individual APK digest or signing certificate was independently observed. A compatible update preserving saved reports therefore still requires a match to the installed app's signing identity.

The prior [installer run 43291874307](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43291874307) produced a downloaded, SHA-256-verified archive whose bundled manifest names source `9155a1a2` and whose 11 installation/repair/uninstall smoke scenarios passed. The `e1a75433` gateway matrix and installer runs also passed. The later `6567b4d1` installer run passed with source repinned to `52eacb7f`, but its artifact was not independently downloaded at this checkpoint. Packaged artifacts do not establish the identity or readiness of the user's deployed gateway.

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

Incoming commits `52eacb7f` and `6567b4d1` record a separate audit of diagnostic
session `a74d18ed-d2eb-43d2-bde1-cc82a48cff83`. That incoming audit and its evidence
are retained with their original attribution; this integration did not re-audit its raw archive. Its
[15-finding repair log](diagnostic-a74d18ed-2026-11-12.md) records the bugs,
implemented fixes, existing branch coverage, and remaining device acceptance.
It adds retained journal identity and semantic policy recovery, exact terminal
checkpoint reconciliation, provider cooldown admission, immutable first-start
timestamps, shared gateway polling deadlines, truthful integration budgets and
prerequisites, explicit unmeasured claim scope, and bounded transcript observation.
The associated [task checkpoint](diagnostic-a74d18ed-2026-11-12-task.json)
records validation for these additional changes; earlier counts above remain tied
to their original revisions.

### Concurrent integration gate repair

The incoming `6567b4d1` CI run exposed 26 new paths missing from the release-scope manifest and one extra trailing empty line in each of three JVM evidence logs. Integration `1587c7f8` adds exactly those 26 paths, without new directory/glob allowances or changes to scope policy. It removes only the final empty line from each affected log; every actual output line, test count and timing remains unchanged. Original and normalized hashes are retained in the task checkpoint. The full configured-base scope comparison and whitespace check passed; the canonical host gate passed 477 tests. Independent source review found no blocker in the incoming recovery authority, shared polling or diagnostic-schema changes. Full Android/Room execution and final required verification remain attached to the next exact-head CI run.

The subsequent `b330796a` follow-up anchors terminal recovery-code parsing to known application guard forms, rejecting incidental provider prose and suffixed pseudo-codes. Its newer 22-test helper receipt is preserved, with only its final empty line normalized. Independent review found no blocker; exact-head Android execution remains required.

### Exact-head test compilation follow-up

The `9781a4fb` run passed scope, diagnostic preflight, formatting, host/gateway/authentication contracts, and Android production compilation. The preview job then found four test compilation sites: three newly added `MessageV2` fixtures omitted required `content`, and the event-only `AgentPersistenceDao` fake lacked two newly added abstract methods. Commit `2ba80781` supplies empty content for those unsent/retained-owner answer fixtures and adds the fake's no-row/no-transition overrides. Assertions, run identities, ownership fences, checkpoints and production code are unchanged. All four files pass pinned ktlint; the canonical host gate passes 477 tests. Full Android test compilation, JVM execution, receipt reconciliation and constrained heaps remain required on the corrected head.

The [Windows installer artifact from `9781a4fb`](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43301643424/artifacts/13982047735) was independently downloaded and verified: archive SHA-256 `27b3c543cbd128a4c9c52f568087d5ec159316b9ee5f2c029a4b1647167bb0e8`, both inner package hashes, 1,357 portable-package file hashes, and 218 gateway source hashes. Its bundled manifest matches source `52eacb7f`; release metadata and the actual checkout identify head `9781a4fb` directly. The bundled CI smoke receipt records 11 passes. No installer was executed on the user's machine, and the subsequent test-fixture correction changes no gateway source.

### Stable validation and queued-preparation authority

Draft [PR #881](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/881)
retains a stable candidate independently of concurrent updates to PR #838. Its
initial source was `77c55ccbd0891e4b1500adb3d41a6dcc51951686`, with PR checkout
`0d7e17e605e63320aa1b1ec82584911b65a6dc3a`; their tracked source trees match.
The current hosted reporting contract supports versions `[1, 2, 3, 4]` as described
in the reporting setup instructions. The v3-only records above are historical.

At that initial stable source, the [preview JVM run](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43309682809)
compiled and executed 9,757 app cases in 1,452 XML reports: 9,751 passed, one
failed, and five constrained-heap cases were intentionally skipped. The failed
case expected four extraction attempts from a stream that never emitted a
completion event. The conservative implementation correctly stopped after one
attempt. The corrected fixture now distinguishes a completed malformed response
(which may use bounded smaller-batch repair) from an unfinished provider stream
(which must remain incomplete without replay). Partial-outcome, private-draft,
and private-receipt assertions remain required. This failed run is not a full
validation pass and supplies no fresh portable-domain, coverage or heap result.

A final source review also found that queued model preparation checked selection,
credentials and ownership but omitted the provider's permission revision. The
narrow follow-up captures `ConfiguredDispatchAuthority` for the accepted model
and chat, then validates it after the existing suspended checks, before returning
authorization at the preparation boundary. Three Room-backed regressions cover
revocation during suspended metadata, unchanged permission, and initial activation
rejection. They retain the saved prompt and assert no generation dispatch. The
new regressions require execution on the corrected candidate; source review and
formatting are not execution evidence.

The [initial stable contract run](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43309682746)
passed 482 host checks, 60 authentication tests and seven browser tests. The
[gateway matrix](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43309683079)
passed on Python 3.12 and 3.13 on both operating systems: each Ubuntu job ran
910 cases with 909 passes and one platform-specific skip; each Windows job
passed all 910. These are repeated platform runs of the same 69 modules.

The [Windows package from this initial stable source](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43309682753/artifacts/13987435012)
was independently downloaded and verified. Archive SHA-256 is
`0fa6131f3e3dd553bb7ebbd1eebba7622d06c93132428cb014e2c916ea83e3e7`;
both package hashes, all 1,357 packaged-file hashes, and 218 gateway source hashes
match. Its package metadata identifies `77c55ccb`, its gateway manifest identifies
`52eacb7f`, and its retained CI smoke receipt records 11 passes. No binary was
executed locally or installed on the user's device.

The [same-source CodeQL build](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43309682669)
exhausted its 4 GiB Java heap while source generation and compilation shared one
Gradle process. This is a build-resource failure before completed analysis, not a
successful security scan. The correction separates KSP/KAPT generation from
production compilation into two traced, no-daemon processes. Both retain the
existing 4 GiB heap and 1 GiB metaspace limits and disable incremental compilation
and build-output caching. Individual compiler tasks are forced in the second
phase without forcing source-generation dependencies again. Manual Java/Kotlin
analysis and its required gate remain enabled. The existing retry-wrapper
regression now checks preservation of the repeated per-task `--rerun` options;
it still retries only transient dependency errors, not compiler or test failures.
The corrected CodeQL build and analysis require their own successful CI run.
