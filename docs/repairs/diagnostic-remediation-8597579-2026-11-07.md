# Diagnostic remediation and improvement coverage

Repository: `tailscale-signin/GPT_Mobile_AI-improved`
Base: `main` at `91dfce15bc481c4bbdb088bcadd28489436dbb1b`
Branch: `fix/diagnostic-remediation-8597579-2026-11-07`

This branch addresses the supplied November 7 diagnostic capture and the requested reliability, gateway and usability backlog. The application that produced the capture identifies the exact base above. Existing coordinators, journals, permission boundaries, gateway transport and UI components are extended; no replacement scheduler, transport manager or memory store is introduced.

The supplied plan explicitly names **26 backlog items** and gives category totals for **19 unnamed items**. The coverage tables preserve the 26 named requests and identify the remaining 19 as implementation interpretations of the three supplied categories. They are not recovered quotations from an unavailable expanded checklist. Implementation coverage and deployment/device qualification remain separate.

## Diagnostic evidence and accounting

The source JSONL has SHA-256 `b539a1e3f3f9642ac5b1ecf779d08e9baa1d65f28dadec4001a415bbc638127d`, 10,253,235 bytes and 6,266 rows. Every row parses. The private export is not committed. A content-free, reproducible inventory is retained in [diagnostic-remediation-8597579-observations.json](diagnostic-remediation-8597579-observations.json).

| Record type | Count | Interpretation |
| --- | ---: | --- |
| Primary events | 3,500 | Includes four export/device/capture metadata records. |
| Continuations | 2,758 | Associated with retained parent events; not additional failures. |
| Duplicate-reference batches | 8 | Represent 247 exact recaptures and are not issue records. |
| Reconciled source records | 6,501 | `3,500 + 2,758 + 247 - 4`; matches the capture summary exactly. |
| `countAsIssue` observations | 79 | 79 recorded incident identities, not 79 independently established software defects. |
| Source/export truncations and reported dropped records | 0 | Applies to this retained capture, not to all historical execution evidence. |

The offline inspector now accepts schema-7 app-log JSONL in addition to existing conversation and staged exports. It bounds total bytes, line allocation, records and reference inventory; verifies continuation sequences and duplicate-reference source positions; reports contradictory or incomplete evidence; and emits only a fixed failure vocabulary, counts, source-line references and input digest. It does not print messages, endpoints, prompts, source metadata or model output. Signature groups do not prove causality, successful inference, task completion or physical-device performance.

Reproduce the inventory with:

```bash
python scripts/replay_conversation_debug.py /path/to/app-diagnostics.jsonl
```

### Disposition of the 79 issue observations

Line numbers below refer to the supplied export, not repository source lines. The inspector's JSON contains every corresponding line reference.

