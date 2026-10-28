package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubBranchProposalTest {
    private val context = GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), "coder")
    private val receipt = """{"owner":"owner","repo":"repo","ref":"fix/next","commit_sha":"${"b".repeat(40)}"}"""
    private fun event(result: String = receipt) = ToolEvent("event", "run", 0, "call", "github", "GitHub", "github_context", "github_context__github_api", """{"action":"resolve","ref":"fix/next"}""", result, "JSON", "COMPLETED")

    @Test fun successfulScopedReceiptCanProposeANewBranch() {
        assertEquals("fix/next", event().branchProposal(context)?.branch)
        assertEquals("fix/next", event("""{"schema":"github.operation.v1","data":$receipt}""").branchProposal(context)?.branch)
    }

    @Test fun oldBranchReceiptsCannotResurfaceAfterDecliningOrFollowingTheLatest() {
        val old = event(receipt.replace("fix/next", "fix/old")).copy(eventId = "old")
        assertNull(latestBranchProposal(listOf(old, event()), context, listOf("event")))
        assertNull(latestBranchProposal(listOf(old, event()), context.copy(ref = "fix/next"), emptyList()))
    }

    @Test fun proseFailuresAndOtherAccountsCannotMoveTheWorkspace() {
        assertNull(event("Switch to fix/next").branchProposal(context))
        assertNull(event().copy(isError = true).branchProposal(context))
        assertNull(event().copy(status = "RUNNING").branchProposal(context))
        assertNull(event().copy(connectionUidSnapshot = "other-account").branchProposal(context))
        assertNull(event(receipt.replace("\"repo\":\"repo\"", "\"repo\":\"other\"")).branchProposal(context))
        assertNull(event(receipt.replace("fix/next", "main")).branchProposal(context))
    }
}
