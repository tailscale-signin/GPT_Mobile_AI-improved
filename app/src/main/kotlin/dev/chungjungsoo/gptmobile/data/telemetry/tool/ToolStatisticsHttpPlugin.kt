package dev.chungjungsoo.gptmobile.data.telemetry.tool

import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.util.AttributeKey
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext

/** Counts actual Ktor sends, including redirects/retries; never infers gateway-internal retries. */
val ToolStatisticsHttpPlugin = createClientPlugin("ToolStatisticsHttp") {
    val ownerKey = AttributeKey<ToolStatisticsContext>("tool.statistics.owner")
    val attemptKey = AttributeKey<String>("tool.statistics.attempt")
    onRequest { request, _ ->
        currentCoroutineContext()[ToolStatisticsContext]?.let { request.attributes.put(ownerKey, it) }
    }
    on(SendingRequest) { request, _ ->
        val owner = request.attributes.getOrNull(ownerKey)
        if (owner != null) {
            val id = UUID.randomUUID().toString()
            request.attributes.put(attemptKey, id)
            owner.repository.attempt(owner.invocationId, id, ToolOutcome.IN_PROGRESS, "HTTP")
        }
    }
    onResponse { response ->
        val owner = response.call.attributes.getOrNull(ownerKey)
        val id = response.call.attributes.getOrNull(attemptKey)
        if (owner != null && id != null) {
            owner.repository.attempt(owner.invocationId, id, if (response.status.value < 400) ToolOutcome.SUCCESS else ToolOutcome.FAILURE, "HTTP")
        }
    }
}
