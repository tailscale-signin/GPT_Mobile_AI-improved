package dev.chungjungsoo.gptmobile.data.github

import android.app.Application
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GitHubDecisionOwnerTest {
    @Test fun ownerReplacementWaitsForTheValidatedDurableAnswerClaim() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("decision-owner-race").toFile()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val chat = db.chatRoomDao().addChatRoom(ChatRoomV2(title = "Decision race")).toInt()
            val answer = MessageV2(id = 2, chatId = chat, platformType = "profile", currentRunId = "run", ownershipFence = 5, linkedMessageId = 1, content = "")
            db.messageDao().addMessages(MessageV2(id = 1, chatId = chat, platformType = null, content = "Implement"), answer)
            db.agentRunDao().upsert(AgentRun("run", chat, 1, 2, "profile", "CUSTOM", "model", "INTERRUPTED", ownershipFence = 5))
            val decisions = GitHubChatDecisions(GitHubRepositoryStateStore(directory))
            val proposal = GitHubChatDecision("a".repeat(64), chat, 1, "profile", "workspace", "Which layout?", "", listOf(GitHubDecisionOption("Compact"), GitHubDecisionOption("Spacious")), persistent = true, conversationIdentity = "conversation", runId = "run")
            val waiting = async { decisions.request(proposal) }
            decisions.waiting.first { it.isNotEmpty() }
            val offered = decisions.decisions.value.single()
            val validated = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val claim = async {
                db.withTransaction {
                    decisions.respondNotification(offered.id, requireNotNull(offered.notificationBinding()), 0) {
                        val owner = db.agentRunDao().ownsPendingDecision("run", chat, 1, "profile")
                        validated.complete(Unit)
                        release.await()
                        owner
                    }
                }
            }
            validated.await()
            val replacementStarted = CompletableDeferred<Unit>()
            val replacement = async(Dispatchers.IO) {
                replacementStarted.complete(Unit)
                db.messageDao().editMessages(answer.copy(currentRunId = "retry", ownershipFence = 6))
            }
            replacementStarted.await()
            assertFalse(replacement.isCompleted)
            release.complete(Unit)
            assertTrue(claim.await().accepted)
            org.junit.Assert.assertEquals("Compact", waiting.await().answer)
            replacement.await()
            assertFalse(db.agentRunDao().ownsPendingDecision("run", chat, 1, "profile"))
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    @Test fun cancellingNotificationAfterStorageStartsCannotExposeAnUnansweredInMemoryCopy() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("decision-cancellation").toFile()
        try {
            val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getNoBackupFilesDir() = directory
            }
            val secrets = mutableMapOf<String, ByteArray>()
            lateinit var saving: Job
            var cancelOnWrite = false
            val vault = object : SecretVault {
                override suspend fun put(secretRef: String, secret: ByteArray) {
                    secrets[secretRef] = secret.copyOf()
                }
                override suspend fun read(secretRef: String): ByteArray? {
                    if (cancelOnWrite) {
                        cancelOnWrite = false
                        saving.cancel()
                    }
                    return secrets[secretRef]?.copyOf()
                }
                override suspend fun delete(secretRef: String) {
                    secrets.remove(secretRef)
                }
            }
            val decisions = GitHubChatDecisions(GitHubRepositoryStateStore(context, vault))
            val proposal = GitHubChatDecision("a".repeat(64), 1, 2, "profile", "workspace", "Which layout?", "", listOf(GitHubDecisionOption("Compact"), GitHubDecisionOption("Spacious")), persistent = true, conversationIdentity = "conversation", runId = "run")
            val waiting = async { decisions.request(proposal) }
            decisions.waiting.first { it.isNotEmpty() }
            val offered = decisions.decisions.value.single()
            saving = launch(start = CoroutineStart.LAZY) { decisions.respondNotification(offered.id, requireNotNull(offered.notificationBinding()), 0) { true } }
            cancelOnWrite = true
            saving.start()
            saving.join()
            assertTrue(saving.isCancelled)
            org.junit.Assert.assertEquals("Compact", waiting.await().answer)
            assertFalse(decisions.respondNotification(offered.id, requireNotNull(offered.notificationBinding()), 1) { true }.accepted)
            val restored = GitHubChatDecisions(GitHubRepositoryStateStore(context, vault))
            restored.load()
            org.junit.Assert.assertEquals("Compact", restored.decisions.value.single().answer)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun notificationOwnershipRejectsRetriedMessagesChangedFencesAndTemporaryChats() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val chat = db.chatRoomDao().addChatRoom(ChatRoomV2(title = "Decision owner")).toInt()
            val user = MessageV2(id = 1, chatId = chat, platformType = null, content = "Implement the layout")
            val answer = MessageV2(id = 2, chatId = chat, platformType = "profile", currentRunId = "run", ownershipFence = 5, linkedMessageId = 1, content = "")
            db.messageDao().addMessages(user, answer)
            db.agentRunDao().upsert(AgentRun("run", chat, 1, 2, "profile", "CUSTOM", "model", "INTERRUPTED", ownershipFence = 5))
            assertTrue(db.agentRunDao().ownsPendingDecision("run", chat, 1, "profile"))
            assertFalse(db.agentRunDao().ownsPendingDecision("run", chat, 1, "different-profile"))
            assertFalse(db.agentRunDao().ownsPendingDecision("run", chat, 2, "profile"))
            db.messageDao().editMessages(answer.copy(currentRunId = "retry"))
            assertFalse(db.agentRunDao().ownsPendingDecision("run", chat, 1, "profile"))
            db.messageDao().editMessages(answer.copy(ownershipFence = 6))
            assertFalse(db.agentRunDao().ownsPendingDecision("run", chat, 1, "profile"))
            db.messageDao().editMessages(answer)
            db.chatRoomDao().rawUpdateTemporary(chat, true)
            assertFalse(db.agentRunDao().ownsPendingDecision("run", chat, 1, "profile"))
        } finally {
            db.close()
        }
    }
}
