package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Raw Gradio /call validates every component slot, including hidden server-owned State. */
internal data class GradioInputContract(val publicSlots: List<Boolean>) {
    fun inputs(prompt: JsonElement, settings: JsonElement): JsonArray {
        val public = listOf(prompt, settings).iterator()
        return JsonArray(publicSlots.map { if (it) public.next() else JsonNull })
    }

    companion object {
        fun discover(config: JsonObject, info: JsonObject): GradioInputContract {
            fun unsupported(): Nothing = throw FreeProviderException(FreeFailureCategory.REQUEST_SCHEMA_INCOMPATIBLE, "The Qwen Space input layout is unsupported. No generation request was sent; update its adapter.")
            val endpoint = (info["named_endpoints"] as? JsonObject)?.get("/add_message") as? JsonObject ?: unsupported()
            val parameters = (endpoint["parameters"] as? JsonArray)?.map { ((it as? JsonObject)?.get("parameter_name") as? JsonPrimitive)?.contentOrNull }
            if (parameters != listOf("input_value", "settings_form_value")) unsupported()
            val dependency = (config["dependencies"] as? JsonArray)?.filterIsInstance<JsonObject>()?.singleOrNull {
                (it["api_name"] as? JsonPrimitive)?.contentOrNull?.trimStart('/') == "add_message"
            } ?: unsupported()
            val components = (config["components"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: unsupported()
            val ids = components.map { (it["id"] as? JsonPrimitive)?.intOrNull ?: unsupported() }
            if (ids.distinct().size != ids.size) unsupported()
            val byId = ids.zip(components).toMap()
            val inputs = dependency["inputs"] as? JsonArray ?: unsupported()
            if (inputs.size !in 2..16 || inputs.distinct().size != inputs.size) unsupported()
            val publicTypes = mutableListOf<String>()
            val slots = inputs.map { id ->
                val component = byId[(id as? JsonPrimitive)?.intOrNull] ?: unsupported()
                val type = (component["type"] as? JsonPrimitive)?.contentOrNull ?: unsupported()
                if (type == "state") {
                    false
                } else {
                    publicTypes += type
                    true
                }
            }
            // This adapter only understands the vetted Qwen text + settings form contract.
            if (publicTypes != listOf("antdxsender", "antdform")) unsupported()
            return GradioInputContract(slots)
        }
    }
}
