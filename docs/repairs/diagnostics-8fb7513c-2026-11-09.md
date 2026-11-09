# Diagnostic and app-audit repairs — 2026-11-09

## Scope and evidence

| Item | Identity |
| --- | --- |
| Repository | `tailscale-signin/GPT_Mobile_AI-improved` |
| Base | `main`, `f27fdbbc0ec351e3134ca0285dadf3e97bf28155` |
| Working branch | `fix/diagnostics-8fb7513c-2026-11-09` |
| Parsed diagnostic input | Uploaded diagnostic archive beginning `diagnostics-8fb7513c-d551-43bb-bbe4-6f2bbb231a84`; its JSONL records were inspected locally |
| Additional supplied review | Review of `app-audit-1794200904370.json`, app `0.9.66.6`, debug, source `78faa5a724efde8641d97c2e756ac8194d5028b9`, November 9, 2026, approximately 00:08:07–00:08:17 Toronto time |
| Later supplied diagnostic review | A 23-finding review of a 4,418-line JSON diagnostic report; that JSON was not attached. Original `F01`–`F23` labels and reported line references are retained below. |

The raw app-audit JSON was **not attached** to this repair session. Its original check IDs, aliases, counts, timestamps and findings below are attributed to the supplied review, not to a fresh parse or a newly executed audit. The diagnostic ZIP was available and parsed separately. The app-audit review alone does not establish a crash, database corruption, failed generation or lost gateway job; the separate ZIP does establish the Gateway screen crash described below.

No private connection names, endpoints, credentials, request/response bodies, conversation text or raw captures are committed with this record. Target aliases are local to their historical run and cannot identify another run's saved connections. New checks in this branch do not renumber, rewrite or promote historical evidence.

## Repairs supported by the diagnostic ZIP

| Finding | Source change or confirmed existing safeguard | Remaining acceptance |
| --- | --- | --- |
| Gateway conversations crashes with `IllegalArgumentException` from `DiagnosticFrameTiming.kt:13`, called by `GatewayConversationsScreen.kt:109` | The caller used a label containing a space while the diagnostic modifier required a restricted identifier. The caller now uses `GatewayChats`; rejected labels return the unchanged modifier without installing telemetry or throwing. `DiagnosticFrameTimingTest` measures and draws content with valid and invalid labels. | Run the Compose regression and open/reopen the Gateway screen on the exact APK. The local host test does not execute Android rendering. |
| Saved GitHub result pages consume work needed by integration/checklist stages | Integration/report-only `GitHubVerifiedSession` owns its recovery selection. Generic `AgentRunner` fragment recovery cannot preempt its retained-original evidence, checklist, reserve or cleanup policy. `GitHubReplayRecovery.State` retains the 32-read automatic-recovery limit independently of replay compaction and restores completed/pending reads. | The regression changes twelve context calls, including eleven redundant fragments, into the three required context/PR/CI family reads with zero fragment calls. Actual repository work, requested outcomes, required checks and any owned-fixture cleanup still need remote receipts. |
| Authorized catalogue and execution wrapper disagree | `ProgressiveToolCatalog` binds already-authorized candidate names to the final executable wrapper schema, including the recovery-effect field. Same-source rediscovery preserves that binding; a real schema/version/capability/dependency change removes it and requires new binding. Initial and dynamically acquired tools use the same rule. Strict GitHub mismatch diagnostics accept only fixed-shape hashes/versions and use safe invalid/missing markers for malformed remote fields. | Run complete Android wiring and exercise a real authorized catalogue. This does not weaken admission, admit an unknown tool or echo rejected schema/version text. |
| Follow-up or evidence-only planning treats retained receipts as fresh tool requirements | Context planning receives the original requested task separately from prepared evidence. Explicit evidence-only, final-writing and stopped-acquisition paths do not require executable tool schemas they cannot use. Ordinary execution still enforces explicitly required tools and preserves GitHub catalogue visibility. | Android repository/continuation integration remains a full-build gate. A summary of retained work is not evidence of a newly completed external task. |
| Gateway backend properties return HTTP 400 and strict reasoning admission remains unverified | Gateway error classification recognizes fixed, exact-selected-model router responses for unloaded/missing models and missing model selectors. Unknown HTTP 400 bodies remain unknown; authentication denial takes precedence. Android retains the actionable gateway reason during reasoning recheck instead of replacing it with a generic unverified result. | The capture does not prove which private backend condition caused its 400. Run `doctor --model` with the exact intended ID on the gateway host; repair the observed server/proxy/model condition, then requalify. Do not substitute another model, loosen strict reasoning policy or issue generation merely to probe readiness. |
| Slow frames and oversized workspace composition | The ZIP records skipped-frame windows of 30, 30 and 33, slow Chat/GitHub workspace measurements, and ART's 17,086-instruction compilation-limit warning for a workspace composable. Repository controls and file editing now compile as separate composables; selector state, edit staging, dirty-navigation guards and partial-file safeguards are preserved. | No numerical performance improvement is claimed. Compare the exact APK on the same device/workload, collect frame distributions and Java/native memory evidence, and check scrolling, focus, loading and animations. |
| SpeechRecognizer service binding reports error 10 twice in the inspected critical log | Current main already retains cross-session binding backoff and a specific service-repair action, preserves partial speech for explicit review, and prevents automatic repair retries or silent online fallback. The later review prompted a precise correction: Android error 10 always records its request-limit meaning, separately from the pre-ready service-start phase. Fixed engine ownership lifecycle fields improve subsequent evidence. | Confirm the intended recognition service, permissions and lifecycle behavior on the affected device. A host gate test cannot repair or qualify its speech service. |
| TTS connection/catalogue recovery | Existing code already retires a disconnected engine after a null/failed voice query, fences generations and avoids idle/unbound `stop()` calls. A missing callback during catalogue-only initialization was not bounded; it now expires after ten seconds, retires once and permits an explicit recheck. Duplicate initialization and foreign/stale utterance callbacks cannot change the current engine's state or leak an unexpected identifier. | New Robolectric regressions require Android CI. A platform service can still disconnect; its initial failed Binder query and required shutdown are not evidence that the app can prevent every framework warning. |
| `asus_leak_detect_proj` protected-setting/finalizer exception | Its frames originate in framework/vendor code. Existing diagnostics retain the origin without establishing app root-cause attribution. No protected permission, hidden API or setting mutation was added. | Reproduce with the exact firmware and compare process-exit evidence and an AOSP/other-device control. Any firmware correction belongs to the device/vendor environment. |
| Empty runtime incident history despite retained crash evidence | Runtime incidents cover local model/native/worker history; application crashes were already separately checkpointed into `critical-app-logs.jsonl`. Export scope now says so and names that evidence file. | Empty runtime history does not mean that no application crash or other session failure occurred. Verify both evidence scopes when qualifying a new capture. |

