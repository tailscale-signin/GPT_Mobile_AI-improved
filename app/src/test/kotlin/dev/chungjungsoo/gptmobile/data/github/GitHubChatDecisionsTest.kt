package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubChatDecisionsTest {
    private fun question(id: String = "choice", persistent: Boolean = false) = GitHubChatDecision(id, 1, 2, "primary", "workspace", "Which layout?", "Choose before changing the screen.", listOf(GitHubDecisionOption("Compact", "Fits small displays"), GitHubDecisionOption("Spacious", "More room for details")), 0, persistent)

    @Test fun requestWaitsForOneValidatedChoiceAndNeverUsesItsRecommendationAutomatically() = runBlocking {
        val store = GitHubChatDecisions(GitHubRepositoryStateStore())
        var offered = 0
        val request = async { store.request(question()) { offered++ } }
        store.waiting.first { "choice" in it }
        assertFalse(request.isCompleted)
        assertFalse(store.respond("choice", 2, "workspace", 0).accepted)
        assertFalse(store.respond("choice", 1, "another-workspace", 0).accepted)
        assertTrue(runCatching { store.respond("choice", 1, "workspace", 7) }.isFailure)
        assertFalse(request.isCompleted)
        assertTrue(store.respond("choice", 1, "workspace", 1).accepted)
        assertEquals("Spacious", request.await().answer)
        assertFalse(store.respond("choice", 1, "workspace", 0).accepted)
        assertEquals("Spacious", store.request(question()) { error("Do not ask again") }.answer)
        assertEquals(1, offered)
        assertTrue(store.waiting.value.isEmpty())
    }

    @Test fun interruptionRetainsQuestionAndSavedCustomAnswerAcrossRecreation() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("github-decisions").toFile()
        try {
            val original = GitHubChatDecisions(GitHubRepositoryStateStore(directory))
            val request = async { original.request(question(persistent = true)) }
            original.waiting.first { it.isNotEmpty() }
            request.cancelAndJoin()
            assertTrue(original.waiting.value.isEmpty())
            val restored = GitHubChatDecisions(GitHubRepositoryStateStore(directory))
            restored.load()
            assertEquals(null, restored.decisions.value.single().answer)
            val response = restored.respond("choice", 1, "workspace", customAnswer = "Use a compact list with expandable details")
            assertTrue(response.accepted)
            assertFalse(response.waiting)
            val resumed = GitHubChatDecisions(GitHubRepositoryStateStore(directory))
            assertEquals("Use a compact list with expandable details", resumed.request(question(persistent = true)) { error("Already answered") }.answer)
            resumed.delivered("choice")
            assertTrue(resumed.decisions.value.single().delivered)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun temporaryChatQuestionsNeverReachPersistentStorage() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("github-temporary-decisions").toFile()
        try {
            val store = GitHubChatDecisions(GitHubRepositoryStateStore(directory))
            val request = async { store.request(question()) }
            store.waiting.first { it.isNotEmpty() }
            request.cancelAndJoin()
            val restored = GitHubChatDecisions(GitHubRepositoryStateStore(directory))
            restored.load()
            assertTrue(restored.decisions.value.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun switchingToTemporaryRemovesSavedQuestionsAndDeletionCancelsWaiters() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("github-decision-privacy").toFile()
        try {
            val store = GitHubChatDecisions(GitHubRepositoryStateStore(directory))
            val request = async { store.request(question(persistent = true)) }
            store.waiting.first { it.isNotEmpty() }
            store.setPersistent(1, false)
            val restored = GitHubChatDecisions(GitHubRepositoryStateStore(directory))
            restored.load()
            assertTrue(restored.decisions.value.isEmpty())
            assertEquals(listOf("choice"), store.deleteChat(1))
            request.join()
            assertTrue(request.isCancelled)
            assertTrue(store.decisions.value.isEmpty())
            assertTrue(store.waiting.value.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun stoppingAQuestionReturnsExplicitCancellationInsteadOfSelectingAnOption() = runBlocking {
        val store = GitHubChatDecisions(GitHubRepositoryStateStore())
        val request = async { store.request(question()) }
        store.waiting.first { it.isNotEmpty() }
        store.dismiss("choice", 1)
        val response = request.await()
        assertTrue(response.cancelled)
        assertTrue(response.answer!!.startsWith("Stop"))
    }

    @Test fun questionAndAnswerBoundsRejectMalformedOrAmbiguousInput() = runBlocking {
        assertTrue(runCatching { question().copy(question = " ") }.isFailure)
        assertTrue(runCatching { question().copy(options = listOf(GitHubDecisionOption("Same"), GitHubDecisionOption("Same"))) }.isFailure)
        assertTrue(runCatching { question().copy(recommended = 2) }.isFailure)
        val store = GitHubChatDecisions(GitHubRepositoryStateStore())
        val request = async { store.request(question()) }
        store.waiting.first { it.isNotEmpty() }
        assertTrue(runCatching { store.respond("choice", 1, "workspace", 0, "both") }.isFailure)
        assertTrue(runCatching { store.respond("choice", 1, "workspace", customAnswer = " ") }.isFailure)
        assertTrue(runCatching { store.respond("choice", 1, "workspace", customAnswer = "a".repeat(2001)) }.isFailure)
        request.cancelAndJoin()
    }
}
