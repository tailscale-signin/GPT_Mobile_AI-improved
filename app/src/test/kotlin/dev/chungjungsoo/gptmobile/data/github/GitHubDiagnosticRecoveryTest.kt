package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.context.ReplayAllowance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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

class GitHubDiagnosticRecoveryTest {
    private fun answer(text: String) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(ProviderEvent.TextDelta(text), ProviderEvent.Completed)
    }
    private fun failed(code: String) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(ProviderEvent.Failed(code, errorCode = code))
    }
    private fun evidence(count: Int = 1): GitHubReviewEvidence = GitHubReviewEvidence().also { data ->
        repeat(count) { index -> data.observe(ProviderEvent.ToolCall("call-$index", "github_read", JsonObject(emptyMap())), AgentToolResult("call-$index", ToolResultContent.Text("Evidence $index: " + "\\\"😀".repeat(900)), false)) }
    }
    private fun verdict(prompt: String): String = buildJsonObject {
        val input = Json.parseToJsonElement(prompt).jsonObject
        put("approved", true)
        put("reviewed_ids", JsonArray(input["records"]!!.jsonArray.map { it.jsonObject.getValue("id") }))
        put("issues", JsonArray(emptyList()))
        put("verified_claims", JsonArray(listOf(JsonPrimitive("Receipt checked."))))
    }.toString()

    @Test fun zeroReplayBudgetBlocksBeforeTheModelOrToolsCanDispatch() = runTest {
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = error("No evidence budget means no tool-dependent model request")
        }
        val tool = object : dev.chungjungsoo.gptmobile.data.agent.AgentTool {
            override val definition = AgentToolDefinition("github_read", "Read", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Must remain undispatched")
        }
        val result = dev.chungjungsoo.gptmobile.data.agent.AgentRunner(dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits(maxReplayTokens = 0)).run(source, listOf(tool)).toList()
        assertTrue(result.filterIsInstance<dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent.Provider>().any { (it.event as? ProviderEvent.Failed)?.errorCode == "REPLAY_EVIDENCE_REQUIRED" })
    }

    @Test fun outputAndReplayFitTogetherForTheReportedZeroBudgetCase() {
        for (context in listOf(8192, 32_768, 131_072, 1_048_576)) {
            val input = if (context == 8192) 4096 else 7620
            for (output in listOf(4096, 32_768)) {
                val budget = ReplayAllowance.allocate(context, input, output, 4096)
                assertTrue(budget.replay >= 512)
                assertTrue(input + budget.output + budget.replay + 1024 <= budget.ceiling)
                assertTrue(budget.output > 0)
            }
        }
    }

    @Test fun renderedEnvelopeMeasurementSplitsEscapedUnicodeEvidenceBeforeDispatch() = runTest {
        var passes = 0
        val reviewed = GitHubReviewedSession(answer("Private draft"), evidence(4), "task", 1800, { prompt, _ ->
            assertTrue(rendered(prompt) <= 1800)
            assertTrue(Json.parseToJsonElement(prompt).jsonObject["records"]!!.jsonArray.size <= 16)
            passes++
            answer(verdict(prompt))
        }, {}, measureInput = ::rendered).streamRound(emptyList(), emptyList()).toList()
        assertTrue(passes > 1)
        assertFalse(reviewed.toString(), reviewed.any { it is ProviderEvent.Failed })
    }
    private fun rendered(prompt: String): Int = dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate.estimate(
        buildJsonObject {
            put("system", "System and schema overhead ".repeat(8))
            put("content", prompt)
        }.toString()
    )

    @Test fun resumeAfterAReviewFailureUsesSavedDraftAndVerdictsWithoutPrimaryPlanning() = runTest {
        val records = linkedMapOf<String, JsonObject>()
        val store = GitHubReviewCheckpoint("task", { records[it] }, { key, value -> records[key] = value })
        var reviews = 0
        val firstPrompt = mutableListOf<String>()
        var broken = true
        suspend fun review(prompt: String, kind: String): AgentProviderSession {
            reviews++
            if (reviews == 2 && broken) return failed("TRANSIENT_NETWORK")
            firstPrompt += prompt
            return answer(verdict(prompt))
        }
        val first = GitHubReviewedSession(answer("Private draft"), evidence(3), "task", 1800, ::review, {}, checkpoint = store).streamRound(emptyList(), emptyList()).toList()
        assertTrue(first.any { it is ProviderEvent.Failed })
        val savedFirst = firstPrompt.first()
        val snapshots = records.filterKeys { it.startsWith("chunk:") }.size
        broken = false
        val primary = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = error("Review retry cannot restart primary planning or GitHub tools")
        }
        val retry = GitHubReviewedSession(primary, GitHubReviewEvidence(), "task", 1800, ::review, {}, checkpoint = store).streamRound(emptyList(), emptyList()).toList()
        assertFalse(retry.toString(), retry.any { it is ProviderEvent.Failed })
        assertEquals(1, firstPrompt.count { it == savedFirst })
        assertEquals(snapshots, records.filterKeys { it.startsWith("chunk:") }.size)
        assertEquals("Private draft", retry.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }

    @Test fun providerCapacityRejectionSplitsOnlyTheFailedEvidenceBatch() = runTest {
        var rejected = 0
        var accepted = 0
        val result = GitHubReviewedSession(answer("Draft"), evidence(), "task", 8000, { prompt, _ ->
            if (rendered(prompt) > 1400) {
                rejected++
                failed("CONTEXT_CAPACITY_EXCEEDED")
            } else {
                accepted++
                answer(verdict(prompt))
            }
        }, {}).streamRound(emptyList(), emptyList()).toList()
        assertTrue(rejected > 0 && accepted > 1)
        assertFalse(result.toString(), result.any { it is ProviderEvent.Failed })
        assertEquals("Draft", result.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }

    @Test fun reasoningOnlyLengthExhaustionRequiresACompleteVerdictBeforePublication() = runTest {
        var attempts = 0
        val result = GitHubReviewedSession(answer("Private draft"), evidence(), "task", 8000, { prompt, _ ->
            if (++attempts == 1) {
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> =
                        flowOf(ProviderEvent.Usage(outputTokens = 4096, reasoningTokens = 4096, finishReason = "length"), ProviderEvent.Completed)
                }
            } else {
                answer(verdict(prompt))
            }
        }, {}).streamRound(emptyList(), emptyList()).toList()
        assertTrue(attempts > 1)
        assertFalse(result.toString(), result.any { it is ProviderEvent.Failed })
        assertEquals("Private draft", result.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }

    @Test fun invalidCheckpointCannotRestartPlanningOrPublishAnAnswer() = runTest {
        val store = GitHubReviewCheckpoint("task", { error("corrupt checkpoint") }, { _, _ -> })
        val primary = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = error("Planning must remain stopped")
        }
        val result = GitHubReviewedSession(primary, GitHubReviewEvidence(), "task", 8000, { _, _ -> error("No valid evidence") }, {}, checkpoint = store).streamRound(emptyList(), emptyList()).toList()
        assertEquals("GITHUB_REVIEW_REQUIRED", result.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertTrue(result.none { it is ProviderEvent.TextDelta })
    }
}
