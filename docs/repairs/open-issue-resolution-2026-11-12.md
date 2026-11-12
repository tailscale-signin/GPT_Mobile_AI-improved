# Open issue repairs — 12 November 2026

## Scope and evidence

Work targets `tailscale-signin/GPT_Mobile_AI-improved`, branch
`fix/session-reliability-reporting-2026-11-12`, PR #838. The starting branch head
was `e1a754336d291ee1ad72878078d7f854d3d0735e`; the base branch was `main` at
`97f10ff7a1f3789fb82d958b2dc6b22c8074dfdf`. Concurrent diagnostic repairs published
through `77c55ccbd0891e4b1500adb3d41a6dcc51951686` were incorporated before
publication, including report-input/provenance follow-ups, additional durable
recovery, shared gateway polling, typed provider/context-4 reporting, integration
readiness, and transcript projection repairs. Overlapping source changes were
reconciled and validated together. A final guarded update also incorporated
extraction deadline/authority, nested speech markup, and validation-report
follow-ups from `36ba1b11` and `77c55ccb`.
Publication uses a normal fast-forward push; approved app-report bodies are
preserved.

The initial inventory contained 18 issues: #841 and #848–#864. A second inventory
added #865–#878; the prepublication inventory also added #879 and #880. The 34
reports reference installed app commits, principally
`956d50d320c9d2f4794a21eee6886b39eaa14ded`, with the latest two on
`f71b0d355a84c46e34d4c88632c86e64d523a643`. Those revisions predate the additional
source repairs below.

All 20 stage digests in diagnostic capture
`a74d18ed-d2eb-43d2-bde1-cc82a48cff83` were verified. Its archive SHA-256 is
`17895bc1d585973c46d115202082a12b0b1a88809213a3dd5d926b979ef38397`.
The retained log prefix ends at approximately 19:38:08 UTC. A later failure is
not explained by that prefix. Run correlation uses the capture's documented
hashed identity, not timestamp proximity. Raw diagnostic content is not committed.
The uploaded diagnostic inventory was checked again at 20:55 UTC; no newer
capture covering the later incidents was available.

## Implemented repairs

### Provider waits and retained tool work

Provider recovery now preserves the receipt journal belonging to the original
accepted turn when the resumed coroutine receives a new authorization revision.
Current authorization and saved-evidence checks still run before and after disk
resolution. A recovered key never restores old permissions.

Older checkpoints can recover a missing journal key only when a bounded scan
finds one unique, settled journal with the exact protected provider-evidence
digest and matching chat, turn, profile, task binding, and hashed filename.
Pending or uncertain operations, changed evidence, malformed digests, ambiguous
matches, and incomplete scans cannot grant recovery. The same digest guards the
initial runner restoration, closing the interval between lookup and use.
The guard remains armed if a dispatcher handoff cancels restoration. Later
authorized tool work uses current receipts rather than the old evidence digest.

The disk regression executes a completed write, two rejected provider attempts,
and a successful resumed answer: one mutation, three model rounds, and no repeated
tool action. Other regressions reject mismatched ownership and a journal changed
between lookup and restoration.

Research recovery also uses a semantic policy revision. UI/readiness observations
do not create a different recovery action. A legacy revision migrates only when
its complete saved hash equals a freshly computed current legacy hash. Task,
action, destination, stage, evidence, checkpoint, and original request time remain
bound to the accepted work.

### Claim extraction and tool diagnostics

Claim extraction preserves provider and transport failures instead of treating
them as malformed JSON and dispatching repeated repair calls. A complete,
schema-valid response remains usable when the provider reports a length finish.
Smaller claim batches require an explicitly classified local parse/size failure
after completion. Matching an error-code string cannot authorize another attempt.
Later length or completion frames cannot replace an earlier provider failure;
later uncertainty or replay prohibitions can only restrict repair. Deadline
handling distinguishes an unfinished provider stream from local processing
after completion, retains completed extraction batches and an earlier terminal
cause, and cannot turn an explicitly nonretryable response into a repair call.
Saved-report delivery carries no new invocation or dispatch facts, and unresolved
tool outcomes retain reconciliation priority.

