package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GatewayDiagnosticTransportTest {
    private val config = ProviderRequestConfig("https://paired.example/v1", "credential", extraHeaders = mapOf("X-Api-Key" to "custom-auth", "X-Gateway-Management" to "forbidden", "X-Gateway-Allow-Local-Tools" to "true", "traceparent" to "private"))
    private fun batch() = buildJsonObject {
        put("schema_version", 2)
        put("session_id", "a".repeat(32))
        put("capture_started_at_utc", 1L)
        put("events", JsonArray(emptyList()))
        put("gaps", JsonArray(emptyList()))
    }
    private fun http(engine: MockEngine) = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout)
    }

    @Test fun actualHttpTransportSendsOnlyScopedAuthAndUsesBoundedOwnerSessionCursor() = runTest {
        var requests = 0
        http(
            MockEngine { request ->
                requests++
                assertEquals("https://paired.example/v1/gateway/debug/events", request.url.toString().substringBefore('?'))
                assertEquals("Bearer credential", request.headers["Authorization"])
                assertEquals("custom-auth", request.headers["X-Api-Key"])
                assertNull(request.headers["X-Gateway-Management"])
                assertNull(request.headers["X-Gateway-Allow-Local-Tools"])
                assertNull(request.headers["traceparent"])
                if (request.method.value == "POST") {
                    val body = (request.body as TextContent).text
                    assertEquals(batch(), Json.parseToJsonElement(body))
                    assertTrue(body.toByteArray().size <= 65536)
                    assertFalse(body.contains("credential"))
                    respond("{\"schema_version\":2,\"storage_state\":\"committed\"}", HttpStatusCode.OK)
                } else {
                    assertEquals("a".repeat(32), request.url.parameters["session_id"])
                    assertEquals("17", request.url.parameters["after"])
                    assertEquals("64", request.url.parameters["limit"])
                    assertEquals("b".repeat(32), request.url.parameters["exclude_origin"])
                    respond("{\"schema_version\":2,\"events\":[]}", HttpStatusCode.OK)
                }
            }
        ).use { client ->
            val transport = GatewayDiagnosticHttpTransport(client, config) { true }
            transport.post(batch())
            transport.read("a".repeat(32), 17, "b".repeat(32))
        }
        assertEquals(2, requests)
    }

    @Test fun privacyDisabledBeforePhysicalDispatchMakesNoHttpCall() = runTest {
        var requests = 0
        http(
            MockEngine {
                requests++
                respond("{}", HttpStatusCode.OK)
            }
        ).use { client ->
            val transport = GatewayDiagnosticHttpTransport(client, config) { false }
            try {
                transport.post(batch())
                fail("Expected privacy cancellation")
            } catch (_: CancellationException) { }
            try {
                transport.read("a".repeat(32), 0, "b".repeat(32))
                fail("Expected privacy cancellation")
            } catch (_: CancellationException) { }
        }
        assertEquals(0, requests)
    }

    @Test fun serverErrorsCannotLeakBodiesOrExceptionSecretsAndDoNotTriggerAutomaticMutationRetry() = runTest {
        var requests = 0
        http(
            MockEngine {
                requests++
                respond("Bearer secret; https://private/?token=secret", HttpStatusCode.ServiceUnavailable)
            }
        ).use { client ->
            try {
                GatewayDiagnosticHttpTransport(client, config) { true }.post(batch())
                fail("Expected unavailable storage")
            } catch (failure: DiagnosticSyncFailure) {
                assertEquals("DEBUG_STORAGE_UNAVAILABLE", failure.code)
                assertFalse(failure.toString().contains("secret"))
            }
        }
        assertEquals(1, requests)
    }

    @Test fun cancellationDuringResponseReadPropagatesWithoutBecomingAProtocolFailure() = runTest {
        val stream = ByteChannel(autoFlush = true)
        val reading = CompletableDeferred<Unit>()
        val responseBody = object : ByteReadChannel by stream {
            override suspend fun awaitContent(min: Int): Boolean {
                reading.complete(Unit)
                return stream.awaitContent(min)
            }
        }
        val gate = GatewayDiagnosticRetryGate { 0L }
        var requests = 0
        http(
            MockEngine {
                requests++
                respond(responseBody, HttpStatusCode.OK)
            }
        ).use { client ->
            val result = async {
                try {
                    gate.run({ true }) { allowed -> GatewayDiagnosticHttpTransport(client, config, allowed).post(batch()) }
                    false
                } catch (_: CancellationException) {
                    true
                }
            }
            reading.await()
            stream.cancel(CancellationException("Capture cancelled while reading"))
            val cancellationEscaped = result.await()
            assertTrue("Cancellation must propagate without changing retry state: ${gate.snapshot()}", cancellationEscaped)
        }
        assertEquals(1, requests)
        assertEquals(GatewayDiagnosticRetryState.READY, gate.snapshot().state)
        assertNull(gate.snapshot().error)
        assertEquals(0, gate.snapshot().failures)
    }

    @Test fun oversizedUploadAndDownloadAreRejectedBeforeUnboundedJsonHydration() = runTest {
        var requests = 0
        http(
            MockEngine {
                requests++
                respond("x".repeat(128 * 1024 + 1), HttpStatusCode.OK)
            }
        ).use { client ->
            val transport = GatewayDiagnosticHttpTransport(client, config) { true }
            val oversized = buildJsonObject { put("padding", "x".repeat(65536)) }
            try {
                transport.post(oversized)
                fail("Expected bounded upload")
            } catch (failure: DiagnosticSyncFailure) {
                assertEquals("DIAGNOSTIC_BATCH_LIMIT", failure.code)
            }
            assertEquals(0, requests)
            try {
                transport.read("a".repeat(32), 0, "b".repeat(32))
                fail("Expected bounded response")
            } catch (failure: DiagnosticSyncFailure) {
                assertEquals("DIAGNOSTIC_RESPONSE_LIMIT", failure.code)
            }
        }
        assertEquals(1, requests)
    }
}
