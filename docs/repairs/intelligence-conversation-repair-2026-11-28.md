# Conversation intelligence and diagnostic repair

## Scope and revision

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Target: PR #2651,
`feature/windows-native-benchmark-kit`. Starting head:
`e28b5df8bf92657284dff8d65bf8b5ed747285e6`; base `main`:
`6435462ac6f306d63b720205a9f2aafc33de1e4d`.

The user requested an analysis of the supplied diagnostic conversation,
maximum practical intelligence improvements, prior changes carried forward to
the latest PR, and repair of all evidenced code defects. The capture is from
Android 0.9.66.19 (153), app commit
`11a1bd5a4b61011ceaa6441754797e6914dd5b58`.
All 21 archive member paths and all chunk/record SHA-256 values were checked.
Private conversation payloads and network/credential details are excluded from
this repository record.

## What the selected conversation establishes

The explicit all-tools verification task stopped after 13 successful local tool
receipts, 19 observed HTTP reads and no mutations. It retained 9 verified required
API capabilities out of 126, six excluded capability rows, and 0/1 accepted task
criteria verified. No provider invocation was retained for this task. The
accepted allowance was 256 calls, with 243 left when the application reported
`REQUIRED_TOOL_CAPABILITY_UNAVAILABLE`.

The run-specific admitted inventory included `discover_available_tools` among
eight advertised tools, while the final checklist snapshot contained zero
advertised tools and 28 candidates marked `DISCOVERY_TOOL_NOT_ADVERTISED`.
Preparation and finalization inventories therefore must not be conflated.

The last four CI operations requested pages 2–5, each returning 30 workflow-run
records with distinct IDs. The cumulative inventory was 150/224; that is real
partial traversal, not evidence that every call repeated the same page. A
bounded presentation must not erase source identities used for progress.

Evidence: archive `capture-context.jsonl`, `runs.jsonl`, reconstructed
`conversation.jsonl`, run-specific `tool-catalog.jsonl` entries 12–14,
`tool-receipts.jsonl` sequences 0–12 and retained `workspace.jsonl` checklist.
The journal has no missing sequences at its frozen boundary.

## Existing changes preserved

The captured app commit is a synthetic PR merge whose source tree exactly matches
preserved branch commit `d45c75f0` (`41702557ff02f70781efed4eab8a7abc593ab508`).
The PR already includes that implementation plus five subsequent commits, including `afbd5c65` (saved-conversation restoration, receipt-preserving
finalization and repair evidence), `238f4c5b` (bounded benchmark preparation and
measurement provenance), `fc0f9c62` (Pocket streaming/caption work), and the
strict dependency metadata correction `e28b5df8`. The concurrent advance
`74ba873c` adds saved-chat identity and caption-revision corrections; it is
reconciled before this patch is published. The merge was a fast-forward and the
local patch reapplied without conflicts. No prior commit was rewritten. The subsequent `1b133fb9` microphone and
automatic-listening repair is preserved too. Its generated feature map and
release-scope metadata were reconciled with the local additions; source/test
bytes were unchanged by that reconciliation. The later `bcca87ee` Combined
append-recovery evidence repair is also retained; its guarded public
`REMOTE_OUTCOME_UNKNOWN` classification is integrated with the exact closed
causal code introduced here.

The existing `GitHubIntegrationFinalization` contract distinguishes deliberately
withheld final-response tools from lost authorization. The new changes build on
that contract. Existing task identity, permission checks, exact-name discovery,
protected evidence, mutation reconciliation, fixture approval and cleanup
requirements remain authoritative.

## Repairs and behavioral contracts

### Tool discovery and meaningful progress

The all-tools checklist must acquire its bounded versioned catalog and missing
action schemas without letting a long executable inventory monopolize every
turn. Cleanup and exact pending action recovery keep priority. A failed catalog
read must not prevent independent authorized reads. Current authority, remaining
calls and schema budgets are never expanded to complete a checklist.

The concrete progress defect was in `AgentRunner.finishBatch`: it cleared
`retainedContent`, compacted each result for replay, and then the outer loop
asked `GitHubInvestigationProgress` to inspect that compacted exchange. A CI
summary can omit all run IDs even when the full result contains thirty new
records. The runner now observes the full current batch once before compaction
and carries only the bounded stop reason back to the loop. It does not retain
unbounded result bodies or increase the model prompt.

