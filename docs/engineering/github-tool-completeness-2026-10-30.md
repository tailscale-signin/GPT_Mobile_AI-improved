# GitHub tool completeness — 30 October 2026

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Base: `main`, `6d31cafd74ee86e1ffbe27b76cadf74298c92a72`.
Working branch: `feature/github-tool-completeness-2026-10-30`.
No parent PR: #761 and #762 were already merged when this branch was created.
Intent: preserve tool evidence and reduce continuation overhead without inventing metrics or replaying writes.

The supplied report names 19 capabilities; its summary says 23, but does not identify four additional tools. Reported file counts, call counts, quality scores and coverage percentages are not verified measurements. Some names are external aliases rather than separate native tools. This ledger maps every named capability to the implementation.

| # | Report capability | Implementation |
|---|---|---|
| 1 | Path search | Existing tree-only traversal scans beyond the content batch window without downloading blobs; pages now contain up to 100 matching paths. |
| 2 | Literal search | Existing 1,000-candidate default, prefix filters and coverage identity retained; cold reads now use four concurrent downloads, default 16 downloads per call, configurable 1–32. Time, blob size and output limits still apply. Exact continuations expose the stopping reason. |
| 3 | Definitions discovery | Directly available in the search schema; description names every mode. No separate definitions tool is required. |
| 4 | Branch metadata | Existing branch list retains commit/protection fields; `github_context(action=branch)` adds protection and applicable rules observations, with unavailable reads explicitly unknown. |
| 5 | Commit history | 30 commits per page, pinned tip, continuation calls, author/committer identities, parents and full messages. |
| 6 | Branch creation | Receipt now exposes head SHA, expected head SHA and resolved base SHA directly. Existing base-head comparison remains. |
| 7 | Branch deletion | `github_context(action=branch_dependencies)` pages both head/base PR dependencies and workflow history. `github_commit(action=delete_branch)` checks rules, protection, active runs, open PRs, default/selected branches, merged ancestry and fresh expected head before deletion. |
| 8 | File ranges | Existing pinned multi-range/evidence reads already support up to 2,000 lines per range and byte continuation. Responses now expose expected_blob_sha directly. Reading never requires staging a mutation. |
| 9 | Staged patch verification | Existing stage IDs and receipts remain; context recovery can inspect a retained stage and its file SHA identities without restaging or committing. |
| 10 | PR creation/status | Creation returns a full receipt and a read-only follow-up for current checks/merge health. New reviews action pages submitted reviews; existing comments/health cover inline comments and unresolved-thread state. |
| 11 | Issue creation | New native `github_issue(action=create)` retains complete upstream identity, body, timestamps and a follow-up context read. Exact write approval applies. |
| 12 | Issue updates | Requires current expected_updated_at, records before/after values in the receipt, and exposes comments/timeline pagination. Timeline is not a complete version archive of body edits. |
| 13 | Commit search | Repository-scoped search exposes author information and full search metadata, exact pagination, upstream incomplete-results flag and GitHub's 1,000-result ceiling. Commit detail provides files/stats/parents. |
| 14 | Status/quality details | Detailed checks and statuses are paginated; CI jobs/logs/artifacts remain available. No synthetic coverage percentage or CodeQL numeric score is returned. |
| 15 | Recovery context | New task recovery view inventories retained evidence IDs, paths, blob SHAs, ranges, stage metadata and commit/review receipts. Conversation action receipts remain accessible through the existing read_recovery_context corpus reader. |
| 16 | Repository search/details | Search preserves fork, owner, identifiers, counts and pagination; repository details fetches complete fork/parent metadata plus language byte counts. |
| 17 | Tags/release notes | Tag inspection returns ref, annotation (if present), resolved commit and release observation. Release detail/list retains body/notes. Unavailable releases are unknown, not assumed absent. |
| 18 | Tag creation | Receipt retains the complete ref, target SHA and explicit lightweight-tag type. Mutable targets require expected_head_sha; immutable targets are accepted directly. No invented annotated-tag ID or release association. |
| 19 | Commit creation | Receipt adds parent/tree/head SHA and changed-file identities; the existing atomic non-force ref update and staged review remain in place. |

## Operational boundaries

- `batch_size` is a candidate budget, not permission to issue 1,000 network downloads. Partial scans are explicit and resumable. Large/binary/unsupported files and truncated match previews remain visible in coverage flags.
- Read local saved-view pages before remote continuation. New issue operations are client-owned in gateway v14 and never classified as replayable mutations.
- GitHub's issue update and ref deletion APIs do not provide an atomic expected-version precondition. Fresh prechecks reduce stale actions but do not eliminate a concurrent edit between the read and write. Branch deletion remains exact-approved and conservative; unknown rules block it.
- Issue history comes from issue timeline/comments. CI runs cannot substitute for issue history, release notes, fork ancestry or branch rules.
- No new voice features. No production issue/tag/branch deletion is performed as a test.

