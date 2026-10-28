package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubOperationToolSelectionTest {
    @Test
    fun commitRequestsExposeCommitAndWriteTools() {
        listOf(
            "Commit changes to the GitHub repository",
            "Start committing changes in the repo",
            "Push changes to GitHub",
            "Continue pushing to the repository"
        ).forEach { request ->
            val tools = GitHubOperationTool.taskTools(request)
            assertTrue("Missing github_commit for: $request", "github_commit" in tools)
            assertTrue("Missing github_write for: $request", "github_write" in tools)
        }
    }

    @Test
    fun fileWriteRequestsExposeWriteTool() {
        listOf(
            "Write a file in the GitHub repository",
            "Apply changes to the GitHub repository",
            "Continue writing files in the repo"
        ).forEach { request ->
            assertTrue("Missing github_write for: $request", "github_write" in GitHubOperationTool.taskTools(request))
        }
    }

    @Test
    fun unrelatedRequestDoesNotExposeGitHubTools() {
        assertTrue(GitHubOperationTool.taskTools("What is the weather tomorrow?").isEmpty())
    }

    @Test
    fun readOnlyGitHubRequestDoesNotAutomaticallyExposeWriteTool() {
        val tools = GitHubOperationTool.taskTools("Read a file in the GitHub repository")
        assertFalse("github_write" in tools)
    }
}
