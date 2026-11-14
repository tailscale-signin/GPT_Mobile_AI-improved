# Chat, voice and remaining report repair — November 14, 2026

## Scope and source identity

All changes use PR #1089 and its existing branch, `fix/session-conflict-and-transport-reporting-2026-11-13`, targeting `main`. The intake main revision was `61083c2468a118eaa15f0f855ff8118e95b21d22`. The implementation reconciles the existing saved-work receipt fix at `71ec060f7899fe03d1a792fd832100e66dac4dfa` and concurrent changes recorded in the companion task checkpoint, including the provider/reporting repairs, the pinned Markdown cell token at `858579f8a7ed078366828d5bae9b5d6415efbb00`, and the backup restoration and regression corrections at `4e4f9959d1aacb4ceca2964270f9471b05c5a650`.

The GitHub branch inventory returned 747 branches with 704 distinct heads. There was one open PR and no open non-PR issues at intake and the first validation checks. Issues #1090 and #1091 arrived during hosted validation and were added to this same repair pass. Historical branch refs are retained; repair work is consolidated on the one existing working branch as requested. This inventory is not a claim that every historical branch independently builds or has been rewritten.

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
| Unsupported explicit reasoning modes | Keep strict requested-mode admission. A benchmark now selects only the reasoning modes required by its frozen plan, so an unrelated saved mode cannot block an all-Off frozen plan. The current Quick manifest includes an intelligence batch and therefore still requires the saved reasoning mode used by those cases. Unsupported requested modes still require a compatible model/control. |
| 133 compiler method-size observations naming `ChatScreen` | Extract chat follow, layout observation, voice presentation and turn-presentation scans into smaller components. A mandatory APK check measures actual chat/voice DEX method definitions and fails at ART's 16,383-code-unit warning boundary. These warnings are not evidence of 133 independent crashes. Actual frame-time improvement remains a device measurement. |
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
| Restored benchmark history | Complete backups retain their frozen endpoints. Redacted reports restore history and interrupted-attempt evidence, and persist which target or delegation endpoints were redacted. Those remote profiles cannot dispatch against a substituted default or changed endpoint; a new benchmark accepts current settings. Fresh provider defaults and local runtimes retain their existing behavior. Invalid JSON produces a bounded error without attaching payload-bearing serializer exceptions. |

