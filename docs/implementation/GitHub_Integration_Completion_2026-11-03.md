# GitHub integration completion and diagnostic repair

Repository: tailscale-signin/GPT_Mobile_AI-improved. Base: main at 62d895f9db9ccfabf9619dde23ff71efc2385e1e. Working branch: feat/voice-github-status-redesign-2026-11-02, existing draft PR #788. Starting revision: 5df5f0125e2cf9482092ecf146396bc3e538fc69.

Intent: address the 26 findings supplied from v0.9.65.0/build 133. The supplied analysis is evidence of reported symptoms; it is not evidence of MCP connection failures, local inference failures, crashes, or APK failures. Implementation and validation findings are recorded separately below.

Checkpoint: inspect current-turn admission, task continuation, claim receipts, pagination and accounting; implement regressions before publishing. No merge requested.

## Source findings and implementation

The incident establishes incomplete work despite successful tool events. It does not establish failed GitHub connectivity or application crashes. Code review confirmed separate classification of a literal `test`, a hard-coded 30-item query in the workspace adapter, unconditional expansion instructions in answer rewriting, a restored-attempt tool budget that could already be exhausted, and a coalescing usage update that could not clear a stale total.

| Export item | Implementation and effect |
| --- | --- |
| 1. Incomplete full test | `GitHubTestChecklist` records 18 API capabilities with receipt IDs and passed/failed/untested states, outstanding local/remote pages, application/device limitations and task acceptance criteria. Checkpoints use the existing protected workspace evidence journal. API success never closes application-test coverage. |
| 2. Resume without progress | Deterministic read acquisition resumes the first missing capability or issued continuation. Existing receipts prevent replay. Explicit continuation gets a new bounded runner allowance while preserving write reconciliation and historical accounting. Undispatched failures are retried only through the separate explicit-retry path. |
| 3. Literal `test` launches work | GitHub chat exits before tool discovery, memory preparation or model generation for unambiguous conversational requests without attachments. The shared request contract also gates the verified session. The acknowledgement says only that the message was received and no repository checks ran. |
| 4. Conflicting continuation | Rewrites explicitly replace their private candidate; they no longer receive append-only continuation instructions. Follow-up context references the current request and explicit additions. A queued acknowledgement remains a separate turn instead of joining active engineering work. Explicit resume still uses its saved original task. |
| 5. Evidence mapping | Each rejected claim records its receipt ID, pointer, exact reason and targeted recovery action: missing receipt, missing pointer/value, failed/untrusted operation, stale revision/time or mismatched value. Supported claims remain present. Coverage incompleteness uses `GITHUB_TASK_INCOMPLETE`; it is distinct from an unsupported-claim failure. |
| 6. Unsupported health score | Deterministic reports score only observed API capabilities: one point per capability with a successful receipt divided by observed capabilities. They separately report capability coverage and explicitly leave overall integration health unmeasured. Failures remain in the operation table even after a later success. |
| 7. Deliverables lost in fallback | Receipt-based fallback includes coverage, successful/failed observations, scoped score or limitation, untested operations and concrete solutions/next actions without another rewriting invocation. |
| 8. Read-only test coverage | The matrix explicitly lists branch creation, patch staging, commit publication, draft PR, dispatch, rerun, cancellation, merge and cleanup. Existing write tools and authorization remain responsible for executing exact isolated fixtures. Untested writes are visible, not silently credited. No generic integration-test request automatically merges or cancels unrelated work. |
| 9. Observing CI vs executing tests | A workflow execution record requires an accepted mutation, exact run ID and SHA, and a later matching result. Existing runs, wrong SHAs and observations preceding dispatch cannot become newly executed tests. A generic workflow success still does not prove application-test coverage. Reruns lacking an attempt fence remain pending rather than borrowing the old terminal result. |
| 10. Page-size forwarding | Workspace queries now forward validated `per_page` (1–100) instead of always requesting 30. Branches, commits, repository/install lists, pull files, tags, releases and compact check pages retain the effective size in metadata. |
| 11. Continuation consistency | Commit continuations preserve the effective page size and pinned SHA. Existing operation adapters preserve filters and translate native continuations to exact model-facing recipes. |
| 12. Workflow inventory | Integration-test acquisition consumes issued workflow/branch/PR inventory continuations within the task budget. Exhausted budgets retain incomplete pagination rather than claiming a complete inventory. Recent commit/run samples remain samples unless full history was requested. |
| 13. Local fragments | The evidence book retains the delivered view envelope alongside original evidence. Checklist acquisition finishes local fragments before following remote continuation recipes. The recipes pass normal schema, read-only authorization and tool-budget checks. |
| 14. Truncated commit observations | Compact commit views carry subject, SHA, author/date and link, omitting verbose history graphs and descriptions. Original evidence remains retained; `response_detail=full` retains the full-view path and existing saved-page mechanism. |
| 15. Four-call starvation | Short status uses four scoped reads plus capacity for two failed-read recoveries. Only identified transient read failures qualify for one retry; authentication failures and successful observations are not repeatedly dispatched. |
| 16. Mixed revisions | Operation coverage groups by tool/action, branch, exact SHA and PR. Current branch checks remain constrained to its observed SHA. Workflow summaries identify their own head; PR and main health remain separate. |
| 17. Pending results | Integration acquisition subscribes once to an observed pending run at the selected SHA using the existing bounded background watcher. It never invents run IDs or substitutes another head. Pending/unassociated runs stay pending in checkpoints. |
| 18. Token totals | Authoritative invocation-ledger reconciliation atomically replaces all three run dimensions and can clear an unknown total. The export adds cumulative unique-invocation reconciliation separately from raw legacy run rows and includes unmeasured counts and source semantics. Per-invocation records retain source/estimation metadata. |
| 19. Latency | Literal acknowledgements use no model or rewrite. Minimal lifecycle capture includes invocation start/finish and first text/reasoning/tool observations, allowing client-observed timing to be distinguished from tool activity. Server reasoning CPU time remains unmeasured; wall intervals are not mislabeled as CPU work. |
| 20. Rewrite waste | Deterministic acknowledgement and checklist reporting bypass unnecessary model rewrites. Private answer rewrites have an explicit replacement contract and emit a typed lifecycle event. |
| 21. Intent-based output budget | Conversational GitHub fallback admission is capped at 128 tokens; short status remains capped at 2,048. The primary acknowledgement path consumes zero provider tokens. Substantive tasks retain their configured budget. |
| 22. Repetitive progress | Checklist notices follow changed receipt state. Heartbeats suppress unchanged activity summaries. Tool completion/failure and the next operation remain observable. |
| 23. Report readability | Status shows a compact recent-commit table with subject, short SHA/link, author and date. Integration reports separate observed API capabilities, revision-scoped results, limits and next actions. |
| 24. Empty diagnostics | A bounded typed lifecycle trace is retained independently of verbose capture for saved chats: intent, admission/result, continuation, verification, rewrite, terminal outcome and invocation timing boundaries. No prompt, source body, URL, token or arbitrary error text is added. Temporary/unknown privacy scopes are excluded; existing vault retention, deletion and capacity limits remain. Export labels this as `minimal_lifecycle`. |
| 25. Stale helper reference | Deleting the selected helper clears its reference, disables Assist and rotates its permission epoch. Enabling Assist requires an existing enabled helper. Existing readiness/processing permission checks continue to apply; profile availability does not claim inference success. |
| 26. Empty acceptance criteria | Effective test acceptance is compiled into the task prompt and saved checklist: capability outcomes, revision-bound receipts, pagination, actual execution, complete reporting and fixture cleanup. These are task-local defaults; explicit repository workflow/path acceptance entries are not fabricated or silently added to gateway settings. |

