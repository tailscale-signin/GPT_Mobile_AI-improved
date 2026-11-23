package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Canonical syntax or selected-target binding does not establish a successful semantic repair. */
internal object ToolArgumentNormalization {
    enum class Kind { SCHEMA_CANONICALIZATION, REPOSITORY_BINDING }

    fun retain(result: AgentToolResult, arguments: JsonObject, kind: Kind): AgentToolResult {
        val receipt = result.executionReceipt.orEmpty()
        // The inner executor owns any later correction and its outcome evidence.
        if (receipt["corrected_arguments"] is JsonObject || receipt["canonical_arguments"] is JsonObject) return result
        return result.copy(
            executionReceipt = JsonObject(
                receipt + buildJsonObject {
                    put("canonical_arguments", arguments)
                    put(
                        "argument_normalization",
                        buildJsonObject {
                            put("kind", kind.name)
                            put("status", "APPLIED_BEFORE_DISPATCH")
                            put("semantic_repair", false)
                            put("dispatched", result.dispatched)
                            put("outcome", if (result.isError) "FAILED" else "SUCCEEDED")
                        }
                    )
                }
            )
        )
    }
}
