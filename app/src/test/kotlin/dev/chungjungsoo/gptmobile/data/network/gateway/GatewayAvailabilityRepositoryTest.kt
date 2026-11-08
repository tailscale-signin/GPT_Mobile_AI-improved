package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import java.io.IOException
import java.net.ConnectException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayAvailabilityRepositoryTest {
    private val config = ProviderRequestConfig("http://10.0.0.2:8090/v1", "selected-token", profileUid = "gateway")
    private fun target(uid: String = "a", model: String = "exact-model", connection: ProviderRequestConfig = config) =
        GatewayAvailabilityTarget(uid, "gateway", "Gateway", model, connection, false)
    private fun ready(at: Long, modelAvailable: Boolean? = true, generationReady: Boolean? = true, draining: Boolean = false, instance: String = "a".repeat(32)) =
        GatewayAvailabilityProbe(
            GatewayCapabilities(gatewayVersion = "15.0.0", instanceId = instance),
            GatewayReadinessObservation(instance, modelAvailable == true && generationReady == true, generationReady, modelAvailable, draining, at)
        )
    private fun TestScope.repository(epoch: () -> Long = { 0L }, process: (ProviderRequestConfig) -> String = { "unobserved" }, route: Flow<Boolean> = flowOf(true), wall: () -> Long = { testScheduler.currentTime }, source: GatewayAvailabilitySource) =
        GatewayAvailabilityRepository(source, backgroundScope, { testScheduler.currentTime }, wall, epoch, process, route)

    @Test fun observersOfSameAuthenticatedRouteAndModelShareChecksAndLastDetachCancelsReads() = runTest {
        var calls = 0
        var cancelled = 0
        val repository = repository { _, model, _ ->
            assertEquals("exact-model", model)
            calls++
            if (calls > 1) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            }
            ready(testScheduler.currentTime)
        }
        var first = emptyMap<String, GatewayAvailability>()
        var second = emptyMap<String, GatewayAvailability>()
        val one = backgroundScope.launch { repository.observe(listOf(target())).collect { first = it } }
        val two = backgroundScope.launch { repository.observe(listOf(target("b", connection = config.copy(profileUid = "same-route")))).collect { second = it } }
        runCurrent()
        assertEquals(1, calls)
        assertTrue(first.getValue("a").isOnline)
        assertTrue(second.getValue("b").isOnline)
        one.cancelAndJoin()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, calls)
        two.cancelAndJoin()
        runCurrent()
        assertEquals(1, cancelled)
        advanceTimeBy(100_000)
        assertEquals(2, calls)
    }

    @Test fun cachedReadinessExpiresAtItsOriginalObservationAndNeverBecomesFreshAgain() = runTest {
        val cached = ready(0)
        val repository = repository { _, _, _ -> cached }
        var current = GatewayAvailability()
        backgroundScope.launch { repository.observe(listOf(target())).collect { current = it.getValue("a") } }
        runCurrent()
        assertTrue(current.isOnline)
        advanceTimeBy(19_999)
        runCurrent()
        assertTrue(current.isOnline)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(GatewayAvailabilityStatus.CHECKING, current.status)
        assertEquals(0L, current.observedAtElapsedMillis)
        advanceTimeBy(40_000)
        runCurrent()
        assertFalse(current.isOnline)
    }

    @Test fun latencyDoesNotMoveTheNextScheduledCheckAndConcurrencyIsBounded() = runTest {
        val starts = mutableListOf<Long>()
        var active = 0
        var maximum = 0
        val repository = repository { _, _, _ ->
            starts += testScheduler.currentTime
            active++
            maximum = maxOf(maximum, active)
            try {
                delay(2_000)
                ready(testScheduler.currentTime)
            } finally {
                active--
            }
        }
        backgroundScope.launch { repository.observe((1..4).map { target("p$it", model = "model-$it") }).collect() }
        runCurrent()
        assertEquals(listOf(0L, 0L), starts)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, maximum)
        assertEquals(listOf(0L, 0L, 2_000L, 2_000L, 10_000L, 10_000L), starts)
    }

    @Test fun changedNetworkCancelsOldReadAndChangedModelOrCredentialCannotReuseOnlineEvidence() = runTest {
        var epoch = 0L
        var cancelled = false
        val calls = mutableListOf<Pair<String, String?>>()
        val repository = repository(epoch = { epoch }) { connection, model, _ ->
            calls += model to connection.token
            if (calls.size == 1) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            ready(testScheduler.currentTime)
        }
        var current = GatewayAvailability()
        backgroundScope.launch { repository.observe(listOf(target())).collect { current = it.getValue("a") } }
        runCurrent()
        epoch++
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(cancelled)
        assertTrue(current.isOnline)
        backgroundScope.launch { repository.observe(listOf(target("b", model = "changed-model", connection = config.copy(token = "new-account")))).collect() }
        runCurrent()
        assertEquals("changed-model" to "new-account", calls.last())
    }

    @Test fun connectedWithoutExactGenerationReadinessOrWhileDrainingNeverGetsOnline() = runTest {
        for (probe in listOf(ready(0, modelAvailable = null), ready(0, modelAvailable = false), ready(0, generationReady = false), ready(0, draining = true), ready(0).copy(readiness = null), ready(0).copy(capabilities = null))) {
            val repository = repository { _, _, _ -> probe }
            var current = GatewayAvailability()
            val collecting = backgroundScope.launch { repository.observe(listOf(target())).collect { current = it.getValue("a") } }
            runCurrent()
            assertFalse(current.isOnline)
            collecting.cancelAndJoin()
        }
    }

    @Test fun mismatchedProcessAndDeniedAccessNeverProduceGreenOrProbeAnInvalidBinding() = runTest {
        val repository = repository { _, _, _ -> ready(0).copy(capabilities = GatewayCapabilities(gatewayVersion = "15.0.0", instanceId = "b".repeat(32))) }
        var current = GatewayAvailability()
        val collecting = backgroundScope.launch { repository.observe(listOf(target())).collect { current = it.getValue("a") } }
        runCurrent()
        assertFalse(current.isOnline)
        collecting.cancelAndJoin()
        val denied = repository { _, _, _ -> throw GatewayDashboardAccessDenied() }
        backgroundScope.launch {
            denied.observe(listOf(target(), target("invalid").copy(config = null))).collect {
                current = it.getValue("a")
                assertEquals(GatewayAvailabilityStatus.CONFIGURATION_REQUIRED, it.getValue("invalid").status)
            }
        }
        runCurrent()
        assertEquals(GatewayAvailabilityStatus.ACCESS_DENIED, current.status)
    }

    @Test fun busyQueueingLoadingAndAdmissionRequireExplicitEvidence() = runTest {
        val base = requireNotNull(ready(0).readiness)
        for ((readiness, expected) in listOf(
            base.copy(availableWorkers = 0, canAccept = true, canQueue = true) to GatewayAvailabilityStatus.BUSY_QUEUE,
            base.copy(availableWorkers = 0, canAccept = true, canQueue = null) to GatewayAvailabilityStatus.NOT_READY,
            base.copy(availableWorkers = 0, canAccept = false, canQueue = true) to GatewayAvailabilityStatus.NOT_READY,
            base.copy(availableWorkers = 1, canAccept = false) to GatewayAvailabilityStatus.NOT_READY,
            base.copy(availableWorkers = 1, modelLoading = true) to GatewayAvailabilityStatus.MODEL_LOADING,
            base.copy(availableWorkers = 1) to GatewayAvailabilityStatus.ONLINE
        )) {
            var current = GatewayAvailability()
            val repository = repository { _, _, _ -> ready(0).copy(readiness = readiness) }
            val collecting = backgroundScope.launch { repository.observe(listOf(target())).collect { current = it.getValue("a") } }
            runCurrent()
            assertEquals(expected, current.status)
            assertEquals(expected in setOf(GatewayAvailabilityStatus.ONLINE, GatewayAvailabilityStatus.BUSY_QUEUE), current.isOnline)
            collecting.cancelAndJoin()
        }
    }

    @Test fun routeLossClearsOnlineImmediatelyAndRecoveryForcesANewReadWithinCacheLifetime() = runTest {
        val route = MutableStateFlow(true)
        val refreshes = mutableListOf<Boolean>()
        val repository = repository(route = route) { _, _, refresh ->
            refreshes += refresh
            ready(testScheduler.currentTime)
        }
        var current = GatewayAvailability()
        backgroundScope.launch { repository.observe(listOf(target())).collect { current = it.getValue("a") } }
        runCurrent()
        assertTrue(current.isOnline)
        route.value = false
        runCurrent()
        assertEquals(GatewayAvailabilityStatus.OFFLINE, current.status)
        advanceTimeBy(1_000)
        assertEquals(listOf(false), refreshes)
        route.value = true
        runCurrent()
        assertEquals(listOf(false, true), refreshes)
        assertTrue(current.isOnline)
    }

    @Test fun offlineRouteAndInvalidConfigurationNeverStartMetadataReads() = runTest {
        var calls = 0
        val repository = repository(route = MutableStateFlow(false)) { _, _, _ ->
            calls++
            ready(0)
        }
        var states = emptyMap<String, GatewayAvailability>()
        backgroundScope.launch { repository.observe(listOf(target(), target("invalid").copy(config = null))).collect { states = it } }
        runCurrent()
        assertEquals(GatewayAvailabilityStatus.OFFLINE, states.getValue("a").status)
        assertEquals(GatewayAvailabilityStatus.CONFIGURATION_REQUIRED, states.getValue("invalid").status)
        advanceTimeBy(30_000)
        assertEquals(0, calls)
    }

    @Test fun aCallerDeadlineDoesNotCancelAnotherObserverOrReportItOffline() = runTest {
        var calls = 0
        var cancelled = 0
        val repository = repository { _, _, _ ->
            calls++
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }
        var current = GatewayAvailability()
        val owner = backgroundScope.launch { repository.observe(listOf(target())).collect { current = it.getValue("a") } }
        val short = backgroundScope.launch { runCatching { withTimeout(100) { repository.observe(listOf(target("short"))).collect() } } }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(short.isCompleted)
        assertEquals(1, calls)
        assertEquals(0, cancelled)
        assertEquals(GatewayAvailabilityStatus.CHECKING, current.status)
        owner.cancelAndJoin()
        assertEquals(1, cancelled)
    }

    @Test fun aProbeDeadlineCancelsItsTransportAndCannotFanOutRetries() = runTest {
        var calls = 0
        var cancelled = 0
        val repository = repository { _, _, _ ->
            calls++
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }
        var current = GatewayAvailability()
        backgroundScope.launch { repository.observe(listOf(target())).collect { current = it.getValue("a") } }
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(GatewayAvailabilityStatus.CHECKING, current.status)
        assertEquals(1, cancelled)
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun aFutureReadinessObservationCannotBecomeOnline() = runTest {
        var current = GatewayAvailability()
        val repository = repository { _, _, _ -> ready(100_000) }
        backgroundScope.launch { repository.observe(listOf(target())).collect { current = it.getValue("a") } }
        runCurrent()
        assertEquals(GatewayAvailabilityStatus.CHECKING, current.status)
    }

    @Test fun serverAndClientCacheAgeCannotBeRenewedByWallClockRollback() = runTest {
        var wall = 50_000L
        val cached = ready(wall).let { it.copy(readiness = it.readiness?.copy(observedAtMillis = 123_000, observationAgeMillis = 15_000, receivedAtMonotonicMillis = 0)) }
        val repository = repository(wall = { wall }) { _, _, _ -> cached }
        var current = GatewayAvailability()
        backgroundScope.launch { repository.observe(listOf(target())).collect { current = it.getValue("a") } }
        runCurrent()
        assertTrue(current.isOnline)
        assertEquals(123_000L, current.observedAtMillis)
        wall -= 40_000
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(current.isOnline)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(GatewayAvailabilityStatus.CHECKING, current.status)
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(current.isOnline)
        assertEquals(123_000L, current.observedAtMillis)
    }

    @Test fun nearExpiredServerEvidenceExpiresAtItsExactDeadline() = runTest {
        val cached = ready(0).let { it.copy(readiness = it.readiness?.copy(observationAgeMillis = 19_985, receivedAtMonotonicMillis = 0)) }
        val repository = repository { _, _, _ -> cached }
        var current = GatewayAvailability()
        backgroundScope.launch { repository.observe(listOf(target())).collect { current = it.getValue("a") } }
        runCurrent()
        assertTrue(current.isOnline)
        advanceTimeBy(14)
        runCurrent()
        assertTrue(current.isOnline)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(GatewayAvailabilityStatus.CHECKING, current.status)
    }

    @Test fun onlyConfirmedConnectionFailureReportsOfflineWithoutPrivateDiagnosticText() = runTest {
        for ((failure, expected) in listOf(
            IOException("private wrapper", ConnectException("private route")) to GatewayAvailabilityStatus.OFFLINE,
            IOException("unverified private response") to GatewayAvailabilityStatus.CHECKING
        )) {
            val repository = repository { _, _, _ -> throw failure }
            var current = GatewayAvailability()
            val observing = backgroundScope.launch { repository.observe(listOf(target())).collect { current = it.getValue("a") } }
            runCurrent()
            assertEquals(expected, current.status)
            assertFalse(current.toString().contains("private"))
            observing.cancelAndJoin()
        }
    }

    @Test fun invalidExactModelsNeverDispatchAndTargetsDoNotExposeConnectionSecrets() = runTest {
        var calls = 0
        val repository = repository { _, _, _ ->
            calls++
            ready(0)
        }
        val targets = listOf("", " selected ", "bad\nmodel", "x".repeat(513)).mapIndexed { index, model -> target("invalid-$index", model) }
        var states = emptyMap<String, GatewayAvailability>()
        backgroundScope.launch { repository.observe(targets).collect { states = it } }
        runCurrent()
        assertEquals(0, calls)
        assertTrue(states.values.all { it.status == GatewayAvailabilityStatus.CONFIGURATION_REQUIRED })
        assertFalse(target().toString().contains("selected-token"))
        assertFalse(target().toString().contains("10.0.0.2"))
    }

    @Test fun aChangedConfigurationRevisionStartsItsOwnReadinessCheck() = runTest {
        val revisions = mutableListOf<String?>()
        val repository = repository { connection, _, _ ->
            revisions += connection.profileRevision
            ready(testScheduler.currentTime)
        }
        backgroundScope.launch { repository.observe(listOf(target())).collect() }
        runCurrent()
        backgroundScope.launch { repository.observe(listOf(target("new", connection = config.copy(profileRevision = "revision-2")))).collect() }
        runCurrent()
        assertEquals(listOf(null, "revision-2"), revisions)
    }
}
