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


## Chat preview and diagnostic follow-up — November 13, Toronto time

### Captured chat and evidence boundary

This follow-up uses the same PR #1089. Its initial head was
`4e4f9959d1aacb4ceca2964270f9471b05c5a650`; after the editing executor reset,
work was reconstructed on the live head
`7c43e3d2be494b414ba47244be8870dcf3d9b463`, preserving the intervening DEX
and admission-report improvements. Before publication, the nonoverlapping provider
adapter test correction at `78bea4854dfd940f8b6e4d01a6aded818b022ef3` was
fast-forwarded into this same branch. The concurrent source-generation repair at
`9ead17c5a2c9e32d505bf37757bbbe41228118cd` was also preserved before publishing.
The base remained
`61083c2468a118eaa15f0f855ff8118e95b21d22`.

The supplied archive is
`diagnostics-ebf8a1a4-adb3-4cd3-9719-4dcca30ac461-part-1-19213e44-82f0-4e5f-a2ac-4be55ba23526.zip`:
1,157,737 bytes, SHA-256
`d954884be3f09816da42ba1fe9920d082f1bd2d0952466541c1cee5262effc03`.
The ZIP and all 20 stage hashes/counts were verified before the executor reset.
All 10,424 physical JSONL rows parsed; 365 continuation chunks reconstructed
17 logical records. Physical rows, reconstructed records and source evidence
rows are different populations and are not added together. Raw exports and
private profile, endpoint, prompt and account data are not committed.

The export identifies app 0.9.66.7, build 141, debug commit
`e4d256dc56e2f90453a330f883cd795a6d1b46b1`, and selected chat 11. The relevant
integration attempt ran from 23:24:36 to 23:29:25 on November 13 in Toronto
(04:24:36–04:29:25 UTC on November 14). Its 56 tool receipts all succeeded;
the trace contains 109 HTTP reads and no mutations. The task outcome was
PARTIAL because 3/5 required acceptance criteria and 47/70 required capabilities
were verified, with 16 missing-input blockers and seven fixture-approval
requirements. It used 56 of 256 calls and retained 200. Those spare calls do not
authorize fixture mutations. Thirteen pending remote continuations belonged
to optional sampled inventories; their presence does not establish a required
pagination stop defect.

Two later engineering attempts were rejected with `VALIDATION_POLICY_REQUIRED`.
The export's stage high-watermarks differ: two run rows are retained, while the
later admission attempt also appears in latest state and journal evidence.
The original admission lost its typed prerequisite, recorded no failure receipt,
and selected the generic `RESUME_UNFINISHED_WORK` recovery. Empty message content
alone does not prove an invisible error: the UI separately displays the stored
run error.

Useful source anchors in the verified export include `recent-app-logs.jsonl`
row 6851 (acceptance counts), row 7778 (admission failure and wrong generic
recovery), row 7882 (gateway back ONLINE), `latest-state.jsonl` row 1 (missing
typed failure fields), and the selected integration run in `runs.jsonl`.
The immutable evidence remains the attached archive; row numbers refer to its
reconstructed stage files from the initial audit.

### Preview and summary findings

The selected assistant message retained 42 preview checkpoints and six summaries.
Twenty-three previews said that a result was available for review without
identifying the finding. The six summaries were deterministic, not additional
model calls, and appeared at approximately 45-second intervals. The last two
were nearly identical. The retained history is bounded, so the first retained
summary at +45 seconds is not proof that an immediate introduction was missing.

The useful facts already existed in the structured tool receipts:

| Source | Available fact | Improved public meaning |
| --- | --- | --- |
| `tool-receipts.jsonl` row 2 | Two open issues and one open pull request | State the returned counts without treating them as repository health. |
| Row 4 | 15 successful, 18 skipped, zero in progress and zero failed check results; complete captured inventory | Distinguish skipped checks from successful checks and inspection from executing validation. |
| Row 8 | 55 workflow definitions; complete captured inventory | Say that definitions were found; do not claim 55 workflows were executed or validated. |
| Row 50 | PR #1089: eight successful, one skipped, nine in progress, zero failed; incomplete captured inventory | Show pending work and the incomplete inventory; zero observed failures does not establish merge readiness. |
| `run-journal.jsonl` rows 519 and 559 | Repeated summary observations | Suppress a new periodic paragraph unless its public meaning changes. |
| Row 568 | Progress counter 47/71 versus terminal 47/70 required capabilities | Use the same required-capability population for both. |
| Row 574 | “Putting the response together.” after the report | Retire the exact automatic staging notice when the run stops, retaining facts and the partial report. |

