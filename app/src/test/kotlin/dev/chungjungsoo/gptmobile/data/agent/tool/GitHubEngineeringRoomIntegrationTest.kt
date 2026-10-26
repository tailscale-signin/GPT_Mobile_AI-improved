package dev.chungjungsoo.gptmobile.data.agent.tool

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GitHubEngineeringRoomIntegrationTest {
    @Test fun evidenceAndStateCommitTogetherAndDuplicateSequenceRollsBack() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), GitHubEngineeringDatabase::class.java).build()
        try {
            val ledger = GitHubEngineeringRoomLedger(db) { 1000L }
            ledger.createTask(EngineeringTaskRow("task", "owner", "repo", "fix", "a".repeat(40), updatedAtEpochMs = 100))
            db.ledgerDao().saveRun(EngineeringRunRow("task", "connection", "[]", "d".repeat(64), "Fix", 100, "main"))
            var last: EngineeringLedgerEvent? = null
            val steps = listOf(
                EngineeringEvidence.INSPECTION_STARTED,
                EngineeringEvidence.PATCH_GENERATED,
                EngineeringEvidence.LOCAL_VALIDATION_STARTED,
                EngineeringEvidence.LOCAL_VALIDATION_PASSED,
                EngineeringEvidence.APPROVAL_REQUIRED,
                EngineeringEvidence.APPROVAL_GRANTED,
                EngineeringEvidence.REMOTE_REF_CONFIRMED,
                EngineeringEvidence.CI_WATCH_STARTED
            )
            for (step in steps) {
                val task = requireNotNull(ledger.task("task"))
                val state = EngineeringState.valueOf(task.state)
                val event = EngineeringLedgerEvent("task", task.sequence + 1, task.attempt, state, step, GitHubEngineeringStateMachine.advance(state, step), "d".repeat(64))
                ledger.append("event-${event.sequence}", event)
                last = event
            }
            val count = db.ledgerDao().events("task").size
            assertTrue(runCatching { ledger.append("duplicate", requireNotNull(last)) }.isFailure)
            assertEquals(count, db.ledgerDao().events("task").size)
            ledger.recordCi("task", "{\"sha\":\"verified-sha\"}", EngineeringCiVerdict.PASSED)
            assertEquals("CI_VERIFIED", ledger.task("task")?.state)
            assertNotNull(db.ledgerDao().run("task")?.verifiedChecksJson)
            val before = db.ledgerDao().run("task")
            assertTrue(runCatching { ledger.recordCi("task", "stale", EngineeringCiVerdict.FAILED) }.isFailure)
            assertEquals(before, db.ledgerDao().run("task"))
        } finally {
            db.close()
        }
    }
}
