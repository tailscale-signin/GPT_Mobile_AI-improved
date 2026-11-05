package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
