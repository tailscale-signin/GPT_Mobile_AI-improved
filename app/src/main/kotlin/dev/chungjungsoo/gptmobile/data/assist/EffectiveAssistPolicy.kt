package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import kotlinx.serialization.Serializable

@Serializable
enum class AssistProcessingPermission { ALLOWED, DENIED, REVIEW_REQUIRED }

/** Preserve explicit grants; new policies cannot derive consent from memory eligibility. */
fun LocalAssistPolicy.migrateProcessingPermissions(profiles: List<PlatformV2>): LocalAssistPolicy {
    if (permissionVersion >= 1) return this
    val inherited = profiles.filter { it.compatibleType == ClientType.LITERT_LM }.associate { profile ->
        profile.uid to if (version >= 4 || profile.excludesMemory()) AssistProcessingPermission.REVIEW_REQUIRED else AssistProcessingPermission.ALLOWED
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
    val effective = requested.validated()
    val reasons = buildList {
        if (!localAssist) add("Local Assist is off.")
        if (requested.profileUid == null) add("Choose a helper.")
        if (requested.profileUid != null && requested.profilePermissions[requested.profileUid] != AssistProcessingPermission.ALLOWED) add("The selected helper needs processing permission.")
    }
    return EffectiveAssistPolicy(requestedEnabled = localAssist, enabled = localAssist, requested = requested, effective = effective, explanations = reasons)
}