Catalog traversal, source delivery and verified capability execution retain
separate meanings. Progress may use new exact identities in retained source;
changed cursors, observation timestamps or repeated discovery alone cannot
renew the progress allowance.

### Complete tool batches

The parser rejected an observed 32-call provider frame against the accepted
per-response limit of eight. None of those calls was dispatched. The runner
previously recognized only the streamed-slot overflow when deciding whether a
single bounded smaller correction was allowed; complete-frame count overflow
was omitted. Both count failures now share the bounded correction path, and the
observed-call counter includes the complete rejected frame. Rejection never
executes a prefix or replays an uncertain write.

### Combined execution and optional reads

A real `BLOCKED` terminal result reached the Combined budget store, whose
terminal vocabulary rejected it with `IllegalArgumentException` and left
`TERMINAL_BUDGET_PENDING`. Blocked and missing-evidence outcomes must settle
once, remain non-success, survive serialization, and release only their own
reserved resources. Waiting for approval retains the original stage and reserve
instead of being classified as a failed terminal stage.

Optional page reads previously continued after a known shared Combined-work
refusal because their loop checked only the ordinary tool-call and output-byte
limits. The same loop now stops on the shared work limit and retains already
retrieved search summaries. No accepted budget is raised and unread pages are
never claimed as inspected.

### Exact local failure evidence

The selected run's typed local tool-admission failure became
`UNCLASSIFIED_PROVIDER_FAILURE`, stage `DELIVERY`, domain `UNKNOWN` in its journal.
The closed diagnostic vocabulary and public issue projection now preserve the
six known required-tool failure codes. Explicit undispatched local admission
failures classify as admission/application verification; positive or uncertain
transport evidence remains authoritative. Such a local failure cannot borrow
usage from an earlier provider invocation. Explicit positive or unknown remote
acceptance remains visible even when a later local admission step was not dispatched.

The app and report receiver also preserve four existing application causes:
`COMBINED_APPEND_REMOTE_COMPLETION_UNKNOWN`, `GATEWAY_CONVERSATION_READ_ONLY`,
`TOOL_BATCH_REJECTED`, and `TOOL_BATCH_OVERSIZE`. The closed receiver vocabulary
and `APPLICATION_FAILURE_CAUSES_V1` extended readiness negotiation must agree
before reports containing the new values are posted; exact cause retention does not authorize replay or retries. For the complete
application-owned append guard, the public `REMOTE_OUTCOME_UNKNOWN` code carries
`COMBINED_APPEND_REMOTE_COMPLETION_UNKNOWN` as its exact causal code. Incomplete
ownership evidence retains the exact fallback code without inventing the
guarded classification or missing transport facts.

### Background map synchronization

Repeated warnings arose from contexts without a configured engineering Gateway
and profiles with tools disabled. A local eligibility predicate filters those
contexts before they consume one of the four background synchronization slots.
The predicate uses current settings on every observation and keeps no negative
cache. The graph repository still rechecks live authority before network reads;
this scheduling hint cannot grant permissions or select a different host.

## External conditions and evidence limits

The archive also includes app-wide provider billing refusals, DNS failures,
a missing source URL, an unreachable connector, invalid local connector
credentials, private-Gateway timeouts/EOF, an unavailable platform speech
recognizer, and OEM media-service warnings. These are separate from the selected
GitHub task. Copied/shared error receipts are not additional physical network
requests. Existing recovery and honest failure handling are retained; repository
code does not repair an external account quota or start an unavailable host.

Eight additional pre-dispatch context refusals recorded prompts plus explicit
safety, tool-result and output reservations exceeding the selected 4,096 or
5,632-token allowance. They are valid admission refusals. The selected allowance remains a hard request boundary.
No evidence in these records establishes a larger provider context. The Combined quality report also
retained 105 failed and five unverified checks with zero repairs under
`SINGLE_PASS_LOCAL_CHECKS_ONLY`; this repair does not silently enable additional
model passes against that policy. The other retained report families include
actual selected-profile policy changes, disabled search providers, missing
comparison revisions, a rejected claim-count schema, read-only saved Gateway
conversation admission, and an uncertain Combined append completion. These
remain explicit prerequisites or failures; their exact known codes must survive
public report normalization without authorizing additional work or replay.

