# Five merged PRs: cross-feature quality gate (2026-11-03)

Scope: PRs #791, #793, #794, #795, #796, ordered by their recent merge activity. This is a verification plan, **not** evidence of a complete source audit, successful device run, or newly fixed runtime behavior.

## Source-of-truth rules
- Verify each requirement against code on the final `main` tree, not PR descriptions alone.
- For each test, record exact head SHA, CI URL, test case, result and observed artifact. Do not transfer green receipts from an earlier SHA.
- Record unsupported, not-run, and externally blocked work explicitly. Do not mark a tool call as a completed task.
- Preserve authorization/privacy policy, mutation receipts, exact-SHA CI checks, approval before merge, and no retries for deterministic preflight rejection.

## Cross-PR acceptance matrix

| Area | Source PR | Acceptance test / evidence still required |
| --- | --- | --- |
| Graph intelligence | #791 | Exercise all 22 feature entry points on a pinned fixture revision, verify provenance, isolation, incomplete-index labeling, bounded results, optional Graphify offline and permission revocation. External Graphify accuracy remains unmeasured. |
| Release reproducibility | #793 | Exact-head Android lint, tests, signed/unsigned packaging as applicable, checksum pins, bounded workers and memory; ensure lint cannot be silently skipped. |
| Chat scroll | #794 | API 34/36 emulator: 1.5s prompt-to-top, summary before preview, sustained upward gesture releases anchor, downward reattachment, true-bottom streaming and navigation/restoration. Physical device smoothness remains unmeasured. |
| Chat progress and privacy | #795 | Cold tool discovery must advertise callable schemas; avoid repeated progress lines, respect Settings workflow presets, block known-incompatible ZDR routes before retrieval; dynamic routes remain unknown. |
| Voice lifecycle | #795 | Rapid navigation/cancellation, settings load, stale TTS callback rejection, speech session ownership and no main-thread Binder/disk work on an actual device. |
| Checklist and recovery | #796 | Resolve workflow/tag/job dependencies before dispatch; bounded saved-page recovery; never replay writes or authorization errors; preserve incomplete coverage and evidence receipts. |
| Combined integration | #791/#794/#795/#796 | On the same final SHA, run a complete GitHub read -> plan -> authorized write -> PR -> CI -> review -> approval -> merge fixture, with explicit blocked steps when permission is absent. |

## Proposed-feature reconciliation

The previous user requests also include Gateway 15 durable 3–12h execution, Windows tray installer with connected-device and job statistics, opt-in voice plugin, local model tool parity, Combined Mode controls, and numerous GitHub workspace UI refinements. The five PR descriptions do **not** prove every one of these is complete. Track these separately with a code path, test and release evidence before claiming closure. In particular, no device-measured frame/jank, live MCP availability, external Graphify correctness or end-to-end unattended durability claim can be inferred from host tests.

## Release gate
1. Compare merge ancestry and final tree for all five PRs.
2. Run all applicable workflows on the exact candidate head; no failed, skipped-required, pending or stale checks.
3. Check regression and integration test coverage for each matrix row.
4. Explicitly record remaining unqualified device/provider items.
5. Merge only after checks and required review are satisfied; verify resulting main SHA and release artifacts separately.

This ledger deliberately does not claim that code changes or full validation have been performed.
