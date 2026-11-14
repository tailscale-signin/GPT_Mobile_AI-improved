# GitHub chat motion, voice presentation and open reports — November 14, 2026

## Repository and scope

This work continues PR #1122 on `fix/github-context-and-voice-reliability-2026-11-14`.
The starting head is `a4581f94e40f106ace5f18bc37bdd6763f7dad4d`; the initial `main`
revision is `5d4b4d81c121b36cf01b79fe8162fb78f793f203`. There was one open PR and
25 open non-PR issues at intake, #1101–#1126 excluding the PR itself. The concurrent
updates at `0189cb747b5ca256597ee58f326fbd81162c7efd`,
`7872874d975858e9cff4232270e873448b18c77f` and
`4d89a6847feec2d513f9af472f42a7cebbbb7c7f` were reconciled before publication.
Their scoped-admission regression changes are preserved with the complete
authorized discovery checks. The final update retires its temporary source-capture
workflow and removes two invalid UTF-8 files at incorrect package paths; the
canonical test files remain under `data/agent/provider` and `data/agent/tool`.
Further context-admission corrections at
`652cfd824c7d045d645d4655adc567a1d8e66876` preserve the supplied local-tool
subset and reject an unavailable selected GitHub contract when no discovery
route fits. The intervening preparation-only workflows have been removed.

The remote inventory contains 748 branch refs. Of those, 384 tips are ancestors
of `main`, one is the current repair PR, and 363 retain other historical or
self-test work. Recent repair branches are already included in `main`. Historical
refs are preserved; this inventory does not claim every historical branch
independently builds. All current changes stay on the existing PR branch.

## Requested chat behavior

The existing top-fade geometry already adds 3% of the current application window
height before IME resizing. It uses the live measured GitHub header height, so
expanding the top bar extends the fade and collapsing restores it. Its retained
navigation and geometry regressions verify this behavior; no second 3% increment
is added.

The common chat gesture handler now recognizes deliberate downward input at the
bottom edge even when the list consumes zero pixels. Reaching the bottom follows
the same anchor transition as the bottom button. Upward input still detaches;
keyboard, layout or viewport changes alone do not reattach it.

GitHub text arrivals now receive the following reading budgets:

| Newly rendered arrival | Duration |
| --- | ---: |
| One-line preview or text arrival | 1,500 ms |
| Two-line block | 2,000 ms |
| Three-line summary | 3,000 ms |
| Larger block | 1,000 ms per measured line |

This supersedes the earlier 1,500 ms-per-line behavior recorded in the preceding
chat/voice audit. Summary publication buffers a complete summary before adding its
timeline item. Ordinary streamed answers are measured as new lines arrive. The
FIFO queue keeps an existing segment's deadline when new output arrives. Width,
font, inset and non-text geometry corrections do not add reading time.

## Voice presentation and caption following

The voice surface uses the selected theme's primary, secondary and tertiary
colors for its orbital stage, soft background, state chip and rounded control
dock. Microphone input bars use the measured input level; orbital motion indicates
the current session state. Effects respect system animation settings, window
focus, lifecycle and the existing frame-pressure guard.

Captions retain the complete text in a stable, scrollable area. Active captions
use the theme accent. Valid engine-provided ranges add emphasis without changing
font weight or rewrapping the sentence. Missing word timing is not synthesized.
The following policy separates automatic movement to the spoken range from a
reader's manual scroll, avoiding the previous accidental detachment after an
automatic upward movement. Earlier text remains accessible and returning to the
bottom resumes following.

## Gateway conversation visibility

Issue #1126 identified GitHub chats that use a gateway but never reached a
successful server submission. The Gateway list previously depended on a persisted
gateway mode, mirror, binding or submission, so those chats could be missing.
Membership now also considers saved gateway selections, verified current
gateway profiles, and GitHub inference profiles bound to a distinct verified
engineering gateway. It uses active profile membership, retains existing offline
records, and excludes archived chats. Gateway-tab conversation bubbles identify
the actual GitHub, research, combined, multi-model or standard chat mode. Opening
a profile-only local chat preserves its mode and draft rather than adopting it as
a frozen server conversation. Existing explicit Gateway conversations still
validate their saved participant boundary. Membership projection runs off the UI
thread and ignores unrelated feature-setting changes.

## Open report dispositions

### Context admission boundaries

