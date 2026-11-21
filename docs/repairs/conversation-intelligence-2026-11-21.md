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
identity without exporting private argument values.

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
normal failing renderer.

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

The local repository fast gate passed 589 tests and the reporting service passed
190 tests. A focused compilation of 13 actual current production files and five
test classes passed all 100 contract/recovery tests against existing baseline
app dependencies, without handwritten stubs. Production/test Kotlin formatting,
source inventory, release scope
and diff checks passed. KSP code generation passed, but the local KAPT worker
repeatedly exited without a source diagnostic. Those attempts do not establish
an Android compile or test pass. The unchanged GitHub CI gate supplies the full
Android compilation and execution evidence; its final receipts are linked from
PR #2126 and the issue resolutions.

The merge gate requires successful checks for the final PR revision, an
up-to-date base and GitHub's current merge policy. A passed check from the starting
revision does not validate these changes.

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
