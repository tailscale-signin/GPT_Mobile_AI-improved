# 0.9.60.0 integration validation

Release branch: `release/0.9.60.0-provider-combined-integration`; version **0.9.60.0 (128)**. The recovered continuation was published as `a4c83fbb` on top of `beaeea2b`; `main` is `951a6ae7`. All 3,629 published paths were checked during local reconciliation; production/test/workflow content matched, while documentation follow-ups were retained. Source dependency #740 is merged. Room schema is **47**, introduced by the additive 46→47 migration; published schema 46 remains unchanged.

## PR #741 branch repair — 24 October 2026 (Toronto)

This repair continues the published `a4c83fbb` tree and preserves the recovered selected-answer action and documentation changes. It addresses the failures observed on that head without changing the APK budgets, method-size threshold, test assertions or required workflows.

| Observed blocker | Repair |
|---|---|
| LiteRT memory replan intermittently violated Flow exception transparency during suspended progress delivery. | A bounded `channelFlow` isolates failed-attempt cancellation from downstream delivery. Deterministic tests cover the original race, downstream cancellation/failure and exhausted descending replans. |
| Retained benchmark results disappeared from the UI test after changing modes. | The Compose v2 test rule and semantic lazy-list scrolling wait for the requested target. The production result availability assertions remain intact. |
| `delegateToProfile` measured 17,170 DEX code units and `AgentToolResolver.resolve` measured 16,388, exceeding the exclusive 16,383 limit. | Delegated prompt admission and run-scoped Health tool construction move to non-inline helpers. The new delegated helper is included in the packaged DEX gate. |
| CodeQL's traced Kotlin build exhausted its 3 GiB Java heap. | CodeQL alone receives a 4 GiB heap; clean compilation, cache bypass, rerun tasks and analysis remain required. |
| The universal release APK exceeded its 230 MiB budget by 902,174 bytes. | Release, benchmark and native-smoke APK variants compress their DEX entries. Native runtime contents and supported ABIs remain present. The unchanged size gate measures actual output artifacts. |
| The revised-answer label could still invoke unfinished-delivery retry or act on another revision. | Banner/menu actions carry the displayed answer's immutable target into a fresh accepted turn. UI and Room checks bind its assessment, document owner, historical identity and current ownership fence. |

The DEX setting uses the documented [AGP `DexPackagingOptions.useLegacyPackaging` API](https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/variant/DexPackagingOptions). It changes APK DEX compression; APKs generated from app bundles use separate packaging. This is a download-size repair, not a measured startup or installed-size improvement.

Fresh repair evidence before publication: **116 isolated LiteRT/replan/watchdog tests**, **183 repository script tests**, all **17 changed Kotlin files** under ktlint, **29 resource XML files** and **875 literal regex patterns** passed. The isolated cancellation test was also observed failing with the original collector. The first clean integrated attempt reached production Kotlin compilation, then the local memory controller killed its Gradle daemon while another build was running (`oom_kill` increased by one); it is not counted as a passing build or test run. Subsequent local builds use the shared build lock. Complete hosted tests, actual APK sizing, packaged method sizes, lint and CodeQL remain independent verification gates. The [PR checks](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/741/checks) remain the authoritative results for the published commit.

## Earlier continuation checkpoints

The continuation integrates the three October 24 designs, the France/Portugal review, the GGUF/benchmark implementation guide and the additional 16-run benchmark audit. The following rows preserve earlier checkpoint results, including failures and work that was pending at each snapshot. They do not qualify the later branch repair above; an earlier pass does not qualify later source changes. The [continuation review](0.9.60.0-continuation-review.md) records additional saved-wait, original-export, GPU-evidence and cold-export repairs. The [PR checks](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/741/checks) identify the exact published commit.

