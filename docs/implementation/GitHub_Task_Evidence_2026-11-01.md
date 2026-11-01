# GitHub task evidence and response quality

Repository: tailscale-signin/GPT_Mobile_AI-improved
Base: fix/github-chat-quality-export-recovery-2026-11-01 (PR #776)
Base SHA: 3bd5671b15c23d603b66e67598ce36ad1152d8f3
Branch: fix/github-task-evidence-reporting-2026-11-01
Intent: address the build 133 repository-status export: report operation coverage without confusing source inspection with test execution; preserve error and receipt evidence; reduce metadata/pagination overhead; repair continuation guidance and commit provenance; improve task-specific presentation and continuation instructions.

## Implemented

- Testing requests render operation coverage from the trusted ledger, retaining successful and failed observations and receipt IDs. Source inspection, workflow inspection and actual test execution remain distinct. Provider completion is accompanied by an independently persisted `task_outcome=PARTIAL`, never a fabricated feature-acceptance verdict.
- Deterministic test reporting avoids the claim-extraction model and preserves current verified CI fields. Historical operation coverage remains visible without making stale CI facts current. General replies keep claim verification and permission checks.
- Verification checkpoints carry the original task and operation coverage into the existing encrypted engineering memory. Continuation prioritizes checkpoints from the same chat and existing connection/profile/repository/branch permission scope. Historical state cannot authorize writes or establish fresh success.
- Compact read projections omit signatures, nested GitHub App permissions and irrelevant identity metadata, preserving operational fields and raw evidence. `response_detail=full` restores metadata on supported inspection calls. No mutation is repeated to recover a result.
- Wrong-operation saved cursors return the exact originating tool/cursor only within the already authorized scope. Foreign/expired/unissued cursors remain unavailable. Missing pinned paths return a structured discovery call rather than another guessed read.
- Verification catalogs cover each operation before repeated reads; test reports omit raw test source/import blocks. Existing immutable read sharing, cache policies and execution budgets remain in force.
- Status replies accept the actual check/PR receipt shapes, label check counts and commit identifiers, and use compact PR identities. Only an explicit aggregate status snapshot short-circuits the model; an isolated check receipt does not stop collection of the rest of a status report.
- Commit-message excerpts use each commit's own SHA, retaining snapshot and blob semantics separately.
- The inherited #776 changes retain settings export and remembered body typography. Reducing giant tables/source blocks reduces layout work; a device trace is still required to establish scrolling performance.

## Validation

- Focused Kotlin/JUnit host harness: 107 tests passed, including the final status-short-circuit regression.
- Canonical `bash scripts/validate-local.sh fast`: 285 host tests passed, plus regex, resources, icons, dependency/CI policy and provenance checks. Fixture outputs inside that suite are not physical-device executions.
- ktlint 1.3.1 passed on changed Kotlin files; architecture index regenerated; `git diff --check` clean.
- Android Gradle unit-test command was attempted but could not download Gradle 9.8.0 (`Network is unreachable`). Host uses JDK 17, rather than the Android build's required JDK 21/SDK 37 environment.
- Host Kotlin checks compile the changed pure runtime/reporting classes with cached dependencies and platform stubs. They do not replace Android compilation, Room integration, device tests, live OAuth/GitHub writes, or UI performance measurement.

## Remaining acceptance boundaries

This change does not claim a measured speedup, complete application test coverage, successful live write/merge tests, or that every repository feature is complete. Full CI and device validation remain required. Autonomous work continues under the existing runner/checkpoint budgets and authorization; broad requests still need actual execution receipts to satisfy acceptance.

