# 0.9.60.0 integration validation

Prepared on 2026-10-24 from `fix/research-free-provider-hf-2026-10-24` (`de1e67b`). Release branch: `release/0.9.60.0-provider-combined-integration`. Version name `0.9.60.0`; version code `128`; Room schema `46`.

## Automated checks

The final unit, lint and APK results are being verified. The environment uses the existing JDK 21.0.8, Gradle 9.8.0 and Android SDK installation; no toolchain or dependency versions were changed for this release.

The first complete integration run executed 4,397 tests with 22 failures and 2 deliberately skipped heap-gate tests. It exposed legacy-policy compatibility, local-publication and fixture issues, which were corrected before final verification. Generated AGP compile and runtime artifacts also contained older classes than the compiled source (confirmed by inspecting the enum and Research circuit bytecode). The generated debug class transforms and both bundle JARs were removed before rebuilding without the build cache. A focused run then passed 83 of 86 tests; the remaining three UI assertions exposed mismatched packaged resource IDs, confirmed with the rendered semantics tree and `aapt2`. The final verification therefore starts with `:app:clean` so bytecode, resource tables and test APK resources are generated together. Earlier failed attempts are not counted as successful validation.

Independent preflights passed: 158 Python script tests; Android resources (29 XML files); Android regex compatibility (832 literal patterns, 51 dynamic patterns skipped); icon resource consistency; QNN packaged provenance metadata. The latter does not establish device execution. Kotlin formatting and whitespace checks are repeated on the final staged diff.

## Requirement-to-regression map

| Behavior | Implementation | Regression coverage |
|---|---|---|
| Activation must not follow a changed model, credential or cloned UID | `ProviderPrivacyPolicy`, `ProviderActivationStore`, `ConfiguredDispatchAuthority` | `ProviderPrivacyPolicyTest` |
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
