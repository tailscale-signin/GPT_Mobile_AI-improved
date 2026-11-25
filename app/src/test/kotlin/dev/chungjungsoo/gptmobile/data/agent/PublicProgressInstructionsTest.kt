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

    @Test fun maximumInlineBindingPreservesEveryCompoundTaskAndWholeOptionalSections() {
        val binding = context.copy(owner = "o".repeat(39), repo = "r".repeat(121), ref = "b".repeat(180))
        val prompt = binding.promptContext("Test all tools, review the selected branch and fix the issue")
        assertTrue(prompt.length < 2000)
        assertTrue(prompt.contains("GitHub workspace: ${binding.owner}/${binding.repo}\nBranch: ${binding.ref}\n"))
        listOf(binding.commitSha, "never infer write approval", "Branch review", "first unread exact range", "Testing acceptance", "github_stage_patch").forEach {
            assertTrue(it, prompt.contains(it))
        }
        val details = dev.chungjungsoo.gptmobile.data.github.GitHubReviewComparison.DETAIL_INSTRUCTION
        assertFalse(prompt.contains(details.substringBefore('.')) && !prompt.contains(details))
        assertFalse(prompt.contains("untrusted continuation"))

        val statusPrompts = listOf(
            "Review the selected branch and repository status",
            "Review the selected branch and repository status [github-workflow-preset:implement_issue]"
        ).map { binding.promptContext(it) }
        (statusPrompts + prompt).forEach { bounded ->
            assertTrue(bounded.length < 2000)
            assertTrue(bounded.contains("GitHub workspace: ${binding.owner}/${binding.repo}\nBranch: ${binding.ref}\n"))
            listOf(
                binding.commitSha,
                "never infer write approval",
                "pin exact base_sha/head_sha",
                "Resolve a supplied baseline to SHA",
                "observed matching PR base or default-branch SHA for a non-default selection",
                "On default without baseline, ask; never infer a self-diff",
                "github_context action=changes",
                "short changed code/matching test ranges at both SHAs",
                "source coverage's first unread exact range",
                "retain requested lines and completed evidence",
                "Bound guidance separately outside code/test allowance",
                "Checks/inventories are not source review"
            ).forEach { required -> assertTrue(required, bounded.contains(required)) }
            assertFalse(bounded.contains(details.substringBefore('.')) && !bounded.contains(details))
            assertFalse(bounded.contains("untrusted continuation"))
        }
        val statusDetails = dev.chungjungsoo.gptmobile.data.github.GitHubStatusRequest.DETAIL_INSTRUCTION
        statusPrompts.forEach { bounded ->
            listOf(
                "read-only selected repo/branch, recent commits, open PRs and checks",
                "pin all pages to selected repo and observed SHA",
                "Health needs branch policy, recent failures and current PR review decisions",
                "Keep call limits; missing/incomplete data stays partial",
                "Do not read source, resume work, edit or poll",
                "Separate latest workflow from required checks; unknown worktree/tests stay unknown"
            ).forEach { required -> assertTrue(required, bounded.contains(required)) }
            assertFalse(bounded.contains(statusDetails.substringBefore('.')) && !bounded.contains(statusDetails))
        }
        assertTrue(statusPrompts.last().contains("github_stage_patch"))
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
