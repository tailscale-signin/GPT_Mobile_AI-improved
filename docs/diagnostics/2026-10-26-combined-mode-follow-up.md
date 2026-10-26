# Combined Mode diagnostics follow-up — 2026-10-26 export

## Scope and evidence

Audited `app-diagnostics-5734205571416660417.jsonl` from the Android device. The export contains 2,868 parseable JSONL records and two malformed/truncated records. Repeated records are not unique defects. The two decisive `SYNTHESIS_QUALITY` events report zero visible words/characters, an empty-content digest, failed delivery, 72/72 missing coverage units, no repair, and single-pass local checks only. The writer usage event reports reasoning disabled, 1,444 reasoning tokens, 1,446 total output tokens, and a 723-token output allowance.

## Changes in this follow-up

- Mark a resolved writer route as `NOT_HONORED` when the user-facing request disabled reasoning but the provider reports reasoning usage that exceeds its reported output allowance.
- Exclude a route with proven reasoning-control failure from subsequent writer selection.
- Keep the preferred primary when it remains eligible; when it is known-ineligible or cannot satisfy the frozen output/context contract, select the highest-ranked eligible writer instead of repeatedly dispatching the same route.
- Add a regression covering the 1,444-reasoning-token / 723-output-token failure signature and the alternate-writer selection behavior.
- Permit the run coordinator to attempt a successor writer after single-pass finalization failures, but only through the existing typed failure policy. Empty final publication and malformed final bundles now map to recoverable writer-rotation categories; ambiguous remote jobs, cancellation, and unclassified failures remain non-retryable.
- Replace blocking profile-pause preference commits with asynchronous persistence to avoid waiting for disk writes on the chat UI thread.
- Add tests for empty-publication and transient HTTP 502 recovery classification.

## Acceptance and remaining device checks

The source change does not claim to fix an external provider's implementation of reasoning controls. It prevents a route with observed contradictory behavior from being treated as a viable writer on subsequent synthesis planning. A classified failed single-pass writer may now hand off to an eligible successor while reusing the frozen source corpus; the successor snapshot still enforces participant membership, writer-authority revisions, and evidence-transfer permissions. If no eligible writer can produce a valid answer, the turn remains failed/partial and empty output is never published as success.

Before release, run the Android unit and instrumented suites and verify:
1. A preferred route with observed ignored reasoning controls is excluded, and an eligible alternative is selected without dropping saved contributor responses or source attribution.
2. Empty/reasoning-only output cannot be published; a failed attempt remains retryable and does not overwrite a previously valid answer.
3. HTTP 429 honors the provider deadline, HTTP 502 follows bounded retry policy, and deterministic request-shape failures are not retried as transport errors.
4. The oversized `CombinedSynthesisPlanner.prepare` method is split into independently testable stages and the 29,590-instruction warning disappears.
5. StrictMode reports no targeted main-thread disk I/O, and physical-device frame-time p95/p99 and >100 ms stalls are measured before and after. The log shows jank but does not prove that blur alone causes it.

## Evidence limitations

This is an application diagnostic export, not a source stack trace. It confirms the failed final-delivery state and contradictory writer usage accounting; it does not independently identify every internal cause of coverage failure, main-thread I/O, or graphics stalls. Provider outages remain external and must be handled through bounded fallback without discarding previously saved work.
