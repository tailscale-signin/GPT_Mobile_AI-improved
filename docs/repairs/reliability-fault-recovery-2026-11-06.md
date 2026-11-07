# Reliability fault recovery — November 6, 2026

Repository: `tailscale-signin/GPT_Mobile_AI-improved`

Base: `main` at `223b7c9f1dbf1ecaa77b65936ca112817cfb6a9b` (merged PR #806).

Working branch: `fix/reliability-fault-recovery-2026-11-06`.

## Evidence boundary

The request supplies twelve reliability priorities and five release requirements, alongside a previous response saying its named diagnostic ZIP was inaccessible. That ZIP was not recovered from Library search in this implementation session. These are requirements and source-audit findings, not newly reconstructed log failures. The older supplied build-139 diagnostic analysis remains historical evidence; none of its counts are reclassified as failures of this new revision.

PR #806 already supplies selected-profile binding, accepted-task receipts, durable mutation reconciliation, gateway readiness, bounded storage, export sharing, the visual Status repository map and concrete IO/rendering repairs. This branch preserves that implementation and its release identity. It does not reopen #806, change version 0.9.66.6/build 140, publish a release, or replace its dedicated publication task.

## Confirmed source gaps repaired

### 1. Safe-read recovery must honor server and security boundaries

`gateway/v14/batch.py` previously truncated Retry-After to 120 seconds, so a longer server cooldown became permission to retry early. HTTP-date cooldowns were ignored; nonfinite values were not rejected. Requests SSL errors inherited the connection-error retry path.

Safe-read recovery now treats the server cooldown as a lower bound, supports HTTP dates and refuses malformed, nonfinite or over-budget cooldowns instead of guessing a shorter wait. Direct and wrapped TLS failures, authorization and deterministic validation failures do not receive automatic replay. The existing three-attempt batch ceiling is unchanged. Excessive cooldowns require explicit recovery rather than blocking a worker indefinitely.

The durable engineering supervisor previously used retry classification as a boolean and scheduled its own shorter delay. It now persists the maximum of the admitted server delay and bounded supervisor backoff. TLS and other non-retryable failures pause with retained evidence instead of repeatedly executing the same stage. Mutation reconciliation remains ahead of every retry decision; none of these changes authorize repeating an uncertain write.

### 2. Cancellation must retain reads that actually finish

The batch coordinator could exit on cancellation or a progress callback exception while its executor waited for physical reads to finish. Those late completed reads were not checkpointed. Recovery could therefore repeat reads that had already returned.

The coordinator now stops new admission/retries, drains already-running reads within their existing transport deadlines and persists their observed results before releasing its batch slot. Work that never dispatched stays resumable. Admission remains occupied until actual workers exit, preventing cancellation from defeating backpressure. Failed executor construction releases the slot. Existing owner/job/plan/schema fences and read-only batch rules remain unchanged.

### 3. Catalog readiness must describe a valid current contract

A tools/list error envelope or missing tools field could be accepted as an empty valid catalog. Full schema validation happened after catalog readiness was published. Execution-success observations also survived tool-schema changes under an unchanged connection configuration.

Catalog admission now checks response shape, cursor type/size, bounded tool names, input-schema objects and supported schema assertions before publishing readiness. Definitive malformed catalogs and authorization failures remain held until explicit refresh or configuration change. An invalid replacement cannot keep advertising the previous executable catalog. Unrelated numeric text is not treated as HTTP authorization evidence.

Actual tools/call observations capture the selected tool's schema/configuration contract before physical I/O. A late response from an older contract cannot verify a newly changed schema or override a later authorization rejection. Discovery still does not count as executed-tool success. Superseded configuration producers receive cancellation, retain physical ownership until exit and cannot publish into the new configuration.

### 4. Event replay must detect more than a missing prefix

`gateway/v14/events.py` checked the earliest retained sequence only. An interior gap, missing tail or fully pruned history could be treated as continuous replay.

Replay now compares the retained sequence set against the durable job boundary, validates exact integer sequence/cursor types and detects duplicates, future cursors and inconsistent metadata. A generation-bound recovery snapshot includes bounded missing ranges and explicit consistency/truncation fields. It instructs the client to reconcile the saved job result; it does not invent missing events. The existing production SSE snapshot hook consumes this function.

### 5. Compaction must enforce aggregate work and output bounds

The prior node budget still traversed every key in a wide object and emitted one omission per field. Its string cap was per item, allowing aggregate evidence to remain much larger than the selected context.

Compaction now bounds visited nodes, container items, key bytes, aggregate text and omission records. Unvisited siblings are represented by a bounded parent-container omission, not thousands of placeholders. Progressive context fitting removes all previous evidence fields before installing a smaller view, so omitted top-level fields cannot accidentally retain their original large bodies. The original durable evidence, selected model, system instructions, objective, requested output allowance and verification/final-response reserves are preserved. Estimated tokens remain estimates, not fabricated measurements.

### 6. Error reporting must identify the next safe action

The Android failure summary and capability checklist offered generic guidance for AUTH_REQUIRED and repository-graph outages. The checklist could suggest mutation reconciliation even for a rejected authentication read.

The shared failure policy now gives deterministic guidance for authentication, graph/capability availability, exact-ref recovery, rate deadlines, circuit probes and genuinely uncertain mutations. The report reuses that policy; the checklist treats authentication and graph availability separately. These text changes do not grant permissions, enable a fallback AI profile or claim a service has recovered.

## Requirement coverage

| Requested area | Existing foundation retained | This branch / validation |
|---|---|---|
| Selected AI profile | Execution binding, outbound model identity and configuration fences | Fault tests preserve the chosen model and output contract; existing real-HTTP selected-execution suite retained |
| GitHub task completion | Run-bound acceptance, operation readback and protected receipts | No completion-gate relaxation; existing engineering/evidence suites retained |
| Gateway connectivity | Readiness, single retry ownership, request deadlines | Correct cooldown/TLS admission and durable scheduling; injected transport failures |
| Background execution | SQLite checkpoints, ownership leases and unknown-write reconciliation | Checkpoint late batch results; gap-aware replay; restart/due-time regressions |
| Context overflow | Selected-runtime limits and reserved final/verification capacity | Aggregate/node-bounded projection and progressive fitting regressions |
| MCP integration | Exact routing, authentication and authoritative schema execution | Validated catalog publication, contract-bound physical observations and superseded-discovery cancellation |
| GitHub API errors | Typed auth/validation/limit/transient policy | Safe retry classification and actionable Android recovery hints |
| CI reliability | Bounded repair supervisor, exact-head checks and explicit merge approval | Existing backend/engineering acceptance tests retained; no automatic approval or gate bypass added |
| Diagnostic logging | Correlated attempt receipts, bounded journals/exports and redaction | Explicit replay gaps/consistency; no reconstruction of uncaptured history |
| Android UI performance | PR #806 IO boundaries, stable rendering and benchmark fixtures | Preserved; physical frame/StrictMode targets remain NOT_MEASURED |
| Gateway batching | Reviewed read-only DAG, ownership-bound journal and bounded concurrency | Cancellation drain, receipt retention, admission cleanup and bounded backoff |
| Error reporting | Failure policy and verified report/checklist rendering | Auth/graph/rate/ref/security recovery guidance and Android regressions |

## Five release requirements

1. **Regression coverage:** new behavior tests exercise each confirmed source gap. Running the initial 24-case fault suite against unchanged main reproduced failures in the changed behaviors; subtest failures are not independent bug counts.
2. **Fault injection:** controlled TLS/HTTP/cancellation/catalog/context/replay faults exercise production functions and the physical MCP dispatch facade. Existing real-HTTP, restart, selected-model and mutation-reconciliation fixtures are retained.
3. **Persistent recovery:** read results are checkpointed, durable retry deadlines survive restart and unknown writes remain reconciled before replay. Storage ceilings and temporary-conversation privacy are unchanged.
4. **Release gates:** canonical Required verification and the existing Android, API 34/36, security, gateway Windows/Linux, installer, native and contract workflows remain unchanged. The new gateway test file is automatically discovered by both gateway stability and PR validation. Missing, failed, cancelled or pending required checks are not success.
5. **Post-release verification and rollback:** existing exact-commit publication and downloaded-artifact verification remain authoritative. Actual device/server smoke evidence is still required. Rollback means a reviewed forward repair/release, not rewriting published tags or replaying uncertain mutations. This branch does not publish or fabricate post-release evidence.

## Validation record and remaining qualification

Executed locally before submission:

- New fault suite: 26 passing cases, including production MCP facade wiring.
- Durable engineering suite: 19 passing cases, including the two new cooldown/TLS cases.
- Combined readiness suites: 17 passing cases, including strengthened schema-readiness evidence.
- Unchanged Gradle-retry suite: seven cases pass in isolation.
- The canonical host suite reached 329 cases but the inherited half-second subprocess fixture failed to create its marker before timeout in the full local run; it also failed on the untouched source snapshot. No deadline, assertion or production retry setting was relaxed.
- The full gateway run is recorded separately from focused results. This container lacks the five pinned tree-sitter parser packages; graph parser tests cannot qualify without them. Local package installation could not retrieve those versions. The repository CI installs the unchanged hashed requirements.
- Android compilation, the three added Kotlin reporting tests and the complete exact-head CI must execute in repository CI. Source preparation or passing Python tests are not substitutes.

Physical-device jank, app-caused StrictMode counts, local NPU/GPU behavior, private port-8090/MCP availability, live authorized app operations and a physical application relaunch remain NOT_MEASURED. An unavailable attachment is not proof of an Android export defect. No guarantee of permanent failure-free operation is made.


## Combined draft integration — 2026-11-06 (Toronto)

The integration candidate combines PR #808 at `d8dd1fdcf56da159669308917ba300d6d267199f` and PR #809 at `1feca10547e2ea8d1f2cebc31299bd4b977a51de`. Their common base is `c313a9897afe6e0a7cff609cdcf33d83fad83a90`. Both parents must remain in the source merge history. This section records the later integration validation separately from the original branch results above.

The batch executor retains #808's bounded cooldown/TLS policy, admission cleanup and draining after a coordinator exception. It also retains #809's immediate halt on worker interruption and cancellation-aware backoff. An interrupted read produces no durable failed receipt. Successful siblings remain checkpointed, and explicit resume dispatches only missing work. A combined behavior regression forces a coordinator failure while one worker is interrupted and another succeeds; it verifies sibling receipt retention, resumability, dependency completion and release of both admission permits.

Evidence projection retains #808's aggregate text and property-name limits, 64-item container limits and decreasing node budgets. It combines these with #809's once-computed source digest, explicit markers for root fields omitted from the view, reported node limit and capped omission-overflow ledger. Two #809 fixtures now use nested lists so node exhaustion and omission overflow are exercised independently of the stricter key/item admission rules. Their original behavioral assertions remain in force, and source inputs remain unchanged.

Android failure guidance combines the typed permission/configuration classifications with the existing auth, graph, exact-ref, rate and unknown-outcome recovery actions. Duplicate branches introduced by the automatic merge were removed. MCP catalog/readiness changes and Android interrupted-session read recovery remain present. The release-scope arrays are the deduplicated union of both parents; required workflows and scalar policy fields are unchanged. The Windows diagnostic stress watchdog remains 600 seconds, with ordinary suite deadlines unchanged.

Integration validation:

- Fault suite: **27 passed**, including the combined coordinator/worker interruption regression.
- Batch suite: **11 passed**; selected execution suite: **17 passed**.
- `bash scripts/validate-local.sh fast`: **passed**, including all **329** host regression cases, policy, dependency catalog, secret scan, regex and resource preflight checks.
- Gateway validation covered **44 Python suites / 528 cases**, with **one environment skip** because no bindable LAN IPv4 interface is available. The initial aggregate invocation returned failure because four startup suites saw the stale generated manifest after the new regression edits. After manifest regeneration, all four affected suites were rerun through the same isolated runner and passed. Every other suite passed, including the code-graph parser suites. The final combined results contain no failed suite.
- Durable stress: **50,000 events / 782 commits**, restart and eviction verification passed. Browser bridge: **7 passed**.
- ktlint **1.3.1** passed on both Kotlin conflict-resolution files. Full Android compilation and emulator qualification remain exact-head CI requirements.
- All **158** gateway payload hashes match the merged source. Whitespace checks pass.

The reviewed source tree was reproduced exactly on GitHub as `316b9095b356741600b8583172bd4af1e1ff7f3b`, and source merge commit `5237ec4cf730a723e371e6af4f78fbc836a99228` retains both PR parents. This metadata revision binds `manifest-v14.json` to that published source commit. The PR branch is advanced once to the complete candidate before fresh CI. Local checks do not replace the final current-head validation gates; the combined merge must preserve both parent histories and recheck the current main revision.


## Gateway response attribution follow-up — 2026-11-06 (Toronto)

The user's added report identified ordinary remote answers receiving the gateway theme colour while actual gateway answers were not consistently marked. The client used generic provider request IDs as gateway evidence, accepted body-provided gateway metadata, omitted the equivalent metadata path for the Responses API, and could retain a prior round's source when later headers supplied no gateway evidence.

Gateway provenance now comes from locally observed `X-Gateway-*` response headers or a saved result bound to the requested job and selected model. The proof is transient in provider DTOs; remote JSON cannot supply it. Each successful physical response publishes either verified gateway metadata or an explicit unknown observation before answer text. The repository persists that source per response, and the buffer flushes earlier text before changing attribution. Only verified metadata can bind a durable gateway job. Metadata-only Responses events do not count as partial model output.

The theme foreground and Gateway badge require both gateway transport and the persisted proof flag. Old records default to unverified, so historical false positives become neutral. Historical genuine gateway records without proof also remain neutral: the old data cannot reliably distinguish them. The renderer continues to preserve per-fragment source and contrast-aware theme colour.

A recognized gateway must confirm its capabilities even when diagnostics and optional gateway request headers are off. Identity learned during a rejecting capability probe also prevents a silent downgrade to a direct connection. The selected profile, endpoint, credentials and model remain bound to the request. An independently selected engineering gateway remains a separate service; it does not reroute unrelated inference.

Regression coverage includes ordinary provider request headers, body spoofing, JSON and SSE completions, Responses metadata ordering, matched and mismatched durable result recovery, per-response source clearing, legacy serialization and mixed-history Compose colour/badge rendering. Focused host checks cover actual transport/core implementations where available; full Android, Compose, lint and emulator results must come from the fresh final-head CI run. The private gateway deployment and physical device have not been accessed or qualified by these workspace tests.

Local follow-up validation passed **40 core Kotlin cases**, covering capability admission, response proof, serialization, saved-result binding and the exact Responses assembler. The separate UI source checks passed **7 cases** (five serializer cases overlap with the core set), and **2** actual flow-reducer regressions passed for gateway-to-unknown transitions within an answer and across GitHub rounds. Baseline substitutions reproduced four admission failures and the legacy appearance failure. Canonical fast validation again passed **329** cases. The hidden prior-round notice no longer keeps the Gateway badge on an unverified answer; the mixed-history Compose fixture covers that case.

The full local HTTP harness was stopped when the complete application authorization/dispatch dependency closure exceeded the focused host boundary. Request preparation and recovery were not replaced with synthetic passing implementations. The actual HTTP transport, adapter and Compose tests are committed for full Android CI. The combined release scope's inherited archive limitation was also added verbatim to the release notes so the existing disclosure gate passes.


### Full Android CI follow-up

Candidate `2014859ade0cace8b1b76d999908744db95ef2f2` completed 7,225 Android unit cases: 7,215 passed, five failed and five existing constrained-heap cases were deferred. The failed suite prevented its later heap/APK steps and blocked merge. The failures identified three merged GitHub recovery-guidance regressions, an empty SSE source observation emitted after a terminal free-provider error, and a Responses retry fixture that still required a single event.

The recovery guidance now preserves both parents' same-account/credential-expiry and rate-limit nonrevocation instructions with dispatch-aware classification. Empty SSE route observations now precede terminal/error publication and retry decisions, including malformed-only streams. The Responses fixture explicitly requires the new unknown observation before its sole completion while retaining its exact retry count, removed sampling field and preserved output allowance assertions. These changes require a fresh full CI run; earlier successful gateway, emulator, formatting and CodeQL results are not reused as final-head qualification.

The focused production GitHub harness reproduced exactly the three original CI failures and passed all **38** cases after the two recovery-message edits, with original assertions unchanged. Independent review confirmed that the stream repair preserves retry and unknown-write guards, keeps all existing transport assertions and strengthens malformed-empty coverage. Full Android runtime qualification remains pending the corrected revision's CI.

### Emulator preparation follow-up

Candidate `6c1a001d0998253979f5bb163c50c76070e6021f` passed both full Android unit suites, constrained-heap verification, lint, APK/runtime verification, CodeQL and all gateway/installer/contracts workflows. The canonical suite reported **7,225 cases: 7,220 passed, five existing constrained-heap deferrals and zero failures**. API 34 acceptance passed. API 36 remained a merge blocker:

- Job `128291485870` lost its first instrumentation process to a startup ANR before any test packet. Android reported heavy CPU contention; the exact blocked application call was not retained.
- The single diagnostic rerun, job `128297624559`, executed and passed that editor case. Two glass tests then correctly rejected captures covered by Android's **System UI isn't responding** dialog. Artifacts `13560121850` and `13560723138` retain the two attempts; their digests were verified before inspection.

The setup had declared readiness after selecting and launching a lightweight HOME, then changed display size, density and font scale immediately before instrumentation. Boot broadcasts were still completing after instrumentation started. Force-stopping the stock launcher also caused System UI to recreate its QuickStep/taskbar service: the configured recents provider is independent of the selected HOME. This relationship is explicit in the [Android System UI launcher proxy](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/packages/SystemUI/src/com/android/systemui/LauncherProxyService.java). The original System UI ANR stack was cleared by setup, so the complete causal chain is not claimed as proven.

The follow-up keeps the lightweight HOME while preserving the recents provider, waits for bounded broadcast/application barriers and verifies the configured display, HOME focus and absence of process errors before instrumentation. Platform success markers are checked because the application barrier can report that it gave up even when its shell exit status is zero. Boot and setup diagnostics are retained on preparation failure. API 36 receives four guest cores, within the [documented four-vCPU allocation for public Ubuntu runners](https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job); its emulator already selected 4096 MB RAM. API 34 retains two guest cores.

Every named test, foreground-window assertion, pixel threshold, instrumentation watchdog and strict protocol check remains in force. Preparation does not dismiss error windows, suppress ANRs, restart failed instrumentation or treat an incomplete barrier as success. All applicable workflows must qualify the new exact head before merge; the successful checks on `6c1a001` are historical evidence for the application fixes, not qualification of the setup follow-up.

Local validation passed **34** focused setup/runner tests and **346** canonical host checks on the combined repair. Behavior coverage includes real shell-runner execution behind explicit ADB/Python boundaries, failed or truncated Android dumps despite exit zero, shared deadlines, configuration/focus/ANR rejection, preservation of original failures and mandatory normal-path logcat capture. A separate comparison confirmed all **20** instrumentation invocations and watchdogs, the complete strict validation block and the foreground/pixel test source are unchanged. API 34/36 execution remains pending the newly published revision.

### API 34 boot resource qualification

Candidate `a8ed1e63a2d1338e0411250ec250a55d3058b343` completed API 36 acceptance successfully in job `128307859557`: all **55 tests** across **20 instrumentation invocations** executed, all readiness phases reported success, and the retained last-ANR report stated that no ANR had occurred since boot. Independent artifact validation passed every named protocol, image, archive and measurement check. All four glass cases passed, including the two previously obstructed by System UI.

API 34 job `128307859313` was correctly blocked before instrumentation. The pinned emulator action sent its unlock key at `05:19:49.599 UTC`; Android recorded a NexusLauncher input-dispatch ANR at `05:19:54`, before the acceptance script started at `05:19:56.465`. The new setup diagnostics retain the actual failure instead of erasing it. Its two-core guest had selected **2560 MB RAM** automatically and reported **100% CPU**, CPU pressure `some avg10=71.24`, memory pressure `some avg10=6.31`, and `kswapd0` reaching **28% CPU** during the incident.

Both targets now explicitly use **four guest cores and 4096 MB RAM** through the pinned action's documented `cores: 4` and `ram-size: 4096M` inputs. This matches the successful API 36 allocation and provides headroom for the observed API 34 CPU and paging load before the action sends its unlock event. The two existing API/profile combinations remain the same, and removing the resource value from the matrix restores their original check names. The action, readiness helper, app/test source, ANR reporting, assertions and time limits are unchanged. This resource follow-up still requires fresh API 34/API 36 and all other applicable checks on its own published head.

The resource-only follow-up passed **346 canonical host checks** and independent review of the exact workflow inputs. Its new device/Android and other applicable CI receipts remain required before merge.
