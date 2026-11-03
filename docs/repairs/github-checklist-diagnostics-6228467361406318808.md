# Build 138 checklist diagnostics repair

Source: all 3,419 JSONL records in `app-diagnostics-6228467361406318808.jsonl`,
captured from version 0.9.66.4/build 138/commit `3a05c621dd0d5dd1777a3decdd92abb8feb20273`.
There are 2,196 event records, 1,223 continuation records and 15 duplicate events.
The retained capture has 128 nonduplicate tool endings: 117 successful and 11 failed.
Seven run endings contain four PARTIAL, two FAILED and one COMPLETED outcome; the
capture ends during another checklist. These counts describe this capture only.

## Findings and disposition

| Observed family | Source finding and repair / qualification |
| --- | --- |
| Three repository-scope preflight rejections | The planner emitted account-wide `repositories` inside a repository-pinned workspace. Remove that recipe; keep the boundary enforced and report the capability's scope restriction. This preserves the overlapping fix in PR #795. |
| Workflow definition invalid arguments | The first Actions workflow can be deleted or generated and have no file at the selected commit. Read the bounded workflow catalog from the pinned tree, without guessing that a live numeric ID has a definition there. Require an observed SHA. |
| Release status without a tag | Discover tags/releases first and supply the observed tag. A restored legacy invalid request may get a corrected read, but identical rejected arguments and authorization failures are not retried. This preserves and extends PR #795. |
| Job logs 404 / transient read failure | Skip skipped, neutral and cancelled jobs for the log sample. After a 404, permit only one different observed completed job; retain the unavailable result and never infer that all logs exist. Current log reader already bounds redirects, refreshes an expired storage URL once, caps ingestion and avoids forwarding credentials to storage. External retention/access failures remain possible. |
| Two evicted saved-page failures | Consume the bounded local-fragment sample before filling the 32-entry result cache with unrelated capability probes. A failed page remains pending. Follow one exact, schema-valid, currently authorized read-only correction/reopen per lost source. A successful reopen replaces the stale cursor with its new continuations; a second loss remains blocked. No mutation replay or cache-limit increase. |
| Partial tests and ineffective continuation | Persisted malformed read recipes can now be corrected. Successful operations remain reused. Failed pages no longer disappear from pending coverage. Existing unchanged-checkpoint reporting, operation-specific write-fixture requirements and evidence verification stay enforced; blocked/device operations do not become false passes. |
| Large repeated reports / UI stalls | Checklist evaluation, action selection, checkpoint hashing/persistence and final progress digest computation now use the computation dispatcher. A regression requires planning to yield to that dispatcher. Physical-device rendering improvement is not measured. Full evidence remains retained. |
| Local model connection timeouts | The configured port 8080 was unreachable, then a later retry generated output. Existing code preserves the endpoint, honors retryAfterMs and allows one bounded connection recovery. This is not evidence of a wrong address or a model-generation defect; desktop listener/firewall/route verification requires the device and server. |
| MCP timeouts at 8101/8102 | Existing health transitions move through DEGRADED/UNREACHABLE with increasing retry deadlines. Both endpoints later record successful connections and catalogs. Preserve backoff and single-owner connection handling; source changes cannot make an offline desktop reachable. |
| MCP discovery negotiation / cancellation | Legacy fallback is followed by successful catalogs. A cancelled acquisition is recorded as OWNER_CANCELLED_OR_INVALIDATED and a later acquisition succeeds. Do not retry an intentionally cancelled owner or classify that as an endpoint outage. |
| Reviewer formatting failure | Existing saved-review recovery retains the private draft and one bounded format-only retry. PR #795 separately improves exhausted-review presentation and terminal categorization. No bypass of structured verification or publication fences is introduced here. |
| ASUS finalizer SecurityException | The stack is entirely framework code (`ActivityThread`, `BinderInternal.GcWatcher`, finalizer daemon) reading a hidden ASUS setting. Existing diagnostics classify the named vendor issue separately. App code does not access that setting, and changing Android restricted-setting permissions is not an app repair. Vendor firmware remains unqualified. |
| Other platform warnings | Ignored WorkManager foreground hint, cancelled/missing WorkSpec, inaccessible optional chip property, disabled feedback tensors, unavailable optional jank monitor and GC/finalizer notices are retained. The capture does not establish an app failure caused by those messages. Existing classification handles known optional probes; no privileged permissions or model-signature changes are warranted. |
| Process restart | Exit history reports reason code 10; there is no retained native-crash/OOM proof. Preserve historical timestamp and process identity, and do not attribute the restart to the adjacent finalizer warning. |
| Usage estimates and export limits | Missing server usage remains explicitly unavailable/estimated. Continuation records, duplicates, source truncation and captured time remain distinguished; an invocation completion is not task completion. No fabricated measurements are added. |

## Validation

The new production-logic tests cover scope isolation, trusted tag dependencies,
pinned definition discovery, legacy argument correction, completed-job selection,
bounded alternate log sampling, timely local pagination, retained failed coverage,
restart-safe recovery limits, mutation/authorization rejection and sample budgets.
The existing saved-fragment regression now requires the local fragment to run first.
The verified-session regression checks computation-dispatcher handoff.

Host validation uses the canonical `scripts/validate-local.sh fast` checks and ktlint
1.3.1. Focused JVM execution uses Android-independent DTO adapters and exact production
read-mapping/authorization excerpts; it does not qualify Android integration.
Full Android unit, lint, APK and emulator acceptance remain required CI gates for
the final branch revision before merge. Device/server limitations above remain open.
