package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.Serializable

@Serializable
enum class BenchmarkExecutionPolicy(val label: String) { DETERMINISTIC_CAPABILITY_V1("Deterministic capability"), PROFILE_SETTINGS_V1("Saved profile settings") }

@Serializable
enum class BenchmarkStructuredOutputMode { NATIVE_JSON_SAFE_V1, PROMPT_JSON_VERIFIED_V1 }

/** Request-scoped benchmark intent; never mutates the saved chat profile. */
class BenchmarkRequestContext(
    val executionPolicy: BenchmarkExecutionPolicy = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1,
    val responseSchemaJson: String? = null,
    val requestDeadlineMs: Long? = null,
    val readinessProbe: Boolean = false,
    val structuredOutputMode: BenchmarkStructuredOutputMode = BenchmarkStructuredOutputMode.NATIVE_JSON_SAFE_V1,
    val verifiedStructuredCompatibilityKey: String? = null
) : AbstractCoroutineContextElement(Key) {
    init {
        require(requestDeadlineMs == null || requestDeadlineMs > 0)
    }
    companion object Key : CoroutineContext.Key<BenchmarkRequestContext>
}

@Serializable
data class BenchmarkProfileSettings(
    val model: String,
    val temperature: Float?,
    val topP: Float?,
    val topK: Int?,
    val outputTokens: Int?,
    val reasoning: Boolean,
    val streaming: Boolean,
    val systemPromptDigest: String,
    val toolsDisabled: Boolean,
    val requestedAccelerator: String?,
    val providerOptionsDigest: String,
    val batchMode: Boolean? = null
)

@Serializable
data class BenchmarkSettingsReceipt(
    val executionPolicy: BenchmarkExecutionPolicy,
    val profile: BenchmarkProfileSettings,
    val requested: BenchmarkProfileSettings,
    val overriddenFields: Set<String>,
    val responseSchemaDigest: String?,
    val structuredOutputMode: BenchmarkStructuredOutputMode? = null,
    val verifiedStructuredCompatibilityKey: String? = null,
    val appAppliedSafetyOverrides: Map<String, String> = emptyMap(),
    val appAppliedInstrumentationOverrides: Map<String, String> = emptyMap(),
    val acknowledgedGenerationIds: Set<String> = emptySet(),
    val acknowledgedFields: Set<String> = emptySet(),
    val acknowledgementScope: String = "per-generation native receipts; requested settings alone are not confirmation"
)

internal fun benchmarkProfileSettings(profile: PlatformV2) = BenchmarkProfileSettings(
    profile.model, profile.temperature, profile.topP, profile.topK, profile.maxTokens, profile.reasoning, profile.stream,
    benchmarkHash(profile.systemPrompt.orEmpty()), profile.disableAllTools, profile.accelerator,
    benchmarkHash(listOf(profile.openRouterRouting, profile.ollamaOptions).joinToString("\u0000")),
    batchMode = profile.batchMode
)

internal fun benchmarkSettingsReceipt(profile: PlatformV2, test: BenchmarkCase, policy: BenchmarkExecutionPolicy, sample: BenchmarkSample, context: BenchmarkRequestContext? = null): BenchmarkSettingsReceipt {
    val original = benchmarkProfileSettings(profile)
    val local = profile.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM
    val schemaSafety = local && test.responseSchemaJson != null
    val requested = benchmarkProfile(profile, test.category == "tools", test.requestCategory, policy)
    val sent = benchmarkProfileSettings(if (schemaSafety) requested.copy(reasoning = false) else requested)
    val changed = buildSet {
        if (original.model != sent.model) add("model")
        if (original.temperature != sent.temperature) add("temperature")
        if (original.topP != sent.topP) add("topP")
        if (original.topK != sent.topK) add("topK")
        if (original.outputTokens != sent.outputTokens) add("outputTokens")
        if (original.reasoning != sent.reasoning) add("reasoning")
        if (original.streaming != sent.streaming) add("streaming")
        if (original.batchMode != sent.batchMode) add("batchMode")
        if (original.systemPromptDigest != sent.systemPromptDigest) add("systemPrompt")
        if (original.toolsDisabled != sent.toolsDisabled) add("toolsDisabled")
        if (original.providerOptionsDigest != sent.providerOptionsDigest) add("providerOptions")
    }
    val configurations = sample.runtimeExecutions.mapNotNull { it.configuration }
    return BenchmarkSettingsReceipt(
        policy, original, sent, changed, test.responseSchemaJson?.let(::benchmarkHash),
        structuredOutputMode = context?.structuredOutputMode?.takeIf { test.responseSchemaJson != null },
        verifiedStructuredCompatibilityKey = context?.verifiedStructuredCompatibilityKey,
        appAppliedSafetyOverrides = if (schemaSafety) mapOf("thinking" to "false", "speculativeDecoding" to "false") else emptyMap(),
        appAppliedInstrumentationOverrides = buildMap {
            if (local) put("nativeMetricsEnabled", "true")
            if (original.batchMode != sent.batchMode) put("batchMode", sent.batchMode.toString())
        },
        acknowledgedGenerationIds = configurations.map { it.generationId }.toSet(), acknowledgedFields = configurations.flatMap { it.nativeAcknowledgedFields }.toSet()
    )
}

