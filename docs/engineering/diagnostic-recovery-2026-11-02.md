# Diagnostic repair checkpoint — November 2, 2026

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`
- Base: `main` at `0b637d8f91eec817f3af432d7014a6118b7eca35`
- Branch: `fix/diagnostic-tool-recovery-heap-2026-11-02`
- Intent recorded before editing: repair failures in the attached diagnostics on a new branch.
- Capture: app 0.9.65.0/build 133, app commit `6f9bc13bd21cd8d1ad5d6eaf1dadbc4090193edc`.
- Examined all 7,257 JSONL records. Severity fields: 83 error, 151 warning, 7,023 info. These are records, including stack frames, not distinct failures.

## Repairs

1. PR page-size schema and native implementation now agree, including the continuation page size. Search accepts explicitly enumerated read-only action aliases; unknown actions and changed saved-page selectors remain rejected.
2. Saved-evidence reads use argument-specific repeat accounting. Independent offsets no longer exhaust a single tool-name repeat allowance. Compaction preserves revision/record identity and advances only past the text actually retained, so omitted evidence remains readable. Invalid record IDs return a scoped record index and a valid restart call.
3. Streaming checkpoints coalesce for 500 ms between writes, retaining one in-flight and one newest pending snapshot. Terminal drain still persists the newest snapshot; cancellation prevents stale publication.
4. Debug export falls back to a separately identified, streaming recent-log archive under heap pressure. The manifest and UI explicitly identify omitted conversation, research, profiles and receipts. It does not hydrate database history. Below the fallback's 2 MiB admission threshold, the UI reports memory pressure without logging it as an unexpected export exception. Full exports retain the existing admission and archive verification.
5. Connection Doctor's legacy preference cleanup runs on IO rather than during main-thread construction.
6. Answer-contract rewrites disable requested reasoning/maximization while retaining the original primary reasoning policy. This addresses the captured rewrite spending 24,181 output tokens entirely on reasoning; provider compliance remains subject to its capabilities.
7. Unparsed-tool errors identify the selected provider rather than incorrectly blaming llama.cpp. The captured failures were OpenRouter/DeepSeek. Raw control markup remains withheld and undispatched.
8. CI log HTTP requests participate in dispatch accounting. A sent read returning 404 is recorded as `READ_FAILED`, with its HTTP status, rather than as an unsent preflight failure. Mutation preflight accounting is unchanged.

## External observations and limits

- The ASUS exception originates in `ActivityThread`/`BinderInternal$GcWatcher.finalize` reading the vendor-only `asus_leak_detect_proj` setting. No application stack frame accesses that key. Application code cannot grant itself system access or patch vendor framework code.
- MCP ports 8101/8102 timed out and Jina DNS resolution failed on the device. The capture does not establish whether the services, routing, firewall or DNS were responsible. No live endpoint access from this environment was available. Existing bounded route recovery is retained.
- InputConnection warnings and skipped-frame records need physical-device verification after checkpoint/IO changes. No claim that all rendering or vendor warnings are eliminated.
- Writer-only recovery intentionally does not reauthorize unavailable GitHub tools. Cursor mismatches remain validation errors and unknown mutation outcomes still require reconciliation.

## Validation checkpoint

- `bash scripts/validate-local.sh fast`: passed (287 host tests, resource/icon/provenance checks, regex checks, secret scan and diff check).
- Added behavioral regressions for page schemas/aliases, independent saved-evidence offsets, compaction offsets, invalid-record correction, checkpoint throttling/drain, low-memory archive cleanup, rewrite constraints and CI log dispatch accounting.
- Targeted Android unit tests attempted locally; Gradle distribution download failed because network access to services.gradle.org is unavailable. This host also has JDK 17 rather than the required JDK 21 and no provisioned Android SDK.
- Android compilation, unit tests and device verification remain required; the PR must not be represented as green without their results.
