# Combined mode: France export diagnosis and repair

## Conclusion and verification status

The contributors succeeded. Final delivery failed through a combination of undersized output packets, rejected continuation wrappers, hidden committed drafts, and slow local processing. Increasing retries alone would repeat a large prompt and still leave the answer invisible.

This branch implements targeted repairs for those mechanisms and adds regression tests. It is **not release-qualified**: Kotlin compilation/unit tests and a physical Android replay have not run in this environment. No provider-backed workflow can promise successful generation under every outage. The enforceable reliability contract is: preserve accepted work, show a truthful unfinished draft, bound recovery, and never label unverified or incomplete delivery as a completed answer.

Baseline: `fe4de044363aed9f9143f882e5fedaf02286d493`, matching the attached app's version **0.9.62.0 / build 130**. The earlier merged PR #750 does not establish that this later failure was fixed.

## Evidence from the attached export

Input: `export_Combined Chat_1793217773974.zip`. All four component SHA-256 hashes and byte lengths were verified against `manifest.json`. Numerical findings are recorded in [the evidence summary](combined-france-2026-10-28-evidence.json). The full conversation, provider settings, and request bodies are deliberately not copied into this repository.

The manifest explicitly says this is not a replayable reducer capture, not complete provider-wire capture, and not a transactionally consistent database snapshot. Absence of a journal event therefore does not establish absence of a durable database record.

| Finding | Retained evidence |
|---|---|
| Five successful contributor answers | `conversation.json.messages`: IDs 2–6, 86,456 characters altogether; final contributor run states are `COMPLETED`. Nvidia's initial failed attempt was followed by a successful run. |
| Large editorial input | `SYNTHESIS_INPUT_FROZEN`: 636 candidate units, 213,592 prompt characters, five sources. Request preparation estimated 73,741 input tokens. |
| Tiny writer allowance | Frozen writer plan: required and effective output both 723 tokens. `OUTPUT_CAP_RESOLVED` reports requested/effective 723 with no profile/provider cap recorded there. |
| Repeated full-context requests | 11 synthesis invocations: one synthesis, ten structured continuations. Provider-reported totals: 656,051 input tokens, 5,669 output tokens. Seven ended at length; four ended at stop. |
| Models returned text | The eleven finished-invocation diagnostics total 21,496 raw visible-output bytes and report zero reasoning bytes. Raw protocol bytes are not the same as published answer bytes. |
| Committed answer existed | Retained continuation cursors advance through sequence 29 and report 1,614 committed visible words, proving at least 28 committed records existed when those requests were assembled. Some records are headings. This does not prove complete or factually correct synthesis. |
| Valid-looking packet rejected at wrapper | The final retained continuation cursor says `rejected_record_reason=INVALID_DELIVERY_RECORD`; its retained untrusted fragment starts with a Markdown `json` fence followed by passage 29. |
| Published answer remained blank | Final message ID 9 has zero characters; its manifest has zero blocks. All 287 reducer checkpoints report zero visible bytes. |
| Excessive local delay | Synthesis created at 13:40:33; first invocation at 13:54:54.768 Toronto time, about 14m22s later. The eleven recorded invocation durations total about 61 seconds. |
| Repetitive persistence work | Retained diagnostics include 291 document-staging and 288 database-checkpoint operations. Many repeatedly stage the empty final document while waiting. |
| Terminal state | The run is `INTERRUPTED`, marked at 15:32:20 Toronto time. Checkpoint logs stop much earlier; do not interpret the entire interval as continuous model execution. |

`evidenceRecords=0` is not itself an error for this knowledge-only history request. No external research was required by its answer contract. Likewise, the recovered Nvidia failure does not explain why the already-successful five-source synthesis remained blank.

## Causes and implemented changes

### 1. Minimum admissible record size became the normal packet budget

`CombinedWriterPolicy.requiredOutput()` caps the append-mode visible-word estimate at 96. On the unobserved route in this export, `96 × 2.2 + 256 framing + 256 reasoning reserve`, truncated to an integer, yields **723**. `ChatRepositoryImpl` passed this admission minimum as the planned final output, and the frozen request used it for every continuation.

