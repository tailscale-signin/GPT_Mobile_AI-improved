package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReviewRecoveryTest {
    @Test fun liveReviewResumesOnlyClaimVerificationAfterRestoringItsOriginalReceipts() = runBlocking {
        val scenario = Scenario()
        val liveBook = scenario.book()
        val liveReview = GitHubReviewEvidence()
        liveBook.observe(scenario.call, scenario.result)
        liveReview.observe(scenario.call, scenario.result)
        val id = liveBook.snapshot().first.keys.single()
        var extractions = 0
        fun verified(book: GitHubEvidenceBook, review: GitHubReviewEvidence) = GitHubVerifiedSession(
            scenario.gate(review), book, scenario.task, { 8192 },
            {
                extractions++
                if (extractions == 1) {
                    response(ProviderEvent.Failed("Formatter unavailable", errorCode = "PROVIDER_UNAVAILABLE"))
                } else {
                    response(ProviderEvent.TextDelta("""{"claims":[{"kind":"fact","receipt_id":"$id","pointer":"/checks","value":3}]}"""), ProviderEvent.Completed)
                }
            }, {}, {}, now = { 1000L },
            onPublication = { report ->
                if (report["completion_verified"] == JsonPrimitive(true) || report["verification_failure"] == JsonPrimitive("GITHUB_TASK_INCOMPLETE")) scenario.checkpoint().completed()
            }
        )
        val first = verified(liveBook, liveReview).streamRound(emptyList(), emptyList()).toList()
        assertTrue(first.last() is ProviderEvent.Failed)
        assertTrue(scenario.checkpoint().load()?.reviewed == true)

        val restoredBook = scenario.book().also { it.restore() }
        val restoredReview = GitHubReviewEvidence().also { it.restoreValidated(restoredBook.checkpointBinding(), restoredBook.snapshot().first) }
        // Live review uses a compact projection; recovery preserves the original provenance.
        assertNotEquals(liveReview.snapshot(), restoredReview.snapshot())
        val resumed = verified(restoredBook, restoredReview).streamRound(emptyList(), emptyList()).toList()
        assertTrue(resumed.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("3"))
        assertEquals(1, scenario.generations)
        assertEquals(1, scenario.reviews)
        assertEquals(2, extractions)
    }

    @Test fun changedResultForTheSameCallRequiresANewReviewAndReplacesTheOldProjection() = runBlocking {
        val scenario = Scenario()
        val book = scenario.book()
        val live = GitHubReviewEvidence()
        book.observe(scenario.call, scenario.result)
        live.observe(scenario.call, scenario.result)
        scenario.gate(live).streamRound(emptyList(), emptyList()).toList()
        val (id, original) = book.snapshot().first.entries.single()
        val changed = buildJsonObject { put("checks", 5) }
        scenario.receipts[id] = JsonObject(original + mapOf("result" to changed, "result_sha256" to JsonPrimitive(GitHubEvidenceBook.digest(changed.toString()))))

        val restoredBook = scenario.book().also { it.restore() }
        val restored = GitHubReviewEvidence().also { it.restoreValidated(restoredBook.checkpointBinding(), restoredBook.snapshot().first) }
        val events = scenario.gate(restored).streamRound(emptyList(), emptyList()).toList()
        assertEquals(ProviderEvent.Completed, events.last())
        assertEquals(1, scenario.generations)
        assertEquals(2, scenario.reviews)
        val reviewedRecords = Json.parseToJsonElement(scenario.lastReview).jsonObject.getValue("records").jsonArray
        assertEquals(1, reviewedRecords.size)
        val evidence = Json.parseToJsonElement(reviewedRecords.single().jsonObject.getValue("evidence").jsonPrimitive.content).jsonObject
        assertEquals(JsonPrimitive(5), evidence.getValue("result").jsonObject["checks"])
    }

    @Test fun revokedEvidenceCannotReuseTheReviewedApprovalOrReturnThroughReplay() = runBlocking {
        val scenario = Scenario("Implement the repository feature")
        val book = scenario.book()
        val live = GitHubReviewEvidence()
        book.observe(scenario.call, scenario.result)
        live.observe(scenario.call, scenario.result)
        scenario.gate(live).streamRound(emptyList(), emptyList()).toList()

        val restoredBook = scenario.book(emptySet()).also { it.restore() }
        val restored = GitHubReviewEvidence().also { it.restoreValidated(restoredBook.checkpointBinding(), restoredBook.snapshot().first) }
        restored.observe(scenario.call, scenario.result)
        assertTrue(restored.snapshot().isEmpty())
        val events = scenario.gate(restored).streamRound(emptyList(), emptyList()).toList()
        assertEquals("GITHUB_REVIEW_BLOCKED", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertTrue(events.none { it is ProviderEvent.TextDelta })
        assertEquals(1, scenario.generations)
        assertEquals(1, scenario.reviews)
        assertEquals("Saved candidate", scenario.checkpoint().load()?.draft)
        assertFalse(scenario.checkpoint().load()?.reviewed == true)
    }

    @Test fun changedOutcomeOrArgumentsCannotMatchOnlyByCallIdAndResultDigest() = runBlocking {
        val scenario = Scenario()
        val book = scenario.book()
        val live = GitHubReviewEvidence()
        book.observe(scenario.call, scenario.result)
        live.observe(scenario.call, scenario.result)
        val saved = live.snapshot()
        val (id, original) = book.snapshot().first.entries.single()
        for (change in listOf(
            mapOf("success" to JsonPrimitive(false)),
            mapOf("dispatched" to JsonPrimitive(false)),
            mapOf("arguments" to buildJsonObject { put("action", "commits") }),
            mapOf("result_sha256" to JsonPrimitive("0".repeat(64)))
        )) {
            val restored = GitHubReviewEvidence()
            restored.restoreValidated(book.checkpointBinding(), mapOf(id to JsonObject(original + change)))
            assertFalse(restored.coveredBy(saved, restored.snapshot()))
        }
    }

    private class Scenario(val task: String = "Explain the repository design") {
        val receipts = linkedMapOf<String, JsonObject>()
        private val reviewRows = linkedMapOf<String, JsonObject>()
        var generations = 0
        var reviews = 0
        var lastReview = ""
        val call = ProviderEvent.ToolCall("original-call", "github_context", buildJsonObject { put("action", "checkz") })
        val result = AgentToolResult(
            call.callId,
            ToolResultContent.Text("{\n  \"checks\": 3,\n  \"node_id\": \"original metadata\"\n}"),
            false,
            executionReceipt = buildJsonObject { put("corrected_arguments", buildJsonObject { put("action", "checks") }) }
        )

        fun book(tools: Set<String> = setOf("github_context")) = GitHubEvidenceBook(
            "same-task-binding",
            "owner/repo",
            "main",
            tools,
            now = { 1000L },
            load = { receipts[it] },
            save = { id, row -> receipts[id] = row },
            retainedIds = { receipts.keys.filter { it.startsWith("ev_") } }
        )

        fun checkpoint() = GitHubReviewCheckpoint(task, { reviewRows[it] }, { id, row -> reviewRows[id] = row })

        fun gate(evidence: GitHubReviewEvidence) = GitHubReviewedSession(
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
                    generations++
                    return flowOf(ProviderEvent.TextDelta("Saved candidate"), ProviderEvent.Completed)
                }
            },
            evidence,
            task,
            8192,
            { prompt, _ ->
                reviews++
                lastReview = prompt
                val ids = Json.parseToJsonElement(prompt).jsonObject.getValue("records").jsonArray.map { it.jsonObject.getValue("id") }
                val verdict = buildJsonObject {
                    put("schema_version", 1)
                    put("verdict", "PASS")
                    put("reason", "Checked the original tool outcome.")
                    put("reviewed_ids", JsonArray(ids))
                    put("issues", JsonArray(emptyList()))
                    put("verified_claims", JsonArray(listOf(JsonPrimitive("The receipt records the observed checks."))))
                }
                response(ProviderEvent.TextDelta(verdict.toString()), ProviderEvent.Completed)
            },
            {},
            checkpoint = checkpoint(),
            reviewerRevision = "same-selected-profile"
        )
    }

    companion object {
        private fun response(vararg events: ProviderEvent) = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
        }
    }
}