The draft, completed claim batches, invocation, HTTP information, and failed stage
remain retained. Extraction failures cannot enter generic provider-wait replay or
corrective execution. Reading a saved report creates no current invocation or
dispatch authority.

Tool prechecks retain bounded, declared missing/invalid field names and typed
causes, without recording argument values or arbitrary extra field names.
Issue-selector guidance applies consistently to invalid numeric-string selectors
while preserving strict validation. Known native precheck causes map to existing
public issue categories only with explicit predispatch evidence. Accepted or
uncertain remote outcomes cannot be reclassified as an unsent precheck.
The concurrent context-4 reporting extension retains typed recovery, extraction,
and precheck causes while preserving already approved older payload bytes. The
new journal guards emit the same closed recovery failure type, including the
protected checkpoint correlation where available, so stricter restoration does
not regress into a generic tool failure.

### Queued gateway model loading

Queued gateway prompts previously waited for readiness while bypassing the
selected-model preparation used by an immediate send. An unloaded model could
therefore leave the accepted prompt waiting indefinitely.

The accepted head prompt now prepares its exact selected model under the existing
global queue lease. The queue revalidates its owner, prompt, profile, policy,
selection, cancellation/pause state, and absence of prior generation before model
load boundaries. Actual generation still requires fresh online conversation
readiness. Started or uncertain generation work stays on its reconciliation path.

A definitive load rejection creates a durable stop on that exact lease. It
survives restart and ordinary readiness updates. A distinct **Retry model
preparation** control can release it after checking current ownership and state;
**Check saved jobs** remains read-only. Unknown model-load outcomes retain their
original persisted operation identity for reconciliation.

### Microphone capture and validation

Commit `76dcfcce` makes playback audio focus advisory for foreground microphone
input. Permission, foreground state, recorder initialization, silenced capture,
and cancellation checks remain enforced. The two reports with this exact cause,
#841 and #852, were closed after the hosted Android unit suite passed all 37
affected microphone/recognizer/reporting regressions.
The [earlier diagnostic analysis](session-reliability-followup-2026-11-12.md)
correlates #841 with capture `2984dfe7` critical records 56 and 62; its public
exception alone does not state the focus-denial cause. Hosted evidence is in
[run 43293697498](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43293697498),
[artifact 13978924883](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/43293697498/artifacts/13978924883),
SHA-256 `9abdfbefcfb191000e6ace20fbba6a1a89f41541c6130453fb975f8612d9e8ec`.

Known failed accepted chat replies in voice retain their original chat incident and
recognized draft. They no longer produce a second generic voice failure or
silently discard words when the accepted reply fails. Resuming opens draft
review; it does not resend the turn. Missing-metadata failures keep a correctly
staged delivery report, while actual microphone and playback error handling
continues to report its own causes.

### Readable speech and earlier startup

Both Android system speech and Kokoro streaming now use the same bounded
speech-text projection. It retains readable prose and descriptive link labels
while omitting raw web addresses, link destinations, inline/fenced/indented code,
HTML code bodies, comments, and formatting syntax. Captions retain the complete
original response. Incomplete words and markup remain withheld across streaming
snapshots. Bare links use a short spoken label, code payloads use at most one
plain-language notice, and benign inline prose remains readable; technical
payloads are not read aloud.

Both routes can begin after four complete words when no earlier short clause is
available; continuation chunks keep their existing bounds. The 12,000-character
speech cap does not expose a cut word or surrogate pair, repeatedly parse later
caption growth, or replay already spoken words. Playback-generation, revision,
and cancellation checks remain enforced. The configured speech rate is preserved. Prepared session text travels through
explicit plugin/deferred/native entry points without a second Markdown pass;
raw whole-text calls project once, and streamed synthesis only normalizes the
already prepared words. Nested/escaped labels and matching line-based code
fences retain readable text without exposing code payloads.

Four actual-session regressions reproduced the original content/startup defects.
The a08f99e2 source checkpoint voice suite passed 102 tests, including the
concurrent comparison/reference protections and typed reply-failure correlation.
The earlier speech component passed 69 tests plus 19 independent incremental
probes before that merge. These prove earlier text eligibility
for playback, not a measured reduction in end-to-end handset latency. Issue #879
remains open for a device timing capture of the reported five-second delay.

