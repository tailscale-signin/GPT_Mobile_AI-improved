# Partial diagnostics 35ac528c — issue-by-issue engineering triage

Date: 2026-11-15. Target: PR #1299, `fix/post-1203-diagnostics-2026-11-15`.

## Evidence scope and non-negotiable limits

The uploaded staged export is a valid first archive part, not a complete device trace. Its manifest reports 90% **stage** collection, `captureCompleted=false`, `capturedFully=false`, and `NEW_CAPTURE_REQUIRED`. Both critical and recent application-log stages report `TRACKING_DISABLED_NO_RETAINED_LOGS`. Re-enabling logs and reproducing the event is required for a new trace; the original missing logs cannot be reconstructed. Run records, tool receipts, failure capsules, and workspace summaries remain useful, but app-wide issue-report history does not by itself prove that a report belongs to the selected conversation.

Reports #1313–#1320 originate from Android build 141 / version 0.9.66.7 at source commit `b3b57670b7bc2c205f324023b5e736860e92cf52`. They **do not** describe the present PR head. Historical 404s, remote refusal, provider limits, inaccessible host services, and absent fixtures are not evidence of an application source defect or a completed repair. In particular, source changes do not authorize writes or unblock remote permissions.

## All 44 open issues and their dispositions

| Issues | Failure evidence | Corrective action / qualification |
| --- | --- | --- |
| #1276, #1282, #1286, #1288 | Four distinct dispatched `read_url` HTTP 404 / `SOURCE_UNAVAILABLE` responses | Preserve each actual remote response, do not retry the same missing URL automatically. Existing PR admission/reporting corrections require focused regression and live verification |
| #1277, #1278, #1279, #1280, #1281, #1283, #1284, #1285, #1287, #1289, #1290, #1291, #1292 | Thirteen `read_url` prechecks rejected as `REPEAT_LIMIT`; no remote call was sent | Retain the rejected call, the initial refusal and its proof-bound repeat-admission history without fabricating a dispatched HTTP request. PR includes regression coverage |
| #1302 | Separate `github_read` tool precheck `TOOL_FAILURE` | Retain tool name, schema revision, exact failed constraint and dispatch=false; use the PR's bounded correction diagnostics, and reproduce with the matching tool catalog |
| #1293, #1310 | Branch-protection/status requests returned HTTP 404; acceptance remains incomplete | Distinguish permission-denied/admin-only API from absent branch policy; never declare required checks satisfied without authorized rule observation |
| #1298, #1312, #1316 | Integration acceptance lacks owned fixture approval, ready reads and complete capability coverage despite spare calls; some remote continuations pending | Carry checkpoint and per-criterion status forward, ask for explicit fixture permission, and resume only unmet verified work. Do not invent fixtures or mark blocked operations as tested |
| #1294 | `github_ci runs` rejected undeclared `conclusion` argument | Report precise unknown key and allowed schema; propose an explicit bounded correction without silently dispatching |
| #1295, #1296 | `github_read` argument constraints rejected before dispatch | Verify actual operation schema and exact bounds; preserve original rejected result |
| #1303 | Unknown additional field in a request including public `limit`, `query` | Capture declared-vs-unknown fields separately and retain the selected operation identity; avoid claiming which field was wrong without its complete schema |
| #1306, #1307 | `github_search` schema rejects one out-of-range field, with successive correction observations | Carry exact failed field, requested bounds, and correction count; cap repairs and retain the original result |
| #1297 | `github_ci logs` invalid arguments co-occur with 32/32 call-budget exhaustion | Display the invalid constraint first and the exhausted budget as a separate limitation |
| #1300 | Dispatched `github_ci workflows` returned HTTP 200 but exceeded a 24,576-byte result allowance at 6/24 calls | Use bounded, authorized result projection/pagination before model delivery; don't reinterpret size restriction as provider or call exhaustion |
| #1301 | Inventory pagination `APP_ERROR` before execution | Persist catalog page cursor/epoch, failure stage, and next recovery step, avoiding unverified full-catalog claims |
| #1304, #1305 | OpenRouter conservative 4,096-token admission; prompt 10,431/10,449 plus safety 2,607/2,612 | Compact before invocation and carry an immutable count of prompt, safety, output, and tool-result reserves. Conservative policy is not proof of actual provider capacity |
| #1313 | Local LLAMA conservative 4,096-token admission; prompt 4,975 plus safety 1,243; no model request dispatched | Same source-bound admission budgeting; ensure preflight respects selected local context rather than trying a known-too-large request |
| #1308, #1309 | OpenRouter HTTP 429 with observed retry-after guidance and no remote acceptance | Honor the provider cooldown for safe retry, budget retries and preserve idempotency/accepted-request boundaries |
| #1311 | Local LLAMA final synthesis `CHAT_FAILED`, unrecognized source code | Classify at the producing boundary; preserve original source code/state and a sanitized exception fingerprint; don't guess cause |
| #1317, #1318 | OpenRouter `CHAT_FAILED` captured only as `CHAT_REPOSITORY_ERROR_VALUE`, source code absent | Trace the upstream `ApiState.Error` producer and retain original provider failure before the terminal catch; capture a new reproducible failure with application logs |
| #1314 | `GATEWAY_CAPABILITY_UNAVAILABLE` at LLAMA connection preflight, `dispatched=false` | Check gateway reachability, selected profile, identity and capability negotiations; distinguish a connection interruption from an unsupported endpoint |
| #1315 | Dispatched `repository_graph status` failed; retained `GRAPH_TRANSPORT_INTERRUPTED` with connection exception | Verify gateway listener/address and host connection, then retry only after recovery. Report network/service error without claiming graph support was absent |
| #1316 | 3 of 5 acceptance criteria verified; 67 of 256 calls used; 16 pending remote continuations; 74 unmet capability criteria and fixture blockers | Resume saved checkpoint by requirement/authority; separate pending external continuation, missing fixtures and actual operation failure |
| #1319, #1320 | Kokoro `PRONUNCIATION_UNAVAILABLE`; #1319 began playback, #1320 failed before first audio | Preserve unknown-word rejection (no letter-spelling or silent omission). Route to **offline-only Android TTS** when Kokoro has not begun to play; do not repeat audible audio. Keep captions and explicit errors if no offline system voice is installed |

