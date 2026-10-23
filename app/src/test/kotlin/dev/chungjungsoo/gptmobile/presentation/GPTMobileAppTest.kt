package dev.chungjungsoo.gptmobile.presentation

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GPTMobileAppTest {
    @Test fun failedRecoveryRetriesOnlyUnfinishedStepsAndSerializesRetryClicks() = runTest {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        var migrations = 0
        var reads = 0
        var fail = true
        StartupRecoveryGate.start(scope) {
            StartupRecoveryGate.step("migration") { migrations++ }
            StartupRecoveryGate.step("profiles") {
                reads++
                if (fail) error("read failed")
            }
        }
        org.junit.Assert.assertTrue(runCatching { StartupRecoveryGate.await() }.isFailure)
        fail = false
        StartupRecoveryGate.retry()
        StartupRecoveryGate.retry()
        StartupRecoveryGate.await()
        assertEquals(1, migrations)
        assertEquals(2, reads)
        org.junit.Assert.assertTrue(StartupRecoveryGate.ready)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun immediateDispatcherCannotObserveThePreviousGateDuringStartup() = runTest {
        StartupRecoveryGate.start(this) {}
        StartupRecoveryGate.await()
        var observedPreviousCompletion = true
        val immediate = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        StartupRecoveryGate.start(immediate) { observedPreviousCompletion = StartupRecoveryGate.ready }
        StartupRecoveryGate.await()
        assertEquals(false, observedPreviousCompletion)
        immediate.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun delayedRecoveryHoldsBackgroundConsumersUntilReconciliationCompletes() = runTest {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val observed = mutableListOf<String>()
        StartupRecoveryGate.start(this) {
            release.await()
            observed += "reconciled"
        }
        val consumer = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            StartupRecoveryGate.await()
            observed += "worker read"
        }
        runCurrent()
        assertEquals(emptyList<String>(), observed)
        release.complete(Unit)
        consumer.join()
        assertEquals(listOf("reconciled", "worker read"), observed)
    }

    @Test
    fun `startup recovery gate waits for current maintenance job`() = runTest {
        val events = mutableListOf<String>()

        StartupRecoveryGate.start(this) { events += "recover" }
        StartupRecoveryGate.await()
        events += "observe"

        assertEquals(listOf("recover", "observe"), events)
    }

    @Test
    fun `startup interrupts persisted work before migrating credentials`() = runTest {
        val events = mutableListOf<String>()

        runStartupMaintenance(
            interruptPersistedWork = { events += "interrupt" },
            migrateSecrets = {
                events += "migrate"
                emptyList()
            }
        )

        assertEquals(listOf("interrupt", "migrate"), events)
    }
}
