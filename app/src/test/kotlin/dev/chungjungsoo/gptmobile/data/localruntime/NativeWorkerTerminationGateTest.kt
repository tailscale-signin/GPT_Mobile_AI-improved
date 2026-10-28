package dev.chungjungsoo.gptmobile.data.localruntime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWorkerTerminationGateTest {
    @Test
    fun firstTerminationWinsAndStateRemainsTerminal() {
        val gate = NativeWorkerTerminationGate()
        assertFalse(gate.isTerminating())
        assertTrue(gate.beginTermination())
        assertTrue(gate.isTerminating())
        assertFalse(gate.beginTermination())
    }

    @Test
    fun concurrentTerminationHasExactlyOneWinner() {
        val gate = NativeWorkerTerminationGate()
        val workers = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val wins = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            repeat(workers) {
                executor.execute {
                    try {
                        start.await()
                        if (gate.beginTermination()) wins.incrementAndGet()
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue("Termination attempts timed out", done.await(5, TimeUnit.SECONDS))
            assertEquals(1, wins.get())
            assertTrue(gate.isTerminating())
        } finally {
            executor.shutdownNow()
        }
    }
}