The context follow-up preserves upstream-advertised GitHub schemas, including
authorized opaque MCP aliases, through local planning. Generic greetings retain
the existing deferred engineering operations. Explicit discovery can expose one
individually large authorized GitHub schema in ordinary, status and integration
catalogs; the provider's hard context admission still determines whether it can
actually be attached to the next request. When the request cannot carry both
its required schemas and the necessary discovery route, planning can reduce only
a default output allowance or return a typed failure before dispatch. It does
not silently remove the route to the remaining authorized operations.

Behavior regressions cover deferred writes, scoped warm discovery, authorization
exclusion, soft versus hard budgets, and the boundary where an immediate context
read fits but its discovery route does not.

### Report evidence

The archive reviewed during the initial PR implementation, `diagnostics-a199704a-2fe7-4afd-9832-24d1035b97c6-part-1-e6fa218d-8b59-4aaa-8a91-7ce5bfdcab6e.zip`,
was available locally at that checkpoint. All 20
stage digests matched. Its 11 retained runs comprise seven failures, two blocked
runs and two completed runs. These are run outcomes, not a count of independent
defects. Raw reports and private account, session, prompt, endpoint and model
details are not committed.

| Issues | Implemented disposition |
| --- | --- |
| #1101–#1103, #1105–#1106, #1115, #1123 | The preceding commits on this PR admit task-sized GitHub schemas and retain on-demand discovery rather than preloading the full catalog. A regression exercises 5,632-token planning. Genuine capacity limits still reject before dispatch and retain their numeric budget evidence. |
| #1104 | Preserve the typed GitHub admission failure across the standard chat boundary. The supplied archive provides the matching precheck evidence. |
| #1108, #1110 | One bounded retry for transient playback focus failure, with observed focus attempts and terminal playback evidence. Device audio contention still needs matching phone evidence. |
| #1109, #1111–#1113, #1125 | One recorder recreation for a silenced zero-sample start, retaining the actual capture stage and outcome. This does not establish that another Android audio owner is permanently removed. |
| #1107 | Diagnostic follow-up: capture both voice-pack readiness states and scheduler status together. A completed download alone does not establish why voice remained unavailable. |
| #1114 | Stable themed captions, complete text, validated range emphasis, and continued following after automatic movement to an earlier spoken phrase. |
| #1116 | Diagnostic follow-up: retain explicit tool-result budget exhaustion and the spent/remaining resource evidence. |
| #1117, #1119 | Diagnostic follow-up: retain bounded completion-repair and reasoning-only attempt evidence when the provider produces no visible answer. |
| #1118 | Preserve the typed invalid tool-protocol frame failure; the original provider frame remains a provider-specific diagnosis. |
| #1120 | Preserve draft focus across background run changes and retain opted-in keyboard/focus observations. |
| #1121, #1124 | Diagnostic follow-up: preserve bounded sanitized application stack frames and exception type when a terminal Flow failure no longer carries its Throwable. These reports retained UNKNOWN classification; a shared fingerprint alone does not establish their cause. |
| #1126 | Include gateway-backed GitHub chats before successful submission, resolve distinct engineering-gateway bindings, preserve original workflow modes during navigation, and show Gateway-tab mode icons. |
| #1127 | Follow issued exact-head workflow pages within existing limits, distinguish unindexed graph prerequisites, and retain bounded prerequisite diagnostics. Fixture approval and indexing remain required. |
| #1128 | Preserve observed rules as partial policy evidence and select access-refresh recovery; inaccessible classic protection remains unknown. |

The terminal exception enhancement reuses the existing approved `errorType` and
`frames` fields. It carries at most eight application-package frames using the
existing grammar; it excludes exception messages, URLs, source filenames and
arbitrary external stack text. Capture-boundary evidence does not infer a failed
stage, dispatch state or retry authority. Existing diagnostic opt-in and private
session suppression remain in force.

Issue closure is performed after the complete PR qualifies and merges. Diagnostic
closures identify the evidence added for the next occurrence rather than claiming
an unobserved historical cause was repaired.

## Verification status

