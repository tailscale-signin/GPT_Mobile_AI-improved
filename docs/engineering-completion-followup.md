# Durable engineering completion follow-up

This extends PR #1203. Source implementation, host regression evidence, hosted
build evidence and installed-device qualification are separate claims.

## Working context and coding loop

Android primary-answer admission now passes the same `ReplayAllowance.Allocation`
to the runner: resolved ceiling, prompt, reply, replay and safety reservation.
Oversized input still fails admission; a small route is not relabeled as a larger
one. GitHub discovery accepts one exact tool name plus `action` to advertise an
action-specific schema next round. For example, `names=["github_ci"], action="logs"`
retains job/line constraints and paging without workflow-dispatch fields. A
subsequent discovery of that name without `action` restores the full contract.
The runner accepts only an exact projection of its currently authorized contract.

During implementation the workspace supports `checks`, `check` and `check_output`.
The model chooses an index from the admitted host-approved commands; it cannot
submit a shell command. A check can apply an inspected proposed patch to a fresh
isolated checkout. Its receipt binds head, patch and policy, and output is available
in bounded pages. These exploratory results cannot authorize COMMIT: full REVIEW
validation, exact-case behavior evidence and the existing approval gates remain.

Retained candidates bind the proposed patch to the source head, validation policy
and authenticated artifacts. The model can inspect candidate files and finalize
that exact retained candidate during implementation. A changed head, policy,
artifact or patch requires fresh evidence. Each check uses a fresh attempt view;
the attempt limit and shared job budget still apply. Typed admission and execution
binding failures stop the specialist rather than spending another format-repair
attempt.

Full Batch adds two concurrent, selected-model read-only investigators with
independent source views, a three-call limit each, shared job budget/deadline and
durable revision-bound findings. CI diagnosis can invoke a bounded failure
investigator. Suggestions do not authorize edits or publication. Existing review
and the bounded independent-job scheduler remain the execution authority.

## Repository Validation settings

The Advanced section contains `Choose task tests…` and `Release verification…`.
Discovery and release dialogs own their worker cancellation and queued callbacks;
closing them retains parent-owned cleanup until the worker has finished. Invalid
release settings open as an explicit unsaved repair draft, preserving valid policy
sections. Applying or saving a draft rejects an external policy revision change.

The desktop engineering form retains one connection, model and repository
selection through preflight and confirmation. Background refresh waits while a
review dialog is open; changing the selected connection or model requires a new
review. An uncertain dispatch keeps its pending identity for reconciliation. A
definitive refusal clears only that matching pending command, and remains visible.

`Choose task tests…` statically discovers existing Python unittest and JUnit tests
in the prepared pinned mirror. It does not import repository code. Select a test,
objective, requirement, covered source file and required CI check, inspect the
proposed command, and save the policy. The Python preset emits exact-case JUnit
results and rejects skipped/missing cases. JUnit presets require an appropriately
prepared build image. Discovering a test is not evidence that it passed.

`Release verification…` declares expected workflow/artifact names and selects
approved installation/smoke command indexes. These are optional for existing
policies, and unconfigured verification is reported as NOT_REQUESTED. Save policy
is the approval boundary; task admission freezes the contract and policy hash.

After merge, the verifier uses the exact merged SHA and applicable workflow
inventory, downloads declared current-attempt artifacts, enforces individual and
combined size bounds, and checks the GitHub SHA-256 digest. Signed-storage requests
do not carry GitHub credentials. Artifacts are copied as ZIPs into the isolated
checkout at `.gateway-artifacts/<name>.zip`; they are not extracted on the host.
Installation/smoke commands run against the actual merged revision. These checks
do not assert physical Android or Windows installation coverage.

## Main-build recovery

