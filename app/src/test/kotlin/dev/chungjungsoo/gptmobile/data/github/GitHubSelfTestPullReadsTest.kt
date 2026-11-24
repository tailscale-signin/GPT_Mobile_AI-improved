package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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

    @Test fun unheadedContextRequiresItsOwnFreshLiveHeaderBeforeOnePinnedRead() {
        val failure = unheadedFailure()
        val oldHeaders = JsonObject(receipt("headers", """{"number":2367,"head":{"sha":"$freshHead"}}""") + mapOf("evidence_origin" to JsonPrimitive("LIVE"), "arguments" to json("""{"action":"headers","pull_number":2367,"verify_live":true}""")))
        val before = listOf(listed, oldHeaders, failure)
        val refresh = available(before).single { it.callId.startsWith("pr-head-refresh-") }
        assertEquals(json("""{"action":"headers","pull_number":2367,"verify_live":true}"""), refresh.arguments)
        assertFalse(available(before).any { it.arguments["action"] == JsonPrimitive("context") })
        for (observed in listOf(oldHead, freshHead)) {
            val refreshed = liveHeader(refresh, observed)
            val retry = available(before + refreshed).single { it.arguments["action"] == JsonPrimitive("context") }
            assertTrue(retry.callId.startsWith("pr-head-retry-"))
            assertEquals(tool.name, retry.name)
            assertEquals(json("""{"action":"context","pull_number":2367,"expected_head_sha":"$observed","verify_live":true}"""), retry.arguments)
            assertTrue(GitHubSelfTestReads.valid(tool, retry.arguments))
            assertFalse(available(before + refreshed + attempted(retry, true)).any { it.arguments["action"] == JsonPrimitive("context") })
        }
    }

    @Test fun unheadedRecoveryRetainsArgumentsAndSingleAllowanceAcrossReceiptSerialization() {
        val original = json("""{"action":"context","owner":"owner","repo":"repo","pull_number":2367,"include_files":true,"include_checks":false,"include_patches":true,"page":2}""")
        val failure = unheadedFailure(original)
        val before = restored(listOf(listed, failure))
        val refresh = available(before).single { it.callId.startsWith("pr-head-refresh-") }
        assertEquals(refresh, available(restored(before)).single { it.callId.startsWith("pr-head-refresh-") })
        val refreshed = JsonObject(liveHeader(refresh, freshHead) + ("call_id" to JsonPrimitive("${refresh.callId}-resumed")))
        val after = restored(before + refreshed)
        val retry = available(after).single { it.arguments["action"] == JsonPrimitive("context") }
        assertEquals(JsonObject(original + mapOf("expected_head_sha" to JsonPrimitive(freshHead), "verify_live" to JsonPrimitive(true))), retry.arguments)
        assertEquals(retry, available(restored(after)).single { it.arguments["action"] == JsonPrimitive("context") })
        assertTrue(GitHubActionIdentity.matches(retry.name, retry.arguments, attempted(retry, false)))
        assertEquals(original, after[1]["arguments"])
        val secondFailure = JsonObject(attempted(retry, false) + ("call_id" to JsonPrimitive("${retry.callId}-resumed")))
        assertFalse(available(restored(after + secondFailure)).any { it.callId.startsWith("pr-head-") })
    }

    @Test fun unheadedRecoveryRejectsFailedCachedMalformedAndPreFailureHeaderReceipts() {
        val failure = unheadedFailure()
        val before = listOf(listed, failure)
        val refresh = available(before).single { it.callId.startsWith("pr-head-refresh-") }
        val valid = liveHeader(refresh, freshHead)
        val arguments = valid["arguments"] as JsonObject
        val invalid = listOf(
            JsonObject(valid + mapOf("success" to JsonPrimitive(false), "error_code" to JsonPrimitive("GITHUB_PERMISSION_DENIED"))),
            JsonObject(valid + ("evidence_origin" to JsonPrimitive("CACHED"))),
            JsonObject(valid + ("evidence_origin" to JsonPrimitive("LOCAL"))),
            JsonObject(valid + ("evidence_origin" to JsonPrimitive("MOCKED"))),
            JsonObject(valid + ("evidence_origin" to JsonPrimitive("NOT_TESTED"))),
            JsonObject(valid + ("arguments" to JsonObject(arguments - "verify_live"))),
            JsonObject(valid + ("arguments" to JsonObject(arguments + ("expected_head_sha" to JsonPrimitive(freshHead))))),
            JsonObject(valid + ("arguments" to JsonObject(arguments + ("pull_number" to JsonPrimitive(99))))),
            JsonObject(valid + ("result" to json("""{"number":99,"head":{"sha":"$freshHead"}}"""))),
            JsonObject(valid + ("result" to json("""{"number":2367,"head":{"sha":"moving-branch"}}"""))),
            JsonObject(valid + ("result" to json("""{"number":2367}""")))
        )
        for (candidate in invalid) {
            assertFalse(available(restored(before + candidate)).any { it.callId.startsWith("pr-head-") })
        }
        assertFalse(available(restored(listOf(listed, valid, failure))).any { it.callId.startsWith("pr-head-") })
        val repeated = JsonObject(valid + ("call_id" to JsonPrimitive("${refresh.callId}-second")))
        assertFalse(available(restored(before + valid + repeated)).any { it.callId.startsWith("pr-head-") })
        val unrelated = JsonObject(valid + ("call_id" to JsonPrimitive("ordinary-header")))
        val next = available(before + unrelated)
        assertFalse(next.any { it.arguments["action"] == JsonPrimitive("context") })
        assertEquals(refresh, next.single { it.callId.startsWith("pr-head-refresh-") })
        val foreign = listOf(
            JsonObject(valid + ("binding" to JsonPrimitive("other-task"))),
            JsonObject(valid + ("repository" to JsonPrimitive("other/repo"))),
            JsonObject(valid + ("tool" to JsonPrimitive("github_pr__other"))),
            JsonObject(valid + ("trusted" to JsonPrimitive(false))),
            JsonObject(valid + ("verification_eligible" to JsonPrimitive(false))),
            JsonObject(valid + ("repository_role" to JsonPrimitive("READ_SOURCE")))
        )
        for (candidate in foreign) {
            val scoped = available(restored(before + candidate))
            assertFalse(scoped.any { it.arguments["action"] == JsonPrimitive("context") })
            assertEquals(refresh, scoped.single { it.callId.startsWith("pr-head-refresh-") })
        }
    }

    @Test fun unheadedRecoveryStopsWhenTheFreshLeaseChangesOrThePinnedRetryFails() {
        val failure = unheadedFailure()
        val before = listOf(listed, failure)
        val refresh = available(before).single { it.callId.startsWith("pr-head-refresh-") }
        val refreshed = liveHeader(refresh, freshHead)
        val retry = available(before + refreshed).single { it.arguments["action"] == JsonPrimitive("context") }
        val newer = receipt("headers", """{"number":2367,"head":{"sha":"${"d".repeat(40)}"}}""")
        assertFalse(available(restored(before + refreshed + newer)).any { it.callId.startsWith("pr-head-") })
        assertFalse(available(restored(before + refreshed + attempted(retry, false) + newer)).any { it.callId.startsWith("pr-head-") })
    }

    @Test fun unheadedRecoveryDoesNotTreatOtherPinsOrMalformedArgumentsAsAbsent() {
        val original = json("""{"action":"context","pull_number":2367}""")
        val fields = listOf(
            "expected_head" to JsonPrimitive(oldHead),
            "expected_base_sha" to JsonPrimitive(oldHead),
            "expected_head_sha" to JsonNull,
            "expected_head_sha" to JsonPrimitive(""),
            "expected_head_sha" to JsonPrimitive("main"),
            "expected_head_sha" to JsonPrimitive(7),
            "expected_head_sha" to JsonArray(emptyList()),
            "view_cursor" to JsonPrimitive("saved-view"),
            "verify_live" to JsonPrimitive("true"),
            "include_files" to JsonPrimitive("yes"),
            "owner" to JsonPrimitive("another-owner")
        )
        for (field in fields) {
            val failure = unheadedFailure(JsonObject(original + field))
            assertFalse(available(listOf(listed, failure)).any { it.callId.startsWith("pr-head-") })
        }
        for (action in listOf("headers", "comments", "reviews", "threads", "update")) {
            val failure = unheadedFailure(JsonObject(original + ("action" to JsonPrimitive(action))))
            assertFalse(available(listOf(listed, failure)).any { it.callId.startsWith("pr-head-") })
        }
    }

    @Test fun unheadedRecoveryRequiresCurrentTrustedScopeAndAnAvailableHeaderSchema() {
        val failure = unheadedFailure()
        val candidates = listOf(
            JsonObject(failure + ("trusted" to JsonPrimitive(false))),
            JsonObject(failure + ("verification_eligible" to JsonPrimitive(false))),
            JsonObject(failure + ("repository_role" to JsonPrimitive("READ_SOURCE"))),
            JsonObject(failure + ("binding" to JsonPrimitive("other-task"))),
            JsonObject(failure + ("repository" to JsonPrimitive("other/repo"))),
            JsonObject(failure + ("tool" to JsonPrimitive("github_pr__other"))),
            JsonObject(failure + ("error_code" to JsonPrimitive("GITHUB_PERMISSION_DENIED"))),
            unheadedFailure(json("""{"action":"context","pull_number":99}"""))
        )
        for (candidate in candidates) {
            assertFalse(available(listOf(listed, candidate)).any { it.callId.startsWith("pr-head-") })
        }
        val observations = listOf(workspace, listed, failure)
        val selected = GitHubSelfTestPullReads.select(observations)
        val contextOnly = tool.copy(inputSchema = json("""{"type":"object","properties":{"action":{"const":"context"},"pull_number":{"type":"integer"},"verify_live":{"type":"boolean"},"expected_head_sha":{"type":"string"}},"required":["action","pull_number"],"additionalProperties":false}"""))
        for (definitions in listOf(emptyList(), listOf(tool.copy(modelVisible = false)), listOf(tool, tool), listOf(contextOnly))) {
            assertTrue(GitHubSelfTestPullReads.recover(definitions, observations, selected).isEmpty())
        }
    }

    @Test fun unheadedRecoveryUsesTheExistingPreparationAcquisitionReserve() {
        val failure = unheadedFailure()
        val receipts = listOf(workspace, listed, failure).mapIndexed { index, receipt -> index.toString() to receipt }.toMap()
        val expected = available(listOf(listed, failure)).single { it.callId.startsWith("pr-head-refresh-") }
        fun preparation(remaining: Int) = GitHubIntegrationPreparation.snapshot("test all GitHub tools", receipts, listOf(tool), JsonArray(emptyList()), remaining, null)
        val prepared = preparation(64)["ready_reads"] as JsonArray
        assertTrue(prepared.filterIsInstance<JsonObject>().any { it["tool"] == JsonPrimitive(expected.name) && it["arguments"] == expected.arguments })
        for (remaining in listOf(0, GitHubChecklistBudget.VERIFICATION_RESERVE)) {
            assertEquals(JsonPrimitive(0), preparation(remaining)["ready_read_count"])
            assertEquals(JsonArray(emptyList()), preparation(remaining)["ready_reads"])
        }
    }

    private fun unheadedFailure(arguments: JsonObject = json("""{"action":"context","pull_number":2367}""")) = JsonObject(failed() + ("arguments" to arguments))

    private fun liveHeader(call: ProviderEvent.ToolCall, head: String) = JsonObject(
        receipt("headers", """{"number":2367,"head":{"sha":"$head"}}""") + mapOf(
            "call_id" to JsonPrimitive(call.callId),
            "arguments" to call.arguments,
            "dispatched" to JsonPrimitive(true),
            "evidence_origin" to JsonPrimitive("LIVE")
        )
    )

    private fun restored(receipts: List<JsonObject>) = (Json.parseToJsonElement(JsonArray(receipts).toString()) as JsonArray).map { it as JsonObject }

    private fun available(receipts: List<JsonObject>) = GitHubSelfTestReads.available(listOf(tool), listOf(workspace) + receipts)

    private fun receipt(action: String, result: String): JsonObject = json(
        """{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"${tool.name}","arguments":{"action":"$action"${if (action == "list") "" else ",\"pull_number\":2367"}},"result":$result}"""
    )

    private fun failed() = JsonObject(receipt("context", "{}") + mapOf("success" to JsonPrimitive(false), "error_code" to JsonPrimitive("GITHUB_PR_HEAD_CHANGED"), "arguments" to json("""{"action":"context","pull_number":2367,"expected_head_sha":"$oldHead","verify_live":true}""")))

    private fun attempted(call: ProviderEvent.ToolCall, success: Boolean) = JsonObject(failed() + mapOf("call_id" to JsonPrimitive(call.callId), "arguments" to call.arguments, "success" to JsonPrimitive(success)))
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
}
