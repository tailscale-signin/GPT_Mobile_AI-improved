# GitHub chat: effective defaults and dependable execution

## Goal and scope

GitHub mode should produce useful, source-grounded work after the user connects
GitHub, selects a repository and selects an available AI profile. The application
should discover the remaining capabilities, prepare the relevant source and
manage ordinary recovery. An intervention should correspond to a real choice,
missing prerequisite or uncertain external action.

The target is successful work per accepted task and budget. Increasing reasoning,
context size, tool count or the number of models is not itself a quality result.
Fresh profiles therefore retain **Intelligent**, adaptive budgeting and task
context. Explicit profile choices remain authoritative, with the versioned
Copilot default migration described below. The implementation
does not turn a saved private route into a cloud route or substitute another
model when the selected one is unavailable.

This document records the November 26, 2026 reanalysis of the combined Android
and Windows branch. The intake revision was
`acc720b54ef59f1de73c7b4a78aadcea83b18635`; concurrent voice and Android smoke work
and immutable-source intelligence work was preserved through
`d94cadd78dd1ac1c0ad1998e8c4bbca42a749ddb`. The companion
`docs/repairs/github-out-of-box-intelligence-2026-11-26-task.json` records the
implementation and validation checkpoint. Earlier repair notes describe their
own revisions and do not qualify this one.

## First-use contract

| Situation | Expected behavior |
| --- | --- |
| No authenticated GitHub connection | Show the existing in-app sign-in flow, then continue to repository setup. |
| Exactly one eligible GitHub connection | Enter directly. Do not add an account-confirmation step. |
| Multiple eligible connections | Show one compact account/host selector. Inventory order must not choose an account. |
| A selected connection disappears or is disabled | Retain the selection as unavailable. Offer connection management or an explicit new selection. |
| A saved conversation opens | Restore its existing connection, repository and model binding. |
| A saved repository belongs to an installation on a later page | Resolve that repository's installation within bounded pagination, preserving its draft and staged changes. |
| An explicit Gateway profile was deleted or disabled | Show that the selected Gateway is unavailable, with a route-selection action. Do not describe it as Automatic. |
| A selected local model is asleep | Use the existing readiness check and supported wake action on that same route. |

Authentication, an ambiguous account choice and a missing usable model are real
inputs. They cannot be guessed. The UI should keep them together with their
repair action and avoid asking users to tune context, schema budgets, reasoning
effort or retry counters before a normal repository question.

The new-entry routes use connection-specific workspace and programmer ViewModels.
Changing the explicit account therefore cannot reuse the previous account's
screen state. Current authorization is checked again at the existing execution
boundaries; choosing an account is not a permanent grant.

## Default policy

The profile defaults originate in `GitHubChatProfileOverride.newProfile` and the
shared GitHub planning policy. Gateway admission now uses the same evidence-guided
next-action policy for new work, while accepted requests and conversations keep
their frozen policy and request identity.

| Control | Default and reason |
| --- | --- |
| Intelligence strategy | Intelligent: use a short verified path for exact lookups and broader planning for investigations and implementation. |
| Context | Task context, sized to the selected route's admitted capacity and current request. |
| Context expansion | Adaptive. Preserve output, pending tool evidence, replay and the current user request before optional material. |
| Repository grounding | Use the selected immutable source identity, compact repository map and bounded relevant source ranges. |
| Next action | Evidence-guided (`ACTIVE`) selection from admitted tools and unresolved criteria for new profiles and new Gateway admissions. Existing conversations inherit their accepted policy. |
| Engineering memory | Retain existing Hybrid behavior and its privacy/authorization controls. Historical observations stay distinct from current source. |
| Gateway | Engineering is part of the Gateway experience and cannot be disabled. Exact selected route, account grants, runtime prerequisites and individual job cancellation remain authoritative. |
| Additional models | Require an explicitly selected helper/reviewer and the applicable workspace consent. |
| Copilot | Enable the integration by default for a correctly bound workspace/conversation. Enforce tool permissions, GitHub eligibility, execution owner and durable submission rules at dispatch. |
| Completion | Report observed work, evidence, unmet criteria and unknown measurements. |

