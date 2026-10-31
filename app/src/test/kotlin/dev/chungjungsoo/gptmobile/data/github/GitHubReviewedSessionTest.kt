package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReviewedSessionTest {
    @Test fun revokedAuthorityAfterReviewStillPreventsPublication() = runBlocking {
        val output = GitHubReviewedSession(answer(), GitHubReviewEvidence(), "task", 8192, { prompt, _ -> answer(verdict(prompt)) }, {}, validatePublication = { error("Permission changed") }).streamRound(emptyList(), emptyList()).toList()
        assertTrue(output.any { it is ProviderEvent.Failed })
        assertTrue(output.none { it is ProviderEvent.TextDelta })
    }

    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
    }
    private fun answer(text: String = "Verified answer") = session(ProviderEvent.TextDelta(text), ProviderEvent.Completed)
    private fun verdict(prompt: String, approved: Boolean = true): String = buildJsonObject {
        put("approved", approved)
        put("reviewed_ids", JsonArray(Json.parseToJsonElement(prompt).jsonObject.getValue("records").jsonArray.map { it.jsonObject.getValue("id") }))
        put("issues", JsonArray(if (approved) emptyList() else listOf(JsonPrimitive("The test failed; do not claim success."))))
        put("verified_claims", JsonArray(listOf(JsonPrimitive("Checked the reported result."))))
    }.toString()
    private fun receipt(evidence: GitHubReviewEvidence, id: String = "c", text: String = "tests failed") {
        evidence.observe(ProviderEvent.ToolCall(id, "github_checks", JsonObject(emptyMap())), AgentToolResult(id, ToolResultContent.Text(text), false))
    }

    @Test fun noDraftIsPublishedUntilAllEvidenceIsReviewed() = runBlocking {
        val evidence = GitHubReviewEvidence().also { receipt(it) }
        val emitted = mutableListOf<ProviderEvent>()
        var reviewed = false
        val gate = GitHubReviewedSession(answer(), evidence, "Check tests", 8192, { prompt, _ ->
            assertTrue(emitted.none { it is ProviderEvent.TextDelta })
            assertTrue(prompt.contains("tests failed"))
            reviewed = true
            answer(verdict(prompt))
        }, {})
        gate.streamRound(emptyList(), emptyList()).collect { emitted += it }
        assertTrue(reviewed)
        assertEquals(listOf("Verified answer"), emitted.filterIsInstance<ProviderEvent.TextDelta>().map { it.text })
        assertEquals(ProviderEvent.Completed, emitted.last())
    }

    @Test fun rejectedAnswerIsCorrectedOnceAndRechecked() = runBlocking {
        var reviews = 0
        var repairs = 0
        val output = GitHubReviewedSession(answer("All tests passed"), GitHubReviewEvidence().also { receipt(it) }, "Check tests", 8192, { prompt, kind ->
            if (kind.endsWith("repair")) {
                repairs++
                answer("Tests failed")
            } else {
                reviews++
                answer(verdict(prompt, reviews == 2))
            }
        }, {}).streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, reviews)
        assertEquals(1, repairs)
        assertEquals(listOf("Tests failed"), output.filterIsInstance<ProviderEvent.TextDelta>().map { it.text })
    }

    @Test fun repeatedRejectionNeverPublishesDraftAndDoesNotLoop() = runBlocking {
        var attempts = 0
        val output = GitHubReviewedSession(answer(), GitHubReviewEvidence(), "task", 8192, { prompt, kind ->
            attempts++
            if (kind.endsWith("repair")) answer() else answer(verdict(prompt, false))
        }, {}).streamRound(emptyList(), emptyList()).toList()
        assertEquals(3, attempts)
        assertTrue(output.filterIsInstance<ProviderEvent.TextDelta>().isEmpty())
        assertEquals("GITHUB_REVIEW_REQUIRED", output.filterIsInstance<ProviderEvent.Failed>().last().errorCode)
    }

    @Test fun malformedOrIncompleteReviewFailsClosed() = runBlocking {
        for (review in listOf(answer("approved"), session(ProviderEvent.TextDelta("{}")), session(ProviderEvent.Failed("disconnected")))) {
            val output = GitHubReviewedSession(answer(), GitHubReviewEvidence(), "task", 8192, { _, _ -> review }, {}).streamRound(emptyList(), emptyList()).toList()
            assertTrue(output.any { it is ProviderEvent.Failed })
            assertTrue(output.none { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
        }
    }

    @Test fun reviewMustAcknowledgeEveryResult() = runBlocking {
        val output = GitHubReviewedSession(answer(), GitHubReviewEvidence().also { receipt(it) }, "task", 8192, { _, _ -> answer("""{"approved":true,"reviewed_ids":[],"issues":[],"verified_claims":["checked"]}""") }, {}).streamRound(emptyList(), emptyList()).toList()
        assertTrue(output.any { it is ProviderEvent.Failed })
        assertTrue(output.none { it is ProviderEvent.TextDelta })
    }

    @Test fun toolRoundsKeepRunningWithoutPublishingUnreviewedProse() = runBlocking {
        val call = ProviderEvent.ToolCall("c", "github_decide", JsonObject(emptyMap()))
        val output = GitHubReviewedSession(session(ProviderEvent.TextDelta("Unverified intermediate draft"), call, ProviderEvent.Completed), GitHubReviewEvidence(), "task", 8192, { _, _ -> error("Review must wait for final answer") }, {}).streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf(call, ProviderEvent.Completed), output)
    }

    @Test fun cancellationStopsReviewWithoutPublishing() = runBlocking {
        var cancelled = false
        try {
            GitHubReviewedSession(answer(), GitHubReviewEvidence(), "task", 8192, { _, _ -> throw CancellationException("cancel") }, {}).streamRound(emptyList(), emptyList()).toList()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test fun evidenceOverflowAndContextOverflowNeverDropResults() = runBlocking {
        for ((evidence, context) in listOf(GitHubReviewEvidence(5).also { receipt(it) } to 8192, GitHubReviewEvidence() to 4)) {
            val output = GitHubReviewedSession(answer(), evidence, "task", context, { _, _ -> error("No over-budget review may dispatch") }, {}).streamRound(emptyList(), emptyList()).toList()
            assertTrue(output.any { it is ProviderEvent.Failed })
            assertTrue(output.none { it is ProviderEvent.TextDelta })
        }
    }

    @Test fun longEvidenceIsFullyCoveredAcrossReviewWindows() = runBlocking {
        val evidence = GitHubReviewEvidence().also { receipt(it, text = "evidence ".repeat(2000)) }
        val seen = mutableSetOf<String>()
        var consistency = false
        val output = GitHubReviewedSession(answer(), evidence, "task", 1600, { prompt, kind ->
            Json.parseToJsonElement(prompt).jsonObject.getValue("records").jsonArray.forEach { seen += it.jsonObject.getValue("id").toString() }
            if (kind.endsWith("consistency")) consistency = true
            answer(verdict(prompt))
        }, {}).streamRound(emptyList(), emptyList()).toList()
        assertTrue(seen.size > 1)
        assertTrue(consistency)
        assertFalse(output.any { it is ProviderEvent.Failed })
        assertEquals(ProviderEvent.Completed, output.last())
    }

    @Test fun fullReceiptWinsOverLaterReplayExcerpt() {
        val evidence = GitHubReviewEvidence()
        receipt(evidence, text = "complete receipt")
        receipt(evidence, text = "short replay")
        assertTrue(evidence.snapshot().single().contains("complete receipt"))
        assertFalse(evidence.snapshot().single().contains("short replay"))
    }

    @Test fun reviewerCannotRequestTools() = runBlocking {
        val output = GitHubReviewedSession(answer(), GitHubReviewEvidence(), "task", 8192, { _, _ -> session(ProviderEvent.ToolCall("write", "github_commit", JsonObject(emptyMap())), ProviderEvent.Completed) }, {}).streamRound(emptyList(), emptyList()).toList()
        assertTrue(output.any { it is ProviderEvent.Failed })
        assertTrue(output.none { it is ProviderEvent.TextDelta || it is ProviderEvent.ToolCall })
    }
}
