package dev.chungjungsoo.gptmobile.data.queue

import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationPhase
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationState
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Loading belongs to an accepted, undispatched queue lease, never a passive readiness probe. */
internal object GatewayQueueModelAdmission {
    fun mayPrepare(observation: GatewayAvailability?, now: Long): Boolean = observation != null &&
        observation.conversationSyncSupported &&
        observation.freshnessRemainingMillis(now) > 0 &&
        (
            observation.isOnline ||
                observation.status in setOf(GatewayAvailabilityStatus.MODEL_LOADING, GatewayAvailabilityStatus.MODEL_UNAVAILABLE, GatewayAvailabilityStatus.NOT_READY) &&
                observation.failureCode in setOf("SELECTED_MODEL_NOT_LOADED", "SELECTED_MODEL_LOADING")
            )

    suspend fun prepareAndStart(
        repository: GatewayModelPreparationRepository,
        profile: PlatformV2,
        mode: ReasoningMode,
        observation: GatewayAvailability?,
        now: () -> Long,
        validate: suspend () -> Unit,
        onState: (GatewayModelPreparationState?) -> Unit,
        start: () -> Unit
    ): Boolean {
        if (!mayPrepare(observation, now()) || observation?.gatewayProfileUid != profile.uid) return false
        val inherited = currentCoroutineContext()[RequestAuthorizationGuard]
        val guard = RequestAuthorizationGuard("gateway-queued-model:${profile.uid}") {
            inherited?.validate?.invoke()
            validate()
        }
        return withContext(guard) {
            guard.validate()
            val prepared = repository.prepareSelected(profile, mode, forceRefresh = true, onState = onState)
            guard.validate()
            val modelReady = if (profile.compatibleType == ClientType.LLAMA) {
                prepared?.phase == GatewayModelPreparationPhase.READY &&
                    prepared.profileUid == profile.uid &&
                    prepared.model == profile.model &&
                    prepared.observation?.requested == mode &&
                    prepared.observation.permitsRequest
            } else {
                true
            }
            // Preparation proves the model, not capacity or conversation admission.
            // A formerly sleeping route waits for its next fresh online observation.
            if (!modelReady || observation?.isOnline != true || observation.freshnessRemainingMillis(now()) == 0L) return@withContext false
            start()
            true
        }
    }
}