The implementation consumes existing bounded `github.semantic-summary.v1`
objects tied to the operation and call. It does not parse or hash raw tool bodies
on the preview path and does not add model invocations. Saved observations are
labelled as saved, and malformed, unrelated, error or display-cursor results
cannot become new factual summaries. Unknown required-check policy remains an
explicit limitation. Failed, blocked and skipped observations retain their
meaning; a later “0 failed” count cannot displace an earlier actual failure.

The immediate introduction, three-second preview cadence and 45-second summary
cadence are retained. Consecutive equivalent previews are suppressed, but an
A → B → A activity sequence can reactivate A. Counters remain one persisted row
and use their last activation sequence for display order. Sorting happens before
adjacent preview coalescing, so an updated counter in an old storage slot cannot
hide the newest activity. The retired and active pair remain together. Existing
full-opacity active styling, real-bottom follow behavior and 1,500 ms per newly
rendered line are preserved.

The ticker stops after visible response text or a terminal outcome. A later tool
start or a new round can resume progress before terminal completion. Failed,
partial and interrupted runs remove only exact automatic staging notices;
factual evidence, public model prose, approval notices and the partial answer
remain. Duplicate summaries no longer increment the timeline revision when
nothing changed. Native tool results and their completion events now reach the
same progress observer, so numeric facts are available to the periodic summary.

### Admission, capacity and integration repairs

| Finding | Repair | Remaining operational requirement |
| --- | --- | --- |
| `VALIDATION_POLICY_REQUIRED` became a generic failure without a durable receipt | Preserve typed engineering prerequisite reason, phase, bounded policy state, blocker count and recovery action. Known unsent setup becomes BLOCKED with a failure receipt and `CONFIGURE_VALIDATION_POLICY`. UI explains the setup and avoids a competing generic regenerate action. | Configure the repository validation policy on the selected gateway. The code does not invent a policy or authorize execution. |
| Other known validation, acceptance, revision, runtime and access prerequisites had generic recovery | Map each supported prerequisite to its actual configuration, mirror, runtime or access repair. Keep unrecognized cases bounded and preserve uncertain prior execution. | Supply the named prerequisite; retry is offered only for verified unsent setup. |
| Planning used a 32,768-token fallback while final admission observed 5,632 | Reuse fresh, exact-model, verified-route capacity from the existing readiness check during primary planning. Intersect route, catalog and configured ceilings, retaining credential/network/epoch invalidation and the authoritative serialized admission check. | Required instructions and the complete authorized GitHub tool contract still must fit. If they do not, an earlier `GITHUB_TOOL_CONTEXT_BUDGET` or capacity rejection remains correct. No private request success is claimed. |
| Progress used a different capability denominator than acceptance | Count required, in-scope rows and actual acceptance PASS outcomes. Report verified, waiting, blocked and failed required capabilities consistently; conditional fixture coverage remains separate. | The captured 16 missing-input and seven approval requirements still need their actual inputs or authority. |
| Continuation advice did not account for all reserved work | Use the dispatcher's acquisition allowance, retaining verification and owned-fixture cleanup reserves. | An optional remaining inventory is not automatically required work or permission to mutate. |
| `CONVERSATION_CONFLICT` while an earlier job waited for client tool results | Preserve the existing richer explicit-acceptance and handoff diagnostics from the intervening PR commits. Keep exact job/profile/model/call binding and the original replay fence. | Recover the original owned tool results and return them through the verified handoff. This follow-up does not clear the waiting job or replay its mutations. |

For #1090, input plus safety was 10,461 + 2,615 = 13,076 tokens before the
4,096-token output request, against 5,632 observed capacity. Tool definitions
accounted for 4,414 estimated tokens. The final rejection was therefore correct;
using verified capacity earlier improves planning and diagnosis, not the physical
capacity of the server. The newer admission-evidence commit already records
capacity before output rejection, so no duplicate logging path was added here.