The focused scrolling host passed 28 tests with no failures, errors or skips,
including real measured single-line and multiline motion, bottom-edge input,
reflow, queue deadlines and fade geometry. The voice host passed 13 tests using
production caption/stage sources, including two native Compose caption-follow
regressions and one rendered stage. The actual stage image was visually inspected.
The emulator runner passed its 46 host boundary tests. Canonical host validation
passed 507 tests, and the authentication/reporter contracts passed 97 tests. Changed
Kotlin formatting and generated-source inventories passed. Full app and emulator qualification
must be bound to the published head before merging. The existing effective main
rules require `Required verification` and `Analyze (java-kotlin)` with a current
base; no bypass actors are configured.

Physical phone audio, microphone contention, private gateway connectivity and
hosted report-service deployment require their respective environments. A
diagnostic disposition records the evidence added for the next occurrence; it
does not claim a historical external-service or device failure was reproduced.

## Supplemental archive and all-issue follow-up

The next pass starts from `1987683f8b560c6e1bae0d2a8ea22344980cdf9d` on the same
PR and base. The new archive has SHA-256
`ad7693bd0392b6b5fb5e003fecfb0b1dbbb5ba4823ada8d65984a648215b1d7c`.
The concurrent voice-contrast and catalog-regression commit
`6d56939a5811c7779e49a072d8d1e3c08cf8fdd3` was preserved by fast-forwarding
the same branch and regenerating its source inventory before publication.
All 20 stage digests and the recorded integrity chains matched. Its 8,825
physical JSONL records parse, including 401 chunks representing 19 logical
records. The capture is truthfully partial: the workspace stage includes 312
of 370 source rows and retains its continuation cursor. The repair does not
rewrite that historical capture or claim the missing rows were observed.

The live intake now contains 27 reports, #1101–#1128 excluding PR #1122. There
is still one active PR. The 748-ref branch inventory remains the one documented
above; work continues only on the current PR, with no historical refs rewritten.

### Confirmed failure paths and changes

| Observed path | Change and acceptance boundary |
| --- | --- |
| Both synthetic review attempts exhausted 2,048 output tokens with reasoning but no visible verdict. | A distinct preflight retry role allows up to 4,096 tokens only if it actually exceeds the first allowance and the complete request still fits. Keep the selected profile, reasoning policy, explicit caps and unknown-outcome stop. No retry when the available allowance cannot grow. |
| One terminal review paragraph appeared twice. | The verified-session wrapper retains the failure; the runner publishes the terminal response after its recovery decision. The regression checks one publication and one terminal failure. |
| The producing invocation retained HTTP acceptance, request identity and usage, while its failure receipt lost them. | Merge only observations belonging to the producing invocation and retain failure-attached usage when a later frame contains only a finish reason. Delivery wrappers cannot borrow another invocation's metrics. HTTP acceptance is not generation completion. |
| The integration run queried jobs for 30 skipped workflow runs and left the selected-revision continuation unused. | A single skipped-run probe can characterize the endpoint. Further skipped-only candidates yield to issued exact-SHA continuation pages under a separate bounded dependency allowance. No workflow dispatch is invented. |
| Graph status reported that the exact repository revision needed indexing. | Prepare the graph through its existing index/checkpoint flow, then derive read selectors from actual returned records. Failed or uncertain indexing is not replayed. Missing baseline, node or diagnostic inputs remain explicit prerequisites. |
| Desktop startup removed an explicitly configured validation-policy path. | Retain only the approved policy setting through both launcher stages; add an Advanced settings chooser/checker and separate durable preference. Invalid or missing configuration still blocks implementation. Unrelated execution and listener overrides remain excluded. |
| A partial export retried a stage below that stage's original headroom threshold. | Recheck each original threshold, retry with one-row source pages and cooperative yielding, preserve atomic cursors, and reuse the row projector during protected-payload hydration. Keep the same record and archive limits. |

The gateway policy chooser validates configuration without running commands or
claiming runtime readiness. Saved preferences remain compatible with the legacy
settings schema. A malformed saved selection must not prevent the settings
screen from opening or enable inherited policy fallback; a new explicit selection
repairs it.

### Remaining report evidence

Report #1127 follows the workflow/graph preparation changes above and retains
specific unmet prerequisites. Fixture-only mutation tests continue to require
the existing explicit Chat decisions setting and its owned-fixture checks.
Report #1128 retains separate branch-protection and repository-rule observations:
an inaccessible or not-found protection response cannot establish that no
protection exists or that the required-check inventory is complete.

