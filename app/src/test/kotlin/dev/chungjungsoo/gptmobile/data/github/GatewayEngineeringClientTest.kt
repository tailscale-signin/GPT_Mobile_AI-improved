package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewaySurfacePolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayEngineeringClientTest {
    private val config = ProviderRequestConfig("https://gateway.test/v1", "test-token")
    private val job = GatewayEngineeringJob("eng_" + "a".repeat(32), "owner/repo", "feature/job", state = "MERGE_READY", revision = 12, createdAt = 1.0, deadline = 99999.0, message = "Review", pr = 7, headSha = "b".repeat(40), mergeReady = true)
    private fun http(engine: MockEngine) = HttpClient(engine) {
        install(HttpTimeout)
        followRedirects = false
    }

    @Test fun aConfirmedDirectRouteSkipsEngineeringDiscoveryUntilItsIdentityChanges() = runTest {
        val direct = config.copy(apiUrl = "https://confirmed-direct.example/v1")
        GatewaySurfacePolicy.shared.observe(GatewaySurfacePolicy.identity(direct), GatewaySurfacePolicy.Surface.CAPABILITIES, 404)
        var calls = 0
        http(
            MockEngine {
                calls++
                respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":true,"principal_id":"new-account"}""")
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            assertTrue(runCatching { client.capabilities(direct) }.exceptionOrNull() is GatewayGraphUnavailableException)
            assertEquals(0, calls)
            assertEquals("new-account", client.principal(direct.copy(token = "new-token")))
            assertEquals(1, calls)
        }
    }

    @Test fun directNetworkIpAndHostnameDoNotRequireCredentials() = runTest {
        for (host in listOf("192.168.1.100", "my-pc.local", "[fd00::10]")) {
            val direct = ProviderRequestConfig("http://$host:8090/v1", null)
            http(
                MockEngine { request ->
                    assertEquals("http://$host:8090/v1/gateway/engineering/capabilities", request.url.toString())
                    assertNull(request.headers["Authorization"])
                    respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":true,"principal_id":"network:shared"}""", HttpStatusCode.OK)
                }
            ).use { http ->
                assertEquals("network:shared", GatewayEngineeringClient(http).principal(direct))
            }
        }
    }

    @Test fun unsupportedInferenceEndpointIsCachedSeparatelyFromAnotherGatewayIdentity() = runTest {
        var calls = 0
        http(
            MockEngine {
                calls++
                respond("not an engineering server", HttpStatusCode.NotFound)
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            repeat(3) { assertTrue(runCatching { client.principal(config) }.isFailure) }
            assertEquals(1, calls)
            assertTrue(runCatching { client.principal(config.copy(token = "other-account")) }.isFailure)
            assertEquals(2, calls)
        }
    }

    @Test fun disabledEngineeringCapabilityNeverEstablishesReadiness() = runTest {
        http(MockEngine { respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":false,"principal_id":"owner"}""", HttpStatusCode.OK) }).use { http ->
            assertTrue(runCatching { GatewayEngineeringClient(http).principal(config) }.isFailure)
        }
    }

    @Test fun engineeringReadinessIsCachedPerAuthenticatedRouteAndObservedServerEpoch() = runTest {
        val first = "a".repeat(32)
        val second = "b".repeat(32)
        var epoch = first
        var calls = 0
        dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics.observeInstance(config, first)
        http(
            MockEngine {
                calls++
                respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":true,"principal_id":"owner"}""", HttpStatusCode.OK, headersOf("X-Gateway-Instance-ID", epoch))
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            repeat(3) { assertEquals("owner", client.principal(config)) }
            assertEquals("owner", client.principal(config.copy(profileUid = "another-profile-on-same-connection")))
            assertEquals(1, calls)
            epoch = second
            dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics.observeInstance(config, second)
            assertEquals("owner", client.principal(config))
            assertEquals(2, calls)
            assertEquals("owner", client.principal(config.copy(token = "different-principal")))
            assertEquals(3, calls)
            assertEquals("owner", client.principal(config.copy(extraHeaders = mapOf("X-Identity" to "different-proxy-identity"))))
            assertEquals(4, calls)
        }
    }

    @Test fun missingHostGithubConnectionBlocksGraphAndNewWorkButPreservesSavedResultIdentity() = runTest {
        var calls = 0
        http(
            MockEngine {
                calls++
                respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":false,"principal_id":"owner","code_graph":{"schema_version":1,"enabled":false},"readiness":{"schema_version":1,"ready":false,"graph_ready":false,"saved_results":true,"checks":{"journal":"ready","host_github_connection":"missing","worker":"running"}}}""", HttpStatusCode.OK)
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            assertEquals("owner", client.principal(config, requireReady = false))
            assertTrue(runCatching { client.principal(config) }.exceptionOrNull()?.message.orEmpty().contains("GitHub connection"))
            assertTrue(runCatching { client.requireGraphReady(config) }.exceptionOrNull()?.message.orEmpty().contains("GitHub connection"))
            assertEquals(1, calls)
        }
    }

    @Test fun engineeringAuthenticationFailureIsNotCachedAsUnsupportedOrRetried() = runTest {
        var calls = 0
        http(
            MockEngine {
                calls++
                respond("credentials-and-private-source", HttpStatusCode.Forbidden)
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            repeat(2) {
                val message = runCatching { client.capabilities(config) }.exceptionOrNull()?.message.orEmpty()
                assertTrue(message.contains("not authorized"))
                assertFalse(message.contains("private-source"))
            }
            assertEquals(2, calls)
        }
    }

    @Test fun createRetriesKeepTheSameRequestIdentityAndNeverCarryApproval() = runTest {
        val payload = GatewayEngineeringRequest("owner/repo", "feature/job", "main", "Fix tests", "local", chatId = 4, requestId = "saved-before-dispatch")
        var count = 0
        http(
            MockEngine { request ->
                count++
                assertEquals("/v1/gateway/engineering/jobs", request.url.encodedPath)
                assertEquals("Bearer test-token", request.headers["Authorization"])
                assertNull(request.headers["X-Gateway-User-Action"])
                assertEquals(payload, Json.decodeFromString<GatewayEngineeringRequest>((request.body as TextContent).text))
                respond(Json.encodeToString(job), HttpStatusCode.Accepted, headersOf("Content-Type", "application/json"))
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            repeat(2) { assertEquals(job.id, client.create(config, payload).id) }
        }
        assertEquals(2, count)
    }

    @Test fun reconnectRequestsOnlyMissedEventsAndParsesTheNextCursor() = runTest {
        val snapshot = job.copy(updates = listOf(GatewayEngineeringUpdate(11, "CI", "Checks passed", 10.0)), nextRevision = 11, hasMoreUpdates = true)
        http(
            MockEngine { request ->
                assertEquals("10", request.url.parameters["after_revision"])
                assertNull(request.headers["X-Gateway-User-Action"])
                respond(Json.encodeToString(snapshot), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        ).use { http ->
            val result = GatewayEngineeringClient(http).get(config, job.id, 10)
            assertEquals(11, result.nextRevision)
            assertTrue(result.hasMoreUpdates)
            assertEquals("Checks passed", result.updates.single().message)
        }
    }

    @Test fun approvalIsAnExplicitExactCommitRequestAndInvalidStateDoesNotDispatch() = runTest {
        var calls = 0
        http(
            MockEngine { request ->
                calls++
                assertEquals("approve-and-merge", request.headers["X-Gateway-User-Action"])
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals(job.headSha, body.getValue("head_sha").jsonPrimitive.content)
                assertEquals("7", body.getValue("pr").jsonPrimitive.content)
                assertEquals("12", body.getValue("revision").jsonPrimitive.content)
                respond(Json.encodeToString(job.copy(state = "MERGE", mergeReady = false)), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            assertEquals("MERGE", client.control(config, job, "approve").state)
            try {
                client.control(config, job.copy(mergeReady = false), "approve")
                error("Expected invalid approval to be rejected")
            } catch (_: IllegalArgumentException) {
                assertEquals(1, calls)
            }
        }
    }

    @Test fun rejectedRequestsExposeNoServerBodyOrCredentials() = runTest {
        http(MockEngine { respond("private source and credentials", HttpStatusCode.Conflict) }).use { http ->
            try {
                GatewayEngineeringClient(http).get(config, job.id, 0)
                error("Expected stale state")
            } catch (failure: IllegalStateException) {
                assertTrue(failure.message.orEmpty().contains("Refresh"))
                assertFalse(failure.message.orEmpty().contains("credentials"))
            }
        }
    }
}
