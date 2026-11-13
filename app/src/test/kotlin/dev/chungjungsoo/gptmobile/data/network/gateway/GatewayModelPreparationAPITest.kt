package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayModelPreparationAPITest {
    private val config = ProviderRequestConfig("https://selected-gateway.example/v1", "selected-token", profileUid = "selected-profile")
    private fun receipt(model: String = "qwen3.5-9b", operation: String = "selection-1", extra: String = "") =
        """{"schema":"gptmobile.gateway.model-preparation.v1","operationId":"$operation","requestedModel":"$model","state":"WAITING_FOR_MODEL","dispatchState":"DISPATCHED","loadAccepted":true,"loadRequests":1,"modelState":"LOADING"$extra}"""

    @Test fun sendsExactModelAndOperationOnlyToSelectedAuthenticatedGateway() = runBlocking {
        var posts = 0
        HttpClient(
            MockEngine { request ->
                posts++
                assertEquals("POST", request.method.value)
                assertEquals("selected-gateway.example", request.url.host)
                assertEquals("/v1/gateway/models/prepare", request.url.encodedPath)
                assertEquals("Bearer selected-token", request.headers["Authorization"])
                assertEquals("selection-1", request.headers["Idempotency-Key"])
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals(setOf("model", "operationId"), body.keys)
                assertEquals("qwen3.5-9b", body["model"]?.jsonPrimitive?.content)
                assertEquals("selection-1", body["operationId"]?.jsonPrimitive?.content)
                respond(receipt(), HttpStatusCode.Accepted)
            }
        ).use { client ->
            val network = mockk<NetworkClient>()
            every { network() } returns client
            val result = GatewayModelPreparationAPI(network).prepare(config, "qwen3.5-9b", "selection-1")
            assertEquals("LOADING", result.modelState)
            assertFalse(result.inferenceTested)
        }
        assertEquals(1, posts)
    }

    @Test fun wrongModelOperationOrSubstitutionReceiptCannotQualifySelection() = runBlocking {
        for (body in listOf(receipt(model = "wrong-model"), receipt(operation = "wrong-operation"), receipt(extra = ",\"automaticSubstitution\":true"), receipt(extra = ",\"reasoningSettingsChanged\":true"))) {
            var calls = 0
            HttpClient(
                MockEngine {
                    calls++
                    respond(body)
                }
            ).use { client ->
                val network = mockk<NetworkClient>()
                every { network() } returns client
                val result = runCatching { GatewayModelPreparationAPI(network).prepare(config, "qwen3.5-9b", "selection-1") }
                assertEquals(true, result.isFailure)
            }
            assertEquals(1, calls)
        }
    }

    @Test fun failedTransportNeverReplaysThePreparationPost() = runBlocking {
        var calls = 0
        HttpClient(
            MockEngine {
                calls++
                throw IOException("lost response")
            }
        ).use { client ->
            val network = mockk<NetworkClient>()
            every { network() } returns client
            val result = runCatching { GatewayModelPreparationAPI(network).prepare(config, "qwen3.5-9b", "selection-1") }
            assertEquals(true, result.isFailure)
        }
        assertEquals(1, calls)
    }

    @Test fun nestedBackendRejectionKeepsTypedCauseButDropsUpstreamProse() = runBlocking {
        HttpClient(
            MockEngine {
                respond(
                    receipt(extra = """, "preparationAction":"WAKE", "failure":{"reasonCode":"BACKEND_METADATA_DENIED","failureCode":"EXECUTION_METADATA_DENIED","failureCategory":"denied","dependency":"selected_model","upstreamStatus":403,"upstreamMessageCode":"INVALID_API_KEY","upstreamMessage":"private token and path"},"reconciliationFailure":{"reasonCode":"private reason","failureCode":"private code","upstreamStatus":999}""")
                )
            }
        ).use { client ->
            val network = mockk<NetworkClient>()
            every { network() } returns client
            val result = GatewayModelPreparationAPI(network).prepare(config, "qwen3.5-9b", "selection-1")
            assertEquals("WAKE", result.preparationAction)
            assertEquals("BACKEND_METADATA_DENIED", result.failure?.reasonCode)
            assertEquals(403, result.failure?.upstreamStatus)
            assertEquals("INVALID_API_KEY", result.failure?.upstreamMessageCode)
            assertNull(result.reconciliationFailure?.reasonCode)
            assertNull(result.reconciliationFailure?.upstreamStatus)
            assertTrue(result.rejectionMessage().contains("authorization"))
            assertFalse(result.toString().contains("private"))
        }
    }
}