Implementation entry points include [the diagnostic modifier](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/DiagnosticFrameTiming.kt), [workspace composition](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/GitHubWorkspaceScreen.kt), [runner recovery](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/AgentRunner.kt), [catalogue binding](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/ProgressiveToolCatalog.kt), [request admission](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/ToolCatalogAdmission.kt) and [gateway error classification](../../gateway/v14/backend_observation.py).

## Supplied app-audit review: all 16 finding groups

### 1. Credential failure, two cached discovery blockers and their age

| Original check and target | Reported observation | Implemented support and required next observation |
| --- | --- | --- |
| `check-48`, `target-11`; configuration `check-47` passed, execution `check-49` untested | `MCP_LOCAL_CREDENTIAL_INVALID`, phase `local_validation`, rule `CREDENTIAL_REAUTHORIZATION_REQUIRED`, field `credential`, `NOT_DISPATCHED`, retryable false | The exact-connection action clearly offers credential repair. Reauthorize or replace the intended saved credential, request fresh discovery, then run an authorized named tool fixture. A configuration pass does not prove a usable credential or execution. |
| `check-36`, `target-7`; configuration `check-35`, execution `check-37` | Cached HTTP 200 with `MCP_DISCOVERY_SCHEMA_INVALID`, field `supportedVersions`, rule `PROTOCOL_VERSIONS_ARRAY_REQUIRED` | Inspect the server/bridge protocol response and correct its version-discovery contract. Explicitly selected network reads can perform a bounded fresh check of this allowlisted remote contract failure. A successful HTTP response is not a valid discovery schema or a tool execution. |
| `check-42`, `target-9`; configuration `check-41`, execution `check-43` | Cached HTTP 404 with `MCP_DISCOVERY_CONFIGURATION`, field `server_discover`, rule `DISCOVERY_REQUEST_ACCEPTANCE_REQUIRED` | Inspect the actual saved endpoint, protocol context and response in the authorized local/server environment. Correct the observed cause and request a fresh check. The patch does not infer one cause from HTTP 404 or introduce a blanket alternate-route fallback. |

The supplied review places the original cached observations at approximately 22:38:44 and 22:38:45 Toronto time on November 8, around 89 minutes 26 seconds before the audit. Those are retained observations, despite network reads being enabled for the audit. The age alone does not establish a cache defect: recovery gates can intentionally preserve a failure. The UI now exposes original time and age before expanding details, and the explicit refresh path can test a repaired remote contract without enabling a disabled target. Authentication, TLS, saved-revision and unrelated recovery constraints remain enforced. No old receipt becomes a fresh success.

### 2. Three intentionally disabled integrations

| Integration | Historical target | Configuration / execution checks | Disposition |
| --- | --- | --- | --- |
| Crawlers | `target-17` | `check-59` / `check-60` | Preserve disabled state |
| Deep Research | `target-19` | `check-62` / `check-63` | Preserve disabled state |
| Google service | `target-28` | `check-80` / `check-81` | Preserve disabled state |

The configuration blockers were admission results with `PERMISSION_REQUIRED` and `NOT_DISPATCHED`. They are intentional scope exclusions, not demonstrated service outages. The app and qualification worklist must not enable them to improve the score. If the user later chooses one, its enablement, authorization, discovery where applicable and behavioral fixture become a separate explicit action.

### 3. Ten absent optional marketplace integrations

| Optional integration | Historical target | Configuration / execution checks |
| --- | --- | --- |
| ArcGIS | `target-31` | `check-86` / `check-87` |
| Eventbrite | `target-32` | `check-88` / `check-89` |
| Foursquare | `target-33` | `check-90` / `check-91` |
| Google Places | `target-34` | `check-92` / `check-93` |
| OpenRouteService | `target-35` | `check-94` / `check-95` |
| OpenStreetMap | `target-36` | `check-96` / `check-97` |
| Ticketmaster | `target-38` | `check-100` / `check-101` |
| TomTom | `target-39` | `check-102` / `check-103` |
| Universal Marketplace | `target-41` | `check-106` / `check-107` |
| Yelp | `target-42` | `check-108` / `check-109` |

