package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDecisionNotificationActionTest {
    private fun question(run: String = "run-1") = GitHubChatDecision(
        "a".repeat(64), 1, 2, "profile", "workspace", "Which layout?", "Choose before implementation.",
        listOf(GitHubDecisionOption("Compact"), GitHubDecisionOption("Spacious"), GitHubDecisionOption("Enter revision", enterValue = true)),
        persistent = true, conversationIdentity = "conversation", runId = run
    )

    @Test fun actionsAreBoundToTheCompleteOwnerAndCustomInputsOpenTheChat() {
        val offered = question().copy(offeredAtMillis = 100)
        val actions = offered.notificationActions()
        assertEquals(listOf(0, 1), actions.map { it.option })
        assertTrue(actions.all { it.binding == offered.notificationBinding() })
        assertNotEquals(offered.notificationBinding(), offered.copy(runId = "new-run").notificationBinding())
        assertNotEquals(offered.notificationBinding(), offered.copy(conversationIdentity = "restored").notificationBinding())
        assertNotEquals(offered.notificationBinding(), offered.copy(sourceMessageId = 3).notificationBinding())
        assertTrue(offered.copy(persistent = false).notificationActions().isEmpty())
        assertTrue(offered.copy(conversationIdentity = "").notificationActions().isEmpty())
        assertTrue(offered.copy(answer = "Compact").notificationActions().isEmpty())
        assertEquals(listOf(1, 0), offered.copy(recommended = 1).notificationActions().map { it.option })
    }

    @Test fun simultaneousNotificationAnswersAreSavedOnceAndSurviveRecreation() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("notification-choice").toFile()
        try {
            val decisions = GitHubChatDecisions(GitHubRepositoryStateStore(directory))
            val waiting = async { decisions.request(question()) }
            decisions.waiting.first { it.isNotEmpty() }
            val offered = decisions.decisions.value.single()
            val binding = requireNotNull(offered.notificationBinding())
            val attempts = (0 until 8).map { index -> async { decisions.respondNotification(offered.id, binding, index % 2) { true } } }.awaitAll()
            assertEquals(1, attempts.count { it.accepted })
            assertTrue(attempts.first { it.accepted }.waiting)
            val answer = waiting.await().answer
            val restored = GitHubChatDecisions(GitHubRepositoryStateStore(directory))
            restored.load()
            assertEquals(answer, restored.decisions.value.single().answer)
            assertFalse(restored.respondNotification(offered.id, binding, 1) { true }.accepted)
            assertEquals(answer, restored.request(question()).answer)
            assertTrue(directory.resolve("user-input").listFiles().orEmpty().isNotEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun resumedRunReissuesActionsAndRejectsTheOldNotification() = runBlocking {
        val decisions = GitHubChatDecisions(GitHubRepositoryStateStore())
        val first = async { decisions.request(question()) }
        decisions.waiting.first { it.isNotEmpty() }
        val original = decisions.decisions.value.single()
        first.cancelAndJoin()
        var reissued = 0
        val resumed = async { decisions.request(question("run-2")) { reissued++ } }
        decisions.waiting.first { it.isNotEmpty() }
        val current = decisions.decisions.value.single()
        assertNotEquals(original.notificationBinding(), current.notificationBinding())
        assertFalse(decisions.respondNotification(original.id, requireNotNull(original.notificationBinding()), 0) { true }.accepted)
        assertFalse(decisions.respondNotification(current.id, requireNotNull(current.notificationBinding()), 0) { false }.accepted)
        assertNull(decisions.decisions.value.single().answer)
        assertFalse(decisions.respondNotification(current.id, requireNotNull(current.notificationBinding()), 2) { true }.accepted)
        assertTrue(decisions.respondNotification(current.id, requireNotNull(current.notificationBinding()), 1) { true }.accepted)
        assertEquals("Spacious", resumed.await().answer)
        assertEquals(1, reissued)
    }

    @Test fun pendingAndAnsweredChoicesAreIndependentOfReadCachePruning() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("choice-retention").toFile()
        try {
            val state = GitHubRepositoryStateStore(directory)
            val decisions = GitHubChatDecisions(state)
            val request = async { decisions.request(question()) }
            decisions.waiting.first { it.isNotEmpty() }
            request.cancelAndJoin()
            repeat(GitHubRepositoryStateStore.MAX_RECORDS + 1) { index ->
                directory.resolve(GitHubRepositoryStateStore.digest("read-cache:result-$index") + ".record").writeText("{\"path\":\"source-$index.kt\"}")
            }
            state.write("read-cache", "new-read", buildJsonObject { put("path", "latest.kt") })
            assertEquals(GitHubRepositoryStateStore.MAX_RECORDS, directory.listFiles { file -> file.extension == "record" }.orEmpty().size)
            val restored = GitHubChatDecisions(GitHubRepositoryStateStore(directory))
            restored.load()
            val offered = restored.decisions.value.single()
            assertTrue(restored.respondNotification(offered.id, requireNotNull(offered.notificationBinding()), 0) { true }.accepted)
            val next = GitHubChatDecisions(GitHubRepositoryStateStore(directory))
            next.load()
            assertEquals("Compact", next.decisions.value.single().answer)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun largeSavedBodiesRemainReadableWithoutPinningThemInTheMemoryCache() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("bounded-evidence-cache").toFile()
        try {
            val state = GitHubRepositoryStateStore(directory)
            val body = buildJsonObject { put("content", "retained source ".repeat(30_000)) }
            state.write("source", "saved-view-body:large", body)
            assertEquals(body, state.read("source", "saved-view-body:large"))
            assertEquals(0, state.cachedMemoryCharge())
            repeat(90) { index -> state.write("source", "small-$index", buildJsonObject { put("value", "x".repeat(50_000)) }) }
            assertTrue(state.cachedMemoryCharge() in 1..GitHubRepositoryStateStore.MAX_MEMORY_BYTES)
            assertEquals(body, state.read("source", "saved-view-body:large"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun storageErrorReloadKeepsTemporaryQuestionsInMemoryOnly() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("temporary-choice-reload").toFile()
        val decisions = GitHubChatDecisions(GitHubRepositoryStateStore(directory))
        val waiting = async { decisions.request(question().copy(persistent = false)) }
        try {
            decisions.waiting.first { it.isNotEmpty() }
            val inputDirectory = directory.resolve("user-input")
            val backup = directory.resolve("retained-input")
            assertTrue(inputDirectory.renameTo(backup))
            inputDirectory.writeText("Storage temporarily unavailable")
            assertTrue(runCatching { decisions.respond(question().id, 1, "workspace", 0) }.isFailure)
            assertTrue(inputDirectory.delete())
            assertTrue(backup.renameTo(inputDirectory))
            decisions.load()
            assertEquals(question().id, decisions.decisions.value.single().id)
            assertNull(decisions.decisions.value.single().answer)
            val restarted = GitHubChatDecisions(GitHubRepositoryStateStore(directory))
            restarted.load()
            assertTrue(restarted.decisions.value.isEmpty())
        } finally {
            waiting.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
