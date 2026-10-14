package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import dev.chungjungsoo.gptmobile.data.orchestration.OptimizationMode
import kotlinx.serialization.Serializable

@Serializable
enum class AssistProcessingPermission { ALLOWED, DENIED, REVIEW_REQUIRED }

/** Migration snapshots historical exclusions once; memory learning is independent afterwards. */
fun LocalAssistPolicy.migrateProcessingPermissions(profiles: List<PlatformV2>): LocalAssistPolicy {
    if (permissionVersion >= 1) return this
    val inherited = profiles.filter { it.compatibleType == ClientType.LITERT_LM }.associate { profile ->
        profile.uid to if (profile.excludesMemory()) AssistProcessingPermission.REVIEW_REQUIRED else AssistProcessingPermission.ALLOWED
    }
    return copy(permissionVersion = 1, profilePermissions = inherited + profilePermissions)
}

fun LocalAssistPolicy.processingPermission(profile: PlatformV2): AssistProcessingPermission =
    profilePermissions[profile.uid] ?: AssistProcessingPermission.REVIEW_REQUIRED

fun LocalAssistPolicy.allowsProcessing(profile: PlatformV2): Boolean = profile.enabled && profile.compatibleType == ClientType.LITERT_LM && processingPermission(profile) == AssistProcessingPermission.ALLOWED

@Serializable
data class EffectiveAssistPolicy(
    val version: Int = 1,
    val requestedEnabled: Boolean,
    val enabled: Boolean,
    val requested: LocalAssistPolicy,
    val effective: LocalAssistPolicy,
    val explanations: List<String>
)

/** The settings UI and execution binder consume exactly this same resolution. */
fun AppFeatureSettings.resolveAssistPolicy(): EffectiveAssistPolicy {
    val requested = localAssistPolicy
    val tuning = executionPolicy
    val active = tuning.mode != OptimizationMode.OFF
    val reasons = mutableListOf<String>()
    val enabled = localAssist && (!active || tuning.warmLocalAssist)
    if (!localAssist) reasons += "Local Assist is off."
    if (localAssist && !enabled) reasons += "The execution policy disables Local Assist."
    val effective = requested.copy(
        evidenceSelection = requested.evidenceSelection && (!active || tuning.evidenceHandoff),
        summarizeRetrievedText = requested.summarizeRetrievedText && (!active || tuning.assistCompression),
        preparation = if (active && !(tuning.coldLoadAssist && tuning.mode in setOf(OptimizationMode.MAXIMUM_SAVINGS, OptimizationMode.CUSTOM))) AssistPreparation.WARM_ONLY else requested.preparation,
        maxAttempts = if (tuning.mode == OptimizationMode.FAST_RESPONSE) minOf(1, requested.maxAttempts) else requested.maxAttempts,
        taskTimeoutSeconds = if (tuning.mode == OptimizationMode.FAST_RESPONSE) minOf(3, requested.taskTimeoutSeconds) else requested.taskTimeoutSeconds
    ).validated()
    if (requested.preparation != effective.preparation) reasons += "The execution policy restricts preparation to an already loaded matching model."
    if (requested.evidenceSelection != effective.evidenceSelection) reasons += "Evidence selection is disabled by the execution policy."
    if (requested.summarizeRetrievedText != effective.summarizeRetrievedText) reasons += "Compression is disabled by the execution policy."
    if (requested.maxAttempts != effective.maxAttempts || requested.taskTimeoutSeconds != effective.taskTimeoutSeconds) reasons += "Fast response mode narrows the requested attempt or time allowance."
    if (effective.profileUid != null && effective.profilePermissions[effective.profileUid] != AssistProcessingPermission.ALLOWED) reasons += "The selected helper needs explicit permission to process task data."
    return EffectiveAssistPolicy(requestedEnabled = localAssist, enabled = enabled, requested = requested, effective = effective, explanations = reasons)
}