### Required integration stop reasons

Required capability prerequisites and independent acceptance evidence now take
priority over retained optional inventory when explaining why an integration
task stopped. Pending continuation counts remain visible with neutral wording.
The report no longer implies that a required local fragment is optional or that
a sampling cap was reached merely because a continuation exists.

The planner, completion predicates, accepted budgets, sampling bounds, approval
requirements, and uncertain-mutation rules are unchanged. Two tests reproduced
the prior masking behavior; the final report/planner suite passed 62 tests.
Issue #880 still needs the accepted contract and checkpoint/receipt ledger to
identify its two unmet criteria.

### Complete validation report inputs

The hosted acceptance reconciliation step previously read only app reports while
the qualification receipt included app and `core-domain` reports. The workflow
now supplies both directories. The parser accepts repeated directories with
cumulative report and byte limits. Exact source/run identity, report digest,
freshness, duplicate identity, and non-promotion checks remain enforced.

## Issue dispositions

Closure records identify a repaired source cause and validation. Improving a
generic report does not establish that its original incident is resolved.
This register supersedes earlier pending dispositions for #841 and #852.

| Issues | Finding and disposition |
| --- | --- |
| #841, #852 | **Closed.** Exact playback-focus rejection is repaired by `76dcfcce`; 37 hosted affected tests passed. Physical handset qualification is separate. |
| #853, #854 | Exact diagnostic joins identify provider wait/checkpoint failures. Ownership, cooldown, terminal checkpoint, and journal recovery repairs are implemented; publication and combined validation determine closure. |
| #848 | No matching terminal run or failed acceptance criterion is retained in either available capture. An acceptance ledger is needed. |
| #849 | LLAMA completion-repair budget exhaustion is recorded, but the matching output/usage and repair ledger are absent. A legitimate bounded stop cannot be distinguished from a defect. |
| #850 | Generic LLAMA failure lacks a retained typed cause and matching detailed run. |
| #851 | Exact run completed 3 of 5 criteria with 53 receipts and no mutations. Accepted-budget and integration prerequisite defects are repaired, but the untested integration matrix and conditional fixture work remain incomplete. |
| #855 | Timestamp aligns with a research recovery guard, but the report lacks an identity join. Its app-wide history record explicitly does not attribute a conversation. The semantic-policy repair does not prove this individual report resolved. |
| #856 | Exact run appears in a prefix containing 49 completed reads. Call 50 begins at 19:38:08; the reported failure occurs at 19:38:21. Terminal evidence is missing. |
| #857 | Original invalid search arguments and rejected field names are absent. Validator diagnostics and selector guidance are repaired; the original request cannot be reconstructed safely. |
| #858, #859 | Confirmed claim-extraction handling defects are repaired and regression tested. These later incidents lack the provider/transport cause needed to establish their exact resolution. |
| #860–#864 | Generic GitHub precheck reports omit the underlying code and rejected fields. Bounded reporting is repaired; source failures remain unverified. |
| #865 | Confirmed queued gateway prompt defect: it waited for model readiness without initiating selected-model preparation. The scoped queue repair, durable rejection stop, and explicit retry control address this source cause. Publication and combined validation determine closure; live private-gateway acceptance is separate. |
| #866 | Voice reported a generic failure after chat/gateway activity, without a capture exception or stage. The typed upstream-failure reporting/draft-retention defect is repaired and regression tested; this report cannot be equated with microphone focus denial and its original upstream cause remains unavailable. |
| #867, #870 | Explicit OpenRouter privacy rejection before dispatch; recovery requires a configuration change. The selected model, restrictions, and endpoint observation are absent. Privacy settings are not relaxed by an issue-resolution pass. |
| #868, #871 | Generic Anthropic failures have invocation hashes but no retained typed cause or matching capture. |
| #869 | Generic OpenRouter failure has a run hash but no retained terminal cause. |
| #872–#874, #877 | Generic tool prechecks omit the cause/operation, except #872 identifies MCP. New diagnostics cover future precheck reports; they cannot reconstruct these missing results. |
| #875 | OpenRouter output-limit report lacks the output allowance, finish/usage receipt, and stage needed to determine whether the limit was expected. |
| #876 | A tool was dispatched and failed. The operation, error, and receipt are absent; a predispatch repair cannot establish resolution of this executed operation. |
| #878 | Claim-extraction output limit falls within the repaired bounded collector, but its original response/completion evidence is absent. Exact incident acceptance remains open. |
| #879 | Spoken URL/code leakage and avoidable startup buffering are repaired on both playback routes. Four baseline regressions reproduced the defects; 102 merged current-source voice/reporting/plugin tests passed. **Remains open:** the reported five-second handset delay requires a timing capture; host tests do not measure engine/model/audio startup. |
| #880 | Required-work blockers now take priority over optional inventory in the final report; 62 report/planner tests passed. **Remains open:** the schema-2 report says 3/5 criteria were verified, but its accepted contract and checkpoint/receipt ledger are absent, so the two remaining criteria cannot be established or completed from this report. |

