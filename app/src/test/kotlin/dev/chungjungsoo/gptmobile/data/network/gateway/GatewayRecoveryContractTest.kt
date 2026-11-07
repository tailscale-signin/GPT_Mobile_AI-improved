package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GatewayRecoveryContractTest {
    @Test fun waitingResultRetainsItsBoundHandoffWithoutPromotingItToCompletion() = runBlocking {
        val body = """{"job_id":"job-1","status":"waiting_for_client_tool","job":{"job_id":"job-1","model":"selected-model","pending_client_tool_call_ids":["call-1"]},"result":{"model":"selected-model","choices":[{"index":0,"message":{"role":"assistant","tool_calls":[{"id":"call-1","type":"function","function":{"name":"read_repository","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}}"""
        HttpClient(MockEngine { respond(body, HttpStatusCode.Accepted) }).use { client ->
            val network = mockk<NetworkClient>()
            every { network() } returns client
            val saved = requireNotNull(GatewayAPIImpl(network).getJobResult("job-1", ProviderRequestConfig("https://waiting-job.example/v1", "selected-token")))
            assertEquals("waiting_for_client_tool", saved.status)
            assertEquals(GatewayResultDisposition.WAITING, saved.recoveryDisposition)
            assertEquals("call-1", saved.result?.choices?.single()?.effectiveDelta?.toolCalls?.single()?.id)
        }
    }

    @Test fun acceptedResponseCannotReturnAnotherJobsEvidenceOrACompletedAnswer() = runBlocking {
        val bodies = listOf(
            """{"job_id":"other-job","status":"waiting_for_client_tool"}""",
            """{"job_id":"job-1","status":"waiting_for_client_tool","job":{"job_id":"other-job"}}""",
            """{"job_id":"job-1","status":"completed","content":"Not accepted as complete","result_quality":"complete"}"""
        )
        for ((index, body) in bodies.withIndex()) {
            HttpClient(MockEngine { respond(body, HttpStatusCode.Accepted) }).use { client ->
                val network = mockk<NetworkClient>()
                every { network() } returns client
                val saved = GatewayAPIImpl(network).getJobResult("job-1", ProviderRequestConfig("https://waiting-job.example/v1", "selected-token"))
                if (index < 2) {
                    assertEquals(null, saved)
                } else {
                    assertEquals(GatewayResultDisposition.WAITING, saved?.recoveryDisposition)
                    assertEquals(null, saved?.recoverableContent)
                }
            }
        }
    }

    @Test fun `generation reset restarts cursor and wrong job or undeclared generation fails closed`() = runBlocking {
        val bodies = listOf(
            "data: {\"job_id\":\"job-1\",\"generation\":\"new\",\"sequence\":1,\"generationChanged\":true}\n\n" +
                "data: {\"job_id\":\"job-1\",\"generation\":\"new\",\"sequence\":2}\n\n",
            "data: {\"job_id\":\"other\",\"generation\":\"old\",\"sequence\":101}\n\n",
            "data: {\"job_id\":\"job-1\",\"generation\":\"unexpected\",\"sequence\":101}\n\n"
        )
        for ((index, body) in bodies.withIndex()) {
            val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK) })
            val network = mockk<NetworkClient>()
            every { network() } returns client
            try {
                val result = runCatching { GatewayAPIImpl(network).resumeEvents("job-1", 100, 10, ProviderRequestConfig("http://192.168.1.2", null), "old").toList() }
                if (index == 0) assertEquals(listOf(1, 2), result.getOrThrow().map { it.sequence }) else assertTrue(result.exceptionOrNull() is GatewayEventReplayException)
            } finally {
                client.close()
            }
        }
    }

    @Test fun `event replay binds generation and suppresses duplicate cursors`() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("old-generation", request.url.parameters["generation"])
                respond(
                    "data: {\"job_id\":\"job-1\",\"generation\":\"new-generation\",\"sequence\":4,\"event\":\"gateway_snapshot\",\"replayGap\":true,\"generationChanged\":true}\n\n" +
                        "data: {\"job_id\":\"job-1\",\"generation\":\"new-generation\",\"sequence\":4,\"event\":\"gateway_snapshot\"}\n\n" +
                        "data: {\"job_id\":\"job-1\",\"generation\":\"new-generation\",\"sequence\":5,\"event\":\"tool_progress\"}\n\n",
                    HttpStatusCode.OK,
                    headers = io.ktor.http.headersOf("Content-Type", ContentType.Text.EventStream.toString())
                )
            }
        )
        val network = mockk<NetworkClient>()
        every { network() } returns client
        val events = GatewayAPIImpl(network).resumeEvents(
            "job-1",
            2,
            10,
            ProviderRequestConfig("http://192.168.1.2", null),
            "old-generation"
        ).toList()
        assertEquals(listOf(4, 5), events.mapNotNull { it.sequence })
        assertEquals(true, events.first().generationChanged)
        client.close()
    }

    @Test fun `202 is pending without parsing a completed body and recovery includes current auth`() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("Bearer rotated-token", request.headers["Authorization"])
                assertTrue(request.url.encodedPath.endsWith("/gateway/jobs/job-1/result"))
                respond("not a completed JSON result", HttpStatusCode.Accepted)
            }
        )
        val network = mockk<NetworkClient>()
        every { network() } returns client
        val api = GatewayAPIImpl(network)
        assertEquals("RUNNING", api.getJobResult("job-1", ProviderRequestConfig("http://192.168.1.2", "rotated-token"))?.status)
        assertTrue(runCatching { api.getJobResult("../other", ProviderRequestConfig("https://example.com", null)) }.isFailure)
        client.close()
    }

    @Test fun `cancellation is never converted to an unavailable recovery response`() = runBlocking {
        val client = HttpClient(MockEngine { throw CancellationException("canceled") })
        val network = mockk<NetworkClient>()
        every { network() } returns client
        try {
            GatewayAPIImpl(network).getJobResult("job-1", ProviderRequestConfig("http://192.168.1.2", null))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { } finally {
            client.close()
        }
    }
}
