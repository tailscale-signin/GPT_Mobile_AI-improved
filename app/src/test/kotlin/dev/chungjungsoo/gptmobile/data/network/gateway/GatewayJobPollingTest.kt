package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.EOFException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayJobPollingTest {
    @Test fun chatJobsAndMonitorShareOneResponseDeadlineThenStopAtTerminal() = runBlocking {
        var elapsed = 0L
        var reads = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = GatewayJobPollCoordinator({ elapsed }, { elapsed })
        val config = ProviderRequestConfig("https://gateway-polling.example/v1", "selected-account")
        HttpClient(
            MockEngine { request ->
                reads++
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("Bearer selected-account", request.headers[HttpHeaders.Authorization])
                assertEquals("/v1/gateway/jobs/saved/result", request.url.encodedPath)
                if (reads == 1) {
                    entered.complete(Unit)
                    release.await()
                    respond("""{"job_id":"saved","status":"running","terminal":false}""", HttpStatusCode.Accepted, headersOf(HttpHeaders.RetryAfter, "2"))
                } else {
                    respond("""{"job_id":"saved","status":"completed","content":"Saved answer","result_quality":"complete","terminal":true,"continuation_allowed":false}""", HttpStatusCode.OK)
                }
            }
        ).use { http ->
            val network = mockk<NetworkClient>()
            every { network.gatewayReadClient() } returns http
            val chat = GatewayAPIImpl(network, coordinator)
            val jobs = GatewayAPIImpl(network, coordinator)
            val monitor = GatewayAPIImpl(network, coordinator)
            val chatRead = async { chat.getJobResult("saved", config.copy(profileUid = "chat")) }
            entered.await()
            val jobsRead = async { jobs.getJobResult("saved", config.copy(profileUid = "jobs")) }
            val monitorRead = async { monitor.getJobResult("saved", config.copy(requestRole = "background-monitor")) }
            elapsed = 700L
            release.complete(Unit)
            assertEquals(listOf("running", "running", "running"), listOf(chatRead.await(), jobsRead.await(), monitorRead.await()).map { it?.status })
            assertEquals(1, reads)
            elapsed = 2_699L
            for (api in listOf(chat, jobs, monitor)) assertEquals("running", api.getJobResult("saved", config)?.status)
            assertEquals(1, reads)
            elapsed = 2_700L
            val terminalReads = listOf(chat, jobs, monitor).map { api -> async { api.getJobResult("saved", config) } }
            assertEquals(listOf("completed", "completed", "completed"), terminalReads.map { it.await()?.status })
            assertEquals(2, reads)
            elapsed = 100_000L
            for (api in listOf(chat, jobs, monitor)) assertEquals("Saved answer", api.getJobResult("saved", config)?.content)
            assertEquals(2, reads)
        }
    }

    @Test fun independentlyConstructedApisUseTheSameNetworkOwner() = runBlocking {
        var reads = 0
        HttpClient(
            MockEngine {
                reads++
                respond("""{"job_id":"saved","status":"running"}""", HttpStatusCode.Accepted, headersOf(HttpHeaders.RetryAfter, "30"))
            }
        ).use { http ->
            val network = mockk<NetworkClient>()
            every { network.gatewayReadClient() } returns http
            val config = ProviderRequestConfig("https://shared-network-polling.example/v1", "selected-account")
            assertEquals("running", GatewayAPIImpl(network).getJobResult("saved", config)?.status)
            assertEquals("running", GatewayAPIImpl(network).getJobResult("saved", config)?.status)
            assertEquals(1, reads)
        }
    }

    @Test fun reconnectAfterAResponseBodyFailureAlsoHonorsTheReceivedRetryAfter() = runTest {
        val reading = CompletableDeferred<Unit>()
        val stream = ByteChannel(autoFlush = true)
        val responseBody = object : ByteReadChannel by stream {
            override suspend fun awaitContent(min: Int): Boolean {
                reading.complete(Unit)
                return stream.awaitContent(min)
            }
        }
        var requests = 0
        var failedResponseAt = 0L
        val coordinator = GatewayJobPollCoordinator({ testScheduler.currentTime }, { 0L })
        HttpClient(
            MockEngine {
                requests++
                if (requests == 1) {
                    respond(responseBody, HttpStatusCode.Accepted, headersOf(HttpHeaders.RetryAfter, "2"))
                } else {
                    assertTrue("The internal reconnect cannot bypass Retry-After.", testScheduler.currentTime >= failedResponseAt + 2_000L)
                    respond("""{"job_id":"saved","status":"completed","content":"Recovered answer","result_quality":"complete","terminal":true}""", HttpStatusCode.OK)
                }
            }
        ).use { http ->
            val network = mockk<NetworkClient>()
            every { network.gatewayReadClient() } returns http
            coEvery { network.evictIdleConnections() } returns Unit
            val read = async {
                GatewayAPIImpl(network, coordinator).getJobResult("saved", ProviderRequestConfig("https://body-reconnect.example/v1", "account"))
            }
            reading.await()
            failedResponseAt = testScheduler.currentTime
            stream.cancel(EOFException("Response body ended early"))
            assertEquals("Recovered answer", read.await()?.content)
            assertEquals(2, requests)
        }
    }
}