| Observations | Count | Finding and disposition |
| --- | ---: | --- |
| Saved-page `INVALID_ARGUMENTS` | 3 | A correct non-dispatched selector rejection supplied a native correction that deterministic checklist recovery did not consume. The branch consumes only the exact, trusted cursor-only read under the same visible tool and current budget. One correction is allowed per retained view across restoration. |
| `POLICY_STALE` tool failures | 6 | Two groups of three correspond to the profile's tools being disabled and the engineering configuration changing. Their reasons said the stale request was unsent while generic receipts said dispatched. Admission and terminal handling retain the actual execution boundary and stop further work under the invalidated authority. A later observer/checkpoint rejection preserves completed receipts and prior unknown mutation outcomes. The production measurement wrapper and thrown provider-guard path retain that same boundary. |
| `STALE_INVENTORY` precheck | 1 | No tool dispatch occurred. Inventory freshness is an authority boundary; retained discovery must not silently reauthorize a changed profile or tool catalog. Recovery requires a current, explicit request context. |
| `PARTIAL/TASK_INCOMPLETE` | 2 | Both report verification 0/5. They remain incomplete; read/API success is not converted into acceptance evidence. The required criteria and exact run/fence/delivery checks are preserved. |
| `FAILED/INCOMPLETE` | 2 | The exact run IDs match the stale-policy groups. Both retained 196 visible characters and unavailable acceptance evidence. Typed configuration invalidation is preserved instead of losing the cause in a generic incomplete outcome. |
| Model and parent TLS failures | 3 | One transport failure is recorded at segment, model-finished and parent-run levels. The request selected OpenRouter/DeepSeek, and the receipt does not establish accepted runtime identity or successful inference. TLS remains validated. Health reporting preserves the TLS cause; this branch cannot establish that the remote certificate path is repaired. |
| MCP resolution/configuration failures | 2 | The retained summary names `McpProtocolConfigurationException` but omits its specific code. Typed codes and recovery requirements are retained in the supervisor and surfaced by consumers. The exact historical protocol defect cannot be reconstructed from the missing detail. |
| DNS availability failures | 2 | Jina requests have successful responses elsewhere before and after the failures. Keep the configured endpoint, supervise bounded recovery, and avoid treating wrapped cooldown refusals as new physical failures that extend the outage. |
| `CHAT_DELETED` cancellation | 2 | Intentional cancellation, with unknown remote outcome. Neither successful mutation nor proven non-dispatch follows from this record. Retained journal fences and reconciliation remain authoritative; no replay is authorized. |
| Frame-timing failure windows | 33 | Measured rendering symptoms. The capture does not prove a common graphics/blur cause; some jank occurs with no graphics effect. Preserve frame-pressure controls and measure the resulting build on the affected device. |
| Skipped-frame warnings | 2 | Reports of 35 and 33 skipped frames. They corroborate a performance symptom, not a separate root cause for each warning. |
| Inactive input-connection warnings | 11 | Occur around input teardown/replacement. No accompanying lost-edit evidence, application exception or attributable application focus stack was found. Existing lifecycle coverage remains; warnings are not suppressed. |
| StrictMode main-thread disk reads | 10 | All ten stacks reach the export chooser's MIME lookup through `ClipData.newUri` and `FileProvider.getType` on `AndroidUiDispatcher`. Explicit known MIME metadata removes that lookup while retaining content-URI/read grants. No relaxed StrictMode policy is added. |
| **Total** | **79** | Every flagged observation is accounted for; repeated layers and intentional actions are not counted as separate repaired defects. |

The additional MediaPipe feedback warning is a symptom record, outside the 79 flagged observations. It follows successful sentence-encoder setup and does not establish failed memory indexing or user-visible inference failure.

### Rendering baseline from all retained windows

Use all 79 windows, including those not individually flagged, when calculating a denominator.

| Screen | Windows | Frames | Deadline-relative jank | Frames over 100 ms |
| --- | ---: | ---: | ---: | ---: |
| Chat | 49 | 17,605 | 859 | 1 |
| Main activity | 28 | 15,046 | 181 | 13 |
| Debug/logs | 2 | 628 | 22 | 2 |
| **Total** | **79** | **33,279** | **1,062** | **16** |

The retained overall rate is approximately 3.19%, with approximately 4.88% in Chat. No retained frame exceeds 500 ms, and these windows report zero dropped samples or fallback deadlines. This is an affected-device debug-capture baseline, not a measured post-fix release improvement or a workload-independent performance guarantee. The existing physical-workload qualifier still requires exact source/APK identity, device/build, refresh rate, thermal/network conditions, reproducible workload and measured navigation/frame trials.

## Changes beyond the observed failures

**Reduced motion:** GitHub indeterminate progress previously used off-screen animation endpoints. At system animation scale zero, an endpoint could leave the active indicator invisible. The existing motion function now holds a visible midpoint when animations are disabled and responds when the setting changes. Existing lifecycle and frame-pressure behavior remains.

