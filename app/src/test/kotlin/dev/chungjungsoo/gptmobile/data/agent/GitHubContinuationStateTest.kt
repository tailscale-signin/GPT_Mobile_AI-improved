package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.GitHubEngineeringMemoryPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubContinuationStateTest {
    private val task = "Inspect the repository issues, implement the fixes, verify the changes and report remaining blockers."
    private fun current() = listOf(ConversationTurn(MessageV2(id = 1, chatId = 1, platformType = null, content = task), null, isCurrentTurn = true))
    private fun decision(id: String = "choice", answer: String = "Fix the runtime blockers first.") = AgentToolExchange(
        listOf(ProviderEvent.ToolCall(id, "github_decide", buildJsonObject { put("question", "Which work should proceed?") })),
        listOf(
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("question", "Which work should proceed?")
                        put("user_answer", answer)
                        put("cancelled", false)
                    }
                ),
                false,
                dispatched = false
            )
        )
    )
    private fun rejected() = AgentToolExchange(
        listOf(ProviderEvent.ToolCall("invalid-read", "github_read", JsonObject(emptyMap()))),
        listOf(
            AgentToolResult(
                "invalid-read",
                ToolResultContent.Json(
                    buildJsonObject {
                        put("validation_errors", JsonArray(listOf(JsonPrimitive("Supply ranges or evidence_id"))))
                        put("corrected_proposal_limit", 1)
                    }
                ),
                true,
                errorCode = "INVALID_ARGUMENTS",
                dispatched = false
            )
        )
    )
    private val brief = buildJsonObject {
        put("schema", "github.working-brief.v1")
        put(
            "findings",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("receipt_id", "issue-2705")
                        put("observations", "The pending tool handoff loses its original call identity.")
                    }
                )
            )
        )
        put("omitted_observations", 3)
    }.toString()
    private fun wideTool(name: String) = AgentToolDefinition(
        name,
        "Action documentation ".repeat(350),
        buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put(
                "properties",
                buildJsonObject {
                    put(
                        "action",
                        buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf("list", "context", "update").map(::JsonPrimitive)))
                        }
                    )
                    put("page", buildJsonObject { put("type", "integer") })
                    put(
                        "body",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Update body ".repeat(150))
                        }
                    )
                    put("pull_number", buildJsonObject { put("type", "integer") })
                }
            )
        }
    )

    @Test fun fiveThousandTokenContinuationRetainsChoicesCorrectionFindingAndOriginalTaskByProjectingActions() {
        val selected = ProviderEvent.ToolCall("issues", "github_issue", buildJsonObject { put("action", "list") })
        val issue = AgentToolExchange(listOf(selected), listOf(AgentToolResult(selected.callId, ToolResultContent.Text("Issue listing ".repeat(2000)), false)))
        val tools = listOf(wideTool("github_issue"), wideTool("github_pr"), AgentToolDefinition(ProgressiveToolCatalog.NAME, "Discover an authorized action.", buildJsonObject { put("type", "object") }))
        val plan = planResponseContinuation(
            current(), "Respect the recorded task and exact tool receipts.", TokenBudgetSettings(contextTokens = 5632, outputTokens = 768),
            "The runtime blockers are identified.", "Continue from the next unfinished source read.", listOf(decision(), rejected(), issue),
            advertisedTools = tools, fitProviderBudget = true, retainedTaskContext = brief, adaptToolSchemas = true
        )
        val sent = plan.turns.single().userMessage.content
        assertTrue(sent.startsWith(task))
        assertTrue(sent.contains("Fix the runtime blockers first."))
        assertTrue(sent.contains("Supply ranges or evidence_id"))
        assertTrue(sent.contains("issue-2705") && sent.contains("loses its original call identity"))
        val actual = plan.tools.single { it.name == "github_issue" }
        assertEquals(GitHubActionSchema.project(tools.first(), "list"), actual)
        assertTrue(ContextBudgetService.breakdown(emptyList(), "", plan.tools).toolSchemas < ContextBudgetService.breakdown(emptyList(), "", tools).toolSchemas)
        assertTrue(plan.promptTokens + RequestAdmission.estimateSafety(plan.promptTokens.toLong()) + 768 + plan.toolResultBytes / 3 <= 5632)
        assertEquals(tools, plan.authorizedTools)
    }

    @Test fun exactPendingProtocolIsChargedOnceAndNeverRenderedAsUserProse() {
        val call = ProviderEvent.ToolCall("pending", "github_read", buildJsonObject { put("evidence_id", "original-evidence") })
        val pending = AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("EXACT_PENDING_BODY ".repeat(80)), false)))
        val plan = planResponseContinuation(current(), "System", TokenBudgetSettings(contextTokens = 5632, outputTokens = 512), "", "Continue.", listOf(pending), fitProviderBudget = true, protocolExchanges = listOf(pending))
        assertFalse(plan.turns.single().userMessage.content.contains("EXACT_PENDING_BODY"))
        val prompt = ContextBudgetService.breakdown(plan.turns, plan.system, plan.tools).total
        assertEquals(prompt + ToolExchangeCompactor.estimateTokens(listOf(pending)), plan.promptTokens)
        assertEquals("original-evidence", (pending.calls.single().arguments["evidence_id"] as JsonPrimitive).content)
    }

    @Test fun projectedProtocolPreservesEveryBatchIdentityAndTerminalErrorWithItsSourceDigest() {
        val success = AgentToolExchange(listOf(ProviderEvent.ToolCall("success", "github_read", buildJsonObject { put("evidence_id", "source-id") })), listOf(AgentToolResult("success", ToolResultContent.Text("Source observation ".repeat(1500)), false)))
        val failure = AgentToolExchange(listOf(ProviderEvent.ToolCall("failure", "github_ci", buildJsonObject { put("action", "status") })), listOf(AgentToolResult("failure", ToolResultContent.Text("Authorization was revoked. ".repeat(500)), true, dispatched = false, errorCode = "AUTH_REQUIRED")))
        val original = listOf(success, failure)
        val projected = ToolExchangeCompactor.compactProtocol(original, 700, 128)
        assertEquals(original.map { it.calls }, projected.map { it.calls })
        assertEquals(original.map { it.results.map { result -> result.callId } }, projected.map { it.results.map { result -> result.callId } })
        val error = projected.last().results.single()
        val body = (error.content as ToolResultContent.Json).value as JsonObject
        assertTrue(error.isError && !error.dispatched)
        assertEquals(JsonPrimitive("AUTH_REQUIRED"), body["error_code"])
        assertEquals(JsonPrimitive(ToolResultDeliveryProof.digest(failure.results.single().content)), body["source_result_sha256"])
        assertEquals(JsonPrimitive(true), body["projection_only"])
        assertTrue(ToolExchangeCompactor.estimateTokens(projected) < ToolExchangeCompactor.estimateTokens(original))
    }

    @Test fun irreducibleAnsweredChoiceRefusesCapacityInsteadOfForgettingIt() {
        val failure = runCatching {
            planResponseContinuation(current(), "System", TokenBudgetSettings(contextTokens = 1024, outputTokens = 256), "", "Continue.", listOf(decision(answer = "Chosen answer ".repeat(500))), fitProviderBudget = true)
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().startsWith("CONTINUATION_CONTEXT_CAPACITY"))
    }

    @Test fun anOwedSavedPageCannotBecomeASemanticProtocolProjection() {
        val call = ProviderEvent.ToolCall("page", "github_search", buildJsonObject { put("view_cursor", "issued:0:4096") })
        val page = AgentToolResult(
            call.callId,
            ToolResultContent.Text("Exact saved source ".repeat(1000)),
            false,
            executionReceipt = buildJsonObject {
                put("saved_view_cursor_issued", true)
                put("saved_view_body_bytes", 19000)
            }
        )
        val original = listOf(AgentToolExchange(listOf(call), listOf(page)))
        assertEquals(original, ToolExchangeCompactor.compactProtocol(original, 512, 128))
        val failure = runCatching {
            planResponseContinuation(current(), "System", TokenBudgetSettings(contextTokens = 5632, outputTokens = 512), "", "Continue.", original, fitProviderBudget = true, protocolExchanges = original)
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().startsWith("CONTINUATION_CONTEXT_CAPACITY"))
    }

    @Test fun mandatoryStateSurvivesEvictedOriginalPairsWithoutChangingTheRetainedSourceBody() {
        val history = listOf(decision(), rejected(), status(0))
        val memory = requireNotNull(GitHubContinuationMemory.snapshot(history, task, includeFinding = true))
        val last = history.last()
        val projected = GitHubContinuationMemory.project(listOf(last), memory)
        assertEquals(last.calls, projected.single().calls)
        assertEquals(last.results.single().content, projected.single().results.single().content)
        val restored = requireNotNull(GitHubContinuationMemory.snapshot(projected, task, includeFinding = true)).toString()
        assertTrue(restored.contains("Fix the runtime blockers first."))
        assertTrue(restored.contains("Supply ranges or evidence_id"))
        assertTrue(restored.contains("open_issue_count"))
        assertTrue(GitHubContinuationMemory.reservation(memory) > 0)
        assertEquals(projected, GitHubContinuationMemory.project(projected, memory))
    }

    @Test fun aCorrectedConstraintAndSupersededChoiceStayResolvedAfterCarrierRotation() {
        val rejected = rejected()
        val successful = AgentToolExchange(
            listOf(ProviderEvent.ToolCall("corrected", "github_read", buildJsonObject { put("evidence_id", "retained") })),
            listOf(AgentToolResult("corrected", ToolResultContent.Text("Source observation"), false))
        )
        val original = listOf(decision(), rejected, successful, decision("updated-choice", "Fix the saved-page read first."))
        val snapshot = requireNotNull(GitHubContinuationMemory.snapshot(original))
        assertNull(snapshot["corrections"])
        val carrier = GitHubContinuationMemory.project(listOf(status(0)), snapshot)
        val rotated = requireNotNull(GitHubContinuationMemory.snapshot(listOf(rejected) + carrier)).toString()
        assertFalse(rotated.contains("Supply ranges or evidence_id"))
        assertFalse(rotated.contains("Fix the runtime blockers first."))
        assertTrue(rotated.contains("Fix the saved-page read first."))
    }

    @Test fun aMandatoryFindingSurvivesAnUnfittableOptionalBrief() {
        val fitted = GitHubEngineeringMemoryPolicy.fitWorkingBrief(brief) { false }
        val value = Json.parseToJsonElement(fitted) as JsonObject
        assertEquals(1, (value["findings"] as JsonArray).size)
        assertTrue(fitted.contains("issue-2705"))
        assertEquals(JsonPrimitive(3), value["omitted_observations"])
    }

    @Test fun aConsumedAnswerRetainsItsExactQuestionAndChoiceInCompactedReplay() {
        val first = decision()
        val recent = AgentToolExchange(listOf(ProviderEvent.ToolCall("status", "github_context", buildJsonObject { put("action", "status") })), listOf(AgentToolResult("status", ToolResultContent.Text("status ".repeat(2000)), false)))
        val replay = ToolExchangeCompactor.compact(listOf(first, recent), 700, 128, consumedCallIds = setOf("choice"))
        val result = replay.flatMap { it.results }.single { it.callId == "choice" }
        assertTrue(result.content.toString().contains("Fix the runtime blockers first."))
        assertTrue(result.content.toString().contains("Which work should proceed?"))
    }

    @Test fun restoredArgumentRejectionsDoNotRenewTheCorrectionAllowance() {
        val definition = AgentToolDefinition("github_read", "Read", buildJsonObject { put("type", "object") })
        val arguments = JsonObject(emptyMap())
        val original = ToolArgumentRepairBudget().rejection("before-stop", definition, arguments, listOf("Supply ranges"))
        val restored = ToolArgumentRepairBudget()
        restored.restore(listOf(requireNotNull(original.executionReceipt)))
        val second = restored.rejection("after-continue", definition, arguments, listOf("Supply ranges"))
        assertEquals(JsonPrimitive(2), second.executionReceipt?.get("argument_repair_attempt"))
        assertEquals(JsonPrimitive(true), second.executionReceipt?.get("argument_repair_exhausted"))
        restored.restore(listOf(requireNotNull(original.executionReceipt)))
        val third = restored.rejection("after-restart", definition, arguments, listOf("Supply ranges"))
        assertEquals(JsonPrimitive(true), third.executionReceipt?.get("argument_repair_exhausted"))
        assertFalse(third.dispatched)
    }

    @Test fun wrapUpInstructionsCannotChangeAnAcknowledgableSavedPageBody() {
        val result = AgentToolResult(
            "page",
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.operation.v1")
                    put("operation", "github_search")
                    put("fragments", JsonArray(listOf(JsonPrimitive("observed source"))))
                }
            ),
            false,
            dispatched = false,
            executionReceipt = buildJsonObject {
                put("saved_view_cursor_issued", true)
                put("saved_view_body_bytes", 132)
            }
        )
        val originalDigest = ToolResultDeliveryProof.digest(result.content)
        val instructed = requireNotNull(SavedPageInstruction.append(result, "Finish from retained results."))
        assertEquals(result.content, instructed.content)
        assertEquals(originalDigest, ToolResultDeliveryProof.digest(instructed.content))
        assertTrue(instructed.assistMetadata.toString().contains("Finish from retained results."))
        assertEquals(instructed, SavedPageInstruction.append(instructed, "Finish from retained results."))
    }

    private fun status(index: Int): AgentToolExchange {
        val call = ProviderEvent.ToolCall("status-$index", "github_context", buildJsonObject { put("action", "status") })
        return AgentToolExchange(
            listOf(call),
            listOf(
                AgentToolResult(
                    call.callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("open_issue_count", 45)
                            put("observed_at_ms", index)
                        }
                    ),
                    false,
                    executionReceipt = buildJsonObject {
                        put("source_result_sha256", index.toString().padStart(64, '0'))
                        put(
                            "semantic_summary",
                            buildJsonObject {
                                put("schema", "github.semantic-summary.v1")
                                put("operation", "github_context")
                                put("action", "status")
                                put("open_issue_count", 45)
                                put("source_result_sha256", index.toString().padStart(64, '0'))
                                put("observed_at_ms", index)
                            }
                        )
                    }
                )
            )
        )
    }
    private fun discovery(index: Int): AgentToolExchange {
        val call = ProviderEvent.ToolCall("discovery-$index", ProgressiveToolCatalog.NAME, buildJsonObject { put("query", "repository $index") })
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Json(buildJsonObject { put("catalog_unchanged", true) }), false)))
    }

    @Test fun interleavedDiscoveryQuestionsAndUnchangedStatusCannotRenewSourceProgress() {
        val progress = GitHubInvestigationProgress(task)
        assertNull(progress.observe(status(0)))
        assertNull(progress.observe(discovery(1)))
        assertNull(progress.observe(decision()))
        assertNull(progress.observe(status(2)))
        assertNotNull(progress.observe(discovery(3)))
    }

    @Test fun restoredInvestigationKeepsItsStallBudgetAcrossContinue() {
        val progress = GitHubInvestigationProgress(task)
        progress.restore(listOf(status(0), discovery(1), decision(), status(2)))
        assertNotNull(progress.observe(discovery(3)))
    }
}
