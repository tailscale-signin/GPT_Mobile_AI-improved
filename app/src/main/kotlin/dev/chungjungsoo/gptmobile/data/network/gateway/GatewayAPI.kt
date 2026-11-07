package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.coroutines.flow.Flow

interface GatewayAPI {
    /** Explicit probes may refresh a remembered unsupported route; ordinary requests reuse it. */
    suspend fun getCapabilities(config: ProviderRequestConfig, forceRefresh: Boolean = false): GatewayCapabilities?

    fun resumeEvents(
        jobId: String,
        afterSequence: Int,
        timeoutSeconds: Int,
        config: ProviderRequestConfig,
        expectedGeneration: String? = null
    ): Flow<GatewayProgress>

    suspend fun getJobResult(
        jobId: String,
        config: ProviderRequestConfig
    ): GatewayJobResult?

    suspend fun cancelJob(
        jobId: String,
        config: ProviderRequestConfig
    ): GatewayCancelResult?
}