## Validation boundaries

The task record contains the latest combined validation and publication status.
The independent source register retains its original checkpoint results. This
register records the later combined source and the precise issue dispositions;
in particular #856 is joined in the critical log prefix even though it is absent
from the five selected run records. Neither source provides its terminal cause.
At the source-publication checkpoint:

- Canonical host validation passed **484 tests**, plus inventory/schema,
  dependency, secret, regex, resource, icon, and whitespace checks. Pinned
  ktlint 1.3.1 passed all **122** Kotlin files changed since `9781a4fb`.
- Final extraction/session/lifecycle/failure-metadata validation passed
  **82 current-source JVM tests**, retaining actual runner/session behavior and
  corrective-execution rejection. Android/persistence boundaries are explicit.
- Merged research-store migration/policy validation passed **7 JVM tests**, with
  a host AtomicFile/evidence boundary.
- Provider journal restoration passed **10 current-source JVM regressions**,
  including a deterministic final-handoff cancellation test that fails on the
  preceding implementation. Android AtomicFile is an explicit host boundary.
- Voice reply, recovery, and incident collection passed **47 current-source host
  tests**, with explicit Android context/preferences/AtomicFile boundaries.
  The earlier speech projection/startup integration passed **69 current-source
  host tests**, plus **19 independent incremental-stream probes**; the
  first concurrent merge passed **76 tests**, and the final speech caller/markup
  integration passed **102 current-source voice/reporting/plugin tests**.
- Integration stop-reason/planner validation passed **62 current-source tests**,
  including required approval/evidence and retained local-fragment cases.
- Complete-report reconciliation passed **19 focused tests** while preserving
  nonrecursive report enumeration and cumulative count/byte bounds.
- Final selected-model preparation/admission passed **40 current-source JVM tests**.
  Eleven new actual Room/store/queue regressions await hosted Android execution
  at this source-publication checkpoint.
- The hosted artifact for branch head `e1a75433`, tested as merge `f71b0d35`,
  contained **9,605 cases: 9,600 passed, five skipped, zero failed/errors**.
  All 37 affected voice tests passed. That older run failed at the now-repaired
  report-input mismatch; later constrained-heap/APK steps were skipped.
- Replaying the authenticated 1,439-report artifact through the corrected
  reconciliation CLI passed, with 17 partial component candidates and **zero
  formal acceptance promotions**.

The merge with `589fe15e` retained the incoming provider ownership/cancellation,
shared polling, accepted budgets, schema-4 reporting, and transcript changes.
Its hosted APK-download job exposed two additional `MessageV2` fixtures missing
required content. Both now explicitly supply empty assistant content; the same correction was
independently published as `743d447c` and retained. Assertions and production
contracts are unchanged. The merged journal tests verify durable
original-receipt validation and a later new-write receipt lookup instead of
assuming an exact number of callbacks. All ten journal cases passed with the
typed guard changes; nineteen compiled source inputs matched their recorded
hashes. The merged queue/preparation suite passed 40 cases, including cancellation
between a known load rejection and durable-stop publication; the merged
extraction/publication suite passed 54 cases. The final hosted run must still
validate the Android/Room integration.

