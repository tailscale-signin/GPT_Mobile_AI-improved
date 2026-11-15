package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolBatchCheckpoint
import dev.chungjungsoo.gptmobile.data.agent.ToolBatchJobContext
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class GitHubCompactContinuationTest {
    private val sha = "a".repeat(40)
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private val arguments = json("""{"action":"resolve","ref":"work"}""")
    private val result = json("""{"data":{"owner":"owner","repo":"repo","ref":"work","commit_sha":"$sha"}}""")

    @Test fun compactResolveKeepsRepositoryRevisionAndSuccessGuards() {
        for (tool in listOf("github_context__selected", "github_context_inspect__selected")) {
            assertEquals(GitHubBranchTransition.Destination("work", sha), GitHubBranchTransition.verified("owner/repo", tool, arguments, result, true))
            assertNull(GitHubBranchTransition.verified("other/repo", tool, arguments, result, true))
            assertNull(GitHubBranchTransition.verified("owner/repo", tool, arguments, result, false))
            assertNull(GitHubBranchTransition.verified("owner/repo", tool, JsonObject(arguments + ("ref" to JsonPrimitive("other"))), result, true))
            val invalid = json("""{"data":{"owner":"owner","repo":"repo","ref":"work","commit_sha":"unknown"}}""")
            assertNull(GitHubBranchTransition.verified("owner/repo", tool, arguments, invalid, true))
        }
        assertNull(GitHubBranchTransition.verified("owner/repo", "github_context_inspect_other__selected", arguments, result, true))
    }

    @Test fun compactResolveRestoresTheVerifiedDestinationWithoutRepeatingTheCall() = runBlocking {
        val file = File.createTempFile("compact-branch-transition", ".json").apply { delete() }
        try {
            val original = ToolBatchJobContext("Fix the same PR", "GITHUB_ENGINEERING", "owner/repo", "main", sha)
            val journal = ToolBatchCheckpoint(file, "same-grant", original)
            val call = ProviderEvent.ToolCall("resolve", "github_context_inspect__selected", arguments)
            journal.enqueue(listOf(call))
            journal.execute(call) { AgentToolResult(call.callId, ToolResultContent.Json(result), false) }
            val restored = ToolBatchCheckpoint(file, "same-grant", original.copy(branch = "work"))
            assertEquals(1, restored.recoverySnapshot().completedCount)
            assertEquals(result, (restored.execute(call) { error("A restored read must not dispatch again") }.content as ToolResultContent.Json).value)
            for (invalid in listOf(original.copy(branch = "unrelated"), original.copy(repository = "other/repo"), original.copy(objective = "Another task"))) {
                assertTrue(runCatching { ToolBatchCheckpoint(file, "same-grant", invalid).recoverySnapshot() }.isFailure)
            }
            assertTrue(runCatching { ToolBatchCheckpoint(file, "other-grant", original).recoverySnapshot() }.isFailure)
        } finally {
            file.delete()
        }
    }

    @Test fun compactEvidenceLinksAndFallbackFactsRetainTheirResourceType() {
        val base = "https://github.com/owner/repo"
        val commit = json("""{"tool":"github_context_inspect__selected","arguments":{"action":"commit"},"result":{"sha":"$sha"}}""")
        assertEquals("$base/commit/$sha", GitHubEvidenceLink.url(base, "/sha", sha, commit))
        assertNull(GitHubEvidenceLink.url(base, "/sha", sha, JsonObject(commit + ("tool" to JsonPrimitive("github_read__selected")))))
        val pr = json("""{"tool":"github_pr_inspect__selected","arguments":{"action":"get"},"result":{"number":42}}""")
        assertEquals("$base/pull/42", GitHubEvidenceLink.url(base, "/number", "42", pr))
        assertNull(GitHubEvidenceLink.url(base, "/number", "0", pr))
        val args = json("""{"action":"list"}""")
        val data = json("""{"items":[{"number":42}],"has_more":false}""")
        val canonical = GitHubSemanticSummary.from("github_pr", args, data)
        val compact = GitHubSemanticSummary.from("github_pr_inspect__selected", args, data)
        assertEquals(JsonObject(canonical - "operation"), JsonObject(compact - "operation"))
        assertEquals(JsonPrimitive("github_pr_inspect"), compact["operation"])
    }
}