For #1092, `GITHUB_TASK_INCOMPLETE` correctly represents unmet requirements rather
than a failed GitHub HTTP operation. The repair makes the reason and next action
consistent across the preview, preparation report and terminal report. It does
not turn unexecuted fixture checks into passes or remove required coverage.

Three selected-model-not-loaded HTTP 503 readiness observations later recovered
to ONLINE. They are separate from the successful GitHub tool receipts. Eighty-six
saved-result GET requests returned HTTP 202 over approximately 5 minutes 50 seconds;
the export does not establish that they are the same job as the earlier conflict.
Existing polling coordination and Retry-After handling remain in place pending
caller/job/state evidence rather than changing retry behavior from timing alone.

The frame trace retained 31 windows, 8,304 frames, 138 janky frames and six frames
over 100 ms, with a maximum of 220.9 ms. Eighteen Choreographer warnings reported
708 skipped frames over overlapping intervals. These counts are not additive,
and no fatal crash or ANR was retained. The source and publication reductions
need matching-device measurements before claiming a frame-time improvement.

### Follow-up verification

Recovered source was revalidated after the executor reset and after reconciling
the nonoverlapping provider adapter fixture correction:

| Validation | Outcome |
| --- | --- |
| Canonical `bash scripts/validate-local.sh fast` | PASS: 507 repository Python tests, CI/schema/dependency/secret checks, 1,651 literal regexes (82 dynamic patterns skipped), 34 resource XML files, icons and provenance checks. |
| Progress/narration/presentation/activity projection | PASS: 71 current-source Kotlin tests. |
| Collector and streaming buffer | PASS: 22 production test methods, including partial terminal cleanup and both counter-reactivation cases. |
| Engineering prerequisites and recovery presentation | PASS: 27 focused Kotlin tests, including preservation of earlier/uncertain execution evidence. |
| Exact-route capacity/readiness/planning | PASS: 13 Kotlin tests; metadata/template calls are exercised without inference. |
| Integration checklist, preparation and report | PASS: 62 Kotlin tests against current production acceptance and authorization helpers. |
| Formatting and generated records | PASS: ktlint 1.3.1 for 36 changed Kotlin files; fresh architecture index and app inventory (2,555 files, 26 areas); diff and exact release-scope checks. |

Focused Kotlin harnesses
compile the changed production policy and presentation sources against lightweight
boundary fixtures; they do not substitute for the Android application build.
Final command outcomes and the published revision are recorded in the companion
repair checkpoint. Earlier local Android generation reached
`:app:kaptGenerateStubsDebugKotlin`, where the Gradle daemon disappeared without a
compiler diagnostic. That attempt is not a compilation or test pass.

Required GitHub verification must qualify the newly published head and current
base. Cancelled, missing, skipped or stale required jobs do not authorize merging.
Physical phone behavior, the live private gateway policy, pending handoff recovery
and actual model execution remain separate from host tests and hosted CI.

### Diagnostic audit source-generation heap follow-up

Before publishing the recovered edits, head
`78bea4854dfd940f8b6e4d01a6aded818b022ef3` failed the diagnostic audit's
[Android job](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43558764249/job/132337569673)
at `:app:kaptGenerateStubsDebugKotlin`. The log reports a 3 GiB maximum heap and
1 GiB metaspace, then `java.lang.OutOfMemoryError: Java heap space` in
`KaptStubConverter.KaptStub.writeMetadataIfNeeded` during FIR KAPT stub generation.
The build stopped after 5m32s, before its production compile and tests. This is
direct evidence of heap exhaustion; no Kotlin source diagnostic or failing test
was observed. The checkout was synthetic merge
`4f5dc84b0d76b4eafb43b0f0b006be80d55f2f80` against main
`61083c2468a118eaa15f0f855ff8118e95b21d22`.

Concurrent commit `9ead17c5a2c9e32d505bf37757bbbe41228118cd` implements the
repair in both the diagnostic audit and PR unit workflow. It separates Room
generation from annotation generation into distinct Gradle processes and
retains both logs. Room keeps the default 3 GiB heap and 1 GiB metaspace;
annotation generation uses the existing production profile of a 4 GiB heap and
768 MiB metaspace. Each process remains bounded below the repository's documented
7 GB runner memory budget. Kotlin and KSP stay in process with one Gradle worker;
global defaults, configuration-cache policy, the audit's 540-second process
deadlines and 10-minute step limits, the PR's 15-minute generation step limits
and all later gates remain intact.

