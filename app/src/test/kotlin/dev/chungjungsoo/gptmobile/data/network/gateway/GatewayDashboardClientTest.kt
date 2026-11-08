package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.EOFException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayDashboardClientTest {
    private val config = ProviderRequestConfig("http://192.168.1.2:8090/v1", "selected-token", profileUid = "selected")
    private fun http(engine: MockEngine) = HttpClient(engine) {
        install(HttpTimeout)
        followRedirects = false
    }

    @Test fun concurrentReadinessFailuresShareTheRecoveryProbeWithoutCachingCancellation() = runTest {
        var calls = 0
        var cancelled = true
        http(
            MockEngine {
                calls++
                if (cancelled) throw kotlinx.coroutines.CancellationException("route lost")
                throw java.net.ConnectException("connection refused")
            }
        ).use { http ->
            val client = GatewayDashboardClient(http)
            assertTrue(runCatching { client.readiness(config, "selected-model") }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(1, calls)
            cancelled = false
            val results = List(12) { async { runCatching { client.readiness(config, "selected-model") } } }.awaitAll()
            assertTrue(results.all { it.exceptionOrNull() is java.net.ConnectException })
            assertEquals(3, calls)
            client.refresh()
            assertTrue(runCatching { client.readiness(config, "selected-model") }.isFailure)
            assertEquals(5, calls)
        }
    }

    @Test fun concurrentOfflineReadsShareOneBoundedRetryAndOneStalePoolEviction() = runTest {
        var calls = 0
        var evictions = 0
        http(
            MockEngine {
                calls++
                throw EOFException("unexpected end of stream")
            }
        ).use { http ->
            val client = GatewayDashboardClient(http) { evictions++ }
            val results = List(12) { async { runCatching { client.read(config) } } }.awaitAll()
            assertTrue(results.all { it.exceptionOrNull() is EOFException })
            assertEquals(2, calls)
            assertEquals(1, evictions)
            repeat(5) { assertTrue(runCatching { client.read(config) }.isFailure) }
            assertEquals(2, calls)
            client.refresh()
            assertTrue(runCatching { client.read(config) }.isFailure)
            assertEquals(4, calls)
            assertEquals(2, evictions)
        }
    }

    @Test fun readinessSeparatesAnAliveGatewayFromUnavailableSelectedModelWithoutInference() = runTest {
        var calls = 0
        http(
            MockEngine { request ->
                calls++
                assertEquals("GET", request.method.value)
                assertEquals("/v1/gateway/ready", request.url.encodedPath)
                assertEquals("selected-model", request.url.parameters["model"])
                assertEquals("Bearer selected-token", request.headers["Authorization"])
                assertNull(request.headers["X-Gateway-Management"])
                respond(readinessFixture("selected-model", ready = false).toString(), HttpStatusCode.ServiceUnavailable)
            }
        ).use { http ->
            val client = GatewayDashboardClient(http)
            val report = client.readiness(config.copy(extraHeaders = mapOf("X-Gateway-Management" to "never-send")), "selected-model")!!
            assertFalse(report.ready)
            assertEquals(false, report.selectedModelAvailable)
            assertEquals("a".repeat(32), report.instanceId)
            assertEquals(1, calls)
        }
    }

    @Test fun readinessCacheRetainsTheOriginalReceiptAndRejectsInvalidModelsBeforeDispatch() = runTest {
        var calls = 0
        http(
            MockEngine { request ->
                calls++
                assertEquals("GET", request.method.value)
                assertEquals("/v1/gateway/ready", request.url.encodedPath)
                assertEquals("selected-model", request.url.parameters["model"])
                respond(readinessFixture("selected-model").toString(), HttpStatusCode.OK)
            }
        ).use { http ->
            val client = GatewayDashboardClient(http)
            val first = client.readiness(config, "selected-model")!!
            val cached = client.readiness(config, "selected-model")!!
            assertEquals(first.receivedAtMillis, cached.receivedAtMillis)
            assertEquals(first.receivedAtMonotonicMillis, cached.receivedAtMonotonicMillis)
            assertNotNull(first.receivedAtMonotonicMillis)
            for (model in listOf(" ", " selected-model ", "invalid\nmodel", "x".repeat(513))) {
                assertTrue(runCatching { client.readiness(config, model) }.isFailure)
            }
            assertEquals(1, calls)
        }
    }

    @Test fun timeSpentReceivingAResponseCannotExtendTheOriginalProbeFreshness() = runTest {
        var elapsed = 0L
        val base = readinessFixture("selected-model")
        val capabilities = base["capabilities"] as JsonObject
        val response = JsonObject(base + ("capabilities" to JsonObject(capabilities + mapOf("observedAtMs" to JsonPrimitive(1_000), "observationAgeMs" to JsonPrimitive(19_000)))))
        http(
            MockEngine {
                elapsed = 1_050
                respond(response.toString(), HttpStatusCode.OK)
            }
        ).use { http ->
            val client = GatewayDashboardClient(http, {}, { elapsed })
            val observed = client.readiness(config, "selected-model")!!
            assertEquals(20_050L, observed.observationAgeMillis)
            assertEquals(1_000L, observed.observedAtMillis)
            val target = GatewayAvailabilityTarget("profile", "selected", "selected-model", config)
            assertEquals(GatewayAvailabilityStatus.CHECKING, gatewayAvailability(target, observed, observed.receivedAtMillis, elapsed).status)
        }
    }

    @Test fun anotherModelsReadinessAndContradictoryStatusAreRejectedWithoutRetry() = runTest {
        for ((model, status) in listOf("other-model" to HttpStatusCode.OK, "selected-model" to HttpStatusCode.ServiceUnavailable)) {
            var calls = 0
            http(
                MockEngine {
                    calls++
                    respond(readinessFixture(model).toString(), status)
                }
            ).use { http ->
                assertTrue(runCatching { GatewayDashboardClient(http).readiness(config, "selected-model") }.isFailure)
                assertEquals(1, calls)
            }
        }
        val valid = readinessFixture("selected-model")
        val capabilities = valid["capabilities"] as JsonObject
        for (malformed in listOf(
            JsonObject(valid + ("schemaVersion" to JsonPrimitive("1"))),
            JsonObject(valid + ("instanceId" to JsonPrimitive("${"a".repeat(32)}\n"))),
            JsonObject(valid + ("capabilities" to JsonObject(capabilities + ("selectedModelDigest" to JsonPrimitive(dev.chungjungsoo.gptmobile.data.research.researchHash("selected-model") + "\n")))))
        )) {
            var calls = 0
            http(
                MockEngine {
                    calls++
                    respond(malformed.toString())
                }
            ).use { http ->
                assertTrue(runCatching { GatewayDashboardClient(http).readiness(config, "selected-model") }.isFailure)
                assertEquals(1, calls)
            }
        }
    }

    @Test fun usesOnlyTheSelectedConnectionAndReadOnlyDashboardRoute() = runTest {
        var calls = 0
        http(
            MockEngine { request ->
                calls++
                assertEquals("http://192.168.1.2:8090/v1/gateway/dashboard", request.url.toString())
                assertEquals("Bearer selected-token", request.headers["Authorization"])
                assertNull(request.headers["X-Gateway-Management"])
                assertEquals("GET", request.method.value)
                respond(dashboardFixture().toString(), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        ).use { http ->
            val client = GatewayDashboardClient(http)
            val observation = client.read(config.copy(extraHeaders = mapOf("X-Gateway-Management" to "never-send")))
            assertNotNull(observation)
            assertEquals(12345L, observation?.snapshot?.outputTokens)
            assertEquals(3L, observation?.snapshot?.openJobs)
            assertEquals(1, calls)
        }
    }

    @Test fun unsupportedEndpointIsCachedWithoutProbingOtherProfilesOrPorts() = runTest {
        val addresses = mutableListOf<String>()
        http(
            MockEngine { request ->
                addresses += request.url.toString()
                respond("not a gateway", HttpStatusCode.NotFound)
            }
        ).use { http ->
            val client = GatewayDashboardClient(http)
            repeat(3) { assertNull(client.read(config)) }
            assertEquals(listOf("http://192.168.1.2:8090/v1/gateway/dashboard"), addresses)
            assertNull(client.read(config.copy(profileUid = "different", token = "other-token")))
            assertEquals(2, addresses.size)
            client.refresh()
            assertNull(client.read(config))
            assertEquals(3, addresses.size)
        }
    }

    @Test fun accessFailureNeverExposesServerBodyOrRetriesAnotherDestination() = runTest {
        var calls = 0
        http(
            MockEngine {
                calls++
                respond("private repository and secret-token", HttpStatusCode.Forbidden)
            }
        ).use { http ->
            val failure = runCatching { GatewayDashboardClient(http).read(config) }.exceptionOrNull()
            assertTrue(failure is GatewayDashboardAccessDenied)
            assertFalse(failure?.message.orEmpty().contains("secret-token"))
            assertEquals(1, calls)
        }
    }

    @Test fun oversizedResponsesAreRejectedBeforeParsing() = runTest {
        http(MockEngine { respond("x".repeat(512_001), HttpStatusCode.OK) }).use { http ->
            assertTrue(runCatching { GatewayDashboardClient(http).read(config) }.isFailure)
        }
    }

    @Test fun aGenericInferenceResponseNeverBecomesAGatewayMeasurement() = runTest {
        http(MockEngine { respond("""{"usage":{"completion_tokens":9000}}""", HttpStatusCode.OK) }).use { http ->
            assertTrue(runCatching { GatewayDashboardClient(http).read(config) }.isFailure)
        }
    }

    @Test fun validatedDashboardRestartsInvalidateTheSelectedProfilesCapabilityEpoch() = runTest {
        val selected = config.copy(apiUrl = "http://dashboard-restart.example/v1", profileUid = "dashboard-restart")
        var instance = "a".repeat(32)
        var calls = 0
        http(
            MockEngine {
                calls++
                respond(JsonObject(dashboardFixture() + ("instanceId" to JsonPrimitive(instance))).toString(), HttpStatusCode.OK)
            }
        ).use { http ->
            val client = GatewayDashboardClient(http)
            client.read(selected)
            val original = GatewayClientDiagnostics.profileCapabilityEpoch(selected.profileUid!!, selected.apiUrl)
            assertEquals(instance, GatewayClientDiagnostics.runtimeEpoch(selected))
            instance = "b".repeat(32)
            client.read(selected)
            assertEquals(original, GatewayClientDiagnostics.profileCapabilityEpoch(selected.profileUid, selected.apiUrl))
            assertEquals(1, calls)
            client.refresh()
            client.read(selected)
            assertTrue(GatewayClientDiagnostics.profileCapabilityEpoch(selected.profileUid, selected.apiUrl) > original)
            assertEquals(instance, GatewayClientDiagnostics.runtimeEpoch(selected))
            assertEquals("unobserved", GatewayClientDiagnostics.runtimeEpoch(selected.copy(token = "different-credential")))
            assertEquals(2, calls)
        }
    }

    @Test fun failureHeadersStillExposeGatewayRestartsBeforeErrorHandling() = runTest {
        val selected = config.copy(apiUrl = "http://dashboard-error-epoch.example/v1", profileUid = "dashboard-error-epoch")
        GatewayClientDiagnostics.observeInstance(selected, "a".repeat(32))
        val original = GatewayClientDiagnostics.profileCapabilityEpoch(selected.profileUid!!, selected.apiUrl)
        http(MockEngine { respond("denied", HttpStatusCode.Forbidden, headersOf("X-Gateway-Instance-ID", "b".repeat(32))) }).use { http ->
            assertTrue(runCatching { GatewayDashboardClient(http).read(selected) }.exceptionOrNull() is GatewayDashboardAccessDenied)
            assertTrue(GatewayClientDiagnostics.profileCapabilityEpoch(selected.profileUid, selected.apiUrl) > original)
            assertEquals("b".repeat(32), GatewayClientDiagnostics.runtimeEpoch(selected))
        }
    }

    @Test fun rejectedDashboardPayloadCannotChangeTheObservedRuntimeInstance() = runTest {
        val selected = config.copy(apiUrl = "http://dashboard-invalid-epoch.example/v1", profileUid = "dashboard-invalid-epoch")
        GatewayClientDiagnostics.observeInstance(selected, "a".repeat(32))
        val original = GatewayClientDiagnostics.profileCapabilityEpoch(selected.profileUid!!, selected.apiUrl)
        val invalid = JsonObject(dashboardFixture() + mapOf("instanceId" to JsonPrimitive("b".repeat(32)), "scope" to JsonPrimitive("management")))
        http(MockEngine { respond(invalid.toString(), HttpStatusCode.OK) }).use { http ->
            assertTrue(runCatching { GatewayDashboardClient(http).read(selected) }.isFailure)
            assertEquals(original, GatewayClientDiagnostics.profileCapabilityEpoch(selected.profileUid, selected.apiUrl))
            assertEquals("a".repeat(32), GatewayClientDiagnostics.runtimeEpoch(selected))
        }
    }
}
