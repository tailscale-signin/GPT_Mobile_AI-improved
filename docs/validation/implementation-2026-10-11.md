# Validation record — GPU/NPU, diagnostics and academic research

Base commit: `5574590965a3a79571a16fa421979b4a3c2cc310` (`feat/qnn-full-reliability-v2-2026-10-11`). Implementation branch: `feat/gpu-npu-execution-plans-2026-10-11`. This record describes local validation of the branch contents, not a signed production release or physical-device certification.

Environment: Linux x86-64, JDK 21, Android API 37/build-tools 37, Gradle 9.8, Kotlin ktlint 1.3.1, Node, Python 3.12. Optional ObjectBox build analytics disabled. JVM tests use an explicit Byte Buddy agent in this container; production dependencies/behavior are unchanged by that validation-only setting. Kotlin incremental compilation and the build cache are disabled for the final source check.

| Check | Result |
| --- | --- |
| Modified/new Kotlin and Gradle formatting | ktlint 1.3.1 passes. |
| Whitespace | `git diff --check` passes. |
| Android Kotlin and focused JVM suites | 414 runtime/provider/benchmark/research tests and 28 chat repository tests pass across two focused runs: 442 total, zero failures or skips. Generated Android outputs were rebuilt cleanly before the final checks. |
| Instrumented sources and debug APKs | `compileDebugAndroidTestKotlin` and `assembleDebug` pass. Universal, arm64-v8a and x86_64 debug APKs built; no instrumentation ran on a device. |
| Native APK packaging | All three APKs pass the repository validator: pinned hashes/inventory, MediaPipe and ObjectBox JNI contracts, embedding-model checksum and 16 KiB host ELF LOAD alignment. The universal APK contains 18 pinned runtime libraries and 24 aligned Android host libraries. |
| Chat coroutine size | Debug APK DEX `ChatRepositoryImpl$completeChat$2.invokeSuspend` has 30,522 16-bit code units; the largest extracted preparation helper has 6,168. This static size check is not an ART compilation or latency measurement and is not directly equated with the historical log's instruction count. |
| Gateway Python suite, hash-pinned dependencies | 193 tests pass, including 13 academic cases and live local HTTP/authentication fixtures. |
| Research bridge Node suite | 17 tests pass, including actual stdio/HTTP academic facade, missing/wrong Bearer, foreign Origin, fixed child and strict argument tests. |
| Repository Python scripts | 65 tests pass. Their native-device/DEX reports are fixture tests, not observations of a physical device. |
| Regex preflight | 414 literal patterns checked, 30 dynamic patterns skipped, zero errors. |
| Resource preflight | 25 XML files pass; icons match reference; packaging provenance remains LiteRT-LM 0.18.0 / QAIRT 2.51.0. |
| Room schema history | Pass; benchmark database v1→v2 export included. Chat database version unchanged. |
| Publisher artifact references | 28 SHA-256 values and revision metadata pinned; catalog copies match at revision 3. No model weights downloaded or executed for this check. |
| Academic live endpoints | OpenAlex, Crossref, arXiv, Europe PMC, DataCite each returned one anonymous record via HTTP 200. Metadata fixtures retained; not a phone/gateway deployment test. |
| PDF reader | Bounded synthetic PDF extraction passes with actual host `pdftotext`, page 1 locator and exact-version quote verification; disguised HTML fails. Windows Job Object behavior not tested here. |

## Reproduce

