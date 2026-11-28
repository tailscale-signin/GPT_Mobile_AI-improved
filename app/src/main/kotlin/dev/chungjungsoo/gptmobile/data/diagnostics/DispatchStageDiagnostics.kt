package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Transport delivery and upstream inference admission are separate observations. */
internal object DispatchStageDiagnostics {
    const val FEATURE = "STAGED_DISPATCH_EVIDENCE_V1"
    val fields = setOf("http_request_dispatched", "upstream_inference_dispatched", "dispatch_scope")

    fun validValue(key: String, value: JsonElement): JsonElement? {
        val primitive = value as? JsonPrimitive ?: return null
        if (key == "dispatch_scope") return primitive.takeIf { it.isString && it.content in setOf("HTTP_REQUEST", "UPSTREAM_INFERENCE") }
        if (key !in fields || primitive.isString) return null
        return primitive.booleanOrNull?.let(::JsonPrimitive)
    }
}
