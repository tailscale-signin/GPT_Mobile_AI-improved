# App audit in the Performance lab

App audit runs repeatable application checks, inventories installed/configured integrations, preserves outcomes in benchmark history, and exports the selected audit for debugging. It works without an AI profile. Existing model capability, performance, delegation and CPU benchmarks remain available for their respective measurements.

## Run and inspect an audit

1. Open the benchmark **Performance lab**, then **App audit**.
2. Run the default local audit. Its fixtures use disposable inputs and fresh in-memory objects; they do not open the user's memory vault or mutate external services.
3. Optionally enable network reads. This permits bounded MCP discovery for enabled configured connections. **Optional model targets** lists eligible saved local and remote profiles. Selecting a profile records its configuration and an explicit model-generation qualification gap; an eligible gateway profile can also receive a capability metadata check when network reads are enabled. No profile, replacement endpoint or model inference is selected automatically.
4. Inspect the score and its adjacent summary: measured checks passed, planned checks verified, failed, blocked, unavailable and untested counts. Open category and plugin results for individual outcomes, evidence kind, dispatch state, duration, typed failure reason, HTTP status when available, and measured counts.
5. Choose **Export audit evidence** to save JSON. A running, stopped or interrupted audit can be exported; its partial status remains in the file. If saving fails, the completed export artifact can be saved again without executing the audit again.

Each configured integration has an immutable run-local `target-N` alias. The UI may also show its **Current saved name**, resolved from its exact current local identifier. Renaming or removing a connection does not change the historical alias or evidence. Current names, connection identifiers, addresses and credentials are not included in the audit export.

### Correct a connection or prepare a model benchmark

An integration result offers **Open connection settings and checks**, or **Repair credentials and reconnect** for a credential failure. The action opens the exact surviving saved connection. A missing connection has no substitute action, even if another connection has the same display name. Opening the repair screen, refreshing saved settings, or saving through the audit's edit route does not dispatch discovery. **Reconnect and check again** requests a fresh bounded discovery check; **Sign in again** is a separate explicit OAuth action. Disabled and absent optional integrations remain unchanged.

Cached results show their original observation time and age at the audit check before evidence details are expanded. Missing original times remain explicitly unknown. An explicitly selected network-read audit may recheck allowlisted remote discovery contract/configuration failures, including the recorded schema and endpoint-rejection classes. It uses the saved endpoint and existing protocol validation, request limits and revision checks. Credential, TLS and unrelated recovery gates remain enforced; there is no blanket HTTP 404 route fallback. Correct the reported condition, check the connection, then start a fresh audit. Earlier receipts remain historical evidence.

A selected profile's generation gap offers **Prepare model benchmark** while the lab is idle. The app verifies the exact saved profile and the accepted configuration/credential revision, then opens the existing benchmark queue with only that profile selected. Review the suite and explicitly press Run to generate answers. A removed, disabled or changed target cannot silently select another profile. This preparation does not execute inference, promote the old audit result, or establish backend acceleration. See [the November 9 remediation record](../repairs/diagnostics-8fb7513c-2026-11-09.md) for the concrete reported audit and its remaining qualification work.

## What is measured

There are 22 local contract probes. They execute production code with fixed inputs across arithmetic/date/file tools, selected-profile routing, usage accounting, outcome receipts, credential redaction, research identity, gateway feature decoding, GitHub reference admission, audit serialization, execution budgets and authority, permission identities, repeated agent execution, gateway attribution and color contrast, research transfer, memory privacy/validity and isolated graph recall.

Plugin configuration inventory, live capability discovery and actual tool execution are separate evidence classes. A successful MCP discovery does not establish that its listed tools execute successfully. A successful gateway capability response does not establish model inference, GPU/NPU execution, completed gateway jobs or independently verified remote source identity. Safe version, instance and build fields from that response are retained as **declared capability identity**. Configuration and unsupported execution checks remain visible even when the connection is disabled, missing or not selected.

