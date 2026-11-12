package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayJobPollCoordinatorTest {
    private val pending = GatewayJobResult("saved", "running")
    private val complete = GatewayJobResult("saved", "completed", "Saved answer", "complete", terminal = true, continuationAllowed = false)

    @Test fun numericAndHttpDateDeadlinesStartAtResponseReceipt() = runBlocking {
        for ((header, deadline) in listOf("30" to 31_000L, "Thu, 12 Nov 2026 19:30:05 GMT" to 5_000L)) {
            var elapsed = 0L
            val wall = Instant.parse("2026-11-12T19:30:00Z").toEpochMilli()
            val coordinator = GatewayJobPollCoordinator({ elapsed }, { wall + elapsed })
            var reads = 0
            suspend fun read() = coordinator.read("authenticated-route", "saved", { "runtime" }) { received ->
                reads++
                if (reads == 1) {
                    elapsed = 1_000L
                    received(GatewayJobPollResponse(202, header))
                    pending
                } else {
                    received(GatewayJobPollResponse(200, null))
                    complete
                }
            }
            assertEquals(pending, read())
            elapsed = deadline - 1
            assertEquals(pending, read())
            assertEquals(1, reads)
            elapsed = deadline
            assertEquals(complete, read())
            elapsed += 100_000L
            assertEquals(complete, read())
            assertEquals(2, reads)
        }
    }

    @Test fun cancellationAfterResponseRetainsDeadlineWithoutInventingAResult() = runTest {
        val coordinator = GatewayJobPollCoordinator({ testScheduler.currentTime }, { 0L })
        var reads = 0
        val cancelledOwner = async {
            coordinator.read("route", "saved", { "runtime" }) { received ->
                reads++
                received(GatewayJobPollResponse(202, "5"))
                awaitCancellation()
            }
        }
        runCurrent()
        cancelledOwner.cancelAndJoin()
        val nextOwner = async {
            coordinator.read("route", "saved", { "runtime" }) { received ->
                reads++
                received(GatewayJobPollResponse(200, null))
                complete
            }
        }
        val cancelledObserver = async {
            coordinator.read("route", "saved", { "runtime" }) { error("A waiting observer must not dispatch.") }
        }
        runCurrent()
        cancelledObserver.cancelAndJoin()
        advanceTimeBy(4_999L)
        runCurrent()
        assertEquals(1, reads)
        assertFalse(nextOwner.isCompleted)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(complete, nextOwner.await())
        assertEquals(2, reads)
    }

    @Test fun mutationDiscardsInflightStateButCannotWaiveItsRetryAfter() = runTest {
        val coordinator = GatewayJobPollCoordinator({ testScheduler.currentTime }, { 0L })
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val stale = async {
            coordinator.read("route", "saved", { "runtime" }) { received ->
                reads++
                release.await()
                received(GatewayJobPollResponse(202, "2"))
                pending
            }
        }
        runCurrent()
        coordinator.invalidate("route", "saved")
        release.complete(Unit)
        assertEquals(null, stale.await())
        val fresh = async {
            coordinator.read("route", "saved", { "runtime" }) { received ->
                reads++
                received(GatewayJobPollResponse(200, null))
                complete
            }
        }
        runCurrent()
        advanceTimeBy(1_000L)
        coordinator.invalidate("route", "saved")
        advanceTimeBy(999L)
        runCurrent()
        assertEquals(1, reads)
        assertFalse(fresh.isCompleted)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(complete, fresh.await())
        assertEquals(2, reads)
    }

    @Test fun routeCredentialsAccountJobAndRuntimeBoundariesPreventEvidenceReuse() = runTest {
        val config = ProviderRequestConfig("https://gateway.example/v1", "first-account", profileUid = "chat-profile")
        val scopes = listOf(
            config,
            config.copy(apiUrl = "https://other-gateway.example/v1"),
            config.copy(token = "other-account"),
            config.copy(extraHeaders = mapOf("X-Gateway-Installation-ID" to "other-installation")),
            config.copy(extraHeaders = mapOf("X-Gateway-Principal-ID" to "other-principal")),
            config.copy(extraHeaders = mapOf("Authorization" to "other-authorization"))
        )
        assertEquals(gatewayJobObservationIdentity(config), gatewayJobObservationIdentity(config.copy(profileUid = "jobs-profile", requestRole = "monitor")))
        assertEquals(scopes.size, scopes.map(::gatewayJobObservationIdentity).distinct().size)
        assertNotEquals(gatewayJobObservationIdentity(config), gatewayJobObservationIdentity(config.copy(extraHeaders = mapOf("X-Custom-Account" to "other"))))
        var runtime = "before-restart"
        var reads = 0
        val coordinator = GatewayJobPollCoordinator({ testScheduler.currentTime }, { 0L })
        suspend fun read(scope: ProviderRequestConfig, jobId: String = "saved") = coordinator.read(gatewayJobObservationIdentity(scope), jobId, { runtime }) { received ->
            reads++
            received(GatewayJobPollResponse(200, null))
            complete.copy(jobId = jobId)
        }
        for (scope in scopes) {
            read(scope)
            read(scope)
        }
        assertEquals(scopes.size, reads)
        read(config, "different-job")
        assertEquals(scopes.size + 1, reads)
        runtime = "after-restart"
        val refreshed = async { read(config) }
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(scopes.size + 1, reads)
        advanceTimeBy(1)
        refreshed.await()
        assertEquals(scopes.size + 2, reads)
    }

    @Test fun progressInvalidatesOldStateWithoutShorteningTheSharedDeadline() = runTest {
        val coordinator = GatewayJobPollCoordinator({ testScheduler.currentTime }, { 0L })
        coordinator.read("route", "saved", { "runtime" }) { received ->
            received(GatewayJobPollResponse(202, "2"))
            pending
        }
        coordinator.observeProgress("route", "saved", GatewayProgress(jobId = "saved", status = "completed", resultAvailable = true))
        var reads = 0
        val next = async {
            coordinator.read("route", "saved", { "runtime" }) { received ->
                reads++
                received(GatewayJobPollResponse(200, null))
                complete
            }
        }
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(0, reads)
        assertFalse(next.isCompleted)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(complete, next.await())
        assertEquals(1, reads)
    }

    @Test fun verifiedMutationInvalidatesUnboundObservationButNotOtherCredentials() = runTest {
        val coordinator = GatewayJobPollCoordinator({ testScheduler.currentTime }, { 0L })
        val config = ProviderRequestConfig("https://gateway.example/v1", "account")
        val mutation = config.copy(extraHeaders = mapOf("X-Gateway-Installation-ID" to "installation", "X-Gateway-Principal-ID" to "principal"))
        val other = config.copy(token = "other-account")
        for (scope in listOf(config, mutation, other)) {
            coordinator.read(gatewayJobObservationIdentity(scope), "saved", { "runtime" }) { received ->
                received(GatewayJobPollResponse(200, null))
                complete
            }
        }
        coordinator.invalidate(gatewayJobObservationIdentity(mutation), "saved")
        assertEquals(complete, coordinator.read(gatewayJobObservationIdentity(other), "saved", { "runtime" }) { error("Other credentials keep their own observation.") })
        var reads = 0
        val observed = async {
            coordinator.read(gatewayJobObservationIdentity(config), "saved", { "runtime" }) { received ->
                reads++
                received(GatewayJobPollResponse(200, null))
                complete.copy(status = "cancelled")
            }
        }
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(0, reads)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals("cancelled", observed.await()?.status)
        assertEquals(1, reads)
    }

    @Test fun cachePressureRetainsDeadlinesEvenWhenLargeResultBodiesAreReleased() = runTest {
        val coordinator = GatewayJobPollCoordinator({ testScheduler.currentTime }, { 0L })
        var reads = 0
        repeat(130) { index ->
            coordinator.read("route", "job-$index", { "runtime" }) { received ->
                reads++
                received(GatewayJobPollResponse(202, "2", if (index < 2) 4 * 1024 * 1024 else 0))
                pending.copy(jobId = "job-$index")
            }
        }
        val next = async {
            coordinator.read("route", "job-0", { "runtime" }) { received ->
                reads++
                received(GatewayJobPollResponse(200, null))
                complete.copy(jobId = "job-0")
            }
        }
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(130, reads)
        assertFalse(next.isCompleted)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals("completed", next.await()?.status)
        assertEquals(131, reads)
    }

    @Test fun contradictoryOrNonterminalReceiptsNeverStopFutureObservation() = runBlocking {
        for ((status, result) in listOf(202 to complete, 200 to complete.copy(terminal = false), 200 to complete.copy(continuationAllowed = true), 200 to pending)) {
            var now = 0L
            var reads = 0
            val coordinator = GatewayJobPollCoordinator({ now }, { now })
            suspend fun read() = coordinator.read("route", "saved", { "runtime" }) { received ->
                reads++
                received(GatewayJobPollResponse(status, null))
                result
            }
            read()
            now = 2_000L
            read()
            assertEquals(2, reads)
        }
    }
}
