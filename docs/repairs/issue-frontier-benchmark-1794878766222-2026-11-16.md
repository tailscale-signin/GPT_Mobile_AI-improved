# GitHub issue frontier and benchmark evidence — November 16, 2026

**Repository:** `tailscale-signin/GPT_Mobile_AI-improved`  
**Existing draft PR:** [#1349](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/1349)  
**Observed open-issue snapshot:** 96 reports / 28 public error-code families.  
**Source builds:** 0.9.66.7/2e8d6e7f0262, 0.9.66.7/278527e30e3c, 0.9.66.7/712d7d92a6b8, 0.9.66.7/72a2cd088487, 0.9.66.7/482eb81645e4, 0.9.66.7/a0777e0598bc, 0.9.66.7/1d4256a6eb2e, 0.9.66.7/e9610259b723. The reporter build predates the current draft branch.  
**Attached benchmark:** `benchmarks-1794878766222.json` (`gptmobile.benchmarks.v2`).  

This is a read-only source-based triage ledger, not evidence that an issue is fixed. No issue should be labeled resolved merely because the code contains a proposed patch. No credentials, raw exception strings, user input, hashed identifiers or user-private reports are copied here. The issue frontier may grow after this snapshot.

## Root-cause triage

| Area | Open reports |
|---|---:|
| Verification | 26 |
| Causal diagnostics | 19 |
| Tool contract | 18 |
| Transport | 17 |
| Provider/model | 9 |
| Voice | 7 |

### 28 public error-code families

| Code | Reports | Repair or recapture required |
|---|---:|---|
| `GITHUB_TASK_INCOMPLETE` | 14 | Bind acceptance criterion to exact-head receipt; reserve verification calls; resume unmet requirements. |
| `CHAT_FAILED` | 14 | Capture producer stage, structured original cause and sanitized exception fingerprint; replay on current build. |
| `INVALID_ARGUMENTS` | 11 | Align model-visible schema and typed validator; return field-level correction; test conflicting read limits. |
| `GITHUB_EVIDENCE_REQUIRED` | 9 | Persist unmet criterion IDs and exact linked evidence; distinguish not-tested from failed. |
| `VOICE_PLAYBACK_FAILED` | 6 | Capture pronunciation or audio-focus subcode, PCM/engine/audio-focus state; device replay. |
| `GITHUB_TRANSIENT_NETWORK` | 5 | Record network phase and dispatch receipt; retry safe reads with jitter, checkpoint others. |
| `TOOL_FAILURE` | 4 | Capture originating tool schema hash, action, typed failure code and source exception fingerprint. |
| `DNS_RESOLUTION_FAILED` | 4 | Retain transport/DNS stage and provider endpoint scope; wait and resume only safe requests. |
| `GITHUB_TOOL_CONTEXT_BUDGET` | 3 | Budget token components independently; compact tools and reserve results/verification. |
| `PROVIDER_RATE_LIMITED` | 2 | Use confirmed 429 retry-after and exact upstream provider/model cooldown key; persist wait. |
| `SAVED_VIEW_UNAVAILABLE` | 2 | Capture issue/restore/identity state and recovery operation; prohibit cross-scope cursor replay. |
| `READ_DEADLINE` | 2 | Record operation deadline and queued vs backend time; retry confirmed read-only calls. |
| `DISPATCH_POLICY_CHANGED` | 2 | Re-evaluate permission and action policy before each operation; return actionable policy diff. |
| `REPOSITORY_GRAPH_UNAVAILABLE` | 2 | Distinguish 5xx, transport and indexed-result issues; reconcile before unsafe retry. |
| `CONNECTION_ABORTED` | 2 | Retain response-stream acceptance and final token/terminal receipts; resume safely. |
| `GITHUB_PAGINATION_INCOMPLETE` | 2 | Preserve page cursor, count, completion receipt and live verification epoch. |
| `TIMEOUT_OUTCOME_UNKNOWN` | 1 | Reconcile remote operation using durable idempotency key before re-dispatch. |
| `SELECTED_MODEL_NOT_LOADED` | 1 | Perform exact selected-profile gateway model readiness and load handshake. |
| `TOOL_PROTOCOL_INVALID_FRAME` | 1 | Record bounded invalid frame class, provider protocol and parser stage; no raw payload. |
| `GITHUB_PR_HEAD_CHANGED` | 1 | Re-read new head and check provenance before applying or verifying operations. |
| `VOICE_MICROPHONE_BUSY` | 1 | Capture record-source ownership, audio permission state and capture session stage. |
| `REASONING_ON_UNSUPPORTED` | 1 | Preflight reasoning capability; use explicit supported mode without silent override. |
| `GITHUB_CIRCUIT_OPEN` | 1 | Persist endpoint-specific circuit-open deadline and safe retry/recovery state. |
| `APP_ERROR` | 1 | Capture originating producer/exception cause and separate generic fallback from actual code. |
| `GATEWAY_CAPABILITY_UNAVAILABLE` | 1 | Record gateway version/tool manifest and exact failed capability check. |
| `RECONCILIATION_REQUIRED` | 1 | Persist pending operation and exact receipts; never repeat uncertain mutations. |
| `CONTEXT_CAPACITY_EXCEEDED` | 1 | Measure input, schema, output, memory and attachment budgets; reduce request before dispatch. |
| `BUDGET_RESTRICTED` | 1 | Capture budget dimension and verified cost accounting; reserve final verification. |

## Full issue-by-issue frontier

All 96 open issues retrieved from GitHub on this audit. **No report has current-build reproduction or deployed closure verification within this snapshot.**

| Issue | Observed code | Failure stage | Safe source subcode | Disposition |
|---|---|---|---|---|
| [#1466](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1466) | `GITHUB_TASK_INCOMPLETE` | `acceptance_verification` | `MATCHING_OBSERVATION_INCOMPLETE` | Capture missing cause; verify on new build |
| [#1465](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1465) | `GITHUB_EVIDENCE_REQUIRED` | `acceptance_verification` | `UNSUPPORTED_CLAIMS` | Capture missing cause; verify on new build |
| [#1464](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1464) | `PROVIDER_RATE_LIMITED` | `model_invocation` | `upstream_provider_shared_pool` | Verify live/current-build regression |
| [#1463](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1463) | `SAVED_VIEW_UNAVAILABLE` | `tool_precheck` | `IDENTITY_REJECTED` | Verify live/current-build regression |
| [#1462](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1462) | `READ_DEADLINE` | `tool_execution` | `—` | Verify live/current-build regression |
| [#1461](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1461) | `TIMEOUT_OUTCOME_UNKNOWN` | `tool_execution` | `—` | Verify live/current-build regression |
| [#1460](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1460) | `CHAT_FAILED` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1459](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1459) | `INVALID_ARGUMENTS` | `tool_precheck` | `TOOL_ARGUMENT_SCHEMA_REJECTED` | Verify live/current-build regression |
| [#1458](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1458) | `PROVIDER_RATE_LIMITED` | `model_invocation` | `upstream_provider_shared_pool` | Verify live/current-build regression |
| [#1457](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1457) | `CHAT_FAILED` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1456](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1456) | `INVALID_ARGUMENTS` | `tool_precheck` | `TOOL_ARGUMENT_SCHEMA_REJECTED` | Verify live/current-build regression |
| [#1455](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1455) | `VOICE_PLAYBACK_FAILED` | `voice_playback` | `PRONUNCIATION_UNAVAILABLE` | Capture missing cause; verify on new build |
| [#1454](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1454) | `VOICE_PLAYBACK_FAILED` | `voice_playback` | `PRONUNCIATION_UNAVAILABLE` | Capture missing cause; verify on new build |
| [#1453](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1453) | `VOICE_PLAYBACK_FAILED` | `voice_playback` | `PRONUNCIATION_UNAVAILABLE` | Capture missing cause; verify on new build |
| [#1452](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1452) | `CHAT_FAILED` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1451](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1451) | `CHAT_FAILED` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1450](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1450) | `DISPATCH_POLICY_CHANGED` | `tool_precheck` | `—` | Verify live/current-build regression |
| [#1449](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1449) | `SELECTED_MODEL_NOT_LOADED` | `provider_admission` | `—` | Verify live/current-build regression |
| [#1448](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1448) | `GITHUB_TASK_INCOMPLETE` | `acceptance_verification` | `MATCHING_OBSERVATION_INCOMPLETE` | Capture missing cause; verify on new build |
| [#1447](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1447) | `DISPATCH_POLICY_CHANGED` | `provider_admission` | `—` | Capture missing cause; verify on new build |
| [#1446](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1446) | `GITHUB_EVIDENCE_REQUIRED` | `acceptance_verification` | `UNSUPPORTED_CLAIMS` | Capture missing cause; verify on new build |
| [#1445](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1445) | `TOOL_FAILURE` | `tool_precheck` | `—` | Verify live/current-build regression |
| [#1444](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1444) | `TOOL_FAILURE` | `tool_precheck` | `—` | Verify live/current-build regression |
| [#1442](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1442) | `INVALID_ARGUMENTS` | `tool_precheck` | `TOOL_ARGUMENT_SCHEMA_REJECTED` | Verify live/current-build regression |
| [#1441](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1441) | `CHAT_FAILED` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1437](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1437) | `INVALID_ARGUMENTS` | `tool_precheck` | `TOOL_ARGUMENT_SCHEMA_REJECTED` | Verify live/current-build regression |
| [#1435](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1435) | `TOOL_PROTOCOL_INVALID_FRAME` | `provider_generation` | `—` | Verify live/current-build regression |
| [#1434](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1434) | `GITHUB_TASK_INCOMPLETE` | `acceptance_verification` | `MATCHING_OBSERVATION_INCOMPLETE` | Capture missing cause; verify on new build |
| [#1432](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1432) | `GITHUB_PR_HEAD_CHANGED` | `unknown` | `GITHUB_PR_HEAD_CHANGED` | Capture missing cause; verify on new build |
| [#1430](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1430) | `GITHUB_TASK_INCOMPLETE` | `acceptance_verification` | `—` | Capture missing cause; verify on new build |
| [#1429](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1429) | `REPOSITORY_GRAPH_UNAVAILABLE` | `tool_execution` | `GRAPH_HTTP_REJECTED` | Verify live/current-build regression |
| [#1428](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1428) | `CHAT_FAILED` | `answer_review` | `—` | Capture missing cause; verify on new build |
| [#1426](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1426) | `INVALID_ARGUMENTS` | `tool_precheck` | `TOOL_ARGUMENT_SCHEMA_REJECTED` | Verify live/current-build regression |
| [#1425](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1425) | `INVALID_ARGUMENTS` | `tool_precheck` | `INVALID_ARGUMENTS` | Verify live/current-build regression |
| [#1424](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1424) | `INVALID_ARGUMENTS` | `tool_precheck` | `SAVED_VIEW_CONTEXT_MISMATCH` | Verify live/current-build regression |
| [#1423](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1423) | `GITHUB_EVIDENCE_REQUIRED` | `acceptance_verification` | `UNSUPPORTED_CLAIMS` | Capture missing cause; verify on new build |
| [#1422](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1422) | `GITHUB_EVIDENCE_REQUIRED` | `tool_precheck` | `—` | Verify live/current-build regression |
| [#1421](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1421) | `GITHUB_EVIDENCE_REQUIRED` | `acceptance_verification` | `UNSUPPORTED_CLAIMS` | Capture missing cause; verify on new build |
| [#1419](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1419) | `GITHUB_TASK_INCOMPLETE` | `acceptance_verification` | `MATCHING_OBSERVATION_INCOMPLETE` | Capture missing cause; verify on new build |
| [#1418](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1418) | `CHAT_FAILED` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1417](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1417) | `INVALID_ARGUMENTS` | `tool_precheck` | `TOOL_ARGUMENT_SCHEMA_REJECTED` | Verify live/current-build regression |
| [#1415](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1415) | `VOICE_PLAYBACK_FAILED` | `voice_playback` | `AUDIO_FOCUS_LOST` | Capture missing cause; verify on new build |
| [#1412](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1412) | `VOICE_MICROPHONE_BUSY` | `capture_read` | `CAPTURE_READ` | Capture missing cause; verify on new build |
| [#1411](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1411) | `VOICE_PLAYBACK_FAILED` | `voice_playback` | `AUDIO_FOCUS_DENIED` | Capture missing cause; verify on new build |
| [#1410](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1410) | `GITHUB_TOOL_CONTEXT_BUDGET` | `provider_admission` | `—` | Capture missing cause; verify on new build |
| [#1409](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1409) | `REASONING_ON_UNSUPPORTED` | `provider_admission` | `—` | Verify live/current-build regression |
| [#1408](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1408) | `GITHUB_TOOL_CONTEXT_BUDGET` | `provider_admission` | `—` | Capture missing cause; verify on new build |
| [#1407](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1407) | `GITHUB_EVIDENCE_REQUIRED` | `acceptance_verification` | `UNSUPPORTED_CLAIMS` | Capture missing cause; verify on new build |
| [#1406](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1406) | `TOOL_FAILURE` | `tool_precheck` | `—` | Verify live/current-build regression |
| [#1405](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1405) | `TOOL_FAILURE` | `tool_precheck` | `—` | Verify live/current-build regression |
| [#1403](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1403) | `GITHUB_EVIDENCE_REQUIRED` | `tool_precheck` | `—` | Verify live/current-build regression |
| [#1394](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1394) | `INVALID_ARGUMENTS` | `tool_precheck` | `TOOL_ARGUMENT_SCHEMA_REJECTED` | Verify live/current-build regression |
| [#1393](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1393) | `GITHUB_TASK_INCOMPLETE` | `acceptance_verification` | `—` | Capture missing cause; verify on new build |
| [#1392](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1392) | `GITHUB_TASK_INCOMPLETE` | `claim_extraction` | `NO_MATCHING_CLAIMS` | Capture missing cause; verify on new build |
| [#1391](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1391) | `READ_DEADLINE` | `tool_execution` | `—` | Verify live/current-build regression |
| [#1386](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1386) | `GITHUB_TRANSIENT_NETWORK` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1385](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1385) | `GITHUB_TASK_INCOMPLETE` | `acceptance_verification` | `UNSUPPORTED_CLAIMS` | Capture missing cause; verify on new build |
| [#1384](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1384) | `GITHUB_EVIDENCE_REQUIRED` | `acceptance_verification` | `UNSUPPORTED_CLAIMS` | Capture missing cause; verify on new build |
| [#1383](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1383) | `DNS_RESOLUTION_FAILED` | `dns` | `—` | Verify live/current-build regression |
| [#1382](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1382) | `GITHUB_TRANSIENT_NETWORK` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1381](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1381) | `GITHUB_CIRCUIT_OPEN` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1380](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1380) | `CONNECTION_ABORTED` | `response_stream` | `—` | Verify live/current-build regression |
| [#1379](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1379) | `GITHUB_TRANSIENT_NETWORK` | `tool_precheck` | `RESTORED` | Verify live/current-build regression |
| [#1377](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1377) | `INVALID_ARGUMENTS` | `tool_precheck` | `INVALID_ARGUMENTS` | Verify live/current-build regression |
| [#1376](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1376) | `DNS_RESOLUTION_FAILED` | `dns` | `—` | Verify live/current-build regression |
| [#1375](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1375) | `GITHUB_TRANSIENT_NETWORK` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1374](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1374) | `CONNECTION_ABORTED` | `response_stream` | `—` | Verify live/current-build regression |
| [#1373](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1373) | `GITHUB_EVIDENCE_REQUIRED` | `tool_precheck` | `—` | Verify live/current-build regression |
| [#1371](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1371) | `DNS_RESOLUTION_FAILED` | `dns` | `—` | Verify live/current-build regression |
| [#1370](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1370) | `DNS_RESOLUTION_FAILED` | `dns` | `—` | Verify live/current-build regression |
| [#1366](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1366) | `SAVED_VIEW_UNAVAILABLE` | `tool_precheck` | `BODY_MISSING` | Verify live/current-build regression |
| [#1363](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1363) | `INVALID_ARGUMENTS` | `tool_precheck` | `TOOL_ARGUMENT_SCHEMA_REJECTED` | Verify live/current-build regression |
| [#1360](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1360) | `CHAT_FAILED` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1359](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1359) | `GITHUB_TASK_INCOMPLETE` | `unknown` | `UNSUPPORTED_CLAIMS` | Capture missing cause; verify on new build |
| [#1357](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1357) | `GITHUB_TASK_INCOMPLETE` | `acceptance_verification` | `—` | Capture missing cause; verify on new build |
| [#1356](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1356) | `GITHUB_TASK_INCOMPLETE` | `acceptance_verification` | `—` | Capture missing cause; verify on new build |
| [#1355](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1355) | `APP_ERROR` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1354](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1354) | `GITHUB_PAGINATION_INCOMPLETE` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1351](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1351) | `GITHUB_TRANSIENT_NETWORK` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1350](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1350) | `GITHUB_PAGINATION_INCOMPLETE` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1347](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1347) | `GITHUB_TASK_INCOMPLETE` | `acceptance_verification` | `MATCHING_OBSERVATION_INCOMPLETE` | Capture missing cause; verify on new build |
| [#1346](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1346) | `CHAT_FAILED` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1345](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1345) | `GATEWAY_CAPABILITY_UNAVAILABLE` | `connection_preflight` | `—` | Capture missing cause; verify on new build |
| [#1343](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1343) | `CHAT_FAILED` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1342](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1342) | `GITHUB_TASK_INCOMPLETE` | `acceptance_verification` | `—` | Capture missing cause; verify on new build |
| [#1335](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1335) | `CHAT_FAILED` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1328](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1328) | `VOICE_PLAYBACK_FAILED` | `voice_playback` | `PRONUNCIATION_UNAVAILABLE` | Capture missing cause; verify on new build |
| [#1327](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1327) | `CHAT_FAILED` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1326](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1326) | `CHAT_FAILED` | `unknown` | `—` | Capture missing cause; verify on new build |
| [#1324](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1324) | `RECONCILIATION_REQUIRED` | `tool_reconciliation` | `—` | Capture missing cause; verify on new build |
| [#1323](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1323) | `REPOSITORY_GRAPH_UNAVAILABLE` | `tool_execution` | `—` | Verify live/current-build regression |
| [#1321](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1321) | `GITHUB_TOOL_CONTEXT_BUDGET` | `provider_admission` | `—` | Capture missing cause; verify on new build |
| [#1312](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1312) | `GITHUB_TASK_INCOMPLETE` | `acceptance_verification` | `—` | Capture missing cause; verify on new build |
| [#1311](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1311) | `CHAT_FAILED` | `final_synthesis` | `—` | Capture missing cause; verify on new build |
| [#1305](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1305) | `CONTEXT_CAPACITY_EXCEEDED` | `provider_admission` | `—` | Capture missing cause; verify on new build |
| [#1300](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1300) | `BUDGET_RESTRICTED` | `delivery` | `—` | Verify live/current-build regression |

## Benchmark evidence and committed repair

The supplied export has four run records. The 14-case selected smoke audit emitted 115 sample rows in one canceled run (the first case appeared twice), and two 114-case runs recorded 113 dependency skips after first-case cancellation. Zero model/GitHub operations executed. These measurements cannot be interpreted as 114 broken GitHub tools.

- `BenchmarkBatchRunner.kt`: initialize the run with the accepted batch case manifest before attempting admission, and retain it through the no-checkpoint profile failure fallback. Preserve `USER_SKIP` instead of misclassifying it as a provider fault.
- `BenchmarkStore.kt`: canonicalize duplicate logical case rows at checkpoint; physical execution attempts have independent durable receipts.
- `BenchmarkBatchRunnerTest.kt`: cover the 14-case preflight interruption and explicit user skip without suite expansion.
- `BenchmarkStoreTest.kt`: cover duplicate logical rows without double counting attempts.

Source repair commits: [505648c](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/505648ca7b5de4b846eadc61d87d0221b767c7ff), [61ddffe](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/61ddffe15468439abe73e6dca2dde54b2d3701cf), [4018505](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/4018505a67aff700bdd788f92c4180b1ad00c806), [1a6c013](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/1a6c013589d7755d02cd97816d8985a338b141a8).

## Required acceptance before closing issues or merging

1. Exact-head PR validation, Android acceptance, Gateway, Windows, security and Kotlin checks all green; fix any test timeouts or dependency-resolution errors.
2. Physical Android execution of the 14-case smoke audit and 114-action offline suite with immutable manifest, unique case IDs, correct cancellation provenance and nonzero actual admitted execution.
3. A separately authorized live GitHub read/CI/PR integration probe; in-memory fixture passes are not evidence of remote permissions or mutations.
4. Live provider 429 + model readiness, GitHub saved-view/pagination and uncertain-mutation recovery testing; do not invent network evidence.
5. Device voice playback, audio-focus, microphone, first-caption and pronunciation replays with source diagnostic receipts.
6. Close only reports with an exact-build/source-bound verification receipt and linked corrective commit; if a fault cannot be reproduced, first deploy and verify the enhanced diagnostic capture, document a new safe recapture path, then close as explicitly diagnostic-limited if that disposition is appropriate.
