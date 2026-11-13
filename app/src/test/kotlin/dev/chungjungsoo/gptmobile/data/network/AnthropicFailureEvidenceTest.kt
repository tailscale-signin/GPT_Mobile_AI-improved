package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.provider.AnthropicEventAssembler
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.MessageRole
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.InputMessage
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.MessageRequest
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ErrorResponseChunk
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicFailureEvidenceTest {
    @Test fun invalidRequestRetainsTypedParameterDiagnosticsThroughProviderAssembly() = runBlocking {
        withApi(
            MockEngine {
                respond(
                    """{"type":"error","error":{"type":"invalid_request_error","param":"messages[0].private-field","message":"private rejection detail"}}""",
                    HttpStatusCode.BadRequest,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        ) { api ->
            val error = api.streamChatMessage(request(), 5, config()).single() as ErrorResponseChunk
            val failed = AnthropicEventAssembler().accept(error).single() as ProviderEvent.Failed
            assertEquals("400", failed.errorCode)
            assertEquals(JsonPrimitive("INVALID_REQUEST"), failed.failureDetails?.get("provider_rejection_category"))
            assertEquals(JsonPrimitive("ANTHROPIC_MESSAGES"), failed.failureDetails?.get("payload_contract"))
            assertEquals(JsonPrimitive("messages"), failed.failureDetails?.get("provider_parameter"))
            assertTrue(failed.failureDetails?.containsKey("provider_parameter_path_hash") == true)
            assertTrue(failed.failureDetails?.containsKey("provider_error_type_hash") == true)
            assertFalse(failed.failureDetails.toString().contains("private"))
        }
    }

    @Test fun insufficientCreditsAreDistinguishedFromOtherBadRequests() = runBlocking {
        withApi(
            MockEngine {
                respond(
                    """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API. Private account details."}}""",
                    HttpStatusCode.BadRequest
                )
            }
        ) { api ->
            val error = api.streamChatMessage(request(), 5, config()).single() as ErrorResponseChunk
            val failed = AnthropicEventAssembler().accept(error).single() as ProviderEvent.Failed
            assertEquals(JsonPrimitive("BILLING_UNAVAILABLE"), failed.failureDetails?.get("provider_rejection_category"))
            assertFalse(failed.failureDetails.toString().contains("Private account details"))
        }
    }

    @Test fun httpRejectionRetainsStatusThroughAssemblyWithoutPublishingProviderTextOrRequestId() = runBlocking {
        val privateText = "Private account detail from provider"
        val requestId = "req_private_fixture"
        withApi(
            MockEngine {
                respond(
                    """{"type":"error","error":{"type":"authentication_error","message":"$privateText"}}""",
                    HttpStatusCode.Unauthorized,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"), "request-id" to listOf(requestId))
                )
            }
        ) { api ->
            val error = api.streamChatMessage(request(), 5, config()).single() as ErrorResponseChunk
            val failed = AnthropicEventAssembler().accept(error).single() as ProviderEvent.Failed
            assertEquals("401", failed.errorCode)
            assertEquals(JsonPrimitive(401), failed.failureDetails?.get("http_status"))
            assertEquals(JsonPrimitive(true), failed.failureDetails?.get("dispatched"))
            assertEquals(JsonPrimitive("model_invocation"), failed.failureDetails?.get("failed_stage"))
            assertEquals(JsonPrimitive(requestId), failed.failureDetails?.get("server_request_id"))
            assertNull(failed.failureDetails?.get("remote_acceptance"))
            val context = IssueReportContext.fromFields("CHAT_FAILURE", requireNotNull(failed.failureDetails) + ("provider_kind" to "ANTHROPIC"))
            assertEquals(401, context.httpStatus)
            assertEquals(true, context.dispatched)
            assertEquals("model_invocation", context.stage)
            val public = IssueReport.json.encodeToString(IssueReportContext.serializer(), context)
            assertFalse(public.contains(privateText))
            assertFalse(public.contains(requestId))
        }
    }

    @Test fun streamErrorRetainsActualHttp200AndRejectsProviderSuppliedDispatchEvidence() = runBlocking {
        val body = """data: {"type":"error","error":{"type":"overloaded_error","message":"Temporary provider error","code":"429","failureDetails":{"http_status":429,"dispatched":false,"remote_acceptance":"NO","server_request_id":"forged"}}}

        """.trimIndent()
        withApi(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "text/event-stream")) }) { api ->
            val error = api.streamChatMessage(request(), 5, config()).single() as ErrorResponseChunk
            assertEquals("overloaded_error", error.error.code)
            assertEquals(JsonPrimitive(200), error.error.failureDetails?.get("http_status"))
            assertEquals(JsonPrimitive(true), error.error.failureDetails?.get("dispatched"))
            assertEquals(JsonPrimitive("overloaded_error"), error.error.failureDetails?.get("provider_error_type"))
            assertNull(error.error.failureDetails?.get("remote_acceptance"))
            assertNull(error.error.failureDetails?.get("server_request_id"))
        }
    }

    @Test fun transientHttpExceptionKeepsResponseStatusAndSafeRequestIdentity() = runBlocking {
        withApi(
            MockEngine {
                respond("{}", HttpStatusCode(529, "Overloaded"), headersOf("request-id", "req_overload_fixture"))
            }
        ) { api ->
            val error = api.streamChatMessage(request(), 5, config()).single() as ErrorResponseChunk
            assertEquals("529", error.error.code)
            assertEquals(JsonPrimitive(529), error.error.failureDetails?.get("http_status"))
            assertEquals(JsonPrimitive("req_overload_fixture"), error.error.failureDetails?.get("server_request_id"))
            assertEquals(JsonPrimitive(true), error.error.failureDetails?.get("dispatched"))
        }
    }

    @Test fun failuresWithoutAResponseNeverInventHttpStatusOrDispatch() = runBlocking {
        for ((exception, expectedCode) in listOf(
            SSLHandshakeException("private endpoint certificate") to "TLS_FAILURE",
            IllegalStateException("private preparation detail") to "PROVIDER_INVOCATION_FAILED"
        )) {
            var requests = 0
            withApi(
                MockEngine {
                    requests++
                    throw exception
                }
            ) { api ->
                val error = api.streamChatMessage(request(), 5, config()).single() as ErrorResponseChunk
                assertEquals(expectedCode, error.error.code)
                assertEquals(1, requests)
                assertNull(error.error.failureDetails?.get("http_status"))
                assertNull(error.error.failureDetails?.get("dispatched"))
                if (expectedCode == "PROVIDER_INVOCATION_FAILED") {
                    assertEquals(JsonPrimitive("model_invocation"), error.error.failureDetails?.get("failed_stage"))
                    assertEquals(JsonPrimitive("UNKNOWN"), error.error.failureDetails?.get("remote_acceptance"))
                    assertEquals(JsonPrimitive(false), error.error.failureDetails?.get("retryable"))
                    assertEquals(JsonPrimitive(false), error.error.failureDetails?.get("request_replay_allowed"))
                    assertEquals(JsonPrimitive(false), error.error.failureDetails?.get("logical_continuation_allowed"))
                } else {
                    assertNull(error.error.failureDetails?.get("remote_acceptance"))
                }
                assertTrue(error.error.failureDetails?.containsKey("cause_types") == true)
                assertFalse(error.error.failureDetails.toString().contains("private"))
            }
        }
    }

    private fun request() = MessageRequest(
        model = "claude-sonnet-4-5",
        messages = listOf(InputMessage(MessageRole.USER, listOf(TextContent("Fixture prompt")))),
        maxTokens = 100,
        stream = true
    )

    private fun config() = ProviderRequestConfig("https://provider.example/v1", token = UUID.randomUUID().toString())

    private suspend fun withApi(engine: MockEngine, block: suspend (AnthropicAPI) -> Unit) {
        val client = HttpClient(engine) { install(HttpTimeout) }
        val wrapper = mockk<NetworkClient>()
        every { wrapper() } returns client
        every { wrapper.gatewayReadClient() } returns client
        try {
            block(AnthropicAPIImpl(wrapper))
        } finally {
            client.close()
        }
    }
}
