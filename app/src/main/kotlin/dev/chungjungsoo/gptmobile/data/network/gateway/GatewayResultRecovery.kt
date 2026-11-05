package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ErrorDetail
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** A saved result is evidence, never permission to dispatch its tool calls again. */
enum class GatewayResultDisposition { COMPLETE, PARTIAL, FAILED, WAITING, UNAVAILABLE }

/** Only the gateway's durable job envelope can prove that all of its model rounds excluded server tools. */
val GatewayJobResult.hasClientOwnedExecutionProof: Boolean
    get() = job?.let {
        it.jobId == jobId &&
            it.executionIsolationSchema == 1 &&
            it.executionIsolationScope == "entire_job" &&
            it.executionIsolation == "github_client_owned" &&
            it.serverToolsAllowed == false
    } == true

val GatewayJobResult.recoverableContent: String?
    get() = content?.takeIf(String::isNotBlank)
        ?: result?.choices?.firstOrNull()?.effectiveDelta?.content?.takeIf(String::isNotBlank)

val GatewayJobResult.recoveryDisposition: GatewayResultDisposition
    get() = when {
        status.lowercase() in setOf("failed", "cancelled", "canceled", "interrupted") -> GatewayResultDisposition.FAILED
        status.lowercase() in setOf("running", "resuming", "waiting_for_client_tool") -> GatewayResultDisposition.WAITING
        !status.equals("completed", true) || error != null || result?.error != null || recoverableContent == null -> GatewayResultDisposition.UNAVAILABLE
        result?.choices.orEmpty().any { !it.effectiveDelta.toolCalls.isNullOrEmpty() } -> GatewayResultDisposition.UNAVAILABLE
        resultQuality == "partial" || result?.choices.orEmpty().any { it.finishReason == "length" } -> GatewayResultDisposition.PARTIAL
        resultQuality == "complete" -> GatewayResultDisposition.COMPLETE
        else -> GatewayResultDisposition.UNAVAILABLE
    }

fun GatewayJobResult.matchesRequestedModel(model: String): Boolean {
    val observed = listOfNotNull(result?.model, job?.model).filter(String::isNotBlank)
    return observed.isNotEmpty() && observed.all { it == model }
}

/** Python persists fractional Unix seconds. Keep the Android seconds API without discarding the whole receipt. */
internal object GatewayEpochSecondsSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("GatewayEpochSeconds", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long {
        val value = ((decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive)
            ?.takeUnless { it.isString }?.doubleOrNull
            ?: throw SerializationException("Invalid gateway completion timestamp.")
        if (!value.isFinite() || value < 0 || value >= Long.MAX_VALUE.toDouble()) throw SerializationException("Invalid gateway completion timestamp.")
        return value.toLong()
    }
}

internal class GatewayTerminalRecovery(private val api: GatewayAPI) {
    suspend fun read(
        httpStatus: Int,
        failure: JsonObject?,
        config: ProviderRequestConfig,
        model: String,
        responseJobId: String?
    ): ChatCompletionChunk? {
        if (httpStatus != 409) return null
        val rejection = failure ?: return null
        if ((rejection["code"] as? JsonPrimitive)?.contentOrNull != "terminal_job_not_resumable") return null
        val requested = config.extraHeaders.entries.firstOrNull { it.key.equals("X-Gateway-Job-ID", true) }?.value
        val reported = (rejection["job_id"] as? JsonPrimitive)?.contentOrNull
        val bound = requested?.matches(Regex("[A-Za-z0-9_-]{1,128}")) == true && requested == reported && (responseJobId == null || responseJobId == requested)
        val saved = if (bound) api.getJobResult(requireNotNull(requested), config) else null
        val verified = saved?.takeIf { it.jobId == requested && it.matchesRequestedModel(model) }
        val disposition = verified?.recoveryDisposition ?: GatewayResultDisposition.UNAVAILABLE
        val retainedText = verified?.takeIf { it.status.lowercase() in setOf("completed", "failed", "cancelled", "canceled", "interrupted") }?.recoverableContent
        return ChatCompletionChunk(
            model = model,
            gatewayMetadata = requested?.takeIf { bound }?.let {
                GatewayResponseMetadata(jobId = it, singleflightRole = "saved_result", terminalStatus = verified?.status, resultQuality = verified?.resultQuality)
            },
            choices = retainedText?.let { listOf(Choice(delta = Delta(content = it), finishReason = if (disposition == GatewayResultDisposition.COMPLETE) "stop" else null)) },
            error = if (disposition == GatewayResultDisposition.COMPLETE) {
                null
            } else {
                ErrorDetail(
                    message = when (disposition) {
                        GatewayResultDisposition.PARTIAL -> "The gateway saved a partial answer. That text was recovered; the terminal job was not restarted. Resume only its unfinished work."
                        GatewayResultDisposition.FAILED -> "The saved gateway job ended without a complete answer. Its terminal result was read; the request was not replayed."
                        else -> "The terminal gateway job has no verified complete result available on this connection. Reconnect to its saved job; the request was not replayed."
                    },
                    code = "terminal_job_not_resumable",
                    type = "gateway_terminal_result",
                    retryable = false,
                    failureDetails = rejection
                )
            }
        )
    }
}