These ten unavailable configuration checks and their untested execution checks remain visible. Installation is optional and is not performed by this repair. A later chosen installation requires its exact package/configuration, permissions and expected behavioral result; presence in the marketplace is not execution evidence.

### 4. All 39 tool-execution checks remain untested

The supplied review reports `PLUGIN_EXECUTION_NOT_EXERCISED`, `attempted: false` and `NOT_DISPATCHED` for every row below. The 13 disabled/absent targets in sections 2–3 are included in these 39; they are not additional execution gaps. The remaining 26 targets still require named, authorized behavioral fixtures.

| Group | Original execution checks and targets/integrations |
| --- | --- |
| Five saved configured connections | `23` → `target-1`; `25` → `target-2`; `27` → `target-3`; `29` → `target-4`; `31` → `target-5`. Private saved labels remain omitted. |
| Six MCP connections | `34` → `target-6`; `37` → `target-7`; `40` → `target-8`; `43` → `target-9`; `46` → `target-10`; `49` → `target-11` |
| Search/shopping integrations | `53` Airbnb; `55` Amazon Free; `57` Amazon Search |
| Disabled research integrations | `60` Crawlers; `63` Deep Research |
| Device and repository integrations | `65` Device Location; `67` GitHub; `69` Health Connect |
| Memory and delegation | `71` Local Memory; `73` Model Delegation |
| Content acquisition | `75` News; `77` Read Files; `79` Read URL; `85` Web Search |
| Google and voice | `81` Google service; `83` Voice Chat |
| Optional marketplace group A | `87` ArcGIS; `89` Eventbrite; `91` Foursquare; `93` Google Places; `95` OpenRouteService; `97` OpenStreetMap |
| Marketplace group B | `99` Refuge; `101` Ticketmaster; `103` TomTom; `105` Toronto; `107` Universal Marketplace; `109` Yelp |

All numbers in this table have the original `check-` prefix. MCP catalogues containing 46, 12 or 3 tools do not establish execution of any listed tool. The local GitHub exact-ref fixture does not establish live `check-67`, and there was no model-delegation execution in this audit. The exported worklist now exposes readiness and blocked prerequisites for each gap, but it does not select or invoke an arbitrary advertised tool. Start with a named read-only fixture and known expected result. A necessary write requires an explicitly authorized owned fixture, dispatch/terminal receipts, remote reconciliation after an uncertain outcome, and verified cleanup.

### 5. All ten original external suites require their own environment

| Original suite | Required behavior and environment |
| --- | --- |
| `check-111` — Android lifecycle | Exact APK/device; startup, recreation, foreground/background transitions, interruption, durable state and resume without duplicate work |
| `check-112` — Model providers | Streaming/SSE and split UTF-8, reasoning versus visible output, tool envelopes, finalization/errors, selected-model generation, cancellation and unavailable-model behavior |
| `check-113` — Local inference | Actual model/backend loading, admission/resources, useful output, cancellation/unload, failures and observed backend use |
| `check-114` — Network recovery | DNS, refusal, TLS, authentication, timeout, cancellation, bounded retry, partial/terminal streams and conservative unknown-dispatch handling |
| `check-115` — Voice/media | Real permission grant/denial/revocation, recording/playback, focus, interruption and lifecycle cleanup |
| `check-116` — Native bridge | Exact device, ABI and binary; native initialization, ownership/lifetime, backend behavior and error propagation |
| `check-117` — Gateway software | Actual deployed identity, protocol/authorization/recovery, selected generation, durable jobs and saved-result recovery |
| `check-118` — Authentication service | Deployed identity, authentication/authorization, callback/refresh/replay handling, recovery and Android handoff |
| `check-119` — External integrations | Authorized real behavior, service identity where available, protocol, permission failures, bounded pagination/recovery and owned writes/cleanup where needed |
| `check-120` — Other production | Enumerate residual source-inventory subsystems and assign named behavior/assertions; a generic category or source count cannot qualify them |

All ten were `EXTERNAL_HARNESS_REQUIRED`. The reported run lasted about 9.7 seconds and consumed 58/128 executed probes and 5/16 network probes. The 39 tool gaps and ten external gaps were planned exclusions, not evidence of budget exhaustion. Raising a timeout or call limit cannot convert them into measured execution.

### 6. One selected model had no generation check

The historical plan had one selected profile but zero `MODEL_EXECUTION` entries. A local selected-profile routing fixture does not generate with that model. The branch adds separate selected-model configuration and generation-qualification entries. The latter remains external/untested and offers **Prepare model benchmark** for the exact current saved profile. Configuration and opaque credential revisions are checked before the queue selection changes; generation still requires the explicit Run action.

Actual acceptance must retain requested and observed model identity, readiness, useful final-text assertions, first output, finalization/finish reason, unavailable-selected-model handling and any observed backend. No alternative model or hardware acceleration is inferred from metadata.

### 7. Gateway capability success is narrower than gateway execution

`check-51` was reported as a 152 ms `LIVE`/`CONFIRMED` capability response. `check-117` remained untested. This supports a capability exchange, not selected-model readiness/inference, successful tools, jobs/queue operation, conversation persistence, reattachment or durable work while Android is closed. The new selected-model and external-suite plan makes those gaps explicit. They still require an independently deployed gateway and actual execution/recovery artifacts.

