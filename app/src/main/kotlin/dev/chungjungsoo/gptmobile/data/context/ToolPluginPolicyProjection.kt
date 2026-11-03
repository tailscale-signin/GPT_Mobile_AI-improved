package dev.chungjungsoo.gptmobile.data.context

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Hash effective grants as JSON values, without serializing a JVM SortedMap subtype. */
internal object ToolPluginPolicyProjection {
    fun project(states: Map<String, Boolean>, optInServices: Set<String>): JsonObject = buildJsonObject {
        states.toSortedMap().forEach { (id, enabled) ->
            if (enabled != (id !in optInServices)) put(id, enabled)
        }
    }
}
