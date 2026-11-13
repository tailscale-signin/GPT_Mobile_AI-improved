package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.github.GatewayJobClient
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayJobPollingReconciliationTest {
    @Test fun concurrentAndSequentialObserversShareOneCommandAndResultUntilServerDeadline() = runTest {
        Fixture(this, "shared").use { fixture ->
            val first = fixture.api()
            val second = fixture.api()
            val commands = fixture.commands()
            val gate = CompletableDeferred<Unit>()
            fixture.resultGate = gate
            assertEquals("job", commands.observeCommand(fixture.config, "command")?.jobId)
            val a = async { first.getJobResult("job", fixture.config) }
            val b = async { second.getJobResult("job", fixture.config) }
            runCurrent()
            assertEquals(1, fixture.results)
            gate.complete(Unit)
            assertEquals("RUNNING", a.await()?.status)
            assertEquals("RUNNING", b.await()?.status)

            repeat(19) {
                advanceTimeBy(100)
                assertEquals("job", commands.observeCommand(fixture.config, "command")?.jobId)
                assertEquals("RUNNING", second.getJobResult("job", fixture.config)?.status)
            }
            assertEquals(1, fixture.results)
            assertEquals(1, fixture.commandReads)
            advanceTimeBy(100)
            fixture.resultStatus = "completed"
            assertEquals("completed", first.getJobResult("job", fixture.config)?.status)
            assertEquals("completed", second.getJobResult("job", fixture.config)?.status)
            assertEquals(2, fixture.results)
            assertEquals(listOf(0L, 2_000L), fixture.resultTimes)
            // Explicit reconciliation is fresh even while passive identity is retained.
            commands.command(fixture.config, "command")
            assertEquals(2, fixture.commandReads)
        }
    }

    @Test fun retryAfterHttpDateAppliesAcrossResultReadersAndCommandObservations() = runTest {
        Fixture(this, "date").use { fixture ->
            fixture.retryAfter = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(fixture.wallTime + 12_000).atZone(ZoneOffset.UTC))
            val api = fixture.api()
            val commands = fixture.commands()
            api.getJobResult("job", fixture.config)
            commands.observeCommand(fixture.config, "command")
            // Once received, an HTTP-date becomes a monotonic deadline.
            fixture.wallOffset = 86_400_000L
            advanceTimeBy(11_999)
            api.getJobResult("job", fixture.config)
            commands.observeCommand(fixture.config, "command")
            assertEquals(1, fixture.results)
            assertEquals(1, fixture.commandReads)
            advanceTimeBy(1)
            api.getJobResult("job", fixture.config)
            assertEquals(2, fixture.results)
        }
    }

    @Test fun cachedObservationSeparatesAuthorityButSharesDisplayProfilesForTheSameJob() = runTest {
        Fixture(this, "scopes").use { fixture ->
            val api = fixture.api()
            val configs = listOf(
                fixture.config,
                fixture.config.copy(token = "another-fixture-token"),
                fixture.config.copy(apiUrl = "https://another-poll-scope.example/v1"),
                fixture.config.copy(extraHeaders = mapOf("X-Gateway-Principal-ID" to "another-owner")),
                fixture.config.copy(extraHeaders = mapOf("X-Gateway-Installation-ID" to "another-installation"))
            )
            configs.forEachIndexed { index, config ->
                api.getJobResult("job", config)
                api.getJobResult("job", config)
                assertEquals(index + 1, fixture.results)
            }
            api.getJobResult("job", fixture.config.copy(profileUid = "jobs-view", profileRevision = "changed-display-settings"))
            assertEquals(configs.size, fixture.results)
            api.getJobResult("another-job", fixture.config)
            assertEquals(configs.size + 1, fixture.results)
        }
    }

    @Test fun gatewayRestartAndNetworkRecoveryInvalidateEvidenceWithoutShorteningDeadlines() = runTest {
        Fixture(this, "epochs").use { fixture ->
            val api = fixture.api()
            val commands = fixture.commands()
            api.getJobResult("job", fixture.config)
            commands.observeCommand(fixture.config, "command")
            GatewayClientDiagnostics.observeInstance(fixture.config, "b".repeat(32))
            fixture.instance = "b".repeat(32)
            api.getJobResult("job", fixture.config)
            commands.observeCommand(fixture.config, "command")
            assertEquals(2, fixture.results)
            assertEquals(2, fixture.commandReads)
            LocalServiceHealth.networkChanged()
            api.getJobResult("job", fixture.config)
            commands.observeCommand(fixture.config, "command")
            assertEquals(3, fixture.results)
            assertEquals(3, fixture.commandReads)
            assertEquals(listOf(0L, 2_000L, 4_000L), fixture.resultTimes)
        }
    }

    @Test fun cancellingAnObserverDoesNotCancelAnotherOrLeaveAnAutonomousPoller() = runTest {
        Fixture(this, "cancellation").use { fixture ->
            val api = fixture.api()
            val gate = CompletableDeferred<Unit>()
            fixture.resultGate = gate
            val owner = async { api.getJobResult("job", fixture.config) }
            runCurrent()
            val observer = async { api.getJobResult("job", fixture.config) }
            runCurrent()
            observer.cancel()
            assertFalse(owner.isCancelled)
            gate.complete(Unit)
            assertNotNull(owner.await())
            assertEquals(1, fixture.results)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, fixture.results)
        }
    }

    @Test fun cancellationWritesAreNeverCachedAndInvalidatePendingObservations() = runTest {
        Fixture(this, "writes").use { fixture ->
            val api = fixture.api()
            assertEquals("RUNNING", api.getJobResult("job", fixture.config)?.status)
            fixture.resultStatus = "cancelled"
            assertNotNull(api.cancelJob("job", fixture.config))
            assertEquals("cancelled", api.getJobResult("job", fixture.config)?.status)
            assertEquals(2, fixture.results)
            assertNotNull(api.cancelJob("job", fixture.config))
            assertEquals(2, fixture.writes)
        }
    }

    @Test fun anInvalidatedInflightReadCannotSatisfyAnotherObserverAfterItCompletes() = runTest {
        Fixture(this, "inflight-write").use { fixture ->
            val api = fixture.api()
            val gate = CompletableDeferred<Unit>()
            fixture.resultGate = gate
            val beforeWrite = async { api.getJobResult("job", fixture.config) }
            runCurrent()
            fixture.resultStatus = "cancelled"
            api.cancelJob("job", fixture.config)
            val afterWrite = async { api.getJobResult("job", fixture.config) }
            runCurrent()
            assertEquals(1, fixture.results)
            gate.complete(Unit)
            assertNull(beforeWrite.await())
            assertEquals("cancelled", afterWrite.await()?.status)
            assertEquals(2, fixture.results)
        }
    }

    @Test fun unrelatedMutationsCannotReviveAnInvalidatedObservationOrWaiveItsDeadline() = runTest {
        Fixture(this, "version-eviction").use { fixture ->
            fixture.retryAfter = "3600"
            val api = fixture.api()
            assertEquals("RUNNING", api.getJobResult("job", fixture.config)?.status)
            fixture.reads.invalidate(gatewayJobObservationIdentity(fixture.config), "job")
            repeat(128) { fixture.reads.invalidate(gatewayJobObservationIdentity(fixture.config), "another-job-$it") }
            fixture.resultStatus = "completed"
            assertEquals("completed", api.getJobResult("job", fixture.config)?.status)
            assertEquals(2, fixture.results)
            assertEquals(listOf(0L, 3_600_000L), fixture.resultTimes)
        }
    }

    @Test fun continuationExchangesInvalidateSharedReadsWithoutCachingWrites() = runTest {
        Fixture(this, "continuation").use { fixture ->
            val api = fixture.sharedApi()
            assertEquals("RUNNING", api.getJobResult("job", fixture.config)?.status)
            fixture.resultStatus = "completed"
            fixture.continueJob()
            assertEquals("completed", api.getJobResult("job", fixture.config)?.status)
            fixture.continueJob()
            assertEquals(2, fixture.writes)
            assertEquals(2, fixture.results)
        }
    }

    private class Fixture(val scope: TestScope, name: String) : AutoCloseable {
        val config = ProviderRequestConfig("https://poll-$name.example/v1", "fixture-token", profileUid = "selected-profile")
        val wallTime = 1_794_512_000_000L
        var wallOffset = 0L
        val reads = GatewayJobPollCoordinator({ scope.testScheduler.currentTime }, { wallTime + scope.testScheduler.currentTime + wallOffset })
        var resultGate: CompletableDeferred<Unit>? = null
        var retryAfter = "2"
        var resultStatus = "RUNNING"
        var instance = "a".repeat(32)
        var results = 0
        var commandReads = 0
        var writes = 0
        val resultTimes = mutableListOf<Long>()
        private val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
                    addHandler { request ->
                        val path = request.url.encodedPath
                        when {
                            path.endsWith("/result") -> {
                                assertEquals(HttpMethod.Get, request.method)
                                results++
                                resultTimes += scope.testScheduler.currentTime
                                val status = resultStatus
                                resultGate?.await()
                                val id = path.substringBeforeLast('/').substringAfterLast('/')
                                respond(
                                    """{"job_id":"$id","status":"$status","job":{"job_id":"$id","model":"selected-model"}}""",
                                    if (status == "RUNNING") HttpStatusCode.Accepted else HttpStatusCode.OK,
                                    headersOf(HttpHeaders.RetryAfter to listOf(retryAfter), "X-Gateway-Instance-ID" to listOf(instance))
                                )
                            }
                            path.contains("/commands/") -> {
                                assertEquals(HttpMethod.Get, request.method)
                                commandReads++
                                respond(
                                    """{"schema_version":1,"gateway_id":"gateway","principal_id":"owner","command_id":"command","conversation_id":"conversation","job_kind":"generation","job_id":"job"}""",
                                    headers = headersOf("X-Gateway-Instance-ID", instance)
                                )
                            }
                            path.endsWith("/cancel") -> {
                                assertEquals(HttpMethod.Post, request.method)
                                writes++
                                respond("""{"job_id":"job","canceled":true,"status":"cancelled"}""")
                            }
                            path.endsWith("/chat/completions") -> {
                                assertEquals(HttpMethod.Post, request.method)
                                writes++
                                respond("{}")
                            }
                            else -> error("Unexpected HTTP request: ${request.method} $path")
                        }
                    }
                }
            )
        ) { install(HttpTimeout) }
        private val network = mockk<NetworkClient>().also {
            every { it() } returns http
            every { it.gatewayReadClient() } returns http
        }

        fun api() = GatewayAPIImpl(network, reads)
        fun sharedApi() = GatewayAPIImpl(network, reads)
        fun commands() = GatewayJobClient(http, now = { scope.testScheduler.currentTime })
        suspend fun continueJob() {
            val config = config.copy(extraHeaders = mapOf("X-Gateway-Job-ID" to "job"))
            gatewayClientExchange(config, "chat", safeRead = false, method = "POST") {
                http.post(config.buildEndpoint("chat/completions"))
            }
        }
        override fun close() = http.close()
    }
}