**Research/Combined scope:** A public source transfer previously copied private derived questions, queries, model/tool history and completion state from a different authority. A public reread could also retain source titles or bibliography from an earlier restricted discovery. The transfer boundary uses an explicit public-evidence projection, preserves accepted task identity and spent-work counters, and keeps completion/review authority in its original scope. Public excerpts remain usable for destination analysis; unavailable metadata and incomplete coverage are explicit. Same-authority snapshots retain their original content and identity.

**Repeatable diagnosis:** The JSONL inspector produces stable, content-free failure groups and complete observation counts that can be attached to a repair review. Synthetic behavioral regressions reproduce confirmed source failures without committing the private diagnostic archive. This does not automatically infer a root cause or generate a passing application regression from an arbitrary error string.

**Repeated disconnect qualification:** The gateway's existing production registration, journal, orphan handling and result routes are exercised over 48 jobs, 144 disconnect notifications, six SQLite journal reloads and 336 result reads. The deterministic fixture completes each job once and verifies zero extra inference during result retrieval. Existing process-death tests separately use a real subprocess exit.

## Capability handshake follow-up from the 12:37 UTC archive

The additional diagnostic ZIP was located and inspected independently after the follow-up request. Its SHA-256 is `e06fbb698864aaf5824642f536d5e7c13c4367331bb80da7cff514ea50930f4a`; its context identifies version 0.9.66.6, build 140 and the same `91dfce15bc48` application source. It contains 4,385 recent-log rows, 205 critical-log rows, retained run/invocation records and a current profile snapshot. The private ZIP, conversation, profiles, endpoints and credentials are not committed.

| Evidence | Independently observed result |
| --- | --- |
| `recent-app-logs.jsonl`, rows 3324–3826 | Fifteen `/gateway/ready` requests and fifteen `/gateway/dashboard` requests used port 8080 between 12:35:04 and 12:35:49 UTC. All thirty responses were HTTP 404 and identified the server as llama.cpp. |
| Rows 4165–4191 | The primary chat requested `/gateway/capabilities` on port 8090 at 12:36:55.050 UTC, received HTTP 200 with JSON at 12:36:55.075 and recorded `GATEWAY_CAPABILITY_UNAVAILABLE` at 12:36:55.081. |
| `invocations.jsonl` and `runs.jsonl` | The selected Llama / `qwen3.5-9b` invocation failed after 71 ms with zero visible output and no actual-runtime receipt. Its terminal error explicitly says no model request was sent; the run became failed at 12:36:55.099 UTC. |
| Recent-log rows 4116–4381 | Four successful capability responses were followed by four discovery failures, including the primary chat and three progress-summary attempts. A separate capability GET encountered an unexpected end of stream at 12:37:11.733, then retried and received HTTP 200 before the same discovery failure. |

The response body and underlying decoding exception were not retained. The archive therefore establishes the HTTP-success/discovery-failure boundary, but cannot prove which response field failed in that particular attempt. The matching source independently confirms that Android required Boolean feature values while the real gateway emitted an icon string and a numeric disconnect grace period in that map. A complete response captured from the production gateway route reproduces that compatibility defect. The configured profile snapshot uses port 8090, but a current snapshot cannot establish whether settings changed between the earlier status checks and the chat request.

The follow-up repair covers both sides of the contract. Android accepts legacy JSON feature values while enabling a feature only for an actual JSON Boolean `true`; strings, numbers, nulls, arrays and objects cannot grant a capability. The server keeps the existing icon in `ui_contract.gateway_tool_icon` and places the grace period in typed `singleflight.disconnect_grace_seconds` metadata, leaving only Boolean flags in `features`. Complete legacy and corrected production-route fixtures are shared with Android's decoder tests, with repository checks preventing fixture copies from drifting.

Capability schema failures retain a bounded schema reason and field path without retaining the raw response or decoder exception text. Transport failures, cancellation, unsupported routes and malformed successful responses remain distinct. The status panel and engineering operations use the same explicit gateway-selection policy, preserving the selected gateway's exact URL and credentials. An unconfigured or unavailable gateway produces actionable status rather than probing an inference profile or inventing another port.

