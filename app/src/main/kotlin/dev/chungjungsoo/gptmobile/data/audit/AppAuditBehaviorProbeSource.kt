package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant
import javax.inject.Inject
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class AppAuditBehaviorProbeSource @Inject constructor() :
    AppAuditFixtureSource(
        "behavior",
        listOf(
            AppAuditFixture("budget_precheck_v1", "Argument admission and shared tool budget", listOf("agent_tools", "agent_execution")) {
                val schema = Json.parseToJsonElement("""{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}""").jsonObject
                var invocations = 0
                val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 2, finalResponseToolCallReserve = 1))
                val tool = budget.bind(
                    fixtureTool("app_audit_budget_fixture", schema) { id, _ ->
                        invocations++
                        fixtureResult(id)
                    }
                )
                val invalid = tool.execute("invalid", buildJsonObject { put("value", 7) })
                verify(invalid.errorCode == "INVALID_ARGUMENTS" && !invalid.dispatched && budget.usedCalls() == 0 && invocations == 0)
                val arguments = buildJsonObject { put("value", "fixture") }
                val accepted = tool.execute("accepted", arguments)
                verify(!accepted.isError && accepted.dispatched && accepted.toolCallBudgetReserved == 1 && budget.usedCalls() == 1)
                verify(tool.execute("accepted", arguments).errorCode == "DUPLICATE_INVOCATION")
                val exhausted = tool.execute("exhausted", arguments)
                verify(exhausted.errorCode == "BUDGET_EXHAUSTED" && !exhausted.dispatched && invocations == 1 && budget.remainingCalls() == 0)
            },
            AppAuditFixture("authorization_denial_v1", "Denied tool authority dispatches no work", listOf("auth_and_security", "agent_tools")) {
                var invocations = 0
                val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 1))
                val tool = budget.bind(
                    fixtureTool("app_audit_permission_fixture") { id, _ ->
                        invocations++
                        fixtureResult(id)
                    },
                    authorize = { _, _ -> false }
                )
                val denied = tool.execute("denied", JsonObject(emptyMap()))
                verify(denied.errorCode == "AUTH_REQUIRED" && !denied.dispatched)
                verify(invocations == 0 && budget.usedCalls() == 0)
            },
            AppAuditFixture("unknown_receipt_v1", "Unknown tool outcome requires reconciliation", listOf("agent_execution", "agent_tools")) {
                var invocations = 0
                val tool = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 3)).bind(
                    fixtureTool("app_audit_receipt_fixture") { _, _ ->
                        invocations++
                        fixtureResult("mismatched-receipt").copy(dispatched = false)
                    }
                )
                val arguments = buildJsonObject { put("path", "fixture.txt") }
                val first = tool.execute("first", arguments)
                verify(first.errorCode == "TOOL_RESULT_ID_MISMATCH" && first.dispatched)
                val next = tool.execute("retry", arguments)
                verify(next.errorCode == "RECONCILIATION_REQUIRED" && !next.dispatched && invocations == 1)
            },
            AppAuditFixture("grant_scope_v1", "Permission identity binds schema, chat and resource", listOf("auth_and_security")) {
                val schema = buildJsonObject { put("type", "object") }
                val arguments = buildJsonObject {
                    put("owner", "fixture")
                    put("repo", "project")
                    put("path", "one.txt")
                    put("action", "read")
                }
                val reversed = JsonObject(arguments.entries.reversed().associate { it.key to it.value })
                verify(ScopedToolGrant.canonicalHash(arguments) == ScopedToolGrant.canonicalHash(reversed))
                val grant = ScopedToolGrant.from(1, schema, arguments)
                verify(grant.key() == ScopedToolGrant.from(1, schema, reversed).key())
                verify(grant.key() != ScopedToolGrant.from(2, schema, arguments).key())
                verify(
                    grant.key() != ScopedToolGrant.from(
                        1,
                        schema,
                        buildJsonObject {
                            arguments.forEach { (key, value) -> put(key, value) }
                            put("path", "two.txt")
                        }
                    ).key()
                )
                verify(
                    grant.key() != ScopedToolGrant.from(
                        1,
                        schema,
                        buildJsonObject {
                            arguments.forEach { (key, value) -> put(key, value) }
                            put("action", "write")
                        }
                    ).key()
                )
            },
            AppAuditFixture("runner_isolation_v1", "Agent collections retain independent execution budgets", listOf("agent_execution")) {
                var invocations = 0
                var rounds = 0
                val tool = fixtureTool("app_audit_runner_fixture") { id, _ ->
                    invocations++
                    fixtureResult(id)
                }
                val session = object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        rounds++
                        if (exchanges.isEmpty()) {
                            emit(ProviderEvent.ToolCall("fixture-call", tool.definition.name, JsonObject(emptyMap())))
                        } else {
                            emit(ProviderEvent.TextDelta("Fixture receipt retained."))
                        }
                        emit(ProviderEvent.Completed)
                    }
                }
                val run = AgentRunner(AgentRunLimits(maxToolCalls = 1, maxRounds = 1)).run(session, listOf(tool))
                repeat(2) {
                    val events = run.toList()
                    val receipts = events.filterIsInstance<AgentRunEvent.ToolFinished>()
                    verify(receipts.size == 1 && receipts.single().result.dispatched && !receipts.single().result.isError)
                    val provider = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
                    verify(provider.count { it is ProviderEvent.Completed } == 1 && provider.none { it is ProviderEvent.Failed })
                }
                verify(invocations == 2 && rounds == 4)
            }
        )
    )

private fun fixtureResult(id: String) = AgentToolResult(id, ToolResultContent.Text("fixture receipt"), isError = false)

private fun fixtureTool(
    name: String,
    schema: JsonObject = buildJsonObject { put("type", "object") },
    execute: suspend (String, JsonObject) -> AgentToolResult
) = object : AgentTool {
    override val definition = AgentToolDefinition(name, "Disposable app-audit fixture", schema)
    override suspend fun execute(callId: String, arguments: JsonObject) = execute.invoke(callId, arguments)
}
