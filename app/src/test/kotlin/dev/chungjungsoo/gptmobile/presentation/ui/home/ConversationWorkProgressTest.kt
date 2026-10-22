package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.agent.CombinedProgressSnapshot
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWorkProgressTest {
    private fun run(uid: String, synthesis: Boolean = false) = AgentRun(uid, 1, 10, 11, uid, "test", "model", "COMPLETED", synthesisKey = if (synthesis) "key" else null)

    @Test fun fiveFinishedProfilesDoNotShowCompleteBeforePrimaryDeliveryAndResearchValidation() {
        val profiles = setOf("a", "b", "c", "d", "e")
        val contributions = profiles.map { run(it) }
        val budget = CombinedProgressSnapshot(1, 1, profiles.toList(), profiles, false)
        assertTrue(conversationWorkProgress(true, true, profiles, contributions, emptyList(), budget, true, false)!!.fraction < 1f)
        val final = contributions + run("final", true)
        assertTrue(conversationWorkProgress(true, true, profiles, final, emptyList(), budget.copy(delivered = true), false, false)!!.fraction < 1f)
        assertEquals(1f, conversationWorkProgress(true, true, profiles, final, emptyList(), budget.copy(delivered = true), false, true)!!.fraction)
        assertTrue(conversationWorkProgress(false, true, profiles, final.drop(1), emptyList(), budget.copy(delivered = false, completedProfiles = profiles - "a"), false, false)!!.label.contains("Partial"))
    }
}