### 8. Gateway receipt lacked its observation binding

The review reports a frozen configuration revision on `check-51`'s plan but no result revision or observation timestamp, unlike the MCP successes. Gateway capability receipts now retain their observation time and accepted opaque revision. Safe remote declarations are separate fields. Configuration drift still invalidates current qualification; adding these fields does not retrospectively complete the historical receipt.

### 9. Qualification worklist ignored failed discovery prerequisites

Execution checks `37`, `43` and `49` were labelled configuration-ready without linking the blockers at `36`, `42` and `48`. The worklist now includes the configuration check, discovery check, separate configuration/credential/discovery readiness, blocking check IDs and an actionable next step. Credential repair takes precedence where required; a fresh discovery pass needs the matching revision, timestamp and live dispatch evidence. Configuration readiness alone is insufficient.

The worklist remains `PLAN_ONLY`: it grants no execution authority, cannot bypass authentication or import a score, and cannot turn an untrusted self-declared harness pass into an audit observation. Its exact-build/plan/configuration bindings remain part of review.

### 10. Rendering/accessibility requirements had no assigned suite

The historical plan's origin-color and contrast fixtures (`check-1` and `check-2`) did not assign `RENDERING_AND_ACCESSIBILITY`. Every source area now retains an external-suite entry even when narrow local checks already exercise associated code. The UI/accessibility family names concrete interaction tests. Compose behavior, focus/IME, semantics, screen configurations, scrolling, animations and loading still need the corresponding Android/device run. The Gateway crash and workspace tests added here do not establish whole-UI coverage.

### 11. Database recovery requirements had no assigned suite

`check-18`'s JSON round trip does not establish migrations, interrupted writes, process death, data integrity or job/conversation restoration. The all-area external plan now retains a persistence suite and maps it to database migration and gateway persistence/recovery harnesses. Those tests must run against the relevant exact schema/build with disposable data. This audit review itself reports no database corruption.

### 12. No device measurements were planned

The old plan contained zero `DEVICE_MEASUREMENT` checks. The branch adds an explicit rendering/resource measurement entry and a device-suite worklist requiring identified APK/device/workload evidence. Frame timing/jank, Java/native memory, resource use and accelerator/backend claims remain unmeasured until such evidence exists. Host/JVM or emulator success cannot be relabelled as physical-phone performance or supported NPU/GPU execution.

### 13. Source inventory and exercised associations are not line coverage

The supplied review reports 2,356 files, 366,859 text lines and 26 source areas, with 15 areas associated with exercised checks. The eleven without such associations were Android shell, Marketplace plugins, Model providers, Local inference, Network, Voice/media, Native bridge, Gateway software, Authentication service, External integrations and Other production. The export correctly retained `lineCoverageAvailable: false`, a null percentage and `NO_EXECUTION_INSTRUMENTATION`.

These are historical inventory/association counts. The branch regenerates its own inventory after production changes. Neither associated areas nor the new external-suite rows establish comprehensive source execution. Actual line/branch claims require matching instrumentation reports.

### 14. A perfect measured score must retain the failed and untested scope

The reported 120 outcomes were 55 passed (29 configuration, 22 local contract and four live metadata), one failed, five blocked, ten unavailable and 49 untested. The 100% measured score described 26/26 eligible measured checks. Verified planned coverage was 26/120, about 21.7%. A locally rejected credential, cached data, configuration-only outcomes and exclusions do not earn measured points.

The result card now keeps score and coverage/counts in one accessible group: **Measured checks 26/26 passed · Planned checks verified 26/120 · 1 failed · 5 blocked · 10 unavailable · 49 untested**. The regression uses the actual failure shape: a live-metadata plan with a local, undispatched observation. `COMPLETED_WITH_FAILURES` remains visible. A successful repair requires new evidence; changing presentation does not erase the original failure.

### 15. App, remote deployment, APK and device identities differ

The app's commit/build declaration does not identify a running gateway, authentication service or integration deployment. The supplied review also lacked exact APK-digest/device qualification evidence. Available safe gateway version/instance/commit/build fields are now exported and displayed as `DECLARED_CAPABILITIES`, with declared and independently verified identity kept distinct. Missing third-party build identity remains unknown and limits source-version claims without discarding otherwise supported functional observations.

For actual qualification, retain the exact APK digest, app commit/version/variant, OS/API/device or emulator, relevant ABI/backend/model, and controlled remote deployed commit/artifact/instance identity. Never derive a remote commit from the Android commit or catalogue contents.

### 16. Privacy-preserving aliases need an exact in-app repair route

Aliases `target-7`, `target-9` and `target-11` intentionally hide saved names, endpoints, raw bodies and credentials in exports. The existing in-app route already resolves the exact surviving saved UID without matching a name or substituting a replacement. This branch keeps that route, improves the credential action label, and makes retained observation age visible. Current saved names remain display-only. Opening the screen, refreshing settings or saving through its editor does not automatically check, sign in, enable or install an integration.

## Later supplied diagnostic review: F01–F23

This section preserves the later review's finding IDs, priorities and line references. Its 4,418-line JSON was not supplied, so these references are **reported review locations**, not newly verified line numbers in the ZIP's individual JSONL files. A repeated record, frame window or related root reference is not automatically a separate defect. Implementation and environment follow-up are identified independently.

