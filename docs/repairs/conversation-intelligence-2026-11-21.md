# Conversation continuity and diagnostic issue repairs

This work extends the existing PR #2126 on
`fix/offline-kokoro-release-publication-2026-11-21`, starting from
`ad26151b2916bf25feb20bcb99c5f233e7de790d`. It preserves the concurrent chat,
voice and publication changes and targets Android 0.9.66.12 (146) with the
matching issue-report service contract.

The user authorized implementation, issue closure after a verified repair or a
targeted reporting improvement, and merging once the current revision satisfies
the repository's checks and merge policy. The exact implementation/verification
checkpoint is recorded in
[`conversation-intelligence-2026-11-21-task.json`](conversation-intelligence-2026-11-21-task.json).

## Findings and changes

### Useful findings survive a long conversation

The retained semantic summary now carries bounded issue titles, states and
reported symptoms, source excerpts with file/line pointers, and incomplete
search obligations. Repeated tool-result compaction retains useful fragments
when a whole summary cannot fit. A continuation keeps the untouched accepted
request, the next unfinished requirement, recorded recovery decisions and a
bounded working brief. That brief is included in context admission and is
explicitly historical orientation; it cannot authorize a write or establish
fresh remote state.

Fallback fact selection removes repeated resource/revision aliases, excludes
recovery recipes and prioritizes facts relevant to the requested task. It scans
at most 128 receipts, keeps at most 64 candidates and displays at most 16 facts.
The working brief considers at most 32 summaries and keeps at most eight findings
under 6,000 characters. Counts disclose omitted observations.

Progress problems are scoped to the operation, resource and revision. Matching
successful recovery clears its active warning. An unrelated result, cached
success or a healthy but incomplete checks page cannot clear a known failure.

### Tool calls agree with their advertised contracts

PR listing accepts and carries `head`, `base`, `sort` and `direction` through
validation, URL encoding, dispatch, pagination and query identity. GitHub's
direction defaults are preserved: `created` descends; the other supported sorts
ascend. Explicit selections remain unchanged.

Mixed saved-view requests normalize only against an exact issued cursor and its
original call from the same tool and connection. A missing issue `after` value
can be recovered only from one exact successful issued recipe. Conflicting,
altered, ambiguous and cross-tool cursors remain rejected. Reports distinguish
the six issue-continuation rejection states and retain field names and schema
identity without exporting private argument values. The native dispatch-accounting
wrapper preserves the complete typed rejection shape without allowing diagnostic
fields to replace measured dispatch, HTTP or mutation accounting.

The late catalog reports are handled at discovery admission: explicit known
single-tool names can be canonicalized without guessing, optional null selectors
receive narrowly defined handling, and an action without a selected tool can
request visible catalog discovery. Discovery correction cannot execute that
action or acquire a hidden tool. Reports retain the selector's public field
name/type and distinguish null from other invalid types.

### Search can progress past an unreadable blob

The search traversal persists no-progress observations for the exact search and
cursor. One timeout permits a retry at the same boundary. A second stalled read
defers that pinned file and advances to later files. Deferred path/SHA and skipped
coverage remain explicit: neither `complete` nor `matches_complete` can become
true while that coverage is missing. The page-cache identity includes prior
coverage, so a cached complete tail cannot erase a newly deferred prefix.

### Recovery keeps the actual cause

Internal verification exceptions now have the separate
`GITHUB_VERIFICATION_INTERNAL_ERROR` code and a bounded emergency report that
does not invoke the failed formatter/verifier again. It retains a small set of
captured successful source observations and their receipt/revision pointers,
preserves an existing primary failure, and never claims task completion.
Evidence-epoch publication checks still prevent stale findings from being
delivered as current. A rejected fault-fallback publication cannot restart the
normal failing renderer. If publication access cannot be revalidated, the app
returns one sanitized blocked failure before reading fallback receipts or
publishing saved content. Cancellation, ownership refusal and the existing
configuration-invalidated lifecycle keep their established behavior.

Output continuation distinguishes no saved task, capacity rejection, observed
HTTP rejection, transport failure and another opening failure. It records the
trigger, saved-character count, opening attempt and output cap. HTTP 413 calls
for replanning the request budget; it does not authorize replay. Cancellation
and changed authority continue to propagate.

