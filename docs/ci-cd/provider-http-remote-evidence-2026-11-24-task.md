# HTTP provider refusal evidence and manual delay projection

## Intent recorded before editing

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
- Integration branch: `fix/diagnostic-execution-completion-2026-11-22` (draft PR #2367).
- Starting public commit: `822d2ef347f47ba5e1844146b23c9643ee2461a7`.
- Working branch: `work/root-http-refusal-d485`, in an isolated worktree.
- Public ownership was reserved in the root PR coordination block at 2026-11-24
  00:57:28 UTC, before source edits. Its exact readback receipt SHA-256 is
  `d97e2403baf592649e87eb59446072a9f11e477d92ee4dd3c9f9be2e3e8d99d0`.
- Preserve other workers' commits, including the HTTP header fixture repair at
  the starting commit. Reconcile any newer public head before the guarded append.

## Intended correction and ownership

Root owns the two OpenAI-compatible HTTP response paths and their ordinary
transport/adapter regressions. Parse the bounded structured error once, include
body-only remote-job and reconciliation evidence when deciding whether a 429 is
known unaccepted, and preserve that evidence in the terminal refusal. Apply the
same evidence to HTTP 402 manual-delay admission. Keep clean 429 durable waits
and safe 402 manual timing, while refusing replay or credential rotation when
remote work may already exist. SSE behavior and the separately owned typed
private-dispatch boundary remain preserved.

The Android reviewer owns only `ProviderFailureReport.kt` and its existing test
file within this worktree. Preserve valid provider delays through 86,400,000 ms
without the unrelated generic count ceiling, with bounded malformed-value
controls. Root integrates and independently reviews that slice.

The existing privacy CI coordinator separately owns readiness-capability
selection. No competing edit is part of this correction.

## Validation and publication gates

Add ordinary tests through the actual HTTP transport and both provider adapters,
including body-only job/reconciliation refusals and clean positive controls.
Do not substitute a standalone Kotlin or privacy harness. Run the canonical
repository host validation, pinned Kotlin formatting and generated-reference
checks, then obtain independent source review. Android/JUnit behavior remains
unqualified until the complete hosted suite executes on the final combined head.
Keep all existing limitations and test assertions. Publication requires the
latest PR gates, normal protected merge, exact-main gates and verified public
Android and Gateway release assets.

## Results

The implementation adds three ordinary transport/adapter methods (eight HTTP
429 refusal cases, two clean 429 controls and eight HTTP 402 cases) and one
public-report projection method (three valid durations and nine malformed or
out-of-range values). Both adapters use two configured credentials; each case
requires one physical HTTP request, one terminal failure and no completion.
All previous test bodies, including the corrected Ktor header fixture, remain.

The own five-file checkpoint was composed onto public typed-refusal commit
`6d3b9e4500099e34998f8a95c25dd896381c8fd3` in branch
`work/root-http-refusal-6d3`. Its two OpenAI private-refusal catch rethrows and
public-report evidence changes are preserved. The typed privacy change is a
separate source review; this correction does not claim to fix or reclassify the
unavailable original reports.

Canonical and independent review receipts are recorded by the publication
coordinator against the complete committed tree. The ordinary Kotlin methods
have not been run locally. This record claims no hosted test, merge, release,
runtime retry or live provider execution outcome.