| Finding | Supplied review evidence | Disposition and acceptance boundary |
| --- | --- | --- |
| **F01 · P0 — Gateway crash** | Lines 26–41, 126–130, 204–207 and 266–269 | **Implemented:** valid fixed screen label plus nonthrowing diagnostic modifier. Compose/device reopening remains an Android acceptance gate. |
| **F02 · P1 — Backend properties 400** | Four undispatched invocation records at lines 9, 10, 20 and 21; later context at 931 | **Implemented:** exact-model typed gateway classification and retained Android repair reason. **Host follow-up:** inspect the actual backend rejection; the unsupplied body cannot establish unloaded-model, selector, authorization or route cause. |
| **F03 · P1 — Readiness differs from API reachability** | Four readiness 200s, two 503s, eight two-second timeouts and two EOFs versus seventeen successful jobs calls and four successful identity calls; lines 876–903, 1880/1889, 2131–2156, 3165/3175, 3693, 3868/3878, 4121/4131 and 4355–4380 | **Reviewed existing safeguards:** one observer/poll job per route and model, cancellation on observer detach, a global two-probe bound, active/background polling with failure backoff, and one bounded retry for eligible read-only connection failures. TLS/authentication/HTTP/document failures are excluded from that retry. Readiness and capability timeouts were not increased. Reachability, capability/API availability, selected-model readiness and job execution remain distinct: successful jobs/identity responses cannot qualify a model, and failed readiness cannot prove every route is down. Host/model health and EOF reproduction remain environment work. |
| **F04 · P1 — Fragment-read starvation** | 229/267 local calls were reported as saved fragments, including 110 for one branch-dependency result; cache counts 94/100 and 237/256; lines 1100, 1358–1737 and 1802 | **Implemented:** planner-owned recovery and a retained automatic-read ceiling. Retained originals remain accessible without consuming the integration/checklist/cleanup reserve on display fragments. No remote mutation or completion is inferred from the repaired host regression. |
| **F05 · P1 — Catalogue not exhausted** | Lines 1101, 1178 and 1803 | **Implemented/retained:** deterministic catalogue cursors plus executable-wrapper binding; a changed real contract requires fresh binding. A genuinely unavailable authorized family remains an explicit blocker rather than a fabricated empty catalogue. |
| **F06 · P1 — Owned fixture not tested** | Lines 1102, 1179 and 1804 | **Existing safeguard:** owned create/use/cleanup reserves and uncertain-write reconciliation remain covered by tests; the planner can now reach the intended work. **Live follow-up:** run the authorized fixture and verify cleanup. `NOT_TESTED` establishes neither a mutation nor a leaked remote resource. |
| **F07 · P1 — Retry with zero executable allowance loses structured work** | `RETRY_FAILED_STAGE` with zero serialized tools, zero calls and 0/5 allowance; lines 975–1013, 1036, 1166–1181, 2823 and 2933 | **Implemented:** retained authorized evidence identities/contracts are separate from budget-executable tools, so zero allowance can restore the original structured ledger. Unfinished operational retries report the budget/catalogue blocker without falling into a new prose generation or renewing the budget. A verified final allowed operation can still complete. Focused regressions cover empty-tool execution, restored evidence without renewed authority and this completion boundary; Android repository wiring remains a full-build gate. |
| **F08 · P1 — Exact required tool unavailable despite other tools** | `github_context__github_api` unavailable while eighteen tools were present; lines 426–428, 498, 2325 and 2335–2341 | **Implemented:** original-request admission is separate from prepared receipts, and final wrapper schemas agree with the authorized catalogue. The presence of other tools does not permit substitution for an exact required identity. |
| **F09 · P2 — Generic blocked criteria and premature not-applicable** | 51/64 and 40/64 blocked criteria plus a `NOT_APPLICABLE` operation; lines 1105–1162, 1807–1864 and 1842 | **Implemented:** typed per-operation prerequisite/readiness codes and canonical `github_decide` identity. An unresolved API operation remains explicit instead of becoming not-applicable; applicability, authorization, availability and attempt status remain distinct fields. Actual prerequisite repair and operation outcome require new evidence. |
| **F10 · P1 — Recognition error 10** | Review reports four binding failures at lines 19, 375, 504, 509, 3301–3304 and 3502–3505 | **Implemented:** `SPEECH_RATE_LIMITED` preserves Android's `ERROR_TOO_MANY_REQUESTS` meaning even before ready; `service_start` remains a separate phase. Existing 5/15/30-second startup backoff, one-owner admission and deliberate service repair remain. Fixed engine instance/owned-count lifecycle records add no transcript, raw voice name or arbitrary locale text. |
| **F11 · P2 — TTS disconnect/unbound calls** | Connected/disconnected, unbound voice query/cleanup and reconnect sequence at lines 3299–3305 and 3489–3501 | **Implemented/retained:** existing null-catalogue retirement, idle-stop guard and generation ownership; new bounded catalogue initialization, duplicate-init and current-utterance guards. Preferences already load/save off main and preserve a newer local edit during asynchronous load. Real engine/firmware behavior remains a device check. |
| **F12 · P1 — UI jank** | Review aggregation: seventy windows, 19,931 frames, 741 janky frames, fifty-one above 100 ms, maximum 341.5 ms; nested Chat/workspace measures around 220.2/222.9 and 210.5/214.6 ms, Home 61.7 ms; lines 848, 2343–2344, 3179–3180 and 3613 | **Device qualification:** retain these as reported historical samples, not a new benchmark. Nested measurements must not be added as independent elapsed time. Known workspace compiler pressure is addressed in F14; no paired numerical jank improvement is claimed. |
| **F13 · P2 — Text measurement cost** | Thirteen measurements reported at 17.2–42 ms; lines 1974–1977, 1996, 2001, 2041–2048 and 2112–2113 | **Reviewed existing safeguards:** off-main/conflated markdown preparation, completed-parse/highlight caches, bounded document blocks, stable lazy keys and remembered measurement modifiers. Width, density, font scale and style still invalidate text layout correctly. These samples do not isolate another safe cache change; profile the exact screen/workload before further optimization. |
| **F14 · P2 — Oversized composable** | 17,086 instructions observed nineteen times; lines 841, 847, 936, 969 and 1030–3282 | **Implemented:** split repository controls and file editing out of the large workspace composition. Existing edit/navigation behavior has regressions. Inspect generated/compiled methods and device traces on the final APK; source extraction alone does not establish a new measured instruction count or frame rate. |
| **F15 · P2 — Export worker/journal latency** | Workspace export about 20.24 s and journal about 24.36 s; lines 2777 and 3072 | **Implemented:** frozen-log copying no longer flushes on every line. Exact UTF-8 byte accounting enforces the same 4 MiB source cap before one durable flush/sync per source. Atomic publication, partial-file cleanup and row-level database fsync/cursor durability remain intact. Existing staged buffering/hex improvements remain. Actual device export durations still need paired measurement; the reported durations alone prove neither a main-thread stall nor database corruption. |
| **F16 · P1 — Multiline diagnostic redaction** | Eighteen blocks reported with visible body content; lines 648–657 and 825–834 | **Implemented:** removed the raw Home chat-list producer; storage sanitizes the chat tag and all logcat fragments, and multiline JSON/unstructured private payloads are sanitized before splitting. Legacy current/previous/critical snapshots, frozen copies and resumed reads are re-sanitized. Privacy canaries and structural diagnostics are tested; no original bodies are committed. |
| **F17 · P2 — Unsent counter disagrees with nested receipts** | Four nested `dispatched=false` records at lines 9/10/20/21 but summary zero at 4418 | **Implemented:** distinct failed invocations are counted as not dispatched only when typed top-level/nested dispatch evidence says false, even if their outcome status is `FAILED`. Transmitted and unknown outcomes remain separate; neither failure prose nor status alone supplies non-dispatch proof. |
| **F18 · P2 — Crash episodes overcounted** | Two episodes versus twelve related root references/process headers; lines 3–5, 26–27, 38, 126–127, 204 and 266–268 | **Implemented:** a bounded pre-index correlates only ComposeInternal/AppCrash/AndroidRuntime emitters with the same exception and first two method frames within one second and compatible process/session/thread identity. Same-emitter and ambiguous records do not merge. Process headers, including orphans, remain context. Occurrences and source records remain retained; historical OS process-exit evidence stays independent without explicit receipt linkage. |
| **F19 · P2 — Structured crash omissions** | Seventy-six frames, sixty retained and sixteen omitted; exception message/suppressed details unavailable; lines 38 and 204 | **Implemented:** bounded cause/suppressed structure, deep application frames, fixed safe message templates and explicit completeness/omission counts. Arbitrary exception prose remains private. Expansion-aware ring budgeting accounts for continuation prefixes so persisted truncation cannot invalidate retained-frame counts. No missing historical message or frame is invented. |
| **F20 · P2 — Checklist export capped at 64 rows** | Reported checklists of 79/80 criteria with only 64 retained, omitting 15/16; lines 1099 and 1801 | **Implemented:** complete hashed pages of at most 64 rows for persistence/capture/log evidence, validated for shared digest, exact count and ordered unique ordinals, with an all-145-row regression. The separate sixteen-row model-prompt presentation remains bounded; completeness of durable evidence does not require an unbounded prompt. |
| **F21 · P2 — First-visible provenance unknown** | About 156.7 s/863.4 s, 304 characters of error display and zero provider bytes; lines 1093, 1791 and 3148–3150 | **Implemented:** explicit model text, local error, progress, app report, retained content and unknown origins survive stream/reducer handling; physical invocation IDs survive terminal error reconstruction. Separate first-publication milestones identify the reducer boundary and do not claim a drawn frame. Unknown or mixed ownership stays unknown. The UI labels app reports and saved content separately; a local diagnostic cannot supply model-text latency or generated-byte evidence. |
| **F22 · P1 — Rate-limit cooldown** | Three reported provider 429 responses, two-second `Retry-After`, and five/ten-second waits; lines 2934, 2949–2968, 3052–3071 and 3129–3153 | **Implemented:** observed pre-acceptance HTTP 429 can supply durable wait evidence for the exact paid-provider profile/model and captured authority. No accepted payload/job, unknown remote outcome, unresolved write or pending approval can acquire retry permission from this metadata; existing checkpoint/continuation guards remain. Terminal GitHub errors preserve the typed limit when applicable. The reported five/ten-second waits already respect a two-second minimum; no immediate retry, model substitution or limit bypass is introduced. |
| **F23 · P2 — ASUS finalizer exception** | Protected-setting finalizer frames at lines 110–121 and 351–362 | **Vendor/device follow-up:** preserve framework origin and unproven application impact. Do not request a protected setting permission, use hidden API bypasses or infer an app-owned leak/crash merely from this warning. |

