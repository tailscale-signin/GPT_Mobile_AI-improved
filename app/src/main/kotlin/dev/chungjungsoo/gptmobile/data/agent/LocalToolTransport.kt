package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.CalculatorTool
import dev.chungjungsoo.gptmobile.data.agent.tool.CurrentDateTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ReadFileSliceTool
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import kotlinx.serialization.json.JsonObject

/**
 * Local execution authority belongs to reviewed application code, never to a
 * tool name, schema, remote read-only hint, profile setting or model argument.
 * This permits computation only; model-result delivery still crosses the
 * ordinary permission, provenance and private-provider masking boundaries.
 */
internal object LocalToolTransport {
    private class Identity(tool: AgentTool, queue: ReferenceQueue<AgentTool>? = null) : WeakReference<AgentTool>(tool, queue) {
        private val identityHash = System.identityHashCode(tool)
        override fun hashCode(): Int = identityHash
        override fun equals(other: Any?): Boolean = this === other || other is Identity && get()?.let { it === other.get() } == true
    }

    private val collected = ReferenceQueue<AgentTool>()
    private val trustedWrappers = mutableSetOf<Identity>()

    @Synchronized
    fun isTransportFree(tool: AgentTool): Boolean {
        prune()
        return tool is CalculatorTool || tool is CurrentDateTool || tool is ReadFileSliceTool || tool is AuditLocalTool || Identity(tool) in trustedWrappers
    }

    /** Called only by app-owned wrappers that preserve their actual child. */
    @Synchronized
    fun inherit(wrapper: AgentTool, child: AgentTool) {
        if (isTransportFree(child)) trustedWrappers.add(Identity(wrapper, collected))
    }

    fun requireExecution(tool: AgentTool) {
        if (!isTransportFree(tool)) PrivacyModeStore.requireUnprotectedTransport()
    }

    private fun prune() {
        while (true) {
            val next = collected.poll() as? Identity ?: return
            trustedWrappers.remove(next)
        }
    }
}

/** Fixed in-process audit fixtures have no application/service dependency. */
private class AuditLocalTool(
    override val definition: AgentToolDefinition,
    private val exercise: suspend (String, JsonObject) -> AgentToolResult
) : AgentTool {
    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = exercise(callId, arguments)
}

/** This factory is not used for configured plugins or model-selected tools. */
internal fun appAuditLocalTool(
    name: String,
    schema: JsonObject,
    execute: suspend (String, JsonObject) -> AgentToolResult
): AgentTool {
    require(name in setOf("app_audit_budget_fixture", "app_audit_permission_fixture", "app_audit_receipt_fixture", "app_audit_runner_fixture"))
    return AuditLocalTool(AgentToolDefinition(name, "Disposable app-audit fixture", schema), execute)
}
