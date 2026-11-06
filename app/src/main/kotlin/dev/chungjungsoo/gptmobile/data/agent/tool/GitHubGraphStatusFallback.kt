package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A gateway read grant never substitutes for the native operation's current grant. */
internal class GitHubGraphStatusFallback(
    private val nativeStatus: AgentTool,
    private val canExecute: suspend () -> Boolean,
    private val authorize: suspend (String, JsonObject) -> Boolean,
    private val finished: suspend (String, Boolean) -> Unit,
    private val validateInherited: suspend () -> Unit = { currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke() }
) {
    suspend fun execute(callId: String): AgentToolResult {
        val arguments = buildJsonObject { put("action", "status") }
        if (!permitted()) return denied(callId)
        var success = false
        try {
            val authorized = try {
                authorize(callId, arguments)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            // Authorization may wait for a decision. Recheck the inherited profile,
            // native account, plugin and data grants immediately before the read.
            if (!authorized || !permitted()) return denied(callId)
            val result = nativeStatus.execute(callId, arguments)
            if (!permitted()) return denied(callId).copy(dispatched = result.dispatched, executionReceipt = result.executionReceipt)
            success = !result.isError
            return result
        } finally {
            withContext(NonCancellable) { finished(callId, success) }
        }
    }

    private suspend fun permitted(): Boolean = try {
        validateInherited()
        canExecute()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    companion object {
        fun denied(callId: String) = AgentToolResult(
            callId,
            ToolResultContent.Text("Native GitHub status authorization is unavailable or changed. Review the native operation's current grants before retrying."),
            true,
            errorCode = "AUTH_REQUIRED",
            dispatched = false
        )
    }
}
