package dev.chungjungsoo.gptmobile.data.research

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResearchAttemptRecoveryTest {
    private fun task() = AcceptedResearchTask(
        taskId = researchHash("task:root"), rootWorkId = "root", chatId = 1, originatingUserMessageId = 2,
        taskText = "Compare retained evidence", intent = "REQUIRED", participantUids = listOf("p"), primaryUid = "p",
        settingsRevision = "settings", sourceAuthorityRevision = "authority", acceptedAt = 1,
        acceptedPolicyJson = "{\"sourceGoal\":90,\"contentScope\":\"WEB_ONLY\"}", sourceScope = "WEB_ONLY", targetSources = 90,
        requestedModels = mapOf("p" to "pinned-model")
    )

    @Test fun atomicAcceptanceSurvivesMissingProjectionAndRepairsItWithoutDispatch() = runBlocking {
        val directory = Files.createTempDirectory("research-atomic-acceptance").toFile()
        try {
            ResearchTaskStore(directory).accept(task(), true)
            directory.listFiles().orEmpty().filter { it.extension == "task" }.forEach { it.delete() }
            val restarted = ResearchTaskStore(directory)
            val record = restarted.reconcile(setOf(1)).single()
            assertEquals(task(), record.accepted)
            assertEquals(ResearchAttemptState.ACCEPTED, record.attempt.state)
            assertEquals(ResearchNextAction.SCHEDULE, record.scheduling.action)
            assertTrue(record.attempt.dispatchedRunIds.isEmpty())
            assertEquals(1, directory.listFiles().orEmpty().count { it.extension == "task" })
            assertEquals(task(), restarted.accept(task().copy(acceptedPolicyJson = "changed", requestedModels = emptyMap()), true))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun longWaitResumesWithTheSameFenceAndOldCheckpointsCannotMutateNewAttempt() = runBlocking {
        val directory = Files.createTempDirectory("research-fenced-recovery").toFile()
        try {
            val store = ResearchTaskStore(directory)
            val accepted = store.accept(task(), true)
            val old = requireNotNull(store.execution("root", true)).attempt
            store.transition("root", old.attemptId, 0, ResearchAttemptState.WAITING_RESOURCE, true, reason = "BENCHMARK_TRIAL", queueMillis = 90_000)
            val restarted = ResearchTaskStore(directory)
            assertEquals(old.attemptId, restarted.execution("root", true)?.attempt?.attemptId)
            assertEquals(90_000L, restarted.execution("root", true)?.attempt?.queueMillis)
            restarted.transition("root", old.attemptId, 0, ResearchAttemptState.INTERRUPTED, true)
            val next = requireNotNull(restarted.resumeAttempt("root", "tap", true))
            assertEquals(next, restarted.resumeAttempt("root", "tap", true, expectedAttempt = old))
            assertTrue(runCatching { restarted.resumeAttempt("root", "stale-reviewed-tap", true, reconciled = true, expectedAttempt = old) }.isFailure)
            store.checkpoint(accepted, "stale-run", "public", 0, ResearchSnapshot(accepted.taskText, phase = "Searching"), true, old.attemptId, old.generation)
            assertNull(restarted.manifest("root", true))
            assertEquals(next, restarted.execution("root", true))
            restarted.checkpoint(accepted, "new-run", "public", 1, ResearchSnapshot(accepted.taskText, phase = "Searching"), true, next.attempt.attemptId, next.attempt.generation)
            assertNotNull(restarted.manifest("root", true))
            assertEquals(ResearchAttemptState.SEARCHING, restarted.execution("root", true)?.attempt?.state)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun legacyAcceptanceIsImportedAsInterruptedAndNeverSilentlyScheduled() = runBlocking {
        val directory = Files.createTempDirectory("research-legacy-import").toFile()
        try {
            File(directory, researchHash("task:root") + ".task").writeText(Json.encodeToString(task()))
            val restored = requireNotNull(ResearchTaskStore(directory).execution("root", true))
            assertEquals(ResearchAttemptState.INTERRUPTED, restored.attempt.state)
            assertEquals(ResearchNextAction.EXPLICIT_RESUME, restored.scheduling.action)
            assertTrue(restored.scheduling.acknowledged)
            assertTrue(restored.attempt.dispatchedRunIds.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun interruptedAtomicReplacementRestoresCommittedExecutionFromBackup() = runBlocking {
        val directory = Files.createTempDirectory("research-execution-backup").toFile()
        try {
            ResearchTaskStore(directory).accept(task(), true)
            val execution = directory.listFiles().orEmpty().single { it.extension == "execution" }
            assertTrue(execution.renameTo(File(execution.path + ".bak")))
            directory.listFiles().orEmpty().filter { it.extension == "task" }.forEach { it.delete() }
            val restored = ResearchTaskStore(directory).reconcile(setOf(1)).single()
            assertEquals(task(), restored.accepted)
            assertEquals(0L, restored.attempt.generation)
            assertTrue(execution.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun deletingChatFencesStaleWritersAcrossStoreRestart() = runBlocking {
        val directory = Files.createTempDirectory("research-deleted-attempt").toFile()
        try {
            val stale = ResearchTaskStore(directory)
            stale.accept(task(), true)
            ResearchTaskStore(directory).clearChat(1)
            val restarted = ResearchTaskStore(directory)
            assertNull(stale.execution("root", true))
            assertNull(restarted.execution("root", true))
            assertTrue(runCatching { stale.accept(task(), true) }.isFailure)
            assertTrue(runCatching { restarted.accept(task(), true) }.isFailure)
            assertTrue(restarted.reconcile(setOf(1)).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
