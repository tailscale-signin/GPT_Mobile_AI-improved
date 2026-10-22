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
import io.ktor.utils.io.readUTF8Line
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
            job?.let { saved ->
                config.freeDiagnostics?.apply {
                    jobId = saved.id
                    progress = saved.state
                    terminal = saved.state in setOf("FAILED", "EXPIRED", "CANCELLED", "REJECTED")
                    stage = if (saved.state == "FAILED") "generation" else "queue_recovery"
                }
            }
            // A definite rejection created no remote job. An explicit retry may submit again.
            if (job?.state == "REJECTED") {
                job = null
                config.freeDiagnostics?.apply {
                    jobId = null
                    terminal = null
                }
            }
            if (job != null) {
                check(System.currentTimeMillis() - job.createdAt < 20 * 60_000) { "Queued job expired. Start a new generation; the previous submission will not be repeated." }
                check(job.state !in setOf("SUBMITTING", "CANCELLED", "FAILED", "EXPIRED")) { "Queued submission is ${job.state.lowercase()}. It cannot be resumed; start a new generation." }
                if (job.answer != null) {
                    deliver(requireNotNull(job.answer), job.servedModel, provider, emit)
                    return@withJob
                }
            }
            try {
                withTimeout(timeoutSeconds.coerceIn(15, 1200) * 1000L) {
                    if (job == null) {
                        if (provider == FreeAiProvider.HUGGING_FACE) {
                            config.freeDiagnostics?.stage = "schema_validation"
                            validateGradioSchema(config, request.model)
                        }
                        val actualPayload = if (provider == FreeAiProvider.HUGGING_FACE) JsonObject(payload + ("session_hash" to JsonPrimitive(key))) else payload
                        val wire = preparation.prepareQueued(actualPayload.toString(), config, request.model, if (provider == FreeAiProvider.AI_HORDE && request.model != "horde-image") ((actualPayload["params"] as? JsonObject)?.get("max_length") as? JsonPrimitive)?.intOrNull else null)
                        config.freeDiagnostics?.stage = "submission"
                        val response = FreeAiRequestLimiter.shared.withRequest(provider, request.model) {
                            FreeQueueJobs.save(FreeQueueJob(key, chatId = scope.substringBefore(':').toIntOrNull()), config.queuePersistent)
                            client.post(if (provider == FreeAiProvider.AI_HORDE) hordeBase(request.model) + "/async" else "${provider.apiUrl}/gradio_api/call/add_message") {
                                contentType(ContentType.Application.Json)
                                if (provider == FreeAiProvider.AI_HORDE) {
                                    header("apikey", "0000000000")
                                    header("Client-Agent", HORDE_CLIENT)
                                }
                                setBody(wire)
                                timeout { requestTimeoutMillis = 30_000 }
                            }
                        }
                        // A timeout (408) can arrive after enqueueing; keep its uncertain receipt.
                        if (response.status.value in setOf(400, 401, 402, 403, 404, 405, 413, 415, 422, 429)) FreeQueueJobs.save(FreeQueueJob(key, state = "REJECTED", chatId = scope.substringBefore(':').toIntOrNull()), config.queuePersistent)
                        val root = checkedJson(response, config, request.model)
                        val id = root.text(if (provider == FreeAiProvider.AI_HORDE) "id" else "event_id")
                        check(id != null && id.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "Queue accepted no usable job ID. The submission will not be repeated." }
                        job = FreeQueueJob(key, id, "QUEUED", chatId = scope.substringBefore(':').toIntOrNull())
                        FreeQueueJobs.save(requireNotNull(job), config.queuePersistent)
                    }
                    FreeCatalogState.recordAccepted(provider)
                    config.freeDiagnostics?.apply {
                        jobId = job!!.id
                        terminal = false
                        stage = "generation"
                        progress = "queued"
                    }
                    emit(ChatCompletionChunk(applicationNotice = "${provider.displayName}: queued · job ${job!!.id}"))
                    if (provider == FreeAiProvider.AI_HORDE && request.model != "horde-image") emit(ChatCompletionChunk(applicationNotice = "AI Horde anonymous output allowance: up to ${(payload["params"] as? JsonObject)?.get("max_length")} tokens. The provider may reduce it further; the accepted allowance is not reported."))
                    val result = if (provider == FreeAiProvider.AI_HORDE) pollHorde(requireNotNull(job!!.id), request.model, config, emit) else readGradio(requireNotNull(job!!.id), config, request.model, emit)
                    val usable = dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser.parse(result.first).displayContent
                    if (usable.isBlank()) throw QueueTerminalFailure("${provider.displayName} completed without a usable final answer (reasoning-only or empty output). Start a new generation; this job cannot resume.")
                    job = job!!.copy(state = "COMPLETED", answer = usable, servedModel = result.second)
                    FreeQueueJobs.save(requireNotNull(job), config.queuePersistent)
                    deliver(usable, result.second, provider, emit)
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) {
                    withContext(NonCancellable) {
                        FreeQueueJobs.save((job ?: FreeQueueJob(key, chatId = scope.substringBefore(':').toIntOrNull())).copy(state = "CANCELLED"), config.queuePersistent)
                        withTimeoutOrNull(5000) {
                            if (provider == FreeAiProvider.AI_HORDE && job?.id != null) {
                                client.delete("${hordeBase(request.model)}/status/${job!!.id}") {
                                    header("apikey", "0000000000")
                                    header("Client-Agent", HORDE_CLIENT)
                                }
                            }
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
                if (cancelled is TimeoutCancellationException && context[kotlinx.coroutines.Job]?.isActive == true) {
                    error(if (job?.id != null) "${provider.displayName} is still queued or generating. Its job ID was saved; retry this request to resume it." else "${provider.displayName} timed out before a job ID was confirmed. No resumable job is available; any uncertain submission will not be repeated automatically.")
                }
                throw cancelled
            } catch (failed: QueueTerminalFailure) {
                config.freeDiagnostics?.terminal = true
                job?.let { FreeQueueJobs.save(it.copy(state = "FAILED"), config.queuePersistent) }
                FreeCatalogState.recordFailure(provider, failed.message.orEmpty())
                if (config.freeDiagnostics?.recorded != true) dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, request.model, failed.message.orEmpty(), terminal = true)
                throw failed
            }
        }
    }

    private suspend fun deliver(answer: String, model: String?, provider: FreeAiProvider, emit: suspend (ChatCompletionChunk) -> Unit) {
        FreeCatalogState.recordResponse(provider, model)
        emit(ChatCompletionChunk(model = model, choices = listOf(Choice(delta = Delta(content = answer), finishReason = "stop"))))
    }

    private suspend fun checkedJson(response: HttpResponse, config: ProviderRequestConfig, model: String): JsonObject {
        val provider = requireNotNull(config.freeProvider)
        if (!response.status.isSuccess()) {
            val body = readFreeErrorBody(response)
            val seconds = if (response.status.value == 429) FreeAiRequestLimiter.shared.defer(provider, response.headers["Retry-After"], model) else null
            val retryAt = seconds?.let { FreeAiRequestLimiter.shared.cooldownUntilMillis(provider, model) }
            val message = freeHttpErrorMessage(response.status.value, body)
            FreeCatalogState.recordHttpFailure(provider, response.status.value, message, retryAt, model, body)
            dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, model, message, response.status.value, body, { response.headers[it] }, retryAt)
            if (seconds != null) throw FreeAiRateLimitException(provider, seconds, message)
            error("${provider.displayName}: $message No automatic resubmission was attempted.")
        }
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

    private suspend fun pollHorde(id: String, model: String, config: ProviderRequestConfig, emit: suspend (ChatCompletionChunk) -> Unit): Pair<String, String?> {
        val image = model == "horde-image"
        while (true) {
            currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
            var result = checkedJson(
                client.get("${hordeBase(model)}/${if (image) "check" else "status"}/$id") {
                    header("Client-Agent", HORDE_CLIENT)
                    timeout { requestTimeoutMillis = 15_000 }
                },
                config,
                model
            )
            if (result.flag("faulted") == true) throw QueueTerminalFailure("AI Horde job failed. Start a new generation; this job cannot resume.")
            if (result.flag("done") == true) {
                if (image) result = checkedJson(client.get("${hordeBase(model)}/status/$id"), config, model)
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

    private suspend fun validateGradioSchema(config: ProviderRequestConfig, model: String) {
        val root = checkedJson(client.get("${FreeAiProvider.HUGGING_FACE.apiUrl}/gradio_api/info"), config, model)
        val endpoint = (root["named_endpoints"] as? JsonObject)?.get("/add_message") as? JsonObject
        val parameters = (endpoint?.get("parameters") as? JsonArray)?.map { (it as? JsonObject)?.text("parameter_name") }
        check(parameters == listOf("input_value", "settings_form_value")) { "The Qwen Space API changed. No prompt was sent; update its adapter." }
    }

    // Bounded, lenient EOF: readLine has no size limit; readLineStrict rejects the final partial line.
    @Suppress("DEPRECATION")
    private suspend fun readGradio(id: String, config: ProviderRequestConfig, model: String, emit: suspend (ChatCompletionChunk) -> Unit): Pair<String, String?> {
        var final: String? = null
        var snapshot: String? = null
        client.prepareGet("${FreeAiProvider.HUGGING_FACE.apiUrl}/gradio_api/call/add_message/$id") {
            timeout {
                requestTimeoutMillis = null
                socketTimeoutMillis = 60_000
            }
        }.execute { response ->
            config.freeDiagnostics?.stage = "stream_retrieval"
            if (!response.status.isSuccess()) checkedJson(response, config, model)
            val channel = response.bodyAsChannel()
            var event = ""
            val data = StringBuilder()
            var lastNotice = 0L
            while (true) {
                currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
                val nextLine = if (channel.isClosedForRead) null else channel.readUTF8Line(2_000_000)
                if (nextLine == null && event.isEmpty() && data.isEmpty()) break
                val line = nextLine ?: ""
                check(line.length < 2_000_000) { "Space output exceeds supported size" }
                if (line.startsWith("event:")) event = line.substringAfter(':').trim()
                if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substringAfter(':').trimStart())
                    check(data.length <= 2_000_000) { "Space event exceeds supported size" }
                }
                if (line.isBlank() && (data.isNotEmpty() || event == "error")) {
                    if (event == "error") {
                        config.freeDiagnostics?.stage = "generation"
                        val detail = gradioErrorDetails(data.toString())
                        val message = "Space generation failed: $detail This job cannot resume; start a new generation later or choose another Free provider."
                        dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, model, message, body = gradioErrorEnvelope(data.toString()).takeUnless { it == "{}" } ?: data.toString(), event = event, terminal = true)
                        throw QueueTerminalFailure(message)
                    }
                    config.freeDiagnostics?.progress = event.take(40)
                    if (event in setOf("generating", "complete")) {
                        val record = Json.parseToJsonElement(data.toString())
                        gradioAnswerOrNull(record)?.let { snapshot = it }
                    }
                    if (event == "complete") {
                        final = snapshot ?: throw QueueTerminalFailure("Space completed without an assistant answer. Start a new generation or choose another Free provider.")
                        break
                    }
                    if (event == "generating" && System.currentTimeMillis() - lastNotice >= 1000) {
                        emit(ChatCompletionChunk(applicationNotice = "Hugging Face Spaces: generating · job $id"))
                        lastNotice = System.currentTimeMillis()
                    }
                    data.clear()
                    event = ""
                }
                if (nextLine == null) break
            }
        }
        return requireNotNull(final) { "Space stream ended before a complete record. Retry can resume the saved job." } to "Qwen/qwen3-235b-a22b"
    }

    companion object {
        private const val HORDE_CLIENT = "GPT-Mobile:1:https://github.com/tailscale-signin/GPT_Mobile_AI-improved"
        internal fun textPrompt(request: ChatCompletionRequest): String = request.messages.joinToString("\n\n") { message ->
            require(message.toolCalls.isNullOrEmpty() && message.content.orEmpty().all { it is TextContent }) { "Queued Free services accept text only; client tool calls and attachments are unavailable." }
            "${message.role.name.lowercase()}: ${message.content.orEmpty().filterIsInstance<TextContent>().joinToString("\n") { it.text }}"
        } + "\n\nassistant:"
        internal fun hordePayload(request: ChatCompletionRequest, prompt: String): JsonObject = buildJsonObject {
            put("prompt", prompt)
            if (request.model != "horde-image") put("allow_downgrade", true)
            put(
                "params",
                buildJsonObject {
                    put("n", 1)
                    if (request.model == "horde-image") {
                        put("width", 512)
                        put("height", 512)
                        put("steps", 20)
                    } else {
                        val tokens = (request.maxTokens ?: 256).coerceAtMost(256)
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
        internal fun gradioAnswer(data: JsonElement): String = gradioAnswerOrNull(data) ?: error("Space returned no assistant answer")
        internal fun gradioAnswerOrNull(data: JsonElement): String? {
            val outputs = data as? JsonArray ?: error("Invalid Space completion record")
            // Gradio hides State components from its public API; output positions can vary.
            val answer = outputs.mapNotNull { if (it is JsonObject) it["value"] as? JsonArray else it as? JsonArray }
                .flatMap { it.filterIsInstance<JsonObject>() }.lastOrNull { it.text("role") == "assistant" } ?: return null
            val content = answer["content"]
            return if (content is JsonPrimitive) {
                content.contentOrNull
            } else {
                (content as? JsonArray).orEmpty()
                    .filterIsInstance<JsonObject>().filter { it.text("type") == "text" }
                    .joinToString("\n") { it.text("content") ?: it.text("text").orEmpty() }.takeIf { it.isNotBlank() }
            }
        }
        private fun hordeBase(model: String) = "${FreeAiProvider.AI_HORDE.apiUrl}/generate" + if (model == "horde-image") "" else "/text"
        private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
    }
}

private class QueueTerminalFailure(message: String) : IllegalStateException(message)