The source inventory records all supported production roots, file hashes, text-line counts and 26 source areas. The generator rejects unknown source modules, unsafe paths and stale output. Runtime fixture source is included; generated inventory outputs are excluded to prevent a recursive digest. The inventory covers Android application sources/resources, native-runtime and portable-domain sources, gateway software including its Windows installer/firewall/launch scripts, authentication services and external MCP integrations. Tests, build/dependency internals, binary assets and tooling outside those roots are not counted as production source.

**An indexed file or associated source area is not proof that its lines or branches executed.** Source-line coverage is explicitly unavailable in this audit. Every source area retains an external-suite qualification entry, including areas already associated with a narrow configuration or local contract probe. Color contrast does not replace a Compose interaction suite, and a JSON round trip does not replace database migrations or interrupted-write recovery. A separate device measurement entry records the rendering/resource measurement gap. Full Kotlin/JVM coverage, device rendering and accessibility, migrations/process death, native acceleration, private service integration, destructive workflows and prolonged live soak require their corresponding external suites and environments. These are not automatically converted into passing results.

## Score and outcomes

The versioned measured-check score is:

`100 × passing eligible weight / measured eligible weight`

Only attempted PASS/FAIL results whose observed evidence matches the accepted check and comes from a fixture or live observation enter that calculation. Configuration-only, cached and external-suite records do not earn points. With no measured assertions the score is unavailable, rather than zero or 100. It is a check pass score, not a model intelligence, hardware-performance or whole-application coverage score.

Every planned entry retains one of PASS, FAIL, SKIPPED, BLOCKED, UNAVAILABLE, UNTESTED or CANCELED, or remains explicitly pending while a run is active. A score of 100 with 22 of 80 checks exercised means those 22 eligible checks passed; it does not verify the other 58. The UI and export keep both the score inputs and the coverage denominators. A locally rejected credential can remain a visible failure while being excluded from the measured score because no live request was dispatched.

The score and planned coverage/count summary form one accessible UI group. For example: **Measured checks 26/26 passed · Planned checks verified 26/120 · 1 failed · 5 blocked · 10 unavailable · 49 untested**. Skipped and canceled counts are appended when present, and detailed recorded-outcome totals remain available. A completed run with failures retains that state.

## Durable execution and bounds

The feature uses the existing `BenchmarkExecutionCoordinator`, foreground-service ownership, `BenchmarkBatchRunner`, Room-backed `BenchmarkStore` and checkpoint journal. `APP_AUDIT` batches contain an additive audit payload and no synthetic model run/profile. Existing batch formats remain readable. Audit outcomes are excluded from model-score populations.

The accepted plan freezes its build/source inventory, configuration fingerprints, fixture identities, selection, weights and limits. Each attempt is checkpointed before execution; a network boundary is checkpointed before the existing client is invoked. Completed receipts are retained even when configuration changes afterward, and remaining checks stop under changed authority. Process recovery never replays an already-started probe with an uncertain outcome. It can resume remaining checks only within the original plan, source and budget. A recovered duration that was not measured is `null`.

An unchanged configuration remains comparable after a process restart. Credential-change detection uses opaque revisions of bounded encrypted vault records, without hashing plaintext credentials or exporting record revisions. Changed or unavailable credential identity blocks the affected live probe. If preparation or Android background tracking exceeds its existing startup deadline, a prepared batch is marked interrupted; its remaining checks can resume within the original run limits. Failure before a manifest is saved shows a distinct error without claiming retained results. Explicit stop, storage failure and already completed work keep their recorded state.

Default limits are 128 executed probes, 16 network probes, 15 seconds per probe and 180 seconds per run. They bound logical probes; existing clients retain their own physical-request limits. The plan has a 4,096-entry bound; plugin inventory retains up to 512 targets with an explicit omitted-target count beyond that bound. Deadline/budget exclusions are recorded explicitly. Stop, process interruption and storage failure preserve their different states.

## Export contract and privacy

The JSON format is `gptmobile.app-audit.v1`. It contains the selected run's state, accepted check plan, joined result receipts, safe build/source identity, score inputs, coverage counts and typed diagnostics. Fixed allowlists govern diagnostic codes, stages, labels and components. No model answers, chat history, tool argument/response bodies, raw exceptions, private map keys or unrelated application logs are copied.

