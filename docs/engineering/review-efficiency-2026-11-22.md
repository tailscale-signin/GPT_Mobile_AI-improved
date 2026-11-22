# Review efficiency and provider dispatch

## Accepted scope

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
- Parent pull request: `#2254`.
- Base branch: `fix/diagnostic-execution-completion-2026-11-22`.
- Base revision: `f225c208e80393f0232ba7849340461fd23901b9`.
- Working branch: `work/review-efficiency-2026-11-22`.
- Intent: make already-adaptive Combined work proportionate to straightforward
  requests, preserve explicit participation/review/evidence contracts, and extract
  one provider-session dispatch boundary from the central chat repository.
- Scope exclusions: UI, readiness, voice, generated inventories and release files
  are owned by other workers. No remote publication is performed from this branch.

## Implementation

### One selected-provider session boundary

`ProviderSessionDispatcher` now owns the provider-to-adapter mapping for primary,
delegated, Research and benchmark sessions. The caller retains privacy admission,
request authorization, accounting, tool permissions, Gateway recovery and wrapper
ordering. The dispatcher passes the exact profile, conversation and constraints
through and never falls back to another destination after failure or cancellation.
Local options are lazy, so a remote request does not bind local tools, read local
preferences or prepare a local runtime. Local authorized tools and advertised
schemas remain separate.

### Routine work uses only already-authorized adaptation

`RoutineTaskPolicy` recognizes greetings and short text transformations containing
their input inline. It does not classify general questions, external documents,
additional actions, evidence requests, sensitive/current topics or unknown tasks
as routine. Explicit multi-model wording now preserves every selected contributor.

For a newly accepted Combined turn, already-enabled adaptive participation and
source diversity below 100 can select the primary plus one other contributor for
these routine tasks. Ordinary straightforward questions keep the existing maximum
of three. Full diversity, non-adaptive selection, accepted Research and explicit
participant requests keep their scope. Resolving a saved accepted turn never
prunes it again. Final-answer reservations, optional effort, mandatory verification
and user hard limits are unchanged. The plan records its accepted contributor
count for inspection.

`AutomaticPreparationPolicy` can skip optional preparation only when helper
selection is explicitly Automatic, ownership is shared/remote (at least 50), no
specific helper/reviewer/plan/template/Research goal is selected, and the task is
recognized as routine. Required and best-effort configured reviewers are retained.
An explicit delegate call still uses the normal path. The skip occurs before
profile lookup, a preparation deadline, model loading or billable dispatch; its
diagnostic reason records zero dispatched model and tool calls. It does not turn
off the delegate tool for subsequent explicit model/tool decisions.

## Validation and remaining gates

- **12 focused JVM behavior tests passed** using the actual new dispatcher,
  routine/automatic-preparation policies, Combined request scope, delegation
  settings/normalization, Research settings and plan/template source. Narrow
  boundaries stand in for Android entities and unrelated session data contracts.
  The exact new repository adapter-construction block also compiled in this
  harness. This is not compilation of the complete Android application.
- Added full-project regressions for Combined policy acceptance, protected output
  and budget contracts, full diversity/Research preservation, and a coordinator
  skip that forbids profile lookup, worker execution and deadline startup. These
  require the normal Android test workflow and were not run in the host harness.
- **603 host script tests ran: 602 passed, one failed** solely because the generated
  app audit inventory still describes the parent source. This file is deliberately
  left for the integrating worker to regenerate after all source changes land.
- `bash scripts/validate-local.sh fast` passed CI policy and the preceding generated
  checks, then stopped at that same stale app audit inventory.
- Android regex validation: **1,879 literal patterns, 84 dynamic patterns skipped,
  zero errors**. Changed Kotlin files passed ktlint 1.3.1. `git diff --check` passed.
- Full Android compilation, the complete Kotlin suite and current-head required CI
  remain mandatory before merge/publication. Physical latency, battery use,
  acoustics, task completion and provider cost improvements remain unmeasured.