Saved provider recovery retains its typed error instead of becoming generic
`CHAT_FAILED`. Checkpoint mismatch reasons identify state, resume permission,
digest, task/tool binding and evidence digest failures, with a separately hashed
observed checkpoint. Ownership, digest and remote-outcome fences remain intact.
Typed 429 scheduling evidence can pass through bounded recognized transport
wrappers while preserving the exact retry deadline and route binding; message
text cannot create a retry decision.

An over-capacity planning failure now retains the profile/model and consumed
capacity observations available at the planning boundary and offers budget
replanning. The conservative 4,096-token fallback and all admission arithmetic
remain unchanged. An unknown model capacity is not enlarged to force admission.

When informational claims have no source receipts, recovery explicitly says
observations have not been collected. A nullable count from the actual advertised
stream-round catalog distinguishes an observed zero from unavailable evidence.
Unsupported claims remain unverified.

### The next report carries actionable evidence

Reports preserve the verification phase, bounded exception evidence,
continuation cause, checkpoint mismatch and issue cursor state. The diagnostic
export's reduced-memory path begins with one raw record, reducing allocation
pressure while retaining compact causal summaries.

The Android and hosted reporter validate the same closed fields, types and
bounds. New fields/codes require `CONVERSATION_RECOVERY_DIAGNOSTICS_V1` through
the existing extended readiness check. Legacy readiness remains below its 1 KiB
limit. An older service leaves the exact approved report pending before POST;
the client does not discard approved evidence or silently choose another
destination. The compatibility regression checks both zero dispatch to an
unsupported service and byte-for-byte submission to a compatible service.

## Combined-branch integration corrections

The incoming approved design adds request-byte budgeting, frozen independent
issue groups, recovery controls and settled journal compaction. Reviewing those
changes against the existing execution paths exposed additional integration
failures, now covered by focused behavior regressions.

Verification-only recovery is fenced in the runner before any saved queued call
or synthesized recovery operation can execute. It may restore settled evidence
through the existing authorization callback, but it cannot dispatch tools,
reopen rejected work, mark results consumed or finish the execution checkpoint.
A session that executes tools internally is rejected before its callbacks run.
Unsettled remote operations still require reconciliation. Newly observed
evidence cannot turn a verifier-only decision into automatic execution.

A complete trusted live intake with no open issues can satisfy an issue-only
obligation without inventing a code change or test run. Extra conditions and
unrelated work retain their separate acceptance requirements. A closure-only
request requires guarded closure and fresh readback for every frozen issue;
repair requests retain diagnosis, a published implementation or targeted
diagnostic improvement, revision-bound tests and closure. The actual wording
“close them once fixed” remains a repair request, as do equivalent bounded
conditional repair phrases. Quoted examples and unrelated file fixes do not
change the issue work type.

Settled journal maintenance tracks each active invocation rather than treating
a late finish observation as permanently active work. Matching finishes release
only their own invocation. Unknown starts, unmatched finishes, pending writes
and changed privacy or capture epochs remain conservative. Export preparation
keeps caller admission callbacks outside the write mutex, preventing privacy
clearing or revocation from deadlocking when saved checkpoints are present;
cancellation and the subsequent privacy and epoch checks remain enforced.

The request-byte failure code requires `REQUEST_BYTE_BUDGET_V1` even when no byte
count fields were captured. The merged reporter tests cover every retained code
location, each byte field, omitted fields, UTF-8 approved payload preservation
and a free-text mention that must not invent a capability requirement. An older
reporter receives no POST; a compatible reporter receives the unchanged
approved payload.

## Issue disposition map

The issue record should link the final implementation and its checks before
closure. Closing an intake under the user's diagnostic-remediation instruction
does not establish that an unreproduced historical runtime cause is fixed.