| Check | Observed result and scope |
|---|---|
| Recovered integration production and unit-test compilation | Passed with benchmark-export, source-paging, process-supervision and comparison-cohort corrections. The additional continuation repairs passed their focused production/test compilation; integrated compilation is a separate final gate. |
| Integrated unit/Robolectric failure-discovery checkpoint | **4,739 tests, 6 failures, 0 errors, 2 reserved for the separate heap gate.** The earlier 4,640-test checkpoint had nine failures; those earlier fixes are included here. A focused integrated follow-up executed **271 tests: 269 passed, 1 retained-results UI failure, 1 reserved heap test**. The UI follow-up remains pending; the other five checkpoint regressions passed without weakening their assertions. |
| Additional continuation regressions | The isolated continuation reports **101 Combined, 24 provider-wait and six GPU-plan tests passed**, with no failures or skips in those focused packets; these are separate from the earlier primary integrated checkpoint. This includes exact recovered France wording and Portugal report fields, post-wake/post-launch permission changes, deadline projection drift and persisted cancelled-GPU evidence. |
| Cold canonical/legacy export follow-up | Standalone production/test compilation, two metadata/parser parity tests, four actual Room/native-SQLite tests and the separate 256 MiB cold-store test passed. Export bypasses whole-history hydration, optional mirror parsing and score publication; complete original runs still supply every per-run grade. Canonical payloads and the captured score pointer remain unchanged. All six ordinary cold-export tests passed in full-v2; its large cold case remains reserved for the separate heap gate. |
| Cold Room export stress | **101,541,461 bytes and 128 complete raw bodies** exported at 256 MiB from 48 MiB of canonical raw history plus retained attempts. Every run row exceeded 2 MiB; an invalid stale mirror was ignored. Incremental output SHA-256: `642bf5a9d4095644bcc285e688f2e1f6832b90408d7b70c022735e202506daca`. This is a synthetic native-SQLite/Robolectric regression, not the user's phone crash trace. The four-class integrated heap gate remains pending. |
| Expanded 256 MiB recovery/export gate | **9 tests passed, 0 failures/errors/skips.** Includes complete large benchmark export, more than 40 MiB of retained Research evidence and a 2,000-message diagnostic export. |
| Android device-test compilation | Passed against the final benchmark-export integration and request-specific NPU qualification contract. |
| Benchmark variant and macrobenchmark harness compilation | Both passed with the extended audit and large-document scenarios. The final export follow-up is compiled with the integrated debug/device-test pass. |
| Original benchmark-export failure reproduction | The existing production exporter raised `OutOfMemoryError` in `JsonToStringWriter.writeQuoted` under Java 21 with `-Xmx256m` while exporting a synthetic 100,755,765-byte history. The fixture is eight runs of 64 roughly 96 KiB answers plus retained attempt copies; SHA-256 `9c1703f21307a3772805c95dc68605dfde1ea13bab54f4e59b27cc90888f4f1b`. This is a reproduced allocation failure, not the user's absent phone crash trace. |
| Streaming benchmark-export stress | Two production-writer tests passed at 256 MiB, both in isolation and in the integrated heap gate. The standalone large case streamed **105,580,147 bytes**, preserving **1,024 raw-answer bodies** with matching incremental content digests; redaction, stream ownership and complete termination passed. Both integrated database-backed export tests passed, including Unicode, forward-compatible fields, legacy parity and write failure. |
| Export destination and recovery helpers | **13 tests passed**, both against production helpers in isolation at 128 MiB and in the integrated Gradle run: complete bounded copying, write/close/cancellation failures, restart retry, corruption, diagnostic authority and stale-orphan cleanup with active-file protection. |
| Native host C++ contracts | Three executables built and all three contract tests passed. |
| Real CPU JNI smoke | Pinned ChatML and arbitrary-Jinja fixtures passed inference, exact prompt/output budget rejection, cancellation/reuse and stale-handle checks. A CPU allocator ownership defect found by this smoke was corrected using exact allocator identity. |
| Android native compilation | ARM64 OpenCL and KleidiAI compiled after the native ownership correction. Compilation does not prove GPU execution. |
| Inspection-cache filesystem regressions | Nine tests passed against production cache/digest code: warm reuse, restart, same-size/mtime mutation, atomic replacement, changed pinned digest, environment change, corrupt cache, mutation during parsing and targeted verification. |
| Strict JSON / QNN failure regressions | Thirteen tests passed against production structured-output, QNN classification and argument-validation code. No behavior stubs were used. |
| Process deadline and supervision regressions | Sixteen standalone production-code tests passed, including continued decode supervision, meaningful counter progress, tool waits, blocked collectors, cleanup ownership and distinct policy serialization through IPC. |
| Python repository scripts | **183 tests passed**, including twelve request-specific QNN qualification-parser regressions. Loopback transport fixtures ran with local socket permission; the sandbox-blocked attempt is not counted as a pass. |
| Static resources and metadata | 29 resource XML files, 870 literal regex patterns, reference icon, QNN provenance metadata, scoped-release validation and the branch's existing reliability gate passed. Dynamic regexes are not claimed as statically validated. |
| Kotlin formatting / whitespace | The initial **379 changed Kotlin files** passed ktlint 1.3.1; seven cold-export/UI follow-up files passed separately, covering **383 distinct paths** at that checkpoint. The published `a4c83fbb` Kotlin Lint workflow passed. Final action-path changes still need their own checks. |
| Android lint on the recovered integration | Passed in **19m29s**. This precedes the additional continuation Kotlin changes and cold-export integration. The CI lint step now allows 30 minutes, within a 100-minute validation job. |
| Full integrated continuation validation | Full-v2 completed **4,778 tests: four failures, zero errors, four large cases reserved for the heap gate**, from 799 fresh XML suites. Production/test content matches published `a4c83fbb`; local generated BuildConfig retained checkpoint `827bb5fd`. Export data/storage and seven formatting/result-card cases passed. Retained-results navigation, two home semantics checks and an advanced-settings check need follow-up. Primary Hilt and isolated full-v1 ASM failures stopped before tests and are retained separately as task-local build-state failures. |
| Selected-answer revision correction | Eleven paths are integrated. Banner/menu actions now carry the selected answer into a distinct ViewModel/regeneration path; Room revalidates exact selection, compact assessment, manifest owner and current fence before accepting a fresh root. Isolated production/UI/test compilation, nine pure and nine real Room cases passed. The eight-class integrated Gradle regression packet is running; real UI/ViewModel execution is not yet counted as passed. |
| Remaining APK / device-test gates | Final source APK packaging, lint and instrumented-test compilation are checked by their applicable workflows. Earlier macrobenchmark/device-test compilation and the expanded heap gate remain scoped checkpoint results. |
| Required GitHub Actions on the final published commit | On `a4c83fbb`, Kotlin Lint and GGUF CPU native smoke passed; other applicable workflows are running. The staged action correction will require checks on its own final commit. Historical runs on `66d009f` or `beaeea2b` do not qualify this continuation. |

