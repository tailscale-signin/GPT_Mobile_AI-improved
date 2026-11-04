# Audit of the five newest merged PR branches

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Pinned starting main:
`6912b6404054728630174f1c4d9aa6c4b576e06c`.
Working branch: `fix/merged-branch-quality-audit-2026-11-03`.

## Selection and merge integrity

Selected the five newest created PRs targeting main that GitHub reports merged.
Git does not expose a durable branch-creation timestamp; PR creation order is the
explicit selection proxy. PR #795 was integrated through #796. Every final PR
head below is an ancestor of the starting main, so none of their commits is missing.
No PR discussion comments were returned for these five PRs.

Merge order differs from creation order: the five latest merges are #791 and
#793–#796. To cover both interpretations, this audit also reviewed #791 at
`2616a0a1cacfad7ed8f6f309273e181f6e95a35d`, which is likewise an ancestor of
starting main. Its 22 graph entry points map to the service, extraction, traversal,
private persistence, gateway/mobile adapters and graph regression suite. A separate
documentation-only audit PR #797 was observed during this work and left intact.

| PR | Branch | Final reviewed head | Proposed change and audit |
| --- | --- | --- | --- |
| #792 | `release/isolated-signed-validation-2026-11-03` | `19eb4e73fc4e59c5544074e19766747a9955bc16` | Separate compile, tests, debug lint and release validation into one-worker processes. Present in release-build.yml; PR validation excludes publishing and signing. Existing phase/permission regressions retained. |
| #793 | `release/verified-test-pins-2026-11-03` | `d00140c5c849f5d03088e7759851bc443ae0a1b6` | Publisher-verified test dependencies, isolated vital lint and exact-commit PR validation before release. Pins and mandatory gates retained; no checksums, limits or workflow requirements weakened. Version 0.9.66.4 supersedes #792's 0.9.66.3 intentionally. |
| #794 | `fix/github-log-scroll-stability-2026-11-03` | `c9f4ff03cc0ef2b275c82a1fb3dc6ce5538922ad` | 1.5-second prompt animation, sustained history release, user-scroll handoff, true-bottom following, summary ordering, receipt-bound branch/commit continuity, saved review retry and bounded packaging. Production motion/receipt helpers and their API 34/36 acceptance wiring remain present. |
| #795 | `fix/github-chat-progress-2026-11-03` | `f2daff0bc7e158f577518da773d647f89d9c7151` | Saved Heavy work preset, larger text, rendered-line folding, stable check counter, event-driven summaries, executable discovery, privacy preflight/cache, exhausted-review admission, serial TTS and async settings. Found settings-write ordering and reporting gaps repaired below. |
| #796 | `fix/github-checklist-diagnostics-2026-11-03` | `b2cd84807cb270aa0417b2bc60ec220e38a40c21` | Typed observed dependencies, pinned workflow definitions, suitable log jobs, failed-page retention, bounded read recovery and computation dispatcher. Preserved; found completion/coverage edge cases repaired below. |

## Repairs from this audit

- Require contiguous inventory pages from page one, consistent totals, unique IDs,
  the same page size and explicit terminal pagination. A last page or a short
  result no longer certifies missing rows. A refresh replaces its old page rows;
  historical failures remain visible while a successful identical read can resolve
  the current coverage blocker.
- Use one exhaustive-inventory predicate for planning and completion, including
  commits, runs and workflow/CI history. The ordinary two-continuation smoke sample
  remains bounded. Exhaustive requests must satisfy independent inventory coverage.
- Separate scope exclusions from failures: account-wide discovery is outside a
  pinned workspace; explicit read-only requests do not require mutation tests.
  Exclusions remain visible and are never called passes. Explicitly requested writes
  or negated read-only wording cannot silently exclude mutations.
- Permit completion for the supported unchanged-branch fixture only with successful,
  currently eligible creation and later cleanup receipts, matching task/repository,
  deterministic owned branch, source/default branch, exact head and deletion proof.
  A collision, missing source, revoked receipt, failed cleanup, wrong head, wrong
  task, wrong repository or cleanup before creation cannot qualify. Other mutation
  families remain blocked without their own authorized fixture plans.
- Treat partial indexing as endpoint evidence while retaining separate incomplete
  inventory status. Pending logs remain blocked. A later failed call cannot be
  labelled recovered merely because an older call succeeded.
- Bound ordinary operation tables to 12 groups and large capability reports to six
  distinct blockers. Counts and all structured rows/receipt IDs remain in the
  existing conversation export; an explicit detailed report still renders them.
  Error remedies identify arguments, scope, unavailable resources, page recovery,
  retry deadlines or uncertain writes instead of generic replay advice.
- Serialize voice settings writes with one bounded, conflated consumer. A slow
  earlier save cannot overwrite a newer offline/privacy setting. Loading cannot
  overwrite a value changed while loading. Cancellation rejects subsequent writes.

## Graph follow-up from merge-order selection

#791's source-level graph features remain bounded and explicitly heuristic. The
31-test graph suite exercises extraction, calls, imports, impact/tests, provenance,
incremental/encrypted indexes, memory, requirements, contracts, diagnostics,
federation, metrics, optional-provider absence, bounds and authorization.

