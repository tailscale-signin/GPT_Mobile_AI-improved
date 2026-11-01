package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubConversationExportTest {
    @Test fun transcriptIncludesUserTurnsSelectedRepliesAndSnapshotState() {
        val text = githubConversationExport("Repository task", "owner/repo", "feature", "a".repeat(40), listOf("status" to listOf("**Checks** are running"), "why?" to emptyList()), listOf("COMPLETED", "FAILED", "RUNNING"), "2026-11-01T18:00:00Z", ChatExportFormat.MARKDOWN)
        assertTrue(text.contains("Repository: owner/repo"))
        assertTrue(text.contains("Branch: feature"))
        assertTrue(text.indexOf("status") < text.indexOf("**Checks**"))
        assertTrue(text.contains("FAILED, RUNNING"))
        assertTrue(text.contains("No response recorded."))
        assertTrue(text.contains("may be incomplete"))
    }

    @Test fun plainTextRetainsLinksAndCodeWithoutMarkdownDecorations() {
        val text = githubConversationExport("Task", "o/r", "main", "a".repeat(40), listOf("Read" to listOf("# Answer\n\n[Run](https://github.com/o/r/actions/runs/1)\n```kotlin\nval x = 1\n```")), emptyList(), "snapshot", ChatExportFormat.PLAIN_TEXT)
        assertFalse(text.contains("# Answer"))
        assertFalse(text.contains("```"))
        assertTrue(text.contains("Run (https://github.com/o/r/actions/runs/1)"))
        assertTrue(text.contains("val x = 1"))
    }
}
