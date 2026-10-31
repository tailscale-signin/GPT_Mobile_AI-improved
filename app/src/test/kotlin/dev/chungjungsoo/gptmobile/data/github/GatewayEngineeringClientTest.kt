package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
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
        http(MockEngine { respond("""{"enabled":false,"principal_id":"owner"}""", HttpStatusCode.OK) }).use { http ->
            assertTrue(runCatching { GatewayEngineeringClient(http).principal(config) }.isFailure)
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
