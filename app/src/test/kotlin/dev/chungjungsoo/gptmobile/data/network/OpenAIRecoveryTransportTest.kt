package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseCompletedEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import java.net.UnknownHostException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIRecoveryTransportTest {
    @org.junit.Before fun resetConnectivity() = LocalServiceHealth.networkChanged()

    @org.junit.After fun clearConnectivity() = LocalServiceHealth.networkChanged()

    private val config = ProviderRequestConfig("https://fixture.invalid/v1/", null)
    private val request = ChatCompletionRequest(model = "fixture", messages = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage(role = dev.chungjungsoo.gptmobile.data.dto.openai.common.Role.USER, content = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent("Transport fixture request.")))))

    @Test fun knownDeviceOutageRetainsTypedPrecheckEvidenceWithoutProviderDispatch() = runTest {
        DeviceNetworkDiagnostics.episodes.networkChanged("test-disconnected", "DISCONNECTED")
        var calls = 0
        try {
            withApi(
                MockEngine {
                    calls++
                    error("No request should be sent")
                }
            ) { api ->
                val error = requireNotNull(api.streamChatCompletion(request, 5, config).toList().last().error)
                assertEquals(0, calls)
                assertEquals("NETWORK_DISCONNECTED", error.code)
                val failure = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider(error.message, error.code, error.failureDetails)
                assertEquals(false, failure.dispatched)
                assertEquals("provider_admission", failure.phase)
                val input = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage("user", dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent.text("Saved prompt")))
                val responseError = api.streamResponses(ResponsesRequest(model = "fixture", input = input), 5, config).toList()
                    .filterIsInstance<dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent>().single()
                val providerFailure = dev.chungjungsoo.gptmobile.data.agent.provider.OpenAIResponsesEventAssembler().accept(responseError).single() as dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Failed
                val responseFailure = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider(providerFailure.message, providerFailure.errorCode, providerFailure.failureDetails)
                assertEquals(0, calls)
                assertEquals(false, responseFailure.dispatched)
                assertEquals("provider_admission", responseFailure.phase)
            }
        } finally {
            DeviceNetworkDiagnostics.episodes.networkChanged(null, "UNKNOWN")
        }
    }

    @Test
    fun `transient DNS failure gets bounded retries before primary handoff fails`() = runTest {
        var attempts = 0
        val engine = MockEngine {
            if (++attempts < 3) throw UnknownHostException("Unable to resolve host openrouter.ai")
            respond("""{"choices":[{"message":{"content":"recovered"}}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        withApi(engine) { api ->
            val chunks = api.streamChatCompletion(request, 5, config).toList()
            assertEquals(3, attempts)
            assertEquals("recovered", chunks.single { it.choices != null }.choices!!.single().effectiveDelta.content)
            assertEquals(listOf(1, 2), chunks.mapNotNull { it.retryAttempts })
            assertTrue(chunks.filter { it.retryAttempts != null }.all { (it.retryDurationMs ?: -1) >= 0 })
            assertTrue(chunks.none { it.error != null })
        }
    }

    @Test
    fun `permanent DNS outage stops after three attempts`() = runTest {
        var attempts = 0
        withApi(
            MockEngine {
                attempts++
                throw UnknownHostException("Unable to resolve host openrouter.ai")
            }
        ) { api ->
            val chunks = api.streamChatCompletion(request, 5, config).toList()
            assertEquals("DNS_RESOLUTION_FAILED", chunks.last().error?.code)
            assertEquals("dns", chunks.last().error?.failureDetails?.get("failed_stage")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
            assertEquals(1, chunks.count { it.error != null })
            assertEquals(listOf(1, 2), chunks.mapNotNull { it.retryAttempts })
            assertTrue(chunks.none { it.choices != null })
            assertEquals(3, attempts)
        }
    }

    @Test
    fun `partial stream ends as transport error and is never replayed`() = runTest {
        var attempts = 0
        withApi(
            MockEngine {
                attempts++
                respond("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        ) { api ->
            val chunks = api.streamChatCompletion(request, 5, config).toList()
            assertEquals(1, attempts)
            assertEquals("CONNECTION_ABORTED", chunks.last().error?.code)
            assertTrue(chunks.last().error?.message.orEmpty().contains("partial response has been retained"))
            assertEquals("partial", chunks.flatMap { it.choices.orEmpty() }.mapNotNull { it.effectiveDelta.content }.joinToString(""))
            assertEquals(false, chunks.last().error?.retryable)
            assertFalse(chunks.any { it.streamFinished })
        }
    }

    @Test fun boundedHttpRetriesRetainKnownProviderFailureInBothTransports() = runTest {
        for (responses in listOf(false, true)) {
            for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
                var attempts = 0
                val isolated = config.copy(apiUrl = "https://http-${status.value}-$responses.fixture.invalid/v1/")
                withApi(
                    MockEngine {
                        attempts++
                        respond("""{"error":{"message":"Temporarily unavailable"}}""", status, headersOf(HttpHeaders.RetryAfter, "0"))
                    }
                ) { api ->
                    val code: String?
                    val details: kotlinx.serialization.json.JsonObject?
                    if (responses) {
                        val input = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage("user", dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent.text("Saved prompt")))
                        val failure = api.streamResponses(ResponsesRequest(model = "fixture", input = input), 5, isolated).toList()
                            .filterIsInstance<dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent>().single()
                        code = failure.code
                        details = failure.failureDetails
                    } else {
                        val failure = requireNotNull(api.streamChatCompletion(request, 5, isolated).toList().last().error)
                        code = failure.code
                        details = failure.failureDetails
                        assertEquals(true, failure.retryable)
                        assertEquals("http_error", failure.type)
                    }
                    assertEquals(3, attempts)
                    assertEquals(status.value.toString(), code)
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(status.value), details?.get("http_status"))
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(0), details?.get("retry_after_ms"))
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(true), details?.get("retryable"))
                    assertEquals(kotlinx.serialization.json.JsonPrimitive("model_invocation"), details?.get("failed_stage"))
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(true), details?.get("dispatched"))
                }
            }
        }
    }

    @Test fun knownHttpFailureDoesNotOverrideAnAmbiguousGatewayMutation() = runTest {
        for (responses in listOf(false, true)) {
            var attempts = 0
            val gatewayConfig = config.copy(apiUrl = "https://http-gateway-$responses.fixture.invalid/v1/", extraHeaders = mapOf("X-Gateway-Allow-Local-Tools" to "true"))
            withApi(
                MockEngine { incoming ->
                    if (incoming.url.encodedPath.endsWith("gateway/capabilities")) {
                        respond("""{"gatewayVersion":"15.0.0","accepted_request_headers":["X-Gateway-Allow-Local-Tools"]}""")
                    } else {
                        attempts++
                        respond("""{"error":{"message":"Temporarily unavailable"}}""", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.RetryAfter, "0"))
                    }
                }
            ) { api ->
                if (responses) {
                    val input = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage("user", dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent.text("Saved prompt")))
                    val failure = api.streamResponses(ResponsesRequest(model = "fixture", input = input), 5, gatewayConfig).toList()
                        .filterIsInstance<dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent>().single()
                    assertEquals("GATEWAY_OUTCOME_UNKNOWN", failure.code)
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(false), failure.failureDetails?.get("replay_safe"))
                } else {
                    val failure = requireNotNull(api.streamChatCompletion(request, 5, gatewayConfig).toList().last().error)
                    assertEquals("GATEWAY_OUTCOME_UNKNOWN", failure.code)
                    assertEquals(false, failure.retryable)
                }
                assertEquals(1, attempts)
            }
        }
    }

    @Test fun unclassifiedProviderExceptionDoesNotInventATransportStage() = runTest {
        for (responses in listOf(false, true)) {
            withApi(MockEngine { throw IllegalStateException("Fixture provider preparation failed") }) { api ->
                val code: String?
                val details: kotlinx.serialization.json.JsonObject?
                if (responses) {
                    val input = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage("user", dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent.text("Saved prompt")))
                    val failure = api.streamResponses(ResponsesRequest(model = "fixture", input = input), 5, config).toList()
                        .filterIsInstance<dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent>().single()
                    code = failure.code
                    details = failure.failureDetails
                } else {
                    val failure = requireNotNull(api.streamChatCompletion(request, 5, config).toList().last().error)
                    code = failure.code
                    details = failure.failureDetails
                    assertEquals("provider_error", failure.type)
                }
                assertEquals("PROVIDER_INVOCATION_FAILED", code)
                assertEquals(kotlinx.serialization.json.JsonPrimitive("stage_not_recorded"), details?.get("failed_stage"))
                assertEquals(kotlinx.serialization.json.JsonPrimitive(false), details?.get("retryable"))
                assertFalse(requireNotNull(details).containsKey("dispatched"))
            }
        }
    }

    @Test
    fun `responses capability rejection removes only unsupported sampling and retries`() = runTest {
        val bodies = mutableListOf<String>()
        withApi(
            MockEngine { incoming ->
                bodies += (incoming.body as TextContent).text
                if (bodies.size == 1) {
                    respond("""{"error":{"message":"Unsupported parameter: 'temperature' is not supported with this model."}}""", HttpStatusCode.BadRequest)
                } else {
                    respond("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"status\":\"completed\"}}\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
                }
            }
        ) { api ->
            val events = api.streamResponses(ResponsesRequest(model = "fixture", input = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage("user", dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent.text("Transport fixture request."))), temperature = .4f, maxOutputTokens = 128), 5, config).toList()
            assertTrue(events.single() is ResponseCompletedEvent)
            assertEquals(2, bodies.size)
            val second = NetworkClient.openAIJson.parseToJsonElement(bodies.last()).jsonObject
            assertFalse(second.containsKey("temperature"))
            assertTrue(second.containsKey("max_output_tokens"))
        }
    }

    @Test
    fun `gateway transient error retries before output but never after gateway tool execution`() = runTest {
        for (toolStarted in listOf(false, true)) {
            var attempts = 0
            withApi(
                MockEngine {
                    attempts++
                    val body = if (attempts == 1) {
                        val progress = if (toolStarted) "data: {\"gateway_progress\":{\"event\":\"tool_started\"}}\n\n" else ""
                        progress + "data: {\"error\":{\"message\":\"Software caused connection abort\"}}\n\ndata: [DONE]\n\n"
                    } else {
                        "data: {\"choices\":[{\"delta\":{\"content\":\"recovered\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
                }
            ) { api ->
                val chunks = api.streamChatCompletion(request, 5, config).toList()
                assertEquals(if (toolStarted) 1 else 2, attempts)
                assertEquals(toolStarted, chunks.any { it.error != null })
            }
        }
    }

    @Test fun gatewayToolRequestIsNotReplayedWhenConnectionClosesBeforeAnyProgress() = runTest {
        for (responses in listOf(false, true)) {
            var dispatches = 0
            withApi(
                MockEngine { incoming ->
                    if (incoming.url.encodedPath.endsWith("gateway/capabilities")) {
                        respond("""{"gatewayVersion":"15.0.0","accepted_request_headers":["X-Gateway-Allow-Local-Tools"]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        dispatches++
                        throw java.io.EOFException("Connection closed before its first stream frame")
                    }
                }
            ) { api ->
                val gatewayConfig = config.copy(extraHeaders = mapOf("X-Gateway-Allow-Local-Tools" to "true"))
                if (responses) {
                    val input = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage("user", dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent.text("Check the repository.")))
                    val events = api.streamResponses(ResponsesRequest(model = "fixture", input = input), 5, gatewayConfig).toList()
                    assertEquals("GATEWAY_OUTCOME_UNKNOWN", events.filterIsInstance<dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent>().single().code)
                } else {
                    val chunks = api.streamChatCompletion(request, 5, gatewayConfig).toList()
                    val error = requireNotNull(chunks.last().error)
                    assertEquals("GATEWAY_OUTCOME_UNKNOWN", error.code)
                    assertEquals(false, error.retryable)
                    assertEquals("unknown", error.failureDetails?.get("outcome")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
                }
                assertEquals(1, dispatches)
            }
        }
    }

    @Test fun samplingRejectionAfterPossibleGatewayMutationDoesNotReplayEitherPhysicalTransport() = runTest {
        for (responses in listOf(false, true)) {
            for (serverTools in listOf(false, true)) {
                val bodies = mutableListOf<String>()
                withApi(
                    MockEngine { incoming ->
                        if (incoming.url.encodedPath.endsWith("gateway/capabilities")) {
                            respond("""{"gatewayVersion":"15.0.0","accepted_request_headers":["X-Gateway-Allow-Local-Tools"]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        } else {
                            assertEquals(serverTools.toString(), incoming.headers["X-Gateway-Allow-Local-Tools"])
                            bodies += (incoming.body as TextContent).text
                            if (bodies.size == 1) {
                                respond("""{"error":{"message":"Unsupported parameter: 'temperature' is not supported with this model."}}""", HttpStatusCode.BadRequest)
                            } else if (responses) {
                                respond("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"status\":\"completed\"}}\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
                            } else {
                                respond("""{"choices":[{"message":{"content":"recovered"},"finish_reason":"stop"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                            }
                        }
                    }
                ) { api ->
                    val gatewayConfig = config.copy(extraHeaders = mapOf("X-Gateway-Allow-Local-Tools" to serverTools.toString()))
                    if (responses) {
                        val input = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage("user", dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent.text("Check repository state.")))
                        val events = api.streamResponses(ResponsesRequest(model = "fixture", input = input, temperature = .4f, maxOutputTokens = 128), 5, gatewayConfig).toList()
                        assertEquals(serverTools, events.any { it is dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent })
                    } else {
                        val chunks = api.streamChatCompletion(request.copy(temperature = .4f, maxTokens = 128), 5, gatewayConfig).toList()
                        assertEquals(serverTools, chunks.any { it.error != null })
                    }
                    assertEquals(if (serverTools) 1 else 2, bodies.size)
                    val first = NetworkClient.openAIJson.parseToJsonElement(bodies.first()).jsonObject
                    assertTrue(first.containsKey("temperature"))
                    if (!serverTools) assertFalse(NetworkClient.openAIJson.parseToJsonElement(bodies.last()).jsonObject.containsKey("temperature"))
                    bodies.forEach { assertEquals("\"fixture\"", NetworkClient.openAIJson.parseToJsonElement(it).jsonObject["model"].toString()) }
                }
            }
        }
    }

    @Test fun initialGatewayDiscoveryFailureNeverDispatchesAnUnisolatedModelRequest() = runTest {
        for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.Forbidden)) {
            val paths = mutableListOf<String>()
            withApi(
                MockEngine { incoming ->
                    paths += incoming.url.encodedPath
                    respond("unavailable", status)
                }
            ) { api ->
                val isolated = config.copy(extraHeaders = mapOf("X-Gateway-Delegated-Worker" to "true", "X-Gateway-Allow-Local-Tools" to "false"))
                val chunks = api.streamChatCompletion(request, 5, isolated).toList()
                assertTrue(chunks.last().error?.message.orEmpty().contains("GATEWAY_CAPABILITY_UNAVAILABLE"))
                assertEquals(listOf("/v1/gateway/capabilities"), paths)
            }
        }
    }

    @Test fun terminalJobConflictReadsItsSavedResultOnceWithoutRepeatingInferenceOrTools() = runTest {
        val paths = mutableListOf<String>()
        val gatewayConfig = config.copy(extraHeaders = mapOf("X-Gateway-Job-ID" to "saved-job", "X-Gateway-Allow-Local-Tools" to "true"))
        withApi(
            MockEngine { incoming ->
                paths += incoming.url.encodedPath
                when {
                    incoming.url.encodedPath.endsWith("gateway/capabilities") -> respond("""{"gatewayVersion":"15.0.0","accepted_request_headers":["X-Gateway-Job-ID","X-Gateway-Allow-Local-Tools"]}""")
                    incoming.url.encodedPath.endsWith("chat/completions") -> respond("""{"error":{"code":"terminal_job_not_resumable","message":"Job already ended","job_id":"saved-job","dispatch_state":"not_dispatched"}}""", HttpStatusCode.Conflict)
                    else -> {
                        assertEquals("/v1/gateway/jobs/saved-job/result", incoming.url.encodedPath)
                        assertEquals(io.ktor.http.HttpMethod.Get, incoming.method)
                        respond("""{"job_id":"saved-job","status":"completed","content":"Retained answer","result_quality":"complete","completed_at":1793844300.182,"job":{"model":"fixture"},"result":{"model":"fixture","choices":[{"message":{"content":"Retained answer"},"finish_reason":"stop"}]}}""")
                    }
                }
            }
        ) { api ->
            val chunks = api.streamChatCompletion(request, 5, gatewayConfig).toList()
            assertEquals("Retained answer", chunks.single { it.choices != null }.choices!!.single().effectiveDelta.content)
            assertTrue(chunks.none { it.error != null })
            assertEquals("saved-job", chunks.single { it.choices != null }.gatewayMetadata?.jobId)
            assertEquals("completed", chunks.single { it.choices != null }.gatewayMetadata?.terminalStatus)
            assertEquals(listOf("/v1/gateway/capabilities", "/v1/chat/completions", "/v1/gateway/jobs/saved-job/result"), paths)
        }
    }

    @Test fun incompleteFailedMismatchedOrToolOnlyTerminalReceiptsNeverReplayWork() = runTest {
        for (kind in listOf("partial", "failed", "tool_calls", "model_changed", "wrong_job", "missing")) {
            var posts = 0
            var reads = 0
            withApi(
                MockEngine { incoming ->
                    when {
                        incoming.url.encodedPath.endsWith("gateway/capabilities") -> respond("""{"gatewayVersion":"15.0.0","accepted_request_headers":["X-Gateway-Job-ID","X-Gateway-Allow-Local-Tools"]}""")
                        incoming.url.encodedPath.endsWith("chat/completions") -> {
                            posts++
                            respond("""{"error":{"code":"terminal_job_not_resumable","message":"Job already ended","job_id":"saved-job"}}""", HttpStatusCode.Conflict)
                        }
                        else -> {
                            reads++
                            assertEquals(io.ktor.http.HttpMethod.Get, incoming.method)
                            val tool = if (kind == "tool_calls") """, "tool_calls":[{"id":"already-issued","type":"function","function":{"name":"mutate","arguments":"{}"}}]""" else ""
                            val status = if (kind == "failed") "failed" else "completed"
                            val quality = if (kind == "partial") "partial" else "complete"
                            val model = if (kind == "model_changed") "other-model" else "fixture"
                            val id = if (kind == "wrong_job") "unrelated-job" else "saved-job"
                            respond("""{"job_id":"$id","status":"$status","content":"Retained text","result_quality":"$quality","result":{"model":"$model","choices":[{"message":{"content":"Retained text"$tool}}]}}""", if (kind == "missing") HttpStatusCode.NotFound else HttpStatusCode.OK)
                        }
                    }
                }
            ) { api ->
                val chunks = api.streamChatCompletion(request, 5, config.copy(extraHeaders = mapOf("X-Gateway-Job-ID" to "saved-job", "X-Gateway-Allow-Local-Tools" to "true"))).toList()
                assertEquals(kind, 1, posts)
                assertEquals(kind, 1, reads)
                assertEquals(kind, "terminal_job_not_resumable", chunks.last().error?.code)
                assertEquals(kind, false, chunks.last().error?.retryable)
                assertTrue(kind, chunks.none { it.choices.orEmpty().any { choice -> !choice.effectiveDelta.toolCalls.isNullOrEmpty() || choice.finishReason == "stop" } })
                assertEquals(kind, kind in setOf("partial", "failed", "tool_calls"), chunks.any { chunk -> chunk.choices.orEmpty().any { !it.effectiveDelta.content.isNullOrBlank() } })
            }
        }
    }

    private suspend fun withApi(engine: MockEngine, block: suspend (OpenAIAPI) -> Unit) {
        val client = HttpClient(engine) { install(HttpTimeout) }
        val wrapper = mockk<NetworkClient>()
        every { wrapper() } returns client
        try {
            block(OpenAIAPIImpl(wrapper))
        } finally {
            client.close()
        }
    }

    @Test fun malformedChunkAfterUsableTextDegradesTheStreamAndDoesNotCommitTools() = runTest {
        val body = """
            data: {"choices":[{"delta":{"content":"Retained answer."}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call","type":"function","function":{"name":"lookup","arguments":"{}"}}]}}]}

            data: {malformed

            data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

            data: [DONE]

        """.trimIndent()
        withApi(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "text/event-stream")) }) { api ->
            val chunks = api.streamChatCompletion(request, 5, config).toList()
            assertEquals("Retained answer.", chunks.flatMap { it.choices.orEmpty() }.mapNotNull { it.effectiveDelta.content }.joinToString(""))
            assertEquals("STREAM_DEGRADED", chunks.last().error?.code)
            assertFalse(chunks.any { it.streamFinished })
            assertFalse(chunks.flatMap { it.choices.orEmpty() }.any { it.finishReason == "tool_calls" })
        }
    }
}