A separate replay of the **available ZIP** verifies that its nine retained ComposeInternal/AppCrash/AndroidRuntime roots correlate into two episodes, all 140 chat-tagged records sanitize completely, and its four failed invocations with explicit non-dispatch evidence count as four not-dispatched invocations. The nine-to-two replay does not experimentally reproduce the later review's twelve related references; historical OS exit observations remain separate.

The review also mentions allocation/GC activity at lines 759, 845, 4339, 4346 and 4408. Allocation turnover is not leak or OOM proof. Four MCP compatibility sequences that return HTTP 400 then 200 are not automatically outages; seventeen catalogue polls are not independently proven excessive. Settled run-service stops do not establish lost durable gateway jobs. The review reports valid export structure and ninety-three duplicates, without fresh evidence of StrictMode, OOM, ANR, native crash or a profile substitution. These limits must survive the repair narrative; no `verify_live` or other authorization bypass is justified by them.

## Follow-up order and acceptance boundaries

The supplied review's positive evidence remains useful: all 22 local contracts passed; fresh MCP metadata succeeded for targets 6, 8 and 10; the gateway capability exchange succeeded; all 120 outcomes were retained without reported omissions or unknown diagnostic codes. That limited evidence is internally consistent and does not qualify the remaining behavior.

1. Repair the credential for exact `target-11`, obtain fresh discovery, then execute its authorized named fixture.
2. Request bounded fresh checks of exact targets 7 and 9 after reviewing their saved settings and server/bridge conditions. Classify the actual fresh response instead of repeating a historical diagnosis or guessing a 404 fallback.
3. Qualify the intended tool workflows, GitHub task outcomes, exact selected model and persistent gateway job/conversation recovery using named fixtures and retained terminal evidence. Leave intentionally disabled/absent optional features unchanged unless the user chooses them.
4. Run the assigned UI, database, lifecycle and device suites on exact artifacts. Retain observation/configuration/deployment bindings and report remaining exclusions.

