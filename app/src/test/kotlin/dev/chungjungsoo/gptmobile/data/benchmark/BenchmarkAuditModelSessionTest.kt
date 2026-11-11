package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.audit.AppAuditModelQualificationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkAuditModelSessionTest {
    @Test fun ownedBindingDeadlineIsAZeroDispatchRejectionWhileOwnerTimeoutKeepsCancellation() = runTest {
        var opened = 0
        val guard = BenchmarkAuditModelSession("a".repeat(64), { benchmarkAuditBindingWithinDeadline(5) { delay(10) } })
        val rejected = runCatching {
            guard.open {
                opened++
                session {}
            }
        }.exceptionOrNull()
        assertEquals("AUDIT_MODEL_BINDING_UNAVAILABLE", (rejected as AppAuditModelQualificationException).code)
        assertEquals(0, opened)
        assertFalse(guard.receipt.beforeGenerationVerified)
        val canceled = runCatching { withTimeout(5) { benchmarkAuditBindingWithinDeadline(10) { delay(20) } } }.exceptionOrNull()
        assertTrue(canceled is CancellationException)
        assertFalse(canceled is AppAuditModelQualificationException)
    }

    @Test fun configurationChangedAfterOpeningCannotDispatchEvenOneRound() = runBlocking {
        var current = true
        var opened = 0
        var dispatched = 0
        val guard = BenchmarkAuditModelSession("a".repeat(64), { if (!current) throw AppAuditModelQualificationException("AUDIT_MODEL_CONFIGURATION_CHANGED") }, { 100 })
        val session = guard.open {
            opened++
            session { dispatched++ }
        }
        current = false
        val failure = runCatching { session.streamRound(emptyList(), emptyList()).toList() }.exceptionOrNull()
        assertTrue(failure is AppAuditModelQualificationException)
        assertEquals(1, opened)
        assertEquals(0, dispatched)
        assertFalse(guard.receipt.beforeGenerationVerified)
        assertFalse(guard.receipt.afterGenerationVerified)
        assertEquals("admission", benchmarkFailure(checkNotNull(failure)).stage)
        assertFalse(benchmarkFailure(failure).retryable)
    }

    @Test fun changedBindingBeforeOpeningNeverCreatesProviderSession() = runBlocking {
        var opened = 0
        val guard = BenchmarkAuditModelSession("a".repeat(64), { throw AppAuditModelQualificationException("AUDIT_MODEL_BUILD_CHANGED") })
        val failure = runCatching {
            guard.open {
                opened++
                session {}
            }
        }.exceptionOrNull()
        assertEquals(0, opened)
        assertEquals("AUDIT_MODEL_BUILD_CHANGED", (failure as AppAuditModelQualificationException).code)
    }

    @Test fun completedOutputCannotHideAConfigurationChangeOrTriggerReplay() = runBlocking {
        var current = true
        var dispatched = 0
        val observed = mutableListOf<ProviderEvent>()
        val guard = BenchmarkAuditModelSession("a".repeat(64), { if (!current) throw AppAuditModelQualificationException("AUDIT_MODEL_CONFIGURATION_CHANGED") }, { 100 })
        val session = guard.open {
            session {
                dispatched++
                current = false
            }
        }
        val failure = runCatching { session.streamRound(emptyList(), emptyList()).toList(observed) }.exceptionOrNull()
        assertEquals(listOf(ProviderEvent.TextDelta("READY"), ProviderEvent.Completed), observed)
        assertEquals("qualification", benchmarkFailure(checkNotNull(failure)).stage)
        assertTrue(guard.receipt.beforeGenerationVerified)
        assertFalse(guard.receipt.afterGenerationVerified)
        current = true
        runCatching { session.streamRound(emptyList(), emptyList()).toList() }
        assertEquals(1, dispatched)
        val rejected = BenchmarkSample("case", "Case", "task", BenchmarkOutcome.ERROR, failureCategory = "AUDIT_MODEL_CONFIGURATION_CHANGED")
        assertEquals(BenchmarkRecoveryAction.STOP_PROFILE, rejected.recoveryAction(allowConfirmedRecovery = true))
    }

    @Test fun everyContinuationRoundIsCheckedAndCancellationRemainsCancellation() = runBlocking {
        var current = true
        var dispatched = 0
        val guard = BenchmarkAuditModelSession("a".repeat(64), { if (!current) throw AppAuditModelQualificationException("AUDIT_MODEL_CONFIGURATION_CHANGED") }, { 100 })
        val session = guard.open { session { dispatched++ } }
        session.streamRound(emptyList(), emptyList()).toList()
        assertTrue(guard.receipt.afterGenerationVerified)
        assertEquals(1, guard.receipt.completedRounds)
        current = false
        runCatching { session.streamRound(emptyList(), emptyList()).toList() }
        assertEquals(1, dispatched)
        assertFalse(guard.receipt.afterGenerationVerified)
        val cancellation = CancellationException("cancelled at generation")
        val canceled = BenchmarkAuditModelSession("a".repeat(64), {})
        val cancelSession = canceled.open { session { throw cancellation } }
        assertTrue(runCatching { cancelSession.streamRound(emptyList(), emptyList()).toList() }.exceptionOrNull() === cancellation)
        assertEquals(1, canceled.receipt.admittedRounds)
        assertEquals(0, canceled.receipt.completedRounds)
        assertFalse(canceled.receipt.afterGenerationVerified)
    }

    private fun session(onDispatch: () -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
            onDispatch()
            emit(ProviderEvent.TextDelta("READY"))
            emit(ProviderEvent.Completed)
        }
    }
}
