package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Display identity belongs to the frozen route that produced this response, never a later selection. */
internal data class GatewayGraphResponse(
    val result: JsonObject,
    val operation: String,
    val gatewayProfileUid: String,
    val gatewayName: String,
    val indexOutcomeUnknown: Boolean = false,
    val durableIndexJobs: Boolean = false,
    val modelAccess: String = "NOT_CHECKED",
    val indexAttemptHash: String? = null,
    val confirmedIndexAttemptHash: String? = null
)

internal class GatewayGraphOperationException(
    cause: Exception,
    val gatewayProfileUid: String?,
    val gatewayName: String?,
    val repairAction: String?,
    val indexOutcomeUnknown: Boolean,
    val failureCode: String = graphFailureCode(cause),
    val indexAttemptHash: String? = null,
    val currentIndexOutcomeUnknown: Boolean = false
) : IllegalStateException(cause.message ?: "Repository graph unavailable.", cause)

/** Keeps a bad response or rejected identity distinct from a disconnected host. */
internal class GatewayGraphIntegrityException(val code: String, message: String) : IllegalStateException(message)

internal class GatewayGraphResultException(val code: String, message: String, val repairAction: String) : IllegalStateException(message)

internal fun graphFailureCode(failure: Exception): String = when (failure) {
    is GatewayGraphIntegrityException -> failure.code
    is GatewayGraphResultException -> failure.code
    is GatewayGraphHttpFailure -> failure.code
    is GatewayGraphAdmissionException -> failure.code
    is javax.net.ssl.SSLException -> "GRAPH_TLS_REJECTED"
    is GatewayAdmissionException -> "GRAPH_${failure.reason.name}"
    is GatewayGraphUnavailableException -> failure.prerequisites.firstOrNull()?.name ?: "GRAPH_UNAVAILABLE"
    is GatewayEngineeringRequestRejected -> failure.errorCode?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{1,100}")) } ?: "GRAPH_REQUEST_REJECTED"
    is java.io.IOException -> "GRAPH_TRANSPORT_UNAVAILABLE"
    else -> "GRAPH_OPERATION_FAILED"
}

/** Source-sharing consent is specific to this account connection, repository, host and inference destination. */
internal object GatewayGraphSharing {
    fun key(profile: PlatformV2, connection: String, repository: String, gateway: String): String =
        GitHubRepositoryStateStore.digest(
            JsonArray(
                listOf(profile.uid, profile.compatibleType.name, profile.apiUrl, profile.providerConnectionUid.orEmpty(), profile.model, connection, repository.lowercase(Locale.ROOT), gateway).map(::JsonPrimitive)
            ).toString()
        )

    fun allowed(profile: PlatformV2?, preferences: GitHubChatProfileOverride?, context: GitHubConversationContext, requestedModel: String? = null): Boolean {
        if (profile == null || !profile.enabled || profile.uid != context.mainProfileUid || PrivacyModeStore.active()) return false
        if (GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)) return true
        val gateway = preferences?.engineeringGatewayProfileUid ?: return false
        if (!requestedModel.isNullOrBlank() && requestedModel != profile.model) return false
        return preferences.graphSharingConsent == key(profile, context.connectionUid, context.repository.fullName, gateway)
    }
}
