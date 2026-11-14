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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** A saved result is evidence, never permission to dispatch its tool calls again. */
enum class GatewayResultDisposition { COMPLETE, PARTIAL, FAILED, WAITING, UNAVAILABLE }

/** Restore only bounded optional attempt metadata; identity is checked by the saved-job caller. */
internal fun decodeGatewayJobResult(json: Json, body: String): GatewayJobResult {
    val envelope = json.parseToJsonElement(body) as? JsonObject ?: throw SerializationException("Expected a saved gateway result object")
    val saved = json.decodeFromJsonElement(GatewayJobResult.serializer(), envelope)
    val result = envelope["result"] as? JsonObject
    return saved.copy(
        result = saved.result?.let { chunk ->
            chunk.copy(
                gatewayRecovery = dev.chungjungsoo.gptmobile.data.accounting.GatewayRecoveryEvidence.fromRecovery(result?.get("gateway_recovery") as? JsonObject),
                error = chunk.error?.let { error ->
                    error.copy(failureDetails = result?.let { dev.chungjungsoo.gptmobile.data.network.structuredProviderFailure(it.toString()) })
                }
            )
        }
    )
}

private val savedGatewayControlTag = Regex("<(/?)(think|analysis|reasoning|tool_call)(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)

/** Older gateways retain inline control blocks in their raw result. Project only the answer, preserving its layout. */
private fun String.visibleSavedGatewayAnswer(): String? {
    val hiddenBlocks = ArrayDeque<String>()
    val visible = StringBuilder(length)
    var cursor = 0
    for (tag in savedGatewayControlTag.findAll(this)) {
        if (hiddenBlocks.isEmpty()) visible.append(this, cursor, tag.range.first)
        val name = tag.groupValues[2].lowercase()
        if (tag.groupValues[1].isEmpty()) {
            hiddenBlocks.addLast(name)
        } else if (hiddenBlocks.lastOrNull() == name) {
            hiddenBlocks.removeLast()
        }
        cursor = tag.range.last + 1
    }
    // Unclosed or mismatched control blocks remain hidden. The raw receipt is
    // unchanged and can still explain why no complete answer was recoverable.
    if (hiddenBlocks.isEmpty()) visible.append(this, cursor, length)
    return visible.toString().takeIf(String::isNotBlank)
}

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
    get() = (
        content?.takeIf(String::isNotBlank)
            ?: result?.choices?.firstOrNull()?.effectiveDelta?.content?.takeIf(String::isNotBlank)
        )
        ?.visibleSavedGatewayAnswer()

val GatewayJobResult.recoveryDisposition: GatewayResultDisposition
    get() = when {
        // Additive terminal receipts must agree with the retained status. They
        // never authorize a model/tool replay, including malformed handoffs.
        terminal == false && status.lowercase() in setOf("completed", "failed", "cancelled", "canceled", "interrupted") -> GatewayResultDisposition.UNAVAILABLE
        terminal == true && status.lowercase() in setOf("queued", "pending", "running", "resuming", "waiting_for_client_tool") -> GatewayResultDisposition.UNAVAILABLE
        continuationAllowed == true && !status.equals("waiting_for_client_tool", true) -> GatewayResultDisposition.UNAVAILABLE
        status.lowercase() in setOf("failed", "cancelled", "canceled", "interrupted") -> GatewayResultDisposition.FAILED
        status.lowercase() in setOf("queued", "pending", "running", "resuming", "waiting_for_client_tool") -> GatewayResultDisposition.WAITING
        resultAvailable == false -> GatewayResultDisposition.UNAVAILABLE
        !status.equals("completed", true) || error != null || structuredError != null || result?.error != null || recoverableContent == null -> GatewayResultDisposition.UNAVAILABLE
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
        if (!(rejection["code"] as? JsonPrimitive)?.contentOrNull.equals("terminal_job_not_resumable", true)) return null
        val requested = config.extraHeaders.entries.firstOrNull { it.key.equals("X-Gateway-Job-ID", true) }?.value
        val reported = (rejection["job_id"] as? JsonPrimitive)?.contentOrNull
        val bound = requested?.matches(Regex("[A-Za-z0-9_-]{1,128}")) == true && requested == reported && (responseJobId == null || responseJobId == requested)
        val saved = if (bound) {
            try {
                api.getJobResult(requireNotNull(requested), config)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val verified = saved?.takeIf { it.jobId == requested && (it.job?.jobId == null || it.job.jobId == requested) && it.matchesRequestedModel(model) }
        val disposition = verified?.recoveryDisposition ?: GatewayResultDisposition.UNAVAILABLE
        val retainedText = verified?.takeIf { it.status.lowercase() in setOf("completed", "failed", "cancelled", "canceled", "interrupted") }?.recoverableContent
        val original = verified?.savedFailure()
        return ChatCompletionChunk(
            model = model,
            gatewayRecovery = verified?.result?.gatewayRecovery,
            gatewayMetadata = requested?.takeIf { bound }?.let {
                GatewayResponseMetadata(jobId = it, singleflightRole = "saved_result", terminalStatus = verified?.status, resultQuality = verified?.resultQuality, transportVerified = verified != null)
            },
            choices = retainedText?.let { listOf(Choice(delta = Delta(content = it), finishReason = if (disposition == GatewayResultDisposition.COMPLETE) "stop" else null)) },
            error = if (disposition == GatewayResultDisposition.COMPLETE) {
                null
            } else {
                ErrorDetail(
                    message = original?.message ?: when {
                        !bound -> "The gateway rejected continuation, but this response did not identify a verified saved job. Check the connection and saved task history; no request or tool action was replayed."
                        disposition == GatewayResultDisposition.PARTIAL -> "The gateway saved a partial answer. That text was recovered; the terminal job was not restarted. Resume only its unfinished work."
                        disposition == GatewayResultDisposition.FAILED -> "The saved gateway job ended without a complete answer. Its terminal result was read; the request was not replayed."
                        else -> "The gateway job $requested could not be reconciled with a verified complete result on this connection. Reconnect and check that job's saved result; the request was not replayed."
                    },
                    code = original?.code ?: "TERMINAL_JOB_NOT_RESUMABLE",
                    type = original?.type ?: "gateway_terminal_result",
                    retryable = false,
                    failureDetails = JsonObject(
                        (original?.failureDetails ?: rejection) + mapOf(
                            "recovery_identity_verified" to JsonPrimitive(verified != null),
                            "retryable" to JsonPrimitive(false),
                            "recovery_code" to JsonPrimitive("TERMINAL_JOB_NOT_RESUMABLE"),
                            "recovery_http_status" to JsonPrimitive(httpStatus),
                            "recovery_disposition" to JsonPrimitive(disposition.name)
                        )
                    )
                )
            }
        )
    }
}

/** The saved cause outranks a later continuation rejection; text is bounded and credential-redacted. */
internal fun GatewayJobResult.savedFailure(): ErrorDetail? {
    val source = structuredError ?: result?.error?.let { failure ->
        kotlinx.serialization.json.buildJsonObject {
            put("message", JsonPrimitive(failure.message))
            failure.code?.let { put("code", JsonPrimitive(it)) }
            failure.type?.let { put("type", JsonPrimitive(it)) }
            failure.failureDetails?.forEach { (key, value) -> put(key, value) }
        }
    } ?: error?.takeIf(String::isNotBlank)?.let { kotlinx.serialization.json.buildJsonObject { put("message", JsonPrimitive(it)) } }
        ?: return null
    val envelope = JsonObject(mapOf("error" to source)).toString()
    val details = dev.chungjungsoo.gptmobile.data.network.structuredProviderFailure(envelope, job?.statusCode?.takeIf { it in 400..599 })
    val contract = dev.chungjungsoo.gptmobile.data.network.error.ProviderContractFailure.find(
        (source["code"] as? JsonPrimitive)?.contentOrNull,
        (source["message"] as? JsonPrimitive)?.contentOrNull
    )
    return ErrorDetail(
        message = contract?.message ?: dev.chungjungsoo.gptmobile.data.network.providerErrorDetails(envelope, "The saved gateway job failed."),
        code = contract?.code ?: (source["code"] as? JsonPrimitive)?.contentOrNull?.take(128) ?: "GATEWAY_JOB_FAILED",
        type = "gateway_saved_failure",
        retryable = false,
        failureDetails = JsonObject(details.orEmpty() + mapOf("job_id" to JsonPrimitive(jobId), "retryable" to JsonPrimitive(false)))
    )
}