The archive also contains measurable frame and layout stalls, but does not
establish their root cause. Opted-in frame diagnostics now retain bounded layout
constraints and repeated-measurement context scoped to one modifier instance.
Equal constraints do not imply equal content or prove redundant layout work.
No prompt, rendered text or chat-history scan is added to this evidence.

Export receipts now retain memory at the actual deferral, the required headroom,
committed output bytes, and numeric source-table/row/byte context. A successful
retry retains the original deferral sample. The SQLite source byte count is
labeled as stored-row size rather than decrypted-payload size. These observations
help distinguish remaining allocation bursts, oversized source values and
insufficient process headroom on the affected device.

### Concurrent implementation verification before integration

The focused production exporter/projection/frame-context host suite passes 71
tests, with Android trace and entity boundaries stubbed. The authentication and
approved-report contract suite passes 97 tests. The production GitHub planner,
status and graph host suite passes 112 tests; preflight recovery passes nine;
failure receipt/report propagation passes six. The desktop policy and existing
validation tests pass 27 cases, including malformed preferences and Windows BOM
files with non-ASCII paths. Canonical host validation passes 507 tests.
The current request-constraints/reasoning suite passes another nine cases.
The complete gateway checker reports 958 Python cases: 955 passed and three
platform/environment cases skipped, with no failures; all seven browser-bridge
tests pass. Pinned dependency hashes were checked without altering the lockfile.
Database/Robolectric, Compose,
full Android and exact-head hosted qualification remain required before merge.
The checkpoint records the rest of the current validation and issue-closure
dispositions. Physical microphone ownership, private endpoint behavior and phone
frame/heap improvements have not been reproduced by these host suites.

### Latest voice implementation

Voice-origin rooms retain a separate presentation preference bound to room ID and
creation time. Temporary rooms are excluded. The full transcript reuses the same
ChatViewModel and voice-session owner, with an Auto read control and a themed
Read aloud/Stop action on each assistant response. Ordinary transcript Back leaves
accepted generation running; explicit End voice still ends the voice interaction.
A generation ledger primes existing history, tracks new typed and spoken replies,
coalesces stream/final updates, and prevents stopped or superseded replies from
re-entering automatic playback. Recording permission gates capture, while explicit
playback can operate without microphone permission.

Both exact pinned English lexicons were downloaded and their hashes verified.
US has 178,401 normalized entries and GB 191,924; each contains `here` with normal
phonemes. Current-source ordinary `here`, `Here` and `HERE` did not reproduce the
reported spelling. Parsing now handles dictionary whitespace, normalization,
comments and delimiters consistently, and speech projection removes supported
word-internal invisible formatting characters. Explicit known initialisms remain
spellable. Unknown ordinary words produce a typed pronunciation failure and
retained captions instead of automatic letter spelling or native silent omission.
This is a deliberate failure boundary while an offline grapheme-to-phoneme front
end remains unqualified; it is not a claim that every name/language is pronounceable.
Content-free pronunciation counts, lexicon health and precise output reasons are
retained in the existing diagnostic log path. Public playback failures retain the
compatible `SYNTHESIS_FAILED` category.

Kokoro remains on the supported CPU execution provider. PCM callbacks are divided
into bounded 100 ms batches with no more than six seconds queued, while synthesis
and playback remain overlapped. Native release uses the shared native-engine gate.
Thread preferences remain bounded; no unsupported GPU provider or unmeasured
higher-thread default is introduced. Device time-to-first-audio, thermal stability
and accelerator execution are not measured by host tests.

### New GitHub report dispositions

| Issue | Change and limits |
| --- | --- |
| #1127 | Continue authorized, already-issued exact-head workflow pages when an executed job/log dependency is still missing, within the accepted call budget and bounded eight-page prerequisite allowance. Identify unindexed graph prerequisites explicitly and retain closed-enum graph/workflow observations. Indexing and fixture approval remain real prerequisites. |
| #1128 | Retain observed rules as partial policy evidence and choose access-refresh recovery when another policy endpoint is inaccessible. Do not retry unchanged reads automatically or infer that 404 means no branch protection. Complete policy and merge readiness remain unverified until all required sources are available. |

Hosted submission of the three new prerequisite fields requires a reporter that
advertises `GITHUB_PREREQUISITE_EVIDENCE_V1`. The app gate, server validation and
advertised capability change together. Native authenticated GitHub reporting
validates and renders the approved payload locally without depending on a hosted
reporter deployment. The diagnostic archive retains the underlying bounded evidence
independently; the approved destination and payload are never silently changed.

