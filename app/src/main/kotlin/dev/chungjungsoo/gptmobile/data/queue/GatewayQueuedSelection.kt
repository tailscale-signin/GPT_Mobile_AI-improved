package dev.chungjungsoo.gptmobile.data.queue

import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import java.net.URI
import kotlinx.serialization.Serializable

/** The accepted destination survives offline sends without persisting credential contents. */
@Serializable
data class GatewayQueuedSelection(
    val profileUid: String,
    val model: String,
    val endpoint: String,
    val providerType: String,
    val profileRevision: String
) {
    fun validate(profile: PlatformV2): String? = when {
        profile.uid != profileUid -> "The original Gateway profile is unavailable."
        !profile.enabled -> "The original Gateway profile is disabled. Enable it to resume the queue."
        profile.compatibleType.name != providerType || profile.apiUrl.trimEnd('/') != endpoint ->
            "The Gateway destination changed. Restore the original profile to resume its saved queue."
        ConfiguredDispatchAuthority.profileRevision(profile.copy(model = model)) != profileRevision ->
            "The Gateway profile configuration changed. Restore its accepted settings or remove the queued message."
        else -> null
    }

    companion object {
        fun capture(profile: PlatformV2, model: String): GatewayQueuedSelection {
            require(profile.uid.isNotBlank() && profile.enabled) { "Select an enabled Gateway profile." }
            require(model.isNotBlank() && model == model.trim() && model.length <= 512 && model.none(Char::isISOControl)) {
                "Select a valid Gateway model."
            }
            val endpoint = profile.apiUrl.trimEnd('/')
            val uri = runCatching { URI(endpoint) }.getOrNull()
            require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Configure a valid Gateway endpoint."
            }
            return GatewayQueuedSelection(profile.uid, model, endpoint, profile.compatibleType.name, ConfiguredDispatchAuthority.profileRevision(profile.copy(model = model)))
        }
    }
}

enum class GatewayQueuedState { QUEUED, WAITING_FOR_GATEWAY, RUNNING, RECONCILING, BLOCKED }

data class GatewayQueuedChatState(
    val state: GatewayQueuedState,
    val queuedCount: Int = 0,
    val message: String? = null,
    val runId: String? = null,
    val canCancelUnsubmitted: Boolean = false,
    val canRetryModelPreparation: Boolean = false
)
