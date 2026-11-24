package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.github.GitHubTaskContinuity
import dev.chungjungsoo.gptmobile.data.github.GitHubVerifiedSession
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerEmptyAnswerTest {
    @Test
    fun ownedTextCannotBecomeAnEvidenceDraftAfterMorePlainText() = runBlocking {
        for (origin in listOf(OutputOrigin.APPLICATION_REPORT, OutputOrigin.RETAINED_CONTENT, OutputOrigin.SYSTEM_ERROR, OutputOrigin.PROGRESS_NOTICE)) {
            var rounds = 0
            var executions = 0
            val published = listOf(
                ProviderEvent.TextDelta("A model draft. ", OutputLineage(OutputOrigin.MODEL_TEXT)),
                ProviderEvent.TextDelta("The owned result. ", OutputLineage(origin)),
                ProviderEvent.TextDelta("Later unlabelled prose.")
            )
            val session = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    rounds++
                    published.forEach { emit(it) }
                    emit(ProviderEvent.Completed)
                }
            }
            val tool = unusedRepositoryRead { executions++ }

            val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(tool)).toList()

            assertEquals(origin.name, 1, rounds)
            assertEquals(0, executions)
            val provider = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
            val text = provider.filterIsInstance<ProviderEvent.TextDelta>()
            assertEquals(published.joinToString("") { it.text }, text.joinToString("") { it.text })
            assertEquals(OutputLineage(OutputOrigin.MODEL_TEXT), text.first().lineage)
            assertEquals(setOf(OutputLineage()), text.drop(1).map { it.lineage }.toSet())
            assertEquals(1, provider.count { it is ProviderEvent.Completed })
            assertEquals(ProviderEvent.Completed, provider.last())
            assertFalse(provider.any { it is ProviderEvent.Failed })
            assertFalse(events.any { it is AgentRunEvent.Notice && it.message.contains("no repository evidence") })
        }
    }

    @Test
    fun combinedFinalAnswerDoesNotAcquireRepositoryEvidenceWithToolsAvailable() = runBlocking {
        val text = "The final answer retains the saved contributions."
        val report = CombinedQualityReport(
            version = 6,
            answerDigest = dev.chungjungsoo.gptmobile.data.research.researchHash(text),
            revisionId = "github-atomic-final",
            complete = true,
            status = "COMPLETED_UNVERIFIED",
            deliveredScopeStatus = "COMPLETED",
            protocolComplete = true,
            finalAssessment = true,
            assessmentStage = "FINAL_DISPLAY",
            semanticCoverageVerified = true,
            wordCount = visibleWordCount(text),
            displayedCharacters = text.length
        )
        for (progressive in listOf(false, true)) {
            var rounds = 0
            var executions = 0
            val session = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    rounds++
                    if (progressive) emit(ProviderEvent.TextDelta(text.take(12)))
                    emit(ProviderEvent.CombinedAnswer(text, report))
                    emit(ProviderEvent.Completed)
                }
            }

            val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(unusedRepositoryRead { executions++ })).toList()
            val provider = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }

            assertEquals(1, rounds)
            assertEquals(0, executions)
            assertEquals(listOf(ProviderEvent.CombinedAnswer(text, report)), provider.filterIsInstance<ProviderEvent.CombinedAnswer>())
            assertEquals(1, provider.count { it is ProviderEvent.Completed })
            assertEquals(ProviderEvent.Completed, provider.last())
            assertFalse(provider.any { it is ProviderEvent.Failed })
            assertFalse(events.any { it is AgentRunEvent.Notice && it.message.contains("no repository evidence") })
        }
    }

    @Test
    fun sessionOwnedRecoveryDoesNotReceiveAnotherPlainTextEvidenceRound() = runBlocking {
        var rounds = 0
        var executions = 0
        val answer = ProviderEvent.TextDelta("The recovery owner completed the answer.")
        val session = object : AgentProviderSession {
            override val handlesToolResultRecovery = true
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                rounds++
                emit(answer)
                emit(ProviderEvent.Completed)
            }
        }

        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(unusedRepositoryRead { executions++ })).toList()

        assertEquals(1, rounds)
        assertEquals(0, executions)
        assertEquals(listOf(answer, ProviderEvent.Completed), events.filterIsInstance<AgentRunEvent.Provider>().map { it.event })
        assertFalse(events.any { it is AgentRunEvent.Notice && it.message.contains("no repository evidence") })
    }

    @Test
    fun verifiedDiagnosticPublishesOnceWithToolsAndOnlyRetainedReceipts() = runBlocking {
        val book = GitHubEvidenceBook("saved-task", "owner/repository", "main", setOf("github_context"), now = { 1000L })
        book.observe(
            ProviderEvent.ToolCall("previous-read", "github_context", buildJsonObject {}),
            AgentToolResult("previous-read", ToolResultContent.Text("{\"checks\":3}"), false)
        )
        val retained = book.snapshot()
        assertEquals(1, retained.first.size)
        var providerRounds = 0
        var verificationRequests = 0
        var continuationRequests = 0
        var publicationChecks = 0
        var rounds = 0
        var executions = 0
        val tool = unusedRepositoryRead { executions++ }
        val verified = GitHubVerifiedSession(
            delegate = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                    providerRounds++
                    error("A retained diagnostic cannot start provider generation")
                }
            },
            evidence = book,
            task = "Inspect the repository",
            inputLimit = { 8192 },
            openVerifier = {
                verificationRequests++
                error("A retained diagnostic cannot start claim extraction")
            },
            validatePublication = { publicationChecks++ },
            progress = {},
            authorizedTools = listOf(tool.definition),
            currentRequest = "Why did you stop?",
            openExecutionContinuation = {
                continuationRequests++
                error("A retained diagnostic cannot resume repository work")
            }
        )
        val session = object : AgentProviderSession by verified {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                rounds++
                assertEquals(listOf(tool.definition), tools)
                assertTrue(exchanges.isEmpty())
                emitAll(verified.streamRound(tools, exchanges))
            }
        }

        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(tool)).toList()
        val provider = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }

        assertEquals(1, rounds)
        assertEquals(1, publicationChecks)
        assertEquals(0, providerRounds)
        assertEquals(0, verificationRequests)
        assertEquals(0, continuationRequests)
        assertEquals(0, executions)
        assertEquals(retained, book.snapshot())
        assertEquals(
            listOf(
                ProviderEvent.TextDelta(GitHubTaskContinuity.explain(null, retained.first), OutputLineage(OutputOrigin.APPLICATION_REPORT)),
                ProviderEvent.Completed
            ),
            provider
        )
        assertFalse(events.any { it is AgentRunEvent.ToolStarted || it is AgentRunEvent.ToolFinished })
        assertFalse(events.any { it is AgentRunEvent.Notice && it.message.contains("no repository evidence") })
    }

    private fun unusedRepositoryRead(executed: () -> Unit) = object : AgentTool {
        override val definition = AgentToolDefinition("repository_read", "Read repository evidence", buildJsonObject {})
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            executed()
            error("Published output must not dispatch a repository read")
        }
    }

    @Test
    fun `github prose without receipts gets one bounded evidence acquisition round`() = runBlocking {
        var round = 0
        var executions = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                when (round++) {
                    0 -> emit(ProviderEvent.TextDelta("It appears complete."))
                    1 -> emit(ProviderEvent.ToolCall("read", "repository_read", buildJsonObject {}))
                    else -> {
                        assertEquals("read", exchanges.single().results.single().callId)
                        emit(ProviderEvent.TextDelta("The repository receipt confirms it."))
                    }
                }
                emit(ProviderEvent.Completed)
            }
        }
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("repository_read", "read", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Text("verified"), false)
            }
        }

        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(tool)).toList()

        assertEquals(3, round)
        assertEquals(1, executions)
        assertTrue(events.any { it is AgentRunEvent.Notice && it.message.contains("no repository evidence") })
        assertEquals(ProviderEvent.Completed, events.filterIsInstance<AgentRunEvent.Provider>().last().event)
    }

    @Test
    fun `github prose evidence correction is attempted only once`() = runBlocking {
        var rounds = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                rounds++
                emit(ProviderEvent.TextDelta("A prose-only response."))
                emit(ProviderEvent.Completed)
            }
        }
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("repository_read", "read", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject) =
                AgentToolResult(callId, ToolResultContent.Text("unused"), false)
        }

        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(tool)).toList()

        assertEquals(2, rounds)
        assertEquals(1, events.count { it is AgentRunEvent.Notice && it.message.contains("no repository evidence") })
        assertEquals(ProviderEvent.Completed, events.filterIsInstance<AgentRunEvent.Provider>().last().event)
    }

    @Test
    fun atomicCombinedAnswerCompletesExactlyOneRoundWithOrWithoutProvisionalText() = runBlocking {
        val text = "The complete answer retains the saved contributions."
        val report = CombinedQualityReport(
            version = 6,
            answerDigest = dev.chungjungsoo.gptmobile.data.research.researchHash(text),
            revisionId = "atomic-final",
            complete = true,
            status = "COMPLETED_UNVERIFIED",
            deliveredScopeStatus = "COMPLETED",
            protocolComplete = true,
            finalAssessment = true,
            assessmentStage = "FINAL_DISPLAY",
            semanticCoverageVerified = true,
            wordCount = visibleWordCount(text),
            displayedCharacters = text.length
        )
        for (singlePass in listOf(false, true)) {
            for (progressive in listOf(false, true)) {
                var rounds = 0
                val requested = CombinedPolicyResolver.accept(dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings(), listOf("source"), mapOf("source" to "model"), "Summarize the contributions", singlePass = singlePass)
                val execution = CombinedExecutionContext(requested, rootBudgetId = "atomic-$singlePass-$progressive")
                val session = object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        check(++rounds == 1) { "A delivered final bundle must never trigger empty-answer recovery." }
                        if (progressive) emit(ProviderEvent.TextDelta(text.take(12)))
                        emit(ProviderEvent.CombinedAnswer(text, report))
                        emit(ProviderEvent.Completed)
                    }
                }
                val events = AgentRunner(AgentRunLimits(combined = execution)).run(session, emptyList()).toList()
                val provider = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
                assertEquals(1, rounds)
                assertEquals(listOf(ProviderEvent.CombinedAnswer(text, report)), provider.filterIsInstance<ProviderEvent.CombinedAnswer>())
                assertEquals(ProviderEvent.Completed, provider.last())
                assertFalse(provider.any { it is ProviderEvent.Failed })
                assertFalse(events.any { it is AgentRunEvent.Notice && it.message.contains("no visible answer") })
            }
        }
    }

    @Test
    fun partialCombinedBundleKeepsItsTerminalFailureWithoutAnEmptyAnswerRetry() = runBlocking {
        var rounds = 0
        val text = "The retained answer has an unfinished requirement."
        val failure = ProviderEvent.Failed("COMBINED_REQUIREMENTS_UNMET", transportComplete = true)
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                check(++rounds == 1) { "The partial bundle must retain its reported outcome." }
                emit(ProviderEvent.CombinedAnswer(text, CombinedQualityReport(answerDigest = dev.chungjungsoo.gptmobile.data.research.researchHash(text), revisionId = "partial")))
                emit(failure)
            }
        }
        val events = AgentRunner().run(session, emptyList()).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals(1, rounds)
        assertEquals(failure, events.last())
        assertEquals(1, events.filterIsInstance<ProviderEvent.CombinedAnswer>().size)
        assertFalse(ProviderEvent.Completed in events)
    }

    @Test
    fun `empty completion finalizes once without repeating executed actions`() = runBlocking {
        var round = 0
        var executions = 0
        val sizes = mutableListOf<Int>()
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                sizes += tools.size
                when (round++) {
                    0 -> emit(ProviderEvent.ToolCall("action", "test", buildJsonObject {}))
                    1 -> emit(ProviderEvent.TextDelta("   "))
                    else -> {
                        assertEquals("action", exchanges.single().results.single().callId)
                        emit(ProviderEvent.TextDelta("Done."))
                    }
                }
                emit(ProviderEvent.Completed)
            }
        }
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("test", "test", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Text("done"), false)
            }
        }
        val events = mutableListOf<AgentRunEvent>()
        AgentRunner(AgentRunLimits()).run(session, listOf(tool)).collect { events += it }
        assertEquals(1, executions)
        assertEquals(listOf(1, 1, 0), sizes)
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    @Test
    fun `repeated empty completion fails terminally instead of looping or reporting success`() = runBlocking {
        var rounds = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                rounds++
                emit(ProviderEvent.Completed)
            }
        }
        val events = mutableListOf<AgentRunEvent>()
        AgentRunner(AgentRunLimits()).run(session, emptyList()).collect { events += it }
        assertEquals(2, rounds)
        assertEquals(1, events.count { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        assertTrue(events.none { it is AgentRunEvent.Provider && it.event is ProviderEvent.Completed })
    }
}
