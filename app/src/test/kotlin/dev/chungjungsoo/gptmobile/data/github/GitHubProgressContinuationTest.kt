package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

class GitHubProgressContinuationTest {
    @Test fun cachedInstructionSearchAt504RemainingDispatchesThePinnedReadBeforePublishing() = runBlocking {
        checkCachedSearchContinuation(issuedRead = true, task = "Inspect the repository instructions and relevant tests, and implement its complete fix.\nAcceptance criteria\n- Fix all issues")
    }

    @Test fun staleSearchInVerificationDispatchesItsPinnedReadOrContinuesIndependentWork() = runBlocking {
        for (issued in listOf(true, false)) checkCachedSearchContinuation(issued, "Fix the repository source and run relevant tests")
    }

    private suspend fun checkCachedSearchContinuation(issuedRead: Boolean, task: String) {
        val head = "a".repeat(40)
        val search = GitHubOperationTool.definition("github_search", "github_search__selected")
        val reader = GitHubOperationTool.definition("github_read", "github_read__selected")
        val tools = listOf(search, reader)
        val searchArgs = buildJsonObject {
            put("owner", "owner")
            put("repo", "repo")
            put("mode", "paths")
            put("query", "CONTRIBUTING")
        }
        val firstCall = ProviderEvent.ToolCall("find-instructions", search.name, searchArgs)
        val hit = buildJsonObject {
            put("path", "CONTRIBUTING.md")
            put("sha", "b".repeat(40))
        }
        val page = buildJsonObject {
            put("commit_sha", head)
            put("items", JsonArray(listOf(hit)))
        }
        val recipe = requireNotNull(GitHubSearchSourceRead.recipe(reader.name, searchArgs, page))
        val data = if (issuedRead) JsonObject(page + ("source_read_call" to recipe)) else page
        val firstResult = AgentToolResult(firstCall.callId, ToolResultContent.Json(data), false, sharedResult = true)
        val saved = mutableMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("cached-search", "owner/repo", "main", tools.map { it.name }.toSet(), load = { saved[it] }, save = { id, row -> saved[id] = row })
        book.retainTaskContract(task, 1, tools, 512)
        fun session(events: List<ProviderEvent>) = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(*(events + ProviderEvent.Completed).toTypedArray())
        }
        var rounds = 0
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = if (rounds++ == 0) {
                flowOf(firstCall, ProviderEvent.Completed)
            } else {
                flowOf(ProviderEvent.TextDelta("The tool-call budget is exhausted."), ProviderEvent.Completed)
            }
        }
        val receiptId = "ev_" + GitHubEvidenceBook.digest("cached-search|${firstCall.callId}").take(24)
        val independent = ProviderEvent.ToolCall("independent", reader.name, recipe.getValue("arguments").jsonObject)
        val verified = GitHubVerifiedSession(
            delegate, book, task, { 32_768 }, {
                session(listOf(ProviderEvent.TextDelta("""{"claims":[{"kind":"fact","receipt_id":"$receiptId","pointer":"/items/0/path","value":"CONTRIBUTING.md"}]}""")))
            }, {}, {}, authorizedTools = tools, executionCallLimit = 512, remainingToolCalls = { 504 },
            openExecutionContinuation = { prompt ->
                assertTrue(prompt.contains(receiptId))
                session(listOf(independent))
            }
        )
        assertEquals(firstCall, verified.streamRound(tools, emptyList()).toList().filterIsInstance<ProviderEvent.ToolCall>().single())
        val events = verified.streamRound(tools, listOf(AgentToolExchange(listOf(firstCall), listOf(firstResult)))).toList()
        val next = events.filterIsInstance<ProviderEvent.ToolCall>().single()
        assertEquals(reader.name, next.name)
        assertEquals(recipe["arguments"], next.arguments)
        assertEquals(!issuedRead, next.callId == independent.callId)
        assertTrue(events.none { it is ProviderEvent.TextDelta || it is ProviderEvent.Failed })
        assertEquals(1, book.snapshot().first.size)
    }

    @Test fun runtimeLimitationsAndCapabilityQuestionsAreBoundToTheExecutionLedger() {
        val task = "Fix the repository implementation and run tests"
        val answer = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"claims":[{"kind":"not_measured","subject":"The tool-call budget was exhausted"}],"questions":["Should the changes be performed in an environment with write, test-execution, branch and pull-request capabilities?"]}"""
        ).jsonObject
        val (text, report) = GitHubEvidencePolicy.render(answer, emptyMap(), 1000, 0, task)
        assertFalse(text.contains("budget was exhausted") || text.contains("environment with write"))
        assertEquals(JsonPrimitive(false), report["completion_verified"])
        val read = GitHubOperationTool.definition("github_read", "github_read__selected")
        val write = GitHubOperationTool.definition("github_stage_patch", "github_stage_patch__selected")
        val resources = GitHubAnswerPresentation.resources(
            buildJsonObject {
                put("remaining_calls", 504)
                put("effective_call_limit", 512)
            },
            listOf(read, write),
            listOf(read)
        )
        val notice = GitHubAnswerPresentation.resourceNotice(resources)
        assertTrue(notice.contains("504 of 512"))
        assertTrue(notice.contains("this round: github_read"))
        assertTrue(notice.contains("authorized catalog: github_stage_patch"))
    }

    @Test fun progressingExecutionPassesThreeCorrectionsAndStopsAtItsOriginalFiniteAllowance() = runBlocking {
        val originalAllowance = 8
        val sourceSha = "a".repeat(40)
        val directory = Files.createTempDirectory("github-progressing-work").toFile()
        val paths = (0 until originalAllowance).map { "source-$it.kt" }
        paths.forEachIndexed { index, path -> directory.resolve(path).writeText("val observed$index = $index\n") }
        var dispatches = 0
        var corrections = 0
        var providerRounds = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "Pinned local repository transport fixture", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                assertEquals(JsonPrimitive("read_ranges"), arguments["action"])
                assertEquals(JsonPrimitive(sourceSha), arguments["commit_sha"])
                val range = arguments.getValue("ranges").jsonArray.single().jsonObject
                val path = (range.getValue("path") as JsonPrimitive).content
                dispatches++
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("commit_sha", sourceSha)
                            put(
                                "items",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("path", path)
                                            put("content", directory.resolve(path).readText())
                                            put("start_line", 1)
                                            put("end_line", 1)
                                            put("range_complete", true)
                                        }
                                    )
                                )
                            )
                        }
                    ),
                    false
                )
            }
        }
        val reader = GitHubOperationTool("github_read", backend, "progressing-work")
        fun read(index: Int) = ProviderEvent.ToolCall(
            "source-$index",
            reader.definition.name,
            buildJsonObject {
                put("commit_sha", sourceSha)
                put(
                    "ranges",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("path", paths[index])
                                put("start_line", 1)
                                put("end_line", 1)
                            }
                        )
                    )
                )
            }
        )
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = if (providerRounds++ == 0) {
                flowOf(read(0), ProviderEvent.Completed)
            } else {
                flowOf(ProviderEvent.TextDelta("Another source is inspected; the implementation and tests still remain."), ProviderEvent.Completed)
            }
        }
        val saved = linkedMapOf<String, JsonObject>()
        val currentVerdictId = "verification_current_" + GitHubEvidenceBook.digest("progressing-work").take(24)
        val book = GitHubEvidenceBook(
            "progressing-work",
            "owner/repo",
            "feature",
            setOf(reader.definition.name),
            load = { saved[it] },
            save = { id, value -> saved[id] = value },
            latestVerdict = { saved[currentVerdictId] }
        )
        val task = "Fix the repository implementation and test the change"
        book.retainTaskContract(task, 1, listOf(reader.definition), originalAllowance)
        val session = GitHubVerifiedSession(
            provider, book, task, { 16_384 }, {
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) =
                        flowOf(ProviderEvent.TextDelta("""{"claims":[{"kind":"not_measured","subject":"implementation and tests"}]}"""), ProviderEvent.Completed)
                }
            }, {}, {},
            authorizedTools = listOf(reader.definition),
            executionCallLimit = originalAllowance,
            remainingToolCalls = { originalAllowance - dispatches },
            openExecutionContinuation = { prompt ->
                assertTrue(prompt.contains("Resume only unverified criteria"))
                corrections++
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(read(corrections), ProviderEvent.Completed)
                }
            }
        )
        try {
            val events = AgentRunner(
                AgentRunLimits(maxToolCalls = originalAllowance, maxRounds = 16, maxReplayTokens = 16_000, githubTask = true, githubTaskRequest = task, githubMode = true),
                onToolResult = { call, result -> book.observe(call, result) }
            ).run(session, listOf(reader)).toList()
            val finished = events.filterIsInstance<AgentRunEvent.ToolFinished>()
            assertEquals("events=$events checkpoint=${book.diagnostic()}", originalAllowance - 1, dispatches)
            assertEquals(originalAllowance - 2, corrections)
            assertEquals(dispatches, finished.size)
            assertTrue(finished.all { !it.result.isError && it.result.dispatched })
            assertEquals(dispatches, finished.map { it.call.arguments }.distinct().size)
            assertEquals(1, originalAllowance - dispatches)
            assertEquals(JsonPrimitive(originalAllowance), book.acceptedPromptContract()?.get("effective_tool_call_limit"))
            val failures = events.filterIsInstance<AgentRunEvent.Provider>().mapNotNull { it.event as? ProviderEvent.Failed }
            assertEquals(1, failures.size)
            assertEquals("PARTIAL", failures.single().taskOutcome)
            assertEquals(dispatches, book.snapshot().first.size)
            assertEquals(JsonPrimitive(false), book.diagnostic()?.get("completion_verified"))
            assertEquals(saved.getValue(currentVerdictId), book.diagnostic())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun receipt(path: String = "app.kt", success: Boolean = true) = buildJsonObject {
        put("tool", "github_read")
        put("arguments", buildJsonObject { put("path", path) })
        put("success", success)
        put("dispatched", true)
        put("trusted", true)
    }

    private fun report(recovery: String = "RESUME_UNFINISHED_WORK") = buildJsonObject {
        put("completion_verified", false)
        put("task_outcome", "PARTIAL")
        put(
            "execution_ledger",
            buildJsonObject {
                put("recovery", recovery)
                put("required_criteria", 3)
                put("verified_criteria", 1)
                put("remaining_criteria", 2)
            }
        )
    }

    @Test fun onlyChangedSuccessfulOperationsCountAsProgress() {
        val original = receipt()
        val progress = GitHubExecutionContinuation.progressKeys(mapOf("one" to original))
        assertEquals(progress, GitHubExecutionContinuation.progressKeys(mapOf("other-id" to original)))
        assertEquals(2, GitHubExecutionContinuation.progressKeys(mapOf("one" to original, "two" to receipt("other.kt"))).size)
        assertTrue(GitHubExecutionContinuation.progressKeys(mapOf("failed" to receipt(success = false))).isEmpty())
        assertTrue(GitHubExecutionContinuation.progressKeys(mapOf("cache" to JsonObject(original + ("shared_result" to JsonPrimitive(true))))).isEmpty())
    }

    @Test fun budgetApprovalAndUncertainOutcomesCannotAutomaticallyContinue() {
        val receipts = mapOf("one" to receipt())
        assertTrue(GitHubExecutionContinuation.canResume(report(), receipts, 10, 1000))
        assertFalse("One remaining call is reserved for final verification", GitHubExecutionContinuation.canResume(report(), receipts, 1, 1000))
        assertFalse(GitHubExecutionContinuation.canResume(report(), receipts, 0, 1000))
        assertFalse(GitHubExecutionContinuation.canResume(report(), receipts, 10, 0))
        for (recovery in listOf("WAITING_FOR_APPROVAL", "USER_INPUT_REQUIRED", "RECONCILE_REMOTE_OUTCOME", "RESTORE_AUTHORIZED_CONFIGURATION", "RESUME_VERIFICATION")) {
            assertFalse(recovery, GitHubExecutionContinuation.canResume(report(recovery), receipts, 10, 1000))
        }
        val freshVerification = JsonObject(report("RESUME_VERIFICATION") + mapOf("failure_code" to JsonPrimitive("GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION"), "evidence_retention_complete" to JsonPrimitive(true)))
        assertTrue(GitHubEffectiveRecovery.resolve(freshVerification).verificationOnly)
        assertFalse(
            "A fresh evidence delta cannot turn verifier-only recovery into automatic execution",
            GitHubExecutionContinuation.canResume(
                freshVerification,
                receipts,
                10,
                1000,
                allowVerificationResume = true
            )
        )
        assertFalse(
            "Provider and budget failures are not retried as missing evidence",
            GitHubExecutionContinuation.canResume(
                JsonObject(report("RESUME_VERIFICATION") + ("failure_code" to JsonPrimitive("GITHUB_EXTRACTION_OUTPUT_LIMIT"))),
                receipts,
                10,
                1000,
                allowVerificationResume = true
            )
        )
        assertFalse(GitHubExecutionContinuation.canResume(report(), receipts + ("failed" to receipt(success = false)), 10, 1000))
        assertFalse(GitHubExecutionContinuation.canResume(JsonObject(report() + ("completion_verified" to JsonPrimitive(true))), receipts, 10, 1000))
        assertTrue(GitHubExecutionContinuation.incompleteMessage(report(), receipts).contains("1 of 3 acceptance criteria"))
    }

    @Test fun progressAfterFirstReadResumesUnfinishedWorkAndDoesNotPublishPartialError() = runBlocking {
        val tool = AgentToolDefinition("github_read", "Read repository source", JsonObject(emptyMap()))
        val first = ProviderEvent.ToolCall("first", tool.name, buildJsonObject { put("path", "app.kt") })
        val next = ProviderEvent.ToolCall("next", tool.name, buildJsonObject { put("path", "test.kt") })
        var round = 0
        var corrections = 0
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = if (round++ == 0) {
                flowOf(first, ProviderEvent.Completed)
            } else {
                flowOf(ProviderEvent.TextDelta("I have read the first file. The fix remains to be implemented."), ProviderEvent.Completed)
            }
        }
        val book = GitHubEvidenceBook("b", "o/r", "feature", setOf(tool.name))
        val session = GitHubVerifiedSession(provider, book, "Fix app.kt and test the change", { 8192 }, {
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta("""{"claims":[{"kind":"not_measured","subject":"implementation and tests"}]}"""), ProviderEvent.Completed)
            }
        }, {}, {}, remainingToolCalls = { 20 }, openExecutionContinuation = { prompt ->
            corrections++
            assertTrue(prompt.contains("Resume only unverified criteria"))
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(next, ProviderEvent.Completed)
            }
        })
        assertEquals(first, session.streamRound(listOf(tool), emptyList()).toList().filterIsInstance<ProviderEvent.ToolCall>().single())
        book.observe(
            first,
            AgentToolResult(
                first.callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("content", "source")
                        put("path", "app.kt")
                    }
                ),
                false
            )
        )
        val events = session.streamRound(listOf(tool), emptyList()).toList()
        assertEquals("events=$events checkpoint=${book.diagnostic()}", 1, corrections)
        assertEquals(next, events.filterIsInstance<ProviderEvent.ToolCall>().single())
        assertTrue(events.none { it is ProviderEvent.Failed || it is ProviderEvent.TextDelta })
        assertEquals(ProviderEvent.Completed, events.last())
        // Without another observed operation, another prose stop cannot spend another correction.
        session.streamRound(listOf(tool), emptyList()).toList()
        assertEquals("events=$events checkpoint=${book.diagnostic()}", 1, corrections)
    }
}
