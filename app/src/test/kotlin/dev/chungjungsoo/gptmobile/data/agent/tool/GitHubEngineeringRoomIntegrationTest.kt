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

    @Test fun versionOneLedgerMigratesAndSurvivesReopenWithoutLosingTasks() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "engineering-migration-${java.util.UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        requireNotNull(file.parentFile).mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE engineering_tasks (taskId TEXT NOT NULL PRIMARY KEY, repositoryOwner TEXT NOT NULL, repositoryName TEXT NOT NULL, workingBranch TEXT NOT NULL, baseSha TEXT NOT NULL, requiresHardwareVerification INTEGER NOT NULL, requiresHumanApproval INTEGER NOT NULL, state TEXT NOT NULL, sequence INTEGER NOT NULL, attempt INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE engineering_events (eventId TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, sequence INTEGER NOT NULL, attempt INTEGER NOT NULL, previousState TEXT NOT NULL, evidence TEXT NOT NULL, resultingState TEXT NOT NULL, evidenceDigest TEXT NOT NULL, recordedAtEpochMs INTEGER NOT NULL, FOREIGN KEY(taskId) REFERENCES engineering_tasks(taskId) ON UPDATE NO ACTION ON DELETE RESTRICT)")
            old.execSQL("CREATE UNIQUE INDEX index_engineering_events_taskId_sequence ON engineering_events(taskId, sequence)")
            old.execSQL("CREATE TABLE engineering_git_operations (operationId TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, idempotencyToken TEXT NOT NULL, expectedHeadSha TEXT NOT NULL, stage TEXT NOT NULL, blobManifestDigest TEXT, treeSha TEXT, commitSha TEXT, remoteRefSha TEXT, updatedAtEpochMs INTEGER NOT NULL, FOREIGN KEY(taskId) REFERENCES engineering_tasks(taskId) ON UPDATE NO ACTION ON DELETE RESTRICT)")
            old.execSQL("CREATE INDEX index_engineering_git_operations_taskId ON engineering_git_operations(taskId)")
            old.execSQL("CREATE UNIQUE INDEX index_engineering_git_operations_idempotencyToken ON engineering_git_operations(idempotencyToken)")
            old.execSQL("INSERT INTO engineering_tasks VALUES ('legacy', 'owner', 'repo', 'fix', ?, 0, 1, 'CREATED', 0, 1, 100)", arrayOf("a".repeat(40)))
            old.version = 1
        }
        fun open() = Room.databaseBuilder(context, GitHubEngineeringDatabase::class.java, name).addMigrations(GitHubEngineeringDatabaseFactory.MIGRATION_1_2).build()
        try {
            val migrated = open()
            try {
                assertEquals("CREATED", migrated.ledgerDao().task("legacy")?.state)
                migrated.ledgerDao().saveRun(EngineeringRunRow("legacy", "connection", "[]", "d".repeat(64), "Fix", 100, "main"))
            } finally {
                migrated.close()
            }
            val reopened = open()
            try {
                assertEquals("connection", reopened.ledgerDao().run("legacy")?.connectionUid)
                assertEquals("CREATED", reopened.ledgerDao().task("legacy")?.state)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
