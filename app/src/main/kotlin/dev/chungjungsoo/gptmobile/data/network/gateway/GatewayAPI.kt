package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.coroutines.flow.Flow

interface GatewayAPI {
    suspend fun getCapabilities(config: ProviderRequestConfig): GatewayCapabilities?

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