| Issues | Observed problem | Implemented disposition |
| --- | --- | --- |
| #2215 | `head` was present in a rejected PR-list call | Repair the advertised/executed list contract, query identity and continuation. |
| #2223 | Branch call mixed with a saved-view cursor | Normalize only a matching issued read recipe; retain conflict rejection and precise diagnostics. |
| #2224, #2226 | Issue-list arguments mixed with saved pagination | Apply the same exact source/tool/connection binding. |
| #2222, #2225 | Issue continuation rejected without enough cause detail | Add six typed continuation states and exact issued-recipe recovery. The original causes remain unproved. |
| #2218 | Continuation opening ended in an unclassified unavailable result | Preserve opening causes and actionable recovery while retaining the saved answer. The historical trigger cannot be reconstructed. |
| #2220 | Typed checkpoint failure became generic chat failure | Preserve the exact code through chat/report/persisted terminal classification and capture the mismatch reason. |
| #2214, #2229–#2232 | Metadata-heavy partial fallback and unmeasured remaining requirement | Preserve task-relevant findings and continuation context; contain and diagnose verifier faults. The attachment identifies cast text for the correlated run, not its source location. |
| #2227, #2228 | Linked receipts were unsuccessful or authorization was unverified | Preserve useful observations and unmet requirements separately. Retain eligibility and authorization checks; do not promote failed or unverified work to success. |
| #2233–#2236 | Catalog `names` had an invalid type while the report hid that field | Add bounded discovery normalization/correction and field/type visibility; retain no-hidden-tool and no-action-execution fences. |
| #2237 | A request exceeded a conservative capacity estimate before dispatch | Preserve planning identity/capacity observations and budget-replanning guidance. Keep the capacity guard. |
| #2238 | Informational claims had no receipts and no source calls | Identify missing observation collection and capture actual advertised-tool availability without inventing a count. |

## Validation

Validation results and the exact head/base policy check are recorded in the task
checkpoint. Focused behavior regressions cover retained findings, same-resource
progress recovery, cursor/recipe identity, delayed-blob traversal with an older
cache, typed fault publication, continuation causes, checkpoint fences, wrapped
rate-limit evidence, catalog discovery, admission observations and reporter
compatibility. The integrated Android source compilation and test gate exercise
the actual implementation; isolated host probes alone are insufficient.

The local repository fast gate passed 592 tests after the concurrent readiness
and progress fixes; the reporting service passed 190 tests. Focused current-source
compilation and execution passed 100 catalog/contract/recovery tests, 56 native
GitHub/dispatch-accounting tests and 158 conversation/verifier tests. These are
separate selections with some shared dependencies, not a claimed unique total.
The harnesses use actual current sources and existing app dependencies without
handwritten stubs. A separate focused compile passed for the main chat repository,
continuation plan and conversation UI.

The diagnostic selection passed 214 of 217 executed tests; the remaining three
need Android's native clock implementation and cannot run in the plain JVM
harness. Its Robolectric incident suite was compiled but left for the Android CI
runtime. No behavioral assertion failure remains in those focused diagnostics.
These results do not replace full Android compilation or execution.

Production/test Kotlin formatting, source inventory, release scope and diff
checks passed. KSP code generation passed, but the local KAPT worker repeatedly
exited without a source diagnostic. Those attempts do not establish an Android
compile or test pass. The first CI compilation identified a List/Collection
mismatch in the verifier; the integrated correction preserves List typing.
The unchanged GitHub CI gate supplies the full combined-head Android compilation
and execution evidence; its final receipts are linked from PR #2126 and the
issue resolutions.

The merge gate requires successful checks for the final PR revision, an
up-to-date base and GitHub's current merge policy. A passed check from the starting
revision does not validate these changes.

### Final combined-source local checks

The follow-up correction is integrated on the full-scope revision
`e66e9665402b388a673dd42dc3ddc70fe3e3ea29`. The canonical fast gate passes
**601 tests**; all **14** changed non-generated Kotlin source/test files pass
formatting. The regenerated source inventory contains **2,834 files / 26 areas**,
and the complete **571-path** pending release scope is checked without expanding
its qualification claims.

Focused actual-source selections pass **31** runner/recovery tests, **36** issue
obligation tests, **114** journal/report tests, **51** catalog tests and **7**
effective-recovery policy tests. These selections overlap. The missing-saved-task
incident regression also passes on the actual Robolectric Android 34 runtime,
including its zero-provider-call and approved incident privacy assertions.
The telemetry schema regression is repaired by shortening four descriptions:
its estimate changes from **2,026 to 1,992 tokens**, while the original
**2,000-token bound** and every non-description schema constraint remain intact.

