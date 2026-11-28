package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseContinuationPlanTest {
    private fun current(request: String) = ConversationTurn(MessageV2(id = 1, chatId = 1, platformType = null, content = request), null, isCurrentTurn = true)

    @Test fun oneTurnContinuationSeparatesTheOriginalGoalAndKeepsTheWorkingFindingWithinAdmission() {
        val request = "Fix all repo issues and close"
        val brief = """{"issue":2229,"finding":"Verifier loses the final answer","receipt_id":"retained-issue","next":"inspect typed verification failure"}"""
        val continued = planResponseContinuation(
            listOf(current(request)),
            "Respect authorization and exact receipts.",
            TokenBudgetSettings(contextTokens = 4096, outputTokens = 512),
            "The failure is in final verification.",
            "Finish the next outstanding requirement.",
            emptyList(),
            fitProviderBudget = true,
            retainedTaskContext = brief
        )
        val sent = continued.turns.single().userMessage.content
        assertTrue(sent.startsWith("$request\n\nContinuation instructions:\nFinish"))
        assertFalse(sent.contains("closeFinish"))
        assertTrue(sent.contains("retained-issue") && sent.contains("Verifier loses the final answer"))
        assertTrue(sent.contains("not permission or current verification"))
        val prompt = continued.promptTokens.toLong()
        assertTrue(prompt + dev.chungjungsoo.gptmobile.data.context.RequestAdmission.estimateSafety(prompt) + 512 <= 4096)
    }

    @Test fun sixteenKContinuationFitsRankedFindingsWithoutDroppingSchemasOrTheOriginalRequest() {
        val request = "Report on repository integration."
        val schema = AgentToolDefinition("github_read", "S".repeat(14_700), buildJsonObject { put("type", "object") })
        val system = "s".repeat(7_350)
        val findings = (1..8).map { index ->
            buildJsonObject {
                put("receipt_id", "rank-$index")
                put("observations", "retained observation ".repeat(12))
            }
        }
        val brief = buildJsonObject {
            put("schema", "github.working-brief.v1")
            put("findings", kotlinx.serialization.json.JsonArray(findings))
            put("omitted_observations", 3)
        }
        val plan = planResponseContinuation(
            listOf(current(request)), system, TokenBudgetSettings(contextTokens = 16_384, outputTokens = 4096),
            "", "Write the report.", emptyList(), advertisedTools = listOf(schema), fitProviderBudget = true, retainedTaskContext = brief.toString()
        )
        val sent = plan.turns.single().userMessage.content
        val carried = kotlinx.serialization.json.Json.parseToJsonElement(sent.lineSequence().last()) as kotlinx.serialization.json.JsonObject
        val rows = carried.getValue("findings") as kotlinx.serialization.json.JsonArray
        assertTrue(rows.size in 1 until findings.size)
        assertEquals(findings.take(rows.size), rows.toList())
        assertEquals((3 + findings.size - rows.size).toString(), carried["omitted_observations"].toString())
        assertTrue(sent.startsWith(request))
        assertEquals(listOf(schema), plan.tools)
        assertTrue(plan.promptTokens + dev.chungjungsoo.gptmobile.data.context.RequestAdmission.estimateSafety(plan.promptTokens.toLong()) + 4096 + plan.toolResultBytes / 3 <= 16_384)
    }

    @Test fun futureResultReserveYieldsToTheMandatoryInputButNeverConsumesTheOutputAllowance() {
        val schema = AgentToolDefinition("github_read", "S".repeat(16_200), buildJsonObject { put("type", "object") })
        val request = "Keep the original request, plan and provider limits."
        val plan = planResponseContinuation(
            listOf(current(request)),
            "s".repeat(7_890),
            TokenBudgetSettings(contextTokens = 16_384, outputTokens = 4096),
            "A saved answer.",
            "Continue.",
            emptyList(),
            advertisedTools = listOf(schema),
            fitProviderBudget = true
        )
        assertEquals(4096, plan.outputTokens)
        assertEquals(listOf(schema), plan.tools)
        assertTrue(plan.toolResultBytes / 3 in 512 until 2048)
        assertTrue(plan.turns.single().userMessage.content.endsWith("A saved answer."))
        assertTrue(plan.promptTokens + dev.chungjungsoo.gptmobile.data.context.RequestAdmission.estimateSafety(plan.promptTokens.toLong()) + 4096 + plan.toolResultBytes / 3 <= 16_384)
    }

    @Test fun automaticOutputUsesTheAdmittedContextReserveInsteadOfTheLargerRouteCeiling() {
        val settings = TokenBudgetSettings(contextTokens = 1024)
        val output = responseOutputAllowance(settings, routeOutputCap = 1024)
        assertEquals(256, output)
        val initial = ContextBudgetService.plan(listOf(current("Write 4096 words.")), "Preserve facts.", emptyList(), settings.copy(outputTokens = output))
        assertEquals(256, initial.outputTokens)
        val continuation = planResponseContinuation(initial.turns, initial.system, settings, "Saved paragraph. ".repeat(2000), "\nContinue the answer.", emptyList())
        assertEquals(256, continuation.outputTokens)
        assertTrue(continuation.promptTokens + requireNotNull(continuation.outputTokens) + 64 <= settings.contextTokens)
        assertEquals(128, responseOutputAllowance(settings, routeOutputCap = 128))
        assertEquals(96, responseOutputAllowance(settings.copy(outputTokens = 96), routeOutputCap = 1024))
        assertEquals(1024, responseOutputAllowance(TokenBudgetSettings(), routeOutputCap = 1024))
    }

    @Test fun fourThousandWordSavedAnswerFitsAOneThousandTokenContinuationWindow() {
        val request = "Explain Portuguese history in 4096 words with chronological headings."
        val draft = (1..4096).joinToString(" ") { "saved$it" }
        val settings = TokenBudgetSettings(contextTokens = 1024, outputTokens = 256)
        val plan = planResponseContinuation(listOf(current(request)), "Write accurately.", settings, draft, "\nContinue the remaining eras without repeating text.", emptyList())
        val sent = plan.turns.single().userMessage.content
        assertTrue(sent.startsWith(request))
        assertTrue(sent.contains("saved4096"))
        assertFalse(sent.contains("saved1 saved2"))
        assertTrue(ContextBudgetService.breakdown(plan.turns, plan.system, emptyList()).total + 256 + 64 <= 1024)
        assertEquals(4096, LongResponsePolicy.countWords(draft))
    }

    @Test fun githubContinuationCompactsLargeResultsAgainstTheProviderSafetyReserve() {
        val request = "Verify the already selected repository and revision."
        val call = ProviderEvent.ToolCall("read-1", "github_read", buildJsonObject {})
        val result = AgentToolResult("read-1", ToolResultContent.Text("Saved source evidence. ".repeat(3000)), false)
        val draft = (1..4000).joinToString(" ") { "saved$it" }
        val continued = planResponseContinuation(
            listOf(current(request)),
            "Return only conclusions supported by the retained evidence.",
            TokenBudgetSettings(contextTokens = 4096, outputTokens = 512),
            draft,
            "\nFinish the next outstanding requirement.",
            listOf(AgentToolExchange(listOf(call), listOf(result))),
            fitProviderBudget = true
        )
        val content = continued.turns.single().userMessage.content
        assertTrue(content.startsWith(request))
        assertTrue(content.contains("github_read"))
        assertTrue(content.contains("saved4000"))
        assertTrue(content.length < (result.content as ToolResultContent.Text).text.length)
        val prompt = continued.promptTokens.toLong()
        assertTrue(prompt + dev.chungjungsoo.gptmobile.data.context.RequestAdmission.estimateSafety(prompt) + 512 <= 4096)
    }

    @Test fun originalUserScopeIsNeverClippedToMakeAContinuationFit() {
        val request = "Required original scope ".repeat(1000)
        val error = runCatching {
            planResponseContinuation(listOf(current(request)), "System", TokenBudgetSettings(contextTokens = 1024, outputTokens = 256), "Saved partial", "Continue", emptyList())
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("CONTINUATION_CONTEXT_CAPACITY"))
    }

    @Test fun selectedToolSchemasAndTheirResultsReduceOnlyTheReplayedAnswerTail() {
        val turns = listOf(current("Continue the authorized repository work and retain its receipts."))
        val tools = listOf(
            AgentToolDefinition("repository_read", "Read repository state and return its receipt. ".repeat(12), buildJsonObject { put("type", "object") }),
            AgentToolDefinition("repository_check", "Verify the saved revision and check results. ".repeat(12), buildJsonObject { put("type", "object") })
        )
        val settings = TokenBudgetSettings(contextTokens = 2048, outputTokens = 256)
        val draft = (1..4096).joinToString(" ") { "saved$it" }
        val textOnly = planResponseContinuation(turns, "Preserve receipts.", settings, draft, "\nContinue.", emptyList())
        val continued = planResponseContinuation(turns, "Preserve receipts.", settings, draft, "\nContinue.", emptyList(), advertisedTools = tools)

        assertEquals(tools, continued.tools)
        assertTrue(continued.toolOmissions.isEmpty())
        assertEquals(768, continued.toolResultBytes)
        assertTrue(continued.promptTokens + 256 + 256 + 128 <= 2048)
        assertEquals(ContextBudgetService.breakdown(continued.turns, continued.system, tools).total, continued.promptTokens)
        val replayed = continued.turns.single().userMessage.content
        assertTrue(replayed.startsWith(turns.single().userMessage.content))
        assertTrue(replayed.endsWith("saved4096"))
        assertTrue(replayed.length < textOnly.turns.single().userMessage.content.length)
    }

    @Test fun oversizedSelectedSchemaIsRejectedEvenWithoutAnAnswerTail() {
        val tools = listOf(
            AgentToolDefinition("small_read", "Read a receipt.", buildJsonObject { put("type", "object") }),
            AgentToolDefinition("large_write", "Required schema documentation ".repeat(1000), buildJsonObject { put("type", "object") })
        )
        for (draft in listOf("Saved partial answer", "")) {
            val error = runCatching {
                planResponseContinuation(listOf(current("Complete the repository task.")), "Preserve receipts.", TokenBudgetSettings(contextTokens = 1024, outputTokens = 256), draft, "\nContinue.", emptyList(), advertisedTools = tools)
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("CONTINUATION_CONTEXT_CAPACITY"))
        }
    }

    @Test fun fullInputTextOnlyContinuationStillPreservesTheFrozenHistory() {
        val history = ConversationTurn(MessageV2(id = 0, chatId = 1, platformType = null, content = "Frozen earlier evidence."), null, isCurrentTurn = false)
        val plan = planResponseContinuation(listOf(history, current("Write the final synthesis.")), "Preserve facts.", TokenBudgetSettings(contextTokens = 2048, outputTokens = 256), "Saved answer tail", "\nContinue.", emptyList(), preserveFullInput = true)
        assertEquals(history, plan.turns.first())
        assertTrue(plan.tools.isEmpty())
        assertEquals(0, plan.toolResultBytes)
        assertTrue(plan.notice.startsWith("Combined workflow: FULL_INPUT."))
        assertTrue(plan.turns.last().userMessage.content.endsWith("Saved answer tail"))
    }

    @Test fun singleFinalWriterDoesNotDisableContributorContinuation() {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(continueLongAnswers = false), listOf("p"), mapOf("p" to "model"), "History in 5000 words", acceptedAtMillis = 0, singlePass = true)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root", CombinedRunRole.CONTRIBUTOR, "p")
        assertTrue(responseContinuationLimit(5000, 1024, context, false, false, "") >= 10)
        assertEquals(0, responseContinuationLimit(5000, 1024, context.forRole(CombinedRunRole.SYNTHESIS), true, false, ""))
        assertEquals(Int.MAX_VALUE, responseContinuationLimit(5000, 1024, context.forRole(CombinedRunRole.SYNTHESIS), true, true, ""))
    }

    @Test fun ordinaryAndMultiChatShareTheSameContinuationAllowance() {
        assertTrue(responseContinuationLimit(20000, 256, null, false, false, "") > 16)
    }

    @Test fun newConversationAllowsLongAnswersWhileArchivedStrictPoliciesRemainStrict() {
        val current = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("p"), mapOf("p" to "model"), "History in 5000 words", acceptedAtMillis = 0, singlePass = true)
        assertTrue(current.appendContinuations)
        val archived = kotlinx.serialization.json.Json.decodeFromString<CombinedRequestedPolicy>("""{"participantUids":["p"],"requestedModels":{"p":"model"},"userPrompt":"History","singlePass":true}""")
        assertFalse(archived.appendContinuations)
        assertFalse(archived.settings.continueLongAnswers)
    }

    @Test fun savedPageReadingSurvivesSourceEvictionHistoryCompactionAndAnswerTailTrimming() = kotlinx.coroutines.runBlocking {
        val request = "Finish the answer using the retained page facts."
        val reading = buildJsonObject {
            put("schema", "saved_page_reading.v1")
            put("evidence_kind", "UNTRUSTED_MODEL_DERIVED_NOTES_NOT_CURRENT_RAW_SOURCE")
            put(
                "reads",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "sources",
                                buildJsonObject {
                                    put(
                                        "saved-page",
                                        buildJsonObject {
                                            put("invocation_sha256", "a".repeat(64))
                                            put("body_sha256", "b".repeat(64))
                                        }
                                    )
                                }
                            )
                            put("notes", "FIRST_REQUIRED_FACT: a quoted \"fact\".\nLAST_REQUIRED_FACT: preserve this exact note.")
                        }
                    )
                }
            )
        }
        val pendingCall = ProviderEvent.ToolCall("pending", "github_read", buildJsonObject { put("scope", "Pinned pending scope. ".repeat(2000)) })
        val pending = AgentToolExchange(listOf(pendingCall), listOf(AgentToolResult("pending", ToolResultContent.Text("Pinned pending receipt."), false)))
        val cursor = "retained-page:0:1"
        val pageCall = ProviderEvent.ToolCall("saved-result-consume-${GitHubEvidenceBook.digest("github_read:$cursor").take(24)}", "github_read", buildJsonObject { put("view_cursor", cursor) })
        val page = AgentToolExchange(listOf(pageCall), listOf(AgentToolResult(pageCall.callId, ToolResultContent.Text("OPTIONAL_SOURCE_BODY ".repeat(3000)), false, savedPageReading = reading)))
        assertTrue(SavedViewRecoveryBatch.isComplete(page))
        val exchanges = listOf(pending, page)
        assertEquals(listOf("pending"), ToolExchangeCompactor.compact(exchanges, 8192, 8192 / 3).flatMap { it.calls }.map { it.callId })
        val history = ConversationTurn(MessageV2(id = 0, chatId = 1, platformType = null, content = "OPTIONAL_OLD_HISTORY ".repeat(3000)), null, isCurrentTurn = false)
        val draft = (1..4000).joinToString(" ") { "saved$it" }
        val reopenedPlans = mutableListOf<dev.chungjungsoo.gptmobile.data.context.ContextPlan>()
        val downstreamRequests = mutableListOf<String>()
        val downstreamFacts = mutableListOf<String>()
        val downstreamExchanges = mutableListOf<List<AgentToolExchange>>()
        val downstreamSessions = mutableListOf<AgentProviderSession>()
        val initial = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> =
                kotlinx.coroutines.flow.flowOf(ProviderEvent.TextDelta(draft), ProviderEvent.Usage(finishReason = "length"), ProviderEvent.Completed)
        }
        val output = GitHubOutputContinuationSession(initial, maxContinuations = 1) { savedDraft, retainedExchanges, definitions ->
            assertEquals(draft, savedDraft)
            assertEquals(exchanges, retainedExchanges)
            val reopenedPlan = planResponseContinuation(
                listOf(history, current(request)),
                "Preserve the original request and distinguish observations from verification.",
                TokenBudgetSettings(contextTokens = 4096, outputTokens = 512),
                savedDraft,
                "Finish the next outstanding section.",
                retainedExchanges,
                advertisedTools = definitions,
                fitProviderBudget = true
            )
            reopenedPlans += reopenedPlan
            val freshRequest = reopenedPlan.turns.single().userMessage.content
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> {
                    downstreamRequests += freshRequest
                    downstreamExchanges += exchanges
                    assertTrue(exchanges.isEmpty())
                    // This fresh session has only its reopened request, not the prior page fixture.
                    val carriedJson = freshRequest.lineSequence().single { it.startsWith("{\"schema\":\"saved_page_reading.v1\"") }
                    val carried = kotlinx.serialization.json.Json.parseToJsonElement(carriedJson) as kotlinx.serialization.json.JsonObject
                    val facts = (carried.getValue("reads") as kotlinx.serialization.json.JsonArray).joinToString("\n") {
                        ((it as kotlinx.serialization.json.JsonObject).getValue("notes") as kotlinx.serialization.json.JsonPrimitive).content
                    }
                    downstreamFacts += facts
                    return kotlinx.coroutines.flow.flowOf(ProviderEvent.TextDelta("\n$facts"), ProviderEvent.Completed)
                }
            }.also { downstreamSessions += it }
        }.streamRound(emptyList(), exchanges).toList()
        assertEquals(1, downstreamSessions.size)
        assertTrue(downstreamSessions.single() !== initial)
        assertEquals(listOf(emptyList<AgentToolExchange>()), downstreamExchanges)
        assertEquals("FIRST_REQUIRED_FACT: a quoted \"fact\".\nLAST_REQUIRED_FACT: preserve this exact note.", downstreamFacts.single())
        assertEquals(draft + "\n" + downstreamFacts.single(), output.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertTrue(output.none { it is ProviderEvent.ToolCall || it is ProviderEvent.Failed })
        assertEquals(ProviderEvent.Completed, output.last())
        val plan = reopenedPlans.single()
        val sent = plan.turns.single().userMessage.content
        assertEquals(sent, downstreamRequests.single())
        assertTrue(sent.startsWith(request))
        assertTrue(sent.contains(reading.toString()))
        assertTrue(sent.contains(requireNotNull(SavedPageReading.instruction(exchanges))))
        assertTrue(sent.contains("untrusted model-derived notes") && sent.contains("not that those raw bodies are in this request or that any claim is verified"))
        // Optional completed-tool prose can be evicted when its call metadata
        // exceeds the allowance. The exact notes above and stopping point cannot.
        assertTrue(sent.endsWith("saved4000"))
        assertFalse(sent.contains("Pinned pending receipt."))
        assertFalse(sent.contains("OPTIONAL_SOURCE_BODY") || sent.contains("OPTIONAL_OLD_HISTORY") || sent.contains("saved1 saved2"))
        val prompt = plan.promptTokens.toLong()
        assertTrue(prompt + dev.chungjungsoo.gptmobile.data.context.RequestAdmission.estimateSafety(prompt) + 512 <= 4096)
        assertEquals(reading, page.results.single().savedPageReading)
    }

    @Test fun mandatorySavedPageReadingCapacityRefusesBeforeDispatchWithoutTruncation() {
        val reading = buildJsonObject {
            put("schema", "saved_page_reading.v1")
            put("evidence_kind", "UNTRUSTED_MODEL_DERIVED_NOTES_NOT_CURRENT_RAW_SOURCE")
            put(
                "reads",
                kotlinx.serialization.json.buildJsonArray {
                    repeat(20) { index ->
                        add(
                            buildJsonObject {
                                put(
                                    "sources",
                                    buildJsonObject {
                                        put(
                                            "saved-page-$index",
                                            buildJsonObject {
                                                put("invocation_sha256", "a".repeat(64))
                                                put("body_sha256", "b".repeat(64))
                                            }
                                        )
                                    }
                                )
                                put("notes", "Mandatory page observation. ".repeat(40))
                            }
                        )
                    }
                }
            )
        }
        val call = ProviderEvent.ToolCall("saved-page", "github_read", buildJsonObject {})
        val result = AgentToolResult(call.callId, ToolResultContent.Text("Small optional source projection."), false, savedPageReading = reading)
        val exchanges = listOf(AgentToolExchange(listOf(call), listOf(result)))
        var dispatched = 0
        for (fitProviderBudget in listOf(false, true)) {
            for (draft in listOf("", "Retained partial answer.")) {
                val error = runCatching {
                    planResponseContinuation(
                        listOf(current("Finish the original answer.")),
                        "Retain the saved evidence.",
                        TokenBudgetSettings(contextTokens = 1024, outputTokens = 256),
                        draft,
                        "Continue.",
                        exchanges,
                        fitProviderBudget = fitProviderBudget
                    ).also { dispatched++ }
                }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
                assertTrue(error?.message.orEmpty().contains("CONTINUATION_CONTEXT_CAPACITY"))
                assertTrue(error?.message.orEmpty().contains("No request was dispatched"))
            }
        }
        assertEquals(0, dispatched)
        assertEquals(reading, result.savedPageReading)
    }
}