Fresh gateway capability receipts carry their observation timestamp and accepted opaque configuration revision, as MCP discovery receipts do. Available remote version, instance, source commit and build status are separately allowlisted and labelled `DECLARED_CAPABILITIES`; the app's commit does not identify a deployed gateway, authentication broker or integration service. An undisclosed remote build stays unknown. An actual model/device qualification must additionally retain its exact APK and device identity in its authorized harness evidence.

The exported `qualificationPlan` is a reviewable worklist with `authority: PLAN_ONLY`, `grantsExecution: false` and `importsPromoteAuditScore: false`. Each tool/model gap names its configuration and discovery check IDs, separate configuration/credential/discovery readiness, blocking check IDs and the next required action. Configuration passing alone cannot make a target ready when its credential or discovery check failed. A live discovery pass requires its timestamp and matching configuration revision; cached metadata cannot satisfy that prerequisite. Available remote capability declarations accompany the relevant worklist row without becoming execution proof.

Worklist rows bind to the exact accepted build, source inventory, plan and available configuration revision. They identify a named target fixture, selected-profile model benchmark, device measurement or external suite and retain bounded-call/timeout requirements. The default tool smoke allowance is one explicitly named tool call; negative cases and owned cleanup require separate declared bounds. Expected assertions, dispatch/terminal receipts, dated harness artifacts, cancellation/recovery behavior and safe result summaries are required. The worklist neither chooses arbitrary tools nor enables plugins, imports a pass, repeats an uncertain mutation, or replaces a failed historical result.

For live qualification, reconnect each timed-out MCP connection by its saved identity and repair the credential on the connection that rejected it. Record one fresh discovery and one named tool fixture per intended connection. A shared discovery leaves the audit check untested. Enable Crawlers, Deep Research, Google service and optional integrations only when they belong to the release scope. Record an intentional exclusion for the others.

Run GitHub fixtures against an explicitly authorized disposable repository or resource. Keep the branch/file, issue, draft PR, release/tag, workflow and user-decision receipts distinct. Exercise pagination, cancellation, restart and remote cleanup using the same pinned source. Run the prepared model benchmark on the exact saved profile and record output, streaming, cancellation and unavailable-model recovery. Run Moonshine microphone input and Kokoro playback on the phone, then verify saved jobs on the deployed Windows Gateway across app closure, reconnection and a host restart.

Match the installed APK digest and signer to its build artifact before using the physical-device workflow. Capture the device ABI, SDK, actual GGUF/QNN backend, warm and cold inference, frame timing, memory, thermals and cancellation. Use separate artifacts for the outstanding emulator, native, portable-domain, authentication, Gateway and external-integration checks. Formal reliability promotion requires a reviewed receipt for every assertion in the case.

The full file inventory is attached only when its canonical digest and per-area totals match the historical run. After an app update, an older audit retains its frozen inventory summary/digest; the exporter reports that the matching full inventory is unavailable instead of attaching a different build's files.

Exports are bounded to 4,096 retained checks, 10,000 source files, 2 MiB of inventory input and 8 MiB output. Omitted records and metrics are counted; truncation removes the numerical score rather than claiming complete exported evidence. Write/cancellation failures do not claim successful delivery. Existing verified artifact retention and Android document-save handling are reused.

## Engineering verification

Regenerate the source inventory after production changes with `python scripts/generate_app_audit_inventory.py`; `--check` verifies drift. Canonical fast validation includes the inventory tests. The architecture index includes benchmark and audit entry points.

Regression coverage includes frozen plan and budget integrity, failures before/after dispatch, unknown outcomes, cancellation, source/configuration drift, Room/coordinator recovery, model-score isolation, actual production fixture behavior, actual gateway route contracts, cache-only versus explicit fresh discovery, privacy canaries, inventory digest verification, partial exports, failed-save retention, exact UI repair/model preparation, blocked worklist prerequisites and measured-score/coverage presentation. Full Android compilation, unit/heap tests, APK packaging and emulator checks must pass on the exact published revision before merge. Physical-phone and private gateway qualification is not inferred from host or emulator tests.
