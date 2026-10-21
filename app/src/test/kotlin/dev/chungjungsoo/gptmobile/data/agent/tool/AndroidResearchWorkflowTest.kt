package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.research.ResearchJournal
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidResearchWorkflowTest {
    @Test fun emptyContributorConsumesSharedAcceptedPlanWithoutRepeatingPlanning() = runBlocking {
        val saved = Journal()
        val shared = dev.chungjungsoo.gptmobile.data.research.ResearchPlanContract(
            listOf(dev.chungjungsoo.gptmobile.data.research.ResearchQuestion("Q1", "What is the documented request limit?")),
            listOf(dev.chungjungsoo.gptmobile.data.research.ResearchQuery("QRY1", "research device requests", listOf("Q1")))
        )
        val journal = object : ResearchJournal by saved {
            override fun scoped(authority: String, sharePublicEvidence: Boolean): ResearchJournal = this
            override suspend fun publicQuestionPlan(task: String, value: dev.chungjungsoo.gptmobile.data.research.ResearchPlanContract?) = shared
        }
        var planningCalls = 0
        AndroidResearchWorkflow(config(1), listOf(search(), reader()), { prompt, tokens ->
            if (prompt.startsWith("Plan public-web") || prompt.startsWith("Repair the planner")) planningCalls++
            model(prompt, tokens)
        }, journal).run("Research device request limits", "shared-plan")
        assertEquals(0, planningCalls)
        assertEquals(shared.questions, saved.snapshot!!.questionPlan)
    }

    @Test fun emptyGapAdviceWithNoReadableSourcesUsesABoundedFallbackQuery() = runBlocking {
        var searches = 0
        val delegate = search()
        val counted = delegate.copy(
            tool = object : AgentTool {
                override val definition = delegate.tool.definition
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    searches++
                    return if (searches == 1) AgentToolResult(callId, ToolResultContent.Json(JsonObject(emptyMap())), false) else delegate.tool.execute(callId, arguments)
                }
            }
        )
        val result = AndroidResearchWorkflow(config(1), listOf(counted, reader()), { prompt, tokens ->
            if (prompt.startsWith("Compare")) """{"missing":[],"queries":[]}""" else model(prompt, tokens)
        }).run("Research device request limits", "empty-gap")
        assertEquals(2, searches)
        assertTrue(result.pagesRead > 0)
    }

    @Test fun resumedPlanWithoutACursorDispatchesAnUndispatchedFallbackQuery() = runBlocking {
        val journal = Journal().apply {
            snapshot = ResearchSnapshot("Research device request limits", questions = listOf("What are the request limits?"))
        }
        var searches = 0
        val delegated = search()
        val counted = delegated.copy(
            tool = object : AgentTool {
                override val definition = delegated.tool.definition
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    searches++
                    return delegated.tool.execute(callId, arguments)
                }
            }
        )
        val result = AndroidResearchWorkflow(config(1), listOf(counted, reader()), ::model, journal).run("Research device request limits", "resume-planned")
        assertTrue(searches > 0)
        assertTrue(result.pagesRead > 0)
    }

    @Test fun `memory pressure pauses before source admission without running fixture tools`() = runBlocking {
        val journal = Journal()
        var calls = 0
        val workflow = AndroidResearchWorkflow(
            config(1),
            listOf(
                tool("web_search") { _, _ ->
                    calls++
                    error("Must pause before acquisition")
                },
                reader { _, _ ->
                    calls++
                    error("Must pause before reading")
                }
            ),
            generate = { _, _ -> """{"questions":[{"id":"Q1","text":"What is the device limit?","evidenceNeeded":"Exact passage"}],"queries":["device limit"]}""" },
            journal = journal,
            memoryAvailable = { false }
        )
        val result = workflow.run("Research https://example.org/device", "memory-pressure")
        assertEquals(LocalResearchOutcome.NO_USEFUL_OUTPUT, result.outcome)
        assertEquals("Paused for device resources", result.completionReason)
        assertEquals("Paused for device resources", journal.snapshot?.phase)
        assertEquals(0, result.pagesRead)
        assertEquals(0, calls)
    }

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
    private fun config(pages: Int = 2) = ModelDelegationSettings(maxInputCharacters = 12000, maxOutputTokens = 768, deepResearch = DeepResearchSettings(schemaVersion = 3, customSourceTarget = pages.takeIf { it > 0 }, effort = if (pages == 0) 0 else 50, maxPages = pages, maxRounds = 1, concurrency = 1))
    private fun tool(name: String, execute: suspend (String, JsonObject) -> AgentToolResult): ResolvedAgentTool {
        val definition = AgentToolDefinition(
            name,
            name,
            buildJsonObject {
                put("properties", buildJsonObject { for (key in listOf("query", "url", "includeLinks", "totalResults", "includeDomains", "excludeDomains")) put(key, buildJsonObject { put("type", "string") }) })
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

    @Test fun dnsFailuresRemainResumableInsteadOfExhaustingTheSource() = runBlocking {
        val journal = Journal()
        val offlineReader = reader { id, _ ->
            AgentToolResult(id, ToolResultContent.Text("Read URL failed: DNS lookup could not resolve the hostname."), true)
        }
        val first = AndroidResearchWorkflow(config(1), listOf(search(), offlineReader), ::model, journal)
            .run("Research https://example.org/research/1", "offline")
        assertEquals(0, first.pagesRead)
        assertEquals("Reader recovery needed", first.completionReason)
        assertTrue(journal.snapshot!!.sources.any { it.status == "Deferred" })
        val resumed = AndroidResearchWorkflow(config(1), listOf(search(), reader()), ::model, journal)
            .run("Research https://example.org/research/1", "online")
        assertTrue(resumed.pagesRead > 0)
        assertTrue(journal.snapshot!!.sources.any { it.readable })
    }

    @Test fun academicReadersReserveRemainingGoalAndReleaseFailedRead() = runBlocking {
        val journal = Journal()
        var reads = 0
        val papers = tool("deep_research_search_papers") { id, _ ->
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put(
                            "papers",
                            JsonArray(
                                (1..3).map { index ->
                                    buildJsonObject {
                                        put("paperId", "doi:10.1234/fixture$index")
                                        put("doi", "10.1234/fixture$index")
                                        put("canonicalUrl", "https://example.org/paper/$index")
                                        put("title", "Research device request limits $index")
                                    }
                                }
                            )
                        )
                    }
                ),
                false
            )
        }
        val paperReader = tool("deep_research_read_paper") { id, _ ->
            reads++
            val fail = reads == 1
            delay(10)
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("retrievalStatus", if (fail) "failed" else "read")
                        put("text", if (fail) "" else passage)
                    }
                ),
                fail
            )
        }
        val settings = config(1).let { it.copy(deepResearch = it.deepResearch.copy(academicSources = true, reviewEvidence = false, concurrency = 4)) }
        val result = AndroidResearchWorkflow(settings, listOf(papers, paperReader), ::model, journal)
            .run("Research request limits", "academic-goal")
        assertEquals(2, reads)
        assertEquals(1, result.pagesRead)
        assertEquals(1, journal.snapshot!!.sources.count { it.readable })
    }

    @Test fun localWorkerUsesSharedOrchestratorWhenDeepResearchIsEnabled() = runBlocking {
        val journal = Journal()
        val settings = config(1)
        assertTrue(settings.deepResearch.enabled)
        val result = LocalResearchWorkflow(settings, listOf(search(), reader()), ::model, journal = journal)
            .run("Research request limits", "local-worker")
        assertEquals(1, result.pagesRead)
        assertEquals("Supported", journal.snapshot!!.claims.single().verdict)
        assertTrue(journal.snapshot!!.toolEvents.any { it.reader == "read_url" && it.outcome == "SUCCESS" })
    }

    @Test fun readsSourcesBeforeReviewingAndRejectsInventedQuotes() = runBlocking {
        val journal = Journal()
        val result = AndroidResearchWorkflow(config(), listOf(search(), reader()), ::model, journal).run("Research request limits", "run")
        assertEquals(1, result.pagesRead)
        assertEquals("Supported", journal.snapshot!!.claims.single().verdict)
        assertFalse(journal.snapshot!!.complete)
        assertTrue(journal.snapshot!!.toolEvents.any { it.outcome == "SUCCESS" && it.sourceIds == listOf("S1") && it.latencyMs >= 0 })
        assertTrue(journal.snapshot!!.toolEvents.any { it.reader == "read_url" && it.outcome == "SUCCESS" && it.sourceIds == listOf("S1") })
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
        assertTrue(result.handoff.contains("\"stopOrigin\":\"UNKNOWN\""))
        assertFalse(result.handoff.contains("Stopped by user"))
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
        assertNull(journal.snapshot)
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

    @Test fun nativePlainTextDoesNotTriggerARemoteFallback() = runBlocking {
        val journal = Journal()
        var remoteReads = 0
        val native = reader { id, _ -> AgentToolResult(id, ToolResultContent.Text(passage), false) }
        val remote = reader { id, _ ->
            remoteReads++
            AgentToolResult(id, ToolResultContent.Text("Unavailable"), true)
        }.copy(connectionUid = "remote")
        val settings = config().copy(deepResearch = config().deepResearch.copy(allowRemoteReaders = true))
        val result = AndroidResearchWorkflow(settings, listOf(search(), native, remote), ::model, journal).run("Research", "plain")
        assertEquals(1, result.pagesRead)
        assertEquals(0, remoteReads)
    }

    @Test fun ordinaryReaderFailureCanUseAnEnabledFallback() = runBlocking {
        val journal = Journal()
        var remoteReads = 0
        val native = reader { id, _ -> AgentToolResult(id, ToolResultContent.Text("HTTP 503 temporarily unavailable"), true) }
        val remote = reader { id, _ ->
            remoteReads++
            AgentToolResult(id, ToolResultContent.Text(passage), false)
        }.copy(connectionUid = "remote", modelToolName = "remote_reader")
        val settings = config().copy(deepResearch = config().deepResearch.copy(allowRemoteReaders = true))
        val result = AndroidResearchWorkflow(settings, listOf(search(), native, remote), ::model, journal).run("Research", "transient")
        assertEquals(1, remoteReads)
        assertEquals(1, result.pagesRead)
    }

    @Test fun accessDeniedDoesNotEscalateToAnotherReader() = runBlocking {
        val journal = Journal()
        var remoteReads = 0
        val remote = reader { id, _ ->
            remoteReads++
            AgentToolResult(id, ToolResultContent.Text(passage), false)
        }.copy(connectionUid = "remote")
        val settings = config().copy(deepResearch = config().deepResearch.copy(allowRemoteReaders = true))
        AndroidResearchWorkflow(settings, listOf(search(), reader { id, _ -> AgentToolResult(id, ToolResultContent.Text("HTTP 403 access denied"), true) }, remote), ::model, journal).run("Research", "blocked")
        assertEquals(0, remoteReads)
        assertEquals("Blocked", journal.snapshot!!.sources.single().status)
    }

    @Test fun modelCannotExceedSixReviewedClaimsPerSource() = runBlocking {
        val journal = Journal()
        var reviews = 0
        AndroidResearchWorkflow(config(), listOf(search(), reader()), { prompt, tokens ->
            when {
                prompt.startsWith("Extract") -> buildJsonObject {
                    put(
                        "claims",
                        JsonArray(
                            (1..30).map { number ->
                                buildJsonObject {
                                    put("sourceId", "S1")
                                    put("text", "Claim $number")
                                    put("quote", passage)
                                }
                            }
                        )
                    )
                }.toString()
                prompt.startsWith("Review") -> {
                    reviews++
                    """{"verdict":"Supported"}"""
                }
                else -> model(prompt, tokens)
            }
        }, journal).run("Research", "claims")
        assertEquals(6, reviews)
        assertEquals(6, journal.snapshot!!.claims.size)
    }

    @Test fun parentCancellationPersistsCompletedSiblingPages() = runBlocking {
        val journal = Journal()
        val waiting = CompletableDeferred<Unit>()
        val read = reader { id, args ->
            if (args["url"]!!.jsonPrimitive.content.contains("two.org")) {
                waiting.complete(Unit)
                delay(5000)
            }
            AgentToolResult(id, ToolResultContent.Text(passage), false)
        }
        val settings = config().copy(deepResearch = config().deepResearch.copy(concurrency = 2))
        val job = launch { AndroidResearchWorkflow(settings, listOf(read), ::model, journal).run("Research https://one.org/research https://two.org/research", "cancel") }
        withTimeout(2500) { waiting.await() }
        job.cancelAndJoin()
        assertEquals("Interrupted", journal.snapshot!!.phase)
        assertFalse(journal.snapshot!!.complete)
        assertEquals(1, journal.snapshot!!.sources.count { it.readable })
    }

    @Test fun nonWebTasksCanDeclineResearchWithoutFollowUpModelCalls() = runBlocking {
        val journal = Journal()
        var calls = 0
        val generate: suspend (String, Int) -> String? = { _, _ ->
            calls++
            """{"queries":[],"questions":[],"noResearchNeeded":true}"""
        }
        val result = AndroidResearchWorkflow(config(), emptyList(), generate, journal).run("Explain a simple concept", "direct")
        assertEquals(LocalResearchOutcome.NO_RESEARCH_NEEDED, result.outcome)
        assertEquals(1, calls)
        val reused = AndroidResearchWorkflow(config(), emptyList(), generate, journal).run("Explain a simple concept", "direct")
        assertEquals(LocalResearchOutcome.NO_RESEARCH_NEEDED, reused.outcome)
        assertEquals(1, calls)
    }

    @Test fun invalidPlanDoesNotClaimResearchWasUnnecessary() = runBlocking {
        val result = AndroidResearchWorkflow(config(), emptyList(), { _, _ -> null }).run("Find current research", "invalid")
        assertEquals(LocalResearchOutcome.NO_USEFUL_OUTPUT, result.outcome)
    }

    @Test fun readerMetadataCannotBecomePageEvidence() = runBlocking {
        val journal = Journal()
        val result = AndroidResearchWorkflow(
            config(),
            listOf(
                search(),
                reader { id, _ ->
                    AgentToolResult(id, ToolResultContent.Text("""{"status":"ok","url":"https://example.org/research/1","metadata":"${"Not article text. ".repeat(20)}"}"""), false)
                }
            ),
            ::model,
            journal
        ).run("Research", "metadata")
        assertEquals(0, result.pagesRead)
        assertTrue(journal.snapshot!!.claims.isEmpty())
    }

    @Test fun combinedDomainPreferencesUseOneProviderFilterAndEnforceBothLocally() = runBlocking {
        val journal = Journal()
        val search = tool("web_search") { id, args ->
            assertEquals(JsonArray(listOf(JsonPrimitive("example.org"))), args["includeDomains"])
            assertFalse(args.containsKey("excludeDomains"))
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put(
                            "sources",
                            JsonArray(
                                listOf("example.org", "blocked.example.org", "other.org").map { host ->
                                    buildJsonObject {
                                        put("url", "https://$host/research")
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
        val settings = config().copy(deepResearch = config().deepResearch.copy(includeDomains = "example.org", excludeDomains = "blocked.example.org"))
        AndroidResearchWorkflow(settings, listOf(search, reader()), ::model, journal).run("Research", "domains")
        assertEquals(listOf("https://example.org/research"), journal.snapshot!!.sources.map { it.url })
        assertEquals(1, journal.snapshot!!.sources.count { it.readable })
    }

    @Test fun fastPaperStartsReadingBeforeSlowProviderFinishes() = runBlocking {
        val firstRead = CompletableDeferred<Unit>()
        val paper = buildJsonObject {
            put("paperId", "doi:10.1000/stream")
            put("canonicalUrl", "https://example.org/stream")
            put("title", "Documented device request limits")
        }
        val search = tool("deep_research_search_papers") { id, _ ->
            kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.research.AcademicDiscoveryObserver]!!.accept(listOf(paper))
            withTimeout(2000) { firstRead.await() }
            AgentToolResult(id, ToolResultContent.Json(buildJsonObject { put("papers", JsonArray(listOf(paper))) }), false)
        }
        val read = tool("deep_research_read_paper") { id, _ ->
            firstRead.complete(Unit)
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("text", passage)
                        put("retrievalStatus", "read")
                    }
                ),
                false
            )
        }
        val settings = config(1).let { it.copy(deepResearch = it.deepResearch.copy(academicSources = true, reviewEvidence = false)) }
        val result = withTimeout(5000) { AndroidResearchWorkflow(settings, listOf(search, read), generate = { _, _ -> null }, journal = Journal()).run("Documented device request limits", "stream") }
        assertEquals(1, result.pagesRead)
        assertEquals(LocalResearchOutcome.PARTIAL_USEFUL, result.outcome)
    }

    @Test fun siblingTaskReusesSourcesButPlansItsOwnQuestions() = runBlocking {
        val journal = Journal().apply {
            snapshot = ResearchSnapshot(
                "Earlier sibling task",
                complete = true,
                questions = listOf("Earlier question"),
                sources = listOf(
                    dev.chungjungsoo.gptmobile.data.research.ResearchSource(
                        "S9",
                        "https://example.org/retained",
                        "Retained source",
                        status = "Read",
                        passage = passage,
                        retrievedAt = System.currentTimeMillis()
                    )
                )
            )
        }
        var planned = 0
        AndroidResearchWorkflow(config(2), listOf(search(), reader()), { prompt, tokens ->
            if (prompt.startsWith("Plan")) {
                planned++
                assertTrue(prompt.contains("New sibling question"))
            }
            model(prompt, tokens)
        }, journal).run("New sibling question", "sibling")
        assertEquals(1, planned)
        assertEquals("New sibling question", journal.snapshot!!.task)
        assertFalse(journal.snapshot!!.questions.contains("Earlier question"))
        assertTrue(journal.snapshot!!.sources.any { it.id == "S9" && it.readable })
        assertEquals(2, journal.snapshot!!.sources.count { it.readable })
    }

    @Test fun restoredAcademicVerifierResumesOnlyVerificationAfterCollectionGoal() = runBlocking {
        val journal = Journal()
        val source = dev.chungjungsoo.gptmobile.data.research.ResearchSource("S1", "https://example.org/paper", "Synthetic device study", "Read", passage, paperId = "fixture-paper", versionId = "v1", retrievedAt = System.currentTimeMillis())
        val claim = dev.chungjungsoo.gptmobile.data.research.ResearchClaim("Two parallel requests", "S1", passage, questionId = "Q1")
        journal.snapshot = ResearchSnapshot(
            "Research request limits",
            questions = listOf("What is the request limit?"),
            questionPlan = listOf(dev.chungjungsoo.gptmobile.data.research.ResearchQuestion("Q1", "What is the request limit?")),
            planningStatus = "Validated",
            sources = listOf(source),
            claims = listOf(claim),
            targetSources = 1,
            extractedSourceVersions = setOf("S1:" + dev.chungjungsoo.gptmobile.data.research.researchHash(source.versionId + source.passage))
        )
        var available = false
        var verificationCalls = 0
        var modelCalls = 0
        val verifier = tool("deep_research_verify_evidence") { id, _ ->
            verificationCalls++
            AgentToolResult(id, ToolResultContent.Json(buildJsonObject { put("verdict", if (available) "quote_verified" else "unavailable") }), !available)
        }
        val generate: suspend (String, Int) -> String? = { prompt, _ ->
            check(prompt.startsWith("Review the claim")) { "Completed planning or extraction was repeated: $prompt" }
            modelCalls++
            """{"verdict":"Supported","relevance":"Relevant","questionId":"Q1"}"""
        }
        val first = AndroidResearchWorkflow(config(1), listOf(verifier), generate, journal).run("Research request limits", "unavailable")
        assertEquals(LocalResearchOutcome.PARTIAL_USEFUL, first.outcome)
        assertEquals(1, verificationCalls)
        assertEquals(0, modelCalls)
        available = true
        val resumed = AndroidResearchWorkflow(config(1), listOf(verifier), generate, journal).run("Research request limits", "restored")
        assertEquals(LocalResearchOutcome.SUCCESS, resumed.outcome)
        assertEquals(2, verificationCalls)
        assertEquals(1, modelCalls)
        assertEquals(1, journal.snapshot!!.claims.size)
        assertTrue(journal.snapshot!!.claims.single().exactQuoteVerified)
    }

    @Test fun disabledResearchUsesExplicitOriginAndNeverInfersUserStopOrRevocation() = runBlocking {
        for (origin in listOf("UNKNOWN", "USER_PRIMARY_ONLY", "PERMISSION_REVOKED")) {
            val journal = Journal()
            AndroidResearchWorkflow(config(), emptyList(), { _, _ -> error("Stopped work must not dispatch") }, journal, stillEnabled = { false }, cancellationOrigin = { origin }).run("Research fixture", "stopped-$origin")
            assertEquals(origin, journal.snapshot!!.cancellationOrigin)
            assertEquals(if (origin == "PERMISSION_REVOKED") "Permission revoked" else "Stopped", journal.snapshot!!.phase)
            assertFalse(journal.snapshot!!.complete)
        }
    }

    @Test fun directResearchActuallyPlansExtractsAndReviewsWithSourceLinkedLocalEvidence() = runBlocking {
        val journal = Journal()
        val stages = mutableListOf<dev.chungjungsoo.gptmobile.data.research.ResearchModelStage>()
        val local = dev.chungjungsoo.gptmobile.data.research.ResearchModelEndpoint("local", "model-A", "known-A", true, { true }) { stage, prompt, _ ->
            stages += stage
            val body = when (stage) {
                dev.chungjungsoo.gptmobile.data.research.ResearchModelStage.PLAN -> """{"schemaVersion":1,"questions":[{"id":"Q1","text":"What is the documented request limit?","evidenceNeeded":"Documented parallel request count"}],"queries":[{"id":"QRY1","text":"research device requests","questionIds":["Q1"]}],"noResearchNeeded":false}"""
                dev.chungjungsoo.gptmobile.data.research.ResearchModelStage.EXTRACT -> {
                    assertTrue(prompt.contains(passage))
                    buildJsonObject {
                        put(
                            "claims",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("sourceId", "S1")
                                        put("questionId", "Q1")
                                        put("text", "The device supports two parallel page requests.")
                                        put("quote", passage)
                                    }
                                )
                            )
                        )
                    }.toString()
                }
                dev.chungjungsoo.gptmobile.data.research.ResearchModelStage.REVIEW -> """{"verdict":"Supported","relevance":"Relevant","questionId":"Q1"}"""
                dev.chungjungsoo.gptmobile.data.research.ResearchModelStage.FIND_GAPS -> """{"missing":[],"queries":[]}"""
            }
            dev.chungjungsoo.gptmobile.data.research.ResearchModelReply(body, "known-A", "inv-${stage.name}")
        }
        val worker = dev.chungjungsoo.gptmobile.data.research.ResearchModelCoordinator(listOf(local))
        val result = AndroidResearchWorkflow(config(1), listOf(search(), reader()), { _, _ -> error("Null/legacy generator must not run") }, journal, modelCoordinator = worker).run("Research documented request limits", "typed")
        assertEquals(1, result.pagesRead)
        assertEquals(1, result.reviewedClaims)
        assertEquals(0, result.independentReviewClaims)
        assertFalse(result.assistPackets.isEmpty())
        assertTrue(result.assistPackets.single().toString().contains(passage))
        assertTrue(stages.containsAll(listOf(dev.chungjungsoo.gptmobile.data.research.ResearchModelStage.PLAN, dev.chungjungsoo.gptmobile.data.research.ResearchModelStage.EXTRACT, dev.chungjungsoo.gptmobile.data.research.ResearchModelStage.REVIEW)))
        assertEquals(3, journal.snapshot!!.modelEvents.count { it.outcome == "SUCCESS" })
    }

    @Test fun aMissingReviewerRetainsReadableEvidenceWithoutFalseVerifiedClaims() = runBlocking {
        val journal = Journal()
        val worker = dev.chungjungsoo.gptmobile.data.research.ResearchModelCoordinator(emptyList())
        val result = AndroidResearchWorkflow(config(1), listOf(search(), reader()), { _, _ -> error("No worker must not generate") }, journal, modelCoordinator = worker).run("Research documented request limits", "no-worker")
        assertEquals(1, result.pagesRead)
        assertEquals(0, result.reviewedClaims)
        assertTrue(journal.snapshot!!.modelEvents.isEmpty())
        assertTrue(result.handoff.contains("unreviewed", ignoreCase = true) || result.handoff.contains("unverified", ignoreCase = true))
    }
}