These fixes require an updated Android build; the server schema change additionally requires deploying the updated gateway software. Legacy-response compatibility lets the corrected client work with the earlier mixed-feature gateway contract. Automated contract tests do not establish that the private PC deployment has been upgraded or that physical Android-to-PC inference has completed.

## Coverage of the 26 explicitly named requests

“Existing” means the implementation was present at the inspected base and was reviewed against its tests. “Strengthened” identifies relevant changes in this branch. Neither label substitutes for exact-revision CI or physical/private deployment acceptance. Detailed execution and gateway mappings are in the [execution audit](diagnostic-execution-8597579-2026-11-07.md) and [gateway audit](diagnostic-gateway-8597579-2026-11-07.md).

### GitHub Mode and autonomous jobs — eight named requests

| # | Request | Implementation and verification |
| --- | --- | --- |
| 1 | Durable job execution | Existing `AgentRunCoordinator`, `AgentRunDao`, `GitHubExecutionJournal` and gateway SQLite jobs retain fenced work, receipts and checkpoints. Coordinator, journal, job/restart and capacity regressions remain required. |
| 2 | Completion verification | Existing `GitHubAcceptanceLedger`, `GitHubRequestCompletion` and `GitHubRunAcceptance` require all criteria, exact run/fence/response evidence and no unresolved outcomes. Seven run-acceptance host cases were rerun; the observed 0/5 runs remain partial. |
| 3 | Tool outcome tracking | Existing `ToolResultEnvelope` and execution receipts distinguish precheck, dispatch, completion, failure and unknown outcome. Strengthened policy-invalidated admission/terminal handling preserves the real boundary and stronger uncertain-write evidence. |
| 4 | Recovery loops | Strengthened `GitHubTestChecklist` consumes one exact native saved-page correction; existing bounded read/CI repair loops and mutation journals remain. Real restored-page consumption and forged/widened-recipe rejection are tested. |
| 5 | Selected-profile enforcement | Existing `ConfiguredDispatchAuthority`, current tool definitions and gateway execution binding pin profile/model/connection/grants. Changed authority blocks the current run and requires revalidation; it cannot select another provider. |
| 6 | Context preservation | Existing evidence book, review checkpoint and bounded projections retain full durable receipts separately from model context. New blocked paths keep earlier receipts. Missing context never becomes verified completion. |
| 7 | GitHub API reliability | Existing operation argument/read validators enforce SHA/ref/cursor/action contracts. The saved-view repair preserves source scope and exact issued cursor, including after restoration. |
| 8 | Background processing | Existing `GitHubCheckWatchWorker`, `GatewayEngineeringSyncWorker` and run-coordinator recovery supervise/reattach saved work. Host/restart tests do not establish the phone's process-death or private network behavior. |

### Gateway/local AI — eight named requests

| # | Request | Implementation and verification |
| --- | --- | --- |
| 9 | Gateway connectivity | Existing readiness/liveness separation, supervisor and bounded reconnect are retained. Mixed legacy capability values now decode without granting non-Boolean features; the server publishes typed metadata. Status uses the explicitly selected gateway. Wrapped preflight failures no longer extend cooldown; permanent TLS/configuration failures retain actionable identity. |
| 10 | Actual model identity | Existing `execution_binding.py`, inference capabilities/execution and Android transport receipts distinguish requested from observed model/provider/route. Selected-execution tests cover fixture requests, changed configuration and contradictory/missing identity. |
| 11 | Batch inference/cache/context | Existing `inference_scheduler.py`, `inference_budget.py`, batch journal and capability cache remain bounded. Correctness tests cover admission/cancellation/cache identity; no unmeasured throughput gain is claimed. |
| 12 | Streaming reliability | Existing singleflight/replay cursors/durable results prevent duplicate inference for the same admitted attempt. New repeated-disconnect coverage supplements jobs, interrupted-read and subprocess-death cases. Arbitrary providers without a resume protocol do not gain token-for-token replay. |
| 13 | Context/output budgets | Existing completion reservation and context admission reject unsupported/oversized requests before inference. Selected-model/budget tests retain positive output allowance and primary/final-response reserves. |
| 14 | GPU/NPU compatibility | Existing local-runtime router, capability observation, runtime fallback and memory admission remain. The capture does not establish a hardware-runtime fault. Actual QNN/GPU execution and thermal/memory performance require compatible-device receipts. |
| 15 | Listener/network security | Existing gateway configuration, identity and endpoint authorization support explicit binding. No wildcard listener, insecure TLS mode, silent route change or credential logging is introduced. Existing configuration/network/security tests remain. |
| 16 | Correlated bounded diagnostics | Existing Android/gateway journals, correlation, privacy and retention limits remain. Typed MCP cause/retry metadata, bounded schema-only capability reasons/paths and the offline JSONL inspector improve diagnosis. Dedicated heap and 50,000-event durable-retention tests remain separate gates. |

