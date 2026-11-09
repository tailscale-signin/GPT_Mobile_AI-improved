package dev.chungjungsoo.gptmobile.data.audit

/** Existing candidate cases, never a claim of complete area coverage or execution. */
internal val APP_AUDIT_SOURCE_HARNESSES: Map<String, List<String>> = linkedMapOf(
    "android_shell" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/queue/DurableExecutionIntegrationTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/presentation/service/AgentRunForegroundLifecycleTest.kt",
        "app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/CombinedFinalizationLifecycleInstrumentedTest.kt"
    ),
    "chat_ui" to listOf(
        "app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/GitHubPromptMotionInstrumentedTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ComposerInputLifecycleTest.kt"
    ),
    "accessibility_and_localization" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/appearance/AppearanceContractsTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/GitHubWorkspaceFilesTest.kt",
        "app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/presentation/navigation/NavigationMotionInstrumentedTest.kt"
    ),
    "agent_execution" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/agent/AgentRunnerTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/agent/GitHubReplayPlanningTest.kt"
    ),
    "agent_tools" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/agent/ToolAuthorizationOutcomeTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/McpCatalogueExecutionSafetyTest.kt"
    ),
    "mcp_plugins" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/ModernMcpTransportTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/McpFailureDiagnosticsTest.kt"
    ),
    "marketplace_plugins" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/marketplace/MarketplaceAvailabilityTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/mcp/MarketplaceViewModelTest.kt"
    ),
    "model_providers" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/network/OpenAICompletionDecoderTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/network/SseUtilsTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/agent/provider/ProviderToolRequestSerializationTest.kt"
    ),
    "models_and_profiles" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepositorySecretMigrationTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepositoryConfigBackupTest.kt"
    ),
    "local_inference" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/InferenceAdmissionTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/ProcessGenerationSupervisionTest.kt",
        "scripts/qualify_device_performance.py"
    ),
    "gateway_client" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/network/gateway/GatewayAvailabilityRepositoryTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/queue/GatewayQueueStoreTest.kt"
    ),
    "network" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/network/ResilientStreamingClientTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/network/LocalServiceHealthTest.kt"
    ),
    "github" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/github/GitHubVerifiedSessionTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/github/GitHubIntegrationAcceptanceTest.kt"
    ),
    "research" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/DeepResearchAcquisitionTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/research/feature/ArticleExaminationRecoveryTest.kt"
    ),
    "memory_and_rag" to listOf(
        "app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryDurabilityInstrumentedTest.kt",
        "app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/data/memory/LocalSemanticMemoryInstrumentedTest.kt"
    ),
    "persistence" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/database/ChatDatabaseV2MigrationsTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/database/GatewayJobPersistenceTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/database/GatewayJobsMigrationTest.kt"
    ),
    "diagnostics_and_export" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/CorrelatedDiagnosticExportTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/RunDiagnosticJournalTest.kt"
    ),
    "benchmark" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/audit/AppAuditQualificationTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/benchmark/BenchmarkQueuePolicyTest.kt"
    ),
    "auth_and_security" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/permissions/ToolGrantRevocationTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/permissions/ProviderPrivacyPolicyTest.kt"
    ),
    "voice_and_media" to listOf(
        "app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/voice/VoiceEntryInstrumentedTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/voice/VoiceSessionTest.kt"
    ),
    "portable_domain" to listOf(
        "core-domain/src/jvmTest/kotlin/dev/chungjungsoo/gptmobile/core/UsageMathTest.kt"
    ),
    "native_bridge" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/NativeHandleLifecycleTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/RuntimeBinaryScopeTest.kt",
        "scripts/qualify_device_performance.py"
    ),
    "gateway_software" to listOf(
        "gateway/check_gateway.py",
        "gateway/tests/test_gateway_model_preparation.py"
    ),
    "authentication_service" to listOf(
        "authentication/test_app.py",
        "authentication/test_ticket_broker.py",
        "authentication/test_github_broker.py"
    ),
    "external_integrations" to listOf(
        "mcp/crawler/tests/test_http_transport.py",
        "mcp/crawler/tests/test_network.py",
        "mcp/crawler/tests/test_server_coordinator.py"
    ),
    "other_production" to listOf(
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/dto/ProviderAttachmentSerializationTest.kt",
        "app/src/test/kotlin/dev/chungjungsoo/gptmobile/util/ApiStateTerminalCollectionTest.kt"
    )
)