Two defects found during this review are repaired. Four regression cases pass on the repair; the same cases produce three failures/errors on the original production service, while authorization/cancellation rejection remains passing:

- A failed blob fetch or integrity check used to abort a whole 24-file batch before
  saving successful siblings. Persist a bounded failed-file marker and the good
  fragments; explicit `retry_failed` retries only failed/unsupported fragments.
  Missing or corrupt source is never indexed as code. Cancellation and HTTP
  401/403/permission failures still abort without committing the batch. Exceptions
  and credentials are not stored in graph warnings.
- Requirement evidence's `same_commit` previously used the job's contextual head
  even when the linked workflow executed another SHA. It now uses the observed
  workflow result, matching the existing pass/fail gate.

The full gateway validator passes: 373 Python tests (one existing platform-specific skip), including all native parser fixtures, and seven
browser bridge tests. External Graphify execution and mobile/device performance
remain unqualified. No compiler-resolved graph or runtime correctness is inferred.

## Reconciliation of the proposed follow-ups

The source audit is not proof of live provider, device or full-product acceptance.
The earlier documents explicitly distinguish implemented changes from recommended
follow-ups. They must not be described as all implemented merely because a PR merged.

| Earlier proposed item | Current disposition |
| --- | --- |
| Chat audit items 1–3: completion layers, fixture proof and effective continuation | API scope/fixture acceptance and independent inventory coverage strengthened here. Immutable successful reads are reused; changed corrected dependencies and one saved-page recovery are implemented in #796. General live-dependency refresh and additional mutation-fixture families remain incomplete. |
| Items 4–11: retry/intent, reviewer recovery, scope, tags and suitable logs | Existing tests/source retain retry aliases, conversational probes, observed tags/jobs, one format retry and exhausted-retry admission. This audit makes account-scope exclusions explicit. General word-limit versus presentation policy and model structured-output qualification remain separate follow-ups. |
| Items 12–14: saved pages, retained capacity and partial inventories | #796 retains failed pages and exact authorized recovery; this audit prevents false completeness and mismatched page-size aggregation. It does not expand the fixed result cache or promise every large upstream write result is retained. |
| Item 15: every mutation family | Only owned unchanged branch creation/dependency inspection/cleanup is implemented. Writes, issue/PR publication, releases, merges and CI cancellation must have separate owned-fixture plans and cleanup proofs; existing production work must never be used as a destructive test fixture. |
| Items 16–19: typed prerequisites, index coverage, denominators and remedies | Distinct scope/index/pending statuses, capability versus operation counts, inventory coverage and actionable recovery text now exist. No resource found, missing permissions and unexecuted device tests are not promoted to passes. |
| Items 20–28: progress, Settings, activity folding, typography, spacing and themes | Implemented in #794/#795 and retained. Host tests are not physical gesture, font-scale, accessibility, IME or device-frame measurements. |
| Item 29: repeated large reports | Bounded here; full evidence retained in the existing export and explicitly requested detailed reports. A dedicated interactive evidence drawer is not added. |
| Items 30–32: resource identity, inconsistent upstream timing and unavailable tools | Resource branch/SHA are separately retained; unavailable resource branches are labelled instead of inheriting main. Existing dispatch rejects unadvertised tools. Upstream reversed timestamps and a route-specific date-tool catalog need actual new observations; no duration or execution evidence is fabricated. |
| Items 33–41 and the 28-item build-138 follow-up | Existing early privacy checks, observed-rejection cache, progressive discovery, TTS worker/config cache, terminal IDs, accounting provenance, omission summaries and capture limits retained. Voice persistence race repaired here. Physical frame/StrictMode/database/IME/model-signature results, missing historical wire data and provider/server availability cannot be reconstructed by host tests. |

The complete original issue lists remain in
`github-chat-progress-1793730947653.md`, `build138-diagnostic-followup.md`,
`github-checklist-diagnostics-6228467361406318808.md`, and the #794 implementation
and repair ledgers. This document records the current disposition without rewriting
historical validation claims.

## Validation

- All six heads spanning both selection orders are ancestors of the pinned starting main.
- 34 focused JVM tests pass against current production checklist, fixture, planner,
  inventory and voice-writer logic. The host harness uses Android-independent DTOs,
  exact production operation mapping/read classification, and digest/status adapters;
  it does not execute Android, HTTP, Compose or speech services.
- The 31 graph regressions and full gateway validator pass; dependency hashes remain enforced.
- The canonical host suite passes 301 checks plus policy, dependency, secret, regex,
  resource/icon and provenance validation.
- ktlint 1.3.1, architecture-index generation, release-scope and whitespace checks
  run on the changed files. No dependency versions or checksum pins changed.
- Full Android unit, lint, APK/security and API 34/36 motion checks must pass on the
  published final head, together with Required verification, before merge.
- Physical ASUS/ROG device performance, speech providers, local MCP servers and
  GPU/NPU qualification remain unmeasured. No absolute quality score is claimed.
