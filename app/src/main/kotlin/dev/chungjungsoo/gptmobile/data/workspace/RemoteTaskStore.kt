package dev.chungjungsoo.gptmobile.data.workspace

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

@Singleton
class RemoteTaskStore @Inject constructor(private val database: ChatDatabaseV2, private val statistics: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsRepository) {
    suspend fun latest(id: String) = database.workspaceDao().get(id)
    suspend fun save(config: McpConnectionConfig, task: JsonObject, callId: String?, answered: Set<String> = emptySet(), cancellationRequested: Boolean = false): WorkspaceRecord = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val taskId = task.getValue("taskId").jsonPrimitive.content
        require(taskId.length in 1..1024)
        val id = "remote:${config.connectionUid}:${WorkspaceRepository.digest(taskId)}"
        val old = database.workspaceDao().get(id)
        val previous = old?.let { runCatching { Json.decodeFromString<RemoteTaskHandle>(it.payload) }.getOrNull() }
        val statisticsInvocation = previous?.statisticsInvocation
            ?: kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsContext]?.invocationId
        val contextRun = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.agent.ToolRunContext]?.runId?.let { database.agentRunDao().getById(it) }
        val owner = contextRun?.let { it.chatId to it.runId } ?: callId?.let { call -> database.openHelper.readableDatabase.query("SELECT r.chat_id, r.run_id FROM tool_events t JOIN agent_runs r ON r.run_id = t.run_id WHERE t.call_id = ? ORDER BY t.started_at DESC LIMIT 1", arrayOf(call)).use { if (it.moveToFirst()) it.getInt(0) to it.getString(1) else null } }
        val record = WorkspaceRecord(id, "remote", "MCP · ${task["status"]?.jsonPrimitive?.content.orEmpty()}", Json.encodeToString(RemoteTaskHandle(config.connectionUid, identity(config), task, answered, cancellationRequested, statisticsInvocation)), owner?.first ?: old?.chatId, owner?.second ?: old?.runId)
        database.workspaceDao().save(record)
        val outcome = when (task["status"]?.jsonPrimitive?.content) {
            "completed" -> if ((task["result"] as? JsonObject)?.get("isError")?.jsonPrimitive?.content == "true") dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.FAILURE else dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.SUCCESS
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
