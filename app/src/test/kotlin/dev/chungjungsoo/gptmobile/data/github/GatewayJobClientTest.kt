package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayJobClientTest {
    private val config = ProviderRequestConfig("http://192.168.1.9:8090/v1", null)

    @Test fun overlappingIdentityReadsShareOnlyTheSameAuthenticatedInFlightObservation() = runBlocking {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        HttpClient(
            MockEngine {
                calls++
                if (calls == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                respond("""{"schema_version":1,"protocol_version":1,"gateway_id":"canonical","principal_id":"owner"}""")
            }
        ) { install(HttpTimeout) }.use { http ->
            val client = GatewayJobClient(http)
            val first = async { client.identity(config) }
            entered.await()
            val overlapping = async { client.identity(config) }
            yield()
            release.complete(Unit)
            assertEquals(first.await(), overlapping.await())
            assertEquals(1, calls)
            client.identity(config)
            assertEquals(2, calls)
            client.identity(config.copy(token = "changed-test-authority"))
            assertEquals(3, calls)
        }
    }

    @Test fun canonicalIdentityReadBindsBothMutationHeadersAndReplacesStaleCaseVariants() = runBlocking {
        val stale = config.copy(extraHeaders = mapOf("x-gateway-installation-id" to "stale", "X-GATEWAY-PRINCIPAL-ID" to "other", "X-Custom" to "retained"))
        HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/v1/gateway/identity", request.url.encodedPath)
                respond("""{"schema_version":1,"protocol_version":1,"gateway_id":"canonical-journal","principal_id":"authenticated-owner"}""")
            }
        ) { install(HttpTimeout) }.use { http ->
            val bound = stale.withGatewayIdentity(GatewayJobClient(http).identity(config))
            assertEquals(mapOf("X-Custom" to "retained", "X-Gateway-Installation-ID" to "canonical-journal", "X-Gateway-Principal-ID" to "authenticated-owner"), bound.extraHeaders)
        }
    }

    @Test fun catalogAndTranscriptAttachAreReadOnlyAndKeepIndependentOpaqueCursors() = runBlocking {
        val requests = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                requests += request.url.toString()
                when {
                    request.url.encodedPath.endsWith("job-catalog") -> {
                        assertEquals("catalog+/=token", request.url.parameters["cursor"])
                        respond("""{"schema_version":1,"gateway_id":"gw_a","principal_id":"network:shared","server_now":123,"items":[],"complete":true}""")
                    }
                    request.url.encodedPath.endsWith("messages") -> {
                        assertEquals("message+/=token", request.url.parameters["after_cursor"])
                        respond("""{"schema_version":1,"gateway_id":"gw_a","principal_id":"network:shared","conversation_id":"conv_a","revision":7,"messages":[],"next_cursor":"next"}""")
                    }
                    else -> respond("""{"schema_version":1,"gateway_id":"gw_a","principal_id":"network:shared","conversation_id":"conv_a","revision":7}""")
                }
            }
        ) { install(HttpTimeout) }.use { http ->
            val client = GatewayJobClient(http)
            client.catalog(config, "catalog+/=token")
            client.conversation(config, "conv_a")
            client.messages(config, "conv_a", "message+/=token")
        }
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.contains("/v1/gateway/") })
    }

    @Test fun legacyMissingRoutesDoNotTryToCreateOrResumeAJob() = runBlocking {
        var calls = 0
        HttpClient(
            MockEngine {
                calls++
                assertEquals(HttpMethod.Get, it.method)
                respond("missing", HttpStatusCode.NotFound)
            }
        ) { install(HttpTimeout) }.use { http ->
            assertTrue(runCatching { GatewayJobClient(http).catalog(config) }.exceptionOrNull() is GatewayHistoryUnsupported)
        }
        assertEquals(1, calls)
    }

    @Test fun nonAdvancingTranscriptPageIsRejected() = runBlocking {
        HttpClient(MockEngine { respond("""{"schema_version":1,"gateway_id":"gw_a","principal_id":"owner","conversation_id":"conv_a","revision":7,"messages":[],"has_more":true,"next_cursor":"same"}""") }) { install(HttpTimeout) }.use { http ->
            assertTrue(runCatching { GatewayJobClient(http).messages(config, "conv_a", "same") }.isFailure)
        }
    }

    @Test fun pendingAdmissionReceiptLookupIsReadOnlyAndMissingReceiptRemainsUnknown() = runBlocking {
        var calls = 0
        HttpClient(
            MockEngine { request ->
                calls++
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/v1/gateway/commands/persisted-command", request.url.encodedPath)
                assertEquals("true", request.url.parameters["include_outcome"])
                respond("Unknown command", HttpStatusCode.NotFound)
            }
        ) { install(HttpTimeout) }.use { http ->
            assertEquals(null, GatewayJobClient(http).command(config, "persisted-command"))
        }
        assertEquals(1, calls)
    }

    @Test fun durableRejectionRequiresOriginalCommandIdentityAndProofOfNoDispatch() = runBlocking {
        val outcome = GatewayCommandRejection(1, "gateway", "owner", "command", "engineering", "rejected", "EXECUTION_MODEL_LOADING", 409, "provider_admission", "not_dispatched", false)
        for (candidate in listOf(outcome, outcome.copy(commandId = "other-command"), outcome.copy(inferenceAttempted = true), outcome.copy(dispatchState = "unknown"))) {
            HttpClient(
                MockEngine { request ->
                    assertEquals(HttpMethod.Get, request.method)
                    respond(Json.encodeToString(candidate))
                }
            ) { install(HttpTimeout) }.use { http ->
                val client = GatewayJobClient(http)
                val failure = runCatching { client.command(config, "command") }.exceptionOrNull()
                val passiveFailure = runCatching { client.observeCommand(config, "command") }.exceptionOrNull()
                if (candidate == outcome) {
                    assertTrue(failure is GatewayCommandRejected)
                    assertTrue(passiveFailure is GatewayCommandRejected)
                    assertEquals(outcome, (failure as GatewayCommandRejected).outcome)
                    assertEquals("EXECUTION_MODEL_LOADING", failure.errorCode)
                    assertEquals("provider_admission", failure.failedStage)
                } else {
                    assertTrue(failure is GatewayAdmissionException)
                    assertTrue(passiveFailure is GatewayAdmissionException)
                    assertEquals(GatewayAdmissionReason.INVALID_RESPONSE, (failure as GatewayAdmissionException).reason)
                }
            }
        }
    }

    @Test fun passiveMissingCommandsBackOffToOneMinuteWithoutDispatchingOrForgettingThem() = runBlocking {
        var now = 0L
        var calls = 0
        HttpClient(
            MockEngine { request ->
                calls++
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/v1/gateway/commands/persisted-command", request.url.encodedPath)
                assertEquals("true", request.url.parameters["include_outcome"])
                respond("Unknown command", HttpStatusCode.NotFound)
            }
        ) { install(HttpTimeout) }.use { http ->
            val client = GatewayJobClient(http, now = { now })
            for ((at, expected) in listOf(0L to 1, 4_999L to 1, 5_000L to 2, 14_999L to 2, 15_000L to 3, 34_999L to 3, 35_000L to 4, 74_999L to 4, 75_000L to 5, 134_999L to 5, 135_000L to 6)) {
                now = at
                assertEquals(null, client.observeCommand(config, "persisted-command"))
                assertEquals("Polling at $at", expected, calls)
            }
        }
    }

    @Test fun explicitReconciliationBypassesAbsenceAndAcceptedReceiptsResetObserverBackoff() = runBlocking {
        var now = 0L
        var calls = 0
        var accepted = false
        HttpClient(
            MockEngine { request ->
                calls++
                assertEquals(HttpMethod.Get, request.method)
                if (accepted) {
                    respond("""{"schema_version":1,"gateway_id":"gateway","principal_id":"owner","command_id":"saved","conversation_id":"conversation","job_kind":"generation","job_id":"job"}""")
                } else {
                    respond("Unknown command", HttpStatusCode.NotFound)
                }
            }
        ) { install(HttpTimeout) }.use { http ->
            val client = GatewayJobClient(http, now = { now })
            assertEquals(null, client.observeCommand(config, "saved"))
            accepted = true
            assertEquals("job", client.command(config, "saved")?.jobId)
            assertEquals(2, calls)
            assertEquals("job", client.observeCommand(config, "saved")?.jobId)
            assertEquals(2, calls)
            accepted = false
            assertEquals("job", client.observeCommand(config, "saved")?.jobId)
            assertEquals(2, calls)
            assertEquals(null, client.command(config, "saved"))
            assertEquals(3, calls)
            now = 5_000
            assertEquals(null, client.observeCommand(config, "saved"))
            assertEquals(4, calls)
        }
    }

    @Test fun concurrentPassiveObserversReuseAcceptedBindingsWithABoundedLifetime() = runBlocking {
        var now = 0L
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        HttpClient(
            MockEngine {
                calls++
                entered.complete(Unit)
                release.await()
                respond("""{"schema_version":1,"gateway_id":"gateway","principal_id":"owner","command_id":"saved","conversation_id":"conversation","job_kind":"generation","job_id":"job"}""")
            }
        ) { install(HttpTimeout) }.use { http ->
            val client = GatewayJobClient(http, now = { now })
            val chat = async { client.observeCommand(config, "saved") }
            entered.await()
            val jobs = async { client.observeCommand(config, "saved") }
            val monitor = async { client.observeCommand(config, "saved") }
            release.complete(Unit)
            assertEquals(listOf("job", "job", "job"), listOf(chat.await(), jobs.await(), monitor.await()).map { it?.jobId })
            assertEquals(1, calls)
            now = 29_999L
            assertEquals("job", client.observeCommand(config, "saved")?.jobId)
            assertEquals(1, calls)
            now = 30_000L
            assertEquals("job", client.observeCommand(config, "saved")?.jobId)
            assertEquals(2, calls)
        }
    }

    @Test fun passiveCommandAbsenceNeverCrossesEndpointCredentialsPrincipalOrCommandIdentity() = runBlocking {
        var calls = 0
        HttpClient(
            MockEngine {
                calls++
                respond("Unknown command", HttpStatusCode.NotFound)
            }
        ) { install(HttpTimeout) }.use { http ->
            val client = GatewayJobClient(http, now = { 0L })
            val scopes = listOf(
                config,
                config.copy(apiUrl = "http://192.168.1.10:8090/v1"),
                config.copy(token = "different-fixture-account"),
                config.copy(extraHeaders = mapOf("X-Gateway-Principal-ID" to "other-owner")),
                config.copy(extraHeaders = mapOf("X-Gateway-Installation-ID" to "other-installation"))
            )
            for ((index, scope) in scopes.withIndex()) {
                assertEquals(null, client.observeCommand(scope, "saved"))
                assertEquals(index + 1, calls)
                assertEquals(null, client.observeCommand(scope, "saved"))
                assertEquals(index + 1, calls)
            }
            assertEquals(null, client.observeCommand(config, "different-command"))
            assertEquals(6, calls)
        }
    }

    @Test fun concurrentPassiveObserversShareOneMissingCommandRead() = runBlocking {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        HttpClient(
            MockEngine {
                calls++
                entered.complete(Unit)
                release.await()
                respond("Unknown command", HttpStatusCode.NotFound)
            }
        ) { install(HttpTimeout) }.use { http ->
            val client = GatewayJobClient(http, now = { 0L })
            val first = async { client.observeCommand(config, "saved") }
            entered.await()
            val second = async { client.observeCommand(config, "saved") }
            release.complete(Unit)
            assertEquals(null, first.await())
            assertEquals(null, second.await())
            assertEquals(1, calls)
        }
    }

    @Test fun authenticationFailuresCannotBecomeCachedMissingCommands() = runBlocking {
        var calls = 0
        HttpClient(
            MockEngine {
                calls++
                respond("Unauthorized", HttpStatusCode.Unauthorized)
            }
        ) { install(HttpTimeout) }.use { http ->
            val client = GatewayJobClient(http, now = { 0L })
            repeat(2) {
                assertTrue(runCatching { client.observeCommand(config, "saved") }.exceptionOrNull() is GatewayAdmissionException)
            }
        }
        assertEquals(2, calls)
    }

    @Test fun fullLargeTranscriptMessageUsesItsOwnBoundWithoutRelaxingCatalogLimits() = runBlocking {
        val content = "x".repeat(2_001_000)
        val transcript = GatewayTranscriptPage(gatewayId = "gateway", principalId = "owner", conversationId = "conversation", revision = 1, messages = listOf(GatewayTranscriptMessage("message", "turn", "assistant", content, "completed", 1)), nextCursor = "end")
        val payload = Json.encodeToString(transcript)
        HttpClient(MockEngine { respond(payload) }) { install(HttpTimeout) }.use { http ->
            assertEquals(content, GatewayJobClient(http).messages(config, "conversation", null).messages.single().content)
            assertTrue(runCatching { GatewayJobClient(http).catalog(config) }.isFailure)
        }
    }
}