### Android/GitHub experience — ten named requests

| # | Request | Implementation and verification |
| --- | --- | --- |
| 17 | GitHub welcome/setup | Existing `GitHubSetupWelcome`, `GitHubProgrammerHome` and workspace admission require account/repository/branch/profile context. Setup and admission suites remain required. |
| 18 | Interactive repository graph | Existing presentation, minimap and viewmodel use actual file/symbol nodes, edges, selection and pagination. Four host projection tests pass; missing nodes do not create invented graph data. This is not compiler-resolved navigation. |
| 19 | Dynamic progress previews | Existing keyed activity rows update counters in place and retain ordered history. `GitHubLiveProgressTest` covers stable rows, duplicate avoidance and terminal behavior. |
| 20 | Detailed summaries | Existing activity/status summaries retain detail and distinguish passed/skipped/cancelled/unknown/stale evidence. Execution acceptance determines completion independently of display text. |
| 21 | Animation/icon alignment | Existing fixed leading icon column and centered row alignment remain. Live-progress coordinate tests are retained; reduced-motion visibility is additionally repaired. |
| 22 | Scrolling and 250 ms release | Existing anchor requires 250 ms of continuous intentional manual motion, accounts for gaps/reversal and does not consume the user's gesture. Six anchor and four bottom-follow host cases pass; Compose/device frame behavior has separate gates. |
| 23 | Gateway response theme | Existing `ResponseSourceAppearance` requires per-response gateway source and verified gateway provenance. Transport/header/job binding and renderer contrast tests remain. Provider-body labels or generic request IDs do not establish gateway attribution. |
| 24 | Live status/warnings/decisions | Existing decision cards, persistent run notices and progress status retain warnings and terminal state. Policy/MCP repairs improve typed blocked/reconnect outcomes. Gateway status and execution share explicit selection; an absent or invalid connection produces setup guidance without inference-server probes. |
| 25 | Loading/main-thread performance | Existing cached workspace bootstrap and asynchronous optional inventories remain. The confirmed export chooser MIME lookup is removed from the UI thread; file sharing preserves its permission contract. |
| 26 | Accessibility | Existing spoken status, graph semantics, large-font/RTL and contrast coverage remain. Added system-animation-scale regression keeps active progress visible when animation is disabled and when the setting changes. Physical/custom-theme qualification remains separate. |

## Explicit interpretation of the 19 category-only requests

These entries define the implementation interpretation used for the collapsed categories. They were not individually named in the supplied audit.

