package dev.chungjungsoo.gptmobile.data.agent.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRoundInstructionsTest {
    @Test fun finalizationDropsOnlyGeneratedToolStageInstructions() {
        val userRules = "Return JSON. Preserve uncertainty."
        val prompt = userRules + dev.chungjungsoo.gptmobile.data.agent.toolStageInstructions("Call github_ci until every page is read.") + "\nProject rule: include the tested revision."
        val final = requireNotNull(toolRoundSystemPrompt(prompt, false))
        assertTrue(final.contains(userRules))
        assertTrue(final.contains("Project rule: include the tested revision."))
        assertTrue(!final.contains("Call github_ci"))
        assertEquals(prompt, toolRoundSystemPrompt(prompt, true))
    }

    @Test fun activeToolsPreserveTheOriginalPrompt() {
        assertEquals("Original prompt", toolRoundSystemPrompt("Original prompt", true))
        assertNull(toolRoundSystemPrompt(null, true))
    }

    @Test fun synthesisPreservesFrozenPromptIncludingAbsentInstructions() {
        val constraints = RequestConstraints(allowTools = false, requestRole = "synthesis")
        assertEquals("Frozen prompt\n", toolRoundSystemPrompt("Frozen prompt\n", false, constraints))
        assertNull(toolRoundSystemPrompt(null, false, constraints))
    }

    @Test fun toolFreeRoundsKeepUserFormatAndForbidUnexecutedSuccessClaims() {
        val prompt = requireNotNull(toolRoundSystemPrompt("Return JSON.", false))
        assertTrue(prompt.startsWith("Return JSON."))
        assertTrue(prompt.contains("No tools are available"))
        assertTrue(prompt.contains("requested answer format"))
        assertTrue(prompt.contains("Do not claim that an unexecuted action succeeded"))
        assertTrue(requireNotNull(toolRoundSystemPrompt(null, false)).startsWith("No tools"))
    }
}
