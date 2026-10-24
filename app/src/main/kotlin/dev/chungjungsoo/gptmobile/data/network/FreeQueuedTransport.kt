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
        // Keep the stable cache identity independent of worker selection. A retry must find
        // the accepted receipt even when volunteer availability changes.
        val identityPayload = if (provider == FreeAiProvider.AI_HORDE) hordePayload(request, prompt) else gradioPayload(request, prompt)
        var payload = identityPayload
        // Scope isolates conversations/accepted turns; payload and permission changes cannot reuse jobs.
        val scope = config.queueScope ?: context[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.queueScope ?: java.util.UUID.randomUUID().toString()
        val key = researchHash("$scope|${config.profileUid}|${config.permissionRevision}|${provider.name}|${config.queuePersistent}|$identityPayload")
        val submission = FreeQueueJob(key, chatId = scope.substringBefore(':').toIntOrNull(), protocol = if (provider == FreeAiProvider.HUGGING_FACE) "GRADIO_CALL_V2" else "HORDE", ownerRunId = context[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.queueScope, profileUid = config.profileUid, requestedModel = request.model)
        FreeQueueJobs.withJob(key) {
            var job = FreeQueueJobs.read(key, config.queuePersistent)
            job?.let { saved ->
                config.freeDiagnostics?.apply {
                    jobId = saved.id
                    progress = saved.state
                    terminal = if (saved.state in setOf("READING", "DISCONNECTED")) null else saved.state in setOf("FAILED", "EXPIRED", "CANCELLED", "REJECTED")
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
                check(job.state !in setOf("SUBMITTING", "CANCELLED", "FAILED", "EXPIRED", "READING", "DISCONNECTED")) { "QUEUE_RECEIPT_NOT_RESUMABLE: Queued submission is ${job.state.lowercase()}. It cannot be resumed; explicitly start a new generation." }
                check(provider != FreeAiProvider.HUGGING_FACE || job.answer != null || job.protocol == "GRADIO_CALL_V2") {
                    "QUEUE_RECEIPT_NOT_RESUMABLE: The saved Space receipt uses the previous session protocol. Explicitly start a new generation."
                }
                if (job.answer != null) {
                    deliver(requireNotNull(job.answer), job.servedModel, provider, emit, verifiedGeneration = false)
                    return@withJob
                }
            }
            try {
                withTimeout(timeoutSeconds.coerceIn(15, 1200) * 1000L) {
                    if (job == null) {
                        if (provider == FreeAiProvider.HUGGING_FACE) {
                            config.freeDiagnostics?.stage = "schema_validation"
                            payload = gradioPayload(request, prompt, validateGradioSchema(config, request.model))
                        }
                        if (provider == FreeAiProvider.AI_HORDE && request.model != "horde-image") {
                            config.freeDiagnostics?.stage = "worker_selection"
                            val catalog = checkedJsonArray(client.get("${provider.apiUrl}/status/models?type=text"), config, request.model)
                            val selected = HordeTextPolicy.select(catalog, request.model)
                                ?: error("QUEUE_WORKERS_UNAVAILABLE: No active worker with a supported non-reasoning instruction template. Choose another provider or refresh later.")
                            val workerRequest = request.copy(model = selected)
                            payload = hordePayload(workerRequest, HordeTextPolicy.prompt(workerRequest))
                            emit(ChatCompletionChunk(applicationNotice = "AI Horde selected $selected with its instruction template; anonymous output remains capped at 256 tokens."))
                        }
                        // The simplified /call result route uses event_id as its session key.
                        // Supplying our cache key as session_hash makes Gradio 5.27 return 404.
                        val actualPayload = payload
                        val wire = preparation.prepareQueued(actualPayload.toString(), config, request.model, if (provider == FreeAiProvider.AI_HORDE && request.model != "horde-image") ((actualPayload["params"] as? JsonObject)?.get("max_length") as? JsonPrimitive)?.intOrNull else null)
                        if (config.captureGenerationReceipt || config.requestRole == "synthesis") config.freeDiagnostics?.admission?.outputTokens?.toInt()?.let { emit(ChatCompletionChunk(serializedOutputTokens = it)) }
                        config.freeDiagnostics?.stage = "submission"
                        val response = FreeAiRequestLimiter.shared.withRequest(provider, request.model) {
                            FreeQueueJobs.save(submission, config.queuePersistent)
                            config.freeDiagnostics?.attempted()
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
                        config.freeDiagnostics?.responded()
                        // A timeout (408) can arrive after enqueueing; keep its uncertain receipt.
                        if (response.status.value in setOf(400, 401, 402, 403, 404, 405, 413, 415, 422, 429)) FreeQueueJobs.save(submission.copy(state = "REJECTED"), config.queuePersistent)
                        val root = checkedJson(response, config, request.model)
                        val id = root.text(if (provider == FreeAiProvider.AI_HORDE) "id" else "event_id")
                        check(id != null && id.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "Queue accepted no usable job ID. The submission will not be repeated." }
                        job = FreeQueueJob(key, id, "QUEUED", chatId = scope.substringBefore(':').toIntOrNull(), protocol = if (provider == FreeAiProvider.HUGGING_FACE) "GRADIO_CALL_V2" else "HORDE", ownerRunId = context[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.queueScope, profileUid = config.profileUid, requestedModel = request.model)
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
                    if (provider == FreeAiProvider.HUGGING_FACE) {
                        // This Gradio protocol cleans the event on SSE disconnect and offers no replay cursor.
                        // Persist before opening the stream so process death cannot resubmit or promise resume.
                        job = job!!.copy(state = "READING")
                        FreeQueueJobs.save(requireNotNull(job), config.queuePersistent)
                    }
                    val result = if (provider == FreeAiProvider.AI_HORDE) pollHorde(requireNotNull(job!!.id), request.model, config, emit) else readGradio(requireNotNull(job!!.id), config, request.model, emit)
                    val usable = dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser.parse(result.first).displayContent
                    if (usable.isBlank()) throw QueueTerminalFailure("${provider.displayName} completed with ${if (result.first.isBlank()) "empty output" else "reasoning-only output"}. Start a new generation; this job cannot resume.")
                    job = job!!.copy(state = "COMPLETED", answer = usable, servedModel = result.second)
                    FreeQueueJobs.save(requireNotNull(job), config.queuePersistent)
                    deliver(usable, result.second, provider, emit)
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) {
                    withContext(NonCancellable) {
                        FreeQueueJobs.save((job ?: submission).copy(state = "CANCELLED"), config.queuePersistent)
                        withTimeoutOrNull(5000) {
                            if (provider == FreeAiProvider.AI_HORDE && job?.id != null) {
                                client.delete("${hordeBase(request.model)}/status/${job!!.id}") {
                                    header("apikey", "0000000000")
                                    header("Client-Agent", HORDE_CLIENT)
                                }
                            }
                            // Gradio's SSE disconnect cleans this event. /call/cancel is a generation
                            // endpoint, not cancellation; do not enqueue a second request to stop it.
                        }
                    }
                }
                if (cancelled is TimeoutCancellationException && context[kotlinx.coroutines.Job]?.isActive == true) {
                    if (provider == FreeAiProvider.HUGGING_FACE && job?.id != null) {
                        FreeQueueJobs.save(job!!.copy(state = "DISCONNECTED"), config.queuePersistent)
                        throw QueueTerminalFailure("QUEUE_STREAM_DISCONNECTED: Space result retrieval timed out. The receipt is retained; this protocol does not guarantee reconnect. Explicitly start a new generation.", remoteTerminal = null)
                    }
                    if (job?.id != null) config.freeDiagnostics?.category = FreeFailureCategory.QUEUE_JOB_PENDING
                    error(if (job?.id != null) "QUEUE_JOB_PENDING: ${provider.displayName} is still queued or generating. Its job ID was saved; retry the unchanged request to check that job." else "QUEUE_SUBMISSION_UNCERTAIN: ${provider.displayName} timed out before a job ID was confirmed. No resumable job is available; any uncertain submission will not be repeated automatically.")
                }
                throw cancelled
            } catch (failed: QueueTerminalFailure) {
                config.freeDiagnostics?.terminal = failed.remoteTerminal
                config.freeDiagnostics?.category = freeFailureCategory(failed)
                job?.let { FreeQueueJobs.save(it.copy(state = if (failed.remoteTerminal == true) "FAILED" else "DISCONNECTED"), config.queuePersistent) }
                if (failed.remoteTerminal == true) FreeCatalogState.recordTerminalFailure(provider, request.model, failed.message.orEmpty())
                if (config.freeDiagnostics?.recorded != true) dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, request.model, failed.message.orEmpty(), terminal = failed.remoteTerminal)
                throw failed
            } catch (failed: FreeProviderException) {
                throw failed
            } catch (failed: Exception) {
                if (provider == FreeAiProvider.HUGGING_FACE && job?.state == "READING") {
                    FreeQueueJobs.save(job!!.copy(state = "DISCONNECTED"), config.queuePersistent)
                    config.freeDiagnostics?.terminal = null
                    config.freeDiagnostics?.category = FreeFailureCategory.NETWORK_ERROR
                    val message = "QUEUE_STREAM_DISCONNECTED: Space result retrieval failed. The accepted receipt is retained; replay is not supported. Explicitly start a new generation. ${failed.message.orEmpty().take(500)}"
                    dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, request.model, message, terminal = null)
                    throw QueueTerminalFailure(message, remoteTerminal = null)
                }
                throw IllegalStateException("QUEUE_REQUEST_FAILED: ${failed.message.orEmpty()}. No automatic continuation was attempted; any accepted or uncertain receipt is retained. Retry only the unchanged request to inspect its status.", failed)
            }
        }
    }

    suspend fun checkSavedHordeJob(runId: String, chatId: Int, config: ProviderRequestConfig): String {
        val job = FreeQueueJobs.forRun(runId, chatId, requireNotNull(config.profileUid), config.queuePersistent) ?: return "No saved job receipt is available for this attempt. No generation was submitted."
        require(job.protocol in setOf("HORDE", "GRADIO_CALL_V2"))
        if (job.protocol == "GRADIO_CALL_V2") {
            return job.answer?.let { "Saved provider result:\n\n$it" }
                ?: "Saved Space job: ${job.state.lowercase()}. This deployment does not offer a separate status endpoint. Generate again creates a new request; checking this receipt has not submitted one."
        }
        if (job.id == null) return "Submission outcome is uncertain and no remote job ID was received. No duplicate generation was submitted."
        require(job.id.matches(Regex("[A-Za-z0-9-]{1,128}")))
        return FreeQueueJobs.withJob(job.key) {
            val current = requireNotNull(FreeQueueJobs.read(job.key, config.queuePersistent))
            current.answer?.let { return@withJob "Saved provider result (not a Combined answer):\n\n$it" }
            if (current.state in setOf("FAILED", "CANCELLED", "EXPIRED")) return@withJob "The saved remote job is ${current.state.lowercase()}. No generation was submitted."
            currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
            val model = requireNotNull(current.requestedModel)
            val result = checkedJson(
                client.get("${hordeBase(model)}/status/${current.id}") {
                    header("Client-Agent", HORDE_CLIENT)
                    timeout { requestTimeoutMillis = 15_000 }
                },
                config,
                model
            )
            currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
            when {
                result.flag("faulted") == true -> {
                    FreeQueueJobs.save(current.copy(state = "FAILED"), config.queuePersistent)
                    "The remote job failed. Saved contributions and any newer answer are unchanged."
                }
                result.flag("done") == true -> {
                    val generation = (result["generations"] as? JsonArray)?.firstOrNull() as? JsonObject
                    val answer = dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser.parse(generation?.text("text").orEmpty()).displayContent
                    FreeQueueJobs.save(current.copy(state = if (answer.isBlank()) "FAILED" else "COMPLETE", answer = answer.takeIf { it.isNotBlank() }, servedModel = generation?.text("model")), config.queuePersistent)
                    "Saved provider result (not a Combined answer):\n\n${answer.ifBlank { "The provider returned no answer text." }}"
                }
                else -> "The remote job is still pending. Queue position: ${result.text("queue_position") ?: "unknown"}. No duplicate generation was submitted."
            }
        }
    }

    private suspend fun checkedJsonArray(response: HttpResponse, config: ProviderRequestConfig, model: String): JsonArray {
        if (!response.status.isSuccess()) checkedJson(response, config, model)
        return readQueueJson(response) as? JsonArray ?: error("QUEUE_WORKERS_UNAVAILABLE: Invalid worker catalog")
    }

    private suspend fun deliver(answer: String, model: String?, provider: FreeAiProvider, emit: suspend (ChatCompletionChunk) -> Unit, verifiedGeneration: Boolean = true) {
        if (verifiedGeneration) FreeCatalogState.recordResponse(provider, model)
        emit(ChatCompletionChunk(model = model, choices = listOf(Choice(delta = Delta(content = answer), finishReason = "queue_complete"))))
    }

    private suspend fun checkedJson(response: HttpResponse, config: ProviderRequestConfig, model: String): JsonObject {
        val provider = requireNotNull(config.freeProvider)
        if (!response.status.isSuccess()) {
            val body = readFreeErrorBody(response)
            val retryAt = retainFreeHttpFailure(config, model, response.status.value, body) { response.headers[it] }
            val seconds = retryAt?.let { ((it - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(1) }
            val message = freeHttpErrorMessage(response.status.value, body, config.token)
            FreeCatalogState.recordHttpFailure(provider, response.status.value, message, retryAt, model, body)
            dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, model, message, response.status.value, body, { response.headers[it] }, retryAt)
            if (seconds != null) throw FreeAiRateLimitException(provider, seconds, cooldown = config.freeDiagnostics?.cooldown, localBlock = false)
            error("${provider.displayName}: $message No automatic resubmission was attempted.")
        }
        return readQueueJson(response) as? JsonObject ?: error("Invalid queue response")
    }

    private suspend fun readQueueJson(response: HttpResponse): JsonElement {
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
        return Json.parseToJsonElement(body)
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

    private suspend fun validateGradioSchema(config: ProviderRequestConfig, model: String): GradioInputContract {
        val layout = checkedJson(client.get("${FreeAiProvider.HUGGING_FACE.apiUrl}/config"), config, model)
        val info = checkedJson(client.get("${FreeAiProvider.HUGGING_FACE.apiUrl}/gradio_api/info"), config, model)
        return GradioInputContract.discover(layout, info)
    }

    // Bounded, lenient EOF: readLine has no size limit; readLineStrict rejects the final partial line.
    @Suppress("DEPRECATION")
    private suspend fun readGradio(id: String, config: ProviderRequestConfig, model: String, emit: suspend (ChatCompletionChunk) -> Unit): Pair<String, String?> {
        var final: String? = null
        var snapshot: String? = null
        var diagnostic: String? = null
        var retrievalFailure: java.io.IOException? = null
        fun generationFailed(envelope: String, event: String): Nothing {
            config.freeDiagnostics?.stage = "generation"
            config.freeDiagnostics?.category = FreeFailureCategory.HOSTED_GENERATION_FAILED
            val detail = gradioErrorDetails(envelope, config.token)
            val message = "Space generation failed: $detail This job cannot resume; start a new generation later or choose another Free provider."
            dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.record(config, model, message, body = envelope, event = event, terminal = true)
            throw QueueTerminalFailure(message)
        }
        client.prepareGet("${FreeAiProvider.HUGGING_FACE.apiUrl}/gradio_api/call/add_message/$id") {
            timeout {
                requestTimeoutMillis = null
                socketTimeoutMillis = 60_000
            }
        }.execute { response ->
            config.freeDiagnostics?.responded()
            config.freeDiagnostics?.stage = "stream_retrieval"
            if (!response.status.isSuccess()) checkedJson(response, config, model)
            val channel = response.bodyAsChannel()
            var event = ""
            val data = StringBuilder()
            var lastNotice = 0L
            while (true) {
                currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
                val nextLine = try {
                    channel.readUTF8Line(2_000_000)
                } catch (failure: java.io.IOException) {
                    // Preserve already parsed output on a socket failure as well as EOF.
                    // Only read errors are intercepted; consumer failures and Stop propagate.
                    retrievalFailure = failure
                    break
                }
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
                        val envelope = gradioErrorEnvelope(data.toString()).takeUnless { it == "{}" } ?: diagnostic?.let { buildJsonObject { put("message", it) }.toString() } ?: "{}"
                        generationFailed(envelope, event)
                    }
                    config.freeDiagnostics?.progress = event.take(40)
                    if (event in setOf("generating", "complete")) {
                        val record = Json.parseToJsonElement(data.toString())
                        qwenDiagnostic(record, config.token)?.let { diagnostic = it }
                        // The Space renders upstream exceptions into an assistant bubble before
                        // reporting failure. That diagnostic is never answer text, even when a
                        // later transport event says complete or the stream ends unexpectedly.
                        if (diagnostic == null) gradioAnswerOrNull(record)?.let { snapshot = it }
                    }
                    if (event == "complete") {
                        diagnostic?.let { generationFailed(buildJsonObject { put("message", it) }.toString(), event) }
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
        diagnostic?.let { generationFailed(buildJsonObject { put("message", it) }.toString(), "provider_diagnostic") }
        if (final == null) {
            snapshot?.takeIf { it.isNotBlank() && diagnostic == null }?.let { emit(ChatCompletionChunk(choices = listOf(Choice(delta = Delta(content = it))))) }
            retrievalFailure?.let { throw it }
            throw QueueTerminalFailure("QUEUE_STREAM_DISCONNECTED: Space stream ended before completion. Any partial answer and the receipt are retained. This protocol cannot guarantee reconnect; explicitly start a new generation.", remoteTerminal = null)
        }
        return requireNotNull(final) to model
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
        internal fun gradioPayload(request: ChatCompletionRequest, prompt: String, contract: GradioInputContract? = null): JsonObject = buildJsonObject {
            val settings = buildJsonObject {
                put("model", request.model)
                put("sys_prompt", "You are a helpful assistant.")
                put("thinking_budget", 1)
            }
            // The two public values are used only for the stable local receipt identity.
            // Dispatch always supplies a discovered contract.
            put("data", contract?.inputs(JsonPrimitive(prompt), settings) ?: JsonArray(listOf(JsonPrimitive(prompt), settings)))
        }
        internal fun gradioAnswer(data: JsonElement): String = gradioAnswerOrNull(data) ?: error("Space returned no assistant answer")
        internal fun gradioAnswerOrNull(data: JsonElement): String? {
            val outputs = data as? JsonArray ?: error("Invalid Space completion record")
            // Gradio hides State components from its public API; output positions can vary.
            val answer = outputs.mapNotNull { if (it is JsonObject) it["value"] as? JsonArray else it as? JsonArray }
                .flatMap { it.filterIsInstance<JsonObject>() }.lastOrNull { it.text("role") in setOf("assistant", "user") }
                ?.takeIf { it.text("role") == "assistant" } ?: return null
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

internal class QueueTerminalFailure(message: String, val remoteTerminal: Boolean? = true) : IllegalStateException("QUEUE_TERMINAL: $message")
