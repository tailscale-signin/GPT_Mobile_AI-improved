package dev.chungjungsoo.gptmobile.data.memory

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryEnrichmentWakeupsTest {
    @Test fun restoredCooldownWakesOnceAtExpiryWithoutAnotherRuntimeEvent() = runTest {
        val savedUntil = MutableStateFlow(300_000L)
        val wakeTimes = mutableListOf<Long>()
        val collection = backgroundScope.launch {
            savedUntil.withEnrichmentCooldownExpiry { testScheduler.currentTime }.collect { wakeTimes += testScheduler.currentTime }
        }
        runCurrent()
        assertEquals(listOf(0L), wakeTimes)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(listOf(0L, 300_000L), wakeTimes)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(listOf(0L, 300_000L), wakeTimes)
        collection.cancel()
    }

    @Test fun newerCooldownCancelsTheOldTimerAndAlreadyExpiredStateDoesNotLoop() = runTest {
        val savedUntil = MutableStateFlow(5_000L)
        val wakeTimes = mutableListOf<Long>()
        val collection = backgroundScope.launch {
            savedUntil.withEnrichmentCooldownExpiry { testScheduler.currentTime }.collect { wakeTimes += testScheduler.currentTime }
        }
        runCurrent()
        advanceTimeBy(1_000)
        savedUntil.value = 10_000
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(listOf(0L, 1_000L), wakeTimes)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 10_000L), wakeTimes)
        savedUntil.value = 0
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 10_000L, 10_000L), wakeTimes)
        collection.cancel()
    }
}
