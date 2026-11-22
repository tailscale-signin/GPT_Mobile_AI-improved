package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceSpeechBenchmarkStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun report(at: Long, state: VoiceSpeechBenchmarkRunState = VoiceSpeechBenchmarkRunState.COMPLETE) = VoiceSpeechBenchmarkReport(
        at,
        VoiceSpeechBenchmarkConfiguration(
            VoiceSpeechBenchmarkMode.QUICK, listOf(VoiceSpeechBackend.CPU), KokoroVoice.HEART, 2, 1f,
            "a".repeat(64), "runtime", 147, 34, "b".repeat(64)
        ),
        state = state
    )

    @Test fun savesReplaceTheCurrentRunAndRetainOnlyFiveIndependentRuns() {
        val directory = temporary.newFolder()
        val store = VoiceSpeechBenchmarkStore(directory)
        for (at in 1L..7L) assertTrue(store.save(report(at)))
        assertTrue(store.save(report(7).copy(issue = VoiceSpeechBenchmarkIssue.MODEL_UNAVAILABLE)))
        val restored = VoiceSpeechBenchmarkStore(directory).read()
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), restored.map { it.startedAtMillis })
        assertEquals(VoiceSpeechBenchmarkIssue.MODEL_UNAVAILABLE, restored.first().issue)
    }

    @Test fun processInterruptionRestoresPartialResultsWithoutClaimingCompletion() {
        val directory = temporary.newFolder()
        val store = VoiceSpeechBenchmarkStore(directory)
        assertTrue(store.save(report(1, VoiceSpeechBenchmarkRunState.RUNNING)))
        val restored = VoiceSpeechBenchmarkStore(directory).read().single()
        assertEquals(VoiceSpeechBenchmarkRunState.INTERRUPTED, restored.state)
        assertEquals(VoiceSpeechBenchmarkBackendState.NOT_RUN, restored.results.single().state)
        assertFalse(restored.results.single().verified)
        assertNull(restored.recommendation())
    }

    @Test fun corruptAndOversizedHistoryCannotProduceBenchmarksAndDoesNotBlockANewRun() {
        val directory = temporary.newFolder()
        val file = File(directory, "results-v1.json")
        file.writeText("not a report")
        val store = VoiceSpeechBenchmarkStore(directory)
        assertTrue(store.read().isEmpty())
        file.writeBytes(ByteArray(2 * 1024 * 1024 + 1))
        assertTrue(store.read().isEmpty())
        assertTrue(store.save(report(10)))
        assertEquals(10L, store.read().single().startedAtMillis)
    }
}
