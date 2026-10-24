package dev.chungjungsoo.gptmobile.data.permissions

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import kotlinx.serialization.json.JsonObject

/** Recheck at execution, including tools prepared before a grant was revoked. */
internal class PersonalDataGuardedTool(private val child: AgentTool, private val permitted: suspend () -> Boolean) :
    AgentTool by child,
    dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool,
    dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool {
    override val executionOwner = (child as? dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool)?.executionOwner ?: dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner.CLIENT
    override fun withChildBudget(bind: (AgentTool) -> AgentTool): AgentTool = PersonalDataGuardedTool(child.withChildBudget(bind), permitted)

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = prepareExecution(callId, arguments).invoke()

    override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult {
        check(permitted()) { "PERSONAL_DATA_PERMISSION_REQUIRED: Review this tool's data sharing permission." }
        val execute = (child as? dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool)?.prepareExecution(callId, arguments) ?: suspend { child.execute(callId, arguments) }
        return {
            check(permitted()) { "PERSONAL_DATA_PERMISSION_REVOKED: The prepared tool was not dispatched." }
            val result = execute()
            check(permitted()) { "PERSONAL_DATA_PERMISSION_REVOKED: The result was not released to the model." }
            result
        }
    }
}