A confirmed main-workflow failure can create one idempotent recovery job on a new
branch. The parent-to-child ownership record is stored atomically with the child;
resuming after a lost checkpoint finds that same child before recalculating budget
or observing a newer main revision. It inherits the original selected execution binding and only the remaining
deadline/model budget. It passes ordinary preflight, publication and a fresh
exact-head merge approval. The already-used approval is never inherited. Cancelling
the original cancels its active recovery descendants, including admission races.
The original job stays unqualified until the recovery completes all gates and the
final qualified descendant's merged SHA is verified as reachable from the intended
main branch's current SHA. A bounded nested recovery chain cannot substitute an earlier failed merge.
The original failure evidence is retained separately from later child results;
missing historical evidence stays unavailable. Public job responses expose the
recovery link and verification outcome.

An explicit follow-up to a paused recovery admits its successor and replaces the
canonical child link in the same transaction. The cancelled predecessor, original
failure, spent budget and remaining deadline are retained. A restart reconciles
the validated predecessor chain to that same successor; ambiguous or cyclic
history remains blocked. The follow-up receives no inherited merge approval.

On Android, a completed required check with a skipped, neutral, action-required,
unknown or missing conclusion ends the CI watch as blocked. It cannot be promoted
to passed or retried indefinitely as an unfinished check. Failure, cancellation
and timeout retain their failed outcome; absent or still-running checks remain
pending within the existing polling bound.

## Failure evidence and pending-command ownership

The local runtime marks ordinary failures from the actual service-binding attempt
with their connection provenance and retains the original cause. The adapter
carries that marker into the existing bounded report as `connection_preflight`.
Cancellation, fatal errors and admission before binding keep their existing
semantics. This phase identifies the observed boundary; it does not establish the
native reason for a historical service death.

Primary-answer preparation retains the exact allocation when positive reply or
tool-evidence headroom is unavailable. The existing capacity codes and numeric
fields now survive the exception and approved-report path. Capacity rejection
remains an admission failure even if an earlier attempt occurred. An attempted
generation is not proof of visible output, and a refused new attempt is not proof
that the entire run was never dispatched. Earlier uncertain outcomes remain
authoritative; the report adds no automatic replay permission.

Desktop rejection cleanup compares the command and Gateway/principal identity
under the same state lock that retires it. A delayed rejection or reconciliation
cannot erase a successor command, newer draft or changed model. An uncertain send
keeps its pending identity for the next explicit reconciliation.

## Qualification and remaining scope

Focused regression modules cover actual temporary Git repositories and validator
selection, static discovery, specialist isolation/checkpointing, recovery ownership
and cancellation, artifact integrity, and normal settings round trips. Host Kotlin
checks compile the current runner/catalog and verify repeated context rotation,
schema selection on the actual provider-call path and allocation boundaries.
These tests use controlled provider/GitHub observations; they do not claim a live
phone, installed Windows gateway, Docker benchmark or production-repository run.

The separate ten-scenario production-task benchmark requested in the source audit
is **not executed by this follow-up**. Its retained scenario list is:

1. Diagnose and repair an unfamiliar bug.
2. Complete a task that requires source beyond one large excerpt.
3. Repair CI against the published parent revision.
4. Retain the same PR across successive jobs.
5. Continue across context rotation.
6. Reconcile a lost mutation response without a duplicate write.
7. Resume after a gateway restart.
8. Resume after the phone disconnects.
9. Report terminal CI blockers without false completion.
10. Retain the merge receipt and qualify a subsequent main-workflow failure.

For each scenario, record completion, first-attempt success, repair and interruption
recovery, duplicate writes, false completion, elapsed time and token use. Bind each
result to the exact model, quantization, route, gateway/app source and execution
mode. Existing fixture benchmarks must continue to report
`liveGitHub: NOT_EXECUTED`. Running these scenarios still requires a designated test
repository/runtime and permitted interruption, CI failure and merge/recovery
operations. Source and controlled host tests do not establish those live results. The prior
9,043-token production request also needs a captured request/route replay before
claiming it fits the 5,632-token route; allocator tests cannot prove that.

Hosted checks must be run on the final published head. GUI checks require a native
display, and complete release qualification requires the installed phone/gateway
acceptance scenario. Do not promote these unmeasured outcomes to PASS or merge on
an older head's green checks.
