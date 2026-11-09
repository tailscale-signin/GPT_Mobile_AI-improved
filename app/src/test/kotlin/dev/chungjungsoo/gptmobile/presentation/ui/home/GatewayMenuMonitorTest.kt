package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayMenuMonitorTest {
    private val connection = GatewayMenuConnection(
        profileUid = "gateway",
        name = "Gateway",
        host = "private-gateway",
        routeIdentity = "route-hash",
        availability = GatewayAvailability(
            status = GatewayAvailabilityStatus.ONLINE,
            observedAtMillis = 1234,
            observedAtElapsedMillis = 0,
            instanceId = "instance"
        ),
        model = "selected-model"
    )

    @Test fun processCollectionAndLastRowsSurviveHomeViewModelReplacementWithoutRestartingDiscovery() = runTest {
        var starts = 0
        var stopped = 0
        val monitor = GatewayMenuMonitor(
            flow {
                starts++
                emit(listOf(connection))
                try {
                    awaitCancellation()
                } finally {
                    stopped++
                }
            },
            backgroundScope
        )
        runCurrent()
        assertEquals(1, starts)
        assertEquals(listOf(connection), monitor.connections.value)
        val first = GatewayMenuViewModel(monitor)
        val screen = backgroundScope.launch { first.connections.collect() }
        runCurrent()
        screen.cancelAndJoin()
        val replacement = GatewayMenuViewModel(monitor)
        assertSame(first.connections, replacement.connections)
        assertEquals(listOf(connection), replacement.connections.value)
        assertEquals(1, starts)
        assertEquals(0, stopped)
    }

    @Test fun aTransientInputFailureRetainsRowsAsUnverifiedAndRetriesWithoutAVisibilityReset() = runTest {
        var attempts = 0
        val monitor = GatewayMenuMonitor(
            flow {
                attempts++
                emit(listOf(connection))
                if (attempts == 1) throw IOException("settings read interrupted")
                awaitCancellation()
            },
            backgroundScope
        )
        runCurrent()
        val retained = monitor.connections.value.single()
        assertEquals(connection.profileUid, retained.profileUid)
        assertEquals(connection.model, retained.model)
        assertFalse(retained.availability.isOnline)
        assertEquals(GatewayAvailabilityStatus.ONLINE, retained.availability.lastObservedStatus)
        assertEquals(1234L, retained.availability.lastObservedAtMillis)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, attempts)
        assertTrue(monitor.connections.value.single().availability.isOnline)
    }
}
