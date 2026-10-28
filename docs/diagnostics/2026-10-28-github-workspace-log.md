# GitHub workspace diagnostic follow-up

Input: the retained `tailscale-signin/GPT_Mobile_AI-improved` conversation export from
October 28, 2026, app 0.9.62.0 (130), commit
`fe4de044363aed9f9143f882e5fedaf02286d493`.

## Verified observations

- Both responses exhausted their 24,576-byte tool-result allowance. Repeated reads
  consumed that allowance; neither response recorded a branch, staged patch, or
  commit. The final implementation example was a proposal, not an applied fix.
- The implementation turn retrieved the same complete 85-line source file twice,
  then split it into progressively smaller ranges. The backend returned complete
  source evidence, but `AgentRunner.finishBatch` compacted retained results to
  512 tokens each even when the full result fit the retention and replay budgets.
  Later provider rounds therefore could receive an irreversibly clipped result.
- `evidence_id` was combined with an invented `view_cursor="0"`. These are separate
  mechanisms: an evidence handle reopens pinned source evidence through the
  repository session; a local page cursor is an opaque token returned by the
  result presenter. Cursor routing ran first and returned an unavailable view.
- Path discovery treated `*` as a literal substring and stopped after 2,000 tree
  entries even though path search does not fetch or index blobs. The exported
  repository had 3,824 paths. A no-hit page was incomplete, not proof of absence.

## Changes

1. Keep complete observations when they fit the bounded retention budget. Remove
   trace and duplicate retained payloads as before. Provider replay still applies
   its own budget, and overflowing retention still uses the existing compactor.
2. Reject mixed evidence/page handles before dispatch with an exact recovery
   instruction. Clarify tool descriptions and expired read-view errors. Opaque
   cursors remain scoped to their original operation and authorized task.
3. Allow path searches to scan cached tree metadata beyond the content scan window,
   retaining the existing deadline and 20-hit pagination. `query=*` in path mode
   lists files; other path queries remain case-insensitive literal substrings.

## Regression coverage

- Complete 85-line evidence survives two following provider rounds with one read.
- Path discovery finds a file after 2,100 earlier entries without fetching blobs.
- Wildcard listing follows the exact continuation and returns every path once.
- The exported mixed-handle mistake returns a correction before backend dispatch,
  then a corrected evidence-only request reaches the backend.
- Existing runner, result-compaction, evidence persistence, cursor isolation, and
  operation-contract tests exercise the surrounding limits and authorization.

## Limits

The export is a retained diagnostic snapshot, not a complete provider wire capture.
The retention defect is verified in source; precisely what the provider inferred
from each clipped result cannot be reconstructed. This change does not increase
configured call/byte budgets or guarantee model compliance. Broader backup,
local-inference, and security claims in the earlier generated audit were not
verified by its recorded tool calls and are outside this focused remediation.

## Validation result

- ktlint 1.3.1: passed for all seven changed Kotlin files.
- Kotlin 2.4.20 focused compilation: passed for the four changed production classes
  and the four test classes, using the available compiled app dependency build.
- JUnit 4.13.2: **95 tests passed** across `AgentRunnerTest`,
  `GitHubOperationToolTest`, `GitHubPersistentEvidenceTest`, and
  `ToolExchangeCompactorTest`. The harness used the project's coroutines 1.11.0
  runtime. The four new regression tests are included in that total.
- Complete Gradle Android verification remains unverified: the normal build lost
  its daemon at the environment's memory limit; a smaller-heap retry was stopped
  after reporting insufficient heap during Kotlin compilation. Focused JVM
  validation does not establish APK packaging or device behavior.

Normal build environment command:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*AgentRunnerTest' \
  --tests '*GitHubOperationToolTest' \
  --tests '*GitHubPersistentEvidenceTest' \
  --tests '*ToolExchangeCompactorTest'
```
