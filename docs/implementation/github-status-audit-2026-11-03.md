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

## Validation and boundaries

Fifteen portable JVM scenarios passed against production pure logic (usage, recognition admission, pagination, typed read planning, fixture ownership, exact integer bounds and progress fingerprints). The full gateway validator and 297 repository script tests passed; changed Kotlin formatting passed. Android compilation passed at `9915c49`; 6,124 tests completed with three failures and four skips. Followups correct the fixture source argument and action-restricted read planner; the stale-state test now distinguishes historical endpoint coverage from current health. Compiler-generated Room schema 48 is included. The final Android/JVM rerun remains required; the local environment lacks JDK 21 and Android SDK 37. Existing graph gateway and repository validation results are documented in the graph implementation ledger and remain separately scoped; the 297 repository script tests were rerun after the audit followups.

Disposable fixture automation covers an unchanged branch and verified cleanup. It does not merge/cancel unrelated work or publish releases; operations needing a content fixture or additional authorization remain explicitly blocked. Device frame-time, microphone binding and native cleanup behavior still require hardware validation. These changes are not evidence of measured device performance or complete integration health.
