# App audit in the Performance lab

App audit runs repeatable application checks, inventories installed/configured integrations, preserves outcomes in benchmark history, and exports the selected audit for debugging. It works without an AI profile. Existing model capability, performance, delegation and CPU benchmarks remain available for their respective measurements.

## Run and inspect an audit

1. Open the benchmark **Performance lab**, then **App audit**.
2. Run the default local audit. Its fixtures use disposable inputs and fresh in-memory objects; they do not open the user's memory vault or mutate external services.
3. Optionally enable network reads. This permits bounded MCP discovery for enabled configured connections. Select an exact saved gateway profile if gateway capability metadata is also wanted. No gateway profile or replacement endpoint is selected automatically.
4. Inspect the score together with exercised/planned checks and verified passes/planned checks. Open category and plugin results for individual outcomes, evidence kind, dispatch state, duration, typed failure reason, HTTP status when available, and measured counts.
5. Choose **Export audit evidence** to save JSON. A running, stopped or interrupted audit can be exported; its partial status remains in the file. If saving fails, the completed export artifact can be saved again without executing the audit again.

Each configured integration has an immutable run-local `target-N` alias. The UI may also show its **Current saved name**, resolved from its exact current local identifier. Renaming or removing a connection does not change the historical alias or evidence. Current names, connection identifiers, addresses and credentials are not included in the audit export.

## What is measured

There are 22 local contract probes. They execute production code with fixed inputs across arithmetic/date/file tools, selected-profile routing, usage accounting, outcome receipts, credential redaction, research identity, gateway feature decoding, GitHub reference admission, audit serialization, execution budgets and authority, permission identities, repeated agent execution, gateway attribution and color contrast, research transfer, memory privacy/validity and isolated graph recall.

Plugin configuration inventory, live capability discovery and actual tool execution are separate evidence classes. A successful MCP discovery does not establish that its listed tools execute successfully. A successful gateway capability response does not establish model inference, GPU/NPU execution, completed gateway jobs or remote source identity. Configuration and unsupported execution checks remain visible even when the connection is disabled, missing or not selected.

The source inventory records all supported production roots, file hashes, text-line counts and 26 source areas. The generator rejects unknown source modules, unsafe paths and stale output. Runtime fixture source is included; generated inventory outputs are excluded to prevent a recursive digest. The inventory covers Android application sources/resources, native-runtime and portable-domain sources, gateway software including its Windows installer/firewall/launch scripts, authentication services and external MCP integrations. Tests, build/dependency internals, binary assets and tooling outside those roots are not counted as production source.

**An indexed file or associated source area is not proof that its lines or branches executed.** Source-line coverage is explicitly unavailable in this audit. Uncovered source areas receive external-harness entries. Full Kotlin/JVM coverage, device rendering and accessibility, migrations/process death, native acceleration, private service integration, destructive workflows and prolonged live soak require their corresponding external suites and environments. These are not automatically converted into passing results.

## Score and outcomes

The versioned measured-check score is:

`100 × passing eligible weight / measured eligible weight`

Only attempted PASS/FAIL results whose observed evidence matches the accepted check and comes from a fixture or live observation enter that calculation. Configuration-only, cached and external-suite records do not earn points. With no measured assertions the score is unavailable, rather than zero or 100. It is a check pass score, not a model intelligence, hardware-performance or whole-application coverage score.

Every planned entry retains one of PASS, FAIL, SKIPPED, BLOCKED, UNAVAILABLE, UNTESTED or CANCELED, or remains explicitly pending while a run is active. A score of 100 with 22 of 80 checks exercised means those 22 eligible checks passed; it does not verify the other 58. The UI and export keep both the score inputs and the coverage denominators.

## Durable execution and bounds

The feature uses the existing `BenchmarkExecutionCoordinator`, foreground-service ownership, `BenchmarkBatchRunner`, Room-backed `BenchmarkStore` and checkpoint journal. `APP_AUDIT` batches contain an additive audit payload and no synthetic model run/profile. Existing batch formats remain readable. Audit outcomes are excluded from model-score populations.

The accepted plan freezes its build/source inventory, configuration fingerprints, fixture identities, selection, weights and limits. Each attempt is checkpointed before execution; a network boundary is checkpointed before the existing client is invoked. Completed receipts are retained even when configuration changes afterward, and remaining checks stop under changed authority. Process recovery never replays an already-started probe with an uncertain outcome. It can resume remaining checks only within the original plan, source and budget. A recovered duration that was not measured is `null`.

An unchanged configuration remains comparable after a process restart. Credential-change detection uses opaque revisions of bounded encrypted vault records, without hashing plaintext credentials or exporting record revisions. Changed or unavailable credential identity blocks the affected live probe. If preparation or Android background tracking exceeds its existing startup deadline, a prepared batch is marked interrupted; its remaining checks can resume within the original run limits. Failure before a manifest is saved shows a distinct error without claiming retained results. Explicit stop, storage failure and already completed work keep their recorded state.

Default limits are 128 executed probes, 16 network probes, 15 seconds per probe and 180 seconds per run. They bound logical probes; existing clients retain their own physical-request limits. The plan has a 4,096-entry bound; plugin inventory retains up to 512 targets with an explicit omitted-target count beyond that bound. Deadline/budget exclusions are recorded explicitly. Stop, process interruption and storage failure preserve their different states.

## Export contract and privacy

The JSON format is `gptmobile.app-audit.v1`. It contains the selected run's state, accepted check plan, joined result receipts, safe build/source identity, score inputs, coverage counts and typed diagnostics. Fixed allowlists govern diagnostic codes, stages, labels and components. No model answers, chat history, tool argument/response bodies, raw exceptions, private map keys or unrelated application logs are copied.

The full file inventory is attached only when its canonical digest and per-area totals match the historical run. After an app update, an older audit retains its frozen inventory summary/digest; the exporter reports that the matching full inventory is unavailable instead of attaching a different build's files.

Exports are bounded to 4,096 retained checks, 10,000 source files, 2 MiB of inventory input and 8 MiB output. Omitted records and metrics are counted; truncation removes the numerical score rather than claiming complete exported evidence. Write/cancellation failures do not claim successful delivery. Existing verified artifact retention and Android document-save handling are reused.

## Engineering verification

Regenerate the source inventory after production changes with `python scripts/generate_app_audit_inventory.py`; `--check` verifies drift. Canonical fast validation includes the inventory tests. The architecture index includes benchmark and audit entry points.

Regression coverage includes frozen plan and budget integrity, failures before/after dispatch, unknown outcomes, cancellation, source/configuration drift, Room/coordinator recovery, model-score isolation, actual production fixture behavior, actual gateway route contracts, cache-only discovery, privacy canaries, inventory digest verification, partial exports, failed-save retention, UI result identity and measured-score/coverage presentation. Full Android compilation, unit/heap tests, APK packaging and emulator checks must pass on the exact published revision before merge. Physical-phone and private gateway qualification is not inferred from host or emulator tests.
