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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class GatewayModelPreparationPhase { CHECKING, LOADING, READY, BLOCKED }

data class GatewayModelPreparationState(
    val profileUid: String,
    val model: String,
    val phase: GatewayModelPreparationPhase,
    val message: String,
    val observation: ReasoningCapabilityObservation? = null,
    /** A definitive rejected load must not become a new operation on a background poll. */
    val retryRequiresUserAction: Boolean = false,
    val failureCode: String? = null,
    val operationId: String? = null,
    val transportCode: String? = null
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
    private val maxPolls: Int = 30,
    private val operations: GatewayModelPreparationStore? = null
) {
    @Inject
    constructor(gateway: GatewayAPI, readiness: ReasoningCapabilityRepository, preparation: GatewayModelPreparationAPI, operations: GatewayModelPreparationStore) : this(
        discover = { gateway.getCapabilities(configuration(it), forceRefresh = true) },
        check = { profile, mode -> readiness.check(profile, mode, forceRefresh = true) },
        load = { profile, operationId -> preparation.prepare(configuration(profile), profile.model, operationId) },
        now = System::currentTimeMillis,
        operations = operations
    )

    private class Entry {
        val mutex = Mutex()
        var users = 0
        var state: GatewayModelPreparationState? = null
        var checkedAt = Long.MIN_VALUE
        var operationId: String? = null
        var restored = false

        // Set before dispatch; a cancellation/lost response cannot reset this to safe-to-repeat.
        var unresolved = false
    }

    private val entries = linkedMapOf<String, Entry>()

    suspend fun prepareSelected(
        profile: PlatformV2,
        mode: ReasoningMode,
        forceRefresh: Boolean = false,
        onState: (GatewayModelPreparationState?) -> Unit = {}
    ): GatewayModelPreparationState? = prepareSelectedWhile(profile, mode, forceRefresh, {}, onState = onState)

    /** A durable Send can lose ownership while metadata or a load request is suspended. */
    internal suspend fun prepareSelectedWhile(
        profile: PlatformV2,
        mode: ReasoningMode,
        forceRefresh: Boolean,
        validateSelection: suspend () -> Unit,
        allowLoad: Boolean = true,
        onRejected: suspend () -> Unit = {},
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
        var retryRequiresUserAction = false
        var failureCode: String? = null
        var transportCode: String? = null
        var observedOperationId: String? = null
        fun publish(phase: GatewayModelPreparationPhase, message: String, observed: ReasoningCapabilityObservation? = null): GatewayModelPreparationState =
            GatewayModelPreparationState(
                profile.uid, profile.model, phase, message, observed, retryRequiresUserAction,
                failureCode = if (phase == GatewayModelPreparationPhase.BLOCKED) failureCode ?: "MODEL_PREPARATION_INCOMPLETE" else null,
                operationId = entry.operationId ?: observedOperationId,
                transportCode = transportCode.takeUnless { phase == GatewayModelPreparationPhase.READY }
            ).also {
                entry.state = it
                entry.checkedAt = now()
                onState(it)
            }
        try {
            return entry.mutex.withLock {
                suspend fun validate() {
                    currentCoroutineContext().ensureActive()
                    currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
                    validateSelection()
                }
                suspend fun observe(): ReasoningCapabilityObservation {
                    validate()
                    return check(profile, mode).also { validate() }
                }
                validate()
                entry.state?.takeIf { !forceRefresh && !it.busy && it.observation?.requested == mode && now() - entry.checkedAt in 0 until 20_000L }?.let { return@withLock it.also(onState) }
                publish(GatewayModelPreparationPhase.CHECKING, "Checking the selected model on the gateway…")
                var observation: ReasoningCapabilityObservation? = null
                try {
                    if (!entry.restored) {
                        val saved = operations?.read(identity, profile.model)
                        entry.operationId = saved?.operationId
                        observedOperationId = saved?.operationId
                        entry.unresolved = saved != null
                        entry.restored = true
                    }
                    suspend fun resolved() {
                        // Clear durable state before admitting a later load. A failed write
                        // retains the old identity and cannot authorize another dispatch.
                        if (entry.operationId != null) operations?.clear(identity)
                        entry.unresolved = false
                        entry.operationId = null
                    }
                    suspend fun rejected() = withContext(NonCancellable) {
                        // Persist the owner stop before clearing the load identity.
                        // Cancellation at publication cannot authorize a new load.
                        onRejected()
                        resolved()
                        retryRequiresUserAction = true
                        failureCode = "MODEL_PREPARATION_REJECTED"
                    }
                    suspend fun persist(receiptState: String? = null) {
                        operations?.write(SavedGatewayModelPreparation(configurationRevision = identity, model = profile.model, operationId = requireNotNull(entry.operationId), updatedAtMillis = now().coerceAtLeast(0), receiptState = receiptState))
                    }
                    withTimeoutOrNull(120_000L) {
                        validate()
                        val capabilities = discover(profile) ?: return@withTimeoutOrNull null.also {
                            entry.state = null
                            onState(null)
                        }
                        validate()
                        repeat(2) preparation@{ preparationPass ->
                            observation = observe()
                            if (observation?.routeVerified == true) {
                                resolved()
                                return@withTimeoutOrNull publishReady(profile, observation!!, ::publish)
                            }
                            if (!allowLoad) return@withTimeoutOrNull publish(GatewayModelPreparationPhase.BLOCKED, observation?.detailMessage ?: "This exact model is not ready yet. The saved message and existing load identity are kept.", observation)
                            if (observation?.safeReasonCode !in setOf("SELECTED_MODEL_NOT_LOADED", "SELECTED_MODEL_LOADING") && !entry.unresolved) {
                                return@withTimeoutOrNull publish(GatewayModelPreparationPhase.BLOCKED, observation?.detailMessage ?: "The gateway could not verify this exact model. Check the profile and connection, then recheck.", observation)
                            }
                            val reconcileOperation = entry.unresolved && entry.operationId != null && capabilities.supportsFeature("model_preparation_v1")
                            if (observation?.safeReasonCode == "SELECTED_MODEL_NOT_LOADED" && !entry.unresolved || reconcileOperation) {
                                if (!capabilities.supportsFeature("model_preparation_v1")) {
                                    return@withTimeoutOrNull publish(GatewayModelPreparationPhase.BLOCKED, "This gateway needs an update to load selected models from the app. Until then, run prepare-model --model with this exact model ID on the gateway computer, then recheck.", observation)
                                }
                                val previouslyUnresolved = entry.unresolved
                                publish(GatewayModelPreparationPhase.LOADING, if (reconcileOperation) "Rechecking the existing load for ${profile.model}…" else "Loading ${profile.model} on the gateway…", observation)
                                currentCoroutineContext().ensureActive()
                                if (!reconcileOperation) entry.operationId = UUID.randomUUID().toString()
                                observedOperationId = entry.operationId
                                persist()
                                entry.unresolved = true
                                currentCoroutineContext().ensureActive()
                                validate()
                                val receipt = try {
                                    load(profile, requireNotNull(entry.operationId))
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: GatewayModelPreparationRejectedException) {
                                    failureCode = "MODEL_PREPARATION_REJECTED"
                                    // A later rejected status request cannot erase the earlier dispatch.
                                    if (!previouslyUnresolved) {
                                        rejected()
                                    }
                                    return@withTimeoutOrNull publish(
                                        GatewayModelPreparationPhase.BLOCKED,
                                        if (previouslyUnresolved) "The gateway could not recheck the existing load. Its earlier outcome is still unknown; check Gateway access, then recheck." else "The gateway rejected the model load before dispatch. Check this profile’s Gateway access and version, then recheck.",
                                        observation
                                    )
                                } catch (failure: Exception) {
                                    // Reconcile by GET below. A failed transport does not prove that the load failed.
                                    // Retain its bounded cause and original identity if readiness remains unconfirmed.
                                    transportCode = dev.chungjungsoo.gptmobile.data.network.networkFailureKind(failure).code
                                    null
                                }
                                if (receipt != null) persist(receipt.state)
                                validate()
                                if (receipt?.modelState == "LOADED") {
                                    resolved()
                                    observation = observe()
                                    // A retained completed receipt proves that the old load ended.
                                    // Only a fresh sleeping observation can authorize one new wake.
                                    if (previouslyUnresolved && preparationPass == 0 && observation?.safeReasonCode == "SELECTED_MODEL_NOT_LOADED") {
                                        return@preparation
                                    }
                                    return@withTimeoutOrNull if (observation?.routeVerified == true) {
                                        publishReady(profile, observation!!, ::publish)
                                    } else {
                                        publish(GatewayModelPreparationPhase.BLOCKED, observation?.detailMessage ?: "The model is loaded, but the gateway could not qualify its model metadata. Check the backend configuration, then recheck.", observation)
                                    }
                                }
                                if (receipt?.modelState != "LOADING" && (receipt?.state in setOf("PRECHECK_REJECTED", "LOAD_REJECTED") || receipt?.loadAccepted == false)) {
                                    rejected()
                                    return@withTimeoutOrNull publish(
                                        GatewayModelPreparationPhase.BLOCKED,
                                        if (receipt?.reasonCode == "MODEL_PREPARATION_UNSUPPORTED") "This gateway’s backend does not support loading models from the app. Load this exact model with the backend’s host controls, then recheck." else "The gateway could not start loading this exact model. Check the backend model and access settings, then recheck.",
                                        observation
                                    )
                                }
                                if (receipt?.loadAccepted == false) resolved()
                            }
                            publish(GatewayModelPreparationPhase.LOADING, "Waiting for ${profile.model} to finish loading on the gateway…", observation)
                            repeat(maxPolls) {
                                pause()
                                observation = observe()
                                if (observation?.routeVerified == true) {
                                    resolved()
                                    return@withTimeoutOrNull publishReady(profile, observation!!, ::publish)
                                }
                                if (observation?.safeReasonCode in DEFINITE_BLOCKERS) {
                                    return@withTimeoutOrNull publish(GatewayModelPreparationPhase.BLOCKED, observation?.detailMessage ?: "The selected model needs its configuration or access repaired. The existing load identity and your draft are kept.", observation)
                                }
                            }
                            return@withTimeoutOrNull publish(GatewayModelPreparationPhase.BLOCKED, "The gateway has not confirmed this model ready yet. Recheck to continue checking the existing load; your draft is kept.", observation)
                        }
                        publish(GatewayModelPreparationPhase.BLOCKED, "The model changed state again during preparation. Recheck this exact model; your draft is kept.", observation)
                    } ?: if (entry.state == null) null else publish(GatewayModelPreparationPhase.BLOCKED, "Model preparation is taking longer than expected. Recheck the existing load; your draft is kept.", observation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: GatewayModelPreparationStorageException) {
                    failureCode = "MODEL_PREPARATION_STORAGE_UNAVAILABLE"
                    publish(GatewayModelPreparationPhase.BLOCKED, "The model load could not be saved or restored. Check device storage and recheck; your draft and any existing gateway operation are kept.", observation)
                } catch (failure: Exception) {
                    failureCode = "MODEL_PREPARATION_CONNECTION_FAILED"
                    transportCode = dev.chungjungsoo.gptmobile.data.network.networkFailureKind(failure).code
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
        val DEFINITE_BLOCKERS = setOf("SELECTED_MODEL_NOT_AVAILABLE", "BACKEND_METADATA_DENIED", "BACKEND_CONFIGURATION_INVALID", "GATEWAY_REMOTE_SURFACE_NOT_ADMITTED", "EXECUTION_MODEL_IDENTITY_INVALID")

        fun configuration(profile: PlatformV2) = ProviderRequestConfig(
            profile.apiUrl,
            profile.token,
            profileUid = profile.uid,
            profileRevision = ConfiguredDispatchAuthority.profileRevision(profile),
            providerType = profile.compatibleType.name,
            requestRole = "selected-model-preparation",
            lockedModel = profile.model,
            lockedApiUrl = profile.apiUrl
        )
    }
}
