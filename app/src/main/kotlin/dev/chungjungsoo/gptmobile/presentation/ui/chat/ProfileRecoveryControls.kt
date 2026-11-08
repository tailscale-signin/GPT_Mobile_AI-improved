package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.net.URI

internal enum class ProfileRecoveryKind { NONE, REASONING, BILLING, SETTINGS }

internal fun profileRecoveryKind(category: String, details: String): ProfileRecoveryKind = when {
    category == "BILLING_UNAVAILABLE" || Regex("\\bBILLING_UNAVAILABLE\\b").containsMatchIn(details) -> ProfileRecoveryKind.BILLING
    Regex("\\bREASONING_(?:ON_UNSUPPORTED|OFF_UNSUPPORTED|CAPABILITY_[A-Z_]+)\\b").containsMatchIn(details) -> ProfileRecoveryKind.REASONING
    Regex("\\bGATEWAY_(?:REMOTE_SURFACE_NOT_ADMITTED|MISSING_SCOPE|TRANSPORT_IDENTITY_REQUIRED|IDENTITY_MISMATCH|UNSUPPORTED_CONTRACT|INVALID_RESPONSE|INVALID_CONNECTION|SOURCE_MISMATCH|HTTP_REJECTED)\\b").containsMatchIn(details) -> ProfileRecoveryKind.SETTINGS
    category in setOf("INCOMPATIBLE_CONFIGURATION", "ENDPOINT_RETIRED", "REQUEST_REJECTED", "POLICY_STALE", "PRIVACY_CONFIGURATION", "AUTHENTICATION_FAILED", "ACCESS_FORBIDDEN") -> ProfileRecoveryKind.SETTINGS
    else -> ProfileRecoveryKind.NONE
}

/** A protocol-compatible proxy may have a different billing account. Never infer it from a name/model. */
internal fun profileBillingUrl(profile: PlatformV2?): String? {
    if (profile?.compatibleType != ClientType.ANTHROPIC) return null
    val endpoint = runCatching { URI(profile.apiUrl) }.getOrNull() ?: return null
    return "https://platform.claude.com/settings/billing".takeIf {
        endpoint.scheme.equals("https", true) && endpoint.host.equals("api.anthropic.com", true) && endpoint.userInfo == null && endpoint.port in setOf(-1, 443)
    }
}

data class ReasoningRecoveryUiState(
    val checking: Boolean = false,
    val requested: ReasoningMode? = null,
    val status: ReasoningCapabilityStatus? = null,
    val qualified: Boolean = false,
    val frozenRequest: Boolean = false,
    val qualifiedAtMillis: Long? = null,
    val routeVerified: Boolean? = null
)

internal data class ReasoningRecoveryRequest(val profile: PlatformV2, val mode: ReasoningMode)

/** Process-local proof only. A retained failure has no recorded configuration revision: restart requires recheck. */
internal class ReasoningRecoveryGate(private val now: () -> Long = System::currentTimeMillis) {
    private data class Proof(val request: ReasoningRecoveryRequest, val observation: ReasoningCapabilityObservation, val networkEpoch: Long)
    private val proofs = mutableMapOf<String, Proof>()

    fun record(attemptId: String, request: ReasoningRecoveryRequest, observation: ReasoningCapabilityObservation): Boolean {
        proofs.remove(attemptId)
        if (request.mode != ReasoningMode.AUTO && !observation.routeVerified) return false
        if (observation.profileUid != request.profile.uid || observation.model != request.profile.model || observation.requested != request.mode || !observation.permitsRequest) return false
        proofs[attemptId] = Proof(request, observation, dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkEpoch)
        return permits(attemptId, request)
    }

    fun permits(attemptId: String, request: ReasoningRecoveryRequest): Boolean {
        val proof = proofs[attemptId] ?: return false
        val age = now() - proof.observation.observedAtMillis
        return proof.request == request && proof.networkEpoch == dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkEpoch && proof.observation.permitsRequest && age in 0 until 20_000L
    }

    fun invalidate(attemptId: String) {
        proofs.remove(attemptId)
    }
}

@Composable
internal fun ProfileRecoveryActions(
    kind: ProfileRecoveryKind,
    profileName: String,
    state: ReasoningRecoveryUiState?,
    canManageBilling: Boolean,
    onSettings: () -> Unit,
    onBilling: () -> Unit,
    onRecheck: () -> Unit,
    onSwitchToAuto: () -> Unit,
    onRetry: () -> Unit,
    enabled: Boolean = true
) {
    val now by dev.chungjungsoo.gptmobile.presentation.common.freeProviderClock()
    val qualified = state?.qualified == true && state.qualifiedAtMillis?.let { now - it in 0 until 20_000L } == true
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (kind) {
            ProfileRecoveryKind.REASONING -> {
                val mode = state?.requested?.name?.lowercase()?.replaceFirstChar { it.uppercase() }
                Text(
                    when {
                        state?.checking == true -> "Checking ${mode ?: "reasoning"} support for this model and route…"
                        state?.status == ReasoningCapabilityStatus.SUPPORTED && qualified -> "${mode ?: "Reasoning"} support verified. Retry is available for this checked configuration."
                        state?.status == ReasoningCapabilityStatus.UNSUPPORTED -> "${mode ?: "This reasoning mode"} is unsupported by the current model and template."
                        state?.status == ReasoningCapabilityStatus.UNVERIFIED -> "Support could not be verified. The saved reasoning setting has not changed."
                        state?.qualified == true -> "The support check expired. Recheck the exact model and reasoning setting before retrying."
                        else -> "This saved failure has no recorded configuration identity. Recheck the exact model and reasoning setting before retrying."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (qualified && state?.routeVerified == false) Text("The selected reasoning setting is supported; the inference route is not verified as ready.", style = MaterialTheme.typography.bodySmall)
                if (state?.frozenRequest == true) Text("This turn keeps its accepted settings. Changing the profile applies to a new request; saved replies remain.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRecheck, enabled = enabled && state?.checking != true) { Text("Recheck support for $profileName") }
                TextButton(onClick = onSettings, enabled = enabled) { Text("Change settings for $profileName") }
                if (state?.requested != ReasoningMode.AUTO) TextButton(onClick = onSwitchToAuto, enabled = enabled && state?.checking != true) { Text("Switch $profileName to Auto") }
                if (qualified) TextButton(onClick = onRetry, enabled = enabled && state?.checking != true) { Text("Retry checked configuration") }
            }
            ProfileRecoveryKind.BILLING -> {
                Text("Restore this profile’s provider credits, then start a new request. Other replies remain saved.", style = MaterialTheme.typography.bodySmall)
                if (canManageBilling) TextButton(onClick = onBilling, enabled = enabled) { Text("Manage billing for $profileName") }
                TextButton(onClick = onSettings, enabled = enabled) { Text("Change settings for $profileName") }
            }
            ProfileRecoveryKind.SETTINGS -> {
                Text("Change the rejected configuration before starting a new request. Saved replies and tool results remain.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onSettings, enabled = enabled) { Text("Change settings for $profileName") }
            }
            ProfileRecoveryKind.NONE -> Unit
        }
    }
}
