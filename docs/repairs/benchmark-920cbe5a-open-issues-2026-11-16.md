# Benchmark 920cbe5a and live issue intake — November 16, 2026

## Scope and evidence limits

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`; active draft PR: [#1349](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/1349), branch `fix/post-1299-incident-diagnostics-2026-11-15`.
- Live issue search snapshot: **98 open reports** across **28 error-code families**; approved JSON payloads parseable: **98/98**. Snapshot is not a durable barrier; new issues may appear immediately afterward.
- Reports primarily originate from Android build 141 and **do not prove the draft PR head fails the same way**. Do not mark them completed from titles, merges or test assumptions alone.
- User-supplied Gateway 17 coding benchmark: 12 planned, 11 recorded cases, 10 finalized at interruption, 5 FAILED, 4 ERROR, 1 BLOCKED, 1 INTERRUPTED, and no valid final score. The original summary's 10-recorded/2-pending projection was stale after the 11th case was persisted.
- Changes in this PR include terminal coverage recalculation, privacy-safe benchmark phase/error receipts, duplicate cancellation receipt removal, and regression tests. CI and live-device acceptance remain mandatory.

## Complete open issue inventory by recorded failure code

| Code | Open | Issue IDs | Observed stages | Cases missing causal evidence |
|---|---:|---|---|---:|
| `GITHUB_TASK_INCOMPLETE` | 15 | [#1468](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1468), [#1466](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1466), [#1448](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1448), [#1419](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1419), [#1434](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1434), [#1430](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1430), [#1393](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1393), [#1392](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1392), [#1385](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1385), [#1359](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1359), [#1357](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1357), [#1356](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1356), [#1342](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1342), [#1347](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1347), [#1312](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1312) | claim_extraction, acceptance_verification, UNRECORDED | 15 |
| `CHAT_FAILED` | 14 | [#1460](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1460), [#1457](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1457), [#1452](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1452), [#1451](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1451), [#1441](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1441), [#1418](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1418), [#1428](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1428), [#1360](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1360), [#1343](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1343), [#1346](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1346), [#1335](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1335), [#1327](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1327), [#1326](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1326), [#1311](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1311) | UNRECORDED, answer_review, final_synthesis | 14 |
| `INVALID_ARGUMENTS` | 11 | [#1459](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1459), [#1456](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1456), [#1437](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1437), [#1442](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1442), [#1425](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1425), [#1424](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1424), [#1426](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1426), [#1417](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1417), [#1394](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1394), [#1377](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1377), [#1363](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1363) | tool_precheck | 0 |
| `GITHUB_EVIDENCE_REQUIRED` | 9 | [#1465](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1465), [#1446](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1446), [#1403](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1403), [#1422](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1422), [#1421](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1421), [#1423](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1423), [#1407](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1407), [#1384](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1384), [#1373](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1373) | acceptance_verification, tool_precheck | 6 |
| `VOICE_PLAYBACK_FAILED` | 6 | [#1455](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1455), [#1454](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1454), [#1453](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1453), [#1328](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1328), [#1415](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1415), [#1411](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1411) | voice_playback | 6 |
| `TOOL_FAILURE` | 5 | [#1467](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1467), [#1445](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1445), [#1444](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1444), [#1406](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1406), [#1405](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1405) | tool_precheck | 0 |
| `GITHUB_TRANSIENT_NETWORK` | 5 | [#1386](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1386), [#1382](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1382), [#1379](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1379), [#1375](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1375), [#1351](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1351) | UNRECORDED, tool_precheck | 4 |
| `DNS_RESOLUTION_FAILED` | 4 | [#1383](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1383), [#1376](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1376), [#1371](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1371), [#1370](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1370) | dns | 0 |
| `GITHUB_TOOL_CONTEXT_BUDGET` | 3 | [#1410](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1410), [#1408](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1408), [#1321](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1321) | provider_admission | 3 |
| `PROVIDER_RATE_LIMITED` | 2 | [#1464](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1464), [#1458](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1458) | model_invocation | 0 |
| `SAVED_VIEW_UNAVAILABLE` | 2 | [#1463](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1463), [#1366](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1366) | tool_precheck | 0 |
| `READ_DEADLINE` | 2 | [#1462](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1462), [#1391](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1391) | tool_execution | 0 |
| `DISPATCH_POLICY_CHANGED` | 2 | [#1450](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1450), [#1447](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1447) | tool_precheck, provider_admission | 1 |
| `REPOSITORY_GRAPH_UNAVAILABLE` | 2 | [#1429](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1429), [#1323](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1323) | tool_execution | 0 |
| `CONNECTION_ABORTED` | 2 | [#1380](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1380), [#1374](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1374) | response_stream | 0 |
| `GITHUB_PAGINATION_INCOMPLETE` | 2 | [#1354](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1354), [#1350](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1350) | UNRECORDED | 2 |
| `TIMEOUT_OUTCOME_UNKNOWN` | 1 | [#1461](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1461) | tool_execution | 0 |
| `SELECTED_MODEL_NOT_LOADED` | 1 | [#1449](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1449) | provider_admission | 0 |
| `TOOL_PROTOCOL_INVALID_FRAME` | 1 | [#1435](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1435) | provider_generation | 0 |
| `GITHUB_PR_HEAD_CHANGED` | 1 | [#1432](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1432) | UNRECORDED | 1 |
| `VOICE_MICROPHONE_BUSY` | 1 | [#1412](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1412) | capture_read | 1 |
| `REASONING_ON_UNSUPPORTED` | 1 | [#1409](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1409) | provider_admission | 0 |
| `GITHUB_CIRCUIT_OPEN` | 1 | [#1381](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1381) | UNRECORDED | 1 |
| `APP_ERROR` | 1 | [#1355](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1355) | UNRECORDED | 1 |
| `GATEWAY_CAPABILITY_UNAVAILABLE` | 1 | [#1345](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1345) | connection_preflight | 1 |
| `RECONCILIATION_REQUIRED` | 1 | [#1324](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1324) | tool_reconciliation | 1 |
| `CONTEXT_CAPACITY_EXCEEDED` | 1 | [#1305](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1305) | provider_admission | 1 |
| `BUDGET_RESTRICTED` | 1 | [#1300](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1300) | delivery | 0 |

## Required repairs or enhanced diagnostic data by family

- **GITHUB_TASK_INCOMPLETE (15)** — Persist the exact unmet criterion, branch-rule/protection observation source, required operation, blocked capability, and replay-safe checkpoint; treat explicit unprotected branches separately from permission failures.
- **CHAT_FAILED (14)** — Capture a typed original cause before final synthesis, transport completion, provider finish reason, last tool identity, and whether any answer bytes were received.
- **INVALID_ARGUMENTS (11)** — Capture schema version/digest, operation, field names/types, failing JSON pointer, precheck vs dispatched state, and a sanitized corrective schema hint.
- **GITHUB_EVIDENCE_REQUIRED (9)** — Retain unmet acceptance criteria, head/base SHA hashes, checked sources and freshness, required check names, and a verified vs unobserved distinction.
- **VOICE_PLAYBACK_FAILED (6)** — Capture focus-change sequence, TTS failure enum, pronunciation fallback availability, audio queue state, sink write errors, and end-to-end first-audio receipts.
- **TOOL_FAILURE (5)** — Capture tool action/schema hashes, rejection stage, canonical code, remote outcome, and retry/reauth decision without raw argument values.
- **GITHUB_TRANSIENT_NETWORK (5)** — Keep DNS/TLS/HTTP stage, retry-after, endpoint category, observed reconnect and idempotent restart decision.
- **DNS_RESOLUTION_FAILED (4)** — Capture resolver exception category, network type, DNS attempt count, target-host hash, and successful recovery evidence.
- **GITHUB_TOOL_CONTEXT_BUDGET (3)** — Record exact context tokens by tool schemas/result previews, reserved answer budget, compaction count, and excluded tool names.
- **PROVIDER_RATE_LIMITED (2)** — Store upstream provider route, model hash, original HTTP 429, retry-after, scoped cooldown key and verified fallback-route identity. Never cool the whole OpenRouter catalog without evidence.
- **SAVED_VIEW_UNAVAILABLE (2)** — Record whether view_cursor was issued, its owner/scope/source binding, mismatch vs expired state, and a safe reissue/recreate instruction.
- **READ_DEADLINE (2)** — Capture operation deadline, elapsed, dispatch observed, remote acceptance certainty, and safe resume deadline.
- **DISPATCH_POLICY_CHANGED (2)** — Capture selected policy revision, last accepted operation, current policy fingerprint and whether a replan can safely resume.
- **REPOSITORY_GRAPH_UNAVAILABLE (2)** — Capture graph backend configuration, permission/test status, fetch stage, and tool fallback availability.
- **CONNECTION_ABORTED (2)** — Retain stream last frame/sequence, received bytes, provider request hash, reconnect eligibility, and continuation/checkpoint certainty.
- **GITHUB_PAGINATION_INCOMPLETE (2)** — Capture cursor lineage, pages received, missing expected page, duplicate cursor hash, API limit and recovery hint.
- **TIMEOUT_OUTCOME_UNKNOWN (1)** — Capture request ID, dispatched/remote acceptance, idempotency/reconciliation key, retry deadline and last known operation receipt.
- **SELECTED_MODEL_NOT_LOADED (1)** — Capture expected model artifact hash, service state, preflight eligibility, configured route and whether model load is in progress.
- **TOOL_PROTOCOL_INVALID_FRAME (1)** — Capture grammar/frame type, bounded invalid-frame class, preceding valid event count, transport completion and parser state.
- **GITHUB_PR_HEAD_CHANGED (1)** — Capture expected/current head SHA hashes, check suite SHA, invalidated operation and safe replan path.
- **VOICE_MICROPHONE_BUSY (1)** — Record audio focus, capture process owner, recorder state/format, contention and retry eligibility.
- **REASONING_ON_UNSUPPORTED (1)** — Log selected model reasoning capability, requested mode, server rejection and fallback policy decision.
- **GITHUB_CIRCUIT_OPEN (1)** — Record circuit scope, trigger and next probe time, original cause and half-open recovery result.
- **APP_ERROR (1)** — Capture original typed error before the generic wrapper, subsystem, last operation and privacy-safe exception class.
- **GATEWAY_CAPABILITY_UNAVAILABLE (1)** — Capture capability catalog digest, Gateway version, missing tool type, active profile and connection health.
- **RECONCILIATION_REQUIRED (1)** — Capture immutable operation ID, observed remote state, expected head SHA and reason replay is fenced.
- **CONTEXT_CAPACITY_EXCEEDED (1)** — Capture context cap, token estimate by category, compaction and reserved schema/finalization budget.
- **BUDGET_RESTRICTED (1)** — Capture declared and effective budgets, phase, reserved verification tokens and next admissible action.

## Cross-cutting benchmark repair backlog

- Four `ValueError` cases (`pagination-1`, `idempotency-1`, `dependencies-1`, `budget-1`) are ungraded. The original export only reported an exception class. New future receipts include phase and closed error code; reproduce on the same runtime and inspect safe diagnostic fields.
- `streaming-1` failed 5/6 functional hidden checks and detected 0/2 calibrated bugs with model-authored tests. Add malformed/split frame and boundary replay vectors.
- `migration-1` emitted invalid patch JSON, then achieved 2/5 hidden checks. Enforce complete JSON output and transactional/idempotent migration.
- `patches-1` passed 6/6 hidden checks but remains blocked because caller input preservation cannot be independently verified. Do not promote a same-process candidate-controlled flag to authoritative proof.
- `ci-1`, `retries-1`, `permissions-1`, `evidence-1` have incomplete contracts or interrupted verification. Require per-SHA check binding, saved cooldown deadlines, explicit permission model, and truthful unknown evidence.
- The original cancel receipts were `requested`, not confirmed shutdown. Reconcile worker/job state and container absence before replay.
- Native batching, loaded model artifact SHA, GPU, runtime build and live GitHub execution were not independently proven in the benchmark. Do not invent these capabilities.

## Specific incident distinctions and criteria

- [#1464](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1464) contains an actual upstream HTTP 429 with a ~60-second retry delay. Handle the scoped provider route, never the whole OpenRouter catalog by default.
- [#1463](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1463) shows `view_cursor_issued=false` and `saved_view_restore_state=IDENTITY_REJECTED`: do not retry the invented/invalid cursor; explicitly create a fresh saved view.
- [#1466](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1466) shows 6/7 acceptance criteria verified, with an explicit branch-protection 404 classified as not protected. Reconcile branch rules, authorization and acceptance policy; never infer permission denial or success from the 404 alone.
- [#1455](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1455) reports `PRONUNCIATION_UNAVAILABLE` despite first audio being observed: preserve the specific TTS subfailure and provide a fallback rather than claiming all playback failed identically.

## Issue closure and merge gate

1. For each issue, bind repair to a concrete source commit and automated regression reproducing its original failure category.
2. Verify the exact PR head with relevant CI checks; validate connected service/device effects when needed. Missing/unmeasured evidence remains unknown.
3. Add an issue comment with fix commit, test receipts, and release/device limitations. Close only after the evidence resolves that reported issue, or use an explicit duplicate/not-planned resolution that does not falsely claim a repair.
4. Keep all changes on PR #1349. Merge only after draft review readiness, applicable required checks green, no outstanding review blockers and clean exact-head acceptance.
