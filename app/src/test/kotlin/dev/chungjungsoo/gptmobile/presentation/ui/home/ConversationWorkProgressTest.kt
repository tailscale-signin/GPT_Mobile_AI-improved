package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.agent.CombinedProgressSnapshot
import dev.chungjungsoo.gptmobile.data.chat.ReplyDisplayRole
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWorkProgressTest {
    private val profiles = setOf("a", "b", "c", "d", "e")
    private val budget = CombinedProgressSnapshot(1, 1, profiles.toList(), profiles, false)

    private fun run(uid: String, status: String = AgentRunStatus.COMPLETED, synthesis: Boolean = false, turn: Int = 10, fence: Long = 1) =
        AgentRun("$turn:$uid:$fence", 1, turn, turn + 1, uid, "test", "model", status, ownershipFence = fence, displayRole = if (synthesis) ReplyDisplayRole.COMBINED else ReplyDisplayRole.DIRECT)

    @Test fun contributorsCannotFinishTheBarBeforePrimaryTextAndResearchAcceptance() {
        val contributions = profiles.map { run(it) }
        assertTrue(conversationWorkProgress(true, true, profiles, contributions, emptyList(), budget, true, false)!!.fraction < 1f)
        val final = contributions + run("final", synthesis = true)
        assertTrue(conversationWorkProgress(true, true, profiles, final, emptyList(), budget.copy(delivered = true), true, true)!!.fraction < 1f)
        assertTrue(conversationWorkProgress(true, true, profiles, final, emptyList(), budget.copy(delivered = true), true, false, finalTextDelivered = true)!!.fraction < 1f)
        val complete = conversationWorkProgress(true, true, profiles, final, emptyList(), budget.copy(delivered = true), false, true, finalTextDelivered = true)!!
        assertEquals(1f, complete.fraction)
        assertFalse(complete.running)
        assertEquals(10, complete.userMessageId)
    }

    @Test fun runningPrimaryCannotCompleteEvenWithAnOldDeliveredBudget() {
        val runs = profiles.map { run(it) } + run("final", AgentRunStatus.RUNNING, synthesis = true)
        val progress = conversationWorkProgress(false, true, profiles, runs, emptyList(), budget.copy(delivered = true), true, true, finalTextDelivered = true)!!
        assertTrue(progress.running)
        assertTrue(progress.fraction < 1f)
    }

    @Test fun deliveredCombinedAnswerMayCompleteWithAnExcludedFailedContributor() {
        val runs = listOf(run("a", AgentRunStatus.FAILED), run("b"), run("final", synthesis = true))
        val progress = conversationWorkProgress(false, true, setOf("a", "b"), runs, emptyList(), budget.copy(participants = listOf("a", "b"), completedProfiles = setOf("b"), delivered = true), false, false, finalTextDelivered = true)!!
        assertEquals(1f, progress.fraction)
        assertFalse(progress.running)
    }

    @Test fun failureCancellationAndInterruptionNeverLeaveACompleteBar() {
        for (status in listOf(AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.INTERRUPTED)) {
            val runs = profiles.map { run(it) } + run("final", status, synthesis = true)
            for (active in listOf(true, false)) {
                assertNull(conversationWorkProgress(false, true, profiles, runs, emptyList(), budget.copy(delivered = true), active, true, finalTextDelivered = true))
            }
        }
        assertNull(conversationWorkProgress(true, true, profiles, profiles.map { run(it) }, emptyList(), budget, false, false))
    }

    @Test fun newRegenerationIsNotHiddenByAnOlderFailedOrCancelledFinal() {
        for (status in listOf(AgentRunStatus.FAILED, AgentRunStatus.CANCELED)) {
            val runs = listOf(run("final", status, synthesis = true), run("a", AgentRunStatus.RUNNING, fence = 2))
            val progress = conversationWorkProgress(false, true, setOf("a"), runs, emptyList(), null, true, false)
            assertNotNull(progress)
            assertTrue(progress!!.running)
            assertTrue(progress.fraction < 1f)
        }
    }

    @Test fun currentRetrySupersedesCompletedAttemptAndOldBudgetMilestone() {
        val runs = listOf(run("a"), run("a", AgentRunStatus.RUNNING, fence = 2), run("b", AgentRunStatus.RUNNING))
        val progress = conversationWorkProgress(false, true, setOf("a", "b"), runs, emptyList(), budget.copy(participants = listOf("a", "b"), completedProfiles = setOf("a")), true, false)!!
        assertEquals(0f, progress.fraction)
        assertTrue(progress.label.contains("0/2"))
    }

    @Test fun newerUserTurnCannotInheritAnOlderTurnsCompletion() {
        val runs = listOf(run("a"), run("a", AgentRunStatus.RUNNING, turn = 20))
        val progress = conversationWorkProgress(false, false, setOf("a"), runs, emptyList(), null, true, false)!!
        assertEquals(20, progress.userMessageId)
        assertTrue(progress.running)
        assertTrue(progress.fraction < 1f)
    }

    @Test fun ordinarySingleReplyCompletesOnlyWhenTheTextIsDelivered() {
        val runs = listOf(run("a"))
        assertNull(conversationWorkProgress(false, false, setOf("a"), runs, emptyList(), null, false, false))
        val complete = conversationWorkProgress(false, false, setOf("a"), runs, emptyList(), null, false, false, finalTextDelivered = true)!!
        assertEquals(1f, complete.fraction)
        assertFalse(complete.running)
    }

    @Test fun multiReplyWaitsForRemainingRepliesAndHidesWhenAllHaveStopped() {
        val participants = setOf("a", "b")
        val active = listOf(run("a", AgentRunStatus.FAILED), run("b", AgentRunStatus.RUNNING))
        assertTrue(conversationWorkProgress(false, false, participants, active, emptyList(), null, true, false)!!.fraction < 1f)
        val stopped = listOf(run("a", AgentRunStatus.FAILED), run("b"))
        assertNull(conversationWorkProgress(false, false, participants, stopped, emptyList(), null, true, false, finalTextDelivered = true))
        val complete = conversationWorkProgress(false, false, participants, listOf(run("a"), run("b")), emptyList(), null, false, false, finalTextDelivered = true)!!
        assertEquals(1f, complete.fraction)
        assertFalse(complete.running)
    }
}