The later F01–F23 table distinguishes implemented safeguards from retained behavior and environment qualification. Account reauthorization, host repair, real service/tool execution, exact-device behavior and external harness outcomes remain separate acceptance work. No live credential changes, private server repair, model generation, external repository mutation or device measurement was performed merely to make this report green.

## Validation checkpoint

These focused host runs compile actual production Kotlin and run the named tests. Their explicit Android/Room/service boundaries do not validate complete Hilt/Compose/WorkManager wiring. Counts are listed by suite; overlapping tests are not summed into an application coverage claim.

| Local suite | Result at this checkpoint | Scope |
| --- | --- | --- |
| GitHub acceptance, recovery, catalogue and checklist host suites | 101 passed in the expanded root checkpoint | Includes the earlier 81-test checkpoint, zero-tool operational retries, restored evidence without renewed authority, success on the final authorized call, complete 145-criterion diagnostics and three terminal provider-limit/lineage regressions. The pre-fix runner spent twelve context calls instead of the three required families; malformed remote mismatch fields remain private |
| Required-tool admission and full GitHub visibility | 18 passed | Original-request versus retained-evidence planning and ordinary executable-tool admission |
| Gateway capability/reasoning/admission Kotlin suites | 29 passed | Exact-model failure provenance, typed recovery guidance and existing routing/admission behavior |
| Canonical gateway contract suite at source `194a225a0ab671e6096dd31d476a991a51cf23a9` | 699 Python tests: 698 passed, one LAN IPv4 test skipped; seven browser tests passed | Fifty-seven Python suite files. Includes the earlier 32 targeted route/classification tests; these are not additional live-host observations or validation of later uncommitted changes |
| Canonical authentication contract suite at the same source checkpoint | 25 passed | Local service contracts; no real-account reauthorization or deployed-service qualification |
| Audit services/source/catalogue/export/qualification/runner | 97 passed across nine suites | Fresh/cache separation, receipt identity, prerequisite links, selected-model planning and honest export/privacy behavior |
| Pure audit presentation and model benchmark preparation | 18 passed | Adjacent 26/26 versus 26/120 summary, cache age, exact-target action and revision-bound model preparation |
| Existing speech admission/session recovery | 19 passed | Shared backoff, explicit repair, stale callbacks and partial-draft preservation; not Android speech service execution |
| Actual Android voice adapter with deterministic host Android boundaries | 6 passed | Error-10 classification, one recognition owner, catalogue-only initialization timeout, duplicate initialization, unrelated utterance guards and canceled-start recovery; the boundary is not Android/Robolectric execution |
| Focused output-origin, reducer, cooldown and terminal-delivery host suites | 66 passed | Actual provider assemblers/DTOs, reducer publication core, source lineage helpers, paid-provider limits, cooldown/Retry-After and terminal/API delivery. Three additional GitHub propagation regressions are included in the 101-test root suite |
| Journal lineage, coverage, fingerprints, draw and timeline host suites | 40 passed | Actual journal and output-lineage behavior; host fixtures do not establish a real rendered frame or device latency |
| Invocation output-origin accounting host suite | 3 passed | Explicit local/system output stays separate from provider-generated text accounting |
| Diagnostic privacy, capture/export, crash correlation and completeness host suites | 175 passed | Actual production code with explicit Android service boundaries; includes orphan process-header and persisted crash-size regressions. Separate available-ZIP replay verifies nine emitter roots → two episodes, 140 sanitized chat records and four typed not-dispatched invocations |
| Branch Kotlin formatting | All 105 changed Kotlin files passed Ktlint 1.3.1 | Source/test formatting; the whole working-tree `git diff --check` also passed |

