package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.research.ResearchJournal
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidResearchWorkflowTest {
    private val passage = "The research device supports two parallel page requests. This documented limit applies to all ordinary page retrieval operations."
    private class Journal : ResearchJournal {
        var snapshot: ResearchSnapshot? = null
        var stop = false
        override suspend fun load() = snapshot
        override suspend fun save(snapshot: ResearchSnapshot) {
            this.snapshot = snapshot
        }
        override fun stopRequested() = stop
    }
    private fun config(pages: Int = 2) = ModelDelegationSettings(maxInputCharacters = 12000, maxOutputTokens = 768, deepResearch = DeepResearchSettings(maxPages = pages, maxRounds = 1, concurrency = 1))
    private fun tool(name: String, execute: suspend (String, JsonObject) -> AgentToolResult): ResolvedAgentTool {
        val definition = AgentToolDefinition(
            name,
            name,
            buildJsonObject {
                put("properties", buildJsonObject { for (key in listOf("query", "url", "includeLinks", "totalResults")) put(key, buildJsonObject { put("type", "string") }) })
            }
        )
        return ResolvedAgentTool(
            object : AgentTool {
                override val definition = definition
                override suspend fun execute(callId: String, arguments: JsonObject) = execute(callId, arguments)
            },
            null,
            "Native",
            name,
            name
        )
    }
    private fun search(count: Int = 1) = tool("web_search") { id, _ ->
        AgentToolResult(
            id,
            ToolResultContent.Json(
                buildJsonObject {
                    put(
                        "sources",
                        JsonArray(
                            (1..count).map {
                                buildJsonObject {
                                    put("url", "https://example.org/research/$it")
                                    put("title", "Research documentation $it")
                                }
                            }
                        )
                    )
                }
            ),
            false
        )
    }
    private fun reader(
        block: suspend (String, JsonObject) -> AgentToolResult = { id, _ ->
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("content", passage)
                        put("links", JsonArray(emptyList()))
                    }
                ),
                false
            )
        }
    ) = tool("read_url", block)
    private suspend fun model(prompt: String, tokens: Int): String = when {
        prompt.startsWith("Plan") -> """{"questions":["What is the documented request limit?"],"queries":["research device requests"]}"""
        prompt.startsWith("Extract") -> buildJsonObject {
            put(
                "claims",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("sourceId", "S1")
                            put("text", "Two parallel page requests are supported.")
                            put("quote", passage)
                        }
                    )
                )
            )
        }.toString()
        else -> """{"verdict":"Supported"}"""
    }

    @Test fun readsSourcesBeforeReviewingAndRejectsInventedQuotes() = runBlocking {
        val journal = Journal()
        val result = AndroidResearchWorkflow(config(), listOf(search(), reader()), ::model, journal).run("Research request limits", "run")
        assertEquals(1, result.pagesRead)
        assertEquals("Supported", journal.snapshot!!.claims.single().verdict)
        assertTrue(journal.snapshot!!.complete)
        val bad = Journal()
        AndroidResearchWorkflow(config(), listOf(search(), reader()), { p, t -> if (p.startsWith("Extract")) """{"claims":[{"sourceId":"S1","text":"Invented","quote":"This sentence never occurred on the source page."}]}""" else model(p, t) }, bad).run("Research", "run2")
        assertTrue(bad.snapshot!!.claims.isEmpty())
    }

    @Test fun pageBudgetAndSameDomainBoundaryHold() = runBlocking {
        val journal = Journal()
        var reads = 0
        AndroidResearchWorkflow(
            config(1),
            listOf(
                search(3),
                reader { id, _ ->
                    reads++
                    AgentToolResult(
                        id,
                        ToolResultContent.Json(
                            buildJsonObject {
                                put("content", passage)
                                put("links", JsonArray(listOf(JsonPrimitive("https://other.org/research"))))
                            }
                        ),
                        false
                    )
                }
            ),
            ::model,
            journal
        ).run("Research", "run")
        assertEquals(1, reads)
        assertEquals(1, journal.snapshot!!.attempts)
        assertFalse(journal.snapshot!!.sources.any { "other.org" in it.url })
    }

    @Test fun interruptedJobKeepsCompletedReadsAndDoesNotRepeatQueries() = runBlocking {
        val journal = Journal()
        var searches = 0
        val search = tool("web_search") { id, args ->
            searches++
            search().tool.execute(id, args)
        }
        AndroidResearchWorkflow(config(), listOf(search, reader()), ::model, journal).run("Research", "run")
        AndroidResearchWorkflow(config(), listOf(search, reader()), ::model, journal).run("Research", "run")
        assertEquals(1, searches)
        assertEquals(1, journal.snapshot!!.sources.count { it.readable })
    }

    @Test fun stopCancelsReadingButReturnsCompletedEvidence() = runBlocking {
        val journal = Journal()
        var reads = 0
        val read = reader { id, _ ->
            reads++
            if (reads > 1) {
                journal.stop = true
                delay(5000)
            }
            AgentToolResult(id, ToolResultContent.Json(buildJsonObject { put("content", passage) }), false)
        }
        val result = withTimeout(2500) { AndroidResearchWorkflow(config(), listOf(search(2), read), ::model, journal).run("Research", "run") }
        assertEquals(1, result.pagesRead)
        assertEquals("Stopped", journal.snapshot!!.phase)
        assertTrue(result.handoff.contains("Stopped by user"))
    }

    @Test fun failedPageNeverBecomesEvidenceAndSharedBudgetStopsWork() = runBlocking {
        val journal = Journal()
        var reads = 0
        AndroidResearchWorkflow(
            config(),
            listOf(
                search(2),
                reader { id, _ ->
                    reads++
                    AgentToolResult(id, ToolResultContent.Text("No budget remains."), true, toolCallBudgetExhausted = true)
                }
            ),
            ::model,
            journal
        ).run("Research", "run")
        assertEquals(1, reads)
        assertTrue(journal.snapshot!!.claims.isEmpty())
        assertEquals(0, journal.snapshot!!.sources.count { it.readable })
    }

    @Test fun gapSearchOnlyRunsWithinRoundBudget() = runBlocking {
        val journal = Journal()
        var searches = 0
        val search = tool("web_search") { id, _ ->
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
                                        put("url", "https://example.org/research/$searches")
                                        put("title", "Source")
                                    }
                                )
                            )
                        )
                    }
                ),
                false
            )
        }
        val settings = config(4).copy(deepResearch = config(4).deepResearch.copy(maxRounds = 2, reviewEvidence = false))
        AndroidResearchWorkflow(settings, listOf(search, reader()), { p, t ->
            if (p.startsWith("Compare")) """{"missing":["Find independent limitations"],"queries":["research device limitations"]}""" else model(p, t)
        }, journal).run("Research", "gap")
        assertEquals(2, searches)
        assertEquals(2, journal.snapshot!!.queries.size)
    }

    @Test fun snippetOnlyModeNeverReadsOrReviewsClaims() = runBlocking {
        val journal = Journal()
        var reads = 0
        AndroidResearchWorkflow(
            config(0),
            listOf(
                search(),
                reader { id, _ ->
                    reads++
                    AgentToolResult(id, ToolResultContent.Text(passage), false)
                }
            ),
            ::model,
            journal
        ).run("Research", "quick")
        assertEquals(0, reads)
        assertTrue(journal.snapshot!!.claims.isEmpty())
        assertEquals("Discovered", journal.snapshot!!.sources.single().status)
    }

    @Test fun connectedReadersRequireExplicitOptIn() = runBlocking {
        val journal = Journal()
        var remoteReads = 0
        val remote = reader { id, _ ->
            remoteReads++
            AgentToolResult(id, ToolResultContent.Text(passage), false)
        }.copy(connectionUid = "remote")
        AndroidResearchWorkflow(config(), listOf(search(), remote), ::model, journal).run("Research", "remote")
        assertEquals(0, remoteReads)
        assertEquals(0, journal.snapshot!!.sources.count { it.readable })
    }

    @Test fun parallelStopRetainsTheAlreadyCompletedPage() = runBlocking {
        val journal = Journal()
        val search = tool("web_search") { id, _ ->
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put(
                            "sources",
                            JsonArray(
                                listOf("one.org", "two.org").map {
                                    buildJsonObject {
                                        put("url", "https://$it/research")
                                        put("title", "Research")
                                    }
                                }
                            )
                        )
                    }
                ),
                false
            )
        }
        val read = reader { id, args ->
            if (args["url"]!!.jsonPrimitive.content.contains("two.org")) {
                delay(200)
                journal.stop = true
                delay(5000)
            }
            AgentToolResult(id, ToolResultContent.Json(buildJsonObject { put("content", passage) }), false)
        }
        val settings = config().copy(deepResearch = config().deepResearch.copy(concurrency = 2))
        val result = withTimeout(2500) { AndroidResearchWorkflow(settings, listOf(search, read), ::model, journal).run("Research", "parallel") }
        assertEquals(1, result.pagesRead)
    }
}
