package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilities
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationAPI
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationReceipt
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationRejectedException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class GatewayModelPreparationPhase { CHECKING, LOADING, READY, BLOCKED }

data class GatewayModelPreparationState(
    val profileUid: String,
    val model: String,
    val phase: GatewayModelPreparationPhase,
    val message: String,
    val observation: ReasoningCapabilityObservation? = null
) {
    val busy: Boolean get() = phase in setOf(GatewayModelPreparationPhase.CHECKING, GatewayModelPreparationPhase.LOADING)
}

/** Only selected profiles enter this service. Passive readiness checks remain read-only. */
@Singleton
class GatewayModelPreparationRepository internal constructor(
    private val discover: suspend (PlatformV2) -> GatewayCapabilities?,
    private val check: suspend (PlatformV2, ReasoningMode) -> ReasoningCapabilityObservation,
    private val load: suspend (PlatformV2, String) -> GatewayModelPreparationReceipt,
    private val now: () -> Long,
    private val pause: suspend () -> Unit = { delay(2_000L) },
    private val maxPolls: Int = 30
) {
    @Inject
    constructor(gateway: GatewayAPI, readiness: ReasoningCapabilityRepository, preparation: GatewayModelPreparationAPI) : this(
        discover = { gateway.getCapabilities(configuration(it), forceRefresh = true) },
        check = { profile, mode -> readiness.check(profile, mode, forceRefresh = true) },
        load = { profile, operationId -> preparation.prepare(configuration(profile), profile.model, operationId) },
        now = System::currentTimeMillis
    )

    private class Entry {
        val mutex = Mutex()
        var users = 0
        var state: GatewayModelPreparationState? = null
        var checkedAt = Long.MIN_VALUE
        var operationId: String? = null

        // Set before dispatch; a cancellation/lost response cannot reset this to safe-to-repeat.
        var unresolved = false
    }

    private val entries = linkedMapOf<String, Entry>()

    suspend fun prepareSelected(
        profile: PlatformV2,
        mode: ReasoningMode,
        forceRefresh: Boolean = false,
        onState: (GatewayModelPreparationState?) -> Unit = {}
    ): GatewayModelPreparationState? {
        if (!profile.enabled || profile.compatibleType != ClientType.LLAMA || profile.model.isBlank()) return null.also(onState)
        val identity = ConfiguredDispatchAuthority.profileRevision(profile)
        val entry = synchronized(entries) {
            (
                entries[identity] ?: run {
                    if (entries.size >= 128) entries.entries.firstOrNull { it.value.users == 0 && !it.value.unresolved }?.let { entries.remove(it.key) }
                    if (entries.size >= 128) null else Entry().also { entries[identity] = it }
                }
                )?.also { it.users++ }
        } ?: return GatewayModelPreparationState(profile.uid, profile.model, GatewayModelPreparationPhase.BLOCKED, "Too many unresolved model loads. Recheck an existing selection before starting another.").also(onState)
        fun publish(phase: GatewayModelPreparationPhase, message: String, observed: ReasoningCapabilityObservation? = null): GatewayModelPreparationState =
            GatewayModelPreparationState(profile.uid, profile.model, phase, message, observed).also {
                entry.state = it
                entry.checkedAt = now()
                onState(it)
            }
        try {
            return entry.mutex.withLock {
                entry.state?.takeIf { !forceRefresh && !it.busy && it.observation?.requested == mode && now() - entry.checkedAt in 0 until 20_000L }?.let { return@withLock it.also(onState) }
                publish(GatewayModelPreparationPhase.CHECKING, "Checking the selected model on the gateway…")
                var observation: ReasoningCapabilityObservation? = null
                try {
                    withTimeoutOrNull(120_000L) {
                        val capabilities = discover(profile) ?: return@withTimeoutOrNull null.also {
                            entry.state = null
                            onState(null)
                        }
                        observation = check(profile, mode)
                        if (observation?.routeVerified == true) {
                            entry.unresolved = false
                            return@withTimeoutOrNull publishReady(profile, observation!!, ::publish)
                        }
                        if (observation?.safeReasonCode !in setOf("SELECTED_MODEL_NOT_LOADED", "SELECTED_MODEL_LOADING") && !entry.unresolved) {
                            return@withTimeoutOrNull publish(GatewayModelPreparationPhase.BLOCKED, observation?.detailMessage ?: "The gateway could not verify this exact model. Check the profile and connection, then recheck.", observation)
                        }
                        val reconcileOperation = forceRefresh && entry.unresolved && entry.operationId != null && capabilities.supportsFeature("model_preparation_v1")
                        if (observation?.safeReasonCode == "SELECTED_MODEL_NOT_LOADED" && !entry.unresolved || reconcileOperation) {
                            if (!capabilities.supportsFeature("model_preparation_v1")) {
                                return@withTimeoutOrNull publish(GatewayModelPreparationPhase.BLOCKED, "This gateway needs an update to load selected models from the app. Until then, run prepare-model --model with this exact model ID on the gateway computer, then recheck.", observation)
                            }
                            val previouslyUnresolved = entry.unresolved
                            publish(GatewayModelPreparationPhase.LOADING, if (reconcileOperation) "Rechecking the existing load for ${profile.model}…" else "Loading ${profile.model} on the gateway…", observation)
                            currentCoroutineContext().ensureActive()
                            if (!reconcileOperation) entry.operationId = UUID.randomUUID().toString()
                            entry.unresolved = true
                            val receipt = try {
                                load(profile, requireNotNull(entry.operationId))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: GatewayModelPreparationRejectedException) {
                                // A later rejected status request cannot erase the earlier dispatch.
                                entry.unresolved = previouslyUnresolved
                                return@withTimeoutOrNull publish(
                                    GatewayModelPreparationPhase.BLOCKED,
                                    if (previouslyUnresolved) "The gateway could not recheck the existing load. Its earlier outcome is still unknown; check Gateway access, then recheck." else "The gateway rejected the model load before dispatch. Check this profile’s Gateway access and version, then recheck.",
                                    observation
                                )
                            } catch (_: Exception) {
                                // Reconcile by GET below. A failed transport does not prove that the load failed.
                                null
                            }
                            if (receipt?.loadAccepted == false) entry.unresolved = false
                            if (receipt?.modelState == "LOADED") {
                                entry.unresolved = false
                                observation = check(profile, mode)
                                return@withTimeoutOrNull if (observation?.routeVerified == true) {
                                    publishReady(profile, observation!!, ::publish)
                                } else {
                                    publish(GatewayModelPreparationPhase.BLOCKED, observation?.detailMessage ?: "The model is loaded, but the gateway could not qualify its model metadata. Check the backend configuration, then recheck.", observation)
                                }
                            }
                            if (receipt?.modelState != "LOADING" && (receipt?.state in setOf("PRECHECK_REJECTED", "LOAD_REJECTED") || receipt?.loadAccepted == false)) {
                                entry.unresolved = false
                                return@withTimeoutOrNull publish(
                                    GatewayModelPreparationPhase.BLOCKED,
                                    if (receipt?.reasonCode == "MODEL_PREPARATION_UNSUPPORTED") "This gateway’s backend does not support loading models from the app. Load this exact model with the backend’s host controls, then recheck." else "The gateway could not start loading this exact model. Check the backend model and access settings, then recheck.",
                                    observation
                                )
                            }
                        }
                        publish(GatewayModelPreparationPhase.LOADING, "Waiting for ${profile.model} to finish loading on the gateway…", observation)
                        repeat(maxPolls) {
                            pause()
                            observation = check(profile, mode)
                            if (observation?.routeVerified == true) {
                                entry.unresolved = false
                                return@withTimeoutOrNull publishReady(profile, observation!!, ::publish)
                            }
                            if (observation?.safeReasonCode == "SELECTED_MODEL_NOT_AVAILABLE") {
                                return@withTimeoutOrNull publish(GatewayModelPreparationPhase.BLOCKED, observation?.detailMessage ?: "This exact model is no longer advertised by the gateway.", observation)
                            }
                        }
                        publish(GatewayModelPreparationPhase.BLOCKED, "The gateway has not confirmed this model ready yet. Recheck to continue checking the existing load; your draft is kept.", observation)
                    } ?: if (entry.state == null) null else publish(GatewayModelPreparationPhase.BLOCKED, "Model preparation is taking longer than expected. Recheck the existing load; your draft is kept.", observation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    publish(GatewayModelPreparationPhase.BLOCKED, "The gateway connection could not be checked. Reconnect and recheck this exact model; your draft is kept.", observation)
                }
            }
        } finally {
            synchronized(entries) { entry.users-- }
        }
    }

    private fun publishReady(
        profile: PlatformV2,
        observation: ReasoningCapabilityObservation,
        publish: (GatewayModelPreparationPhase, String, ReasoningCapabilityObservation?) -> GatewayModelPreparationState
    ): GatewayModelPreparationState = publish(
        GatewayModelPreparationPhase.READY,
        if (observation.permitsRequest) "${profile.model} was loaded and ready at the last check." else "${profile.model} is loaded. The profile’s reasoning setting still needs attention.",
        observation
    )

    private companion object {
        fun configuration(profile: PlatformV2) = ProviderRequestConfig(
            profile.apiUrl,
            profile.token,
            profileUid = profile.uid,
            providerType = profile.compatibleType.name,
            requestRole = "selected-model-preparation",
            lockedModel = profile.model,
            lockedApiUrl = profile.apiUrl
        )
    }
}
