# Diagnostic 2a97f916 audit and public progress previews

## Scope and source identity

This work audits the supplied diagnostic archive against PR **#2367** in
`tailscale-signin/GPT_Mobile_AI-improved`, branch
`fix/diagnostic-execution-completion-2026-11-22`, and implements the user's requested
improvement to empty, repetitive progress descriptions. The presentation goal is
brief, specific previews with useful Markdown and evidence-backed statistics. It
does not claim to reproduce OpenAI's proprietary implementation.

The archive is
`partial-diagnostics-2a97f916-3e5f-4bc0-9cb9-f72608c5d20f-part-1-37fab0e9-97c7-4bac-bcf4-18b08bd5182d.zip`.
Its debug app is **0.9.66.15, build 149**, commit
`bd3d9a69a6dd537862fa909cbc3abb9c9ef02bb7`. The selected workspace is `main` at
`8095c1aee67b64584033289944eff1dfac7a7b48`, also the observed PR base. These are
different identities from the app build (`capture-context.jsonl:1–2`).

The public PR heads observed during this audit were
`dcaa851dce7fb7f602ed6d230f24bdf69838c06a`, then
`a38d3fc593bb16aa898212c46824d8d3de27aea2`, then
`b50c29febaeb54808a4561955220f5b33bd09253`, followed by
`55b8490ffdb7a11d78f9721d422c78b2ea467948`, then
`f06066bc6016100d2ef7ac55c9f36ebb2d954cda`. The local preview implementation
checkpoint is `edf256e3fa1090ae9eb18a61527c81f639fda925`, on
`fix/summary-audit-2026-11-23`. The provider changes through `b50c29f` were independently reviewed: they
strengthen refusal of unsafe replay, including accepted/unknown remote state and
explicit retry refusal. They do not establish recovery of the captured missing
wait-decision path or repair the task-continuation findings below. The [task checkpoint](diagnostic-2a97f916-progress-previews-2026-11-23-task.json)
records the validation boundary. The final integration preserves the independent
privacy-lifecycle test change at `55b8490` and checkpoint-report attribution,
document-test synchronization and exact model-pin scanner changes at `f06066b`.
The tested preview inputs retain their exact hashes. The checkpoint-report change
rejects an unsupported extraction-stage label; it does not establish provider-wait
recovery for the captured primary invocation in #2442.

## What the archive establishes

Physical JSONL references below identify archive records; chunked records were
reassembled before interpretation. There are **14 runs**: two COMPLETED, two
PARTIAL, six BLOCKED and four EVIDENCE_MISSING (`runs.jsonl:1–15`). The 176 complete
tool events contain 172 COMPLETED and four FAILED results; the last coverage row
is metadata (`tool-receipts.jsonl:269`). The archive also contains 88 completed
model invocations and 20 conversation records (`conversation.jsonl:82`). No
captured tool invocation stages a patch, commits, creates a branch/PR, or closes
an issue. Model completion and HTTP success therefore do not establish completed
engineering work.

The observed run interval is November 23, 2026, 19:38–20:44 America/Toronto.
Process-wide logs and app-report history include other chats. In particular,
provider report #2442 and saved-page report #2445 concern chat 17; the selected
conversation is chat 18. Repeated critical/recent log entries are duplicate
reporting, not independent failures.

The conversation contains 101 progress checkpoints and 16 status summaries; all
16 summaries have `modelAuthored=false`. Examples include an “inspection
needs attention” card for issue #74 despite that issue being closed, and a failed issue read
followed by generic “returned a result for inspection” bullets
(`conversation.jsonl:2–22,73–79`). The session-question opening embeds a truncated
question as its implementation subject (`conversation.jsonl:80–81`).

## Preview repairs implemented

**Restore the actual public-commentary instruction.** GitHub requests previously
omitted `ToolProgressTracker.SUMMARY_INSTRUCTION`; their alternative instruction
was inside a bounded repository snapshot that could cut the progress tag, and
the compact integration path omitted it. `PublicProgressInstructions` now supplies
the complete closed-tag instruction in the primary prompt, outside that snapshot,
including the local-model path. It requests a concrete finding or decision,
practical consequence, and useful next action, while keeping the final answer
separate. Generic reassurance and tool recaps are discouraged. Public commentary
uses the selected model's existing response; no extra remote generation is added.

**Retain closed public blocks and useful new analysis.** The parser publishes each
closed block even when the same chunk starts an unfinished later block. Unclosed
text and non-model lineage remain private. Summary admission permits a second
material interpretation of already-read source. Normalized duplicate wording and
known activity-only sentences are suppressed, while a substantive finding after
a boilerplate sentence is retained. Evidence and transition identity distinguish
a repeated update from a newly recurring blocker or reopened issue.

**Present resource facts accurately.** Approved issue/PR observations retain their
individual identities and states, bounded to the producer's four items. Fresh
state changes retire stale descriptions; reopening is visible even without model
commentary. Resource keys include explicit repository scope. Historical titles
such as “Build failure” cannot become current operation failures. Routine targeted
API-read labels remain activity previews instead of creating empty finding cards.
Successful source observations and real blocker/recovery transitions remain visible.
The session-question opening is corrected; historical evidence binding itself is
still a separate audit finding below.

