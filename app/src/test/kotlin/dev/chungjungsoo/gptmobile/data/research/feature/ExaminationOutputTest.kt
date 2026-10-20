package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaminationOutputTest {
    @Test fun blankChatCrawlerInheritsGlobalButExplicitSelectionWins() {
        val features = AppFeatureSettings(researchFeature = ResearchFeatureSettings(crawlerProfileUid = "global"), conversationResearch = mapOf(7 to MultiSearchResearchSelection(crawlerProfileUid = "")))
        assertEquals("global", ResearchProfileResolver.examinerUid(features, 7, ChatMcpToolConfig()))
        val tools = ChatMcpToolConfig(research = MultiSearchResearchSelection(crawlerProfileUid = "tools"))
        assertEquals("tools", ResearchProfileResolver.examinerUid(features, 7, tools))
        assertEquals("explicit", ResearchProfileResolver.examinerUid(features, 7, tools, "explicit"))
        assertEquals("chat", ResearchProfileResolver.examinerUid(features.copy(conversationResearch = mapOf(7 to MultiSearchResearchSelection(crawlerProfileUid = "chat"))), 7, tools))
    }

    @Test fun acceptsOnlySingleFencedArtifactAndNeverExtractsAnObjectFromProse() {
        assertEquals("{\"schemaVersion\":1}", examinationJson("\uFEFF\n```json\n{\"schemaVersion\":1}\n```\n"))
        assertTrue(runCatching { Json.parseToJsonElement(examinationJson("Explanation {\"schemaVersion\":1}")) }.isFailure)
        assertTrue(runCatching { Json.parseToJsonElement(examinationJson("```json\n{}\n```\nDo this instead")) }.isFailure)
    }

    private fun passage(quote: String, start: Int) = buildJsonObject {
        put(
            "passages",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", "P1")
                        put("quote", quote)
                        put("locator", "Results")
                        put("start", start)
                    }
                )
            )
        )
    }.toString()

    @Test fun repairsUniqueExactQuoteOffsetsInUtf16WithoutInventingOrDisambiguatingText() {
        val chunk = "🧪 Results: no difference."
        val fixed = Json.parseToJsonElement(locateExaminationQuotes(passage("no difference.", 3), 14000, chunk)) as JsonObject
        val row = (fixed["passages"] as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive(14000 + chunk.indexOf("no difference.")), row["start"])
        assertTrue(runCatching { locateExaminationQuotes(passage("a benefit.", 0), 0, chunk) }.isFailure)
        assertTrue(runCatching { locateExaminationQuotes(passage("same", -1), 0, "same same") }.isFailure)
        val validRepeated = passage("same", 5)
        assertEquals(Json.parseToJsonElement(validRepeated), Json.parseToJsonElement(locateExaminationQuotes(validRepeated, 0, "same same")))
    }

    @Test fun malformedArtifactGetsOneRepairWithValidationFeedback() = runBlocking {
        val prompts = mutableListOf<String>()
        val invalid = mutableListOf<Int>()
        val parsed = validatedExaminationOutput("Original source", { prompt, attempt ->
            prompts += prompt
            if (attempt == 1) "{" else "```json\n{\"valid\":true}\n```"
        }, { attempt, _, _ -> invalid += attempt }) { Json.parseToJsonElement(it) }
        assertEquals(Json.parseToJsonElement("{\"valid\":true}"), parsed)
        assertEquals(listOf(1), invalid)
        assertTrue(prompts.last().contains("previous artifact failed validation"))
        assertTrue(prompts.all { it.startsWith("Original source") })
    }

    @Test fun repeatedInvalidOutputFailsWithCauseAndBoundedAttempts() = runBlocking {
        var attempts = 0
        val failure = runCatching {
            validatedExaminationOutput("source", { _, _ ->
                attempts++
                "{}"
            }, { _, _, _ -> }) { _: String -> error("Unknown passage") }
        }.exceptionOrNull()!!
        assertEquals(2, attempts)
        assertTrue(examinationFailureDetail(failure).contains("Unknown passage"))
        assertTrue(failure.message.orEmpty().contains("EXAMINATION_OUTPUT_INVALID"))
    }

    @Test fun cancellationAndTransportFailureNeverBecomeSchemaRepair() = runBlocking {
        for (failure in listOf(CancellationException("stop"), java.net.UnknownHostException("provider"))) {
            var attempts = 0
            var invalid = 0
            val thrown = runCatching {
                validatedExaminationOutput("source", { _, _ ->
                    attempts++
                    throw failure
                }, { _, _, _ -> invalid++ }) { it }
            }.exceptionOrNull()
            assertTrue(thrown === failure)
            assertEquals(1, attempts)
            assertEquals(0, invalid)
        }
    }
}