The advanced settings remain available for an intentional Deep Engineering,
Maximum Intelligence, fixed reasoning or private-only workflow. Maximum
Intelligence is not silently enabled as a way to authorize extra providers,
larger paid requests or new permissions.

### Gateway engineering is built in

The GitHub chat transcript has no persistent engineering control card, toggle
or five-second control polling loop. Settings and Windows diagnostics show
readiness and the specific setup action when needed. They do not expose a
feature enable/disable switch. A compatibility `enabled: true` request is an
idempotent observation at the expected revision; `enabled: false` is rejected
with `ENGINEERING_ALWAYS_ON`. The control store retains a durable rejection
receipt for an old client attempting the removed switch.

The one-time owner migration removes the legacy engineering pause. It does not
promote viewers, restore revoked devices, authorize a different repository,
enable network access or make an unavailable model ready. A device still must
confirm the exact PC and installation when binding its owner grant. Individual
jobs can be cancelled, and blocked prerequisites remain visible. Selecting
“Use AI profile limits” changes adaptive budgeting only; it does not turn off
Gateway engineering.

### Copilot with observable purpose

New app settings and a correctly bound conversation make Copilot available by
default. A versioned one-time migration moves the legacy default-off state to
that new baseline. Later explicit off selections are saved as bound false
preferences and remain off. A preference from another connection, repository,
conversation or workspace cannot enable delegation. The Windows host has a
matching versioned saved-configuration migration; malformed settings stay unavailable.
A manually launched/headless Gateway respects an explicit
`GATEWAY_COPILOT_ENABLED=false` override; the Windows host derives this environment
setting from its saved Copilot preference.

