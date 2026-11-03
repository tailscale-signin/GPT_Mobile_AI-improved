# GitHub Status export audit implementation

The retained export review described 30 issues at main `e76ff28bcb220965c145f6ab8461e15a75a6f964`. The archive itself was not supplied. The implementation and evidence for each item are recorded in `docs/repairs/github-status-audit-2026-11-03-task.json`; changes retain all 22 graph features in PR 791.

## Execution and evidence

The durable checklist selects dependency-aware reads from typed observed resources, reuses receipts across catalog changes, records concrete blockers and tracks disposable branch fixture ownership/cleanup. Coverage reports are deterministic. Retried unchanged checkpoints produce a short blocker result without another model draft. Historical failures remain visible, including recovered and preflight-rejected attempts. Current authorization and action schemas can exclude a receipt from proof without deleting it. Identical repeated observations do not advance the progress fingerprint.

Status acceptance shares the same requirements and terminal evaluation. Claims retain operation-bound resource/field types and reject semantic mismatches; PR state is labelled as PR state, with resource branch and SHA separate from selected workspace. Tools-only policy invalidation can rebuild a durable checklist against current permissions; destination/account changes remain blocked. Recovery instructions refer only to advertised readers, otherwise supplying inline context. Unknown canonical names are cached per catalog version.

## Contracts and traversal

Action schemas expose required branch safety leases and release pagination. Workflow IDs support positive 64-bit values. Issue reads use observed issue identities. Pagination compares upstream IDs before filtering and returns one terminal state across payload, display and receipt. Active job logs are pending; completed jobs are preferred. Expired external log redirects refresh once through GitHub, without forwarding credentials to storage. Workflow definitions use discovered IDs and pinned source revisions. Incomplete search remains incomplete, with exact outstanding continuation recipes retained.

## Accounting, UI and recovery

Usage counters retain immutable bounded observations in separate provenance lanes. Room migration 48 adds invocation-keyed evidence; exports distinguish reservations, estimates, interim usage, final usage and unknown cost. Existing background parsing and rendering caches remain in use; long tables initially compose 12 rows and expand on demand. Live self-test activity shows evaluated completion/failure/blocker counts and keeps earlier activity collapsed until requested. Incremental checkpoint storage is retained, identical queued snapshots no longer rewrite the database, and terminal drain still persists the latest change. Recognition sessions now hold an owner lease until stopped, with existing cooldown and availability checks. No-op embedding release checks no longer emit release-policy records. Redacted catalog snapshots and bounded log-window completeness metadata improve diagnosis.

## Confirmed release failure

The exact main failure was job `125874097879`, run `41614053223`, at `e76ff28…`: `:app:packageRelease` exhausted Java heap while `BytesSource`/`ApkFlinger` read APK entries. R8 had completed. The branch now inherits the merged shared bounded Android pipeline correction from PR 790 / `71b19180e01608fefb93e837db4f205ff01f8a2f`: separate shrink/package/bundle phases with the existing 4 GiB heap, one Gradle worker and two common-pool workers. Release versions and publishing are independent.

A subsequent signed-release validation failure at main `1a3dd3d4` / job `125912185406` exhausted the 3 GiB compiler heap before packaging. The branch also inherits PR 792 / `19eb4e73`, which runs compilation, tests, debug lint and release lint in separate processes at the existing heap limit. The original packaging failure and this validation failure are distinct; no signed release is published by this task.

## Validation and boundaries

Sixteen portable JVM scenarios passed against production pure logic (usage, recognition admission, pagination, typed read planning, fixture ownership, exact integer bounds and progress fingerprints). The full gateway validator and 297 repository script tests passed; changed Kotlin formatting passed.

At `6ef54ecf`, Android compilation and all 1,010 unit-test suites passed: 6,135 tests, 6,131 passes, zero failures/errors and four large-history skips in the ordinary suite. The separate 256 MiB recovery/export stage passed. The uploaded XML reports in run `41651790429` / artifact `13240675441` confirm those counts; its compiler-generated Room schema 48 exactly matches the committed file. Android lint, Windows packaged parser preflight, gateway stability, marketplace/build-download contracts, CodeQL, native-memory smoke and the separate UI review APK workflow also passed on that revision.

API 36 emulator acceptance passed. API 34 failed before executing tests because the SDK system-image download was an invalid ZIP; the failed job was retried. Physical-device frame times and microphone behavior remain unmeasured.

The diagnostic workflow at `6ef54ecf` separately exposed missing MockK JVM classes. Concurrent commit `092bdb0f` selects the JVM artifact at the same version; this followup pins its observed Kotlin reflection POM dependency. Strict verification remains enabled. Earlier missing test-library checksum failures and the recovered stale status fixture are retained in the task ledger. Final exact-head CI is pending after these dependency-only followups; earlier passing receipts are not reassigned to a newer commit. The local environment lacks JDK 21 and Android SDK 37.

Disposable fixture automation covers an unchanged branch and verified cleanup. It does not merge/cancel unrelated work or publish releases; operations needing a content fixture or additional authorization remain explicitly blocked. Device frame-time, microphone binding and native cleanup behavior still require hardware validation. These changes are not evidence of measured device performance or complete integration health.
