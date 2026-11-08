package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    private fun target(model: String = "selected", token: String = "one", profileUid: String = "profile") = GatewayAvailabilityTarget(
        profileUid,
        "gateway",
        model,
        ProviderRequestConfig("http://192.168.1.2:8090/v1", token, profileUid = "gateway")
    )

    private fun ready(now: Long = 0) = GatewayReadinessObservation("a".repeat(32), true, true, true, false, now, availableWorkers = 1)

    @Test fun modelEvidenceIsRequiredAndBusyQueueingMustBeExplicitlyAdmitted() {
        val target = target()
        assertEquals(GatewayAvailabilityStatus.ONLINE, gatewayAvailability(target, ready(), 0).status)
        assertEquals(GatewayAvailabilityStatus.MODEL_UNAVAILABLE, gatewayAvailability(target, ready().copy(ready = false, selectedModelAvailable = false), 0).status)
        assertEquals(GatewayAvailabilityStatus.ATTENTION, gatewayAvailability(target, ready().copy(generationReady = null), 0).status)
        assertEquals(GatewayAvailabilityStatus.ATTENTION, gatewayAvailability(target, ready().copy(draining = true), 0).status)
        assertEquals(GatewayAvailabilityStatus.ATTENTION, gatewayAvailability(target, ready().copy(availableWorkers = 0), 0).status)
        assertEquals(GatewayAvailabilityStatus.ATTENTION, gatewayAvailability(target, ready().copy(availableWorkers = 0, canQueue = true, canAccept = false), 0).status)
        val queued = gatewayAvailability(target, ready().copy(availableWorkers = 0, canQueue = true, canAccept = true), 0)
        assertEquals(GatewayAvailabilityStatus.BUSY_QUEUE, queued.status)
        assertTrue(queued.isAvailable(19_999))
        assertFalse(queued.isAvailable(20_000))
        assertEquals(GatewayAvailabilityStatus.CHECKING, queued.at(20_000).status)
        assertEquals(GatewayAvailabilityStatus.ATTENTION, gatewayAvailability(target, null, 0).status)
    }

    @Test fun oldAndFutureObservationsCannotRemainOnline() {
        val observed = gatewayAvailability(target(), ready(5_000), 5_000)
        assertFalse(observed.isAvailable(4_999))
        assertTrue(observed.isAvailable(24_999))
        assertEquals(GatewayAvailabilityStatus.CHECKING, observed.at(25_000).status)
        val unavailable = gatewayAvailability(target(), ready(0).copy(selectedModelAvailable = false), 0)
        assertEquals(GatewayAvailabilityStatus.CHECKING, unavailable.at(20_000).status)
    }

    @Test fun simultaneousConsumersShareOneReadAndCancellationDoesNotKeepHiddenPollingAlive() = runTest {
        val response = CompletableDeferred<GatewayReadinessObservation>()
        var reads = 0
        val repository = GatewayAvailabilityRepository(
            GatewayAvailabilityProbe { _, _ ->
                reads++
                response.await()
            },
            { testScheduler.currentTime },
            networkEpoch = { 0 }
        )
        val first = async { repository.refresh(target()) }
        val second = async { repository.refresh(target(profileUid = "another-selected-profile")) }
        runCurrent()
        assertEquals(1, reads)
        response.complete(ready())
        assertEquals(first.await(), second.await())
        assertEquals(1, reads)
    }

    @Test fun noMoreThanTwoDifferentConnectionsAreProbedAtOnce() = runTest {
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        var reads = 0
        val repository = GatewayAvailabilityRepository(
            GatewayAvailabilityProbe { _, _ ->
                active++
                peak = maxOf(peak, active)
                reads++
                try {
                    release.await()
                    ready(testScheduler.currentTime)
                } finally {
                    active--
                }
            },
            { testScheduler.currentTime },
            networkEpoch = { 0 }
        )
        val pending = (1..8).map { index -> async { repository.refresh(target(model = "model-$index")) } }
        runCurrent()
        assertEquals(2, reads)
        assertEquals(2, peak)
        release.complete(Unit)
        pending.awaitAll()
        assertEquals(8, reads)
        assertEquals(2, peak)
    }

    @Test fun pollingStopsWithItsVisibleCollectorAndFreshObservationsAreShared() = runTest {
        var reads = 0
        val repository = GatewayAvailabilityRepository(
            GatewayAvailabilityProbe { _, _ ->
                reads++
                ready(testScheduler.currentTime)
            },
            { testScheduler.currentTime },
            networkEpoch = { 0 }
        )
        val observations = mutableListOf<GatewayAvailability>()
        val observer = backgroundScope.launch { repository.observe(listOf(target())).collect { observations += it.getValue("profile") } }
        runCurrent()
        assertEquals(1, reads)
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(1, reads)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, reads)
        assertTrue(observations.any { it.status == GatewayAvailabilityStatus.ONLINE })
        observer.cancelAndJoin()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, reads)
        assertEquals(GatewayAvailabilityStatus.CHECKING, repository.snapshot(target()).status)
    }

    @Test fun leavingTheScreenCancelsAnInflightProbeWithoutRecordingItAsOffline() = runTest {
        var cancelled = false
        var reads = 0
        val repository = GatewayAvailabilityRepository(
            GatewayAvailabilityProbe { _, _ ->
                reads++
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
            { testScheduler.currentTime },
            networkEpoch = { 0 }
        )
        val states = mutableListOf<GatewayAvailabilityStatus>()
        val observer = backgroundScope.launch { repository.observe(listOf(target())).collect { states += it.getValue("profile").status } }
        runCurrent()
        observer.cancelAndJoin()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(1, reads)
        assertFalse(GatewayAvailabilityStatus.OFFLINE in states)
        assertEquals(GatewayAvailabilityStatus.CHECKING, repository.snapshot(target()).status)
    }

    @Test fun routeRecoveryRequiresANewModelReadEvenWithinTheCacheLifetime() = runTest {
        val network = MutableStateFlow(false)
        var reads = 0
        var state: GatewayAvailability? = null
        val repository = GatewayAvailabilityRepository(
            GatewayAvailabilityProbe { _, _ ->
                reads++
                ready(testScheduler.currentTime)
            },
            { testScheduler.currentTime },
            network,
            { 0 }
        )
        val observer = backgroundScope.launch { repository.observe(listOf(target())).collect { state = it.getValue("profile") } }
        runCurrent()
        assertEquals(0, reads)
        assertEquals(GatewayAvailabilityStatus.OFFLINE, state?.status)
        network.value = true
        runCurrent()
        assertEquals(1, reads)
        network.value = false
        runCurrent()
        assertEquals(GatewayAvailabilityStatus.OFFLINE, state?.status)
        network.value = true
        runCurrent()
        assertEquals(2, reads)
        assertEquals(GatewayAvailabilityStatus.ONLINE, state?.status)
        observer.cancelAndJoin()
    }

    @Test fun modelCredentialAndNetworkChangesDoNotReuseAnotherObservation() = runTest {
        var reads = 0
        var epoch = 0L
        val repository = GatewayAvailabilityRepository(
            GatewayAvailabilityProbe { _, _ ->
                reads++
                ready(testScheduler.currentTime)
            },
            { testScheduler.currentTime },
            networkEpoch = { epoch }
        )
        repository.refresh(target())
        repository.refresh(target(model = "second"))
        repository.refresh(target(token = "new-token"))
        epoch++
        assertEquals(GatewayAvailabilityStatus.CHECKING, repository.snapshot(target()).status)
        repository.refresh(target())
        assertEquals(4, reads)
    }

    @Test fun oneShotConsumersObserveAnOfflineRouteWithoutStartingAPollingSubscription() = runTest {
        val network = MutableStateFlow(false)
        var reads = 0
        val repository = GatewayAvailabilityRepository(
            GatewayAvailabilityProbe { _, _ ->
                reads++
                ready(testScheduler.currentTime)
            },
            { testScheduler.currentTime },
            network,
            { 0 }
        )
        assertEquals(GatewayAvailabilityStatus.OFFLINE, repository.snapshot(target()).status)
        assertEquals(GatewayAvailabilityStatus.OFFLINE, repository.refresh(target()).status)
        assertEquals(0, reads)
        network.value = true
        assertEquals(GatewayAvailabilityStatus.ONLINE, repository.refresh(target()).status)
        network.value = false
        assertEquals(GatewayAvailabilityStatus.OFFLINE, repository.snapshot(target()).status)
        network.value = true
        repository.refresh(target())
        assertEquals(2, reads)
    }

    @Test fun configurationProblemsNeverDispatchAndTransportErrorsExposeNoPrivateDetails() = runTest {
        var reads = 0
        val repository = GatewayAvailabilityRepository(
            GatewayAvailabilityProbe { _, _ ->
                reads++
                throw IOException("private-token and private-host")
            },
            { testScheduler.currentTime },
            networkEpoch = { 0 }
        )
        val broken = target().copy(config = null, problem = "Select a gateway connection.")
        assertEquals(GatewayAvailabilityStatus.ATTENTION, repository.refresh(broken).status)
        assertEquals(GatewayAvailabilityStatus.ATTENTION, repository.refresh(target(model = "")).status)
        assertEquals(0, reads)
        val unavailable = repository.refresh(target())
        assertEquals(GatewayAvailabilityStatus.OFFLINE, unavailable.status)
        assertFalse(unavailable.toString().contains("private-token"))
        assertFalse(unavailable.toString().contains("private-host"))
    }

    @Test fun aProbeDeadlineCancelsTransportAndReportsOfflineWithoutRetryFanout() = runTest {
        var cancelled = false
        var reads = 0
        val repository = GatewayAvailabilityRepository(
            GatewayAvailabilityProbe { _, _ ->
                reads++
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
            { testScheduler.currentTime },
            networkEpoch = { 0 }
        )
        val attempt = async { repository.refresh(target()) }
        runCurrent()
        advanceTimeBy(6_001)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(GatewayAvailabilityStatus.OFFLINE, attempt.await().status)
        repository.refresh(target())
        assertEquals(1, reads)
    }

    @Test fun aCallerDeadlineRemainsCancellationAndCannotPoisonAvailability() = runTest {
        val repository = GatewayAvailabilityRepository(GatewayAvailabilityProbe { _, _ -> awaitCancellation() }, { testScheduler.currentTime }, networkEpoch = { 0 })
        val attempt = async { runCatching { withTimeout(1_000) { repository.refresh(target()) } } }
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(attempt.await().exceptionOrNull() is CancellationException)
        assertEquals(GatewayAvailabilityStatus.CHECKING, repository.snapshot(target()).status)
    }
}
