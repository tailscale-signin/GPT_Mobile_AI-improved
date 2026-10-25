package dev.chungjungsoo.gptmobile.data.agent.tool

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.effectiveResearchPlan
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import dev.chungjungsoo.gptmobile.data.research.ResearchAdmission
import dev.chungjungsoo.gptmobile.data.research.ResearchModelCoordinator
import dev.chungjungsoo.gptmobile.data.research.ResearchModelEndpoint
import dev.chungjungsoo.gptmobile.data.research.ResearchModelReply
import dev.chungjungsoo.gptmobile.data.research.ResearchModelRequestBudget
import dev.chungjungsoo.gptmobile.data.research.ResearchModelStage
import dev.chungjungsoo.gptmobile.data.research.ResearchProviderDeferred
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ResearchDeferredResumeTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun tool(name: String, action: suspend (String, JsonObject) -> AgentToolResult): ResolvedAgentTool {
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(
                name,
                name,
                buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(if (name == "read_url") "url" else "query", buildJsonObject { put("type", "string") })
                        }
                    )
                }
            )
            override suspend fun execute(callId: String, arguments: JsonObject) = action(callId, arguments)
        }
        return ResolvedAgentTool(tool, null, "Native", name, name)
    }

    private fun packet(prompt: String): JsonObject {
        val envelope = Json.parseToJsonElement(prompt.substringAfterLast('\n')).jsonObject
        val evidence = envelope.getValue("untrusted_evidence").jsonPrimitive.content
        return Json.parseToJsonElement(if (evidence.startsWith("Questions:")) evidence.substringAfter('\n') else evidence).jsonObject
    }

    @Test fun processRestartAfterLongQuotaWaitResumesDensePacketWithFrozenPromptAndRetainedBudget() = runBlocking {
        val files = temporary.newFolder()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
            override fun getFilesDir(): File = files
        }
        val task = "Research documented limits and qualifications"
        val settings = ModelDelegationSettings(maxInputCharacters = 12000, maxOutputTokens = 768, deepResearch = DeepResearchSettings(schemaVersion = 3, customSourceTarget = 1, effort = 50, concurrency = 1))
        val firstQuote = "The documented device supports parallel requests."
        val secondQuote = "The second passage describes the relevant operating conditions."
        val thirdQuote = "The same passage records qualifications that must also be retained."
        val passage = (firstQuote + " Research documentation describes operating behavior.").padEnd(512, ' ') + secondQuote + " " + thirdQuote + " Additional research documentation describes the supported operation."
        var now = 1_700_000_000_000L
        var searches = 0
        var reads = 0
        var cooling = false
        var resumed = false
        var extractionCalls = 0
        var resumedFirstPrompt: String? = null
        val dispatchedStarts = mutableListOf<Int>()
        val tools = listOf(
            tool("web_search") { id, _ ->
                searches++
                AgentToolResult(
                    id,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put(
                                "sources",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("url", "https://example.org/documentation")
                                            put("title", "Research documentation")
                                        }
                                    )
                                )
                            )
                        }
                    ),
                    false
                )
            },
            tool("read_url") { id, _ ->
                reads++
                AgentToolResult(id, ToolResultContent.Json(buildJsonObject { put("content", passage) }), false)
            }
        )
        val limit = ProviderLimitDecision("AI_HORDE", "free", "small", now + 3 * 86_400_000L, "scope", "LOCAL_COOLDOWN_ACTIVE", true)
        fun coordinator() = ResearchModelCoordinator(
            listOf(
                ResearchModelEndpoint(
                    "free",
                    "small",
                    "small",
                    false,
                    { true },
                    admission = { if (cooling) ResearchAdmission.PROVIDER_COOLDOWN else ResearchAdmission.ADMITTED },
                    requestBudget = { cap -> ResearchModelRequestBudget(minOf(cap, if (resumed) 512 else 256), if (resumed) 4000 else 3000, true) },
                    providerLimit = { limit }
                ) { stage, prompt, _ ->
                    val result = when (stage) {
                        ResearchModelStage.PLAN -> """{"questions":[{"id":"Q1","text":"What are the documented limits and qualifications?"}],"queries":[{"id":"QRY1","text":"research operating limits","questionIds":["Q1"]}]}"""
                        ResearchModelStage.EXTRACT -> {
                            val packet = packet(prompt)
                            val start = packet.getValue("packetStart").jsonPrimitive.content.toInt()
                            dispatchedStarts += start
                            extractionCalls++
                            if (resumed && resumedFirstPrompt == null) resumedFirstPrompt = prompt
                            val quote = when (extractionCalls) {
                                1 -> firstQuote
                                2 -> secondQuote
                                else -> thirdQuote
                            }
                            if (extractionCalls == 2) cooling = true
                            buildJsonObject {
                                put("packetComplete", extractionCalls != 2)
                                put(
                                    "claims",
                                    JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("sourceId", packet.getValue("id"))
                                                put("questionId", "Q1")
                                                put("text", quote)
                                                put("quote", quote)
                                            }
                                        )
                                    )
                                )
                            }.toString()
                        }
                        ResearchModelStage.REVIEW -> buildJsonObject {
                            put("packetId", packet(prompt).getValue("packetId"))
                            put("verdict", "Supported")
                            put("relevance", "Relevant")
                            put("questionId", "Q1")
                            put("rationale", "The exact source passage states this qualification.")
                        }.toString()
                        ResearchModelStage.FIND_GAPS -> """{"missing":[],"queries":[]}"""
                    }
                    ResearchModelReply(result, "small", "inv-$stage-$extractionCalls")
                }
            ),
            healthScope = "7:42"
        )
        val firstStore = ResearchSessionStore(context)
        firstStore.acceptTask(7, "7:42", 42, task, listOf("primary"), "primary", "settings", "sources", true)
        val firstJournal = firstStore.journal("first", 7, true, "7:42", ownerScope = "primary")
        val failure = runCatching {
            AndroidResearchWorkflow(settings, tools, { _, _ -> error("Legacy generator must not run") }, firstJournal, now = { now }, monotonicMs = { 0 }, modelCoordinator = coordinator()).run(task, "first")
        }.exceptionOrNull()
        assertTrue("failure=$failure; searches=$searches; reads=$reads; packets=$dispatchedStarts; snapshot=${firstStore.sessions.value["first"]?.snapshot?.phase}", failure is ResearchProviderDeferred)
        val paused = firstStore.sessions.value.getValue("first").snapshot
        val cursor = requireNotNull(paused.deferredStage)
        assertEquals((failure as ResearchProviderDeferred).checkpoint, cursor.checkpoint)
        assertEquals("EXTRACT", cursor.checkpoint.stage)
        assertEquals(256, cursor.outputTokens)
        assertTrue(cursor.prompt.contains("alreadyExtracted"))
        assertTrue(cursor.prompt.contains(secondQuote))
        assertEquals(listOf(0, 512), dispatchedStarts)
        assertEquals(2, paused.claims.size)
        assertEquals(1, paused.extractedSourceVersions.size)
        assertEquals(1, searches)
        assertEquals(1, reads)
        assertEquals(cursor, ResearchSnapshot.parse(paused.json())?.deferredStage)
        val spent = requireNotNull(firstJournal.goalBudget(effectiveResearchPlan(settings.deepResearch)).ledger).snapshot()

        now += 3 * 86_400_000L
        cooling = false
        resumed = true
        val restarted = ResearchSessionStore(context)
        restarted.resumeGeneration(7, "7:42", "quota-resume")
        val resumeJournal = restarted.journal("resumed", 7, true, "7:42", ownerScope = "primary")
        val restoredBudget = requireNotNull(resumeJournal.goalBudget(effectiveResearchPlan(settings.deepResearch)).ledger)
        assertEquals(spent, restoredBudget.snapshot())
        val result = AndroidResearchWorkflow(settings, tools, { _, _ -> error("Legacy generator must not run") }, resumeJournal, now = { now }, monotonicMs = { 0 }, modelCoordinator = coordinator()).run(task, "resumed")
        val completed = restarted.sessions.value.getValue("resumed").snapshot
        assertEquals(LocalResearchOutcome.SUCCESS, result.outcome)
        assertEquals(cursor.prompt, resumedFirstPrompt)
        assertEquals(listOf(0, 512, 512), dispatchedStarts)
        assertEquals(1, searches)
        assertEquals(1, reads)
        assertEquals(passage, completed.sources.single().passage)
        assertEquals(3, completed.claims.size)
        assertNull(completed.deferredStage)
        assertTrue(completed.extractedSourceVersions.containsAll(paused.extractedSourceVersions))
        assertTrue(restoredBudget.snapshot().logical > spent.logical)
        assertEquals(spent.physical, restoredBudget.snapshot().physical)
        assertEquals(spent.activeMillis, restoredBudget.snapshot().activeMillis)

        val wrongHash = cursor.copy(prompt = cursor.prompt + "changed")
        assertNull(ResearchSnapshot.parse(paused.copy(deferredStage = wrongHash).json()))
        assertNotNull(ResearchSnapshot.parse(paused.json()))
        assertFalse(completed.notes.any { it.contains("expired sources") })
    }
}
