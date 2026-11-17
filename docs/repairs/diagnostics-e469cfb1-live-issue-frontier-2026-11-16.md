# Diagnostics e469cfb1 — live issue reconciliation and review-baseline remediation

**Date:** 2026-11-16 (America/Toronto)
**Repository:** `tailscale-signin/GPT_Mobile_AI-improved`
**Sole open draft PR:** [#1349](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/1349)
**Branch:** `fix/post-1299-incident-diagnostics-2026-11-15`
**Source archive:** `diagnostics-e469cfb1-57d3-426d-80ca-f3190f9b8f81-part-1-2a6eaafb-70ec-4209-a35f-26fd67d787cb.zip`; private archive content is not copied here.

## Evidence boundaries

- Live connector search returned **100 open app-generated reports** in **28 public failure-code families**. This is an observation, not an immutable issue frontier.
- All observed reports came from **Android build 141** (older than this draft PR) and cannot certify that the draft PR's source either reproduces or resolves the issue.
- The attached diagnostics export is marked captured/completed but not complete historical coverage. Retained run outcomes: EVIDENCE_MISSING (1), PARTIAL (2), BLOCKED (2).
- Affected review session selected `main` without a baseline; repeated review attempts asked for a baseline after spending read allowance.
- One prior run lost a saved `github_read` page; another observed branch-protection HTTP 404. Do not treat an inaccessible protection endpoint as proof of no required rules.
- In the live issue snapshot **22 reports** have no recorded source failure stage; these require producer-side recapture rather than inferred attribution.

## Implemented code correction on this PR

- `GitHubReviewComparison.plan`: when the selected branch is the observed default branch and no literal baseline is supplied, return `BASELINE_REQUIRED` **before** issuing an open-PR inventory request. Preserve the previously observed, exact matching PR head/base exception, explicit selectors, and parent-SHA suggestion only when observed.
- `GitHubReviewBaselineTest`: add regressions for immediate baseline prompting without a PR inventory and for an already verified exact PR head/base.
- Scope: avoids wasted calls, repeated truncated inventories and misleading source-review progress; **does not** infer baseline or change a user's selected branch.
- Test status: repository CI must execute the Kotlin test at the final exact PR head; source edit alone is not production proof.

## Live open issue frontier

| Failure code | Open reports | Issue numbers |
|---|---:|---|
| `GITHUB_TASK_INCOMPLETE` | 16 | [#1312](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1312), [#1342](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1342), [#1347](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1347), [#1356](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1356), [#1357](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1357), [#1359](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1359), [#1385](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1385), [#1392](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1392), [#1393](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1393), [#1419](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1419), [#1430](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1430), [#1434](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1434), [#1448](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1448), [#1466](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1466), [#1468](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1468), [#1470](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1470) |
| `CHAT_FAILED` | 14 | [#1311](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1311), [#1326](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1326), [#1327](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1327), [#1335](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1335), [#1343](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1343), [#1346](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1346), [#1360](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1360), [#1418](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1418), [#1428](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1428), [#1441](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1441), [#1451](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1451), [#1452](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1452), [#1457](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1457), [#1460](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1460) |
| `INVALID_ARGUMENTS` | 11 | [#1363](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1363), [#1377](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1377), [#1394](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1394), [#1417](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1417), [#1424](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1424), [#1425](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1425), [#1426](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1426), [#1437](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1437), [#1442](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1442), [#1456](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1456), [#1459](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1459) |
| `GITHUB_EVIDENCE_REQUIRED` | 9 | [#1373](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1373), [#1384](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1384), [#1403](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1403), [#1407](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1407), [#1421](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1421), [#1422](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1422), [#1423](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1423), [#1446](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1446), [#1465](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1465) |
| `VOICE_PLAYBACK_FAILED` | 6 | [#1328](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1328), [#1411](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1411), [#1415](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1415), [#1453](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1453), [#1454](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1454), [#1455](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1455) |
| `GITHUB_TRANSIENT_NETWORK` | 5 | [#1351](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1351), [#1375](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1375), [#1379](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1379), [#1382](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1382), [#1386](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1386) |
| `TOOL_FAILURE` | 5 | [#1405](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1405), [#1406](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1406), [#1444](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1444), [#1445](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1445), [#1467](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1467) |
| `DNS_RESOLUTION_FAILED` | 4 | [#1370](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1370), [#1371](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1371), [#1376](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1376), [#1383](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1383) |
| `GITHUB_TOOL_CONTEXT_BUDGET` | 3 | [#1321](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1321), [#1408](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1408), [#1410](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1410) |
| `PROVIDER_RATE_LIMITED` | 3 | [#1458](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1458), [#1464](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1464), [#1469](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1469) |
| `CONNECTION_ABORTED` | 2 | [#1374](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1374), [#1380](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1380) |
| `DISPATCH_POLICY_CHANGED` | 2 | [#1447](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1447), [#1450](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1450) |
| `GITHUB_PAGINATION_INCOMPLETE` | 2 | [#1350](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1350), [#1354](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1354) |
| `READ_DEADLINE` | 2 | [#1391](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1391), [#1462](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1462) |
| `REPOSITORY_GRAPH_UNAVAILABLE` | 2 | [#1323](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1323), [#1429](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1429) |
| `SAVED_VIEW_UNAVAILABLE` | 2 | [#1366](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1366), [#1463](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1463) |
| `APP_ERROR` | 1 | [#1355](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1355) |
| `BUDGET_RESTRICTED` | 1 | [#1300](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1300) |
| `CONTEXT_CAPACITY_EXCEEDED` | 1 | [#1305](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1305) |
| `GATEWAY_CAPABILITY_UNAVAILABLE` | 1 | [#1345](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1345) |
| `GITHUB_CIRCUIT_OPEN` | 1 | [#1381](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1381) |
| `GITHUB_PR_HEAD_CHANGED` | 1 | [#1432](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1432) |
| `REASONING_ON_UNSUPPORTED` | 1 | [#1409](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1409) |
| `RECONCILIATION_REQUIRED` | 1 | [#1324](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1324) |
| `SELECTED_MODEL_NOT_LOADED` | 1 | [#1449](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1449) |
| `TIMEOUT_OUTCOME_UNKNOWN` | 1 | [#1461](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1461) |
| `TOOL_PROTOCOL_INVALID_FRAME` | 1 | [#1435](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1435) |
| `VOICE_MICROPHONE_BUSY` | 1 | [#1412](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1412) |

## Measured diagnostic gaps by stage

| Source-owned stage | Count |
|---|---:|
| `tool_precheck` | 23 |
| `UNRECORDED` | 22 |
| `acceptance_verification` | 18 |
| `provider_admission` | 7 |
| `voice_playback` | 6 |
| `tool_execution` | 5 |
| `dns` | 4 |
| `model_invocation` | 3 |
| `claim_extraction` | 2 |
| `response_stream` | 2 |
| `task_budget` | 1 |
| `provider_generation` | 1 |
| `answer_review` | 1 |
| `capture_read` | 1 |
| `connection_preflight` | 1 |
| `tool_reconciliation` | 1 |
| `final_synthesis` | 1 |
| `delivery` | 1 |

## Required recapture before closing cases as repaired

1. On the revised build, retain originating exception **at its catch**: stable permitted cause class, stage, operation, schema contract hash and originating invocation/run IDs as privacy-safe hashes. Never infer an HTTP outcome from a neighboring log.
2. For tool precheck errors, retain the exact validator rule, sanitized field names/types, whether dispatch occurred, and correction proposal. For saved evidence, retain issued cursor lineage and scoped immutable source identity.
3. For provider 429, retain the real HTTP status, confirmed upstream limit source, model/pinned route, Retry-After, and the exact cooldown scope. Test isolation from unrelated routes.
4. For GitHub and gateway retries, preserve wait deadline, execution session/step, idempotency key, remote acceptance certainty and replay permission. Never blindly retry uncertain writes.
5. For evidence/acceptance failures, bind each unmet criterion to a current exact head SHA and observed status-policy origin; do not interpret inaccessible branch protection as absence.
6. For voice failures, capture typed TTS, audio focus/PCM sink, playback queue and mic contention states. Confirm on the originally affected Android device.

## Closure gates

An issue may be closed **completed** only after it is reproduced and fixed or its corresponding behavior is observed passing on the exact deployed build. An exact same-incident duplicate may be closed **duplicate** with canonical issue linkage. If cause is still unknown, retain a canonical blocker and/or close only as **not planned / diagnosis limited** with a durable recapture plan, never as fixed. Same public code alone does not prove a duplicate. Never submit raw private diagnostic archive data into GitHub issues.

## Exact-head merge gates

Keep PR #1349 as the single working branch. Final Android, Windows/Gateway, security, lint, integration and required test checks must succeed on the same final head. Reconfirm review state, approvals and merge authorization before merging. Historic green checks are not reusable for a newer head.
