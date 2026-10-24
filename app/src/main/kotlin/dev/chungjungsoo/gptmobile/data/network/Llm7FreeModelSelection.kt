package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.free.CatalogHttpFailure
import dev.chungjungsoo.gptmobile.data.free.FreeCatalogState
import dev.chungjungsoo.gptmobile.data.free.FreeGenerationReadiness
import dev.chungjungsoo.gptmobile.data.free.FreeModelCatalog
import dev.chungjungsoo.gptmobile.data.free.FreeModelOption
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json

/** A client-side automatic choice. Never send an alias that could select a paid route. */
internal object Llm7FreeModelSelection {
    fun select(requested: String, catalog: List<FreeModelOption>): FreeModelOption {
        val eligible = catalog.filter { it.freeAllowance && it.id != FreeModelCatalog.AUTOMATIC }
        if (requested == FreeModelCatalog.AUTOMATIC) {
            return eligible.sortedWith(compareByDescending<FreeModelOption> { it.streaming }.thenBy { it.id }).firstOrNull()
                ?: throw FreeProviderException(FreeFailureCategory.PROVIDER_UNAVAILABLE, "LLM7 currently lists no chat models eligible for its free allowance. No paid model was selected.")
        }
        return eligible.firstOrNull { it.id == requested }
            ?: throw FreeProviderException(FreeFailureCategory.REQUEST_INVALID, "This LLM7 model is not currently eligible for the free allowance. Select Automatic or refresh the free models in this profile’s settings.")
    }

    suspend fun resolve(client: HttpClient, config: ProviderRequestConfig, requested: String): FreeModelOption {
        val provider = FreeAiProvider.LLM7
        FreeProviderPreflight.require(config, requested)
        config.freeDiagnostics?.stage = "catalog"
        // Recheck live eligibility for every generation. Disk cache is display data only;
        // changes to a model's tier must never authorize a paid fallback.
        val refreshed = FreeCatalogState.refresh(provider, force = true, token = config.token) {
            client.prepareGet(FreeCatalogState.catalogUrl(provider)) {
                accept(ContentType.Application.Json)
                config.token?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
                timeout { requestTimeoutMillis = 8000 }
            }.execute { response ->
                if (!response.status.isSuccess()) throw CatalogHttpFailure(response.status.value)
                val bytes = ByteArray(2_000_001)
                val channel = response.bodyAsChannel()
                var count = 0
                while (count < bytes.size) {
                    val read = channel.readAvailable(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                check(count <= 2_000_000) { "LLM7 model catalog exceeds the supported size" }
                Json.parseToJsonElement(bytes.decodeToString(0, count))
            }
        }
        if (!refreshed.succeeded) {
            val category = if (refreshed.readiness == FreeGenerationReadiness.ACCESS_DENIED) {
                if (config.token.isNullOrBlank()) FreeFailureCategory.AUTHENTICATION_REQUIRED else FreeFailureCategory.AUTHENTICATION_REJECTED
            } else {
                FreeFailureCategory.PROVIDER_UNAVAILABLE
            }
            throw FreeProviderException(category, refreshed.message.ifBlank { "LLM7 free model catalog is unavailable. No generation request was sent." })
        }
        return select(requested, refreshed.models)
    }
}
