# 0.9.60.0 integration validation

Prepared on 2026-10-24 from `fix/research-free-provider-hf-2026-10-24` (`de1e67b`). Release branch: `release/0.9.60.0-provider-combined-integration`. Version name `0.9.60.0`; version code `128`; Room schema `46`.

## Automated checks

The environment uses JDK 21.0.8, Gradle 9.8.0 and the installed Android SDK. No repository toolchain or dependency versions were changed for this release.

| Check | Result |
|---|---|
| Clean complete unit suite on integration commit `66d009f` | 4,401 discovered; 4,399 passed, zero failures/errors, two intentionally reserved for the separate heap gate |
| Final endpoint-consent hardening: eight affected test classes | 71 passed; zero failures/errors/skips |
| Dedicated 256 MiB recovery/export gate | Passed in GitHub PR Validation on `66d009f` |
| Android lint | Passed in GitHub PR Validation on `66d009f`; local duplicate stopped at the workspace memory ceiling |
| Review APK, packaged-runtime verification and unsigned-release build | Passed in the separate GitHub UI Review APK and Build Unsigned Release workflows on `66d009f` |
| Python script suite | 158 passed |
| Resource, regex and icon preflights | Passed: 29 XML files, 832 literal patterns; 51 dynamic regex patterns intentionally skipped |
| QNN packaged provenance metadata | Passed; this is not a device-execution result |
| Kotlin formatting, whitespace and exported Room schemas | Passed |

The final focused tests cover activation and personal-tool grants after changing the endpoint query, dispatch authority, personal-data guards, tool resolution, credential migration, backup consent exclusion and provider-wait ownership/cancellation. The complete clean suite ran before this last scope-hardening change; its result is not presented as a second full-suite run on the final patch.

Earlier generated test bytecode and packaged resource IDs were inconsistent. A clean rebuild regenerated them together and passed the full unit suite. Both the combined invocation and a separate local lint attempt were interrupted when memory reached this workspace's 8 GiB ceiling. Local APK assembly reached native configuration and exposed missing Vulkan headers; the CI-equivalent headers were then obtained in the temporary toolchain, without disabling a runtime backend. A successful final local APK build is not claimed. The completed GitHub lint, heap, APK and release-build checks provide the integration-commit results above. Interrupted or failed attempts are not counted as passing validation.

Reproduction uses the repository's standard Gradle wrapper with JDK 21. Run compilation/tests and lint in separate invocations on memory-constrained machines:

```sh
./gradlew :app:clean :app:testDebugUnitTest --no-build-cache
./gradlew :app:assembleDebug --no-build-cache
./gradlew :app:lintDebug --no-build-cache
./gradlew -I scripts/heap-stress.init.gradle :app:testDebugUnitTest \
  --tests '*ResearchSessionStoreHeapTest' --tests '*StreamingDebugBundleTest'
python3 scripts/check_local_runtime_apk.py app/build/outputs/apk/debug/*.apk
python3 scripts/check_no_telemetry.py app/build/outputs/apk/debug/*.apk
```

The initial [PR Validation run](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/40117585643) passed unit tests, exported schemas, Android lint and the heap gate. A PR-description edit triggered its configured `edited` event and superseded that run during its later APK step; it was not a failing test result. The independent [UI Review APK](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/40117585686) and [Build Unsigned Release](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/actions/runs/40117585713) workflows passed. CodeQL, Kotlin lint, Android acceptance, native-memory smoke, marketplace contracts, build downloads, diagnostic audit and GenieX preview validation also passed on `66d009f`.

GitHub Actions reruns against the final published PR head. Those exact-head workflows remain pending at handoff; integration-commit results are not represented as final-head completion. No release-qualification gate is waived.

## Requirement-to-regression map

| Behavior | Implementation | Regression coverage |
|---|---|---|
| Activation must not follow a changed endpoint, model, credential or cloned UID | `ProviderPrivacyPolicy`, `ProviderActivationStore`, `ConfiguredDispatchAuthority` | `ProviderPrivacyPolicyTest` |
| Personal consent cannot unlock prohibited routes or other recipients | `FreeModelToolConsentStore`, `PersonalDataGuardedTool`, `AgentToolResolver` | `ProviderPrivacyPolicyTest`, `FreeModelToolConsentStoreTest`, `PersonalDataGuardedToolTest`, `AgentToolResolverTest` |
| Backups are not informed consent | `CompleteBackupPreferences` | `ProviderConsentBackupTest` |
| Retired routes cannot re-enter catalog or generic dispatch | `FreeAiProvider`, `FreeModelCatalog`, `ProviderRequestConfig` | `FreeAiProviderTest`, `FreeModelCatalogTest` |
| Local quotas survive process recreation; Retry-After tolerates skew | `FreeAiRequestLimiter`, `FreeHttpFailure` | `FreeQuotaPersistenceTest`, `FreeAiRequestLimiterTest`, `FreeProviderReliabilityTest` |
| Only an undispatched current owner can enter a durable wait; cancellation prevents claiming it | `ProviderWaitStore`, `ProviderLimitResumeWorker`, `AgentPersistenceDao` | `ProviderWaitPersistenceTest`, `CombinedResponseStateTest` |
| Source contributor rows survive final publication; duplicate triggers use one final identity | `CombinedFinalMessage`, `AgentPersistenceDao` | `CombinedSingleDispatchPersistenceTest`, existing ownership/revision/recovery suites |
| Every retained source block is represented beyond 72 units; conflicts/code/tables/Unicode remain intact | `CombinedSourcePreservation`, `CombinedDeliverySession` | `CombinedSourcePreservationTest` |
| A capacity fallback commits source text without a provider dispatch | `CombinedSynthesisPlanner`, `publishLocalCombinedAssembly` | `CombinedSingleDispatchPersistenceTest`, existing writer-plan and synthesis-planner suites |
| Per-source cache reuse preserves IDs and occurrences when sources change order | `CombinedContentIndexes` | `CombinedContentIndexTest` |
| Schema upgrades preserve conversation evidence and profile credentials | `ChatDatabaseV2Migrations.MIGRATION_45_46` | `RoadmapMigrationMatrixTest` (including versions 42–45), existing migration suites |
| Existing Research/HF repairs remain present | Base-branch Research coordinator, queue adapter, repair/statistics changes | Existing Research, free-provider, shared-stage and delivery suites |

## Qualification boundaries

`adb devices -l` reported no attached devices. Connected-device tests, frame-time/heap measurements, screen-reader exercises and WorkManager reboot/background-liveness qualification were not performed. Unit/Robolectric rendering is not physical-device qualification. No production credential or personal data was submitted to a live provider. The Qwen Hugging Face Space's upstream availability is not asserted by a passing client test.

See [release scope](../releases/0.9.60.0.md#remaining-designrelease-gates) for requested design work that remains outside this implementation draft. The branch must remain a draft until those design decisions and device/live-provider gates are reviewed; it is not a claim that all three design documents have been fully implemented.