The current branch audit also checked the recent divergent production repair tips.
Their associated PRs #785, #801, #802, #803, #804 and #807 are already merged into
`main`; a separate Windows-suite branch is patch-equivalent. The remaining recent
release-recovery branches contain one-time publication procedures rather than new
application fixes. Historical refs are preserved and are not represented as
independently rebuilt or newly merged by this repair.

## Combined-head integration

The combined implementation preserves concurrent commits
`6d56939a5811c7779e49a072d8d1e3c08cf8fdd3` and
`b9f3a36c6861c39ff0d65791e7c13c8a4dbbd88c`, plus the subsequent Windows test fixes
`752e61c5e76afe305c5fd5aca88f60c4ac86ada9` and
`2f7d5826c4ab043c628fae5f54caf32cdd8349d3`. Their review, workflow, graph,
desktop-policy and export changes are reconciled with the voice transcript,
pronunciation and public prerequisite diagnostics on the same PR branch.

The independent archive review reconstructed 19 chunked records only after every
chunk and complete-record digest matched. Six actual run rows comprise two partial,
three blocked and one failed run; metadata records are excluded. The remaining
58 workspace rows require resumed capture on the source phone. No missing
payloads or historical root causes are inferred.

After integration, canonical fast validation passed 508 checks. The unchanged reporter
suite passed 102 tests. Rebuilt focused exact-source host suites passed 34 capture,
11 review-preflight, 13 review/request-budget, 20 prerequisite/status and four
standalone graph-preparation cases. The voice work passed 45 runtime/classification,
66 session/transcript and five acceptance-runner cases. Counts overlap and
must not be summed as unique cases. The real Kokoro API compiles against pinned
Sherpa 2.0.1 and Android 16 interfaces with unrelated store/report boundaries
stubbed. Exact-head Android CI remains required; earlier-head results do not
qualify the combined head. The two new Compose response-control tests are wired
into both the API 34/API 36 acceptance runner and packet verification.

The pinned full gateway checker also passed: 955 Python cases, three platform/
environment skips and seven browser cases, with no failures. The final preserved
Windows alias-path policy regression was separately rerun (11 passed). Package
digests match reviewed source; dependency lock hashes were not changed.

## Independent integration review and voice follow-up

This review preserves the combined voice/report implementation at `0a340a91`,
the diagnostic and CI follow-up at `90c2f835`, and the repository-map repair at
`b279eddfe41edd23602aacd811c881defa3b1aea` on PR #1122. The current review
adds the following concrete repairs; final hosted qualification is still required.

- Workflow discovery inspects cancelled, neutral and unknown-conclusion runs
  because they can contain completed jobs with usable logs. Only confirmed
  completed/skipped runs are excluded after the first jobs probe. The exact
  repository/revision and bounded continuation rules remain in force.
- Gateway diagnostic timing tests verify monotonic duration independently of
  coarse or backward wall-clock observations. Controlled regressions also
  ensure work after response-body completion does not inflate its latency.
- Marketplace source generation uses separate KSP and KAPT processes with the
  established bounded 4 GiB annotation heap, one worker, retained deadlines
  and logs. All 16 focused Android contract selectors remain enabled.
- Regenerating a response explicitly retires its old audio generation, even
  after model output completed while audio was still draining. Both automatic
  playback and manual playback of the latest revision are covered. Deliberate
  historical-revision replay retains ownership until its real finish. Retired
  callbacks cannot stop, fail or complete replacement playback.
- Submitted voice drafts retain their actual request identity and draft
  generation independently of replay. Late queued/accepted/rejected events
  settle only their owning draft; pause and recreation do not re-offer an
  accepted prompt or strand a rejected one. Both composer checkpoint paths
  guard against the dispatcher's already-observed acceptance. Restored audio
  remains silent, and unrelated historical playback is not interrupted.
- The transcript frame supplies theme content color through a Material
  surface. Its navigation icons and labels remain visible in dark mode, and
  the selected Chat label aligns with the Voice control. Required render
  evidence covers light, dark and 200% text at a narrow phone width.

