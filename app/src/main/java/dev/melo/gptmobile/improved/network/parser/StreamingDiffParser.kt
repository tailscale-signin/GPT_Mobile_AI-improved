package dev.melo.gptmobile.improved.network.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real-time diff parsing for streaming responses with incremental updates.
 * Supports live code diff visualization and progressive content rendering.
 */
class StreamingDiffParser @Inject constructor() {
    private val lock = Any()

    /**
     * Parse a complete response into incremental chunks.
     */
    suspend fun parseStreamingResponse(content: String): Result<List<DiffChunk>> = withContext(Dispatchers.Default) {
        try {
            val chunks = mutableListOf<DiffChunk>()
            var currentIndex = 0

            // Split by common delimiters
            val lines = content.split("\n")

            for (line in lines) {
                val chunk = DiffChunk(
                    id = "${chunks.size + 1}",
                    text = line,
                    isComplete = true,
                    timestamp = System.currentTimeMillis()
                )
                chunks.add(chunk)
            }

            Result.success(chunks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parse JSON streaming response.
     */
    suspend fun parseJsonStreaming(content: String): Result<List<JsonChunk>> = withContext(Dispatchers.Default) {
        try {
            val chunks = mutableListOf<JsonChunk>()
            var buffer = StringBuilder()

            for (char in content) {
                buffer.append(char)

                if (char == '{' || char == '[') {
                    // Start of new object/array
                    chunks.add(JsonChunk(
                        id = "${chunks.size + 1}",
                        text = buffer.toString(),
                        isComplete = false,
                        timestamp = System.currentTimeMillis()
                    ))
                    buffer = StringBuilder()
                } else if (char == '}' || char == ']') {
                    // End of object/array
                    val completeChunk = JsonChunk(
                        id = "${chunks.size + 1}",
                        text = buffer.toString(),
                        isComplete = true,
                        timestamp = System.currentTimeMillis()
                    )
                    chunks.add(completeChunk)
                    buffer = StringBuilder()
                }
            }

            // Add remaining content
            if (buffer.isNotEmpty()) {
                chunks.add(JsonChunk(
                    id = "${chunks.size + 1}",
                    text = buffer.toString(),
                    isComplete = true,
                    timestamp = System.currentTimeMillis()
                ))
            }

            Result.success(chunks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get the latest complete chunk.
     */
    fun getLatestChunk(): DiffChunk? = null // Would need to store chunks in a real implementation

    override fun close() {}
}

/**
 * Incremental diff chunk.
 */
data class DiffChunk(
    val id: String,
    val text: String,
    val isComplete: Boolean,
    val timestamp: Long
)

/**
 * JSON streaming chunk.
 */
data class JsonChunk(
    val id: String,
    val text: String,
    val isComplete: Boolean,
    val timestamp: Long
)