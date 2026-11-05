package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One contract for HTML, text and documents, independent of link crawling or display mode. */
@Serializable
data class ResearchDocumentResult(
    val url: String,
    val content: String = "",
    val truncated: Boolean = false,
    val links: List<String> = emptyList(),
    val bodyChunks: List<String> = emptyList(),
    val status: String = "read",
    val failureCode: String? = null,
    val failureReason: String? = null,
    val tablesInspected: Boolean? = null,
    val figuresInspected: Boolean? = null,
    val supplementsInspected: Boolean? = null,
    val citationMetadata: Map<String, String> = emptyMap(),
    val sourceBytesRead: Long? = null,
    val sourceBytesExpected: Long? = null,
    val sourceComplete: Boolean? = null,
    val sourceSha256: String? = null,
    val textStartOffset: Int? = null,
    val textEndOffset: Int? = null,
    val extractedTextCharacters: Int? = null,
    val truncationReasons: List<String> = emptyList(),
    val firstPage: Int? = null,
    val lastPage: Int? = null,
    val totalPages: Int? = null,
    val nextReadArguments: JsonObject? = null,
    val extractionWarnings: List<String> = emptyList()
) {
    fun toJson(): JsonObject = codec.encodeToJsonElement(serializer(), this) as JsonObject

    companion object {
        private val codec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        fun fromJson(value: JsonObject): ResearchDocumentResult? = runCatching { codec.decodeFromJsonElement(serializer(), value) }.getOrNull()
    }
}