An enabled integration is not proof of account eligibility, task submission or
execution. The selected direct/Gateway owner, tool grants, all-tools-disabled
state and private-route restrictions still govern each request. The task API
requires an eligible Copilot plan and supported user credential with the required
Agent tasks permission. GitHub's current response determines availability;
GitHub App installation tokens are not a substitute for a supported user token.
See the official [Agent tasks API](https://docs.github.com/en/rest/agent-tasks/agent-tasks)
and [Copilot API guide](https://docs.github.com/en/copilot/how-tos/use-copilot-agents/cloud-agent/use-cloud-agent-via-the-api).

The preview names **GitHub Copilot**, its bounded task purpose, and the observed
phase: preparing a request, accepted, running, waiting, completed or unavailable.
Preparing is intent; only a confirmed receipt can establish remote acceptance.
Every Copilot semantic preview path requires a local producer receipt, matching
call and source digest, and the selected task identity. Raw tool text cannot
forge progress when evidence elaboration is disabled. Saved observations remain
identified as historical and cannot establish a current lifecycle.
Source-owned Copilot phase changes are delivered immediately so a fast final
answer cannot swallow the progress event between ordinary preview ticks. Named
MCP Copilot requests identify the requested action without treating an opaque
response as verified task completion.

An accepted task is restored from its original identity after restart. A lost
submission response remains uncertain and is reconciled through readback;
it is never automatically resubmitted. Completing a Copilot task does not
establish passing tests, a current PR head or merge permission. The supported
GitHub preview API has no task-cancellation endpoint: stopping local observation
must not be presented as stopping GitHub's remote worker.

The optional engineering-agent adapter maps GitHub's `DISPATCHING`,
`IN_PROGRESS`, `IDLE` and `WAITING_FOR_USER` states to its supported lifecycle
before publishing progress. Previously these valid remote phases could become
`STATE_UNVERIFIED`. The adapter retains its own configured backend and consent;
the integration default does not configure a missing SDK or substitute a provider.

## One task authority from selection to answer

The account, host, connection, repository, accepted revision, selected model,
privacy route, budget and acceptance criteria form one task authority. Source
preparation, tool discovery, tool execution, saved checkpoints and the final
answer must continue to refer to it. A source cache, discovered schema or
historical answer cannot create missing authority.

| Phase | Responsibility | Evidence needed to advance |
| --- | --- | --- |
| Admission | Resolve the selected account/repository/profile and current access. | Current connection, repository scope and model readiness. |
| Preparation | Classify the request and select useful context within the actual remaining capacity. | Pinned source identity, admitted prompt budget and disclosed omissions. |
| Execution | Choose an authorized next operation, retain its identity and process its result. | Bound schema, remaining budget, required approval and durable operation receipt. |
| Verification | Compare observed results with accepted requirements. | Source, executed checks and external state at the relevant revision. |
| Delivery | Preserve a useful answer and explain unresolved work. | Verified claims; unknown or unavailable evidence remains explicit. |

An exact record lookup should not require a repository scan or an engineering
job. A larger code change needs the relevant implementation, callers,
configuration and tests. A successful tool invocation is an observation; it
does not by itself satisfy the whole task.

Readiness has to be capability-specific. A repository can be ready for questions
while a host lacks an approved validation policy or execution image for a code
change. The existing Gateway preflight distinguishes those prerequisites. Static
discovery can suggest validation commands and required checks; it cannot approve
them, invent their results or promote a plan to a completed implementation.

## Source that fits the actual request

The audit reproduced a concrete context defect: repository preparation used the
overall model context size, while final admission used a much smaller optional
allowance. The resulting bundle was prepared and then discarded as one oversized
section. This could leave a small-window task without any of the source just
prepared for it.

The repaired preparation receives the actual residual allowance after required
prompt content and output/replay reservations. Optional engineering memory keeps
a bounded share so its request, feedback and decision capture is preserved.
Source projection receives the actual space remaining after admitted memory.
Neither path increases the model or user limits. A zero allowance performs no
optional connection, map or cache work.

The projection preserves repository, branch and source revision, then retains
whole useful source lines with their path, blob and line range. It removes lower
priority relationship and test candidates before sacrificing the final source
window. If the requested matched line cannot fit as a complete line, the result
becomes an explicitly unread pointer. It must never claim that a different
prefix contains the requested match. An incoming range already marked
`read_required` remains an unread pointer even if the bundle fits; it contributes
no delivered-source coverage. Resizing never fabricates an empty excerpt for it.

Shrinking a selection updates the delivered range, byte/line coverage and
omission indicators. A partial excerpt is not a complete file. A relationship
candidate is not compiler resolution. A test path is not proof of test execution.
Prepared context also remains distinct from final serialized provider delivery.

Required system/project instructions, current user content, admitted history,
pending evidence and canonical continuation retain their existing protected
admission paths. Optional remembered observations cannot override them. Private
engineering memory keeps its destination restrictions even when a cloud model
has additional free context.

The initial matched fixture, before the concurrent source-receipt changes, used
identical required prompts, output/replay reservations and optional memory before
and after the repair:

| Context ceiling | Required prompt | Output reserve | Replay reserve | Optional allowance | Source admitted before | Source admitted after |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 4,096 tokens | 1,961 | 512 | 512 | 330 tokens | 0 | 304 characters / 314 UTF-8 bytes |
| 8,192 tokens | 3,847 | 1,024 | 1,024 | 800 tokens | 0 | 1,367 characters / 1,411 UTF-8 bytes |

Both arms retained the same 37-token optional memory and invoked the mocked
memory-preparation and repository-map boundary once. These figures measure
fixture context admission, not network requests, live token savings or answer
quality.

## Recovery that makes progress

The system already distinguishes provider waits, transport recovery, tool
handoffs and no-progress allowances. Recovery should advance only after new
evidence, an elapsed admitted wait or a changed prerequisite. Repeating the same
blocked stage is not progress.

This reanalysis repaired two storage cases in the durable Gateway controller:

1. Re-pinning an artifact with the same content digest now succeeds at the job's
   retained-byte quota. It adds no retained bytes. A new artifact still has to
   satisfy the per-artifact and per-job limits.
2. A typed evidence-capacity failure while checkpointing a completed stage now
   pauses the job as `NEEDS_INPUT`. The last committed checkpoint, original
   request and separately journaled operation receipts remain authoritative.
   The failed new stage result is explicitly marked unretained.

The pause is committed under the same owner, revision and lease checks. A stale
worker cannot restore cancelled work. Previously dispatched writes retain the
requirement to reconcile the original operation. A small partial report records
the storage blocker and unavailable acceptance evidence without duplicating the
large data that caused the failure. Storage exhaustion does not renew the task,
delete evidence, switch models or authorize replay.

The audit also tested terminal streaming cleanup with a deliberately
non-cooperative delegate. The existing runtime already delivered the completed
answer or original failure within its cleanup allowance. Two regressions retain
that behavior; no production watchdog change was needed.

HTTP 429 and rate-limit HTTP 403 responses after a physical mutation were also
tested. The existing journal retains the actual response and dispatched flag,
the controller attempts readback and pauses when the result remains unconfirmed,
and later ticks do not replay the write. This behavior required coverage, not
a production retry-policy change.

## Queue stability and Windows controls

The investigation separates active execution capacity, model scheduling, retained
history and the Jobs presentation. They have different recovery rules.

| Finding | Repair and boundary |
| --- | --- |
| At the 32-job engineering limit, an explicit follow-up could be refused before replacing its own paused predecessor. | Perform the authorized replacement and then check occupancy inside one transaction. An unrelated full-queue admission rolls back; the limit stays 32. |
| A failed progress callback immediately after acquiring a model slot could leak the slot before the caller received its lease. | Release the acquired ticket and clear thread-local ownership for either progress or receipt failure, then preserve the original exception. A queued callback failure removes only that request's waiter. |
| Failed engineering work and interrupted generations could be counted as open indefinitely. | Use the canonical terminal states for open-work counts; keep their retained records available as evidence. |
| Old principal identities left retained jobs visible but impossible for the PC owner to cancel. | Add an authenticated local-supervisor cancellation command, re-read the original owner and revision, and use the existing engine cancellation path. Retain a separate administrative intent/result receipt. |
| A restarted client-tool wait had no live cancellation event and returned a conflict when stopped. | End only its exact retained local continuation, preserving handoff IDs, fingerprints and result evidence. Late continuation is rejected; remote tool effects stay unconfirmed. |
| Only the recent snapshot was visible, and terminal entries could not be removed from Jobs. | Add scoped paging, terminal-only reversible removal and a Removed view. Preserve original receipts and command identities across restart. |
| A job advancing after cancellation review left a saved Stop request permanently stale. | Retain a definitive `NOT_DISPATCHED` rejection under the original command ID. Only that proof permits fresh review; a missing receipt or timeout remains uncertain. |

Cancellation distinguishes requested/pending from confirmed. In-flight work may
need to reach a cancellation checkpoint; an uncertain upstream mutation remains
uncertain. A local supervisor command cannot invent a remote cancellation,
impersonate an account or discard operation evidence. Pending cancellation stays
visible. Terminal removal changes local presentation and can be undone; it does
not free an active slot by hiding work or erase the durable journal.

The existing 4,096-record engineering-history ceiling remains separate from
the 32 active-job limit. Reversible list removal does not erase that retained
history or reduce its count. This change does not introduce automatic journal
archival or deletion; reaching the history ceiling still requires explicit,
evidence-preserving history maintenance.

The model queue remains bounded. New default intelligence does not increase
parallelism, budgets or retry counts. Evidence-guided next-work selection uses
admitted dependencies and existing verified receipts to avoid redundant work.
Explicit baseline policies remain available for controlled campaigns. Existing
requests, old rejection receipts, continuations and post-merge recovery retain
their accepted policy, including an omitted legacy policy.

## Gateway investigation coverage

The audit traced admission, conversation identity, durable state transitions,
model scheduling, source projection, task-plan selection, artifact retention,
operation journals, Copilot supervision and the native management bridge. The
repairs above address reproduced defects in those paths. Existing invariants
remain necessary:

- The selected model and execution binding are frozen before dispatch. Missing
  capabilities, stale source and changed credentials fail with a repair path.
- Tool/source discovery and generated repository guidance do not grant write,
  validation, network, merge or alternate-provider authority.
- Model reservations, output/replay admission and verification budgets remain
  bounded independently of durable job retention.
- Checkpoint transitions require the current owner epoch, revision and lease.
  A replaced or cancelled worker cannot publish a new authoritative result.
- External mutations have original operation identities. Readback reconciles
  uncertain outcomes; timeout, restart or quota pressure does not permit replay.
- Verification and merge readiness require the accepted evidence at the relevant
  source revision. Copilot completion and model confidence are insufficient.

This is source and controlled-regression evidence, not a claim that every
production environment is stable or that this design maximizes model quality.

## Verification and product measurement

Regression checks establish behavior for their controlled inputs. The most
useful acceptance matrix includes:

| Area | Required observation |
| --- | --- |
| Fresh setup | One eligible account enters directly; multiple accounts require an explicit choice. |
| Account continuity | Inventory reorder, disablement and removal cannot move a task to another account. |
| Saved workspace | Later-page installation discovery preserves repository identity and local edits. |
| Route recovery | An unavailable explicit Gateway is visible and cannot silently become Automatic. |
| Small model context | Useful pinned source survives final section admission without exceeding the residual budget. |
| Source accuracy | Delivered lines agree with range and match metadata; incomplete requested lines become unread pointers. |
| Memory | Current request/feedback capture is retained while optional rendered history stays bounded. |
| Capacity recovery | A full evidence store permits deduplication, rejects new excess bytes and pauses without stage replay. |
| Mutation continuity | Unknown and confirmed operation receipts survive a capacity pause; cancellation cannot be resurrected. |
| Terminal delivery | Completed output and the original failure survive slow transport retirement. |
| Built-in engineering | No chat toggle; old disable commands reject durably; owner/viewer/revocation and readiness boundaries remain effective. |
| Queue recovery | A full-queue continuation replaces only its own eligible paused job; unrelated admission rolls back. |
| Scheduler cleanup | Failed admission callbacks release slots; a cancelled waiter cannot release another request's lease. |
| Windows Jobs | Old-owner work is reachable and cancellable by the authenticated local supervisor; pending and confirmed outcomes differ; terminal removal is reversible. |
| Copilot defaults | One-time migration, exact conversation binding and later explicit off are preserved. |
| Copilot visibility | Fast accepted work still emits a purpose and phase; unavailable and uncertain states do not claim execution. |
| Accepted policy | New tasks default to ACTIVE; retained jobs, continuations and rejected command identities keep their original meaning. |

Use the existing independent campaign infrastructure for the subsequent product
evaluation. Preregister the repository revision, tasks, account/model/runtime
configuration, budgets, allowed tools and acceptance criteria. Keep native
product captures and independent grading bound to those identities. Report
first-attempt completion separately from best-within-budget completion and keep
synthetic fixtures separate from production GitHub trials.

Useful operational measures include interventions before first useful answer,
time to first useful evidence, source retained at final admission, duplicate
reads, unresolved criteria, recovery without duplicate writes and verified task
completion. Provider usage and monetary cost require actual receipts; estimated
tokens remain estimates. A larger prompt or more tool calls is not automatically
an improvement.

No live product/model trial was run for this reanalysis. Comparative model
quality, physical-phone latency and the exact final Windows binary behavior
remain unmeasured until their corresponding trials execute. Existing Windows
benchmark artifacts retain their own source-revision identity.

## Further work requiring separate evidence

The review identified one narrower evaluation follow-up. It is not a reason to
weaken the current authority or completion gates:

- Requirement-focused schema selection can prioritize read/context schemas over
  an already admitted edit schema in a small context window. A repair needs
  phase-aware implementation and read-only negative tests; a schema-selection
  fixture alone does not establish a live-model stall.

Prefer targeted improvements supported by those behavior tests and matched
campaign measurements. Additional orchestration should earn its complexity by
improving completion and recovery under the same accepted constraints.
