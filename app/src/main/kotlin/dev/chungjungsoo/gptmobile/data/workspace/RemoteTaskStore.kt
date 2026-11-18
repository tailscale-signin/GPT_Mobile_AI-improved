package dev.chungjungsoo.gptmobile.data.workspace

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.agent.tool.McpConnectionConfig
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class RemoteTaskHandle(val connectionUid: String, val identity: String, val task: JsonObject, val answered: Set<String> = emptySet(), val cancellationRequested: Boolean = false, val statisticsInvocation: String? = null)

internal fun RemoteTaskHandle.mergeWith(previous: RemoteTaskHandle?): RemoteTaskHandle {
    if (previous == null) return this
    require(previous.identity == identity && previous.connectionUid == connectionUid && previous.task["taskId"] == task["taskId"]) {
        "The saved task belongs to another connection."
    }
    val terminal = previous.task["status"]?.jsonPrimitive?.content in setOf("completed", "cancelled", "failed")
    val preserveTerminal = terminal && previous.task["status"] != task["status"]
    return copy(
        task = if (preserveTerminal) {
            previous.task
        } else if (terminal) {
            JsonObject(previous.task + task)
        } else {
            task
        },
        answered = previous.answered + answered,
        cancellationRequested = previous.cancellationRequested || cancellationRequested,
        statisticsInvocation = previous.statisticsInvocation ?: statisticsInvocation
    )
}

@Singleton
class RemoteTaskStore @Inject constructor(private val database: ChatDatabaseV2, private val statistics: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsRepository) {
    suspend fun latest(id: String) = database.workspaceDao().get(id)
    suspend fun save(config: McpConnectionConfig, task: JsonObject, callId: String?, answered: Set<String> = emptySet(), cancellationRequested: Boolean = false): WorkspaceRecord = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val taskId = task.getValue("taskId").jsonPrimitive.content
        require(taskId.length in 1..1024)
        val id = "remote:${config.connectionUid}:${WorkspaceRepository.digest(taskId)}"
        val attemptInvocation = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsContext]?.invocationId
        val ownerRun = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.agent.ToolRunContext]?.runId
        val (record, merged) = database.withTransaction {
            val old = database.workspaceDao().get(id)
            val previous = old?.let { Json.decodeFromString<RemoteTaskHandle>(it.payload) }
            val next = RemoteTaskHandle(config.connectionUid, identity(config), task, answered, cancellationRequested, attemptInvocation).mergeWith(previous)
            val contextRun = ownerRun?.let { database.agentRunDao().getById(it) }
            val owner = contextRun?.let { it.chatId to it.runId } ?: callId?.let { call -> database.openHelper.readableDatabase.query("SELECT r.chat_id, r.run_id FROM tool_events t JOIN agent_runs r ON r.run_id = t.run_id WHERE t.call_id = ? ORDER BY t.started_at DESC LIMIT 1", arrayOf(call)).use { if (it.moveToFirst()) it.getInt(0) to it.getString(1) else null } }
            val revised = WorkspaceRecord(id, "remote", "MCP · ${next.task["status"]?.jsonPrimitive?.content.orEmpty()}", Json.encodeToString(next), old?.chatId ?: owner?.first, old?.runId ?: owner?.second)
            database.workspaceDao().save(revised)
            revised to next
        }
        val statisticsInvocation = merged.statisticsInvocation
        val outcome = when (merged.task["status"]?.jsonPrimitive?.content) {
            "completed" -> if ((merged.task["result"] as? JsonObject)?.get("isError")?.jsonPrimitive?.content == "true") dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.FAILURE else dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.SUCCESS
            "failed" -> dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.FAILURE
            "cancelled" -> dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.CANCELED
            else -> null
        }
        if (statisticsInvocation != null && outcome != null) statistics.reconcileRemote(statisticsInvocation, outcome)
        record
    }
    companion object {
        fun identity(config: McpConnectionConfig) = WorkspaceRepository.digest(config.endpointUrl + "\n" + config.authorizationHeader.orEmpty())
    }
}
