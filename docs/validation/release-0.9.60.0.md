# 0.9.60.0 integration validation

Release branch: `release/0.9.60.0-provider-combined-integration`; version **0.9.60.0 (128)**. The published base for this continuation is `beaeea2b`; `main` is `951a6ae7`. Source dependency #740 is merged. Room schema is **47**, introduced by the additive 46→47 migration; published schema 46 remains unchanged.

## Local validation before continuation publication

The continuation integrates the three October 24 designs, the France/Portugal review, the GGUF/benchmark implementation guide and the additional 16-run benchmark audit. This is the local validation snapshot prepared before publishing the continuation to draft PR #741. It preserves failed checkpoint results alongside their follow-ups; an earlier passing checkpoint does not qualify later source changes. The [continuation review](0.9.60.0-continuation-review.md) records the additional saved-wait, original-export, GPU-evidence and cold-export repairs. The [PR checks](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/741/checks) provide results for the published head; the exact tested commit must be checked there.

| Check | Observed result and scope |
|---|---|
| Recovered integration production and unit-test compilation | Passed with benchmark-export, source-paging, process-supervision and comparison-cohort corrections. The additional continuation repairs passed their focused production/test compilation; integrated compilation is a separate final gate. |
| Integrated unit/Robolectric failure-discovery checkpoint | **4,739 tests, 6 failures, 0 errors, 2 reserved for the separate heap gate.** The earlier 4,640-test checkpoint had nine failures; those earlier fixes are included here. A focused integrated follow-up executed **271 tests: 269 passed, 1 retained-results UI failure, 1 reserved heap test**. The UI follow-up remains pending; the other five checkpoint regressions passed without weakening their assertions. |
| Additional continuation regressions | **101 Combined, 24 provider-wait and six GPU-plan tests passed**, with no failures or skips in those focused packets. This includes exact recovered France wording and Portugal report fields, post-wake/post-launch permission changes, deadline projection drift and persisted cancelled-GPU evidence. |
| Cold-start export follow-up | Prepared production/Room checks passed: two pure tests, four Room tests and one 256 MiB cold export. The latter streamed **101,541,461 bytes with 128 complete raw bodies**. The new cold test is included in the CI heap gate. The final integrated run includes these production changes and the retained-results visibility correction. |
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
| Kotlin formatting / whitespace | All **379 changed Kotlin files** passed ktlint 1.3.1 after integration; `git diff --check` passed. |
| Android lint on the recovered integration | Passed in **19m29s**. This precedes the additional continuation Kotlin changes and cold-export integration. The CI lint step now allows 30 minutes, within a 100-minute validation job. |
| Full integrated continuation validation | Queued after the existing export verification when this snapshot was written. It includes the additional Combined, provider-wait and GPU fixes, cold export and retained-results visibility correction. Source is frozen for this gate; compilation, complete unit execution, heap tests and final-commit CI are not inferred from focused passes. |
| Remaining APK / device-test gates | Final source APK packaging, lint and instrumented-test compilation are checked by their applicable workflows. Earlier macrobenchmark/device-test compilation and the expanded heap gate remain scoped checkpoint results. |
| Required GitHub Actions on the final published commit | Pending. Historical runs on `66d009f` or `beaeea2b` are not results for this continuation. |

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
