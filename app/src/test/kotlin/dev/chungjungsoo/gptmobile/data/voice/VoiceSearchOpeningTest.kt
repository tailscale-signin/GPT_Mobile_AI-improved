package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSearchOpeningTest {
    private val event = ToolEvent("e", "run", 1, "c", null, null, "web_search", "web_search", """{"query":"Toronto weather extra private words"}""", null, null, ToolEventStatus.RUNNING, startedAt = 1)

    private val run = AgentRun("run", 1, 2, 3, "profile", "provider", "model", status = AgentRunStatus.RUNNING)

    @Test fun aConfirmedLookupUsesTheSpokenLanguageAndNeverReadsSearchArguments() {
        val prompts = mapOf("fr-CA" to "Quel temps fera-t-il demain ?", "es" to "¿Qué tiempo hará mañana?", "it" to "Che tempo farà?", "pt-BR" to "Como estará o tempo?", "hi" to "कल मौसम कैसा होगा?", "zh" to "明天天气如何？", "ja" to "明日の天気は？")
        val cues = prompts.map { (lang, question) ->
            val plan = requireNotNull(VoiceTurnOpenings.plan(question, run, lang, listOf(event)))
            assertTrue(plan.cue.lookup)
            assertEquals(VoiceMessageIdentity(3, "run"), plan.identity)
            assertFalse(plan.cue.text.contains("Toronto"))
            plan.cue.text
        }
        assertEquals(prompts.size, cues.distinct().size)
        val inactive = listOf(event.copy(runId = "another"), event.copy(status = ToolEventStatus.PENDING), event.copy(completedAt = 5), event.copy(connectionUidSnapshot = "untrusted"), event.copy(modelToolName = "something_else"))
        for (other in inactive) assertFalse(requireNotNull(VoiceTurnOpenings.plan("Check this for me.", run, "en-US", listOf(other))).cue.lookup)
        for (operation in listOf("read_url", "read_file_slice", "github_search", "github_read", "deep_research_search_papers")) {
            val read = event.copy(toolName = operation, modelToolName = operation)
            assertTrue(requireNotNull(VoiceTurnOpenings.plan("Check this for me.", run, "en-US", listOf(read))).cue.lookup)
        }
        val write = event.copy(toolName = "github_commit", modelToolName = "github_commit")
        assertFalse(requireNotNull(VoiceTurnOpenings.plan("Check this for me.", run, "en-US", listOf(write))).cue.lookup)
    }

    @Test fun quickLiteralAndInactiveTurnsStaySilentAndLongerTasksGetRelevantCues() {
        for (prompt in listOf("Hello!", "Merci.", "Answer with one word only.", "Réponds uniquement par oui.", "逐字翻译这段话。")) {
            assertNull(VoiceTurnOpenings.plan(prompt, run, "en-US", listOf(event)))
        }
        assertNull(VoiceTurnOpenings.plan("Explain tides.", run.copy(status = AgentRunStatus.QUEUED), "en-US", emptyList()))
        val compared = requireNotNull(VoiceTurnOpenings.plan("Compare these engines.", run, "en-US", emptyList()))
        assertEquals("Let me compare those.", compared.cue.text)
        assertEquals("I can find that for you.", VoiceTurnOpenings.plan("Find the manuals.", run, "en-US", emptyList())?.cue?.text)
        assertEquals("Sure, no problem.", VoiceTurnOpenings.plan("Write a short outline.", run, "en-US", emptyList())?.cue?.text)
        val lookup = requireNotNull(VoiceTurnOpenings.plan("Check the forecast.", run, "en-US", listOf(event)))
        assertTrue(compared.delayMs > lookup.delayMs)
        assertEquals("Je vais vous expliquer.", VoiceTurnOpenings.plan("Explique les marées.", run, "fr", emptyList())?.cue?.text)
    }

    @Test fun onlyTheQuestionSuppliesTheSpokenTopic() {
        assertEquals("I’m checking the weather in Toronto.", VoiceSearchOpening.line("What's the weather in Toronto?", "run", listOf(event)))
        for (prompt in listOf("What's the weather in Ottawa?", "What is it?", "What's my password?", "What is the weather? Reply with one word.", "Say exactly: What's the weather in Toronto?")) {
            assertNull(prompt, VoiceSearchOpening.line(prompt, "run", listOf(event)))
        }
    }

    @Test fun queuedCompletedForeignAndUnrelatedSearchesStaySilent() {
        val events = listOf(
            event.copy(status = ToolEventStatus.PENDING),
            event.copy(status = ToolEventStatus.COMPLETED),
            event.copy(runId = "other"),
            event.copy(connectionUidSnapshot = "external"),
            event.copy(toolName = "read_url"),
            event.copy(arguments = "malformed"),
            event.copy(startedAt = null),
            event.copy(isError = true)
        )
        events.forEach { assertNull(VoiceSearchOpening.line("Check the weather in Toronto.", "run", listOf(it))) }
    }
}