The nine checkpoint failures exposed asynchronous benchmark reservation release, child-abort terminal accounting, retained worker quarantine visibility, Android-tag classification, and test-environment/interface mismatches. Fixes retain the safety and persistence assertions rather than weakening them. In particular, the 207-source/24-helper Research incident replay runs under Robolectric so real Android atomic-file persistence remains exercised; its original recovery assertions are retained.

## Requirement and evidence maps

- [Design implementation ledger](0.9.60.0-design-coverage.md): provider activation/authorization, quotas and durable waits; Research evidence/recovery/statistics; strict and opt-in append finals; content-addressed documents and source inspection; Examine Accept/Decline.
- [GGUF/benchmark implementation ledger](0.9.60.0-gguf-benchmark-coverage.md): G01–G12, B01–B14, U01–U02, D01 and R01–R04.
- [Extended benchmark audit ledger](0.9.60.0-benchmark-audit-extended-coverage.md): A01–A27, including runtime preparation, exact-schema readiness, semantic unknowns, performance modes, policy separation and evidence presentation.
- [Structured-output implementation notes](0.9.60.0-structured-output.md): pinned SDK behavior, app changes and remaining device assertions.

The later audit's original 16-run/250-result export remains unavailable. The original France/Portugal conversation and the earlier four-run/110-sample benchmark export were recovered during continuation. Exact excerpts/report fields and a raw-field compatibility round-trip are documented in the [continuation review](0.9.60.0-continuation-review.md). Reconstructed fixtures remain labeled accordingly; this is not a complete conversation replay.

## Reproduction

The local environment uses JDK 21.0.8, Gradle 9.8.0 and the installed Android SDK. No dependency upgrade was used to conceal the pinned LiteRT behavior. Run heavy Gradle tasks sequentially on machines with limited memory.

```sh
./gradlew :app:testDebugUnitTest
./gradlew -I scripts/heap-stress.init.gradle :app:testDebugUnitTest \
  --tests '*ResearchSessionStoreHeapTest' --tests '*StreamingDebugBundleTest' \
  --tests '*BenchmarkStreamingExportHeapTest' --tests '*BenchmarkColdStreamingExportHeapTest'
./gradlew :app:lintDebug
./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin
./gradlew :macrobenchmark:assemble
python3 -m unittest discover -s scripts/tests
python3 scripts/check_android_resources.py
python3 scripts/check_android_regex.py
python3 scripts/check_local_runtime_apk.py app/build/outputs/apk/debug/*.apk
python3 scripts/check_no_telemetry.py app/build/outputs/apk/debug/*.apk
```

Use the configured `memoryModelFile` when working offline. The exact final commands, commit, counts and workflow URLs will be recorded after completion. Failed or interrupted commands are never counted as passing validation.

## Qualification boundaries

No Android device is attached in this workspace. Physical GPU/NPU execution, repeated Full runs on the reported phone, measured warm-inspection latency, sustained thermals/energy, frame distributions, screen-reader interaction and reboot/Doze recovery remain device gates. The device-validation workflow requires actual backend enumeration/offload, native tokens, received output and cleanup evidence; a selected accelerator or skipped test cannot qualify it.

No production credential or personal data was submitted to a live provider. Hosted-service availability, live quota behavior and accepted-job cleanup require separate service observations. SDK fields that do not expose effective sampler/schema/output settings or driver/native identities remain unknown. The review must preserve the broader qualification boundaries in [release-scope.json](../../release-scope.json); no absent evidence is promoted to a pass.
