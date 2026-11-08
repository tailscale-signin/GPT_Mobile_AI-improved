package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class ReasoningCapabilityStatus { SUPPORTED, UNSUPPORTED, UNVERIFIED }

data class ReasoningCapabilityObservation(
    val profileUid: String,
    val model: String,
    val requested: ReasoningMode,
    val status: ReasoningCapabilityStatus,
    /** Opaque process-local identity; never a reusable token or plaintext credential hash. */
    val configurationRevision: String,
    val runtimeRevision: String?,
    val observedAtMillis: Long,
    val safeReasonCode: String?,
    val routeVerified: Boolean,
    val supportVerified: Boolean
) {
    val permitsRequest: Boolean get() = status == ReasoningCapabilityStatus.SUPPORTED && supportVerified
}

internal data class ReasoningCapabilityProbe(
    val routeVerified: Boolean,
    val control: ReasoningTemplateControl? = null,
    val runtimeRevision: String? = null,
    val safeReasonCode: String? = null
)

/** Shared read/template-only preflight. It never loads a model, creates a job or generates text. */
@Singleton
class ReasoningCapabilityRepository internal constructor(
    private val probe: suspend (PlatformV2, Boolean) -> ReasoningCapabilityProbe,
    private val now: () -> Long
) {
    @Inject
    constructor(client: NetworkClient, gateway: GatewayAPI) : this(client, gateway, System::currentTimeMillis)

    internal constructor(client: NetworkClient, gateway: GatewayAPI, now: () -> Long) : this(networkProbe(client, gateway), now)

    private class Entry {
        val mutex = Mutex()
        val flow = MutableStateFlow<ReasoningCapabilityObservation?>(null)
        var identity: String? = null
        var revision = UUID.randomUUID().toString()
    }

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun observe(profileUid: String): StateFlow<ReasoningCapabilityObservation?> = entry(profileUid).flow.asStateFlow()

    suspend fun check(profile: PlatformV2, requested: ReasoningMode, forceRefresh: Boolean = false): ReasoningCapabilityObservation {
        val entry = entry(profile.uid)
        // This key remains private in memory. Only the unrelated random revision is exposed.
        val identity = "${ConfiguredDispatchAuthority.profileRevision(profile)}|$requested|${LocalServiceHealth.networkEpoch}|${GatewayClientDiagnostics.profileCapabilityEpoch(profile.uid, profile.apiUrl)}"
        return entry.mutex.withLock {
            if (entry.identity != identity) {
                entry.identity = identity
                entry.revision = UUID.randomUUID().toString()
                entry.flow.value = null
            }
            entry.flow.value?.takeIf { !forceRefresh && now() - it.observedAtMillis in 0 until FRESH_MILLIS }?.let { return@withLock it }
            val result = if (profile.compatibleType == ClientType.LLAMA) {
                try {
                    withTimeoutOrNull(12_000L) { probe(profile, forceRefresh) }
                        ?: ReasoningCapabilityProbe(false, safeReasonCode = "REASONING_CAPABILITY_TIMEOUT")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ReasoningCapabilityProbe(false, safeReasonCode = "REASONING_CAPABILITY_UNAVAILABLE")
                }
            } else {
                ReasoningCapabilityProbe(false)
            }
            val needsTemplateControl = profile.compatibleType == ClientType.LLAMA && requested != ReasoningMode.AUTO
            val supported = !needsTemplateControl || result.control != null
            val status = when {
                supported -> ReasoningCapabilityStatus.SUPPORTED
                result.routeVerified -> ReasoningCapabilityStatus.UNSUPPORTED
                else -> ReasoningCapabilityStatus.UNVERIFIED
            }
            ReasoningCapabilityObservation(
                profile.uid,
                profile.model,
                requested,
                status,
                entry.revision,
                result.runtimeRevision,
                now(),
                when {
                    supported -> result.safeReasonCode
                    result.routeVerified -> "REASONING_${requested}_UNSUPPORTED"
                    else -> result.safeReasonCode ?: "REASONING_CAPABILITY_UNVERIFIED"
                },
                result.routeVerified,
                supported
            ).also { entry.flow.value = it }
        }
    }

    private fun entry(uid: String): Entry = entries.getOrPut(uid) { Entry() }

    private class NetworkProbe(client: NetworkClient, private val gateway: GatewayAPI) {
        private val capacity = LlamaCapacityRepository(client)

        suspend fun inspect(profile: PlatformV2): ReasoningCapabilityProbe {
            val config = ProviderRequestConfig(
                apiUrl = profile.apiUrl,
                token = profile.token,
                profileUid = profile.uid,
                providerType = profile.compatibleType.name,
                requestRole = "reasoning-capability-check",
                lockedModel = profile.model,
                lockedApiUrl = profile.apiUrl
            )
            // The outer service owns the twenty-second freshness window. Never renew
            // that observation from the capacity repository's longer metadata cache.
            capacity.invalidate()
            // A definite unsupported gateway surface permits the direct llama.cpp metadata path.
            // An access/transport failure does not qualify either route.
            gateway.getCapabilities(config, forceRefresh = true)?.let { GatewayClientDiagnostics.observeInstance(config, it.instanceId) }
            val snapshot = capacity.discover(config, profile.model)
                ?: return ReasoningCapabilityProbe(false, safeReasonCode = "REASONING_CAPABILITY_UNVERIFIED")
            val verified = snapshot.provenance == CapacityProvenance.VERIFIED_ROUTE &&
                snapshot.fingerprint.requestedModel == profile.model &&
                snapshot.fingerprint.resolvedModel == profile.model &&
                capacity.hasExactModelProof(config, profile.model, snapshot)
            val control = if (verified) capacity.reasoningControl(config, profile.model, snapshot) else null
            val revision = researchHash("${snapshot.fingerprint.runtimeRevision}|${snapshot.fingerprint.templateRevision}|${snapshot.fingerprint.allocationRevision}")
            return ReasoningCapabilityProbe(verified, control, revision)
        }
    }

    private companion object {
        const val FRESH_MILLIS = 20_000L

        fun networkProbe(client: NetworkClient, gateway: GatewayAPI): suspend (PlatformV2, Boolean) -> ReasoningCapabilityProbe {
            val network = NetworkProbe(client, gateway)
            return { profile, _ -> network.inspect(profile) }
        }
    }
}
