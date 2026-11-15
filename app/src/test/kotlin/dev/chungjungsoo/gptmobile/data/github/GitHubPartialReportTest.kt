package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPartialReportTest {
    private fun call(id: String, page: Int) = ProviderEvent.ToolCall(
        id,
        "github_ci",
        buildJsonObject {
            put("action", "workflows")
            put("page", page)
        }
    )

    private fun result(id: String, page: Int) = AgentToolResult(
        id,
        ToolResultContent.Json(
            buildJsonObject {
                put("page", page)
                put("total_count", 57)
                put("workflows", JsonArray(((page - 1) * 30 + 1..minOf(page * 30, 57)).map { buildJsonObject { put("id", it) } }))
                put(
                    "pagination_progress",
                    buildJsonObject {
                        put("unique_records_observed", if (page == 1) 30 else 57)
                        put("complete", page == 2)
                    }
                )
            }
        ),
        false
    )

    @Test fun cachedFirstPageCannotReplaceTheCompleteWorkflowObservation() {
        val first = result("page-one", 1)
        val final = result("page-two", 2)
        val replay = first.copy(callId = "cached-one", sharedResult = true, dispatched = false)
        val observations = listOf(
            requireNotNull(GitHubPartialReport.observe(call("page-one", 1), first)),
            requireNotNull(GitHubPartialReport.observe(call("page-two", 2), final)),
            requireNotNull(GitHubPartialReport.observe(call("cached-one", 1), replay))
        )
        val report = GitHubPartialReport.render(observations)
        assertTrue(report, report.contains("57 (complete captured inventory)"))
        assertTrue(report.contains("receipt `page-two`"))
        assertFalse(report.contains("receipt `cached-one`"))

        val receipts = linkedMapOf<String, JsonObject>()
        for ((id, value) in listOf("page-one" to first, "page-two" to final, "cached-one" to replay)) {
            receipts[id] = buildJsonObject {
                put("receipt_id", id)
                put("tool", "github_ci")
                put("trusted", true)
                put("success", true)
                put("shared_result", value.sharedResult)
                put("dispatched", value.dispatched)
                put("arguments", call(id, if (id == "page-two") 2 else 1).arguments)
                put("result", (value.content as ToolResultContent.Json).value)
            }
        }
        assertTrue(GitHubPartialReport.fromReceipts(receipts).contains("57 (complete captured inventory)"))
    }

    @Test fun aNewLiveTraversalCanReplaceEarlierCompleteCoverage() {
        val observations = listOf(
            requireNotNull(GitHubPartialReport.observe(call("page-one", 1), result("page-one", 1))),
            requireNotNull(GitHubPartialReport.observe(call("page-two", 2), result("page-two", 2))),
            requireNotNull(GitHubPartialReport.observe(call("new-live-page-one", 1), result("new-live-page-one", 1)))
        )
        val report = GitHubPartialReport.render(observations)
        assertTrue(report, report.contains("30 (incomplete captured inventory)"))
        assertTrue(report.contains("receipt `new-live-page-one`"))
        assertFalse(report.contains("57 (complete captured inventory)"))
    }
}
