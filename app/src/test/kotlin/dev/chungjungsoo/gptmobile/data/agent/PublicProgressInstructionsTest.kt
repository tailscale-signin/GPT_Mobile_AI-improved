package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicProgressInstructionsTest {
    private val context = GitHubConversationContext(
        "connection", "owner", "repo", "feature", "a".repeat(40), "profile",
        stagedFileCount = 2,
        recentCommits = List(100) { "b".repeat(500) + "\nuntrusted continuation" },
        observedAtMillis = 123
    )

    @Test fun longRepositoryContextCannotCutThePrimaryPreviewProtocol() {
        val repository = context.promptContext("Review the selected branch and its tests")
        val system = toolStageInstructions(toolProgressInstructions(githubMode = true)) + "\n" + repository
        assertTrue(repository.length < 2000)
        assertTrue(system.contains("<progress_update>...</progress_update>"))
        assertTrue(system.contains("Close the progress tag before continuing"))
        assertTrue(system.contains("GitHub workspace: owner/repo\nBranch: feature"))
        assertTrue(system.contains("never infer write approval"))
        assertFalse(system.contains("untrusted continuation"))
    }

    @Test fun longBindingAndCommitMetadataCannotDisplaceTheTaskOrSplitAnInstruction() {
        val snapshot = context.copy(ref = "release/".repeat(300))
        val prompt = snapshot.promptContext("Test all tools, review the selected branch and fix the issue", contextTokens = 32000)
        assertTrue(prompt.length < 2000)
        assertTrue(prompt.contains("Captured head at chat binding: ${snapshot.commitSha}"))
        assertTrue(prompt.contains("resolve selected ref with github_context"))
        assertTrue(prompt.contains("never infer write approval"))
        assertTrue(prompt.contains("Testing acceptance"))
        assertTrue(prompt.contains("Branch review"))
        assertTrue(prompt.contains("github_stage_patch"))
        assertFalse(prompt.contains("[bounded GitHub context]"))
    }

    @Test fun compactIntegrationContextKeepsTheSamePrimaryPreviewProtocol() {
        val repository = context.promptContext("Test all GitHub integration tools", contextTokens = 8192)
        val system = toolStageInstructions(toolProgressInstructions(githubMode = true)) + "\n" + repository
        assertTrue(repository.contains("Reconcile unknown outcomes and respect budgets, approvals and cancellation"))
        assertTrue(system.contains("<progress_update>...</progress_update>"))
        assertTrue(system.contains("omit an update when nothing material changed"))
        assertTrue(system.contains("Keep the final answer separate"))
        assertFalse(system.contains("after every 10 completed tool calls"))
    }

    @Test fun githubPreviewsRequestUsefulMarkdownAndBoundNumericClaimsToEvidence() {
        val instruction = toolProgressInstructions(githubMode = true)
        assertTrue(instruction.contains("specific finding or decision"))
        assertTrue(instruction.contains("Markdown headings, **emphasis**, bullets or a compact table"))
        assertTrue(instruction.contains("units, source and scope"))
        assertTrue(instruction.contains("graphs from recorded check tables"))
        assertTrue(instruction.contains("never estimate missing results"))
        assertTrue(instruction.contains("Never claim unverified fixes or completion"))
        assertTrue(instruction.contains("private reasoning"))
    }

    @Test fun nonGithubToolProgressRetainsItsExistingCadenceAndDirectAnswerPolicy() {
        val instruction = toolProgressInstructions(githubMode = false)
        assertTrue(instruction.contains("before the first tool call", ignoreCase = true))
        assertTrue(instruction.contains("after every 10 completed tool calls"))
        assertTrue(instruction.contains(ToolProgressTracker.SUMMARY_INSTRUCTION))
        assertTrue(instruction.contains("Answer ordinary general-knowledge and writing requests directly"))
        assertFalse(instruction.contains("The app renders graphs"))
    }
}
