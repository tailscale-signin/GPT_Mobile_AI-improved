package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSelfTestPullReadsTest {
    private val oldHead = "a".repeat(40)
    private val freshHead = "b".repeat(40)
    private val workspaceHead = "c".repeat(40)
    private val tool = GitHubOperationTool.definition("github_pr").copy(name = "github_pr__selected")
    private val workspace = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$workspaceHead","branch":"main"}}""")
    private val listed = receipt("list", """{"pull_requests":[{"number":2367,"state":"open","head":{"sha":"$oldHead"}}]}""")

    @Test fun newerSamePullHeadersReplaceTheListHeadWithoutChangingTheWorkspaceRevision() {
        val headers = receipt("headers", """{"pull_request":{"number":2367,"head":{"sha":"$freshHead"}}}""")
        val next = available(listOf(listed, headers))
        val context = next.single { it.arguments["action"] == JsonPrimitive("context") }
        assertEquals(JsonPrimitive(2367), context.arguments["pull_number"])
        assertEquals(JsonPrimitive(freshHead), context.arguments["expected_head_sha"])
        assertEquals(JsonPrimitive(workspaceHead), GitHubSelfTestPrerequisites.selected(listOf(workspace, listed, headers))?.head)
        assertEquals(null, next.single { it.arguments["action"] == JsonPrimitive("agent_merge_status") }.arguments["expected_head_sha"])
        next.forEach { assertTrue(GitHubSelfTestReads.valid(tool, it.arguments)) }
    }

    @Test fun changedHeadReadUsesTheObservedFreshHeadOnceAndStopsAfterAnotherChange() {
        val headers = receipt("headers", """{"number":2367,"head":{"sha":"$freshHead"}}""")
        val failed = failed()
        val retry = available(listOf(listed, headers, failed)).single { it.arguments["action"] == JsonPrimitive("context") }
        assertTrue(retry.callId.startsWith("pr-head-retry-"))
        assertEquals(JsonObject((failed["arguments"] as JsonObject) + ("expected_head_sha" to JsonPrimitive(freshHead))), retry.arguments)
        assertEquals(tool.name, retry.name)
        val attempted = attempted(retry, false)
        val latest = receipt("headers", """{"number":2367,"head":{"sha":"${"d".repeat(40)}"}}""")
        assertFalse(available(listOf(listed, headers, failed, attempted, latest)).any { it.arguments["action"] == JsonPrimitive("context") })
        assertFalse(available(listOf(listed, headers, failed, attempted(retry, true))).any { it.arguments["action"] == JsonPrimitive("context") })
    }

    @Test fun oldSuccessfulReadDoesNotQualifyTheNewHeadAndCurrentHeadIsNotReadTwice() {
        val oldSuccess = JsonObject(failed() + ("success" to JsonPrimitive(true)))
        val headers = receipt("headers", """{"number":2367,"head":{"sha":"$freshHead"}}""")
        val read = available(listOf(listed, oldSuccess, headers)).single { it.arguments["action"] == JsonPrimitive("context") }
        assertEquals(JsonPrimitive(freshHead), read.arguments["expected_head_sha"])
        assertFalse(read.callId.startsWith("pr-head-retry-"))
        assertFalse(available(listOf(listed, oldSuccess, headers, attempted(read, true))).any { it.arguments["action"] == JsonPrimitive("context") })
    }

    @Test fun latestStaleReadCanRecoverAfterAnOlderSuccessButNoSecondRecoveryIsGranted() {
        val oldSuccess = JsonObject(failed() + ("success" to JsonPrimitive(true)))
        val headers = receipt("headers", """{"number":2367,"head":{"sha":"$freshHead"}}""")
        val firstRead = available(listOf(listed, oldSuccess, headers)).single { it.arguments["action"] == JsonPrimitive("context") }
        val stale = attempted(firstRead, false)
        val newestHead = "d".repeat(40)
        val fresh = receipt("headers", """{"number":2367,"head":{"sha":"$newestHead"}}""")
        val retry = available(listOf(listed, oldSuccess, headers, stale, fresh)).single { it.arguments["action"] == JsonPrimitive("context") }
        assertTrue(retry.callId.startsWith("pr-head-retry-"))
        assertEquals(JsonPrimitive(newestHead), retry.arguments["expected_head_sha"])
        assertFalse(available(listOf(listed, oldSuccess, headers, stale, fresh, attempted(retry, false))).any { it.arguments["action"] == JsonPrimitive("context") })
        val denied = JsonObject(stale + ("error_code" to JsonPrimitive("GITHUB_PERMISSION_DENIED")))
        assertFalse(available(listOf(listed, oldSuccess, headers, denied, fresh)).any { it.callId.startsWith("pr-head-") })
    }

    @Test fun missingFreshHeadGetsOneLiveHeaderRefreshThenOnePinnedRead() {
        val failure = failed()
        val refresh = available(listOf(listed, failure)).single { it.arguments["action"] == JsonPrimitive("headers") }
        assertTrue(refresh.callId.startsWith("pr-head-refresh-"))
        assertEquals(json("""{"action":"headers","pull_number":2367,"verify_live":true}"""), refresh.arguments)
        val refreshed = JsonObject(receipt("headers", """{"number":2367,"head":{"sha":"$freshHead"}}""") + ("call_id" to JsonPrimitive(refresh.callId)))
        val retry = available(listOf(listed, failure, refreshed)).single { it.arguments["action"] == JsonPrimitive("context") }
        assertTrue(retry.callId.startsWith("pr-head-retry-"))
        assertEquals(JsonPrimitive(freshHead), retry.arguments["expected_head_sha"])
        assertFalse(available(listOf(listed, failure, attempted(refresh, false))).any { it.callId.startsWith("pr-head-") })
        val unchanged = JsonObject(refreshed + ("result" to json("""{"number":2367,"head":{"sha":"$oldHead"}}""")))
        assertFalse(available(listOf(listed, failure, unchanged)).any { it.callId.startsWith("pr-head-") })
        val anotherFailure = JsonObject(failure + ("arguments" to JsonObject((failure["arguments"] as JsonObject) + ("action" to JsonPrimitive("threads")))))
        assertEquals(1, available(listOf(listed, failure, anotherFailure)).count { it.callId.startsWith("pr-head-refresh-") })
    }

    @Test fun foreignUntrustedAndInconsistentHeadersCannotChangeTheSelectedPullHead() {
        val headers = receipt("headers", """{"number":2367,"head":{"sha":"$freshHead"}}""")
        val alternatives = listOf(
            JsonObject(headers + ("repository" to JsonPrimitive("other/repo"))),
            JsonObject(headers + ("binding" to JsonPrimitive("other-task"))),
            JsonObject(headers + ("tool" to JsonPrimitive("github_pr__other"))),
            JsonObject(headers + ("trusted" to JsonPrimitive(false))),
            JsonObject(headers + ("verification_eligible" to JsonPrimitive(false))),
            JsonObject(headers + ("repository_role" to JsonPrimitive("READ_SOURCE"))),
            JsonObject(headers + ("result" to json("""{"number":99,"head":{"sha":"$freshHead"}}"""))),
            JsonObject(headers + ("result" to json("""{"number":2367,"head":{"sha":"moving-branch"}}""")))
        )
        for (candidate in alternatives) {
            val context = available(listOf(listed, candidate)).single { it.arguments["action"] == JsonPrimitive("context") }
            assertEquals(JsonPrimitive(oldHead), context.arguments["expected_head_sha"])
            assertEquals(JsonPrimitive(2367), context.arguments["pull_number"])
        }
    }

    @Test fun recoveryCannotRetryAMutationPermissionFailureForeignPullOrHiddenTool() {
        val headers = receipt("headers", """{"number":2367,"head":{"sha":"$freshHead"}}""")
        val failure = failed()
        for (candidate in listOf(
            JsonObject(failure + ("error_code" to JsonPrimitive("GITHUB_PERMISSION_DENIED"))),
            JsonObject(failure + ("repository" to JsonPrimitive("other/repo"))),
            JsonObject(failure + ("binding" to JsonPrimitive("other-task"))),
            JsonObject(failure + ("arguments" to json("""{"action":"context","pull_number":99,"expected_head_sha":"$oldHead","verify_live":true}"""))),
            JsonObject(failure + ("arguments" to json("""{"action":"update","pull_number":2367,"expected_head_sha":"$oldHead","title":"change"}""")))
        )) {
            assertFalse(available(listOf(listed, headers, candidate)).any { it.callId.startsWith("pr-head-") })
        }
        assertTrue(GitHubSelfTestPullReads.recover(listOf(tool.copy(modelVisible = false)), listOf(listed, headers, failure), GitHubSelfTestPullReads.select(listOf(listed, headers))).isEmpty())
    }

    private fun available(receipts: List<JsonObject>) = GitHubSelfTestReads.available(listOf(tool), listOf(workspace) + receipts)

    private fun receipt(action: String, result: String): JsonObject = json(
        """{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"${tool.name}","arguments":{"action":"$action"${if (action == "list") "" else ",\"pull_number\":2367"}},"result":$result}"""
    )

    private fun failed() = JsonObject(receipt("context", "{}") + mapOf("success" to JsonPrimitive(false), "error_code" to JsonPrimitive("GITHUB_PR_HEAD_CHANGED"), "arguments" to json("""{"action":"context","pull_number":2367,"expected_head_sha":"$oldHead","verify_live":true}""")))

    private fun attempted(call: ProviderEvent.ToolCall, success: Boolean) = JsonObject(failed() + mapOf("call_id" to JsonPrimitive(call.callId), "arguments" to call.arguments, "success" to JsonPrimitive(success)))
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
}
