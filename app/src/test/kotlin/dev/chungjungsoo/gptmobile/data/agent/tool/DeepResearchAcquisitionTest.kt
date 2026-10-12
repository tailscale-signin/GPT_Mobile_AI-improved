package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.researchAcquisitionLimits
import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.research.ResearchJournal
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepResearchAcquisitionTest {
    @Test
    fun maximumAcquisitionReachesThreeHundredWithoutAModelOrLegacyToolCeilings() = runTest {
        val options = DeepResearchSettings(academicSources = true, reviewEvidence = false).forEffort(100)
        val parent = AgentRunLimits(maxToolCalls = 2, maxToolOutputBytes = 256 * 1024)
        val budget = ToolExecutionBudget(researchAcquisitionLimits(parent, options))
        var captured: ResearchSnapshot? = null
        val journal = object : ResearchJournal {
            override suspend fun load() = captured
            override suspend fun save(snapshot: ResearchSnapshot) {
                captured = snapshot
            }
            override fun stopRequested() = false
        }
        fun fixture(name: String, execute: suspend (String, JsonObject) -> AgentToolResult): ResolvedAgentTool {
            val implementation = object : AgentTool {
                override val definition = AgentToolDefinition(name, "Recorded fixture", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject) = execute(callId, arguments)
            }
            return ResolvedAgentTool(budget.bind(implementation), null, "Fixture", name, name)
        }
        val search = fixture("deep_research_search_papers") { id, args ->
            val page = args.getValue("cursor").jsonPrimitive.content.toInt()
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put(
                            "papers",
                            JsonArray(
                                (1..50).map { offset ->
                                    val index = (page - 1) * 50 + offset
                                    buildJsonObject {
                                        put("paperId", "doi:10.1234/fixture$index")
                                        put("doi", "10.1234/fixture$index")
                                        put("versionId", "version$index")
                                        put("canonicalUrl", "https://example.org/fixture/$index")
                                        put("title", "Research fixture $index")
                                        put("abstract", "Metadata lead ".repeat(100))
                                    }
                                }
                            )
                        )
                        if (page < 10) put("nextCursor", (page + 1).toString())
                    }
                ),
                false
            )
        }
        val reader = fixture("deep_research_read_paper") { id, args ->
            val body = "Research document ${args.getValue("identifier")} " + "Substantive captured methods and results. ".repeat(800)
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("text", body)
                        put("textHash", researchHash(body))
                        put("versionId", id)
                        put("retrievalStatus", "read")
                    }
                ),
                false
            )
        }
        val result = AndroidResearchWorkflow(
            ModelDelegationSettings(deepResearch = options, preparationTimeoutSeconds = 3600),
            listOf(search, reader),
            generate = { _, _ -> null },
            journal = journal
        ).run("Research fixture", "fixture")
        assertEquals(300, result.pagesRead)
        assertEquals(300, captured!!.sources.count { it.readable })
        assertEquals(300, captured!!.targetSources)
        assertTrue(captured!!.sources.all { it.passage.length <= 6000 })
        assertEquals(2, parent.maxToolCalls)
    }

    @Test
    fun acquisitionContinuesBeyondTenPagesAndNineHundredCandidates() = runTest {
        val options = DeepResearchSettings(academicSources = true, reviewEvidence = false, customSourceTarget = 1000, sourceSafetyCap = 150).forEffort(100)
        val parent = AgentRunLimits(maxToolCalls = 2, maxToolOutputBytes = 256 * 1024)
        val budget = ToolExecutionBudget(researchAcquisitionLimits(parent, options))
        var captured: ResearchSnapshot? = null
        val journal = object : ResearchJournal {
            override suspend fun load() = captured
            override suspend fun save(snapshot: ResearchSnapshot) {
                captured = snapshot
            }
            override fun stopRequested() = false
        }
        fun fixture(name: String, execute: suspend (String, JsonObject) -> AgentToolResult): ResolvedAgentTool {
            val implementation = object : AgentTool {
                override val definition = AgentToolDefinition(name, "Recorded fixture", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject) = execute(callId, arguments)
            }
            return ResolvedAgentTool(budget.bind(implementation), null, "Fixture", name, name)
        }
        val search = fixture("deep_research_search_papers") { id, args ->
            val page = args.getValue("cursor").jsonPrimitive.content.toInt()
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put(
                            "papers",
                            JsonArray(
                                (1..50).map { offset ->
                                    val index = (page - 1) * 50 + offset
                                    buildJsonObject {
                                        put("paperId", "doi:10.1234/fixture$index")
                                        put("doi", "10.1234/fixture$index")
                                        put("versionId", "version$index")
                                        put("canonicalUrl", "https://example.org/fixture/$index")
                                        put("title", "Research fixture $index")
                                        put("abstract", "Metadata lead ".repeat(100))
                                    }
                                }
                            )
                        )
                        if (page < 20) put("nextCursor", (page + 1).toString())
                    }
                ),
                false
            )
        }
        val reader = fixture("deep_research_read_paper") { id, args ->
            val body = "Research document ${args.getValue("identifier")} " + "Substantive captured methods and results. ".repeat(800)
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("text", body)
                        put("textHash", researchHash(body))
                        put("versionId", id)
                        put("retrievalStatus", "read")
                    }
                ),
                false
            )
        }
        val result = AndroidResearchWorkflow(
            ModelDelegationSettings(deepResearch = options, preparationTimeoutSeconds = 3600),
            listOf(search, reader),
            generate = { _, _ -> null },
            journal = journal
        ).run("Research fixture", "fixture")
        assertEquals(1000, result.pagesRead)
        assertEquals(1000, captured!!.sources.count { it.readable })
        assertEquals(1000, captured!!.targetSources)
        assertTrue(captured!!.sources.all { it.passage.length <= 6000 })
        assertEquals(2, parent.maxToolCalls)
    }
}