The final canonical fast gate, `bash scripts/validate-local.sh fast`, passed after regeneration at source commit `aab76320aa43d9f829db715baa7fdf009fc8f39a`: 404 script tests, 1,410 literal regex patterns with zero errors, 73 dynamic patterns explicitly outside that static check, and 34 XML resource files. The source inventory now contains 2,364 files across 26 areas; executed-line coverage remains unavailable. This successful final run supersedes the earlier stale-inventory failure during concurrent edits. Fixture log messages in the script suite do not establish that a real device or APK test ran.

The local container lacks the repository's JDK 21/Android SDK 37 toolchain and a cached Gradle 9.8.0 distribution. Full Android compilation, `AppAuditContentTest`, `DiagnosticFrameTimingTest`, `GitHubWorkspaceFilesTest`, the four new Robolectric voice regressions, lint/heap gates, packaging and emulator checks must be established on the final published revision. Missing, skipped, canceled, failed or stale CI is not a pass. Physical-phone, private-service and live workflow acceptance remains unverified unless separately recorded with matching artifacts.

## Delivery status

The branch is `fix/diagnostics-8fb7513c-2026-11-09`. Its published source snapshot is `a8c1729962f5e87f45997086019986fd6d265279`, with Git tree `470a4b5df9ceda25116a881f677be5d98c1e0c43`. This tree exactly matches the tested local source snapshot `aab76320aa43d9f829db715baa7fdf009fc8f39a`. The earlier local commit IDs in the validation record identify the original test checkpoints; publication through the authenticated GitHub connector creates a new commit identity without changing that source tree.

Automatic approval review initially required explicit destination approval for the push. On 2026-11-09, the user approved publishing this branch to `tailscale-signin/GPT_Mobile_AI-improved` and opening its draft pull request. This clears the publication approval requirement. Remote branch and exact-head CI outcomes must be verified from the published pull request; the local validation results above remain scoped to their recorded source checkpoints.

## Related implementation and operating guidance

- [App audit usage and evidence contract](../benchmark/app-audit.md)
- [Qualification worklist](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/audit/AppAuditQualification.kt)
- [Selected-model source](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/audit/AppAuditSelectedModelProbeSource.kt) and [benchmark preparation guard](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/AppAuditModelBenchmarkPreparation.kt)
- [MCP/gateway audit observations](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/audit/AppAuditPluginServices.kt) and [safe remote identity fields](../../app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/audit/AppAuditRemoteIdentity.kt)
- [Device frame/memory and OEM qualification procedure](android-diagnostic-performance-2026-11-08.md)
- [Prior exact-connection repair and discovery contract](app-audit-mcp-remediation-2026-11-07.md)

## Draft PR #815 repair follow-up

The published head `68a0739ed2db14ddb0109abfb941c51c67ec99a7`, against main
`f27fdbbc0ec351e3134ca0285dadf3e97bf28155`, was inspected after the user requested
repairs to the draft branch. PR Validation and Diagnostic audit validation both
reported 8,586 Android unit tests, 12 failures and five skipped cases. The review
APK built, but its ART guard measured the main streaming continuation at 16,696
DEX code units, above the exclusive 16,383 limit.

- Extract continuation snapshot restoration and delivery-policy preparation into
  `streamPrimaryAnswerRecovery`. The setup retains its original operation order,
  coroutine context and exceptions. The existing method-size guard also covers the
  extracted helper and its continuation; its threshold is unchanged.
- Correct provider/repository assertions to require explicit model-origin output,
  and application-origin assertions for deterministic GitHub acknowledgements.
- Finish model-origin wiring in the shared Groq/LLAMA/NVIDIA reasoning decoder and
  the on-device LiteRT/GGUF adapter, including buffered and recovery text. The
  application-generated progress closing tag retains a separate application
  origin. Provider tests exercise these distinctions.
- Preserve explicit admission blockers when an integration task lacks executable
  tools. Tests retain receipt evidence, prove no model is dispatched in that case,
  and separately preserve actual provider-denial and retained-denial metadata.
- Run the frame-timing regression under native Robolectric graphics and verify a
  captured pixel for each valid and rejected label, alongside the size/layout
  assertions. This replaces an assumption that layout completion implies drawing.

Original failing workflow evidence:

- [PR Validation unit job](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42586012165/job/129055900365)
- [Diagnostic audit Android job](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42586012105/job/129055900019)
- [Review APK method-size job](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/42586012244/job/129055900191)

The focused GitHub host suite passes 146 tests using current runner, classification,
verification and review source with explicit Android/storage/service boundaries.
The provider-output/parser host suite passes 72 tests. Canonical fast validation
passes 404 script tests, the regex/resource checks, secret scan, dependency/CI
policy checks and diff check. All 12 changed Kotlin files pass Ktlint 1.3.1.
Exact-head Android CI remains pending publication of this repair. Its results belong
to the resulting commit and must be read from the PR checks. Physical-device and
private-service qualification remain separate from host and emulator results.