```sh
export OBX_DISABLE_ANALYTICS=true
./gradlew :app:compileDebugKotlin --no-build-cache -Pkotlin.incremental=false
./gradlew :app:testDebugUnitTest \
  --tests '*localruntime.*' --tests '*benchmark.*' --tests '*research.*' \
  --tests '*Academic*' --tests '*PluginBackupPreferencesTest' \
  --tests '*ProviderEventAssemblerTest' --tests '*ProviderAdaptersTest' \
  --tests '*ProviderErrorDiagnosticsTest' --tests '*OpenAIAPIImplTest' \
  --tests '*LiteRtLmAdapterTest' --tests '*AndroidResearchWorkflowTest' \
  --tests '*StructuredDiagnosticExportTest' --tests '*ToolServicePolicyTest' \
  --tests '*ChatRepositoryImplTest' \
  --tests '*ModelCatalogParserTest' --tests '*ModelCatalogRepositoryTest' \
  --no-build-cache -Pkotlin.incremental=false
./gradlew :app:compileDebugAndroidTestKotlin :app:assembleDebug
python3 scripts/check_local_runtime_apk.py app/build/outputs/apk/debug/*.apk
python3 scripts/check_android_regex.py
python3 scripts/check_room_schemas.py
bash scripts/validate.sh resources
python3 -m unittest discover -s scripts/tests
python3 -m venv /tmp/gateway-validation
/tmp/gateway-validation/bin/python -m pip install -r gateway/requirements.txt
/tmp/gateway-validation/bin/python -m unittest discover -s gateway/tests
npm ci --prefix mcp/research --ignore-scripts --no-audit --no-fund
npm test --prefix mcp/research
```

Run the research bridge tests from their npm working directory; npm supplies that directory automatically. JVM self-attachment restrictions may require explicitly attaching the same test-classpath Byte Buddy agent in restricted runners.

## Required hardware and deployment checks

- No Android phone or emulator was attached. `LocalInferenceProcessTest` source/APK compilation does not execute process death, Binder backpressure or native cancellation on a device.
- No GPU/NPU tokens, throughput, sustained thermal gain, energy/token, UI frame improvement or Gemma 4 stability was measured. None of the new measurements is promoted to qualified evidence automatically.
- Run the SM8750 Gemma 3 short-context baseline first. Retain Gemma 4 protection until exact artifact/runtime/dispatch/firmware qualification passes G1–G8.
- Run 500-turn reliability, cancellation/death/memory injection, Unicode/tool ordering, release/profileable startup/scroll/GC, requested-versus-confirmed runtime settings, and 15-minute sustained comparisons on each target tuple.
- Validate Android with/without the authenticated PC gateway, Tailscale reconnect, in-flight revocation and stop-and-summarize, including completed sibling evidence. Run Windows parser-resource limits on a Windows host.
- Run Deep Research DR-001–028 device/deployment cases and the guide's 40-query recall/relevance/evidence study before launch. Disabled specialist provider contracts remain disabled until independently verified.
- A signed release APK and production rollout are separate release gates. This branch is not merged or deployed by these checks.

## Failure corrections during validation

The implementation run caught and corrected capability-fallback expectations, assertions for newly retained finish reasons and Groq's explicit low reasoning effort, stale Android ASM outputs during development (confirmed older class fields despite fresh Kotlin classes, resolved with a clean generated-output rebuild), test-runner agent attachment, a missing Compose import, optional arXiv PDF URLs returning HTML, a thread-unsafe parser pre-exec hook, Europe PMC pagination, a disabled-source lookup bypass, and stale gateway package hashes after updating its guide. The final results above supersede those intermediate runs. Initial gateway discovery without its optional test dependencies failed import; the reported suite uses the repository's hash-pinned requirements.

## Merge verification repairs

PR #652's first full LiteRT and GenieX test runs exposed five failures outside the initial focused suite. SQLite compile diagnostics can be empty or unavailable; that is now treated as unknown FTS5 support and permits one cached creation probe. A populated options list that omits FTS5 still avoids the unsupported-module attempt. Regression coverage includes present, absent, empty and failing diagnostics, FTS4 reuse and the portable fallback.

The transport recovery tests now distinguish retry telemetry from provider payloads, verify retry indices 1 and 2, and still require exactly three request attempts, one terminal failure for a permanent outage, and no replay after partial output. PR checks must pass on the repair commit before merging; the initial focused validation above is not a claim that the first full CI run passed.