Current-head hosted compilation and Android tests, physical-device execution,
and live gateway/provider acceptance are distinct forms of evidence. A host test
or an older hosted run is not reported as current-head device qualification.

Remaining reports need a fresh diagnostic captured immediately after their
terminal failure on an APK carrying the repaired commit, including the
corresponding run/invocation or tool receipt. The relevant model
selection/readiness receipt, acceptance ledger, privacy preflight observation,
or output/usage ledger is specified above so follow-up can target the missing
cause rather than repeating a generic failure report.

## Post-publication recovery, cooldown and speech review

The follow-up recorded in [the issue verification register](open-issues-verification-2026-11-12.md#post-a08f99e2-recovery-cooldown-and-speech-follow-up) corrects the initial journal guard for compacted provider replay, aligns the visible countdown with the current shared cooldown, and preserves numeric link labels in spoken text. Its source and validation are separate from the a08f99e2 checkpoint above. Known legacy failed chat replies also retain the chat-owned incident and recognized draft without inferring transport facts from their prose.

## Diagnostic ownership review after cc31f5a3

The combined branch also retains `b0148a61` and `cc31f5a3`, including the full
protected journal boundary, shared cooldown projection, and bounded diagnostics
for an explicitly reviewed follow-up report. The new capture preserves observed
provider limits, tool/schema facts, acceptance criteria, and voice timing without
publishing prompt text, argument values, recognized speech, or audio. Existing
approved report bytes remain unchanged. Hosted context-5 delivery still requires
a service that advertises that version; this work does not deploy the service.

Independent review reproduced three attribution defects in the new diagnostics:

- Sequential Android speech chunks could overwrite a cold first engine-ready
  observation with a later warm flag while retaining the first timestamp. The
  first readiness time and its warm/cold provenance are now retained together.
- A direct Kokoro settings preview could inherit the paused conversation reply's
  timing probe and native log identity. The actual preview path detaches both;
  whole-response playback binds its supplied identity, and normal reply replay
  and microphone capture bind their own probes. Replay identities describe
  playback, not a new provider invocation.
- When persisted tool arguments were malformed, oversized, or outside the bounded
  shape traversal, receipt-supplied action/shape hashes could fill absent local
  facts. The public projection now derives its local identity and shape only from
  the persisted call. The authoritative execution receipt remains unchanged.

The checked-in regressions fail against the preceding implementations. Focused
voice/session/forwarding checks pass 114 tests; the actual-recorder check passes
eight cases, including the checked-in seven-scenario regression and its original
failing probe. The authentication/reporting suite passes 66 tests. Hosted cc31f5a3
compilation also identified two worker tests calling the old manual-report API;
their call sites now supply the displayed preview, draft and approved route while
preserving every existing destination-consent assertion. Actual Android/Room execution
and the additional callback/read regressions remain hosted gates for the published
source, including all 136 named recovery/queue methods required for the three
demonstrated-cause closure candidates.

Issue #882 arrived during validation, bringing this task's reviewed inventory to
35 reports. Its LLAMA completion-repair failure occurred at 20:34:00.057 UTC, after
the available diagnostic prefix ended at 19:38:08.815 UTC. The original output,
usage, finish and repair-attempt receipts remain unavailable. It stays open with
the other 29 incidents requiring causal, configuration, acceptance or device
evidence. Better future capture does not establish that those historical failures
were repaired. The separate source fixes for #853, #854 and #865 require the
published-source hosted test receipt before closure; #841 and #852 remain closed
with their recorded source and execution evidence.

Concurrent pushes cancelled the a08f99e2 and b0148a61 PR validation runs before
their full unit suites executed. Those cancellations are not regression failures
and are not passing evidence. The b0148a61 API-36 attempt also encountered a
launcher/system ANR before application instrumentation began; its preparation
guard correctly stopped the run. Final workflow identities, artifact digests and
issue dispositions are recorded on PR #838 and the individual issues so that
recording the result does not alter the tested source.
