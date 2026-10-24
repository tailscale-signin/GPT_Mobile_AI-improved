package dev.chungjungsoo.gptmobile.data.localruntime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufInitializationRecoveryTest {
    @Test fun probesExpireBeforeTheGeneralLoadWatchdogButWeightProgressKeepsItsOwnBudget() {
        for (stage in listOf("LIBRARY_LOADING", "VALIDATING_TEMPLATE", "INITIALIZING_BACKEND", "PROBING_DEVICE")) {
            val budget = requireNotNull(GgufLoadDeadline.budgetMs(stage))
            assertTrue(budget <= 20_000)
            assertFalse(GgufLoadDeadline.expired(stage, 100, 100 + budget - 1))
            assertTrue(GgufLoadDeadline.expired(stage, 100, 100 + budget))
        }
        assertFalse(GgufLoadDeadline.expired("LOADING_WEIGHTS", 0, 30_000))
        assertFalse(GgufLoadDeadline.expired("NATIVE_READY", 0, 180_000))
    }

    @Test fun advancingStagesKeepsTheSamePendingOperationAndQuarantinesOnlyTheInterruptedGpu() {
        val directory = Files.createTempDirectory("gguf-initialization").toFile()
        try {
            val journal = NativeOperationJournal(directory, processId = { 42 })
            journal.before("model|gpu", "LIBRARY_LOADING", "request", "worker")
            journal.stage("INITIALIZING_BACKEND", 7)
            assertEquals(listOf("INITIALIZING_BACKEND", "request", "worker", "7"), directory.resolve("pending-metadata").readLines().drop(2))
            val restarted = NativeOperationJournal(directory, exitCause = { _, _ -> "CONTROLLED_TERMINATION|reason=LOAD_STAGE_DEADLINE_EXCEEDED" }, processId = { 43 })
            assertTrue(restarted.isQuarantined("model|gpu"))
            assertFalse(restarted.isQuarantined("model|cpu"))
            restarted.before("model|cpu", "LIBRARY_LOADING")
            restarted.stage("ALLOCATING_CONTEXT", 8)
            restarted.finished()
            assertFalse(directory.resolve("pending").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun terminationReceiptNeedsTheExactPidAndAnExitWithinItsTimeWindow() {
        val directory = Files.createTempDirectory("worker-exit").toFile()
        try {
            NativeWorkerTermination.record(directory, 42, "request", "generation", "LOAD_STAGE_DEADLINE_EXCEEDED", GgufNativeProgress(7, "PROBING_DEVICE"), nowMs = 1_000)
            val receipt = NativeWorkerTermination.matched(directory, 42, 500, 1_100)
            assertTrue(requireNotNull(receipt).contains("operation=7|stage=PROBING_DEVICE"))
            assertNull(NativeWorkerTermination.matched(directory, 43, 500, 1_100))
            assertNull(NativeWorkerTermination.matched(directory, 42, 1_001, 1_100))
            assertNull(NativeWorkerTermination.matched(directory, 42, 500, 999))
            assertNull(NativeWorkerTermination.matched(directory, 42, 500, 17_000))
            NativeWorkerTermination.record(directory, 43, "", "generation", "CLIENT_SHUTDOWN", null, nowMs = 2_000)
            val shutdown = NativeWorkerTermination.snapshot(directory).single { it["process"] == "43" }
            assertEquals("CLIENT_SHUTDOWN", shutdown["reason"])
            assertEquals("", shutdown["stage"])
        } finally {
            directory.deleteRecursively()
        }
    }
}
