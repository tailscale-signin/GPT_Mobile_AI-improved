package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.free.FreeCatalogState
import dev.chungjungsoo.gptmobile.data.free.FreeQueueJob
import dev.chungjungsoo.gptmobile.data.free.FreeQueueJobs
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readLineStrict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal class FreeQueuedTransport(private val client: HttpClient, private val preparation: ModelRequestPreparation) {
    suspend fun generate(request: ChatCompletionRequest, config: ProviderRequestConfig, timeoutSeconds: Int, emit: suspend (ChatCompletionChunk) -> Unit) {
        val provider = requireNotNull(config.freeProvider)
        require(provider in setOf(FreeAiProvider.AI_HORDE, FreeAiProvider.HUGGING_FACE))
        val context = currentCoroutineContext()
        context[RequestAuthorizationGuard]?.validate?.invoke()
        val prompt = textPrompt(request)
        val payload = if (provider == FreeAiProvider.AI_HORDE) hordePayload(request, prompt) else gradioPayload(request, prompt)
        // Scope isolates conversations/accepted turns; payload and permission changes cannot reuse jobs.
        val scope = config.queueScope ?: context[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.queueScope ?: java.util.UUID.randomUUID().toString()
        val key = researchHash("$scope|${config.profileUid}|${config.permissionRevision}|${provider.name}|${config.queuePersistent}|$payload")
        FreeQueueJobs.withJob(key) {
            var job = FreeQueueJobs.read(key, config.queuePersistent)
            if (job != null) {
                check(System.currentTimeMillis() - job.createdAt < 20 * 60_000) { "Queued job expired. Start a new generation; the previous submission will not be repeated." }
                check(job.state !in setOf("SUBMITTING", "CANCELLED")) { "Queued submission is ${job.state.lowercase()}. It cannot be replayed safely; start a new generation." }
                if (job.answer != null) {
                    deliver(requireNotNull(job.answer), job.servedModel, provider, emit)
                    return@withJob
                }
            }
            try {
                withTimeout(timeoutSeconds.coerceIn(15, 1200) * 1000L) {
                    if (job == null) {
                        if (provider == FreeAiProvider.HUGGING_FACE) validateGradioSchema()
                        val actualPayload = if (provider == FreeAiProvider.HUGGING_FACE) JsonObject(payload + ("session_hash" to JsonPrimitive(key))) else payload
                        val wire = preparation.prepareQueued(actualPayload.toString(), config, request.model, if (provider == FreeAiProvider.AI_HORDE && request.model != "horde-image") request.maxTokens else null)
                        FreeQueueJobs.save(FreeQueueJob(key, chatId = scope.substringBefore(':').toIntOrNull()), config.queuePersistent)
                        val response = FreeAiRequestLimiter.shared.withRequest(provider, request.model) {
                            client.post(if (provider == FreeAiProvider.AI_HORDE) hordeBase(request.model) + "/async" else "${provider.apiUrl}/gradio_api/call/add_message") {
                                contentType(ContentType.Application.Json)
                                if (provider == FreeAiProvider.AI_HORDE) header("apikey", "0000000000")
                                setBody(wire)
                                timeout { requestTimeoutMillis = 30_000 }
                            }
                        }
                        val root = checkedJson(response, provider)
                        val id = root.text(if (provider == FreeAiProvider.AI_HORDE) "id" else "event_id")
                        check(id != null && id.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "Queue accepted no usable job ID. The submission will not be repeated." }
                        job = FreeQueueJob(key, id, "QUEUED", chatId = scope.substringBefore(':').toIntOrNull())
                        FreeQueueJobs.save(requireNotNull(job), config.queuePersistent)
                    }
                    emit(ChatCompletionChunk(applicationNotice = "${provider.displayName}: queued · job ${job!!.id}"))
                    val result = if (provider == FreeAiProvider.AI_HORDE) pollHorde(requireNotNull(job!!.id), request.model, emit) else readGradio(requireNotNull(job!!.id), emit)
                    val usable = dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser.parse(result.first).displayContent
                    check(usable.isNotBlank()) { "${provider.displayName} completed without a usable final answer (reasoning-only or empty output)." }
                    job = job!!.copy(state = "COMPLETED", answer = usable, servedModel = result.second)
                    FreeQueueJobs.save(requireNotNull(job), config.queuePersistent)
                    deliver(usable, result.second, provider, emit)
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) {
                    withContext(NonCancellable) {
                        FreeQueueJobs.save((job ?: FreeQueueJob(key, chatId = scope.substringBefore(':').toIntOrNull())).copy(state = "CANCELLED"), config.queuePersistent)
                        withTimeoutOrNull(5000) {
                            if (provider == FreeAiProvider.AI_HORDE && job?.id != null) client.delete("${hordeBase(request.model)}/status/${job!!.id}")
                            if (provider == FreeAiProvider.HUGGING_FACE && job?.id != null) {
                                client.post("${provider.apiUrl}/gradio_api/call/cancel") {
                                    contentType(ContentType.Application.Json)
                                    setBody(
                                        buildJsonObject {
                                            put("data", JsonArray(emptyList()))
                                            put("session_hash", key)
                                        }.toString()
                                    )
                                }
                            }
                        }
                    }
                }
                if (cancelled is TimeoutCancellationException && context[kotlinx.coroutines.Job]?.isActive == true) error("${provider.displayName} is still queued or generating. Its job ID was saved; retry this request to resume it.")
                throw cancelled
            }
        }
    }

    private suspend fun deliver(answer: String, model: String?, provider: FreeAiProvider, emit: suspend (ChatCompletionChunk) -> Unit) {
        FreeCatalogState.recordResponse(provider, model)
        emit(ChatCompletionChunk(model = model, choices = listOf(Choice(delta = Delta(content = answer), finishReason = "stop"))))
    }

    private suspend fun checkedJson(response: HttpResponse, provider: FreeAiProvider): JsonObject {
        if (response.status.value == 429) throw FreeAiRateLimitException(provider, FreeAiRequestLimiter.shared.defer(provider, response.headers["Retry-After"]))
        check(response.status.isSuccess()) { "${provider.displayName}: HTTP ${response.status.value}. Job state was retained; no automatic resubmission." }
        val channel = response.bodyAsChannel()
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= 2_000_000) {
            val count = channel.readAvailable(buffer, 0, minOf(buffer.size, 2_000_001 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        check(output.size() <= 2_000_000) { "Queue response exceeds supported size" }
        val body = output.toString(Charsets.UTF_8.name())
        return Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid queue response")
    }

    private suspend fun pollHorde(id: String, model: String, emit: suspend (ChatCompletionChunk) -> Unit): Pair<String, String?> {
        val image = model == "horde-image"
        while (true) {
            currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
            var result = checkedJson(client.get("${hordeBase(model)}/${if (image) "check" else "status"}/$id") { timeout { requestTimeoutMillis = 15_000 } }, FreeAiProvider.AI_HORDE)
            check(result.flag("faulted") != true) { "AI Horde job failed; no new job was submitted." }
            if (result.flag("done") == true) {
                if (image) result = checkedJson(client.get("${hordeBase(model)}/status/$id"), FreeAiProvider.AI_HORDE)
                val generation = (result["generations"] as? JsonArray)?.firstOrNull() as? JsonObject ?: error("AI Horde completed with no generation")
                val answer = if (image) {
                    val url = requireNotNull(generation.text("img"))
                    val uri = java.net.URI(url)
                    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && !url.contains(Regex("[\\s<>\\[\\]()]"))) { "Image job returned an invalid image URL" }
                    "![AI Horde generated image]($url)"
                } else {
                    generation.text("text").orEmpty()
                }
                return answer to generation.text("model")
            }
            emit(ChatCompletionChunk(applicationNotice = "AI Horde: ${if ((result["processing"] as? JsonPrimitive)?.intOrNull?.let { it > 0 } == true) "generating" else "queued"} · position ${result.text("queue_position") ?: "unknown"} · estimated wait ${result.text("wait_time") ?: "unknown"} s"))
            delay(6000)
        }
    }

    private suspend fun validateGradioSchema() {
        val root = checkedJson(client.get("${FreeAiProvider.HUGGING_FACE.apiUrl}/gradio_api/info"), FreeAiProvider.HUGGING_FACE)
        val endpoint = (root["named_endpoints"] as? JsonObject)?.get("/add_message") as? JsonObject
        val parameters = (endpoint?.get("parameters") as? JsonArray)?.map { (it as? JsonObject)?.text("parameter_name") }
        check(parameters == listOf("input_value", "settings_form_value")) { "The Qwen Space API changed. No prompt was sent; update its adapter." }
    }

    private suspend fun readGradio(id: String, emit: suspend (ChatCompletionChunk) -> Unit): Pair<String, String?> {
        var final: String? = null
        client.prepareGet("${FreeAiProvider.HUGGING_FACE.apiUrl}/gradio_api/call/add_message/$id") {
            timeout {
                requestTimeoutMillis = null
                socketTimeoutMillis = 60_000
            }
        }.execute { response ->
            check(response.status.isSuccess()) { "Space queue is unavailable (HTTP ${response.status.value})" }
            val channel = response.bodyAsChannel()
            var event = ""
            while (true) {
                currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
                if (channel.isClosedForRead) break
                val line = channel.readLineStrict(2_000_000) ?: break
                check(line.length < 2_000_000) { "Space output exceeds supported size" }
                if (line.startsWith("event:")) event = line.substringAfter(':').trim()
                if (line.startsWith("data:")) {
                    if (event == "error") error("Space generation failed. Its job ID was retained; retry later.")
                    if (event == "complete") {
                        final = gradioAnswer(Json.parseToJsonElement(line.substringAfter(':').trim()))
                        break
                    }
                    if (event == "generating") emit(ChatCompletionChunk(applicationNotice = "Hugging Face Spaces: generating · job $id"))
                }
            }
        }
        return requireNotNull(final) { "Space stream ended before a complete record. Retry can resume the saved job." } to "Qwen/qwen3-235b-a22b"
    }

    companion object {
        internal fun textPrompt(request: ChatCompletionRequest): String = request.messages.joinToString("\n\n") { message ->
            require(message.toolCalls.isNullOrEmpty() && message.content.orEmpty().all { it is TextContent }) { "Queued Free services accept text only; client tool calls and attachments are unavailable." }
            "${message.role.name.lowercase()}: ${message.content.orEmpty().filterIsInstance<TextContent>().joinToString("\n") { it.text }}"
        } + "\n\nassistant:"
        internal fun hordePayload(request: ChatCompletionRequest, prompt: String): JsonObject = buildJsonObject {
            put("prompt", prompt)
            put(
                "params",
                buildJsonObject {
                    put("n", 1)
                    if (request.model == "horde-image") {
                        put("width", 512)
                        put("height", 512)
                        put("steps", 20)
                    } else {
                        val tokens = (request.maxTokens ?: 512).coerceAtMost(1024)
                        require(tokens >= 16) { "AI Horde requires at least 16 output tokens" }
                        require(dev.chungjungsoo.gptmobile.data.context.RequestAdmission.estimate(prompt).promptTokens + tokens <= 32768) { "AI Horde prompt exceeds the supported queued context; shorten the conversation." }
                        put("max_length", tokens)
                        put("max_context_length", (dev.chungjungsoo.gptmobile.data.context.RequestAdmission.estimate(prompt).promptTokens + tokens).coerceIn(2048, 32768))
                    }
                }
            )
            if (request.model == "horde-image") {
                put("r2", true)
                put("nsfw", false)
                put("censor_nsfw", true)
            } else if (request.model != "automatic") {
                put("models", JsonArray(listOf(JsonPrimitive(request.model))))
            }
        }
        internal fun gradioPayload(request: ChatCompletionRequest, prompt: String): JsonObject = buildJsonObject {
            put(
                "data",
                JsonArray(
                    listOf(
                        JsonPrimitive(prompt),
                        buildJsonObject {
                            put("model", request.model)
                            put("sys_prompt", "You are a helpful assistant.")
                            put("thinking_budget", 1)
                        }
                    )
                )
            )
        }
        internal fun gradioAnswer(data: JsonElement): String {
            val outputs = data as? JsonArray ?: error("Invalid Space completion record")
            val history = outputs.getOrNull(1).let { if (it is JsonObject) it["value"] as? JsonArray else it as? JsonArray } ?: error("Space returned no conversation")
            val answer = history.filterIsInstance<JsonObject>().lastOrNull { it.text("role") == "assistant" } ?: error("Space returned no assistant answer")
            return (answer["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it.text("type") == "text" }.joinToString("\n") { it.text("content").orEmpty() }
        }
        private fun hordeBase(model: String) = "${FreeAiProvider.AI_HORDE.apiUrl}/generate" + if (model == "horde-image") "" else "/text"
        private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
    }
}