@Serializable
enum class BenchmarkReadinessStatus { PASSED, FAILED, UNSUPPORTED, UNKNOWN, NOT_REQUIRED }

@Serializable
data class BenchmarkReadinessCheck(
    val id: String,
    val status: BenchmarkReadinessStatus,
    val detail: String? = null,
    val durationMs: Long? = null,
    val sample: BenchmarkSample? = null
)

@Serializable
data class BenchmarkReadinessProbe(
    val id: String,
    val structuredOutputMode: BenchmarkStructuredOutputMode,
    val sample: BenchmarkSample?,
    val compatibilityKey: String?,
    val passed: Boolean,
    val schemaDigest: String? = null,
    val state: String = "TERMINAL"
)

@Serializable
data class BenchmarkReadinessReceipt(
    val startedAt: Long,
    val finishedAt: Long?,
    val checks: List<BenchmarkReadinessCheck>,
    val requiredCheckIds: Set<String>,
    val executionPolicy: BenchmarkExecutionPolicy? = null,
    val compatibilityKey: String? = null,
    val probes: List<BenchmarkReadinessProbe> = emptyList(),
    val structuredOutputMode: BenchmarkStructuredOutputMode = BenchmarkStructuredOutputMode.NATIVE_JSON_SAFE_V1,
    val verifiedStructuredCompatibilityKey: String? = null,
    val nativePrimaryFailureRetained: Boolean = false,
    val schemaModes: Map<String, BenchmarkStructuredOutputMode> = emptyMap(),
    val verifiedStructuredCompatibilityKeys: Map<String, String> = emptyMap(),
    val policy: String = "benchmark-readiness-v1"
) {
    val ready: Boolean get() = finishedAt != null && requiredCheckIds.isNotEmpty() && requiredCheckIds.all { id -> checks.singleOrNull { it.id == id }?.status == BenchmarkReadinessStatus.PASSED }
}

/** Legacy enrollment keys remain readable; each explicit policy owns a separate revision. */
internal fun benchmarkEnrollmentKey(profileUid: String, policy: BenchmarkExecutionPolicy?): String =
    policy?.let { "benchmark-policy:${it.name}:$profileUid" } ?: profileUid

internal fun benchmarkEnrolledRevision(enrollment: Map<String, String>, run: BenchmarkRun): String? =
    enrollment[benchmarkEnrollmentKey(run.profileUid, run.executionPolicy)]
        ?: enrollment[run.profileUid].takeIf { run.executionPolicy == BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1 }

internal fun benchmarkEnrollment(profiles: List<PlatformV2>, localEnvironment: String): Map<String, String> = buildMap {
    profiles.forEach { profile ->
        put(profile.uid, benchmarkConfigKey(profile, localEnvironment, BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1))
        BenchmarkExecutionPolicy.entries.forEach { policy -> put(benchmarkEnrollmentKey(profile.uid, policy), benchmarkConfigKey(profile, localEnvironment, policy)) }
    }
}

internal fun benchmarkReadinessExclusion(run: BenchmarkRun): String? = when {
    run.readiness?.nativePrimaryFailureRetained == true -> "PRIMARY_STRUCTURED_FAILURE_RETAINED"
    run.suiteVersion >= 10 && run.mode !in setOf(BenchmarkMode.QUICK, BenchmarkMode.DELEGATION) && run.readiness?.ready != true -> "FINISHED_POSITIVE_READINESS_NOT_RECORDED"
    else -> null
}
