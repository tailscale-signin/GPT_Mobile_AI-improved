package dev.chungjungsoo.gptmobile.data.voice

import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceRecognitionBenchmarkResumeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun interruptedCpuCohortStopsTheCampaignAndRerunsAsAWholeWhenContinued() = runBlocking {
        val stage = VoiceBenchStage("Recognition CPU calibration", threads = 2, rate = 1f, probe = VoiceBenchProbe.RECOGNITION, listeningStyle = VoiceListeningStyle.NATURAL)
        val plan = listOf(stage, stage.copy(label = "Following benchmark"))
        val pending = plan.map { VoiceBenchResult(it.label) }
        val store = VoiceBenchmarkSuiteStore(temp.newFolder())
        val initial = VoiceBenchManifest(
            runId = "1800000000000-${UUID.randomUUID()}",
            startedAtMillis = 1_800_000_000_000,
            build = 153,
            commit = "a".repeat(40),
            androidApi = 37,
            results = pending,
            continuationSignature = "matched-recognition-cohort"
        )
        store.begin(initial, VoicePerformanceSnapshot(), null)
        val measured = mutableListOf<Int>()
        VoiceBenchmarkCampaign(store, {}).run(initial, plan, { null }, { true }) { index, current ->
            measured += index
            store.evidence(initial.runId, index) { it.write("""{"completedThreadCandidates":1,"interruptedCandidate":2}""".toByteArray()) }
            listOf(
                VoiceBenchObservation("cpu-1/ready", VoiceBenchStatus.MEASURED, metrics = mapOf("wordErrorRate" to 0.0)),
                VoiceBenchObservation("cpu-2/weather", VoiceBenchStatus.INTERRUPTED, "THERMAL_LIMIT"),
                VoiceBenchObservation.unavailable("recognition-cpu-recommendation", "RECOGNITION_CPU_COHORT_INCOMPLETE")
            ).summarize(current.label)
        }
        val stopped = requireNotNull(store.latest())
        assertEquals(listOf(0), measured)
        assertEquals("THERMAL_LIMIT", stopped.state)
        assertEquals(VoiceBenchStatus.INTERRUPTED, stopped.results[0].status)
        assertEquals(VoiceBenchStatus.PENDING, stopped.results[1].status)
        assertTrue(stopped.canContinue("matched-recognition-cohort", initial.build, initial.commit, initial.androidApi, pending))
        assertTrue(stopped.reusableStages(pending).isEmpty())
        val next = initial.copy(
            runId = "1800000000001-${UUID.randomUUID()}",
            startedAtMillis = initial.startedAtMillis + 1,
            resumedFromRunId = stopped.runId
        )
        store.begin(next, VoicePerformanceSnapshot(), null, stopped)
        measured.clear()
        VoiceBenchmarkCampaign(store, {}).run(next, plan, { null }, { true }) { index, current ->
            measured += index
            VoiceBenchResult(current.label, VoiceBenchStatus.MEASURED)
        }
        assertEquals(listOf(0, 1), measured)
        assertEquals("FINISHED", store.latest()!!.state)
        val previousExport = ByteArrayOutputStream()
        store.export(requireNotNull(store.previous(store.latest()!!)), previousExport)
        assertTrue(previousExport.toString("UTF-8").contains("\"interruptedCandidate\":2"))
    }
}