| # | Category and interpreted requirement | Implementation and verification |
| --- | --- | --- |
| 27 | MCP: typed argument/result contracts | Existing tool schema validation, contract validation and modern MCP discovery/result parsing remain. New recovery accepts only native structured correction recipes, never inferred arguments. |
| 28 | MCP: supervised health and circuits | Existing MCP manager/process supervision/circuit gates remain; nested TLS and wrapped cooldown accounting are strengthened with behavioral regressions. |
| 29 | MCP: capability/inventory freshness | Existing catalog fingerprints and current-grant/selected-profile checks remain binding. Stale inventory is a precheck failure and cannot silently authorize a replacement tool set. |
| 30 | MCP: cancellation and uncertain outcomes | Existing interrupted-read recovery is bounded to safe reads; mutation outcomes require receipts/reconciliation. Generic policy handling must not relabel a started or unknown mutation as unsent. |
| 31 | MCP: actionable configuration/authentication failures | Supervisor retains safe error code, failed stage and retryability; resolver/settings consumers surface permanent reconnect requirements. Successful verified discovery clears stale details. |
| 32 | Plugins/tools: isolation and resource limits | Existing connection/grant/actor boundaries, MCP subprocess I/O/time limits and tool budgets remain. Fault tests exercise silent, oversized and flooding subprocesses; no new privilege or destination is granted. |
| 33 | Research: selected, authorized model stages | Existing `DirectResearchModelFactory` and `ResearchModelCoordinator` admit only configured candidates, validate identity/permissions and retain stage outcome evidence. Unavailable or changed models do not silently authorize another account. |
| 34 | Research: durable restart and budgets | Existing startup reconciler, task/session stores and accepted-attempt fences preserve evidence and spent work. Restart/checkpoint/cancellation regressions remain; sharing public evidence does not reset counters. |
| 35 | Research: source grounding and missing coverage | Existing content validator, source identity, claim capture/version/quote binding and review digests distinguish leads, excerpts and verified captures. Unknown publication/coverage metadata remains unknown. |
| 36 | Memory: scope, privacy and retention | Existing memory-scope resolver, encrypted memory/vector stores and retention policies isolate authorized conversations/projects and historical evidence. Historical memory is not current execution proof. |
| 37 | Combined: bounded work and revision recovery | Existing `CombinedTurnBudgetStore`, reservations, immutable input snapshots and delivery checkpoints retain ownership, budgets and uncertain outcomes across revisions. Existing Combined budget/recovery/delivery tests remain. |
| 38 | Combined: cross-authority evidence transfer | Strengthened public-capture projection excludes private derived run context and inherited source metadata. Same-authority snapshots remain intact; public excerpts stay usable without inheriting completion or review authority. |
| 39 | Testing: diagnostic failure fixtures | Added actual saved-page/policy/TLS/export/reduced-motion/research regressions and bounded schema-7 inspection fixtures. Input digest and complete line mappings allow comparison with a future capture without publishing private payloads. |
| 40 | CI: exact-revision required gates | Existing canonical `Required verification`, workflow identity and missing/skipped/failed/cancelled rejection remain. Branch/head/base must be reconciled before merge; no gate, checksum or test watchdog is weakened. |
| 41 | Android: build/install/UI acceptance | Full Android unit, heap and API 34/API 36 instrumentation workflows remain mandatory. Host adapters are not Android installation evidence. Physical-phone behavior still requires that phone. |
| 42 | Release: identity/signatures/artifact verification | Existing release evidence, draft/published identity reconciliation, signature and downloaded-byte checks remain. This repair does not republish an existing version or imply an existing release contains the new branch. |
| 43 | Security: dependencies/secrets/static analysis | Existing dependency verification, secret scanning, pinned actions and CodeQL remain. TLS verification, endpoint consent, mutation authorization and current grant checks are preserved. |
| 44 | Stability: disconnect/restart soak | Added 48-job bounded disconnect/journal-reload regression; existing real subprocess-death and durable stress tests remain. Prolonged live phone/gateway soak is explicitly a separate measured workload. |
| 45 | Diagnostics: retention and reproducible qualification | Existing bounded export/storage/heap controls remain. The inspector reconciles retained-source counts and refuses invalid continuations/references; qualification scripts reject absent source/device/workload evidence. |

## Qualification and handoff

