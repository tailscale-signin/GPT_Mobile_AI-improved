# Successor repairs after PR #1122 — source reconciliation and issue disposition

## Scope and source identity

This follow-up remains on `tailscale-signin/GPT_Mobile_AI-improved`. Work began on
`fix/github-context-and-voice-reliability-2026-11-14`. PR #1122 was merged outside
this coordinator at November 15, 2026, 01:36:50 UTC, using the previously
published `b553525da42a9f6d24c56f249355a7ef03bf3f13` head. Main is now
`485515d3d63010aceb217ac0988ba28fff7eabf4`; its tree is exactly the b553 tree.
That merge does not contain or qualify these pending repairs. The later existing
draft [PR #1203](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/1203)
on `fix/durable-engineering-completion-2026-11-14` is now the single integration
target, as required by the user's latest-draft instruction. Its published
a0c480084e067c9ca55ba4b322d1094cd87448c9 work, its 6b346/11fd metadata children, and all prior ancestry are preserved
while this reviewed union is reconciled. Its final combined head and current main
base require fresh validation before merge or individual issue closure.

The composition preserves the complete prior
`2b2d6276bceb5d3e2428ff52aad3b8e6bd9c791e` lineage and the fuller Gateway
Repository Validation feature at `fdc808976f1df2024856dac01ffd2a0a1dddbda2`.

The committed intake cutoff is November 14, 2026, 23:14:21 UTC: **67 reports,
#1101–#1168 excluding PR #1122**. Every report is read individually. The
[issue ledger](gateway17-issue-dispositions.json) gives its fix, diagnostic
fallback, source and tests, and remaining historical qualification. Later
reports are still reviewed: a new source defect holds merge, while an already
covered report can use the tested repair and an individual PR/issue addendum
without a count-only source commit restarting validation.

The initial branch inventory contained 748 refs: 384 reachable from main, one active PR
branch and 363 historical divergent refs. Relevant recent production tips were
reconciled by their merge or patch equivalence. Historical refs are preserved;
this is not an independent build or rewrite of every historical branch.

The complete prepared individual successor dispositions are retained in
[`pr1203-issue-dispositions.json`](pr1203-issue-dispositions.json). That ledger
preserves each unresolved historical cause and the precise new evidence collected.

## Latest existing PR and implementation qualification

The successor adopts PR #1203 and preserves the frozen durable-engineering
component and its workflow-coverage correction. Source preparation uses exact
commit/tree identity; inherited PR markers remain consistent across creation and
reconciliation. Post-merge verification requires the applicable workflows at the
merged source, the proved push boundary, and matching repository, branch, event
and SHA. Unsupported syntax, incomplete changed-path coverage and unproved rebase
or single-parent recovery remain explicitly blocked. Immutable completion evidence
is saved after the final verdict, including pending and blocked outcomes.

The integration preserves the accepted CI investigation path while bounding replay
to remaining context. Compact read aliases must reach the actual status/CI
consumers and validate their advertised continuations without gaining write
permission. Current check identities and complete coverage remain prerequisites;
terminal configuration blockers retain their names, actual failures take priority,
and superseded checks cannot veto a current successful run.

Source edit authority is tied to pinned original ranges actually delivered in the
admitted model request. Retained full files and proposed replacements do not grant
that authority. Large source reads and individual or aggregate edited contents use
authenticated artifacts across journal/checkpoint/reopen boundaries. Bounded
validation and patch feedback remain available for an authorized repair iteration.
Generic store ceilings and existing source limits remain unchanged.

Required benchmark input preservation cannot be proved by a candidate-process
Boolean or serializer. Functional outputs remain measured separately; absent
independent preservation evidence keeps full resolution and overall scoring
unqualified. This distinction is required by the shipped prompt, including its
universal caller-input contract, and applies to first-attempt and final summaries.

The initial ledger and separately reviewed follow-ups prepare individual
resolutions for 101 issues through #1202. Qualified historical causes, diagnostic
fallbacks and exact source ownership are retained per issue; the latest archive's
eighteen voice comparisons add no new causal evidence. Actual final-head CI and
the current base must qualify the complete union before merge and closure.

## Additional reports and concrete repairs

| Report | Observed problem and resolution |
| --- | --- |
| #1149 | The current-only estimated schema budget is 754 tokens short. Retain the exact admission-time selected identity, capacity ceilings, provenance and numeric breakdown. Even removing the entire 704-token result reserve leaves 50 tokens short at this guard. The private request is not replayed, and later physical-request admission remains necessary. |
| #1150 | The existing selected-profile/model lock became a generic exception. Carry immutable comparisons and hashed identities from the same guard through the typed failure and approved report. Preserve every original condition and earlier guard. The historical capsule cannot identify which operand failed. |
| #1151 | Graph indexing was classified as a replayable read outside an inner checkpoint that requires reconciliation after uncertainty. Exclude exact index operations before generic read-only-wrapper fallback, and normalize native/alias duplicate-action identity. Keep existing local read grants, non-fixture acceptance semantics, and bounded status/query retries. |
| #1152 | A new index call correctly stopped before dispatch because an earlier index outcome was unknown, but structured metadata lost its reconciliation requirement and saved-index recovery action. Preserve that prior-checkpoint requirement without relabeling the rejected current call as dispatched or unknown. |
| #1153–#1154 | Graph adapter error unwrapping could discard saved-index uncertainty and recovery evidence. Preserve the actual operation evidence while retaining underlying typed-cause fallback decisions. The old reports do not establish HTTP status, cooldown or backend completion. |
| #1155–#1159 | Distinct native GitHub preflight failures retained only a generic operation code. Carry bounded sanitized cause types and an exception fingerprint through the existing accounting and report paths. Keep actual current-operation dispatch and unknown-mutation observations authoritative; do not infer private arguments or an unobserved cause. |
| #1160 | The retained acceptance verdict counted three failed operations, but the bounded public sample omitted every FAIL row. Prioritize recorded failures before prerequisite rows, including final byte compaction, while preserving order within failures, totals, omissions and the immutable verdict. |
| #1161, #1163–#1165, #1167 | A local resource cooldown became a generic chat exception and lost its wait observation. Retain the actual resource snapshot, timing basis, delay and deadline at that guard. A secondary timer does not borrow facts from the most recent resource snapshot. |
| #1162, #1168 | Distinct actual GitHub rate-limit responses lacked the response observations needed for diagnosis. Capture status 403/429 and only headers/timing from that same response, including the replacement response after an orphan 304. Do not attach a prior response's HTTP status to a locally refused request. |
| #1166 | The manual cooldown request selected an earlier acceptance capture. That snapshot records 140/256 app task calls used and 116 remaining; it is not a current GitHub quota measurement. Retain accurate cooldown evidence and saved-work guidance. Removing a local guard would not remove GitHub's server limits or authorize replay of uncertain work. |

The #1160 verdict still records 3/5 requirements and 13/70 capabilities
verified, with four pending remote continuations and unmet input/approval
prerequisites. Available call allowance does not grant fixture approval or
establish an executable continuation. The original failed-operation causes
remain unavailable; adjacent timestamps or matching signatures do not prove
that distinct reports describe the same operation.

## Accurate retained outcomes

A new collection may belong to a run that already dispatched work. The
selection rejection or cooldown guard therefore does not establish whole-run
non-dispatch or negative remote acceptance. Earlier known provider evidence
remains authoritative, and earlier uncertain operations stay uncertain.

The published intermediate helper at `0d0971cc4aa4850023cb78444133eb5c3cffbff1`
incorrectly emitted `dispatched=false` and `remote_acceptance=NO` when current
content and thoughts were empty. The same actual coordinator oracle fails
against those exact helper/manager bytes and passes with the corrected typed
projector. The composition replaces those helper/types/tests while preserving
the remote commit's ancestry. It does not retain an unsafe assertion simply
because the assertion was introduced by an earlier partial publication.

REST and GraphQL mutation-admission wrappers keep their existing status-zero
rejection and journal behavior while retaining the direct typed cooldown cause.
Tool accounting remains the authority for the current operation's phase,
dispatch, accepted/unknown mutation state and reconciliation. New diagnostic
fields do not grant retry or replay authority, remove waits, change accounts,
substitute profiles or relax existing admission and approval controls.

## Voice delivery, failure evidence and original privacy authority

Terminal run and message observers can become visible to the UI at different
times. Voice playback now reads a matching committed assistant row and terminal
run under one transaction, validates chat/message/run/fence ownership, resolves
immutable Combined content, and rechecks playback ownership after suspension.
An unavailable or mismatched read is not delivered. Admission still reserves
the identity against duplicate autoplay. Genuine empty or failed terminal
results remain reportable. Stop suppresses sound while allowing valid silent
captions and completion; unrelated manual replay or replacement retires ownership.

Actually caught Kokoro failures retain their bounded sanitized cause evidence
through output, audio, session and report collection. Pronunciation
and lexicon failures have finite reasons; a refusal without a caught exception
does not acquire a fabricated cause. A new
reply clears previous reply-owned run/terminal attribution, while same-reply
pause and close retain legitimate history. Reported PREPARING state does not
invalidate a separately captured playback-head first-audio observation, and
first audio does not establish completed audible playback.

Every asynchronous playback or durable-read failure retains its originating
diagnostic authority. The collector rechecks that authority under the same lock
as privacy publication; cancellation cannot renew a private or invalidated
operation. An explicit new public capture/reply can obtain a fresh ticket.
Failure collection occurs once before audio retirement, and UI recovery stays
independent of whether a report is permitted. Actual producer tests reproduce
the old private-to-public callback defect and preserve public/fresh controls.

## Later capacity, reasoning and review reports

Reports #1185, #1197 and #1198 retain separate measured admission failures.
All three routes provide 5,632 tokens, but required schemas exceed the allowance
remaining after input and reserves by 806, 2,197 and 3,411 tokens respectively.
The first report also observes a configured ceiling of 1,048,576; that setting
is unknown in the other two reports and is not borrowed between incidents.
The recovery UI now shows the immutable attempt's measured constraint and
opens configuration guidance instead of unchanged generic regeneration.
Combined failures display the specific reason. Required tool selection, actual
route capacity and result/safety/output reserves remain enforced. These reports
do not establish a schema-selection defect or a supported larger model context.

Report #1186 correctly refused an unproved reasoning-on capability. New probe
observations distinguish metadata age from the actual probe completion age and
record a finite outcome and observed HTTP-start count. Control evidence remains
strict; there is no new inference request, retry, fallback or enlarged cache
lifetime. Same-key refreshes cannot combine different generations' state and
observations. A recorded HTTP start is not proof that bytes reached a provider.
The later archive contains the same report projection but adds no joined
historical probe result.

Report #1187 did not establish whether synchronous admission or a later owned
request failed. A synchronous refusal now reports its actual admission stage
without querying an old request. A matching asynchronous failure can retain a
finite approved reason hash. Unknown free text is neither serialized nor hashed,
and no run, causal identity, dispatch or remote-acceptance fact is invented.
The originating diagnostic authority still governs delayed failure reporting.

Report #1200 joins the later archive's saved review run. The first format-repair
request identifies an evidence-ID coverage/cardinality failure, but the original
verdict body is absent, so missing, duplicate, extra or mismatched IDs cannot be
distinguished. Both review invocations completed HTTP 200 / STOP within their
output allowance. The final validator's condition is unavailable and must not
be inferred from the first. The repair retains the actual final validator
exception through bounded type hashes and a message-free fingerprint. Strict
schema/evidence checks, cancellation, the two-format-attempt limit, draft/evidence
retention and durable review budgets stay unchanged.

## Keyboard and frame diagnosis on the next occurrence

Report #1192 records twelve focus changes and twelve keyboard-visibility changes,
ending unfocused with the keyboard hidden; it does not identify the physical
cause. The bounded APP snapshot now includes up to eight editor/window/lifecycle
and app-release observations with relative ages, capture generation and dropped
counts. Coalesced events share one timestamp. Release requests, observed focus
loss and window-wide IME visibility remain distinct. No typed content or key
contents are recorded. Diagnostics off/private transitions revoke the originating
capture even if settings are rapidly restored; an old collector cannot revive.
Refreshing the diagnostic callback does not change physical editor cleanup keys
or dismiss the keyboard.

The frame export now includes each retained operation sample's observed monotonic
start and end. This closes the loss of placement within a reporting window while
preserving the existing sample/context/serialized-character bounds and explicit
truncation. It does not establish that a checkpoint, recomposition or exporter
caused a sampled slow frame, and it is not a measured performance improvement.

## Gateway quota recovery and saved progress

The additional archive binds individual reports to their retained run and call
identities. Independent GitHub reads and workflow discovery precede each graph
index batch so a repository graph cannot consume the entire foreground task
budget before other capabilities are checked. Graph indexing retains completed
siblings and reports the actual pending work when a bounded GitHub rate wait
interrupts a batch. The app preserves the Gateway's actual response observation
without relabeling earlier uncertain index acceptance as a new rejection.

A recorded cooldown survives shutdown and same-generation pause/cancel without
restoring execution authority, a lease, progress or acknowledgement. A valid
replacement worker can commit its progress without clearing or shortening the
current row's still-future deadline. Claim admission respects that wait and the
original work budget. Late observations cannot cross a new generation; expired
waits can clear. These changes retain saved work while keeping cancellation,
namespace, generation and lease checks authoritative.

## Report contract and delivery

`PROFILE_SELECTION_EVIDENCE_V1` negotiates the new profile-lock observations.
`GH_COOLDOWN_V1` negotiates the six closed cooldown observations in PROVIDER and
TOOL areas, their omitted-field names and the new local code at applicable
locations. Existing primary/secondary rate-limit code placements remain
compatible; newly projected provider-failure locations are gated. The actual
readiness response is 1,022 UTF-8 bytes with 30 features, inside the unchanged
1,024-byte bound. The two additional finite voice reasons require numeric
reporter protocol 2 before dispatch; older voice values keep their existing
compatibility behavior. Four optional reasoning-probe observations and the
24 optional composer/tool extensions likewise require numeric protocol 2 for
new values or explicit omission names. Existing core requirements and the four
legacy optional composer observations remain compatible.

Composer event slots require complete contiguous pairs with non-increasing ages
and a newest age of zero. Malformed local tails or byte compaction omit the
whole supplied slot group; restored payloads reject mixed retained slots and
omitted slot names. Missing optional observations do not manufacture unavailable
values. Available result bytes and a locally bound graph-attempt hash add
observations only, with no completion, retry or authorization effect. The
3,072-byte diagnostic and 8,192-byte approved-payload limits remain unchanged.

Approved report bytes remain immutable. An older hosted reporter leaves the
report saved with an update-required result before any POST; it does not
silently strip new observations. Native authenticated GitHub submission remains
independent of hosted readiness. The actual terminal-chat error/incident path
persists the six observations in the complete approved report. The minimal
profile timeline summary and journal receipt do not establish a durable retry
countdown or an automatic-resume schedule. Updated hosted source is supplied,
but service deployment is not implied by source review or CI.

## Gateway Repository Validation

The [feature design](gateway-managed-repository-validation-2026-11-14.md)
describes the Repository Validation tab, managed mirrors and image preparation,
explicit policy choice, read-only Check setup and advanced contract editing.
The fuller published implementation is retained; the unfinished earlier
prototype is not installed as a second competing setup subsystem.

The corrective integration preserves immutable policy snapshots and explicit
selection authority, prevents stale editors from silently overwriting another
save, retains imported entries and active-job policy files, and bounds native
preparation and window lifecycle cleanup. A first-generation mutable managed
file cannot override a newly selected external policy. A worktree-local Git
configuration that can rewrite the effective remote is rejected before a
credential-bearing fetch.

Preparation and runtime validation use the same isolated Docker endpoint.
The default profile performs honest standard-library source checks; it does
not claim Android test execution, objective-specific behavior acceptance or
device validation. The new real-Docker job runs all three configured source
commands and publishes the resolved digest, source revision and result. Final
qualification inspects that actual receipt alongside all four Gateway
platform/Python lanes and the Windows installer.

A settings conflict disables Check setup consistently with its handler. The
existing native conflict/reload regression now checks that the control becomes
available again after a confirmed reload. The Windows containment assertion
resolves both paths before comparing them, preserving the containment guard
across equivalent platform path representations. The conversation observer
cancellation fixture now schedules cancellation when the actual model worker
enters, so a slow durable-admission step cannot make the test fail before the
operation under test starts. It still checks observer cancellation, a shielded
worker and exactly one durable assistant reply; no runtime timeout is relaxed.

## Complete authentication test selection

The prior explicit four-module authentication command omitted eight tests:
three report-extension cases and five failure-detail cases. Both CI entry points
and the full local validation command now use package-qualified discovery with
verbose method names. The recorded component run executes all 127 unique tests;
final composed execution and hosted workflow logs must demonstrate that same
selection. Triggers, matrices, timeout budgets, existing jobs and the fast local
command remain unchanged.

## Verification boundary

Focused component tests are source-scoped evidence. Their counts overlap and
must not be added as a total of distinct tests. The complete successor must
regenerate its app source inventories, architecture index and Gateway package
hashes, preserve release scope, and pass canonical local checks before final
publication. Named regression inventories are rebound to the composed source.

The accepted Gateway source including the reasoning-probe correction completed
84 Python suites: 1,223 cases, 1,196 passing and 27 declared platform/display/network
skips, plus seven passing browser cases. The 50,000-event stress case is included
in the Python total. All 304 packaged source hashes and the matching manifest
were bound before and after that execution. An earlier attempt stopped at a stale
manifest guard and remains recorded separately. The recording wrapper's Node
summary-format correction reparsed the successful raw log without rerunning tests.

The later #1203 source adds Gateway behavior and therefore requires a new complete
run and regenerated package hashes. Actual native Windows execution, compact
settings captures and real Docker validation remain final hosted gates. Fresh
package-qualified authentication discovery passed all 127 methods; its 25 bound
inputs were unchanged through the Gateway run. That result remains source-scoped
until the final union is checked for changes. Canonical local checks, formatting
and the Android method inventory are rebound after all source corrections join.

Merge additionally requires the actual final-head Android unit and core-domain
results, all dedicated heap cases, API 34 and API 36 acceptance execution, APK
method/native/runtime checks, diagnostic audit, Gateway and Windows packaging,
Marketplace, Kotlin formatting, CodeQL, APK catalog/download contracts and
canonical Required verification. The current main revision, repository rules,
reviews and mergeability are checked immediately before a normal expected-head
merge. A previous head's success, skipped applicable gate or cancelled run
cannot qualify the successor. Final execution receipts are recorded in the PR
conversation, avoiding a validation-only source commit that invalidates its
own receipts. Individual closure comments follow the qualified merge.

## Remaining evidence that cannot be manufactured

The earlier supplied archives were integrity-checked and their available records read.
The latest partial capture contains 312 of 370 workspace rows; 58 absent rows
require resumption on the source phone and are not reconstructed. The later
21c055de capture completed all 20 selected stages, with all stage/chunk integrity
checks passing. It is a separate bounded snapshot, not a reconstruction of the
missing earlier bytes. Its individual records and source corrections are
described in the [additional archive review](diagnostic-21c055de-2026-11-14.md). The later
[97b8bc5c review](diagnostic-97b8bc5c-2026-11-15.md) separately binds health and
CI-diagnosis reports, exposes the admission/progression/pagination defects, and
qualifies the first-versus-final verdict evidence. It does not reconstruct any
earlier missing rows. Unavailable
private endpoints, model behavior, original tool outcomes and historical
exception operands remain unavailable unless their actual observations exist.

Voice transcript/playback ownership, normalization, bounded queues and CPU
worker policy have source and regression coverage. The affected phone's
pronunciation, physical audio latency, GPU acceleration, microphone timing,
IME/gesture behavior and frame-time improvements require matching hardware
execution. Kokoro provides no qualified exact-word timing callback; captions
do not invent such timing. The repair does not claim that the original
H-E-R-E playback was physically reproduced.

An inherited CodeQL zip-slip finding remains attached to unchanged installer
source. Canonical/lexical containment guards and their regressions were reviewed;
no escaping path was reproduced, and the finding was not dismissed. Existing
pinned optional-companion dependency advisories also remain part of the
baseline. Restricted application use reduces some exposure but is not proof
that every advisory is unreachable or resolved. No unrelated force upgrade or
suppression is used to manufacture a green gate.

## Complete successor local validation

The final composed Gateway source completed all 87 Python suites: 1,277 cases,
1,250 passes and 27 explicit platform/display skips, plus seven passing browser
checks. The 312 packaged source files and manifest stayed unchanged throughout
the run. Authentication's 127-method result carries forward only because all 25
bound inputs remain byte-identical. Canonical fast validation passes 511 tests;
all 106 changed Kotlin files pass pinned ktlint 1.3.1. These local results do not
replace the final hosted Android, native Windows, Docker, APK or security gates.