Provider envelope behavior was checked against the [llama.cpp server contract](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md) and [OpenRouter structured-output contract](https://openrouter.ai/docs/guides/features/structured-outputs). These sources establish the envelope shape, not live compatibility of the user's selected model.

## Issues received during validation

### #1090 — context capacity

The approved report from tested merge revision `e4d256dc56e2f90453a330f883cd795a6d1b46b1` records 10,461 estimated prompt tokens, 2,615 safety tokens, 4,096 output tokens and a 5,632-token context capacity. Input plus safety alone is 13,076 tokens. The observed pre-dispatch rejection is correct for that estimate; lowering output alone cannot make this input fit. No private model request was replayed or claimed to succeed.

The repair captures the actual capacity observation before output admission can throw, including route, configured and catalog ceilings when known, provenance and bounded observation age. It adds numeric system, history, current-request, tool-result, response-schema and serialization/template components alongside the existing tool-schema and reserve counts. Missing measurements remain absent. Ordinary compatible-provider chat also records its actual configured output setting before an admission failure, and the rejection wrapper retains the resolved output allowance rather than replacing it with an absent or larger original request. Guidance distinguishes an input that already exceeds capacity from a request with positive output headroom. Admission arithmetic and required input remain unchanged.

### #1091 — active conversation handoff

The approved report from the same revision records HTTP 409, `WAITING_FOR_CLIENT_TOOL`, `RETURN_CLIENT_TOOL_RESULTS`, a generation job and conversation revision 8. Source review found that the gateway's explicit `remote_acceptance=NO` was lost in the app's structured-error parser. The parser now preserves valid explicit acceptance and recovery observations. If the original command outcome is unknown, negative acceptance cannot erase that uncertainty or grant affirmative resume permission.

The gateway reports whether the principal-scoped pending tool set is identified, missing, empty, invalid or unavailable, plus an observed count, a hash of its canonical identity set and a bounded handoff sequence when present. The app distinguishes unreported evidence and compares the blocking job only with verified stream metadata. Server conversation ownership is never treated as ownership by the rejected Android run. The exact HTTP regression preserves the original waiting job, rejects the new turn without a new provider call or command receipt, and does not insert the rejected message into the transcript.

Both issues use the requested diagnostic-follow-up disposition for the original private-service incident. The repairs and new evidence do not establish that the historical request completed or that its pending tool results have been recovered. The matching gateway must be installed to emit the additional handoff evidence. Hosted submission negotiates `CAPACITY_ADMISSION_EVIDENCE_V1` and `CONVERSATION_CONFLICT_EVIDENCE_V1`; an older reporter keeps the approved payload saved with update-required status. The diagnostic byte cap remains 3,072, and approved payloads are not rewritten to fit an older service.

## Chat interaction contract

1. Increase the GitHub top fade by three percentage points of the stable app-window height. The expanded header adds its measured growth once; collapsing returns to the increased baseline. Insets and animation use the same measured geometry.
2. Keep the retired active preview immediately above the new active preview. Presentation order follows activation, while source event IDs and timestamps retain evidence chronology. A newer activation sequence can move an existing counter row to the active position without duplicating it. A same-line counter change does not receive another reading-time budget.
3. Reaching the real bottom by a user drag or fling activates the same follow intent as the down-arrow action in shared chat. Upward input detaches immediately. A layout or keyboard change alone cannot silently reattach.
4. While GitHub follow is active, give every newly rendered visual text line 1,500 ms. Three new lines receive 4,500 ms. New output appends time; it does not shorten a segment already in progress. Width/font/inset reflow changes distance without charging old text again. Detaching cancels the backlog; reattaching establishes a current baseline.

## Voice interaction and reporting contract

The voice screen uses the active theme, a restrained state halo, 33 input bars driven by observed microphone level, a fixed 96 dp microphone control and accessible side controls. Captions show the complete current phrase with theme-color emphasis and a full-transcript action. Motion respects reduced-motion, lifecycle and frame-pressure conditions; captions do not pretend to have word-level playback timestamps.

`PREPARING` separates synthesis/queueing from the observed playback start. Android speech uses its utterance-start signal; Kokoro uses consumed playback frames. Playback-owner and session-generation fences reject stale callbacks after stop, cancellation, replacement or close. The phase blocks microphone reentry and remains cancellable.

The closed diagnostic contract accepts `voice_phase=PREPARING`, leaves first-audio fields unavailable until observed, and negotiates `VOICE_PLAYBACK_PHASE_V1` before hosted submission. An older reporter leaves the approved payload saved with update-required status; it does not alter approved bytes or silently discard the phase. Native GitHub reporting keeps its existing selected account and approval behavior.

## Validation and acceptance

The companion [task checkpoint](chat-voice-design-and-remaining-reports-2026-11-13-task.json) records the actual validation commands and outcomes. Generated architecture and source inventories are refreshed after integration. Merge requires the current head/base pair, the complete required-check set, repository rules, resolved review state and GitHub mergeability; a successful subset or a cancelled run is insufficient.

The first complete Android unit results at `000c0c0fd1a694431f5bf0a47ac037c18c2d8f0f` contained 10,219 tests, six failures, zero errors and five skips. Both full unit jobs reported the same six cases: current Quick reasoning modes, redacted backup restoration, rendered-line timing, preview-position observation, real font wrapping and result-card copy. Corrections retain the behavior requirements: real native text measurement, an explicit animation duration scale, the exact 4,500 ms budget for three new lines, stable preview identity and immediate adjacency, and distinct execution accounting. CodeQL and both API 34 and API 36 acceptance jobs passed at that revision, including all three new voice cases; these outcomes do not qualify a later head automatically.

`scripts/check_chat_method_size.py` measures the actual `ChatScreen` and `ChatScreenContent` definitions plus all packaged chat/voice methods. References, accessors, default wrappers and lambdas cannot substitute for the required bodies. The report records APK SHA-256, CI source revision, the largest methods and every oversized method. Missing definitions and malformed or oversized inputs fail closed. Both UI Review and canonical PR validation publish the small report, including on a failed measurement. This static check does not establish device compilation or frame-time performance.

Physical-device gesture, IME, rotation, large-font, audio-start, interruption and frame-time checks still require an installed matching APK. Private gateway/model availability and hosted reporter deployment require their corresponding live systems. These are not promoted to passed by source inspection, host policy tests or unrelated CI results.
