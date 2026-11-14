# Chat, voice and remaining report repair — November 14, 2026

## Scope and source identity

All changes use PR #1089 and its existing branch, `fix/session-conflict-and-transport-reporting-2026-11-13`, targeting `main`. The intake main revision was `61083c2468a118eaa15f0f855ff8118e95b21d22`. The implementation reconciles the existing saved-work receipt fix at `71ec060f7899fe03d1a792fd832100e66dac4dfa` and the concurrent benchmark/reporting changes at `48d807e95b4ff5cd06a9d0155b288ddcb01f1182` and `4bd7f737a6efd6f2ae9957555e05ec982519e2ae`, `29eeba43c286ffde4fed26e64be092749503638e` and `335c25e311239bb38bb731b4ac5cd858d8e1be98`.

The GitHub branch inventory returned 747 branches with 704 distinct heads. There was one open PR and no open non-PR issues at intake and subsequent pre-publication checks. Historical branch refs are retained; repair work is consolidated on the one existing working branch as requested. This inventory is not a claim that every historical branch independently builds or has been rewritten.

Two supplied exports were read in full. Their contents and private endpoint, model, prompt, session and account identifiers are not checked into the repository.

| Input | Bytes | SHA-256 |
| --- | ---: | --- |
| `app-diagnostics-2992951956134468049.jsonl` | 11,726,003 | `1253331ed1fc068462ade4a64d2cb1429f0fd704cb820e1ab69894d6075da083` |
| `benchmarks-1794622835861.json` | 20,555,303 | `9a3becb0583df0e3be641d0214e81f8ee0dc2311dcd7b69e5a93d6f983b720af` |

## Diagnostic export accounting

All 6,382 JSONL records parsed: 1,805 events, 4,575 continuation records, one duplicate-reference batch and one export summary. The export reports 207 observed failure records grouped into 188 correlated unresolved incidents, two failed run attempts and 13 failed model attempts reported as not dispatched. Its 6,407 represented source occurrences reconcile with its retained source occurrences. These populations overlap; the export explicitly leaves the independent defect count unknown.

| Observation | Disposition and next evidence |
| --- | --- |
| GitHub transient network failures and timeouts | Preserve bounded read-attempt, unanswered-read, HTTP-response, transport-kind and cooldown-stop observations already added by this PR. A failed DNS lookup or disconnected private route cannot be repaired from retained text alone. A new affected-device report must establish the actual route and response outcome without replaying an uncertain write. |
| Acceptance criteria and pending remote work | Keep distinct approval, input, live-evidence and continuation blockers, including pending remote continuations. A report of unmet criteria is not proof of an independent implementation defect or permission to fabricate execution evidence. |
| Context capacity rejection | Retain prompt, safety, output and context limits plus tool-schema, tool-result and attachment reserves. The original request exceeded the observed capacity; code does not silently increase a model's real context or remove required instructions. |
| Saved conversation conflicts | Keep active job kind, conversation revision and bounded pending-tool count. Recovery remains attached to the original owned job and its retained tool results; it does not start a replacement mutation to evade a conflict. |
| Missing command receipts | Distinguish the gateway's exact `command_receipt_unavailable` contract from a generic 404. Both command and unknown-conversation routes have regression coverage. Missing receipts keep uncertain work fenced. |
| Unsupported explicit reasoning modes | Keep strict requested-mode admission. A benchmark now selects only the reasoning modes required by its frozen plan, so an unrelated saved mode cannot block an all-Off Quick run. Unsupported requested modes still require a compatible model/control. |
| 133 compiler method-size observations naming `ChatScreen` | Extract chat follow, layout-observation and voice presentation work into smaller components. These warnings are not evidence of 133 independent crashes. Actual frame-time improvement remains a device measurement. |
| Frame timing observations | Preserve existing bounded phase, deadline, refresh, memory, GC and sampled-operation evidence. Source changes and host tests cannot certify that all recorded phone stalls have disappeared. |

The issue work already recorded in [the session repair checkpoint](session-conflict-and-transport-reporting-2026-11-13-task.json) and [the gateway admission register](gateway-admission-reporting-2026-11-13.md) remains part of this PR. Diagnostic-only closure means the next report can carry the missing evidence; it does not mean a historical private-service or device incident was reproduced and fixed.

## Benchmark export accounting

