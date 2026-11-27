package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventResultType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Save complete research and its separate display trace without changing the Room schema. */
object ToolResultCheckpoint {
    fun encode(payload: String, type: String, display: String, personalData: List<dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance> = emptyList(), executionReceipt: JsonObject? = null): String = buildJsonObject {
        put("payload", payload)
        put("payload_type", type)
        put("display", display)
        executionReceipt?.let { put("execution", it) }
        if (personalData.isNotEmpty()) put("personal_data", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance.serializer()), personalData))
    }.toString()

    fun execution(event: ToolEvent): JsonObject? = if (event.resultType != ToolEventResultType.CHECKPOINT) {
        null
    } else {
        runCatching {
            (Json.parseToJsonElement(event.result.orEmpty()) as? JsonObject)?.get("execution") as? JsonObject
        }.getOrNull()
    }

    /** Only the original local decision's bound receipt can project a failure-shaped wait. */
    fun waitingForUserChoice(event: ToolEvent): Boolean {
        val name = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubDecisionTool.NAME
        val pending = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubDecisionTool.PENDING
        if (!event.isError || event.toolName != name || event.modelToolName != name) return false
        if (event.status !in setOf(ToolEventStatus.PENDING, ToolEventStatus.RUNNING, ToolEventStatus.WAITING_FOR_USER, ToolEventStatus.FAILED)) return false
        val receipt = execution(event) ?: return false
        if (receipt["schema"] != JsonPrimitive("gptmobile.tool-execution.v1") ||
            receipt["call_id"] != JsonPrimitive(event.callId) ||
            receipt["run_id"] != JsonPrimitive(event.runId) ||
            receipt["event_id"] != JsonPrimitive(event.eventId) ||
            receipt["tool"] != JsonPrimitive(name) ||
            receipt["error_code"] != JsonPrimitive(pending) ||
            receipt["dispatched"] != JsonPrimitive(false) ||
            receipt["execution_state"] != JsonPrimitive("PRECHECK_REJECTED") ||
            receipt["requires_reconciliation"] != JsonPrimitive(false)
        ) {
            return false
        }
        val payload = runCatching { Json.parseToJsonElement(read(event, "payload").orEmpty()) as? JsonObject }.getOrNull() ?: return false
        val promptId = (payload["choice_prompt_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return payload["status"] == JsonPrimitive(pending) && promptId?.matches(Regex("[a-f0-9]{64}")) == true
    }

    /** Native rejection or a normalized precheck can prove no effect; uncertainty never can. */
    fun confirmsNoEffect(event: ToolEvent): Boolean {
        if (!event.isError) return false
        val receipt = execution(event) ?: return false
        // Older MCP wrappers inferred UNKNOWN_TOOL prechecks from remote result text.
        // Such receipts cannot release a mutation unless a local catalogue check is recorded.
        if (event.modelToolName.startsWith("mcp__") &&
            receipt["error_code"] == JsonPrimitive("UNKNOWN_TOOL") &&
            (receipt["schema"] != JsonPrimitive("gptmobile.mcp-operation.v1") || receipt["dispatch_evidence"] != JsonPrimitive("LOCAL_CATALOGUE_PRECHECK"))
        ) {
            return false
        }
        if (receipt["requires_reconciliation"] != JsonPrimitive(false) ||
            (receipt["error_code"] as? JsonPrimitive)?.content in ToolResultEnvelope.unknownOutcomeCodes
        ) {
            return false
        }
        return receipt["phase"] in setOf(JsonPrimitive("PREFLIGHT_REJECTED"), JsonPrimitive("REJECTED")) ||
            receipt["execution_state"] == JsonPrimitive("PRECHECK_REJECTED") &&
            receipt["dispatched"] == JsonPrimitive(false)
    }

    fun personalData(event: ToolEvent): List<dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance> {
        if (event.resultType != ToolEventResultType.CHECKPOINT) return emptyList()
        val saved = Json.parseToJsonElement(event.result.orEmpty()) as? JsonObject ?: return emptyList()
        val labels = saved["personal_data"] ?: return emptyList()
        return Json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance.serializer()), labels)
    }

    internal fun read(event: ToolEvent, key: String): String? {
        if (event.resultType != ToolEventResultType.CHECKPOINT) return event.result
        return runCatching {
            val saved = Json.parseToJsonElement(event.result.orEmpty()) as JsonObject
            (saved[key] as? JsonPrimitive)?.content
        }.getOrNull() ?: event.result
    }
}

fun ToolEvent.recoveryResult(): String? = ToolResultCheckpoint.read(this, "payload")
fun ToolEvent.displayResult(): String? = ToolResultCheckpoint.read(this, "display")

/** A durable user wait retains its failure-shaped control receipt without reporting a tool failure. */
fun ToolEvent.isToolFailure(): Boolean = (status == ToolEventStatus.FAILED || isError) && !ToolResultCheckpoint.waitingForUserChoice(this)
