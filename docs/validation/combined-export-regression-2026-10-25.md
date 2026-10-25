# Combined export regression: Portugal / primary dispatch / duplicate continuation

Date: 2026-10-25  
Branch: `fix/combined-export-duplicate-primary-dispatch-2026-10-25`

## Observed symptoms

The supplied `export_Combined Chat_1792967773966.zip` reports app commit
`16889faaf5ace43793ddfc7706cc8ce300d822fe`, with Pollinations selected as
the primary profile (`builtin-free-pollinations`). The captured run/provider
records do not show a Pollinations synthesis invocation. The visible final
answer is associated with Vireonix and is 17,431 characters long, with a second
`History of Portugal` heading appearing after the answer has already begun.
The export's contribution mappings are empty and the expected
`combinedAnalysis`, `answerSegments`, and `segmentSourceIntegrity` fields
are absent.

The export has diagnostics disabled and does not include a complete provider
wire trace. These observations identify the failure boundary but do not prove
which exact internal branch skipped dispatch or appended the duplicate.

## Required implementation and acceptance criteria

1. **Primary dispatch invariant**
   - Resolve the configured primary from the accepted, frozen Combined turn policy.
   - Record a distinct synthesis-stage run linked to the root budget, user message,
     primary UID, and owner/fence.
   - Before publication, verify that the designated primary was invoked, or record
     a typed fallback reason and the fallback UID.
   - Do not infer a successful primary run from a contributor run or final message
     author. Do not dispatch excluded profiles.

2. **Collection and synthesis separation**
   - Treat provider responses as immutable source contributions.
   - Do not publish a raw contributor response as the Combined final answer.
   - Build synthesis inputs from completed and usable partial contributions while
     excluding failed/empty/reasoning-only outputs.
   - A rate-limited or failed contributor must not prevent synthesis from the
     remaining usable sources.

3. **Idempotent continuation**
   - Persist a canonical accepted prefix and revision/fence identity.
   - Continuations must append only new text to the saved prefix; never pass an
     already-complete response through a second append path.
   - Detect repeated headings/paragraphs and no-progress output before persistence.
   - Keep bounded retry limits and terminate only the failed provider continuation,
     not the whole Combined turn.

4. **Atomic final publication and attribution**
   - Publish the final text, displayed-body digest, quality report, source ranges,
     and profile attribution as one validated persisted bundle.
   - Require source ranges to be in bounds, non-overlapping where appropriate,
     and tied to the exact displayed-body digest.
   - On legacy or incomplete records, mark attribution as pending/unconfirmed rather
     than manufacturing source mappings.

5. **Export observability**
   - Export primary UID, actual synthesis provider UID, invocation stage, fallback
     reason, root budget/run/fence, continuation parent revision, prefix digest,
     duplicate/no-progress rejection counts, usable/failed contributor counts,
     and final publication digest.
   - Export explicit reasons when an expected stage was not invoked.
   - Preserve privacy redaction; do not include credentials or raw sensitive tool data.

## Regression tests required

- Configured Pollinations primary is invoked for synthesis when available.
- If Pollinations is unavailable, an explicit fallback is invoked and recorded.
- A contributor response cannot satisfy the synthesis-stage invocation check.
- Vireonix partial response followed by continuation does not duplicate the opening
  heading or previously accepted paragraphs.
- Identical continuation output is rejected as no progress and bounded.
- HTTP 502, rate limiting, and reasoning-budget exhaustion are isolated per profile;
  successful contributions still produce one final answer.
- Final answer and attribution are persisted atomically and survive close/reopen.
- Every source range references the exact displayed body; absent mappings are
  reported as unconfirmed.
- A replay of the same finalization event does not append or publish a second answer.
- The supplied export is retained as a regression fixture with secrets and
  personal/private payloads removed.

## Validation status

This document captures the observed regression and acceptance criteria. It is not
evidence that the implementation or tests have passed. The existing repository
already contains extensive Combined finalization code and regression coverage, so
the implementation must be made against the actual source and tested on the
Android build/CI environment before claiming the issue is fixed.