Where an issue appears in two rows above, #1316 is elaborated separately and **counted only once** in the 44-issue total. All 44 original issue IDs are retained; duplicate reports are not assumed to share an execution run.

## New voice repair in this PR

- `KokoroVoiceOutput.synthesize`: pronunciations that cannot be projected may request fallback **only before actual audio starts**. A playback that has started continues to report a typed failure and retains captions; replaying the whole response would duplicate speech.
- `VoiceChatScreen`: route the existing fallback to `AndroidVoiceAudio.speakOffline`, which uses installed on-device voices only and excludes voices marked `isNetworkConnectionRequired`. There is no silent network TTS fallback. Missing offline voice remains a visible failure and intact transcript.
- `VoicePluginAudioTest`: exercise the streaming fallback once, failure after speech has started without replay, and strict-Kokoro mode when explicitly required.
- The existing lexicon and pronunciation validators remain intact. Do not invent phonemes, send text to an online TTS service, or suppress unknown words.

## Required next verification

1. Source checks: run Android compilation, focused voice tests, reporting tests, Gateway/Windows workflows, Kotlin lint and CodeQL for the **exact newly published PR revision**; a prior SHA or a cancelled workflow is not proof.
2. On Android 16 device, test a real absent ordinary-name pronunciation before first audio, a late unknown word after playback began, installed vs unavailable offline system voices, and speech while streaming. Record first-audio latency, underruns, completion/stop callbacks, sound output, and transcript integrity without storing spoken text.
3. For gateway graph and capability, test listener online/offline, wrong host, host GitHub disconnected, missing permissions and a successful authorized graph request. Record transport state and do not infer a successful read from preflight.
4. For context and remote rate limits, reproduce with a pinned selected-model profile and actual capacity receipt; demonstrate blocked-before-dispatch vs successful budgeted continuation. Do not replay accepted external writes.
5. For integration acceptance, supply authorized disposable fixtures, run the saved unmet criteria, and retain per-criterion signed revision-bound evidence.
6. Enable application logging (with privacy-safe capture), reproduce unknown `CHAT_FAILED` reports and export a **new** staged archive. Do not claim this archive was resumable.

**Disposition rule:** Leave GitHub issues open until the exact fix is built and regression-qualified for the affected pathway; use a linked commit and matching test/CI evidence when closing. Unsupported external operations or unknown-cause historical reports stay open for follow-up, not falsely marked repaired.

## Current-PR implementation follow-up (2026-11-15)

The following changes have been published to PR #1299 on the same branch, without broadening the source or permission authority:

- **#1315: bounded graph transport recovery:** `GatewayGraphRepository.executeForMap` retries only a revision-bound `status` read following an `IOException` that is not TLS-related. It revalidates the selected profile/account/branch and coroutine activity before the single replay. Index, cancel, HTTP rejection and uncertain mutation requests remain non-replayable. `GatewayGraphRepositoryTest` covers a transient status recovery and permission revocation before the retry.
- **#1313, plus #1304/#1305 planning class:** `LocalContextPlanner.preflightSafetyReserve` retains a minimum 256 tokens and, for larger contexts, reserves one fifth of declared context in advance of optional history and tool selection. This corresponds to the final JSON admission's proportional estimate. It cannot create a larger serving allocation or recover a missing metadata/credential/route capability. `LocalContextPlannerTest` includes a 4096-token budget fixture. An actual selected-model inference remains a separate live requirement.
- **#1317/#1318: code-less provider errors:** the OpenAI-compatible stream adapter preserves a typed `PROVIDER_INVOCATION_FAILED` and a bounded `provider_error_code_state=ABSENT` observation rather than losing provenance in a repository terminal boundary. No historical cause is inferred from these old reports.
- **#1319/#1320: pronunciation:** unknown ordinary words are not silently omitted or read as spelled-out letters. Prior to actual Kokoro output, a protected offline Android TTS fallback may read the complete reply. If audio has already started or an installed offline voice is absent, the transcript and explicit failure are retained; no network speech is selected.
- **Missing logs in this archive:** the manual issue report provides an **explicit**, separately consented application-log tracking toggle. A report's `include diagnostics` checkbox never enables logging. Past omitted records remain omitted, private-session collection remains excluded, and submission still requires approval.

### Verification and issue-closure conditions

The graph retry, context-budget change and report UI need the current exact-head Android/test/ktlint/CodeQL qualification. Focused test source is not a successful runtime receipt until CI completes. Require actual selected-model and gateway route tests for #1313–#1316, a real on-device audio/voice-pack observation for #1319/#1320, and privacy-safe application-log reproduction for #1317/#1318. Service outages, auth scope, inaccessible branch protection and unavailable disposable fixtures cannot be repaired by repository-only changes and must not be labeled fixed. Leave affected issues open until there is evidence for the specific outcome.

No service tokens, permissions, GitHub rulesets, models, or user's machine configuration were changed by this PR.