**Render supported Markdown without weakening numerical provenance.** Emphasis,
inline code, headings, lists and tables use the existing Markdown renderer without
requiring a fixed heading-plus-bullets template. Literal filename underscores stay
intact. Count parsing requires the approved recorded-check prefix; quoted numbers
inside an issue title cannot become chart data. Source-owned tables retain their
exact source, coverage, chart and CSV contract. Model-authored tables cannot gain
source chart/export actions. The 45-second first-summary and 135-second follow-up
cadence, privacy boundaries and optional narrator budgets are unchanged.

## Remaining audit risks and recommended solutions

1. **Historical questions lose available session evidence (#2446).** The exact “What issues
   have you discovered this session…” question produced an eight-point draft, but
   claim extraction received an empty catalog and the verifier replaced it with a
   no-evidence answer (`workspace.jsonl:4,10–13,19–20`; `conversation.jsonl:80–81`).
   Recognize retrospective session intent, project historical run/error evidence
   with its original scope, and preserve the active repair task. Test this wording
   without new GitHub calls or inherited authorization.

2. **Execution ends with work and budget remaining (#2441 and #2444).** The last repair attempt had
   499 of 512 calls remaining, zero source ranges and zero staged changes, then
   returned another plan. Its output checkpoint was complete while task acceptance
   still required continuation and the wake owner was unconfirmed
   (`workspace.jsonl:79–85,94–98,112`). Persist a concrete next unmet operation and
   accountable worker; stop for a real blocker or limit, not a plan-only draft.

3. **Local branch-selection reconciliation stalls repeated resumes.** A `resolve
   main` call returned RECONCILIATION_REQUIRED with `dispatched=false`; four retries
   ended with pending-tool continuation (`tool-receipts.jsonl:231`;
   `run-journal.jsonl:907,947,983,1038`). Verify the saved local selection and current
   authorized target, then settle its owned handoff. Preserve barriers for genuinely
   uncertain remote writes rather than treating every selection as stateless.

4. **Replay capacity and absent evidence need different recovery.** Runs 2 and 5
   stopped because a complete saved page could not fit the replay allowance. Bound
   the next page by actual serialized-message capacity and retain exact issued
   reopening recipes. Separately, #2445 recorded 6,627 tokens against a 24,000-token
   allowance: its missing required page is not a capacity overrun
   (`latest-state.jsonl:36`; `recent-app-logs.jsonl:1268,1286,2075`). Pin admitted
   bodies until exact delivery, or reacquire the authorized body before advancing.

5. **Retry and pagination must preserve typed state.** #2442 recorded a retryable
   HTTP 429 with no accepted request or visible output, but no usable durable-wait
   decision (`latest-state.jsonl:33`; `recent-app-logs.jsonl:668–698`). Trace the
   complete OpenRouter cooldown-to-resume path; the missing raw response prevents
   exact incident replay. For #2443, the final page was complete before the model
   invented an unissued cursor (`tool-receipts.jsonl:258–260`). The rejection was
   correct. Use exact controller-owned continuation recipes and terminal-frontier
   state, preserving cursor identity checks.

6. **CI targeting and indexing need bounded execution.** The CI diagnosis found no
   exact-head failed run, then inspected an incidental skipped notification job and
   repeatedly queried page 1 (tools 159,163–165,170,173). Prefer the exact-SHA failure
   query and actual validation attempts; skipped work is not passing validation.
   Two phone-index reads reached approximately 20-second local deadlines but saved
   progress and advanced issued cursors (tools 33–37). Reduce per-batch work while
   retaining checkpoints and honest incomplete coverage.

7. **Export completion and physical UI performance remain unqualified.** The
   manifest reports 84 of 805 workspace rows, with 721 remaining. Its 95% figure
   measures section completion, not history. A retry again hit the 16 MiB headroom
   guard on a 256 MiB Java heap. This is a resumable partial capture, not an observed
   OOM. Newer source reduces payload copies and preserves cooperative one-row mode;
   verify all 805 rows across continuation parts using the same large-row fixture.
   Across 57 retained frame windows, 250 of 14,698 frames were janky (1.70%); nine
   exceeded 100 ms, maximum 201.69 ms (`recent-app-logs.jsonl`, frame-window events through line 2155). Measure the same
   content on-device before claiming improvement. Thresholded overlapping layout
   measurements and cumulative allocation counters do not prove a leak or GPU cause.

## Validation and qualification

The host harness passed **116 tests with zero failures**, compiling actual preview,
prompt and parser sources with bounded platform shims and verbatim extracted
dependencies. Its manifest records 43 full source inputs and 20 extracted sections;
source hashes matched after execution. This is host JVM validation, not an Android
build. A negative control restored the old progress controller and rich renderer,
retaining the compatible presentation helper: six of eight new tests failed as
expected. Local evidence is in `audit/summary-validation/result.json`,
`manifest.json`, `tests.log` and `baseline/tests.log`.

Gradle stopped before configuration with **No space left on device**. The added
Compose/Robolectric Markdown regression has not run. Pinned ktlint 1.3.1 passed all 14 changed Kotlin files, including the regenerated
source inventory. Canonical `bash scripts/validate-local.sh fast` passed, including 711 script
tests, generated-reference checks, secret/dependency policy checks, Android regex
validation and resource preflight (36 XML files). Full Android,
device and exact-head hosted qualification remain required. The archive contains
no retained fatal/ANR/OOM incident, but retained coverage cannot establish that no
historical failure occurred. This preview repair does not close the remaining
audit findings or qualify the user's complete engineering task.
