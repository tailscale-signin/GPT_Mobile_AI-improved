package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkCase
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkQueueItem
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSample
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkQueueProgressTest {
    @Test fun restoredProgressUsesFrozenManifestAndKeepsFailedCasesCompleted() {
        val profile = PlatformV2(uid = "p", name = "Profile", model = "model")
        val item = BenchmarkQueueItem(0, profile, "run", "key", activeCaseId = "second", completedCases = 1)
        val first = BenchmarkCase("first", "First frozen case", "task", "Fixture")
        val second = BenchmarkCase("second", "Second frozen case", "task", "Fixture")
        val run = BenchmarkRun(
            "run", "p", "Profile", "OPENAI", "model", "key", false, BenchmarkMode.FULL, 1, plannedTrials = 99,
            caseManifest = listOf(first, second), samples = listOf(BenchmarkSample("first", "First frozen case", "task", BenchmarkOutcome.ERROR))
        )
        val progress = benchmarkQueueProgress(item, run)
        assertEquals(2, progress.planned)
        assertEquals(1, progress.completed)
        assertEquals(1, progress.remaining)
        assertEquals("Second frozen case", progress.currentCase)
    }

    @Test fun legacyAndNotYetAcceptedRunsNeverBorrowTheCurrentSuiteTotal() {
        val profile = PlatformV2(uid = "p", name = "Profile", model = "model")
        val item = BenchmarkQueueItem(0, profile, "run", "key", completedCases = 2)
        val legacy = BenchmarkRun("run", "p", "Profile", "OPENAI", "model", "key", false, BenchmarkMode.FULL, 1)
        assertNull(benchmarkQueueProgress(item, legacy).planned)
        assertNull(benchmarkQueueProgress(item, legacy).remaining)
        assertNull(benchmarkQueueProgress(item, null).planned)
        assertEquals(2, benchmarkQueueProgress(item, null).completed)
    }
}