Repair: use `preferredOutput()` separately from `requiredOutput()` (the shared policy now on `main` through PR #753). New append plans prefer useful packets of 4,096–16,384 generation tokens where route and context limits permit; 1,024/1,500-token routes remain eligible and retain their smaller ceilings. Profile/provider limits, explicit user caps, context admission, hard turn budgets, and observed reasoning requirements still apply. This repair also applies that policy when freezing a new writer plan, so a newly frozen explicit retry gets a new capacity decision. Existing sealed requests are not edited or silently re-dispatched.

4,096 is a preferred per-request capacity, **not** a guaranteed answer length or an automatic permission to consume that many tokens. It does not change the requested visible word contract or guarantee all 636 claims fit one packet.

### 2. Framing was treated as conversation-wide instead of packet-local

`CombinedRecordProtocol` accepted an opening Markdown fence only while the entire logical record list was empty, and a closing fence only after the logical END. A later provider response could therefore wrap valid records and be rejected solely because earlier records already existed.

Repair: accept only the whitelisted standalone JSON/JSONL/NDJSON fences at record boundaries, including continuation packets. A known physical response boundary can discard a final closing fence without mistaking a split opening fence for a complete wrapper. Fences never advance record sequence or imply logical completion. Unknown references, conflicting replay, gaps, invalid fields, and text after END retain their existing checks. The cursor instruction now distinguishes an unfinished record from a clean packet boundary and asks the writer to continue new material after completing a fragment.

### 3. Durable record delivery did not reach the visible answer

`CombinedRecordRecoverySession` saves records before emitting them. However, `CombinedDeliverySession` disabled progressive output when `singlePass=true`, while accepted append-continuation turns also use `singlePass=true`. It held the visible text until delivery and final checking finished. An interrupted or slow check could therefore leave a blank message despite a substantial saved prefix.

Repair: publish each decoded, already-saved prefix through the existing atomic text-and-quality publication path for accepted single-pass record continuation. New `COMMITTED_DRAFT` reports bind text digest, revision, exact passage ranges, and display metrics. They must be partial, unverified, non-final, and protocol-incomplete. The validator rejects attempts to mark them completed or verified. Final reports continue through the existing final checks. Original contributor content and attribution references remain available; no arbitrary winning contributor is relabelled as Combined output.

This produces **one growing Combined draft**, with its unfinished state explicit. It does not bypass deduplication, scope, contradiction, or grounding checks for final completion. Repair candidates do not use this provisional-publication path.

### 4. Restart fallback ignored the saved Combined candidate

`restoreCombinedCandidate()` previously recovered an individual contributor revision when the final body was blank. It did not first restore the Combined record checkpoint.

Repair: for a blank terminal message, restore the saved Combined prefix first. Check the run ownership fence, frozen snapshot identity, body digest, source owners, and attribution ranges. Preserve the unfinished fragment and cursor for later recovery. Do not dispatch a provider during restoration, replace an already-visible answer, or override an explicitly selected saved revision. If there are no saved Combined records, the original fallback remains available.

### 5. Pairwise editorial checks repeatedly extracted the same claims

`CombinedEditorialInventory.select()` scans prior groups for every claim. Its loop repeatedly called string-based fingerprint and clause extraction, including `CombinedHistoricalEvent.sameEvent()`, even though the input corpus was immutable. At hundreds of claims this multiplies regex/normalization work across roughly quadratic comparisons.

Repair: prepare fingerprints and clause representations once per proposition and reuse them in the pairwise checks. Preserve the original directional comparison, qualifier, number, negation, and clause-association rules. Move local coverage-selection work after initial saved-draft collection so it cannot block the first displayed passage.

The 14-minute pre-dispatch gap is observed. Repeated extraction is a confirmed expensive code path, but the export lacks a complete profiler span proving how much of that gap it caused. The patch must be timed on the target device before claiming that delay is eliminated.

## Required production behavior

1. Freeze the user request, contributor corpus, scope/length contract, revision, and writer capacity before dispatch. Retain every source; source inclusion alone is not semantic coverage.
2. Validate record structure and references, commit raw cursor plus body/attribution, then publish the committed prefix as a partial draft. Cancellation between these steps must be recoverable locally.
3. Continue only from a known provider boundary, within the accepted budget, with fresh physical-attempt identity and unchanged committed record IDs. Unknown remote completion must require reconciliation, not blind replay.
4. Keep no-progress and malformed-record recovery bounded. Never repeat failed packets indefinitely or erase the saved prefix. A different writer/new revision is an explicit recovery action.
5. Run coverage, duplication, contradiction, user-contract, and grounding checks separately from display. Only a complete protocol and satisfied required checks can produce a completed answer. Textual attribution is not proof of factual truth.
6. On interruption, restore the exact saved draft and cursor. Export should eventually include a portable checkpoint/record chain, or explicitly declare its absence, so future diagnosis can test storage recovery directly.

## Validation and release gate

Completed here:

- Four manifest component hashes/lengths verified; invocation counts, token totals, cursor progression, fence rejection, and zero-output checkpoints independently recomputed.
- 204 existing Python script tests passed. Their fixture messages about instrumentation are test fixtures, not evidence that an Android device was exercised here.
- Android regex check passed: 949 literal patterns, no errors; 59 dynamic patterns not evaluated by that checker.
- Android resources passed: 29 XML files. `git diff --check` passed.

Added Kotlin regressions, **not executed locally**:

- Continuation fences split at every transport boundary and restored from a checkpoint.
- Fenced packets retain unknown-reference and conflicting-replay rejection.
- Fenced multi-packet delivery completes without spending malformed-recovery attempts.
- Useful packet planning retains small-route and explicit user caps.
- A saved first passage is published before cancellation, without a fake completion.
- Draft reports cannot forge completion, verification, or display metrics.
- Interrupted blank-message recovery chooses the Combined draft; a stale ownership fence is rejected.
- Prepared comparisons agree with the original string path for changed dates, uncertainty, negation, and swapped event dates.

Local Kotlin execution is blocked: `./gradlew --version` cannot download Gradle 9.8.0 (`Network is unreachable`). The environment has JDK 17 and no configured Android SDK; the project expects JDK 21 and Android 37. Static checks do not substitute for compilation.

Before merge/release, require:

| Gate | Acceptance |
|---|---|
| Compile and targeted tests | Run `:app:testDebugUnitTest` for `CombinedRecordProtocolTest`, `CombinedRecordRecoverySessionTest`, `CombinedRecordStoreTest`, `CombinedWriterPolicyTest`, `CombinedWriterRequestPlanTest`, `CombinedCommittedDraftTest`, `CombinedPreparedComparisonTest`, existing `CombinedDeliverySessionTest`, and publication/reducer tests. No failures or skips presented as passes. |
| Full CI | Full unit tests, Android lint, ktlint, package/DEX checks pass on the exact proposed commit. |
| France device replay | Same five-source history workload reaches a visible committed draft promptly, continues into later historical periods, preserves attribution, and either passes final checks or reports the specific remaining issue. Zero-byte final success is impossible. |
| Fault injection | Kill/reopen after the first and later records; cancel while streaming/checking; inject duplicate/reordered/fenced/truncated packets, provider failure, no progress, stale owner, and storage failure. No duplicate/lost accepted passage and no hidden retry after unknown completion. |
| Performance | Measure preparation, record saving, draft publication, final checks, heap, and UI frames on the target device with the 636-unit workload. Specifically verify the 14-minute gap and repeated empty staging are removed or explain any remaining measured work. |
| Time limits | Verify the accepted turn deadline governs local preparation/checking as well as provider admission. A non-cooperative CPU loop must not leave an indefinite spinner; add cooperative cancellation/work bounds where the device profile demonstrates a gap. This patch's caching is not proof of a hard wall-clock bound. |

The diagnostic export does not contain a portable full record journal, and this environment cannot run the Android app. Accordingly, this branch is a concrete repair candidate with a release test contract, not a claim of a universally guaranteed fix or a released APK.
