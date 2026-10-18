# Combined Mode and Settings: validation record

**Branch:** `fix/conversation-glass-rendering-2026-10-18`

**Implementation baseline:** `a361beb11ddeb6ede92cc6b11fdfd9517fb2565d`

**Design and audit:** [Combined Mode and Settings implementation design](Combined_Mode_and_Settings_Implementation_2026-10-18.md)

The earlier glass-rendering pull request, [#718](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/718), was merged into `main` at `d1da61ae2f21c0cab4e231fc1dac5576080afeee`. These additional changes remain on the requested branch and are reviewed in a follow-up pull request. The original glass capture, tint, and entry-visibility fixes are retained.

## Prepublication validation

This record captures the checks completed before publishing the implementation commit. The unfiltered Android unit suite passed all 3,525 tests across 579 suites, with zero failures, errors, or skipped tests. The follow-up pull request records subsequent lint, APK, and CI results against the published commit; those checks are not represented as completed at this record's cutoff.

| Check | Observed result |
| --- | --- |
| Repository Python validation | 138 tests passed; transport tests ran with local loopback sockets enabled |
| Android regular-expression preflight | Passed: 638 literal patterns; 38 dynamic patterns skipped by the checker |
| Android resource preflight | Passed: 29 XML files checked |
| Exported Room schemas | Match committed migration history |
| Unified Reliability v2 scope checker | Passed; scope inventory is not a device-qualification result |
| Kotlin formatting | Passed: ktlint 1.3.1 checked all 158 changed/new Kotlin files with the repository's Android Studio style; the final two queue-fixture edits were checked separately after the full 156-file check |
| Android application Kotlin compilation | Final integrated `:app:compileDebugKotlin` passed with warnings; no compilation errors |
| Android unit-test source compilation | Final integrated `:app:compileDebugUnitTestKotlin` passed |
| Focused queue and Room integration tests | Passed: 26 tests across `DurableExecutionIntegrationTest` and `ChatPromptQueueTest`; these cases also passed in the final unfiltered suite |
| Home native rendering | Passed: all four production-component rendering/interaction tests; all three final captures visually inspected |
| Instrumentation source compilation | `:app:compileDebugAndroidTestKotlin` passed; device execution was not performed |
| Full Android unit suite | `:app:testDebugUnitTest` passed: 3,525 tests, 579 suites, zero failures/errors/skips; unfiltered execution completed in 6 min 58 s |
| Android lint | Not completed at publication cutoff; the first isolated 1 GiB attempt was stopped under sustained heap pressure without source diagnostics, and a 1.5 GiB retry is running |
| Debug APK and packaged runtime/telemetry checks | Not completed at publication cutoff; the default two-ABI Vulkan build and APK inspection follow lint, with final results recorded in the pull request |
| Published-commit CI | Triggered by the follow-up pull request; consult its checks for the delivered commit's results |

The Python harness prints synthetic instrumentation-parser fixtures as part of its own tests. Those lines do not represent a connected Android device or an executed device acceptance suite.

The initial full unit run exposed two outdated queue-test fixtures. The Room fixture now reflects that a remaining healthy contributor can still deliver a Combined final; the mocked repository planner returns a real accepted execution context instead of an unserializable nested relaxed mock. The queued-start and ordering assertions remain in place. The corrected 26-case queue batch and the subsequent complete, unfiltered 3,525-case run both passed. The final XML/HTML reports and the logged count independently agree on the full-suite result; retained reports from earlier filtered runs were not counted as newly executed tests.

## What the regressions exercise

The policy tests exercise the two axes independently and across all 25 anchor combinations. Their invariants include selected participant preservation, task-derived output contracts, permission restrictions, no-search behavior, protected finalization resources, whole-turn optional quotas, finite renewal, concurrent reservations, actual physical retries, unknown completion and price handling, and cancellation. Required explicit-source work is assigned once; additional contributors and newly discovered URLs cannot multiply the accepted task.

Stop is checked under budget-journal failure as well as normal operation. In-memory closure and detached-job cancellation happen even if the budget cannot be saved. The coordinator still cancels and joins provider jobs, allowing partial-answer persistence before terminal fallback, when one root fails to close, the Stop caller is cancelled, or error reporting itself fails.

Settings tests cover normalization, future JSON fields, Save/Cancel and stale revisions, commit and abort recovery, stable conversation identity, queued accepted settings, branch/duplicate inheritance, backup/restore matching, and deletion cleanup. Room-backed tests check that settings and final-answer replacement use the intended transactions and owner/digest comparisons. A deliberately failing late regeneration insert must roll back the entire replacement operation.

Semantic regressions cover qualifiers, population and route distinctions, negative and null findings, numerical or causal reversals, within-profile contradictions, source ancestry, repeated or mirrored publications, and unsupported evidence edges. A merge disposition needs a corresponding final span. A failed correction cannot leave a known-invalid draft labelled complete. Visible word and item counts are checked against the user's answer contract independently of model token counts.

A long-original stress case exposed recursive regular-expression matching in claim and final-answer span relocation. Both callers now use a whitespace-normalized text index with exact raw UTF-16 boundaries and source digests. The original prompt equality assertions remain unchanged. Four additional regressions cover 10,000-word wrapped text, final revision receipts, repeated occurrences, and literal punctuation and Unicode. The ten focused prompt and span tests also passed with a 256 KiB JVM thread stack and JIT compilation disabled; that focused check is separate from the integrated Gradle suite.

History and recovery tests distinguish contributor candidates, delivered finals, partial answers, and archived revisions. Archived answers use their own saved source bundles. Failed or interrupted retries preserve the accepted corpus and root budget while creating a new output revision identity. Safe saved verification reads may be reused only after validating the ancestor, original model and invocation, exact tool identity, and current permissions; old answer verdicts are excluded.

The original audited export and corrected-evidence fixtures are separate files under `app/src/test/resources/combined_audit/`. The original fixture contains the candidates and tool outcomes available in that export, with pseudonymized profile identifiers and without credentials or provider configuration. The corrected fixture contains clearly labelled synthetic passage paraphrases for deterministic regressions. Neither fixture claims to be a new live model evaluation.

## UI and rendering coverage

The Home render suite uses the actual Compose components with Robolectric native graphics and synthetic display data. It exercises one through five information items, the empty state, compact and wide widths, large text, right-to-left layout, light and dark themes, text overflow, tile bounds and overlap, navigation callbacks, and the maximum-five toggle rule. All four cases passed. Captures were accepted only after checking rendered pixels and visually inspecting every final review image. The three layout cases cover fifteen nonempty layouts plus their empty states; the interaction case uses a 360 × 800 dp scrollable Home screen.

| Accepted capture | Conditions |
| --- | --- |
| [Compact light](assets/settings-home/compact-light-native.png) | 320 dp viewport; 288 × 498 px component; font scale 1.0; five selected items |
| [Wide light](assets/settings-home/wide-light-native.png) | 560 dp viewport; 528 × 326 px component; font scale 1.0; five selected items |
| [Large text, RTL, dark](assets/settings-home/large-text-rtl-dark-native.png) | 320 dp viewport; 288 × 1058 px component; font scale 2.0; five selected items |

The [capture notes](assets/settings-home/README.md) give the host configuration and text-measurement details. These are production-component renders with synthetic data, not live wallet values or physical-device screenshots.

The implementation also adds Android instrumentation tests for Combined settings, Home selection, and navigation. Compilation of these tests is recorded separately from their execution. No connected emulator or physical device is available in this workspace, so physical back-gesture behavior, GPU presentation, and device-specific accessibility are not claimed as validated by the native host render tests.

The shared back transition combines eased horizontal movement and opacity changes; the internal Settings/Home transition uses the same navigation functions. Glass backgrounds have no decorative shared-surface border. The search control remains hidden until its toolbar icon is pressed and retains the requested themed outline.

## Build environment

Validation uses JDK 21, Gradle 9.8.0, Android platform 37.0, build tools 37.0.0, NDK 29.0.14206865, and CMake 3.22.1. The existing default Vulkan backend remains enabled. Host header dependencies use the official Khronos `vulkan-sdk-1.4.321.0` archives, pinned to Vulkan-Headers `2cd90f9d20df57eac214c148f3aed885372ddcfe` and SPIRV-Headers `2a611a970fdbc41ac2e3e328802aed9985352dca`.

The validation environment supplies its network proxy, trust store, Android SDK, and host-header paths outside the repository. A stale incremental KSP cache was cleared by running with `-Pksp.incremental=false`. A concurrent build held the shared Gradle journal lock, so the final run uses a separate Gradle user home. An earlier integrated run was interrupted by the shared host's memory limit and is not counted as a passing suite. The passing full unit run used a 1 GiB Gradle heap and one 768 MiB test worker, recycled after twenty test classes, through a temporary external init script. The lint retry uses a 1.5 GiB heap. Native compilation is limited to two compile jobs and one link job without disabling either supported ABI or Vulkan. The repository's build configuration is not weakened to bypass generated-code checks.

## Quality score and remaining empirical work

The manual audit of the supplied eight-turn conversation remains **58/100 overall** and **68/100 for its final recap**. These are judgments about that single historical export. The log delivered seven Combined finals from eight requests and included two failed contributor attempts and fifteen failed tool events. The design explains the findings and rubric limitations.

There is no measured post-change quality score in this record. Passing tests establishes behavior for the exercised cases, not a factual accuracy percentage, a latency guarantee, or an OpenRouter cost reduction. No paid live-provider comparison or authenticated live wallet request was performed as part of this implementation validation.

A follow-up evaluation should replay fixed tasks with fixed model versions, retain the exact evidence and physical-attempt accounting, and compare the new final with the old final and each healthy contributor. Human reviewers should judge source support, qualification preservation, contradiction handling, useful contribution coverage, task compliance, delivery, and clarity without knowing which implementation produced the answer. Report sample size, serious factual failures, delivery rate, tokens, tool work, latency, and cost alongside any new score.
