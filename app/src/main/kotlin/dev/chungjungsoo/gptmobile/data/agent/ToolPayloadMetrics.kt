package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Payload sizes describe normalized tool content, not HTTP framing or billed tokens. */
@Serializable
data class ToolPayloadMetrics(
    val argumentsCharacters: Int = 0,
    val argumentsBytes: Int = 0,
    val resultBytes: Int? = null,
    val estimatedResultTokens: Int? = null,
    val durationMs: Long? = null,
    val timingSource: String = "client",
    val shared: Boolean = false
) {
    companion object {
        fun measure(arguments: String, content: ToolResultContent, durationMs: Long? = null, shared: Boolean = false): ToolPayloadMetrics {
            val text = when (content) {
                is ToolResultContent.Text -> content.text
                is ToolResultContent.Json -> content.value.toString()
                is ToolResultContent.ResourceLinks -> buildJsonArray {
                    content.links.forEach { link ->
                        add(
                            buildJsonObject {
                                put("uri", link.uri)
                                put("name", link.name)
                                put("mimeType", link.mimeType)
                            }
                        )
                    }
                }.toString()
            }
            return ToolPayloadMetrics(
                argumentsCharacters = arguments.length,
                argumentsBytes = arguments.toByteArray(Charsets.UTF_8).size,
                resultBytes = text.toByteArray(Charsets.UTF_8).size,
                estimatedResultTokens = ((text.length.toLong() + 3) / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                durationMs = durationMs,
                shared = shared
            )
        }
    }
}