The integrated follow-up host check passes 378 script tests plus policy, dependency, secret, Android regex, resource, icon and source-provenance checks. After the capability follow-up, the full gateway check completes 46 isolated Python suites: 531 tests pass and one existing LAN-interface test is skipped because this host has no bindable LAN IPv4 interface. All seven browser tests pass. The earlier 529-test run likewise included that one environment skip; it did not qualify actual LAN access. Focused production-source host runs additionally pass 85 GitHub/session/classifier, 76 MCP/network, 24 UI-policy, ten research-transfer/delivery and 23 final runner/budget/measurement-wrapper cases; these overlap later full-app suites and are not a unique combined test count. The follow-up additionally passes 31 capability/API/negotiation cases, three independent diagnostic/privacy probes and 17 status-selection/viewmodel cases, with the new no-selection regression failing against the previous resolver. Platform adapters and each work package's exact boundaries are recorded in the linked audits and task checkpoints. Those results do not substitute for the integrated branch's exact-revision required workflows. The pull request's current checks and retained artifacts are the authoritative remote qualification record; a later source or base change invalidates earlier merge evidence.

Run the canonical checks from a configured JDK 21/Android SDK 37 checkout:

```bash
bash scripts/validate-local.sh fast
bash scripts/validate-local.sh full
```

The full gateway check includes the new bounded connection test and the existing 50,000-event/782-commit durable diagnostic stress suite. Model generation in the new connection test is deterministic; registration, journals, disconnect handling, restoration and result routes are production code. The existing subprocess-death case verifies real termination separately. These measurements do not certify an hours-long live deployment.

### Android CI repair and required rerun

The first PR revision completed 7,295 ordinary Android unit cases: 7,289 passed, one reduced-motion test failed and five dedicated heap cases were skipped by explicit assumption gates. Both API 34 and API 36 emulator jobs passed 55 instrumentation cases each. These results belong to the earlier `bf8e5f7` revision and do not qualify the subsequent capability/status changes. The five skipped cases require the separate constrained-heap step, which did not execute after the unit failure; they are not counted as passed.

The motion failure was a test synchronization defect. Starting with animation disabled leaves no animation coroutine to publish a raw Snapshot change during a manually advanced clock. Pinned Compose 1.12.1 runtime, animation and test-clock classes reproduce the old sequence retaining phase `0.5`; publishing the Snapshot before advancing gives a changing phase and then a stationary `0.5` when disabled again. The regression now synchronizes after both setting changes and checks two successive moving samples as well as stationary visibility. The production motion helper is unchanged. The PR also corrects two documentation whitespace errors. The final exact-head workflows must independently pass ordinary units, the constrained-heap gates, APK verification and both emulator targets before merge.

The next revision, `b1647150`, passed the reduced-motion regression and all 17 gateway status cases in full Android tests. Its 7,309 ordinary cases had one different failure: the new cancellation test required the identical outer exception instance, while Gradle's assertions-enabled coroutine diagnostics returned a recovered copy with the original cancellation as its cause. The revised regression requires the cancellation type, original message and original causal identity, exactly one capability GET and no connection eviction or retry. The unchanged baseline reproduces the failure with JVM assertions enabled; all 31 capability cases pass after correction with assertions/debug enabled and stack-trace recovery both enabled and disabled. No production code or JVM build setting changes. The five heap-gated ordinary-suite skips remain subject to their separate gate on the new exact head. See [the cancellation CI repair](capability-cancellation-ci-8597579-2026-11-07.md).

### Packaged runner size and MCP refresh race

Revision `c285a868` passed all 7,304 executable ordinary cases in PR Validation, with the same five explicit constrained-heap assumptions. The separate memory step then passed all 11 cases across five suites with no skips; all five ordinary skipped case identities match passing dedicated results. Android lint also passed. Its parallel diagnostic audit completed 7,309 cases with one intermittent failure: explicit MCP refresh returned a failure without making a new HTTP request. A passing parallel run did not waive that failure. The audit's artifact is `13577426154`, SHA-256 `709b242a95751485517c6d6f2fa5bcea375159a4ac03310e5346f8a1dcfdba94`; the clean PR-unit artifact is `13577591813`, SHA-256 `f5ed705b3b15f3c6a4f23a0027bd5eff24bd086685923825506954122dc5a43b`.