Focused validation passed 81 controller tests using the real session, playback
ledger, speech projection/buffer and diagnostic trace sources. Only platform
and report boundaries are substituted in that host. The supersession failure
was reproduced before correction for ordinary and streaming output; the manual
latest/history distinction had two failing-before cases. Two actual Compose
render/interactions methods also passed in the native-graphics SDK 34 host,
with model/ChatScreen boundaries substituted. These are not full-app or physical
audio execution results. The acceptance runner now requires all ten named
voice methods and five retained PNGs; its 48 contract tests passed.

The integrated workflow selector/prerequisite host passed 18 tests, Gateway
diagnostic evidence passed 14, and the existing CI/model-provisioning policy
checks passed 19. Independent repository-map review passed 50 backend cases
covering graph context, paging, indexing and selected-execution integration.
These suites overlap other validation and must not be added as unique coverage.
Canonical checks, exact scope, generated inventories and full-PR formatting
are rerun before publication. Final Android/unit/lint, CodeQL, Gateway and
emulator results must qualify the final shared head after all active work lands.

The publication candidate based on `b279eddfe` passed 509 canonical host checks
and 99 tests in the four authentication/reporter modules used by PR Validation.
The source inventory and exact staged scope are checked again at publication;
these local receipts do not replace the final combined-head hosted gates.

## Independent reporter and map integration review

The follow-up review starts from shared map integration
`b279eddfe41edd23602aacd811c881defa3b1aea` and preserves its source,
relationship, pagination and durable-worker repairs. The combined follow-up also
preserves the voice ownership/admission/theme repair
`b1a0ea71ce748a04d50b0d85fa4c301032c57107`. The Python graph review
passed 44 focused paging, context and durable-index tests; the exact-source
planner/status host passed 125 tests. These checks do not establish physical
device behavior or replace final shared-head Android qualification.

The hosted reporting compatibility reproduction covered twelve newly accepted
failure codes at six possible locations. Existing provider enum constraints
correctly exclude eleven combinations, leaving 61 valid approved payloads. The
main-branch reporter rejects all 61, while the updated reporter accepts them.
Before the correction, the app dispatched 57 to the older service and converted
its rejection into terminal `INVALID_REPORT`. After the correction, all 61 wait
before dispatch with `REPORT_SERVICE_UPDATE_REQUIRED`; their approved bytes and
report IDs remain unchanged for explicit retry after compatible support exists.

The eleven new budget, validation, source-revision and repository-access codes
require `ISSUE_RECOVERY_CODES_V1`. Protocol failures retain
`COMPLETION_EVIDENCE_V1`, now including causal and voice nesting. The guard is
inside hosted `IssueReportSubmission`; native authenticated GitHub reporting
continues to validate and render locally without depending on hosted readiness.
The real readiness document is 813 bytes against the retained 1,024-byte bound.
Freshly compiled production report/submission sources passed all 28 submission
tests, including the 61 supported location/code combinations. All 53 reporter
HTTP/contract tests passed. Suite and fixture counts overlap and are not a
combined unique-test total.

The map integration review also reproduced a never-indexed revision being shown
as an empty indexed map: its status now carries revision metadata, which the
ViewModel previously treated as proof that an architecture query was available.
The follow-up preserves `NOT_INDEXED` as a distinct state and keeps real indexed
empty results separate. It also clears stale map/search cursors after refresh or
snapshot eviction between status and architecture reads. Five real-ViewModel
host checks passed with HTTP/lifecycle boundaries isolated; four added repository
regressions remain subject to the full Android suite. Index admission, scope,
pagination and coverage rules retain their existing production contracts.

After reconciling the voice follow-up, the combined canonical host gate passed
509 tests. The four authentication/report modules passed 100 tests. Generated
source inventories, diagnostic schema and whitespace checks are current; hosted
Android, CodeQL, emulator and applicable packaging gates remain required on the
final shared head.

## Complete bounded query paging

Independent review of the repository-map integration reproduced a silent
200-node traversal-frontier cutoff. A depth-two query with 202 first-hop branches
and one second-hop leaf per branch exposed only 402 of 404 known relationships,
then reported no continuation and no truncation. The new real-Graph regression
first failed on the unchanged implementation with those two missing relationships.

The repair removes that hidden frontier slice. Traversal remains bounded by the
finite admitted graph snapshot, the existing depth limit, visited/expanded nodes
and unique relationship identities. Page record/byte limits and revision/query
cursor binding remain intact. The regression collects all 404 relationships with
both endpoints on each page, unique advancing cursors, no duplicate relationships,
and pages no larger than 8,000 bytes. All 72 focused effectiveness, durable-index
and core-graph tests passed.