Three empty provider-completion observations lack the response-tail evidence
needed to infer one new causal code defect. They remain failures; no successful
answer or safe replay is inferred. No FATAL EXCEPTION, ANR, SIGSEGV or SIGABRT
signature was found in the retained logs. This does not establish that a crash
never occurred outside the capture.

The selected run spent about 25.0 seconds inside recorded tool calls, with about
43.8 seconds in gaps between calls and 11.1 seconds before the first dispatch.
The gap is measured, but its CPU/IO cause is not. No model-latency or live answer-
quality improvement is inferred from a deterministic run with no model invocation.

## App-wide failure disposition

The reconstructed capture contains 56 report observations across 17 public codes.
The same failure can appear in multiple retained views; these counts are not a
count of physical requests.

| Evidence family | Disposition |
| --- | --- |
| False CI no-progress stop and unavailable-discovery final snapshot | Repair raw-result observation; fairly schedule required discovery; preserve the earlier truthful finalization correction. |
| 32-call provider batch rejected against eight-call frame limit | Keep whole-frame rejection, charge the observed frame, and allow the existing single bounded smaller correction where eligible. |
| `BLOCKED` Combined settlement exception | Accept the actual terminal status, preserve non-success accounting and idempotence, and retain approval waits as pending work. |
| Optional page reads after shared work exhaustion | Stop additional optional requests and retain retrieved search summaries. |
| Missing engineering Gateway or disabled tools during background sync | Filter those current local configurations before allocating a sync slot; keep live admission checks. |
| Lost local admission and application-owned diagnostic causes | Retain exact closed causes, honest transport evidence and matching client/receiver support. |
| Eight context admission refusals | Preserve the selected context ceiling; reservations already exceed it. No evidence authorizes a larger route. |
| Provider payment incidents | Two dispatched quota failures plus three later local refusals; an account/provider condition. |
| DNS, unreachable host, missing URL and invalid connector credential | External connectivity, source availability or configuration; preserve the concrete cause. |
| Single-pass Combined quality report | 105 failed and five unverified checks with no repair rounds; incomplete under the accepted single-pass policy. |
| Unknown append completion | Retain the 23-word draft and unresolved remote outcome; no automatic replay. |
| Read-only Gateway voice conversation | Capture/transcription succeeded before message admission refused the read-only destination; preserve that exact reason. |
| Empty completion and platform speech/OEM warnings | Retain failure evidence; the retained data is insufficient to establish a new causal patch or measured crash repair. |

## Validation

Source-bound validation receipts and the reconciled parent revision are recorded
in `intelligence-conversation-repair-2026-11-28-task.json`; the published revision
is the Git commit containing this record. Focused JVM tests exercise
the changed behavior with explicit boundary ports where Android dependencies
are unavailable. They do not qualify a complete Android build or a physical
handset. The local full-Android memory preflight requires 8,960 MiB verified
headroom and returned `UNKNOWN` because cgroup headroom could not be established.
Observed available physical RAM was about 5.25 GB, below that requirement, and no
Android SDK was configured. The preflight was not bypassed.

Focused local JVM qualification passed 71 tests: 24 discovery/scheduler/progress,
21 complete Combined budget tests, five parser/recovery boundary tests, two
search behavior tests, fourteen receipt/code/voice and integrated append
producer/normalizer tests, and five Gateway
eligibility tests. The complete authentication/report-service suite passed all
253 tests. The final formatting result for all changed Kotlin files is recorded
in the integration receipt. These counts do not
include the unexecuted full-module methods declared in the individual receipts.

The final canonical host and generated-reference results are recorded in
`intelligence-conversation-repair-2026-11-28-validation.json`. The source-bound
receipts were rechecked against the latest reconciled worktree. Hosted Android
and aggregate results must belong to the exact published head; no older check
run establishes qualification for this patch.