## Completion and safety contracts

A checklist's `PASSED` means the recorded API operation succeeded. The UI calls it **API succeeded** so it cannot be confused with a current passing CI state. A failed attempt remains in historical operation coverage. Successful read receipts are never evidence that patches, commits, PRs or tests executed.

Full inventory requests consume local fragments and then remote pages. Requests are finite and obey existing cancellation, rate-limit and shared tool budgets. A stale saved cursor can fail explicitly; it is not replaced with an invented cursor or blindly replayed mutation. Historical receipts are retained as historical observations; the existing freshness gate still applies to current claims.

The write matrix is not permission to mutate a production branch. The ordinary agent prepares an isolated fixture through the existing tools and authorization path. Merge/cancel exercises need an eligible fixture and the normal exact-action approval. A denied or unavailable exercise remains blocked/untested. The checklist itself only automatically dispatches reads and a background watch of an observed pending run.

Checkpoints are derived from persisted receipts, including views required for continuation, rather than a separate mutable database schema. Resuming an explicit task gives a fresh bounded attempt allowance while keeping the durable execution ledger that prevents repeating completed mutations.

Minimal lifecycle metadata retains existing encrypted storage and temporary-chat restrictions. First-token and reasoning boundaries describe observations at the client. They do not establish upstream internal reasoning duration or a performance improvement percentage.

## Validation

Regression coverage includes literal test acknowledgement without provider/tool activity; effective page sizes and second-page continuation; resumed budget admission without losing previous receipts; local-before-remote pagination; rejection of mutation continuation recipes; scoped API score and remaining solutions; claim-pointer diagnostics; readable commit projection; revision separation; pending-run subscriptions; and rejection of old/wrong-head workflow results.

Additional regressions cover replacement-vs-continuation instructions, clearing unknown totals through the Room DAO, and lifecycle retention with verbose capture disabled while excluding temporary chats.

Validation results are recorded below after the final checks. Android/Room tests require the Android dependency graph; local host harnesses use the actual orchestration, GitHub client and policy sources with platform-service adapters replaced. They are not an APK or physical-device test.

Final local results:

- **526 orchestration/GitHub JVM tests passed**, including the final inventory regression (different filters remain separate; a failed local page cannot produce complete coverage). The final changed checklist class and its tests were compiled against the same host suite and the entire suite reran with that class first on the classpath.
- **17 diagnostics/accounting JVM tests passed**, including disabled-verbose lifecycle capture, privacy exclusion, loss accounting and measured-versus-estimated usage.
- **295 canonical fast host tests passed**; architecture-index, resource, Android-regex and tool-statistics preflights passed.
- Changed Kotlin files pass ktlint 1.3.1 and whitespace checks.
- Android Gradle tests were attempted with JDK 21 and SDK 37. Dependency resolution stopped before compilation because the offline cache lacks dependencies including Health Connect external protobuf 1.1.0, coroutines-guava 1.11.0 and annotation-experimental 1.5.0. The newly added Room DAO and follow-up integration tests therefore await Android CI; no APK/device test is claimed.

Review boundary: this change implements the task/checklist, reporting, adapter and diagnostic behavior described above. It does not claim to have executed every GitHub mutation against a live test repository, to have measured overall integration health, or to have reproduced failures outside the supplied incident evidence. The PR remains Draft for Android CI and device validation.