This checkpoint preserves repository-map commit `b279eddfe41edd23602aacd811c881defa3b1aea`
and voice-ownership/theme commit `b1a0ea71ce748a04d50b0d85fa4c301032c57107`.
The hosted-versus-native report-delivery clarification above also applies to
`GITHUB_GRAPH_POLICY_EVIDENCE_V1`; no report destination or approved bytes change.

After reconciling Gateway 17 commit `cf094eea6fc6bdeadbfe1ba76864a019c4cf688d`,
the focused paging, durable-index and core-graph rerun passed 75 tests. Canonical
fast validation on the combined source passed 509 tests; all five authentication
and reporting modules passed 105. The canonical architecture index exactly
matches concurrent correction `1ed51b1a0ebbdd8fa1efd1b51d1b27cac379c773`.
Gateway versions 14, 15 and 16 remain supported alongside 17, required-policy
completeness still depends on both valid source observations, and the new hosted
recovery fields remain bounded and negotiated. The current readiness document is
847 bytes within the unchanged 1,024-byte limit. Native authenticated GitHub
delivery retains local validation. Required Android and exact-head hosted checks
remain pending the final shared integration candidate; the separately owned
benchmark interruption correction must also be preserved before qualification.

## Benchmark interruption review

The follow-up based on `37a37dd87f5626871f006a233bc5144780b1f7ac` preserves the
published paging and PR Python runtime corrections. Restart recovery previously
changed a provisional coding case to `INTERRUPTED` before shared normalization,
leaving its top-level score and resolution flag visible despite `graded=false`.
Recovery now clears only that unfinished case's score and resolution while
retaining completed cases and each measured attempt's separate evidence.

A second reproduction found that an unknown physical outcome in a parallel
comparison could leave the shared cancellation flag unset. Executor shutdown
then allowed queued samples to dispatch before propagating the interruption.
The worker now signals cancellation before it can dequeue another trial, and
the collector cancels pending futures while admitted observations settle within
the existing request bounds. The original cancellation receipts and completed
comparison arms remain available; an interrupted parent is never qualified.

All 35 focused benchmark tests passed on this combined source. The new real
coordinator/runner/journal regression uses a fake transport: a completed
sequential arm remains saved, two admitted concurrent observations retain their
receipts, and the remaining three samples never dispatch. It does not execute a
live model or Docker candidate. Container-cleanup reconciliation and malformed
coding evidence are separately owned follow-ups and remain a source hold before
the final shared-head Android, packaging and hosted verification gates.

## Durable benchmark cleanup and grading evidence

This follow-up starts from `46736a827a500ba25930edb55d93b4722227cf1c`,
preserving the restart/parallel interruption corrections, the desktop list and
callback repairs, and the explicitly pinned CI dependency and workflow syntax
correction. It completes the two benchmark follow-ups identified above.

Model-supplied `evidence.changed_files` previously accepted a list containing
mixed types, then raised `TypeError` while sorting after successful execution.
Four failing-before shapes reproduced lost grading despite a passing patch.
The guard now requires strings before comparison. Malformed metadata earns
zero for the evidence dimension while preserving the eight other measured
dimensions, execution and resolution; the controlled passing proposal scores
950 rather than losing its entire result.

A bounded Docker client timeout or output overflow can leave its actual
container active. The prior implementation discarded failed removal receipts
and could start another repair or trial. The repair keeps the partial execution
observation, output digest, elapsed time, model/job receipts and prior attempts,
then interrupts before further dispatch when physical cleanup is unconfirmed.
Reference calibration remains separate: its observed execution is retained,
but it cannot count as an executed candidate trial or cause model dispatch.

The existing benchmark journal now records one exact generated container name
per run before launch. Claims survive process restart and disabling new durable
benchmark admission. Unresolved ownership blocks new execution, history
deletion and installer maintenance; healthy active work can still accept a
queued run. The existing 500-run history bound also bounds cleanup ownership.
Recovery performs bounded removal and exact-name absence observations, never
candidate or model replay. Only confirmed removal, a successful attached
completion, or a successful observation of absence releases a claim. A failed
Docker query does not establish absence. Preflight, launch, removal and restart
reconciliation use an explicit empty Docker configuration and default context,
so inherited settings cannot direct cleanup to another endpoint.

