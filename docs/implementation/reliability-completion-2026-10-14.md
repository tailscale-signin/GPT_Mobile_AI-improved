# Reliability completion work branch — October 14, 2026

Branch: `feat/reliability-completion-2026-10-14`.
Baseline: `6416abea1f2651823476f10eb32c42fec1276a0c`.
Local-runtime follow-up integrated: `f315a7a3e23d36de818b300fb0ed84e52b2583c1` (PR #679).
New implementation: `e6bdad46b8551a64efb89d32c82ad008fb2109b3`; import cleanup: `cc9735d62`.

This is incomplete work toward the four specifications, with additional requested UI work. It is not a claim that the 48 tasks or 110 acceptance cases are finished.

## Changes

- Local runtime: PR #679 supplies overflow-safe memory budgets, exact shortfalls, typed load/generation admission failures, stable failure state through delayed delegate updates, warm-engine recovery, thermal/memory separation and correct benchmark drain-fence acknowledgement. A saved 4,096 MB reserve still needs the explicit existing reset to 768 MB; settings and fixed model contexts are preserved. Actual phone token generation is not established here.
- Chat menus: replace the per-message three-dot expansion with a one-second hold. Both user and assistant bubbles provide themed rounded rectangular action menus. Position follows the touch with a 48 dp horizontal gap where screen space allows, edge clamping, 220 ms scale-in, a 500 ms visual shake and a 500 ms haptic waveform respecting system touch feedback. Scrolling and multi-touch cancel the hold. Select, copy, edit, favorite, retry, context and details retain existing callbacks and eligibility. Back/outside dismiss the focusable popup. Accessibility exposes a long-click action.
- Backup/restore: five expandable categories cover every existing section exactly once. AI platforms contains profiles, credentials and model files; Conversations contains chats/favorites, attachments, execution history, statistics and shopping; Settings & tools contains preferences and plugin/MCP configuration; Research evidence contains archive, chat evidence and appraisals; Personal data contains memory and health. Each of 15 child rows controls its real archive section and explains dependencies. Partial group selection, persisted selections, encryption and old archive compatibility remain. The shared selector applies to backup and restore confirmation.
- Combined mode: use a strict full-input plan for preparation, replanning and continuation. Required frozen history is no longer silently evicted by the ordinary history window. Finite overflow produces an estimated capacity report and blocks without changing the primary. Provider/tokenizer admission remains authoritative; delivery is not semantic coverage. A bounded same-primary text-only multipass workflow now runs exact frozen source-span inspections and conditionally writes from complete inspection reports. It refuses unsupported attachments, missing inspection completions and an over-capacity final request without dropping the originals. Durable inspection checkpoints, provider/device qualification and semantic coverage repair are not yet complete.
- Evidence permissions: check before constructing a provider round, retain the physical-boundary recheck, and convert the expected typed saved-evidence denial into a terminal provider failure. Cancellation and unrelated errors still propagate. This is an additional code-level guard, not physical-device crash sign-off.

## Validation

- PASS: `python3 -m unittest discover -s scripts/tests -v`: 109 tests. These are repository Python tests and synthetic fixtures, not tests of the newly added Kotlin behavior or actual native device qualification.
- PASS: Android resource preflight, 28 XML files.
- PASS: Android regex preflight, 513 literal patterns; 33 dynamic patterns skipped.
- PASS: ledger scope inventory remains 48 tasks / 110 acceptance cases.
- PASS: `git diff --check`.
- BLOCKED / NOT_RUN: Android compilation, Kotlin unit/Compose tests, ktlint and Android lint. Gradle 9.8 download failed with `Network is unreachable`; this workspace has Java 17, no Android SDK, no cached Gradle distribution and no connected device.
- Added but NOT_RUN: request permission entry/dispatch/cancellation tests, strict Combined history/output tests, popup position tests, hold/scroll instrumentation and granular backup UI/group tests. PR #679's runtime recovery and benchmark tests also require Android CI.
- BLOCKED: signed-release completion gate. `check_unified_reliability_v2.py --require-done` still fails. No acceptance outcomes were changed to PASS, no release checks removed, no version bumped and no release published.

## Remaining work

Durable full-corpus pass checkpoints and targeted semantic repair, qualified exclusive attribution and sidecar persistence, the outstanding provider/MCP/diagnostic replay acceptance matrix, long-answer and restart tests, ASUS CPU/GPU/NPU/GGUF qualification, navigation/accessibility/performance measurements, and V46–V48 release qualification remain open. Consult the task ledger and the research workspace validation record for their full scope.

## New branch integration checkpoint

- Branch: `feat/pr-gap-reliability-integration-2026-10-14`, based on the unmerged #681 head; the nine local-runtime and benchmark files match PR #679 by blob SHA.
- All seven distinct code hunks from PR #680 and its three main chat regressions were ported. The current user turn cannot reuse unauthorized prior recovery, and unavailable *unused* tool grants are checked only when invoked.
- Bounded same-primary Combined inspection is enabled only for finite, text-only context overflow. Every source span is kept exact, each physical pass goes through the existing permission and invocation boundary, and incomplete stages cannot dispatch final writing. Long-context and answer-quality acceptance remain open.
- The base draft includes the chat hold menu and five-category backup dialog from #681. This branch does not claim the unrelated older closed draft redesigns were merged.
- Full Android/native checks, physical ASUS acceptance and the 110-case specification are tracked separately. `--require-done` remains unchanged, and there is no version bump or signed release.
