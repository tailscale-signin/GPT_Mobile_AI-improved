package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReviewProgressTest {
    private val head = "a".repeat(40)
    private val base = "b".repeat(40)
    private val task = "Review this branch.\nBaseline: $base"
    private val contextTool = AgentToolDefinition("github_context", "Context", json("""{"type":"object","properties":{"action":{"type":"string"},"ref":{"type":"string"},"base_sha":{"type":"string"},"commit_sha":{"type":"string"},"include_patches":{"type":"boolean"},"view_cursor":{"type":"string"}}}"""))
    private val readTool = AgentToolDefinition("github_read", "Read", json("""{"type":"object","properties":{"commit_sha":{"type":"string"},"ranges":{"type":"array","maxItems":8},"view_cursor":{"type":"string"}},"additionalProperties":false}"""))
    private val tools = listOf(contextTool, readTool)
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun receipt(tool: String, arguments: JsonObject, result: JsonObject, success: Boolean = true) = buildJsonObject {
        put("tool", tool)
        put("binding", "selected")
        put("repository", "owner/repo")
        put("trusted", true)
        put("verification_eligible", true)
        put("success", success)
        put("arguments", arguments)
        put("result", result)
        put("observed_at_ms", 1000)
        put("epoch", 1)
        put("immutable", false)
    }
    private fun status() = receipt("github_context", json("""{"action":"status"}"""), json("""{"branch":"main","default_branch":"main","commit_sha":"$head"}"""))
    private fun comparison(files: List<JsonObject>, page: Int = 1, total: Int = files.size, more: Boolean = false) = receipt(
        "github_context",
        json("""{"action":"changes","base_sha":"$base","commit_sha":"$head","page":$page,"per_page":100}"""),
        buildJsonObject {
            put("base_sha", base)
            put("head_sha", head)
            put("files", JsonArray(files))
            put("files_total", total)
            put("page", page)
            put("per_page", 100)
            put("has_more", more)
        }
    )

    @Test fun pinnedComparisonAndItsRestoredFragmentsSurviveAgeAndEpochChanges() {
        val compared = comparison(listOf(json("""{"filename":"src/A.kt","status":"modified"}""")))
        val arguments = compared.getValue("arguments").jsonObject
        assertTrue(GitHubOperationTool.immutableRead("github_context_inspect__selected", arguments))
        for (mutable in listOf(
            JsonObject(arguments + ("base_sha" to JsonPrimitive("main"))),
            JsonObject(arguments + ("commit_sha" to JsonPrimitive("feature"))),
            JsonObject(arguments + ("action" to JsonPrimitive("checks"))),
            json("""{"view_cursor":"saved:100:8192"}""")
        )) {
            assertFalse(GitHubOperationTool.immutableRead("github_context", mutable))
        }
        val saved = JsonObject(receipt("github_context", json("""{"view_cursor":"saved:100:8192"}"""), json("""{"metadata":{"base_sha":"$base","head_sha":"$head"}}""")) + ("source_observation_receipt_id" to JsonPrimitive("compare")))
        val rows = mapOf("compare" to compared, "saved" to saved)
        val claim = json("""{"receipt_id":"saved","pointer":"/metadata/base_sha","value":"$base"}""")
        assertEquals(JsonPrimitive("VERIFIED"), GitHubEvidencePolicy.verify(claim, rows, 300_000, 9)["status"])
        val foreign = JsonObject(compared + ("binding" to JsonPrimitive("other")))
        assertEquals(JsonPrimitive("STALE"), GitHubEvidencePolicy.verify(claim, rows + ("compare" to foreign), 300_000, 9)["status"])
        val check = receipt("github_context", json("""{"action":"checks","commit_sha":"$head"}"""), json("""{"status":"success"}"""))
        assertEquals(JsonPrimitive("STALE"), GitHubEvidencePolicy.verify(json("""{"receipt_id":"checks","pointer":"/status","value":"success"}"""), mapOf("checks" to check), 300_000, 9)["status"])
    }

    @Test fun sourceSeedIsDeterministicBoundedAndStopsAtTheVerificationReserve() {
        val files = (1..30).map { json("""{"filename":"src/File$it.kt","status":"modified","patch":"@@ -400,4 +500,5 @@"}""") }
        val rows = linkedMapOf("status" to status(), "compare" to comparison(files))
        val first = requireNotNull(GitHubReviewComparison.plan(task, tools, rows)?.nextCall)
        assertEquals("github_read", first.name)
        assertEquals(JsonPrimitive(head), first.arguments["commit_sha"])
        val ranges = first.arguments["ranges"] as JsonArray
        assertEquals(4, ranges.size)
        assertTrue(ranges.all { it.jsonObject["start_line"] == JsonPrimitive(480) && it.jsonObject["max_lines"] == JsonPrimitive(160) })
        assertEquals(first.arguments, GitHubReviewComparison.plan(task, tools, rows.toMap())?.nextCall?.arguments)
        assertNull(GitHubReviewComparison.plan(task, tools, rows, acquireSource = false)?.nextCall)
        rows["head-read"] = receipt(first.name, first.arguments, json("""{"commit_sha":"$head","items":[]}"""))
        val second = requireNotNull(GitHubReviewComparison.plan(task, tools, rows)?.nextCall)
        assertEquals(JsonPrimitive(base), second.arguments["commit_sha"])
        rows["base-read-failed"] = receipt(second.name, second.arguments, json("{}"), success = false)
        val completeSeed = requireNotNull(GitHubReviewComparison.plan(task, tools, rows))
        assertNull(completeSeed.nextCall)
        assertEquals(JsonPrimitive(8), completeSeed.checkpoint["source_work_queue"]?.jsonObject?.get("attempted_windows"))
        assertEquals(JsonPrimitive(false), completeSeed.checkpoint["source_review_verified"])
        assertEquals(JsonPrimitive(30), completeSeed.checkpoint["comparison_coverage"]?.jsonObject?.get("changed_files_without_source_observation"))
        assertNotNull(GitHubReviewComparison.incompleteReason(task, rows))
    }

    @Test fun sourceQueueUsesTheIssuedCursorAfterAnInventedCursorWasRejected() {
        val rows = linkedMapOf("status" to status(), "compare" to comparison(listOf(json("""{"filename":"src/A.kt","status":"added"}"""))))
        val next = requireNotNull(GitHubReviewComparison.plan(task, tools, rows)?.nextCall)
        val continuation = json("""{"tool":"github_read","arguments":{"view_cursor":"saved:230:8192"}}""")
        rows["source"] = JsonObject(
            receipt(next.name, next.arguments, json("""{"commit_sha":"$head","items":[]}""")) +
                ("view" to buildJsonObject { put("continuation_call", continuation) })
        )
        rows["rejected"] = JsonObject(receipt("github_read", json("""{"view_cursor":"saved:208:8192"}"""), json("{}"), false) + ("error_code" to JsonPrimitive("UNISSUED_CURSOR")))
        val recovered = requireNotNull(GitHubReviewComparison.plan(task, tools, rows)?.nextCall)
        assertEquals(continuation["arguments"], recovered.arguments)
        assertEquals("github_read", recovered.name)
        rows["continued"] = receipt(recovered.name, recovered.arguments, json("""{"commit_sha":"$head","items":[]}"""))
        assertNull(GitHubReviewComparison.plan(task, tools, rows)?.nextCall)
    }

    @Test fun earlierHeadersAndPartialRangesCannotRetireTheChangedSourceWindow() {
        val compared = comparison(listOf(json("""{"filename":"src/A.kt","status":"modified","patch":"@@ -400,4 +500,5 @@"}""")))
        val priorRanges = listOf(
            """{"path":"src/A.kt","start_line":1,"max_lines":20}""",
            """{"path":"src/A.kt","start_line":400,"max_lines":160}""",
            """{"path":"src/A.kt","start_line":500,"max_lines":160}""",
            """{"path":"src/A.kt","start_line":480,"max_lines":160,"start_offset":10}"""
        )
        for (priorRange in priorRanges) {
            val rows = linkedMapOf(
                "status" to status(),
                "compare" to compared,
                "earlier-read" to receipt("github_read", json("""{"commit_sha":"$head","ranges":[$priorRange]}"""), json("""{"commit_sha":"$head","items":[]}"""))
            )
            val planned = requireNotNull(GitHubReviewComparison.plan(task, tools, rows))
            val next = requireNotNull(planned.nextCall)
            assertEquals(JsonPrimitive(head), next.arguments["commit_sha"])
            assertEquals(json("""{"path":"src/A.kt","start_line":480,"max_lines":160}"""), (next.arguments["ranges"] as JsonArray).single())
            assertEquals(JsonPrimitive(0), planned.checkpoint["source_work_queue"]?.jsonObject?.get("attempted_windows"))
        }
    }

    @Test fun aContainingReadRetiresItsWindowButNotTheOtherRevision() {
        val compared = comparison(listOf(json("""{"filename":"src/A.kt","status":"modified","patch":"@@ -400,4 +500,5 @@"}""")))
        for (range in listOf(
            """{"path":"src/A.kt","start_line":400,"end_line":700}""",
            """{"path":"src/A.kt","start_line":480,"max_lines":160}"""
        )) {
            val rows = linkedMapOf(
                "status" to status(),
                "compare" to compared,
                "head-read" to receipt("github_read", json("""{"commit_sha":"$head","ranges":[$range]}"""), json("""{"commit_sha":"$head","items":[]}"""))
            )
            val planned = requireNotNull(GitHubReviewComparison.plan(task, tools, rows))
            assertEquals(JsonPrimitive(base), requireNotNull(planned.nextCall).arguments["commit_sha"])
            assertEquals(JsonPrimitive(1), planned.checkpoint["source_work_queue"]?.jsonObject?.get("attempted_windows"))
            assertEquals(JsonPrimitive(false), planned.checkpoint["source_review_verified"])
        }
    }

    @Test fun deletedAndRenamedSourceUsesExistingPathsAtEachRevision() {
        val compared = comparison(
            listOf(
                json("""{"filename":"src/Deleted.kt","status":"removed"}"""),
                json("""{"filename":"src/New.kt","previous_filename":"src/Old.kt","status":"renamed"}"""),
                json("""{"filename":"src/Added.kt","status":"added"}""")
            )
        )
        val work = GitHubReviewSourceQueue.plan(task, mapOf("compare" to compared), mapOf("status" to status()), base, head, "owner/repo", "selected")
        val byRevision = work.recipes.associate { recipe ->
            val args = recipe.getValue("arguments").jsonObject
            args["commit_sha"] to (args["ranges"] as JsonArray).map { it.jsonObject["path"] }.toSet()
        }
        assertEquals(setOf("src/Added.kt", "src/New.kt").map(::JsonPrimitive).toSet(), byRevision[JsonPrimitive(head)])
        assertEquals(setOf("src/Deleted.kt", "src/Old.kt").map(::JsonPrimitive).toSet(), byRevision[JsonPrimitive(base)])
        val observed = JsonObject(
            receipt("github_read", json("""{"commit_sha":"$base","ranges":[{"path":"src/Old.kt"}]}"""), json("""{"commit_sha":"$base","items":[{"path":"src/Old.kt","commit_sha":"$base","sha":"${"c".repeat(40)}","expected_blob_sha":"${"c".repeat(40)}","content":"fun beforeRename() = true","start_line":1,"end_line":1,"total_lines":1,"range_complete":true}]}""")) +
                ("source_head_sha" to JsonPrimitive(base))
        )
        val state = requireNotNull(GitHubReviewComparison.plan(task, emptyList(), mapOf("status" to status(), "compare" to compared, "source" to observed))).checkpoint
        assertEquals(JsonPrimitive(1), state["comparison_coverage"]?.jsonObject?.get("changed_files_with_source_observation"))
    }

    @Test fun missingBaselineUsesRetainedDecisionRatherThanExtractorQuestions() {
        val review = "Review this branch."
        val answer = json("""{"claims":[{"kind":"not_measured","subject":"Source correctness"}],"questions":["Should this review use the first parent instead?","Can we use more tool calls?"]}""")
        val (rendered, report) = GitHubEvidencePolicy.render(answer, mapOf("status" to status()), 1000, 1, review)
        val state = report.getValue("review_state").jsonObject
        assertEquals(JsonPrimitive("BASELINE_REQUIRED"), state["state"])
        assertEquals(JsonPrimitive(true), state["user_input_required"])
        assertFalse(state.containsKey("base_sha"))
        val nextSteps = rendered.substringAfter("## Next steps\n\n", "").substringBefore("\n\n## Retained review scope")
        assertTrue(nextSteps, nextSteps.contains("Baseline:"))
        assertTrue(nextSteps, nextSteps.contains("commit, tag or branch"))
        assertFalse(rendered.contains("Should this review use the first parent instead?"))
        assertFalse(rendered.contains("Can we use more tool calls?"))
        assertEquals(JsonPrimitive(false), report["completion_verified"])
    }

    @Test fun mandatoryProjectionRetainsTheAnsweredBaselineAndCompletedLargeComparison() {
        val review = "Review this branch."
        val chooser = AgentToolDefinition("github_decide", "Choose", json("{}"), capabilities = setOf(AgentToolCapability.USER_DECISION))
        val rows = linkedMapOf("status" to status(), "head-detail" to receipt("github_context", json("""{"action":"commit","ref":"$head"}"""), json("""{"commit_sha":"$head","parents":[{"sha":"$base"}]}""")))
        val offered = requireNotNull(GitHubReviewComparison.plan(review, tools + chooser, rows)?.nextCall)
        rows["decision"] = JsonObject(
            receipt(offered.name, offered.arguments, json("""{"user_answer":"Before this commit (${base.take(12)})","cancelled":false}""")) + mapOf(
                "call_id" to JsonPrimitive(offered.callId),
                "shared_result" to JsonPrimitive(true),
                "dispatched" to JsonPrimitive(false)
            )
        )
        repeat(11) { page ->
            val count = if (page == 10) 55 else 100
            rows["page-$page"] = comparison((1..count).map { json("""{"filename":"src/Source${page * 100 + it}.kt","status":"modified"}""") }, page + 1, 1055, page < 10)
        }
        val state = requireNotNull(GitHubReviewComparison.plan(review, emptyList(), rows)).checkpoint
        assertEquals(JsonPrimitive(true), state["comparison_verified"])
        assertEquals(JsonPrimitive("decision"), state["baseline_decision_receipt_id"])
        val mandatory = GitHubVerificationCatalog.mandatory(GitHubEvidencePolicy.catalog(rows), state)
        assertEquals(setOf("decision", "status", "page-0", "page-10").map(::JsonPrimitive).toSet(), mandatory.map { it["receipt_id"] }.toSet())
        val last = mandatory.single { it["receipt_id"] == JsonPrimitive("page-10") }.getValue("facts").jsonObject
        assertEquals(JsonPrimitive(base), last["/base_sha"])
        assertEquals(JsonPrimitive(head), last["/head_sha"])
        assertEquals(JsonPrimitive(1055), last["/files_total"])
        val selected = GitHubVerificationCatalog.select(GitHubEvidencePolicy.catalog(rows), "Review changed files status status status")
        assertTrue(selected.filter { it["arguments"]?.jsonObject?.get("action") == JsonPrimitive("changes") }.all { "/base_sha" in it.getValue("facts").jsonObject })
        val answer = json("""{"claims":[{"kind":"not_measured","subject":"Source correctness"}],"questions":["Which baseline should I use?"]}""")
        val (rendered, report) = GitHubEvidencePolicy.render(answer, rows, 300_000, 9, review)
        assertFalse(rendered.contains("Which baseline should I use?"))
        assertTrue(rendered.contains(base))
        assertEquals(JsonPrimitive(base), report["review_state"]?.jsonObject?.get("base_sha"))
    }

    @Test fun comparisonPrerequisiteCanPassWhileSourceCorrectnessRemainsUnverified() {
        val prerequisite = "Inspect the exact head/base revisions and changed files"
        val correctness = "Identify correctness findings with file/line evidence"
        val request = "$task\nAcceptance criteria:\n- $prerequisite\n- $correctness"
        val rows = mapOf("status" to status(), "compare" to comparison(listOf(json("""{"filename":"src/A.kt","status":"modified"}"""))))
        assertNull(GitHubReviewComparison.comparisonIncompleteReason(request, rows))
        assertNotNull(GitHubReviewComparison.incompleteReason(request, rows))
        assertEquals(GitHubAcceptanceContract.Producer.COMPARISON_OBSERVATION, GitHubAcceptanceContract.producer(prerequisite))
        assertEquals(GitHubAcceptanceContract.Producer.SOURCE_OBSERVATION, GitHubAcceptanceContract.producer("Inspect changed files and their source code"))
        assertEquals(GitHubAcceptanceContract.Producer.SOURCE_OBSERVATION, GitHubAcceptanceContract.producer("Inspect changed files for security implications"))
        assertFalse(GitHubReviewComparison.comparisonOnly("Inspect the exact head/base revisions and changed files for security implications"))
        val claims = listOf(json("""{"kind":"fact","status":"VERIFIED","receipt_id":"compare"}"""))
        val answer = buildJsonObject {
            put(
                "requirement_claims",
                buildJsonObject {
                    put(GitHubRequestCompletion.id(prerequisite), JsonArray(listOf(JsonPrimitive(0))))
                    put(GitHubRequestCompletion.id(correctness), JsonArray(listOf(JsonPrimitive(0))))
                }
            )
        }
        val completion = GitHubRequestCompletion.evaluate(request, claims, answer, rows, 1000)
        val acceptance = completion.getValue("requirements").let { it as JsonArray }.map { it.jsonObject }.associateBy { it["requirement"] }
        assertEquals(JsonPrimitive("PASSED"), acceptance[JsonPrimitive(prerequisite)]?.get("status"))
        assertFalse(acceptance[JsonPrimitive(correctness)]?.get("status") == JsonPrimitive("PASSED"))
        assertEquals(JsonPrimitive(false), completion["completion_verified"])
    }
}
