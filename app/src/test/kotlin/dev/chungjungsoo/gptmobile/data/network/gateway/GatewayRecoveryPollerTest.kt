package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.agent.GatewayOutcomeRecovery
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayRecoveryPollerTest {
    private val selected = PlatformV2(uid = "selected", name = "Selected", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8090/v1", model = "chosen-model")
    private val interrupted = AgentRun("run", 1, 1, 2, selected.uid, "LLAMA", selected.model, status = AgentRunStatus.INTERRUPTED, gatewayJobId = "saved-job", gatewayBaseUrl = selected.apiUrl)

    @Test fun waitingSavedJobIsReadAgainWithoutANetworkEventAndStopsAfterItsTerminalResult() = runTest {
        var saved = interrupted
        var candidateReads = 0
        val resultReads = mutableListOf<Long>()
        val poller = GatewayRecoveryPoller(
            backgroundScope,
            hasPending = {
                candidateReads++
                hasEligibleGatewayRecovery(listOf(saved), listOf(selected), emptySet())
            },
            reconcile = {
                resultReads += testScheduler.currentTime
                // The first two GETs return 202/WAITING; the third saved result is terminal.
                if (resultReads.size == 3) saved = saved.copy(status = AgentRunStatus.COMPLETED)
            }
        )
        poller.routeChanged(true)
        runCurrent()
        advanceTimeBy(120_001)
        runCurrent()
        assertEquals(listOf(0L, 60_000L, 120_000L), resultReads)
        advanceTimeBy(60_000)
        runCurrent()
        val stoppedReads = candidateReads
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(stoppedReads, candidateReads)
        assertEquals(3, resultReads.size)
    }

    @Test fun emptyRecoverySetHasNoTimerAndANewRunCanWakeTheSameMonitor() = runTest {
        var pending = false
        var inspections = 0
        var reads = 0
        val poller = GatewayRecoveryPoller(backgroundScope, {
            inspections++
            pending
        }, {
            reads++
            pending = false
        })
        poller.routeChanged(true)
        runCurrent()
        val initialInspections = inspections
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(initialInspections, inspections)
        assertEquals(0, reads)
        pending = true
        poller.wake()
        runCurrent()
        assertEquals(1, reads)
    }

    @Test fun repeatedWakeupsShareOneInflightReadAndDoNotBypassThePollInterval() = runTest {
        var reads = 0
        var concurrent = 0
        var maximumConcurrent = 0
        val release = CompletableDeferred<Unit>()
        val poller = GatewayRecoveryPoller(backgroundScope, { true }, {
            reads++
            concurrent++
            maximumConcurrent = maxOf(maximumConcurrent, concurrent)
            try {
                if (reads == 1) release.await()
            } finally {
                concurrent--
            }
        })
        poller.routeChanged(true)
        runCurrent()
        repeat(30) {
            poller.routeChanged(true)
            poller.wake()
        }
        runCurrent()
        assertEquals(1, reads)
        release.complete(Unit)
        runCurrent()
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(1, reads)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, reads)
        assertEquals(1, maximumConcurrent)
    }

    @Test fun losingTheRouteCancelsTheReadAndPreventsFurtherChecksUntilItReturns() = runTest {
        var reads = 0
        var cancellations = 0
        val poller = GatewayRecoveryPoller(backgroundScope, { true }, {
            reads++
            try {
                awaitCancellation()
            } finally {
                cancellations++
            }
        })
        poller.routeChanged(true)
        runCurrent()
        poller.routeChanged(false)
        runCurrent()
        assertEquals(1, cancellations)
        advanceTimeBy(3_600_000)
        poller.wake()
        runCurrent()
        assertEquals(1, reads)
        poller.routeChanged(true)
        runCurrent()
        assertEquals(2, reads)
    }

    @Test fun lifecycleCancellationStopsTheInflightReadAndAllFuturePolling() = runTest {
        val owner = SupervisorJob(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + owner)
        var reads = 0
        var cancelled = false
        val poller = GatewayRecoveryPoller(scope, { true }, {
            reads++
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        })
        poller.routeChanged(true)
        runCurrent()
        owner.cancelAndJoin()
        assertTrue(cancelled)
        poller.wake()
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, reads)
    }

    @Test fun failingLocalCandidateReadsHaveBoundedRetriesAndThenWaitForANewEvent() = runTest {
        var inspections = 0
        var failures = 0
        val poller = GatewayRecoveryPoller(backgroundScope, {
            inspections++
            error("local storage unavailable")
        }, { error("must not read a remote job") }, onFailure = { failures++ })
        poller.routeChanged(true)
        runCurrent()
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(3, inspections)
        assertEquals(3, failures)
    }

    @Test fun onlyRecoverableJobsWithTheSameEnabledUnambiguousProfileKeepPollingAlive() {
        assertTrue(hasEligibleGatewayRecovery(listOf(interrupted), listOf(selected), emptySet()))
        assertTrue(hasEligibleGatewayRecovery(listOf(interrupted.copy(status = AgentRunStatus.FAILED, terminalError = GatewayOutcomeRecovery.ERROR_CODE)), listOf(selected), emptySet()))
        assertTrue(hasEligibleGatewayRecovery(listOf(interrupted.copy(status = AgentRunStatus.BLOCKED, terminalError = GatewayOutcomeRecovery.PARKED_RESULT_PREFIX)), listOf(selected), emptySet()))
        for (status in listOf(AgentRunStatus.COMPLETED, AgentRunStatus.CANCELED, AgentRunStatus.RUNNING, AgentRunStatus.QUEUED, AgentRunStatus.PARTIAL)) {
            assertFalse(hasEligibleGatewayRecovery(listOf(interrupted.copy(status = status)), listOf(selected), emptySet()))
        }
        assertFalse(hasEligibleGatewayRecovery(listOf(interrupted), listOf(selected), setOf(interrupted.runId)))
        assertFalse(hasEligibleGatewayRecovery(listOf(interrupted), listOf(selected.copy(enabled = false)), emptySet()))
        assertFalse(hasEligibleGatewayRecovery(listOf(interrupted), listOf(selected.copy(uid = "other")), emptySet()))
        assertFalse(hasEligibleGatewayRecovery(listOf(interrupted), listOf(selected.copy(apiUrl = "http://127.0.0.1:9090/v1")), emptySet()))
        assertFalse(hasEligibleGatewayRecovery(listOf(interrupted), listOf(selected, selected.copy(name = "Duplicate")), emptySet()))
        assertFalse(hasEligibleGatewayRecovery(listOf(interrupted.copy(gatewayJobId = null)), listOf(selected), emptySet()))
    }
}
