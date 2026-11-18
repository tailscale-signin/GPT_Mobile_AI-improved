# Post-1534 runtime follow-up — November 18, 2026

Working branch: `fix/post-1534-runtime-continuation-and-acceptance-2026-11-18`; stacked draft PR #1741 is based on PR #1534, not on `main`.

This document records **source improvements**, not installed-device acceptance. The latest audit observed 160 open issues across 20 error families; the Android build 142 synthetic merge-test commit is relevant to the parent PR.

## Implemented
- An issued saved-view `view_cursor` is the only argument of a local continuation. Full, selected-action and inspect schemas expose a mutually exclusive cursor-only shape, preserving normal action and approval constraints.
- Saved bodies persist their *initial actually issued cursors* as immutable bootstrap proof. If the mutable cursor index is lost, only these exact initial handles can be restored after restart, without a GitHub read or mutation replay. Newer handles remain dependent on the surviving cursor index. A missing body still fails closed and requires fresh authorized source evidence.
- The integration preset now requests prerequisite-first verification for current repository/commit, model capacity, authorized tool catalog, workflow/run inputs and an approved owned fixture before exhaustive operations. Unexecuted capability checks remain `NOT_TESTED`.
- Added regression tests for the six major GitHub read families, saved-view restart recovery and refusal to invent missing legacy cursors.
- Refreshed source-association inventory and generated Kotlin digest for every modified production file. Source association is not executed-line coverage.

## Open issue families (baseline, not reclassified as fixed)

| Family | Open | State |
| --- | ---: | --- |
| INVALID_ARGUMENTS | 44 | Cursor contracts changed; device regression pending |
| CHAT_FAILED | 24 | Retain first typed provider/tool failure; device evidence pending |
| SAVED_VIEW_UNAVAILABLE | 16 | Initial index-loss recovery changed; body-loss eviction remains unresolved |
| GITHUB_EVIDENCE_REQUIRED | 15 | Live exact-SHA test receipt matching pending |
| GITHUB_TASK_INCOMPLETE | 10 | Preflight guidance changed; blocked fixture/workflow readiness still pending |
| GITHUB_PAGINATION_INCOMPLETE | 9 | Existing contiguous traversal needs real provider verification |
| BUDGET_RESTRICTED | 7 | Durable result paging required |
| TOOL_FAILURE | 6 | Precheck lineage verification required |
| GITHUB_RUN_INTERRUPTED | 5 | Durable job checkpoint/resume required |
| PROVIDER_RATE_LIMITED | 5 | Observed provider/model quota scope required |
| CONTEXT_CAPACITY_EXCEEDED | 5 | Observed 4,096 conservative context; don't invent a larger serving limit |
| TOOL_PROTOCOL_INVALID_FRAME | 3 | Parser/recovery tests required |
| TOOL_BUDGET_EXHAUSTED | 3 | Verification and cleanup allowance required |
| RECONCILIATION_REQUIRED | 2 | Unknown writes must never be replayed |
| OUTPUT_CONTINUATION_UNAVAILABLE | 1 | Retain checkpoint; no write replay |
| NO_VISIBLE_ANSWER | 1 | Delivery-layer end-to-end test required |
| SOURCE_UNAVAILABLE | 1 | Verify provider authorization and route |
| GITHUB_VALIDATION_FAILED | 1 | Verify pinned source path |
| CONNECTION_ABORTED | 1 | Preserve partial stream state |
| DISPATCH_POLICY_CHANGED | 1 | Refresh authorization and renewed approval |
| **Total** | **160** | Distinct reports, not necessarily distinct code bugs |

## Release qualification required

- [ ] Exact-head Android JVM, Kotlin lint, emulator and strict inventory CI
- [ ] Installed Android saved-view initial and subsequent-page restart proof
- [ ] Provider context-admission and rate-limit tests on selected remote models
- [ ] Android-to-Windows Gateway MCP readiness, durable job checkpoint/resume
- [ ] Complete permitted capability audit on task-owned disposable fixture, cleanup and matching SHA receipts
- [ ] Individual issue resolution evidence and human review
- [ ] Parent and child merge eligibility, separately verified

Never close an incident solely because diagnostic fields or tests were added. No permission, budget, provider capacity or remote write outcome is inferred.
