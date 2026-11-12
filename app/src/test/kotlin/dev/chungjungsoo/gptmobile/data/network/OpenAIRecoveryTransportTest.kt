package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseCompletedEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseGatewayMetadataEvent
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIRecoveryTransportTest {
    @Test fun secondBackendRejectionRetainsPriorAttemptsWithoutReplayOrNestedCauseReclassification() = runTest {
        for (stream in listOf(false, true)) {
            for (status in listOf(429, 503)) {
                for (measured in listOf(false, true)) {
                    var requests = 0
                    val usage = if (measured) """, "usage_origin":"PROVIDER_REPORTED","usage_estimated":false,"completed_attempts":1,"failed_attempt":2,"failed_attempt_usage_available":false,"attempt_usage":[{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19}]""" else ""
                    val body = """{"error":{"code":"$status","message":"Backend unavailable","gateway_recovery":{"schema_version":1,"attempt_receipts":[{"attempt":1,"output_allowance":50,"finish_reason":"LENGTH","visible_output_bytes":0,"reasoning_output_bytes":11,"terminal_error_code":"empty_completion","terminal_status_code":200,"backend_response_observed":true,"status":"COMPLETED"},{"attempt":2,"output_allowance":43,"finish_reason":"UNKNOWN","visible_output_bytes":null,"reasoning_output_bytes":null,"terminal_error_code":"backend_http_error","terminal_status_code":$status,"backend_response_observed":true,"status":"FAILED"}]$usage}}}"""
                    withApi(
                        MockEngine {
                            requests++
                            respond(if (stream) "data: $body\n\n" else body, if (stream) HttpStatusCode.OK else HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, if (stream) "text/event-stream" else "application/json"))
                        }
                    ) { api ->
                        withContext(ProviderWaitContext(durableRateLimits = true) {}) {
                            val error = api.streamChatCompletion(request, 5, config).toList().mapNotNull { it.error }.single()
                            assertEquals(1, requests)
                            assertEquals(status.toString(), error.code)
                            assertEquals(false, error.retryable)
                            assertFalse(error.providerLimit?.knownNotAccepted == true)
                            val details = requireNotNull(error.failureDetails)
                            assertEquals(kotlinx.serialization.json.JsonPrimitive(false), details["request_replay_allowed"])
                            val recovery = details.getValue("gateway_recovery").jsonObject
                            val receipts = recovery.getValue("attempt_receipts") as kotlinx.serialization.json.JsonArray
                            assertEquals(2, receipts.size)
                            assertEquals(kotlinx.serialization.json.JsonPrimitive(11), receipts.first().jsonObject["reasoning_output_bytes"])
                            val known = dev.chungjungsoo.gptmobile.data.accounting.GatewayRecoveryUsage.from(details)
                            assertEquals(if (measured) 7 else null, known?.outputTokens)
                            assertFalse(known?.outputComplete == true)
                        }
                    }
                }
            }
        }
    }

    @org.junit.Before fun resetConnectivity() = LocalServiceHealth.networkChanged()

    @org.junit.After fun clearConnectivity() = LocalServiceHealth.networkChanged()

    // Gateway identity survives network epochs; independent mock routes must not inherit it.
    private val fixtureHost = "recovery-${java.util.UUID.randomUUID()}.fixture.invalid"
    private val config = ProviderRequestConfig("https://$fixtureHost/v1/", null)
    private val request = ChatCompletionRequest(model = "fixture", messages = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage(role = dev.chungjungsoo.gptmobile.data.dto.openai.common.Role.USER, content = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent("Transport fixture request.")))))

    @Test fun knownDeviceOutageRetainsTypedPrecheckEvidenceWithoutProviderDispatch() = runTest {
        DeviceNetworkDiagnostics.episodes.networkChanged("test-disconnected", "DISCONNECTED")
        var calls = 0
        try {
            for (knownGateway in listOf(false, true)) {
                val selected = config.copy(apiUrl = "https://outage-$knownGateway.$fixtureHost/v1/")
                if (knownGateway) dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics.observeInstance(selected, "a".repeat(32))
                withApi(
                    MockEngine {
                        calls++
                        error("No request should be sent")
                    }
                ) { api ->
                    val error = requireNotNull(api.streamChatCompletion(request, 5, selected).toList().last().error)
                    assertEquals(0, calls)
                    assertEquals("NETWORK_DISCONNECTED", error.code)
                    val failure = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider(error.message, error.code, error.failureDetails)
                    assertEquals(false, failure.dispatched)
                    assertEquals("provider_admission", failure.phase)
                    val input = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage("user", dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent.text("Saved prompt")))
                    val responseError = api.streamResponses(ResponsesRequest(model = "fixture", input = input), 5, selected).toList()
                        .filterIsInstance<dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent>().single()
                    val providerFailure = dev.chungjungsoo.gptmobile.data.agent.provider.OpenAIResponsesEventAssembler().accept(responseError).single() as dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Failed
                    val responseFailure = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider(providerFailure.message, providerFailure.errorCode, providerFailure.failureDetails)
                    assertEquals(0, calls)
                    assertEquals(false, responseFailure.dispatched)
                    assertEquals("provider_admission", responseFailure.phase)
                }
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
                val isolated = config.copy(apiUrl = "https://http-${status.value}-$responses.$fixtureHost/v1/")
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

    @Test fun durableRateLimitKeepsTheServerDeadlineAndBlocksTheNextRequestOnThatCredential() = runTest {
        var attempts = 0
        val selected = config.copy(profileUid = "selected", providerType = "OPENROUTER")
        val start = System.currentTimeMillis()
        withApi(
            MockEngine {
                attempts++
                respond("""{"error":{"code":429,"type":"rate_limit_exceeded","message":"Wait for capacity"}}""", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "600"))
            }
        ) { api ->
            withContext(ProviderWaitContext(durableRateLimits = true) {}) {
                val rejection = requireNotNull(api.streamChatCompletion(request, 5, selected).toList().last().error?.providerLimit)
                assertEquals("RETRY_AFTER", rejection.retrySource)
                assertTrue(rejection.knownNotAccepted)
                assertTrue(rejection.retryAt >= start + 600_000)
                val input = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage("user", dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent.text("Saved prompt")))
                val cooldown = api.streamResponses(ResponsesRequest(model = "fixture", input = input), 5, selected).toList()
                    .filterIsInstance<dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent>().single()
                assertEquals("SHARED_COOLDOWN", cooldown.providerLimit?.retrySource)
                assertTrue(requireNotNull(cooldown.providerLimit).retryAt >= start + 600_000)
                assertEquals(1, attempts)
            }
        }
    }

    @Test fun rateLimitFrameAfterTextKeepsThePrefixAndRequiresACheckpoint() = runTest {
        var attempts = 0
        val body = """
            data: {"choices":[{"delta":{"content":"Saved prefix"}}]}

            data: {"error":{"code":429,"message":"Provider capacity"}}

        """.trimIndent()
        withApi(
            MockEngine {
                attempts++
                respond(body, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        ) { api ->
            withContext(ProviderWaitContext(durableRateLimits = true) {}) {
                val chunks = api.streamChatCompletion(request, 5, config.copy(profileUid = "selected")).toList()
                assertEquals("Saved prefix", chunks.flatMap { it.choices.orEmpty() }.single().effectiveDelta.content)
                val decision = requireNotNull(chunks.last().error?.providerLimit)
                assertTrue(decision.checkpointContinuationAllowed)
                assertFalse(decision.knownNotAccepted)
                assertFalse(decision.mayWait)
                assertEquals("APP_BACKOFF", decision.retrySource)
                assertEquals(1, attempts)
            }
        }
    }

    @Test fun knownHttpFailureDoesNotOverrideAnAmbiguousGatewayMutation() = runTest {
        for (responses in listOf(false, true)) {
            var attempts = 0
            val gatewayConfig = config.copy(apiUrl = "https://http-gateway-$responses.$fixtureHost/v1/", extraHeaders = mapOf("X-Gateway-Allow-Local-Tools" to "true"))
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
                // The mock engine was entered, but no HTTP response or transport
                // cause was observed. Do not conflate that with non-dispatch.
                assertEquals(kotlinx.serialization.json.JsonPrimitive(true), details?.get("dispatched"))
                assertEquals(kotlinx.serialization.json.JsonPrimitive("UNKNOWN"), details?.get("remote_acceptance"))
                assertEquals(kotlinx.serialization.json.JsonPrimitive(false), details?.get("logical_continuation_allowed"))
                assertFalse(requireNotNull(details).containsKey("failure_kind"))
                assertFalse(details.containsKey("http_status"))
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
            assertEquals(4, events.size)
            assertInitialResponsesHeaders(events, 400)
            assertInitialResponsesHeaders(events.drop(1), 200)
            val observed = events.filterIsInstance<ResponseGatewayMetadataEvent>()
            assertEquals(listOf(400, 200), observed.mapNotNull { it.providerResponseTiming?.httpStatus })
            val route = observed.single { it.metadata != null }
            assertEquals(2, events.indexOf(route))
            assertFalse(requireNotNull(route.metadata).transportVerified)
            assertTrue(events.last() is ResponseCompletedEvent)
            assertEquals(2, bodies.size)
            val second = NetworkClient.openAIJson.parseToJsonElement(bodies.last()).jsonObject
            assertFalse(second.containsKey("temperature"))
            assertTrue(second.containsKey("max_output_tokens"))
        }
    }

    @Test
    fun `accepted transient stream failures never replay with or without tool activity`() = runTest {
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
                assertEquals(1, attempts)
                assertInitialChatHeaders(chunks, 200)
                val failure = chunks.single { it.error != null }.error!!
                assertTrue(chunks.mapNotNull { it.retryAttempts }.isEmpty())
                assertTrue(chunks.flatMap { it.choices.orEmpty() }.isEmpty())
                assertEquals(if (toolStarted) 1 else 0, chunks.count { it.gatewayProgress != null })
                assertTrue(chunks.all { it.reasoningControlReceipt != null && it.providerResponseTiming?.responseHeadersMs != null })
                if (!toolStarted) {
                    assertEquals("CONNECTION_ABORTED", failure.code)
                    assertEquals(kotlinx.serialization.json.JsonPrimitive("YES"), failure.failureDetails?.get("remote_acceptance"))
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(false), failure.failureDetails?.get("request_replay_allowed"))
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(true), failure.failureDetails?.get("logical_continuation_allowed"))
                } else {
                    assertEquals("GATEWAY_OUTCOME_UNKNOWN", failure.code)
                    assertEquals(false, failure.retryable)
                    assertEquals(kotlinx.serialization.json.JsonPrimitive("YES"), failure.failureDetails?.get("remote_acceptance"))
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(true), failure.failureDetails?.get("requires_reconciliation"))
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(false), failure.failureDetails?.get("request_replay_allowed"))
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(false), failure.failureDetails?.get("logical_continuation_allowed"))
                }
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

    @Test fun acceptedChatErrorsWithoutProgressReconcileOnlyWhenServerToolsWereAllowed() = runTest {
        for (serverTools in listOf(false, true)) {
            for (transport in listOf("sse", "buffered", "json-for-stream")) {
                var dispatches = 0
                val payload = """{"error":{"message":"Fixture backend failed","code":"backend_failed","retryable":false,"failed_stage":"precheck","dispatched":false,"dispatch_state":"PRECHECK_REJECTED"}}"""
                val requestId = "chat-$serverTools-$transport"
                withApi(
                    MockEngine { incoming ->
                        if (incoming.url.encodedPath.endsWith("gateway/capabilities")) {
                            respond("""{"gatewayVersion":"15.0.0","accepted_request_headers":["X-Gateway-Allow-Local-Tools"]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        } else {
                            dispatches++
                            respond(
                                if (transport == "sse") "data: $payload\n\ndata: [DONE]\n\n" else payload,
                                headers = headersOf(
                                    HttpHeaders.ContentType to listOf(if (transport == "sse") "text/event-stream" else "application/json"),
                                    "X-Request-ID" to listOf(requestId),
                                    "X-Generation-ID" to listOf("generation-$requestId")
                                )
                            )
                        }
                    }
                ) { api ->
                    val selected = config.copy(extraHeaders = if (serverTools) mapOf("X-Gateway-Allow-Local-Tools" to "true") else emptyMap())
                    val chunks = api.streamChatCompletion(request.copy(stream = transport != "buffered"), 5, selected).toList()
                    assertInitialChatHeaders(chunks, 200)
                    assertEquals(1, dispatches)
                    assertTrue(chunks.mapNotNull { it.retryAttempts }.isEmpty())
                    val failure = chunks.single { it.error != null }.error!!
                    assertTrue(failure.message.contains("Fixture backend failed"))
                    if (serverTools) {
                        assertUncertainAcceptedFailure(failure.code, failure.failureDetails, requestId)
                        assertEquals(kotlinx.serialization.json.JsonPrimitive("backend_failed"), failure.failureDetails?.get("upstream_error_code"))
                        assertRetainedServerPrecheck(failure.failureDetails)
                    } else {
                        assertEquals("backend_failed", failure.code)
                        assertFalse(failure.failureDetails?.get("requires_reconciliation") == kotlinx.serialization.json.JsonPrimitive(true))
                    }
                }
            }
        }
    }

    @Test fun configuredServerToolsRetainATransientServerReportBeforeAnyProgress() = runTest {
        var dispatches = 0
        withApi(
            MockEngine { incoming ->
                if (incoming.url.encodedPath.endsWith("gateway/capabilities")) {
                    respond("""{"gatewayVersion":"15.0.0","accepted_request_headers":["X-Gateway-Allow-Local-Tools"]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    dispatches++
                    respond(
                        "data: {\"error\":{\"message\":\"Software caused connection abort\",\"code\":\"backend_failed\",\"retryable\":true}}\n\ndata: [DONE]\n\n",
                        headers = headersOf(HttpHeaders.ContentType to listOf("text/event-stream"), "X-Request-ID" to listOf("server-report"), "X-Generation-ID" to listOf("generation-server-report"))
                    )
                }
            }
        ) { api ->
            val chunks = api.streamChatCompletion(request, 5, config.copy(extraHeaders = mapOf("X-Gateway-Allow-Local-Tools" to "true"))).toList()
            assertEquals(1, dispatches)
            val failure = chunks.single { it.error != null }.error!!
            assertUncertainAcceptedFailure(failure.code, failure.failureDetails, "server-report")
            assertTrue(failure.message.contains("Software caused connection abort"))
            assertEquals(kotlinx.serialization.json.JsonPrimitive("backend_failed"), failure.failureDetails?.get("upstream_error_code"))
        }
    }

    @Test fun toolActivityInTheSameErrorFrameIsObservedBeforeTransientErrorClassification() = runTest {
        var dispatches = 0
        withApi(
            MockEngine {
                dispatches++
                respond(
                    "data: {\"gateway_progress\":{\"event\":\"tool_started\"},\"error\":{\"message\":\"Software caused connection abort\"}}\n\ndata: [DONE]\n\n",
                    headers = headersOf(HttpHeaders.ContentType to listOf("text/event-stream"), "X-Request-ID" to listOf("combined-frame"), "X-Generation-ID" to listOf("generation-combined-frame"))
                )
            }
        ) { api ->
            val chunks = api.streamChatCompletion(request, 5, config).toList()
            assertEquals(1, dispatches)
            val failure = chunks.single { it.error != null }.error!!
            assertUncertainAcceptedFailure(failure.code, failure.failureDetails, "combined-frame")
            assertTrue(failure.message.contains("Software caused connection abort"))
        }
    }

    @Test fun acceptedResponsesErrorAndFailedEnvelopesRetainServerToolUncertaintyAndOriginalCode() = runTest {
        for (serverTools in listOf(false, true)) {
            for (type in listOf("error", "response.failed")) {
                var dispatches = 0
                val requestId = "responses-$serverTools-${type.replace('.', '-')}"
                val payload = if (type == "error") {
                    """{"type":"error","message":"Fixture backend failed","code":"backend_failed","failed_stage":"precheck","dispatched":false,"dispatch_state":"PRECHECK_REJECTED"}"""
                } else {
                    """{"type":"response.failed","response":{"id":"response-1","status":"failed","error":{"message":"Fixture backend failed","code":"backend_failed","failed_stage":"precheck","dispatched":false,"dispatch_state":"PRECHECK_REJECTED"}}}"""
                }
                withApi(
                    MockEngine { incoming ->
                        if (incoming.url.encodedPath.endsWith("gateway/capabilities")) {
                            respond("""{"gatewayVersion":"15.0.0","accepted_request_headers":["X-Gateway-Allow-Local-Tools"]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        } else {
                            dispatches++
                            respond(
                                "data: $payload\n\n",
                                headers = headersOf(HttpHeaders.ContentType to listOf("text/event-stream"), "X-Request-ID" to listOf(requestId), "X-Generation-ID" to listOf("generation-$requestId"))
                            )
                        }
                    }
                ) { api ->
                    val selected = config.copy(extraHeaders = if (serverTools) mapOf("X-Gateway-Allow-Local-Tools" to "true") else emptyMap())
                    val input = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage("user", dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent.text("Use the existing results.")))
                    val events = api.streamResponses(ResponsesRequest(model = "fixture", input = input), 5, selected).toList()
                    assertInitialResponsesHeaders(events, 200)
                    assertEquals(1, dispatches)
                    val assembler = dev.chungjungsoo.gptmobile.data.agent.provider.OpenAIResponsesEventAssembler()
                    val failure = events.flatMap(assembler::accept).filterIsInstance<dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Failed>().single()
                    assertTrue(failure.message.contains("Fixture backend failed"))
                    if (serverTools) {
                        assertUncertainAcceptedFailure(failure.errorCode, failure.failureDetails, requestId)
                        assertEquals(kotlinx.serialization.json.JsonPrimitive("backend_failed"), failure.failureDetails?.get("upstream_error_code"))
                        assertRetainedServerPrecheck(failure.failureDetails)
                    } else {
                        assertEquals("backend_failed", failure.errorCode)
                        assertFalse(failure.failureDetails?.get("requires_reconciliation") == kotlinx.serialization.json.JsonPrimitive(true))
                    }
                }
            }
        }
    }

    private fun assertUncertainAcceptedFailure(code: String?, details: kotlinx.serialization.json.JsonObject?, requestId: String) {
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", code)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("YES"), details?.get("remote_acceptance"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(requestId), details?.get("server_request_id"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive("generation-$requestId"), details?.get("generation_id"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), details?.get("requires_reconciliation"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), details?.get("request_replay_allowed"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), details?.get("logical_continuation_allowed"))
        assertFalse(requireNotNull(details).containsKey("cause_types"))
        val failure = dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Failed("Remote operation must be reconciled", errorCode = code, failureDetails = details)
        assertEquals(null, dev.chungjungsoo.gptmobile.data.agent.GitHubTaskRecovery.delayMillis(failure, 0, false))
        val classified = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider(failure.message, code, details)
        assertFalse(classified.retryAllowed)
        assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.YES, classified.remoteAcceptance)
        assertEquals(requestId, classified.serverRequestId)
        assertEquals(false, classified.generationCompleted)
        assertEquals(false, classified.requestReplayAllowed)
        val receipt = dev.chungjungsoo.gptmobile.data.diagnostics.ProviderFailureReceipt.from(
            dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "profile", name = "Selected", model = "fixture"),
            "primary",
            failure
        )
        assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.YES, receipt.remoteAcceptance)
        assertEquals(requestId, receipt.serverRequestId)
        assertEquals("generation-$requestId", receipt.generationId)
        assertEquals(false, receipt.requestReplayAllowed)
        assertEquals(false, receipt.logicalContinuationAllowed)
    }

    private fun assertRetainedServerPrecheck(details: kotlinx.serialization.json.JsonObject?) {
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), details?.get("dispatched"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), details?.get("upstream_dispatched"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive("precheck"), details?.get("upstream_failed_stage"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive("PRECHECK_REJECTED"), details?.get("dispatch_state"))
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
            assertTrue(chunks.single { it.choices != null }.gatewayMetadata?.transportVerified == true)
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
                assertEquals(kind, "TERMINAL_JOB_NOT_RESUMABLE", chunks.last().error?.code)
                assertEquals(kind, kotlinx.serialization.json.JsonPrimitive("TERMINAL_JOB_NOT_RESUMABLE"), chunks.last().error?.failureDetails?.get("recovery_code"))
                assertEquals(kind, kotlinx.serialization.json.JsonPrimitive(409), chunks.last().error?.failureDetails?.get("recovery_http_status"))
                assertEquals(kind, false, chunks.last().error?.retryable)
                assertTrue(kind, chunks.none { it.choices.orEmpty().any { choice -> !choice.effectiveDelta.toolCalls.isNullOrEmpty() || choice.finishReason == "stop" } })
                assertEquals(kind, kind in setOf("partial", "failed", "tool_calls"), chunks.any { chunk -> chunk.choices.orEmpty().any { !it.effectiveDelta.content.isNullOrBlank() } })
            }
        }
    }

    @Test fun gatewayObservationsRequireHeaderProofAndRequestSnapshotSurvivesBothResponseFormats() = runTest {
        for (stream in listOf(false, true)) {
            for (verified in listOf(false, true)) {
                val provenance = """"gateway_provenance":{"schema_version":1,"origin":"BACKEND_RESPONSE","requested_model":"fixture","reported_model":"fixture","model_identity":"MATCHED","http_status":200},"gateway_timing":{"measurement_scope":"physical_backend_request","backend_first_output_ms":5,"delivery_mode":"completion_buffered"},"gateway_delivery":{"measurement_scope":"gateway_asgi_stream","delivery_mode":"completion_buffered","first_event_submit_ms":1,"first_event_send_completed_ms":2}"""
                val packet = if (stream) """{"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}],$provenance}""" else """{"choices":[{"message":{"content":"answer"},"finish_reason":"stop"}],$provenance}"""
                val body = if (stream) "data: $packet\n\ndata: [DONE]\n\n" else packet
                val headers = io.ktor.http.Headers.build {
                    append(HttpHeaders.ContentType, if (stream) "text/event-stream" else "application/json")
                    if (verified) append("X-Gateway-Instance-ID", "a".repeat(32))
                }
                withApi(MockEngine { respond(body, headers = headers) }) { api ->
                    val selected = config.copy(apiUrl = "https://stream-$stream-verified-$verified.$fixtureHost/v1/", profileRevision = "b".repeat(64), configuredProfileReasoning = false)
                    val chunks = api.streamChatCompletion(request.copy(stream = stream), 5, selected).toList()
                    assertEquals("answer", chunks.flatMap { it.choices.orEmpty() }.mapNotNull { it.effectiveDelta.content }.joinToString(""))
                    assertEquals(verified, chunks.any { it.gatewayProvenance != null })
                    assertEquals(verified, chunks.any { it.gatewayTiming != null })
                    assertEquals(verified, chunks.any { it.gatewayDeliveryTiming != null })
                    assertTrue(chunks.any { it.providerResponseTiming?.firstParsedPayloadMs != null })
                    assertTrue(chunks.any { it.reasoningControlReceipt?.profileRevision == "b".repeat(64) && it.reasoningControlReceipt?.configuredProfileReasoning == false })
                    assertTrue(chunks.none { it.error != null })
                }
            }
        }
    }

    private suspend fun withApi(engine: MockEngine, block: suspend (OpenAIAPI) -> Unit) {
        val client = HttpClient(engine) { install(HttpTimeout) }
        val wrapper = mockk<NetworkClient>()
        every { wrapper() } returns client
        every { wrapper.gatewayReadClient() } returns client
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