This follow-up preserves that concurrent repair without adding another workflow
edit. Fresh successful generation on the final published revision is still
required; neither the change nor a pass on an earlier revision establishes a
current compilation or test pass. The evidence and validation state are retained
as a new follow-up in the existing session repair checkpoint.

Local checks passed: repository CI policy, policy checks against both upstream
workflow texts, YAML parsing, `bash -n` for all 43 run blocks, checkpoint JSON
parsing and `git diff --check`. `actionlint` was unavailable in the local tooling
and is not claimed as run.

## Retained acceptance scope for issue #1092

The approved report in [issue #1092](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/1092) came from tested merge `e4d256dc56e2f90453a330f883cd795a6d1b46b1`; its approved payload SHA-256 is `cf79531117299875f288501116340b2bc7aa72fb8f409ea3412290898af2a94e`. It recorded five required criteria, three verified and two remaining; 70 capabilities comprised 47 verified, 16 blocked and seven untested. Seven approval blockers and 16 input blockers remained. Thirteen remote continuations were retained while no continuation blocked acceptance. The bounded sample retained seven of 25 unmet criteria and accurately reported 18 omissions.

`task_kind=GENERAL` is an operation/answer category, not evidence that the ordinary-task checklist path was used. Source review found that full catalog coverage is selected by integration scope; ordinary tasks use observed capabilities. Likewise, retained remote pages can coexist with zero pagination blockers when the task requests bounded sampling. The historical report does not retain those two decisions, so neither a global catalog promotion defect nor a pagination defect is established by the available evidence. No historical private task was replayed or declared complete.

The new capture projects only the exact retained checkpoint fields:

| Approved diagnostic field | Retained source | Closed values |
| --- | --- | --- |
| `acceptance_capability_scope` | `test_checklist.coverage_scope` | `FULL_CATALOG`, `OBSERVED_CAPABILITIES` |
| `acceptance_pagination_mode` | `test_checklist.pagination_resume.mode` | `COMPLETE_INVENTORY_REQUESTED`, `BOUNDED_SMOKE_SAMPLE` |

Missing, malformed and unknown values remain absent; the reporter does not recompute scope from task text or a top-level lookalike. The maximum compact representation of both fields is 115 bytes. They receive priority before optional criterion-sample trimming within the unchanged 3,072-byte diagnostic limit. Aligned arrays and truthful omission counts are retained. No raw task, repository details, tool arguments or capability catalog is added. The matching Android and Python contracts permit these fields only in the acceptance area.

`ACCEPTANCE_SCOPE_EVIDENCE_V1` is required before hosted submission, including when a known field was omitted by bounding. An older reporter leaves the approved payload saved with an update-required result; subsequent supported submission uses exactly the originally approved bytes. Historical reports without scope remain valid and are not rewritten. This is the requested diagnostic-follow-up disposition for #1092, with no weakening of fixture approval, required coverage, work reserves or completion gates.

New regressions exercise actual retained checklist/save/restore/lifecycle projection for ordinary, bounded integration and exhaustive integration tasks; malformed and absent metadata; bounded criterion sampling; unsupported-to-supported reporter transition; and Python validation through issue construction. The 92 authentication/report tests and formatting of five changed Kotlin files passed locally. Full Android execution and current-head CI remain pending publication of this combined change.

The concurrent progress/prerequisite repair `3d2f210cca8865599b06ee23382443ed34ab12af` is preserved. The annotation-generation repair `9ead17c5a2c9e32d505bf37757bbbe41228118cd` is also preserved: a small additional observability change wraps each audit generation phase with the existing build-metrics collector and always retains both JSON reports. It changes no heap, metaspace, worker count, process deadline, step limit or subsequent gate. Repository CI policy and all 30 CI-observability/delivery regressions passed.

Final combined host validation passed `bash scripts/validate-local.sh fast`: 507 Python tests, 1,651 literal regular expressions with 82 dynamic patterns skipped, 34 Android resource XML files, CI/schema/dependency/secret checks, icon checks and provenance checks. Generated inventory is current. The release validator passed all changed paths and verbatim limitation disclosures; the shallow recovery checkout cannot independently establish full release-base ancestry, so the hosted full-checkout gate remains required. These checks do not claim execution of the newly added Android regressions.