The focused isolated Gateway runner passed 12 new cleanup tests, 35 existing
benchmark tests, one grading-evidence test and four maintenance tests: 52 cases.
These exercise the real coordinator, journal, runner and maintenance admission
with controlled platform failures. Two existing exit-125 fixtures now explicitly
provide successful cleanup while retaining their original assertions. The new
tests also cover unknown nonzero client exits, unavailable Docker observation,
restart with admission disabled, and cleanup of an absent prelaunch container.
Independent source review covered sandbox settlement and runner evidence as
well as service/store ownership and maintenance; no additional blocker remained.

The separate desktop follow-up passed 24 focused cases; three native display
cases cannot run in this container and remain required in hosted Windows
qualification. The PyYAML 6.0.3 CPython 3.12 Linux wheel checksum independently
matches the official PyPI release metadata. No live Docker workload, model
inference, physical device result or hosted reporter deployment is claimed.
Canonical checks, package digests and exact staged scope are validated before
publication. All applicable hosted jobs must qualify the final shared head and
current base before the single authorized merge and issue disposition flow.

The publication candidate passed all 510 canonical host checks and six real
conversation metadata/API regressions. The regenerated package manifest matches
reviewed source; the app inventory contains 2,599 files across 26 areas. Final
staged scope and full-PR Kotlin formatting are checked at publication. These
receipts retain the final shared-head hosted qualification requirement.

## Late reports #1129 and #1130

Both reports arrived during qualification and identify the preceding build 141
commit `b5ab42bfe5270cbe28c2bbde16eecb38d44b4194`. Intake now covers 29 issues.
Neither contains an external attachment, and neither establishes a regression
on the then-current candidate `007b29d2a7581c0acfd5474f7bc01ba768c57bae`.

#1129 retained only `CHAT_FAILED`, absent code state and unknown origin. Source
inspection identified two real diagnostic gaps: Gemini, Groq, Ollama and LiteRT
could catch exceptions and emit ordinary failure values without exception
metadata; repository preflight/recovery could also emit error values without
run/provider or capture-boundary evidence. Neither path reaches the terminal
Throwable catch. Actual adapter catches now retain sanitized type, bounded app
frames, fingerprint and hashed cause types. An outer repository error-value
projection supplies missing run/provider correlation and identifies the
observed repository boundary. Existing typed details and cancellation are
preserved. A normal error value does not gain an invented exception, HTTP
response, dispatch state, retry authority or causal origin.

The closed capture sites are `PROVIDER_ADAPTER_STREAM_CATCH` and
`CHAT_REPOSITORY_ERROR_VALUE`; both use `CAPTURE_BOUNDARY_ONLY` provenance.
Hosted delivery requires the new `CAPTURE_BOUNDARY_EVIDENCE_V1` capability only
when one of these new values is present. An older service leaves the exact
approved report saved. Native authenticated GitHub submission stays independent
of hosted readiness. The readiness document remains below its existing byte
limit. The historical failure is still undiagnosed and needs an updated app
and a new report from the original route.

#1130 is a capability-prerequisite verdict: 45 of 118 verified, 66 blocked,
seven untested, zero failed operations and 173 calls left. Compared with #1127,
the extra 48 required/blocked capabilities are 47 owned-fixture and one
live-evidence prerequisite. Their distinct run identities remain distinct.
`FULL_CATALOG` selects API capabilities; `BOUNDED_SMOKE_SAMPLE` still bounds
ordinary inventory traversal, so 15 pending pages alone do not prove stalled
required work. Existing preparation advances admissible selected-SHA graph and
workflow dependencies, then retains actual fixture/input blockers. `ASK` does
not become fixture approval, and Continue cannot supply missing owned resources
or live evidence. Current recovery reports already include preparation state,
ready-call count, fixture admissibility, operation families and graph/workflow
observations, so this report requires no additional source mechanism.

The regression additions cover actual adapter catches, typed-error precedence,
message-only responses without fabricated exception evidence, cancellation,
the real Android report collector for Continue without a saved original, and
hosted capability compatibility with durable/native delivery boundaries.
Focused local receipts and the final full Android execution are recorded in
PR #1122; local host fixtures are not physical-device/provider qualification.