The preceding full Android run compiled production and tests, then identified
three remaining assertions. The combined correction addresses the schema budget
and the two outdated expectations for current report capability and typed
missing-task recovery. These local results do not claim a full final-head
Android pass. The unchanged required and applicable CI gates must validate the
published combined correction before merge and issue closure.

### Full-suite terminal publication follow-up

The full suite at `c7d507a32986f75adab38b303bdb3019b0c889a4` compiled
production and test sources and ran **12,095 tests**, with **six failures** and
**five skips**. The concurrent corrections through
`5b433508af9be0181c3ec7057aba8353efc68a4a` address all six: one stale tool-grant
expectation, two stale execution-continuation expectations, incomplete-evidence
incident export, the deadline control's missing scrollable test host, and a
duplicate native report publication. Their actual-source selections pass
**63** runner/verifier/progress tests, **two** incident-export tests and **four**
Android 34 Compose recovery-control tests. These selections are not a unique
combined total.

An independent follow-up reproduced the same duplicate in final review-failure
delivery against the incoming production fix: **eight** focused tests ran and
the **two** review-publication/checkpoint cases failed. The supplement applies
the existing application-report flag to that terminal path. A previously
delivered application report is not published again, its original typed failure
and invocation remain intact, and the pending receipt checkpoint is not finished.
No report, whitespace-only reports and unverified model text still permit the
saved-report fallback. Authority, uncertain-outcome, tool-budget and verification
rules are unchanged.

The final focused selection passes **39 tests** using actual current production
sources and dependencies without handwritten production stubs. It includes the
strict single-status-report regression, both publication paths, original failure
identity, pending receipt preservation, verification-only recovery, child
isolation and user-decision behavior. The tested source hashes are retained in
the local integration record. Full Android and every applicable check must still
pass on the final published revision; final merge and issue-closure receipts are
recorded on PR #2126.

### Review APK build phase correction

The earlier `4e40cd883bf0b6d910a4d9c6ef99d48891f7a8ca` UI Review APK run
`44971090566`, job `137055166480`, reached the build step's **30-minute** limit
while still compiling x86_64 Vulkan shaders. Production Kotlin and Java
compilation had completed at 01:58:43 UTC. DEX packaging continued until arm64
native configuration at 02:06:16; x86_64 native work began at 02:10:03/07, leaving
only **42 seconds** before the outer step terminated the process. The log shows
ongoing build progress, not a source compiler failure.

The review workflow now runs debug KSP, KAPT and production compilation in
separate fresh processes, following the existing canonical validation pattern.
Their outer bounds are **10 / 10 / 20 minutes**, with **540 / 540 / 1,140-second**
child-process deadlines. Full APK assembly retains its **30-minute** outer bound
and gains a **1,740-second** child watchdog. The **90-minute** job cap, compiler
heap limits, worker limit and packaging parallelism remain bounded. Every native
backend, packaged method-size/runtime/privacy check, device-regression and
benchmark compilation, and required APK artifact is retained.

Parsed workflow comparison confirms that all existing steps and settings are
preserved after accounting for the three added phases and the assembly watchdog.
The **49** targeted CI/watchdog/pipeline tests and the **601-test** canonical fast
gate pass; those selections overlap. The combined **577-path** release scope
retains all **87** disclosed limitations, and the app source has no further diff.

This workflow correction does not alter app behavior or turn the timed-out
historical build into a pass. The final revision must still complete the full
review build and every other applicable CI workflow before merge.

## Limits

The archived `Long` to `Integer` failure lacks the source stack required to
identify its original cast site. The new fault capture is targeted diagnosis and
containment, not proof that this historical cast has been eliminated. Other
reports omit the original continuation and checkpoint trigger, which likewise
cannot be reconstructed from hashes alone.

The partial attachment does not establish a native crash, GPU fault or a measured
improvement in physical-device memory, frame pacing, latency or battery use.
These changes require an updated installed app; new hosted report evidence also
requires the matching reporter capability. Runtime qualification remains
separate from source, host tests and CI. Unsupported acceptance claims, missing
authorization, incomplete search coverage and uncertain remote outcomes remain
explicitly unresolved until their own evidence is collected.
