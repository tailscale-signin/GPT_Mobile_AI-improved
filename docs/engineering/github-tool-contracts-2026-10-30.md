# GitHub tool contract repairs

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Base branch: `main`.
Base SHA: `020b351bc3b98bd30cbdbb3443a332e8f5bd88f9`.
Working branch: `fix/github-tool-contracts-2026-10-30`.
Intent: reproduce the distinct failures in the supplied deep-test reports, repair
argument/action contracts, CI reads and state handling, and publish a reviewable branch.

The reports repeat many entries and contradict one another about supported actions.
Their call counts, tool counts and sample IDs are not treated as verification evidence.
An empty artifact collection, a missing branch, a stale PR head, an active run and a
permission denial are distinct outcomes. No production deletion, cancellation,
workflow dispatch, review, staged patch commit or release is performed as a test.

## Checkpoint

- Created the remote branch at the observed main SHA; local checkout matches it.
- Confirmed missing facade aliases, direct-log redirect handling, failure-log
  expansion contract, and PR reviews without inline comments as repair targets.
- Implemented and tested action admission, CI routing, logs and mutation prechecks.
- Extended the same branch for the follow-up report: bounded GitHub pages receive
  separate result allowances; issue inspection/listing and paginated discovery are
  explicit capabilities.

## Repairs

| Area | Result |
|---|---|
| Action compatibility | CI `status` reads a run, job or selected commit; `rerun_failed_jobs` aliases failed-only retry, and `rerun_all` explicitly retries the full completed run. `commit_staged_patch` aliases staged commit; `ranges` is an optional read action. |
| Argument admission | Missing ranges/evidence, invalid paths/line spans, missing IDs, incomplete patches, and malformed staged commits fail before backend dispatch with the required fields. Workflow IDs accept discovery's integer IDs as well as filenames. |
| CI ID/state checks | A run ID passed as workflow ID is diagnosed through read-only discovery. Active reruns stop without cancellation; completed cancellations and successful failed-only reruns are observed no-ops. Acceptance receipts lead to read-only status checks. Permission denials remain permission denials. |
| CI collections | Jobs, workflows and artifacts honor `per_page`, retain IDs/filters in continuation calls, and report returned/empty counts. A missing collection is an invalid upstream response, not successful emptiness. |
| Logs | Direct logs share the redirect-aware, credential-fenced reader. Bounded line/character continuations preserve long Unicode lines. Failure digests expose executable log expansion, excerpt qualification, and resumable failed-job discovery beyond the ten-page inspection batch. The 4 MiB source-ingestion ceiling is explicit. |
| PR reviews | A review without inline comments uses the top-level review endpoint with the observed commit ID. Inline reviews retain their existing path. Stale heads stop before posting; PR creation validation points to branch/PR discovery. |
| Staged commits | The submitted expected head must match the saved stage. Replayed completed stages still return their retained receipt. A default-branch commit refusal explicitly identifies the need to create/select a working branch. Staging is a local preview; publication retains its existing working-branch and exact-approval checks. |
| Large parallel results | GitHub tasks use bounded allowances per result instead of exhausting a cumulative 24 KiB excerpt counter after a few saved pages. Calls, observations, authorization, retained-view capacity, timeouts and provider replay/context limits still apply. Page sizes respect the admitted output allowance, and every tenth progress checkpoint preserves the structured page instead of discarding fragments. This changes the app's own budget; it cannot change a separate host service's limits. |
| Issues/catalog | Issue requests admit `github_issue`; direct paginated listing excludes PR records without prematurely ending a mixed GitHub issues/PR page. Tool discovery inventories use twelve-record pages, preserve omission reasons and report operational status as untested. Repository issue counts do not replace issue inspection. |

## Validation

- Final canonical host validation: 234 tests passed, including CI policy, resource,
  regex, secret and whitespace checks.
- Kotlin 2.4.20 host compilation and JUnit execution use the repository's actual
  tool facade, native HTTP client/session code, budget implementation and Ktor
  MockEngine. Android storage, telemetry and unused Combined runtime dependencies
  are stubbed in this host harness; this is not a device or full-app build.
- Final Kotlin regression run: 185 tests passed, covering the original GitHub
  suites, the new HTTP/action/continuation regressions, parallel saved pages beyond
  24 KiB, call/zero-output ceilings, ordinary budget behavior and paginated discovery.
- ktlint 1.3.1 on every changed Kotlin file and the regenerated architecture index:
  passed.
- Targeted Android Gradle invocation was attempted but the Gradle distribution
  download failed with `Network is unreachable` in the Java runtime. This host
  also lacks JDK 21 and Android SDK 37. APK, Android lint and device execution
  must be checked by the branch CI; no success is inferred from the host subset.
