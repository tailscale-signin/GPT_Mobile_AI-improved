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

Full Batch adds two concurrent, selected-model read-only investigators with
independent source views, a three-call limit each, shared job budget/deadline and
durable revision-bound findings. CI diagnosis can invoke a bounded failure
investigator. Suggestions do not authorize edits or publication. Existing review
and the bounded independent-job scheduler remain the execution authority.

## Repository Validation settings

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
branch. It inherits the original selected execution binding and only the remaining
deadline/model budget. It passes ordinary preflight, publication and a fresh
exact-head merge approval. The already-used approval is never inherited. Cancelling
the original cancels its active recovery descendants, including admission races.
The original job stays unqualified until the recovery completes all gates and its
merged revision is still reachable from the intended main branch. Public job
responses expose the recovery link and verification outcome.

## Qualification and remaining scope

Focused regression modules cover actual temporary Git repositories and validator
selection, static discovery, specialist isolation/checkpointing, recovery ownership
and cancellation, artifact integrity, and normal settings round trips. Host Kotlin
checks compile the current runner/catalog and verify repeated context rotation,
schema selection on the actual provider-call path and allocation boundaries.
These tests use controlled provider/GitHub observations; they do not claim a live
phone, installed Windows gateway, Docker benchmark or production-repository run.

The separate ten-scenario production-task benchmark requested in the source audit
is **not implemented by this follow-up**. The audit references that list but does
not include its scenario definitions. Existing fixture benchmarks must continue
to report `liveGitHub: NOT_EXECUTED`. Completing that benchmark requires the original
scenario specification and a designated test repository/runtime, including the
permitted interruption, CI failure and merge/recovery operations. The prior
9,043-token production request also needs a captured request/route replay before
claiming it fits the 5,632-token route; allocator tests cannot prove that.

Hosted checks must be run on the final published head. GUI checks require a native
display, and complete release qualification requires the installed phone/gateway
acceptance scenario. Do not promote these unmeasured outcomes to PASS or merge on
an older head's green checks.
