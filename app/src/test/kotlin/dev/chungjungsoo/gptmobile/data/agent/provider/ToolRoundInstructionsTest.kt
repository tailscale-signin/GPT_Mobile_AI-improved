package dev.chungjungsoo.gptmobile.data.agent.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRoundInstructionsTest {
    @Test fun localGithubPreviewGuidanceSurvivesToolRoundsAndLeavesFinalization() {
        val context = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("connection", "owner", "repo", "feature", "a".repeat(40), "profile")
        val prompt = dev.chungjungsoo.gptmobile.data.context.LocalRequestPrompt.build("Use clear language.", "Retain the tested revision.", true) +
            dev.chungjungsoo.gptmobile.data.agent.toolStageInstructions(dev.chungjungsoo.gptmobile.data.agent.toolProgressInstructions(githubMode = true)) +
            dev.chungjungsoo.gptmobile.data.agent.toolStageInstructions(context.promptContext("Test all GitHub integration tools", contextTokens = 8192))
        val active = requireNotNull(toolRoundSystemPrompt(prompt, true))
        assertTrue(active.contains("<progress_update>...</progress_update>"))
        assertTrue(active.contains("omit an update when nothing material changed"))
        assertTrue(active.contains("Markdown headings, **emphasis**"))
        assertTrue(active.contains("Reconcile unknown outcomes and respect budgets, approvals and cancellation"))
        val final = requireNotNull(toolRoundSystemPrompt(prompt, false))
        assertTrue(!final.contains("<progress_update>"))
        assertTrue(final.contains("Use clear language."))
        assertTrue(final.contains("Retain the tested revision."))
        assertTrue(!final.contains("<app_tool_stage>"))
    }

    @Test fun finalizationDropsOnlyGeneratedToolStageInstructions() {
        val userRules = "Return JSON. Preserve uncertainty."
        val prompt = userRules + dev.chungjungsoo.gptmobile.data.agent.toolStageInstructions("Call github_ci until every page is read.") + "\nProject rule: include the tested revision."
        val final = requireNotNull(toolRoundSystemPrompt(prompt, false))
        assertTrue(final.contains(userRules))
        assertTrue(final.contains("Project rule: include the tested revision."))
        assertTrue(!final.contains("Call github_ci"))
        assertTrue(requireNotNull(toolRoundSystemPrompt(prompt, true)).startsWith(prompt))
    }

    @Test fun activeToolsPreserveTheOriginalPromptAndBoundTheRequestedBatch() {
        val original = requireNotNull(toolRoundSystemPrompt("Original prompt", true))
        assertTrue(original.startsWith("Original prompt\n\n"))
        assertTrue(original.contains("at most 8 tool calls"))
        assertTrue(original.contains("remaining total tool budget"))
        assertEquals(ProviderToolBatchPolicy.INSTRUCTIONS, toolRoundSystemPrompt(null, true))
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
