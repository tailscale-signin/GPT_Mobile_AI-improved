package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventResultType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Save complete research and its separate display trace without changing the Room schema. */
object ToolResultCheckpoint {
    fun encode(payload: String, type: String, display: String, personalData: List<dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance> = emptyList()): String = buildJsonObject {
        put("payload", payload)
        put("payload_type", type)
        put("display", display)
        if (personalData.isNotEmpty()) put("personal_data", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance.serializer()), personalData))
    }.toString()

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
