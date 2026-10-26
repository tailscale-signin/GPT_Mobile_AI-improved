package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubEngineeringLedgerTest {
    private val digest = "a".repeat(64)
    private val initial = EngineeringLedgerSnapshot(taskId = "task-1")

    private fun started(sequence: Long = 1, taskId: String = "task-1") = EngineeringLedgerEvent(
        taskId = taskId,
        sequence = sequence,
        attempt = 1,
        previousState = EngineeringState.CREATED,
        evidence = EngineeringEvidence.INSPECTION_STARTED,
        resultingState = EngineeringState.INSPECTING,
        evidenceDigest = digest,
    )

    @Test
    fun acceptedEventAdvancesProjection() {
        assertEquals(
            EngineeringLedgerSnapshot("task-1", sequence = 1, state = EngineeringState.INSPECTING),
            GitHubEngineeringLedger.append(initial, started()),
        )
    }

    @Test
    fun rejectsSkippedSequence() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubEngineeringLedger.append(initial, started(sequence = 2))
        }
    }

    @Test
    fun rejectsCrossTaskEvent() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubEngineeringLedger.append(initial, started(taskId = "task-2"))
        }
    }

    @Test
    fun rejectsReplayOfSameEvent() {
        val advanced = GitHubEngineeringLedger.append(initial, started())
        assertThrows(IllegalArgumentException::class.java) {
            GitHubEngineeringLedger.append(advanced, started())
        }
    }

    @Test
    fun rejectsForgedCompletion() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubEngineeringLedger.append(
                initial,
                started().copy(resultingState = EngineeringState.COMPLETED),
            )
        }
    }
}
