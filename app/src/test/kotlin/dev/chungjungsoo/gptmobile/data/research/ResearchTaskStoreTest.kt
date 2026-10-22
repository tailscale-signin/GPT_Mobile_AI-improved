package dev.chungjungsoo.gptmobile.data.research

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResearchTaskStoreTest {
    @Test fun progressTimestampDoesNotDuplicateEvidenceButWorkCountersSurviveRestart() = kotlinx.coroutines.runBlocking {
        val dir = Files.createTempDirectory("research-timestamp").toFile()
        try {
            val store = ResearchTaskStore(dir)
            val accepted = store.accept(task(), true)
            val snapshot = ResearchSnapshot(accepted.taskText, sources = listOf(ResearchSource("S1", "https://example.org/study", "Study", "Read", "Retained evidence")), updatedAt = 10)
            repeat(20) { store.checkpoint(accepted, "run", "public", 0, snapshot.copy(updatedAt = 10L + it), true) }
            assertEquals(1, store.manifest(accepted.rootWorkId, true)!!.revisions.size)
            assertEquals(1, dir.listFiles().orEmpty().count { it.extension == "evidence" })
            store.checkpoint(accepted, "run", "public", 0, snapshot.copy(attempts = 2, updatedAt = 50), true)
            val restored = ResearchTaskStore(dir).retainedEvidence(accepted.rootWorkId, setOf("public"), true).single().snapshot
            assertEquals(2, restored.attempts)
            assertEquals(snapshot.sources, restored.sources)
            assertEquals(50L, restored.updatedAt)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun task(chat: Int = 1) = AcceptedResearchTask(taskId = researchHash("task:$chat:10"), rootWorkId = "$chat:10", chatId = chat, originatingUserMessageId = 10, taskText = "Compare the original evidence", intent = "REQUIRED", participantUids = listOf("a", "b"), primaryUid = "a", settingsRevision = "settings", sourceAuthorityRevision = "grants", acceptedAt = 100)

    @Test fun corpusAndDeliveryRetainAcceptedTaskLineageAndRejectDifferentTasks() = kotlinx.coroutines.runBlocking {
        val original = ResearchSnapshot("Compare evidence", taskId = "task-one", rootWorkId = "1:10", originatingUserMessageId = 10, taskChatId = 1)
        val restored = requireNotNull(ResearchSnapshot.parse(original.json()))
        val packet = ResearchCorpus.freeze(restored).json()
        assertEquals("task-one", packet.text("taskId"))
        assertEquals("1:10", packet.text("rootWorkId"))
        assertEquals(10, packet.number("originatingUserMessageId"))
        assertTrue(runCatching { ResearchCorpus.freezeAll(listOf(original, original.copy(taskId = "another-task"))) }.isFailure)
    }

    @Test fun journalBindsInternalStageTextToOriginalAcceptedRequest() = kotlinx.coroutines.runBlocking {
        val dir = Files.createTempDirectory("accepted-journal").toFile()
        try {
            val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val context = object : android.content.ContextWrapper(app) {
                override fun getFilesDir() = dir
            }
            val sessions = ResearchSessionStore(context)
            val accepted = sessions.acceptTask(1, "1:10", 10, "Compare the original evidence", listOf("a"), "a", "settings", "authority", true)
            val journal = sessions.journal("run", 1, true, "1:10")
            val bound = journal.bindTask(ResearchSnapshot("Internal condensed stage instruction"))
            assertEquals(accepted.taskText, bound.task)
            assertEquals(accepted.taskId, bound.taskId)
            journal.save(bound)
            assertEquals(accepted.taskId, journal.load()?.taskId)
            assertTrue(runCatching { journal.bindTask(bound.copy(taskId = "another-task")) }.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun acceptedTaskAndRecoveryAreImmutableAcrossRestart() = kotlinx.coroutines.runBlocking {
        val dir = Files.createTempDirectory("accepted-research").toFile()
        try {
            val first = ResearchTaskStore(dir)
            first.accept(task(), true)
            val second = ResearchTaskStore(dir)
            assertEquals(task(), second.accept(task().copy(acceptedAt = 200, settingsRevision = "later settings"), true))
            assertTrue(runCatching { second.accept(task().copy(taskText = "Hi"), true) }.isFailure)
            val request = ResearchResumeRequest("tap", task().taskId, "1:10", "checkpoint", "RETRY_FAILED_STAGE", "a", "evidence", "settings", 200)
            second.resume(request, true)
            assertEquals(request, second.resume(request.copy(requestedAt = 300, checkpointId = "newer display"), true))
            assertTrue(runCatching { second.resume(request.copy(destinationProfileUid = "unaccepted"), true) }.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun temporaryTasksNeverWriteAndDeletingOneChatPreservesTheOther() = kotlinx.coroutines.runBlocking {
        val dir = Files.createTempDirectory("accepted-research").toFile()
        try {
            val store = ResearchTaskStore(dir)
            store.accept(task(), false)
            store.accept(task(2), false)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
            store.clearChat(1)
            assertNull(store.accepted("1:10", false))
            assertNotNull(store.accepted("2:10", false))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun checkpointCannotAdoptAnotherTaskAndRetainsExactEvidenceDependencies() = kotlinx.coroutines.runBlocking {
        val dir = Files.createTempDirectory("accepted-research").toFile()
        try {
            val store = ResearchTaskStore(dir)
            val accepted = store.accept(task(), true)
            val source = ResearchSource("S1", "https://example.org", "Trial", "Read", "Methods and results retain exact evidence.", versionId = "revision", providerReceipts = listOf("physical-1"))
            store.checkpoint(accepted, "run", "private-owner", 2, ResearchSnapshot(accepted.taskText, sources = listOf(source)), true)
            val checkpoint = requireNotNull(dir.listFiles()?.singleOrNull { it.extension == "checkpoint" })
            val body = kotlinx.serialization.json.Json.decodeFromString<ResearchTaskCheckpoint>(checkpoint.readText())
            assertEquals("private-owner", body.dependencies.single().authority)
            assertEquals(researchHash(source.passage), body.dependencies.single().bodyHash)
            assertFalse(body.dependencies.single().publicCapture)
            assertTrue(runCatching { store.checkpoint(accepted, "run", "private-owner", 2, ResearchSnapshot("Hi"), true) }.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }
}