The export contains 24 runs and 615 case rows: 10 passed, 13 terminal errors and 592 dependency skips. Eleven separate Full readiness probes failed. Propagated failure groups cover 340 reasoning-Off unsupported cases, 115 malformed response-format cases, 100 selected-model-not-loaded cases and 50 terminal-job continuation cases. These four groups explain all 605 non-passing case rows; a dependency skip is not a separately executed model failure.

The two embedded app-audit records contain 54 and 116 rows. Their combined outcomes are 71 PASS, 18 BLOCKED because a plugin is disabled, 24 UNAVAILABLE because a plugin is not installed and 57 UNTESTED. Neither record contains a FAIL outcome. Missing plugin execution, model benchmarks, external harness receipts and physical-device measurements remain explicit coverage gaps. The two observed network capability probes passed.

| Repair | Resulting contract |
| --- | --- |
| Provider response format | Convert a bare schema to the provider's supported response-format envelope while retaining its schema. Existing named or strict envelopes, including GitHub claim contracts, keep their constraints. |
| Frozen-plan readiness | Derive required reasoning modes from the actual frozen cases and use the same policy when making each request. Exact-model availability and verified routing remain required. |
| Dispatch and admission evidence | An allocated request identity or reasoning error string does not establish dispatch. A metadata/admission HTTP error does not become a generation HTTP result. HTTP delivery, request admission and model execution remain separate observations. |
| Benchmark coverage and scoring | Do not grade an absent answer, convert a dependency skip into an execution or infer successful generation from an HTTP response. Retain unknown evidence where the export cannot establish the event. |
| Shared exports | Preserve the concurrent redaction of endpoint hosts/paths, batch endpoints, profile instructions and credentials in normal exports. Explicit private-detail export and retained-private export continue to require the existing user choice. |

Provider envelope behavior was checked against the [llama.cpp server contract](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md) and [OpenRouter structured-output contract](https://openrouter.ai/docs/guides/features/structured-outputs). These sources establish the envelope shape, not live compatibility of the user's selected model.

## Chat interaction contract

1. Increase the GitHub top fade by three percentage points of the stable app-window height. The expanded header adds its measured growth once; collapsing returns to the increased baseline. Insets and animation use the same measured geometry.
2. Keep the retired active preview immediately above the new active preview. Presentation order follows activation, while source event IDs and timestamps retain evidence chronology. Updating a counter does not create a new activation or reading-time budget.
3. Reaching the real bottom by a user drag or fling activates the same follow intent as the down-arrow action in shared chat. Upward input detaches immediately. A layout or keyboard change alone cannot silently reattach.
4. While GitHub follow is active, give every newly rendered visual text line 1,500 ms. Three new lines receive 4,500 ms. New output appends time; it does not shorten a segment already in progress. Width/font/inset reflow changes distance without charging old text again. Detaching cancels the backlog; reattaching establishes a current baseline.

## Voice interaction and reporting contract

The voice screen uses the active theme, a restrained state halo, 33 input bars driven by observed microphone level, a fixed 96 dp microphone control and accessible side controls. Captions show the complete current phrase with theme-color emphasis and a full-transcript action. Motion respects reduced-motion, lifecycle and frame-pressure conditions; captions do not pretend to have word-level playback timestamps.

`PREPARING` separates synthesis/queueing from the observed playback start. Android speech uses its utterance-start signal; Kokoro uses consumed playback frames. Playback-owner and session-generation fences reject stale callbacks after stop, cancellation, replacement or close. The phase blocks microphone reentry and remains cancellable.

The closed diagnostic contract accepts `voice_phase=PREPARING`, leaves first-audio fields unavailable until observed, and negotiates `VOICE_PLAYBACK_PHASE_V1` before hosted submission. An older reporter leaves the approved payload saved with update-required status; it does not alter approved bytes or silently discard the phase. Native GitHub reporting keeps its existing selected account and approval behavior.

## Validation and acceptance

The companion [task checkpoint](chat-voice-design-and-remaining-reports-2026-11-13-task.json) records the actual validation commands and outcomes. Generated architecture and source inventories are refreshed after integration. Merge requires the current head/base pair, the complete required-check set, repository rules, resolved review state and GitHub mergeability; a successful subset or a cancelled run is insufficient.

Physical-device gesture, IME, rotation, large-font, audio-start, interruption and frame-time checks still require an installed matching APK. Private gateway/model availability and hosted reporter deployment require their corresponding live systems. These are not promoted to passed by source inspection, host policy tests or unrelated CI results.
