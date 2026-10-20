package dev.chungjungsoo.gptmobile.data.research.feature

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExaminationLifecycleTest {
    @Test fun recoveryFencesTheOldWorkerAndPreservesCheckpointAndHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ResearchDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.research()
            dao.work(ResearchWork("work", "Trial", "https://example.org/trial"))
            dao.job(ExaminationJob("job", "key", "work", null, ExaminationOrigin.USER_EXAMINE, 0, "profile", "public", "policy", ExaminationStage.QUEUED, updatedAt = 10, lease = 4, queuedAt = 10, workerId = "worker"))
            assertEquals(1, dao.advanceJob("job", 4, ExaminationStage.READING, "saved bytes and chunk 3", null, 20))
            assertEquals(1, dao.heartbeat("job", 4, "worker", 30))
            assertEquals(0, dao.heartbeat("job", 4, "other-worker", 40))
            val reading = requireNotNull(dao.job("job"))
            assertEquals(30, reading.lastHeartbeatAt)
            assertEquals(20, reading.updatedAt)
            assertEquals(20 + ExaminationLifecycle.phaseMillis(ExaminationStage.READING), reading.deadlineAt)
            dao.recoverJobs()
            val paused = requireNotNull(dao.job("job"))
            assertEquals(5, paused.lease)
            assertEquals("PROCESS_RECOVERY", paused.cancellationOrigin)
            assertEquals("saved bytes and chunk 3", paused.checkpointJson)
            assertEquals(0, paused.deadlineAt)
            assertEquals(0, dao.advanceJob("job", 4, ExaminationStage.COMPLETE_FULL, "stale", null, 50))
            assertEquals(0, dao.heartbeat("job", 4, "worker", 50))
            assertEquals(listOf(ExaminationStage.READING, ExaminationStage.PAUSED), dao.transitions("job").map { it.stage })
            dao.recoverJobs()
            assertEquals(2, dao.transitions("job").size)
        } finally {
            db.close()
        }
    }

    @Test fun terminalCommitStopsHeartbeatsAndCannotBeOverwrittenByCancellation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ResearchDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.research()
            dao.work(ResearchWork("work", "Trial", "https://example.org/trial"))
            dao.job(ExaminationJob("job", "key", "work", null, ExaminationOrigin.USER_EXAMINE, 0, "profile", "public", "policy", ExaminationStage.REVIEWING, updatedAt = 10, lease = 7, workerId = "worker"))
            assertEquals(1, dao.advanceJob("job", 7, ExaminationStage.COMPLETE_PARTIAL, "accepted", "limited coverage", 20))
            assertEquals(0, dao.heartbeat("job", 7, "worker", 30))
            assertEquals(0, dao.interruptJob("job", 7, ExaminationStage.PAUSED, "late pause", "USER_PAUSE", 30))
            assertEquals(0, dao.advanceJob("job", 7, ExaminationStage.FAILED, "late failure", null, 30))
            val done = requireNotNull(dao.job("job"))
            assertEquals(ExaminationStage.COMPLETE_PARTIAL, done.stage)
            assertEquals("accepted", done.checkpointJson)
            assertEquals(0, done.deadlineAt)
            assertNull(done.cancellationOrigin)
            assertEquals(1, dao.transitions("job").size)
        } finally {
            db.close()
        }
    }

    @Test fun queueCancellationDoesNotInventAStartedExecution() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ResearchDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.research()
            dao.work(ResearchWork("work", "Trial", "https://example.org/trial"))
            dao.job(ExaminationJob("job", "key", "work", null, ExaminationOrigin.USER_EXAMINE, 0, "profile", "public", "policy", ExaminationStage.QUEUED, updatedAt = 10, lease = 2))
            assertEquals(1, dao.advanceJob("job", 2, ExaminationStage.PAUSED, "saved", "Queue deadline", 20, "QUEUE_DEADLINE"))
            assertEquals(0L, dao.job("job")?.startedAt)
            assertEquals(0L, dao.job("job")?.lastHeartbeatAt)
            assertEquals("QUEUE_DEADLINE", dao.job("job")?.cancellationOrigin)
            dao.chatlessFence("work", 30)
            assertEquals("USER_DELETE", dao.job("job")?.cancellationOrigin)
            assertEquals(0, dao.advanceJob("job", 2, ExaminationStage.READING, "stale", null, 40))
            assertEquals(2, dao.transitions("job").size)
        } finally {
            db.close()
        }
    }

    @Test fun userPauseIsDurableAndDoesNotBecomeAutomaticProcessRecovery() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ResearchDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.research()
            dao.work(ResearchWork("work", "Trial", "https://example.org/trial"))
            dao.job(ExaminationJob("job", "key", "work", null, ExaminationOrigin.USER_EXAMINE, 0, "profile", "public", "policy", ExaminationStage.QUEUED, updatedAt = 10, lease = 2))
            assertEquals(1, dao.interruptJob("job", 2, ExaminationStage.PAUSED, "Paused; completed evidence retained", "USER_PAUSE", 20))
            dao.recoverJobs()
            assertEquals("USER_PAUSE", dao.job("job")?.cancellationOrigin)
            assertTrue(requireNotNull(dao.job("job")?.terminalReason).startsWith("Paused;"))
            assertEquals(0, dao.advanceJob("job", 2, ExaminationStage.READING, "stale", null, 30))
        } finally {
            db.close()
        }
    }
}
