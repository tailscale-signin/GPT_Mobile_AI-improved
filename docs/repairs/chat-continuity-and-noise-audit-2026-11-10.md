# Chat continuity, GitHub completion and UI-noise remediation

Follow-up: [retained-response delivery and full issue inventory](chat-continuity-design-2026-11-10.md) extends this initial patch. Expired GitHub tasks now deliver saved reports with an application-budget blocker, preserve explicit report/diagnostic requests, and retain the primary cause in persisted state. The original five-second attempt remains removed.

**Evidence:** partial-diagnostics-df07af46-3d8f-4204-9eb4-a32dc4669741, archive part 1, captured 2026-11-10, app 0.9.66.7 (141), app commit 7eceaa54dde4d77100898a7ac09e5d2688a65f96.

## Evidence boundaries

- 3,743 useful retained records, capture marked 90% complete, `captureCompleted=false`.
- 10 agent runs: 5 BLOCKED (saved patch approval), 4 PARTIAL (3/5 criteria verified), 1 FAILED (OpenRouter HTTP 429). No COMPLETED run in this selected capture.
- 118 completed tool-event envelopes; a completed envelope is not evidence of successful execution or verified acceptance.
- The journal has 103 continuation selections and 9 verification rejections. These are observations, not independent failures.
- Critical and recent application log tracking was disabled. No claim of zero app crashes or zero streaming defects is justified. The text "Running on this device" and the cache-clear memory notices came from repository code, not retained archive lines.
- The saved chat contains "Agent run timed out after 5000 ms", a 429 provider failure, multiple waiting-for-approval and interrupted messages. The five-second fallback in `ChatRepositoryImpl` provided a reproducible source path for the 5000 ms duration.

## Changes in this patch

1. GitHub progress shows meaningful activity and time only; omit the redundant static ownership label "Running on this device".
2. Runtime memory monitoring and formatting-cache eviction remain enabled, but the chat UI no longer announces routine cache housekeeping. Keep diagnostics in logs and Health/Debug. Do not silence real failures.
3. When a durable GitHub task's original time budget is exhausted, do not start an artificial five-second request. Return an explicit non-dispatched `RUN_DEADLINE_REACHED` receipt with `PARTIAL` outcome and preserved work.
4. Add a focused regression test asserting that an exhausted task never calls the provider. Keep the independent positive-timeout behavior unchanged.

## Additional actionable issues and design recommendations

| Priority | Observed issue | Remedy / acceptance |
|---|---|---|
| P0 | 5-second timeout on expired saved task | Exhausted budget -> no dispatch, explicit status; no false attempt, no replay of mutations. |
| P0 | Interrupted model delivery / HTTP 429 | Honor provider Retry-After and jittered cooldown, persist partial response and request lineage; one idempotent retry only when remote acceptance is explicitly NO. |
| P0 | Assistant run interrupted with uncertain remote acceptance | Reconcile by server request ID/job ID before issuing any new mutation; append from immutable offset instead of duplicating previous text. |
| P0 | 5 runs paused for patch approval | One persistent, clearly anchored Approve & Commit card; status "Approval needed"; Continue routes to card rather than another blocked run. |
| P1 | 4 repeated 3/5 criterion partials | Show two specific remaining criteria, last supporting receipt, available next actions; never claim all complete based on tool HTTP 200. |
| P1 | 118 envelopes vs verified effects | Separate transport, API, mutation, CI, cleanup and acceptance evidence; count unique live tools, not repeated pagination. |
| P1 | 103 continuation selections | Reuse one job/checkpoint and previous receipts; cap repeated no-op Continue attempts and display "No new evidence" as a compact state, not a full generated answer. |
| P1 | Verbose repeated public task summaries | Brief real change summaries; move raw progress/transport/retry traces to expandable Activity/Debug. |
| P1 | Routine memory cleanup spoken in chat | Evict disposable UI caches silently; retain structured diagnostic measurement. Show an actionable error only if actual content rendering fails. |
| P1 | Diagnostics 90% complete, logging disabled | Export shows a persistent evidence-completeness qualifier; permit user-controlled critical-log retention without enabling overly broad private logging. |
| P2 | On-screen warning and assistant content repeat errors | De-duplicate terminal error cards against the saved assistant message, while preserving visible recovery controls. |
| P2 | Chat stream appears to jump/interrupt on reconnect | Maintain stable bubble ID and persisted last-visible sequence; on reconnect restore the prior text before rendering new deltas. |
| P2 | Rendering during active stream | Conflate markdown/layout updates and keep raw text available; syntax highlighting/cache trimming must not cancel provider jobs. |

## Streaming acceptance matrix

- A mid-response network loss retains exact text and cursor; reconnect neither clears the bubble nor reissues an accepted mutation.
- A provider 429 emits one concise cooldown state without replacing partial content; once eligible, retry only when no remote request was accepted.
- App background/foreground transition preserves durable gateway run and restores progress by job ID.
- Approval stays a decision state, not an error or implicit "retry"; no repeated generation calls.
- Budget reached before dispatch emits saved-work status; **zero** provider/tool calls observed.
- Healthy memory pressure evicts caches with **no** chat text or accessibility live-region spam.
- Completion requires explicit objective evidence and reports unverified criteria as remaining.
- Keep distinction between unit, emulator, connected phone and gateway end-to-end results.

**Validation status:** Source and regression test edited; no Android, gateway or physical-device test has been executed as part of this patch.
