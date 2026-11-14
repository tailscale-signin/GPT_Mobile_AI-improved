# Gateway 17 local validation and release qualifications

The changes are prepared on the existing PR #1122 branch,
`fix/github-context-and-voice-reliability-2026-11-14`, after reconciling remote
commits through `aad56e7f6b9bbac05ca905907cdd627e2bb36244`. Remote CI must qualify
the final published revision before merge. These local results do not replace it.

## Observed local results

| Check | Observed result |
| --- | --- |
| `bash scripts/validate-local.sh fast` with the pinned Gateway environment | Passed: 508 host tests, policy/schema/dependency/secret checks, Android regex and resource checks. |
| `python gateway/check_gateway.py` after repository-map reconciliation | Passed: 79 isolated Python suites, 1,084 tests with five expected platform/environment skips, plus seven browser tests. |
| Dedicated benchmark regressions | 40 passed, including packaged-source provenance. |
| Graph-index worker and update integration | 22 graph worker tests and four installer maintenance tests passed after the repository-map reconciliation. |
| Authentication and reporting suites | 105 passed after final reporter reconciliation. |
| Focused Kotlin report/status tests | 61 passed against current changed production sources and cached surrounding classes. |
| Expanded Kotlin graph/diagnostic reconciliation | 92 assertions passed; one pre-existing retained-checkpoint integration test remained blocked by cached host collaborator ABI mismatch. Full Android CI is required. |
| Focused Android Gateway capability decoder | Nine passed, including real v17 fixture compatibility and rejected future/malformed versions. |
| Changed Kotlin formatting | ktlint 1.3.1 passed for changed diagnostic/status/capability sources. |
| Durability stress | 50,000 events, 782 commits, restart and digest-expiry assertions passed. |
| Source integrity | Gateway package hashes and app source inventory regenerated. All 42 navigation PNG index blobs verified byte-for-byte against the originals after setting binary attributes. |

The complete Gateway run had five expected platform/environment skips, including
the two native desktop acceptance tests on this display-less Linux host. Windows
CI must execute the native tests and capture the normal and minimum-size pages.

The local full Android entry point passed its resource/model preflight but was
blocked downloading Gradle 9.8.0 with `Network is unreachable`. No local full
Android build, lint, emulator or device success is claimed. The focused Kotlin
harnesses are deliberately narrower evidence.

The complete Gateway rerun passed after the repository-map reconciliation. The
later voice and reporter commits did not change Gateway runtime behavior. Full
Android and Windows qualification still requires the published revision's CI.

## Merge requirements

Observed main ruleset 27005122 requires `Required verification` and
`Analyze (java-kotlin)` from GitHub Actions, with the branch up to date. It grants
no bypass. The user also requires all applicable triggered checks to be green:
Gateway stability on Ubuntu/Windows and Python 3.12/3.13, Windows installer,
Android acceptance, diagnostic audit, review APK, Marketplace and Kotlin lint.
The repair branch's unsigned release job is not applicable under its existing
release-branch/manual condition; a skipped release build is not release evidence.

Finish PR metadata changes before the final wait: its `edited` and draft-state
events restart PR Validation and cancel older producers. Re-read the live head,
base, checks, reviews and rules before merging. Missing, cancelled, failed or
incomplete required producers must not be described as green.

## Remaining environment qualifications

- No selected-model inference benchmark or Docker candidate execution was run
  here. Host fixture calibration validates the evaluator's declared cases;
  actual model scores require a configured local digest-pinned sandbox image.
- The paired workload compares serial and concurrent HTTP scheduling. Native
  inference batching, GPU execution, cache state and model artifact identity are
  not inferred from configuration or HTTP concurrency.
- Coding results cover 12 repair problems and four integrated capstones. Deep
  mode has 100 trials, including repeated seeded variants, rather than 100 unique
  coding problems or a live GitHub integration score.
- Physical audio, microphone contention, device layout/performance and private
  provider incidents require observations from their original environments.
- The hosted reporter has no deployment workflow in this repository. Its
  operator must deploy the compatible service separately and observe
  `GITHUB_PREREQUISITE_EVIDENCE_V1`, `ACCEPTANCE_RECOVERY_EVIDENCE_V1` and
  `GITHUB_GRAPH_POLICY_EVIDENCE_V1` and `ISSUE_RECOVERY_CODES_V1` before
  claiming those fields are accepted. An older service preserves approved report
  bytes and returns an update-required state before submission.

The [27-issue disposition ledger](gateway17-issue-dispositions.json) records the
specific code fix, bounded recovery or diagnostic closure basis for each report.
Diagnostic closure does not establish the cause of an unreproduced incident.