The MCP manager could reuse a terminal discovery producer before its completion callback removed the map entry. The selector now shares only unfinished producers, while retaining active-request coalescing and identity-checked cleanup. A new test captures a real failed discovery producer, deterministically recreates that valid cleanup-window state, and verifies that explicit refresh sends another HTTP request. The unchanged implementation fails that assertion; the fixed actual-source MCP/network harness passes all 77 cases with JVM assertions, coroutine debug and stack-trace recovery enabled. No cooldown, timeout, permission or test assertion was relaxed.

The same revision's UI Review APK passed app, device-test and benchmark compilation plus native-runtime and telemetry checks, then failed the existing guarded-method size check. `AgentRunner.executeLoop` measured 24,310 DEX code units against the exclusive 16,383-unit gate. Invocation-local state and two non-inline stages reduce it to 6,244 units. Pinned D8 exactly reproduces the original packaged measurement before measuring the refactor; all runner methods remain below the unchanged gate, with the largest at 8,970 units. The actual-source suites pass 23 focused and 94 broader cases, including repeated collection of the same flow to detect shared budgets or finalization flags. See the [runner-size repair](runner-dex-size-8597579-2026-11-07.md). The integrated published source is `de2daf514c81b8a680e96ae706902bdc22d8cd2b`; its subsequent review-checkpoint commit and all current PR checks must be reconciled before merge.

Both `c285a868` emulator jobs passed 55 functional cases. Their real statistics workload also retained measurement gaps: API 34 recorded one unacknowledged telemetry start and one recorded-in-progress entry; API 36 recorded one unacknowledged start and none in progress. These are observed runtime measurements, not injected negative fixtures. The logs do not establish the exact reason for each missing acknowledgement, and performance remains unqualified. Functional acceptance, bytecode reduction and bounded host tests are not claims of improved physical-device frame pacing.

### Prolonged device/gateway acceptance procedure

Record the exact app commit, APK SHA-256, gateway source/package digest, selected profile/model, observed route receipt, device/build/refresh rate, network, thermal state and workload revision. Use a disposable repository and explicitly authorized mutations. Keep raw captures private.

1. Run a multi-hour selected-profile job workload with repeated safe reads, checkpointed long inference and independently verified disposable writes. Record one operation identity and expected result for each authorized mutation.
2. Interrupt the Android connection during queueing, streaming, tool handoff and completed-result retrieval. Reattach to the saved job and verify retained text/results, current model binding and no additional physical mutation for an uncertain operation.
3. Terminate/restart the gateway and the Android process at recorded boundaries. Verify saved terminal results and blocked/interrupted active work, then resume only after current authority and remote outcomes have been reconciled. Missing historical evidence stays unresolved.
4. Change the selected profile, tool grants and gateway configuration while work is queued. Verify a typed blocked outcome, retained completed receipts and no request under stale authority or silent alternate model.
5. Test valid and invalid TLS, DNS loss/recovery and explicit MCP reconnect. Verify correct diagnostics, bounded safe recovery, no security downgrade and no timer-based promise for a permanent configuration failure.
6. On the affected physical phone, check verified gateway theme through streaming, process restoration and a subsequent direct-provider response; compare source receipts with rendered content. Exercise export sharing, large fonts, screen reader navigation, animation scale zero and the 250 ms manual-scroll release.
7. Repeat the recorded rendering/navigation workload in a release or benchmark build and feed measurements to `scripts/qualify_gateway_github_performance.py`. Compare like-for-like device/workload/thermal conditions; retain failures and missing measurements.

Stop the workload on duplicate writes, unverified success, silent profile changes, missing durable receipts, unsafe replay or a security downgrade. A passing deterministic host/emulator suite does not waive these device/private-deployment acceptance checks. The supplied capture has no post-fix physical-device, private gateway, GPU/NPU or prolonged-live-soak evidence, and this branch does not manufacture it.