## Follow-up verification and CI changes

| # | Follow-up request | Verified result / implementation |
|---|---|---|
| 20 | Complete workflow history | Read 20,793 unique runs over 209 API page requests, reaching the end and matching the first-page total. The history contains 45 workflow IDs; the checkout contains 25 workflow YAML files. These are historical and current-file counts, not a claim of 45 active workflows. |
| 21 | Native CI run pagination | Fixed the previously ignored page/workflow selector. `github_ci(action=runs)` now reads up to 100 runs per page, retains attempts, actors, commit and artifact/job links, and preserves branch/status/event/actor/created/head filters. `status=failure` is supported. GitHub limits filtered searches to 1,000 results; the response reports that limit and instructs smaller date windows. |
| 22 | Job steps and artifacts | Jobs retain every returned step and its conclusion. `action=job, job_id=…` and `action=jobs, job_id=…` support direct job inspection; `action=jobs, run_id=…` lists paginated jobs. Artifact records retain digest and workflow-run association. Existing bounded logs and failure digests remain available. |
| 23 | Resumable historical analysis | `collect_workflow_health.py --max-pages 250` covers up to 25,000 runs. Each accepted page atomically checkpoints IDs, next page, counts and metrics. `--resume previous.json` resumes the saved inventory, deduplicates and distinguishes traversal completion from verified coverage. The workflow exposes the page budget and retains partial reports on failure. |
| 24 | PR #761 and #762 diffs | Both are already merged. Retrieved all 30/94 file records and all 30/94 patches. Merge SHAs are `066458ea69f5d7287e38d1b6b309a0a021ac5563` and `6d31cafd74ee86e1ffbe27b76cadf74298c92a72`. Old conversation view cursors were not reused. |
| 25 | Branch inventory | Seven pages returned 651 distinct branches, including this new branch, with head SHAs. The supplied 28-branch count was incomplete. The connector cannot read branch rules, so applicable rule status remains unknown; the application inspection reports unavailable policy explicitly. |
| 26 | CI optimization | Optional APK/CodeQL push workflows skip root README, Markdown documentation and LICENSE changes. Required PR validation remains universal and runs on synchronize. CodeQL still scans test changes and now runs for relevant workflow edits. Existing scoped Gradle caching, parallel lint/contracts and cancellation already exist. Remote diagnostics is manual; no source-snapshot workflow is present in this checkout. |

The machine-readable [history audit](../audits/github-workflow-history-2026-10-30.json) records counts for every historical workflow and links the three sampled failed runs. Their logs show two unit-test assertions, an unresolved Kotlin `delay` reference, and APK size-budget failures. These are historical examples, not a claim that all failures share those causes. The complete current unit-test run passed those suites; newer main builds succeeded. No annual savings or percentage speedup is inferred from counts alone.

The filtered-run ceiling and accepted filters follow the [GitHub workflow-runs API contract](https://docs.github.com/en/rest/actions/workflow-runs#list-workflow-runs-for-a-repository).

Use `github_context(action=branch, branch=main)` for branch policy, `action=efficiency` for observed tool metrics, and `action=changes, base_sha=…` for diffs. Staged patches commit through `github_commit(stage_id=…, expected_head_sha=…)`; `github_write` accepts file changes. Correct repository prefixes include `app/src/main/kotlin/`, not `src/main/java/`.

## Validation

- Canonical `bash scripts/validate-local.sh full`: passed. The full Android suite ran 5,467 tests with zero failures (four heap-only cases skipped in its normal JVM); Android lint, all debug APKs, packaged native runtime checks and telemetry checks passed.
- Final source regression and APK rebuild: 376 GitHub/workspace tests passed with zero skips or failures; rebuilt APK runtime and telemetry checks passed after the follow-up CI and selected-branch guard changes.
- Separate 256 MiB heap gate: 10 tests passed with zero skips or failures, including the four cases intentionally skipped by the normal suite.
- Final canonical host validation: 233 tests passed. Gateway validation: 198 Python tests plus seven browser bootstrap tests passed. GitHub authentication: 15 tests passed.
- ktlint 1.3.1 on changed Kotlin, actionlint 1.7.7, CI policy, architecture index, resource checks and `git diff --check`: passed.
- Live read-only audit: 20,793 unique workflow runs, both complete PR file inventories, 651 branches, and failed-job steps/logs for three representative runs.

No performance percentage, production reliability score, device qualification or live destructive-tool test is claimed. Remote CI will run on this branch's published PR.
