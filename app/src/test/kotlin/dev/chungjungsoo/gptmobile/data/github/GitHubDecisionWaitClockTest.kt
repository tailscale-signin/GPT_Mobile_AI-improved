package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDecisionWaitClockTest {
    @Test fun explicitDeliveryCutoffsStayWallClockBound() {
        assertFalse(GitHubDecisionWaitClock.allowsPause("Finish within 10 minutes"))
        assertFalse(GitHubDecisionWaitClock.allowsPause("Deliver the patch by 5pm"))
        assertFalse(GitHubDecisionWaitClock.allowsPause("Deadline: tomorrow noon"))
        assertTrue(GitHubDecisionWaitClock.allowsPause("Budget: 10 minutes and 17 tool calls"))
    }

    @Test fun deliveryObjectPhrasesDoNotConvertExplicitCutoffsToActiveWorkAllowances() {
        assertFalse(GitHubDecisionWaitClock.allowsPause("Finish the change within 10 minutes"))
        assertFalse(GitHubDecisionWaitClock.allowsPause("Complete this feature within 20 minutes"))
        assertFalse(GitHubDecisionWaitClock.allowsPause("Deliver the tested patch within 5 minutes"))
        assertTrue(GitHubDecisionWaitClock.allowsPause("Implement the change. Budget: 10 minutes and 17 tool calls."))
        assertTrue(GitHubDecisionWaitClock.allowsPause("Finish the change. Work within 10 minutes of active execution."))
    }

    @Test fun savedTaskRetainsOnlyItsOwnPauseAndReopensWithTheRemainingActiveBudget() = runBlocking {
        var clock = 1_000L
        val durable = mutableMapOf<String, JsonObject>()
        fun book() = GitHubEvidenceBook("task", "owner/repo", "main", emptySet(), now = { clock }, load = { durable[it] }, save = { key, value -> durable[key] = value })
        val context = GitHubConversationContext("connection", "owner", "repo", "main", "a".repeat(40), "profile")
        val original = book()
        original.retainTaskContract("Implement the change. Budget: 10 minutes and 17 tool calls.", 2, emptyList(), 17)
        val choice = GitHubChatDecision("choice", 1, 2, "profile", GitHubChatDecisions.workspaceKey(context), "Which layout?", "", listOf(GitHubDecisionOption("Compact"), GitHubDecisionOption("Spacious")), persistent = true, offeredAtMillis = 11_000L, answeredAtMillis = 86_411_000L)
        clock = 86_411_000L
        original.retainDecisionWaits(listOf(choice, choice.copy(id = "wrong-task", sourceMessageId = 9, offeredAtMillis = 1_000L)), 1, context)
        assertEquals(590_000L, original.executionAdmission().remainingMillis)
        val restored = book()
        assertEquals(590_000L, restored.executionAdmission().remainingMillis)
        assertEquals(JsonPrimitive("ACTIVE_EXCLUDING_USER_CHOICES"), restored.executionAdmission().timing["task_deadline_clock"])
        clock += 590_000L
        assertEquals("WINDOW_EXPIRED", restored.executionAdmission().observation)
    }

    @Test fun daysWaitingDoNotSpendActiveExecutionTime() {
        val day = 86_400_000L
        assertEquals(2 * day, GitHubDecisionWaitClock.pausedMillis(100, 2 * day + 150, listOf(150L to null)))
        assertEquals(2 * day, GitHubDecisionWaitClock.pausedMillis(100, 2 * day + 200, listOf(150L to (2 * day + 150))))
    }

    @Test fun overlapsAndNewTimeWindowsCannotDoubleCountThePause() {
        assertEquals(80L, GitHubDecisionWaitClock.pausedMillis(100, 200, listOf(90L to 150L, 120L to 180L)))
        assertEquals(0L, GitHubDecisionWaitClock.pausedMillis(200, 250, listOf(100L to 180L)))
        assertEquals(0L, GitHubDecisionWaitClock.pausedMillis(100, 200, listOf(180L to 120L, -1L to 180L, 250L to null)))
    }
}
