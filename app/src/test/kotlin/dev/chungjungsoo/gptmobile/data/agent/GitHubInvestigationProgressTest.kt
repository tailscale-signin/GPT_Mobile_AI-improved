package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubInvestigationProgressTest {
    private fun exchange(index: Int, failure: Boolean = false): AgentToolExchange {
        val call = ProviderEvent.ToolCall("call-$index", "github_read", buildJsonObject { put("path", "File$index.kt") })
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("source"), failure, errorCode = if (failure) "SAVED_VIEW_UNAVAILABLE" else null)))
    }

    @Test fun alternatingSuccessfulReadsDoNotHideRecurringPageFailures() {
        val progress = GitHubInvestigationProgress()
        repeat(6) { assertNull(progress.observe(exchange(it, it % 2 == 0))) }
        assertNotNull(progress.observe(exchange(6, true)))
    }

    @Test fun repeatedReceiptsAreNotNewEvidenceButDifferentReadsAre() {
        val progress = GitHubInvestigationProgress()
        repeat(20) { assertNull(progress.observe(exchange(it))) }
        repeat(3) { assertNull(progress.observe(exchange(19))) }
        assertNotNull(progress.observe(exchange(19)))
    }

    @Test fun repeatedPageFailureCanReplanOnceWithoutRemovingUntriedCiReads() {
        val progress = GitHubInvestigationProgress()
        val failed = (1..3).map { exchange(it, true) }
        failed.forEach(progress::observe)
        val replan = progress.replan(failed, listOf(GitHubOperationTool.definition("github_ci")))
        assertNotNull(replan)
        assertTrue(requireNotNull(replan).contains("github_ci action=runs"))
        assertTrue(replan.contains("or repeat a write", ignoreCase = true))
        repeat(3) { assertNull(progress.observe(exchange(10 + it, true))) }
        assertNotNull(progress.observe(exchange(20, true)))
        assertNull(progress.replan(failed, listOf(GitHubOperationTool.definition("github_ci"))))
    }

    @Test fun noAuthorizedAlternativeDoesNotRenewTheLoop() {
        val progress = GitHubInvestigationProgress()
        assertNull(progress.replan(listOf(exchange(1, true)), emptyList()))
        assertNull(progress.replan(listOf(exchange(1, true)), listOf(GitHubOperationTool.definition("github_write"))))
    }

    @Test fun theFirstSuccessfulRecoveryOfAFailedReadIsNewEvidence() {
        val progress = GitHubInvestigationProgress()
        repeat(3) { assertNull(progress.observe(exchange(it, true))) }
        assertNull(progress.observe(exchange(2)))
        repeat(3) { assertNull(progress.observe(exchange(2))) }
        assertNotNull(progress.observe(exchange(2)))
    }

    private fun resolved(index: Int, connection: String = "github_api", repository: String = "repo", head: String = "a".repeat(40), tree: String = "b".repeat(40)): AgentToolExchange {
        val name = if (index % 2 == 0) "github_context__$connection" else "github_context_inspect__$connection"
        val call = ProviderEvent.ToolCall(
            "resolve-$index",
            name,
            buildJsonObject {
                put("action", "resolve")
                put(if (index % 2 == 0) "ref" else "branch", "main")
                put("verify_live", index % 2 == 0)
                put("response_detail", if (index % 3 == 0) "full" else "compact")
            }
        )
        val data = buildJsonObject {
            put("owner", "owner")
            put("repo", repository)
            put("ref", "main")
            put("commit_sha", head)
            put("tree_sha", tree)
            put("observation_sequence", index)
        }
        val receipt = buildJsonObject { put("source_result_sha256", index.toString(16).padStart(64, '0')) }
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Json(data), false, executionReceipt = receipt)))
    }

    @Test fun sameResolvedRevisionCannotRenewProgressThroughNewReceiptsAliasesOrDisplayOptions() {
        val progress = GitHubInvestigationProgress()
        repeat(4) { assertNull(progress.observe(resolved(it))) }
        assertNotNull(progress.observe(resolved(4)))
    }

    @Test fun changedSourceRepositoryAndConnectionRemainIndependentProgress() {
        val progress = GitHubInvestigationProgress()
        repeat(4) { assertNull(progress.observe(resolved(it))) }
        assertNull(progress.observe(resolved(4, head = "c".repeat(40))))
        repeat(3) { assertNull(progress.observe(resolved(5 + it, head = "c".repeat(40)))) }
        assertNull(progress.observe(resolved(8, repository = "another", head = "c".repeat(40))))
        repeat(3) { assertNull(progress.observe(resolved(9 + it, repository = "another", head = "c".repeat(40)))) }
        assertNull(progress.observe(resolved(12, connection = "other", repository = "another", head = "c".repeat(40))))
        assertNull(progress.observe(resolved(13, connection = "other", repository = "another", head = "c".repeat(40), tree = "d".repeat(40))))
    }

    @Test fun replanDoesNotTreatAnInspectAliasAsAnUnreadResolveAction() {
        val progress = GitHubInvestigationProgress()
        val replan = requireNotNull(progress.replan(listOf(resolved(0)), listOf(GitHubOperationTool.definition("github_context_inspect", "github_context_inspect__github_api"))))
        assertTrue(!replan.contains("action=resolve"))
        assertTrue(replan.contains("action=checks"))
    }
    private fun search(index: Int, path: String? = null, line: Int = 1): AgentToolExchange {
        val call = ProviderEvent.ToolCall(
            "search-$index",
            "github_search__github_api",
            buildJsonObject {
                put("owner", "owner")
                put("repo", "repo")
                put("query", "TODO")
                put("index_cursor", index * 10)
            }
        )
        val result = buildJsonObject {
            put("commit_sha", "a".repeat(40))
            put("interval_end", (index + 1) * 10)
            put(
                "items",
                kotlinx.serialization.json.JsonArray(
                    if (path == null) {
                        emptyList()
                    } else {
                        listOf(
                            buildJsonObject {
                                put("path", path)
                                put("sha", "b".repeat(40))
                                put(
                                    "matches",
                                    kotlinx.serialization.json.JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("line", line)
                                                put("text", "TODO repair")
                                            }
                                        )
                                    )
                                )
                            }
                        )
                    }
                )
            )
        }
        val receipt = buildJsonObject { put("source_result_sha256", index.toString(16).padStart(64, '0')) }
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Json(result), false, executionReceipt = receipt)))
    }

    @Test fun advancingEmptySearchCursorsAndResponseDigestsDoNotConcealNoSourceProgress() {
        val progress = GitHubInvestigationProgress()
        repeat(3) { assertNull(progress.observe(search(it))) }
        assertNotNull(progress.observe(search(3)))
    }

    @Test fun newSearchMatchesRemainUsefulButRepeatedSourceCannotResetTheGuard() {
        val progress = GitHubInvestigationProgress()
        repeat(10) { assertNull(progress.observe(search(it, "src/Repair.kt", it + 1))) }
        repeat(3) { assertNull(progress.observe(search(10 + it, "src/Repair.kt", 10))) }
        assertNotNull(progress.observe(search(13, "src/Repair.kt", 10)))
    }

    @Test fun exhaustiveSearchConsumesOnlyExactIssuedFrontiersWithoutResettingSourceStaleness() {
        val progress = GitHubInvestigationProgress("Find every TODO in this repository")
        repeat(6) { assertNull(progress.observe(nativeSearch(it))) }
        // A completed required search is an allowed coverage result, but does not
        // refresh the source counter or authorize replay of its final interval.
        assertNotNull(progress.observe(nativeSearch(5)))
    }

    @Test fun broadAuditCannotEvadeTheGuardWithNewQueriesOrUnrelatedInstructionScope() {
        val changedQueries = GitHubInvestigationProgress("Examine all issues and bugs")
        repeat(3) { assertNull(changedQueries.observe(nativeSearch(0, "needle-$it"))) }
        assertNotNull(changedQueries.observe(nativeSearch(0, "needle-3")))
        val instructions = GitHubInvestigationProgress("Read all repository instructions")
        repeat(3) { assertNull(instructions.observe(nativeSearch(it))) }
        assertNotNull(instructions.observe(nativeSearch(3)))
    }

    @Test fun replayingAnEarlierFrontierCannotReissueItsConsumedExhaustiveSearchAllowance() {
        val progress = GitHubInvestigationProgress("Find every TODO in this repository")
        assertNull(progress.observe(nativeSearch(0)))
        assertNull(progress.observe(nativeSearch(1)))
        assertNull(progress.observe(nativeSearch(0)))
        assertNotNull(progress.observe(nativeSearch(1)))
    }

    private fun nativeSearch(index: Int, query: String = "TODO"): AgentToolExchange {
        val arguments = buildJsonObject {
            put("owner", "owner")
            put("repo", "repo")
            put("query", query)
            put("index_cursor", index * 10)
            if (index > 0) {
                put("commit_sha", "a".repeat(40))
                put("search_id", "c".repeat(64))
            }
        }
        val call = ProviderEvent.ToolCall("native-$index-$query", "github_search__github_api", arguments)
        val data = buildJsonObject {
            put("commit_sha", "a".repeat(40))
            put("search_id", "c".repeat(64))
            put("interval_start", index * 10)
            put("interval_end", (index + 1) * 10)
            put("total_candidate_files", 60)
            put("has_more", index < 5)
            put("complete", index == 5)
            put("items", kotlinx.serialization.json.JsonArray(emptyList()))
        }
        val recipe = buildJsonObject {
            put("tool", call.name)
            put("arguments", JsonObject(arguments + mapOf("commit_sha" to data.getValue("commit_sha"), "search_id" to data.getValue("search_id"), "index_cursor" to data.getValue("interval_end"))))
        }
        val frontier = requireNotNull(dev.chungjungsoo.gptmobile.data.github.GitHubSearchFrontier.capture(call.name, arguments, data, listOf(recipe)))
        val execution = buildJsonObject {
            put("schema", "github.dispatch.v1")
            put("resolved_arguments", JsonObject(arguments + ("action" to JsonPrimitive("search_repository"))))
            put(
                "resource_context",
                buildJsonObject {
                    put("owner", "owner")
                    put("repo", "repo")
                }
            )
            put("search_frontier", frontier)
            put("source_result_sha256", index.toString(16).padStart(64, '0'))
        }
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Json(data), false, executionReceipt = execution)))
    }
}
