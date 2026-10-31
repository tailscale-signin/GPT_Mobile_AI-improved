# GitHub tool contract repairs

## GitHub chat diagnostic follow-up

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Base branch: `main` at `020b351bc3b98bd30cbdbb3443a332e8f5bd88f9`.
Working branch: `fix/github-tool-contracts-2026-10-30`, PR #765.
Repair checkpoint: `9144dee0ef386b0f9313e3fd0651143e284d3914`.
Intent: repair the uploaded build-132 diagnostics: cross-message source handles,
saved-page argument loops, repeated unsuccessful GitHub rounds, empty/protocol
finalization and the oversized repository-search coroutine. Retain task-scoped
mutation receipts and account/connection/conversation isolation.

The log contains 36 source-handle failures, saved-view correction loops, three
rejected provider tool frames and five oversized-search compiler warnings. These
are distinct from inaccessible CI resource IDs and unreachable external MCP hosts.
The latter cannot be made reachable by changing Android source.

- Retain at most 128 immutable source descriptors per repository and conversation.
  A new turn or process can inventory and reopen them without selecting an old
  branch revision. Descriptor aliases exclude source bodies. Cross-account,
  connection, conversation, repository and conflicting-commit reads are rejected.
- Keep captured result pages available across turns in the same credential,
  connection and conversation. Page delivery never dispatches a backend operation;
  mutation receipts and stages keep their existing task-specific authorization.
- Accept matching owner/repository/commit/action defaults when reading a saved
  page. Conflicting context still returns an exact cursor-only correction.
- Finalize after three consecutive unsuccessful GitHub tool rounds, resetting the
  bound on a successful result, including a saved read. Repeated empty responses
  and failed protocol repair receive one text-only finalization from saved results.
  The existing single-pass and cancellation policies remain enforced.
- Preserve final-response instructions inside the structured GitHub envelope so
  replay compaction retains both the instructions and executable recovery handles.
- Split blob prefetch and file matching out of the repository search coroutine.
  The compiled search method is 10,302 JVM bytecode bytes; Android ART behavior
  still requires an APK/device check.

Current follow-up validation: 91 Kotlin host regressions passed against the real
runner, facade, retained views, session and replay code. Android storage adapters,
telemetry, unrelated Combined types and UI answer projection are host stand-ins;
this is not a full Android build. All 238 canonical host tests, changed-source
ktlint 1.3.1, architecture-index regeneration and whitespace checks pass. The
targeted Gradle invocation failed downloading Gradle with `Network is unreachable`;
this workspace also lacks JDK 21 and Android SDK 37. Earlier hosted successes
below apply to their recorded head only; new-head Android verification is pending.

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

## CI follow-up on PR #765

Repair base: `864ae21e776c36c59941f8513a7b4de2a40b9dad`; same working branch.
CodeQL run `41154624990`, job `124398586717`, failed before Kotlin compilation
while resolving `io.objectbox:io.objectbox.gradle.plugin:5.4.2`. Other checks had
already passed formatting and repository contracts; Android checks were running.

The ObjectBox marker and implementation were fetched from Maven Central. Their
SHA-256 values match the existing verification metadata: marker POM
`6847926c3bf9941bd1982cec5206da2493d57294f320f74bce076ece56b19047`,
implementation JAR `08f70685fb677ed83ea8d39a08a09afe274a412f9fcf273d1ab17b1b01ce2365`,
and module metadata `63b5deb386fd79abc9dc14ed7dac0333af6358590d548f989411181d375c5e33`.
The implementation POM also confirms version 5.4.2 and the official ObjectBox SCM.
Sources: [marker POM](https://repo.maven.apache.org/maven2/io/objectbox/io.objectbox.gradle.plugin/5.4.2/io.objectbox.gradle.plugin-5.4.2.pom),
[implementation metadata](https://repo.maven.apache.org/maven2/io/objectbox/objectbox-gradle-plugin/5.4.2/objectbox-gradle-plugin-5.4.2.module).

Plugin resolution now targets that already verified implementation directly.
CodeQL uses the existing bounded dependency retry runner and verbose resolution
diagnostics. Versions, trusted hashes, compiler/test failures and security gates
retain their existing enforcement; unknown-plugin errors without explicit
transient HTTP evidence are not retried.

Follow-up validation: all 235 canonical host tests pass; the retry process
regressions, actionlint and settings ktlint pass. A Gradle 9.8.0 smoke project
using the repository's plugin-resolution settings and unchanged strict
verification metadata resolves the ObjectBox implementation successfully, and
also configures a versionless consumer of the already loaded plugin. Its isolated
classpath includes AGP 9.4.1's published ASM 9.9 constraint so it matches the
existing application's verified dependency graph. No dependency version or
trusted checksum was changed. Full Android/CodeQL compilation remains a CI check.

CodeQL then passed on `e58de9230d7081ba5b00a1d0781829a444189745`
(run `41155543290`, job `124401425731`). The canonical contracts job separately
reported dependency-review HTTP 403. This security gate remains enforced.
Denied review requests now produce a `BLOCKED_API` artifact with a bounded,
credential-redacted upstream message and `scan_performed=false`; the independent
gateway/OAuth tests still run after a denied scan. No scan result is invented and
no repository security setting is changed implicitly.

## Replay delivery follow-up

Follow-up base: `e58de9230d7081ba5b00a1d0781829a444189745`, same branch.
Provider replay compaction could retain a next-page cursor after dropping records
from the current page, including small results from a large parallel batch. Each
retained result now supplies a scoped recovery call for its current offset.
Compaction offers that saved read before any later cursor, marks the preview as
unverified and delivers smaller recovery pages within the replay allowance.
Old call metadata no longer consumes the entire replay budget before the newest
result is admitted; complete old pairs are removed as necessary to maintain the
hard context bound. Capacity, expiry, scope and operation checks still apply.

Regressions reconstruct Unicode data without gaps through pressured replay,
recover mutation receipts with exactly one backend mutation, reject forged page
ceilings and foreign scopes, and verify that denied dependency scans still fail
and retain safe diagnostics.

Follow-up host validation: 197 Kotlin tests passed, including the full exchange
compactor suite and the new recovery regressions. All 238 canonical host tests,
changed-source ktlint, workflow actionlint and whitespace checks passed. Cloud
checks must be refreshed on the next published revision; the earlier CodeQL
success is evidence for the plugin repair, not approval of a later head.
