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


## Reconciled evidence checkpoint

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Base: `main` at `6912b6404054728630174f1c4d9aa6c4b576e06c`. Working branch: `audit/last-five-merged-pr-quality-2026-11-03`; reviewed audit head: `72f1cf14b53971b8596883e70ec5cb3f2e36272a`. Intent: repair this document's exact release-scope omission and attach verified integration evidence to the remaining acceptance plan.

GitHub PR and comparison reads confirm that every head below is an ancestor of the pinned main revision (zero commits behind). This verifies inclusion, not implementation completeness.

| PR | Reviewed head | Recorded merge / inclusion |
| --- | --- | --- |
| #791 | `2616a0a1cacfad7ed8f6f309273e181f6e95a35d` | `94d8a4a87f3e27b838439c4ae6b3d32171c7b90a` |
| #793 | `d00140c5c849f5d03088e7759851bc443ae0a1b6` | `3de77a00946f5c621443dc7c8668dc8879ef0d1c` |
| #794 | `c9f4ff03cc0ef2b275c82a1fb3dc6ce5538922ad` | `3a05c621dd0d5dd1777a3decdd92abb8feb20273` |
| #795 | `f2daff0bc7e158f577518da773d647f89d9c7151` | Included by integration `b2cd84807cb270aa0417b2bc60ec220e38a40c21`; GitHub closed #795 after #796 landed |
| #796 | `b2cd84807cb270aa0417b2bc60ec220e38a40c21` | `6912b6404054728630174f1c4d9aa6c4b576e06c` |

Main's merge tree `31d9268de79f486ffaec9f8e15ef9508d23f3f84` matches the reviewed integrated #796 tree. The independent #795 audit was still running when inclusion was reconciled; it was not counted as a pass. The combined #796 head passed all applicable pre-merge workflows and Required verification.

All five applicable post-merge validation workflows on main `6912b640` are now successful, latest attempt 1:

| Workflow | Observed run |
| --- | --- |
| PR Validation | [41718913818](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/41718913818) |
| API 34/36 Android acceptance | [41718913656](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/41718913656) |
| Build Android APK | [41718913590](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/41718913590) |
| CodeQL | [41718913541](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/41718913541) |
| Post-merge correctness | [41718913903](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/41718913903) |

Notification and observability workflows are not validation receipts. These runs validate their configured scope; they do not establish every device/provider requirement above. They also do not substitute for #797's own final-revision checks.

## Source and regression traceability

The following paths exist in the pinned tree and identify where to continue the source-to-requirement audit. Test presence is not a claim that every individual assertion was independently extracted from a CI artifact in this review.

| Area | Current implementation | Relevant regression sources |
| --- | --- | --- |
| Graph intelligence | `data/agent/tool/RepositoryGraphTool.kt`, `data/github/GatewayGraphRepository.kt`, `presentation/ui/chat/RepositoryGraphViewModel.kt`, `RepositoryGraphPanel.kt` | `data/github/GatewayGraphTest.kt`; all 22 entry-point acceptance items still need individual outcomes |
| Release verification | `scripts/android_pipeline.py`, `scripts/await_release_checks.py`, `.github/workflows/pr-validation.yml`, `gradle/verification-metadata.xml` | `scripts/tests/`; retain publisher-verified digests, separate lint and bounded packaging controls |
| Prompt and bottom following | `presentation/ui/chat/GitHubPromptAnchor.kt`, `ChatScreen.kt` | `GitHubPromptMotionInstrumentedTest.promptMovesOverOneAndHalfSecondsWithoutAnImmediateJump`, `reattachmentWaitsForTheUserScrollAndRechecksAnchorIntent`; `ChatBottomAutoScrollerInstrumentedTest.growingContentWhileFollowing_keepsTheTrueBottomVisible` |
| Progress and privacy | `data/github/GitHubChatProgress.kt`, `presentation/ui/chat/GitHubActivityLines.kt`, `data/openrouter/PrivacyRejectionCache.kt` | `GitHubChatProgressTest`, `GitHubActivityLinesTest`, `PrivacyRejectionCacheTest`; live endpoint availability remains unqualified |
| Voice lifecycle | `data/voice/AndroidVoiceAudio.kt`, `presentation/ui/voice/VoicePreferencesViewModel.kt` | `AndroidVoiceAudioTest.speechConfigurationRunsOnWorkerAndIsReusedUntilSettingsChange`, `cancellingDuringConfigurationNeverStartsStaleAudio`; physical Binder/disk/frame measurements remain unperformed |
| Checklist recovery | `data/github/GitHubSelfTestReads.kt`, `GitHubTestChecklist.kt`, `GitHubVerifiedSession.kt` | `GitHubSelfTestReadsTest`, `GitHubChecklistPaginationTest.sourceRecoveryCannotReplayMutationOrUnauthorizedTool`, `permissionFailureCannotTriggerSavedPageRecovery` |

Kotlin implementation paths above are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`; JVM regression paths are relative to its matching `app/src/test/kotlin/` package, and motion regressions use `app/src/androidTest/kotlin/`. This source map does not close the proposed-feature reconciliation or authorize mutation fixtures.

The original audit revision failed `scripts/check_release_scope.py` because this new document was absent from `allowedPaths`. The repair adds only its exact path; the release base, required workflows, qualification limitations and validation rules remain intact. Full source-to-requirement reconciliation and the unmeasured acceptance items remain open, so this PR remains draft.
