# Examine provider recovery and diagnostic repair

Diagnostic input: `app-diagnostics-1260866102005161672.jsonl`, 5,413 records, app revision `82d8a6ecda3ed2b5824b426ce05c30a34fbbf5fc`. Implemented against main `a7ed6fe88`.

## Observed failures

- Seven examination attempts reached APPRAISING then failed with only `IllegalStateException` exported. The original log cannot establish which validation failed because the cause was discarded.
- Six automatic admissions reported no examination profile. A blank conversation crawler UID masked the global selection.
- Correlated DNS failures affected OpenRouter and NVIDIA requests. A website or model provider outage cannot guarantee a successful assessment.
- Research delivery validators rejected unsupported citations/numbers and fell back to partial evidence. These checks must remain active.
- StrictMode traced main-thread file access to `ResearchEngineHealthPanel` constructing its telemetry store during composition.

## Changes

Examination accepts a single JSON fence and resolves an incorrect quotation offset only when the unchanged quote occurs uniquely within the inspected chunk. Every existing schema, identity, exact quotation, numerical grounding and scoring gate still runs. Invalid model artifacts get one repair attempt with validation feedback. Cancellation and transport failure retain their own semantics; all physical invocation IDs are checkpointed, including conclusion-review requests. Each validated outcome review is checkpointed before advancing to the next outcome.

Blank crawler settings inherit the next configured scope. Explicit selections remain authoritative. Backend admission, cards and article details share this resolver. Disabled profiles cannot be admitted.

If the selected discovery service no longer supports an archived identifier, metadata resolution can fall back to the work's public canonical URL. An unavailable academic reader can use the authorized native document reader. Readable abstracts/partial text are retained if that attempt fails. Domain restrictions, permission revocation and gateway ownership remain enforced. Reader selection participates in the admission policy hash: changing it pauses the accepted attempt; explicit Examine / Resume authorizes the new configuration.

The engine-health store initializes on the I/O dispatcher and presents a retry state on initialization failure.

## Logging mode

Enable existing app logging and reproduce Examine. Filter the export for `ResearchExamine`:

- `ADMISSION_BLOCKED`: work/chat identifiers and the actionable configuration blocker.
- `METADATA_FALLBACK`, `READER_PROVIDER_CHANGED`, `DOCUMENT_READER_*`, `SOURCE_READ`: selected route, provider receipts, retrieval status and fallback outcome.
- `MODEL_ATTEMPT`: job/lease, phase, profile/model, attempt, chunk progress, physical invocation IDs, duration, output size/hash, truncation and provider failure.
- `OUTPUT_INVALID`: validation cause and application code location, correlated to the same attempt.
- Terminal `STAGE_COMMITTED`: prior phase, retained progress, invocation IDs, bounded redacted exception/cause chain and code locations.

Generated artifacts are represented by size/hash, not raw response bodies. Existing opt-in logging and private-session suppression remain active. Terminal reasons are also retained in article details with saved evidence available for retry. Unavailable or invalid evidence never becomes a successful or favorably scored assessment merely to avoid an error.

## Regression coverage

Focused unit and Room/Robolectric tests cover blank-setting precedence, fenced JSON, unique versus ambiguous Unicode quote locations, bounded repair, cancellation/transport propagation, saved-evidence resume, provider-switch recovery through a committed limited assessment, reader fallback, excluded redirects and permission restrictions. Device/provider acceptance still requires a fresh diagnostic run with the user's configured services.

Validation completed: Android debug Kotlin compilation passed; 67 focused JVM/Robolectric tests passed with zero failures, errors or skips; ktlint passed for all changed Kotlin files; Android resource and regex preflights and `git diff --check` passed. Tests use scripted providers and do not constitute a live-device/provider acceptance run.
