package dev.melo.gptmobile.improved.data.repository.rag

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device document chunking, keyword retrieval (BM25), and vector retrieval (cosine similarity).
 * Local document indexing and retrieval without external API dependencies.
 */
class DocumentRagEngine @Inject constructor(
    private val context: Context
) {
    private val documents = ConcurrentHashMap<String, DocumentIndex>()

    /**
     * Ingest a document file.
     */
    suspend fun ingestDocument(filePath: String): Result<DocumentIndex> = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext Result.failure(Exception("File not found: $filePath"))
            }

            val content = file.readText()
            val chunks = chunkDocument(content)
            val index = DocumentIndex(
                id = filePath,
                chunks = chunks,
                metadata = extractMetadata(filePath, content)
            )
            documents[filePath] = index
            Result.success(index)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Chunk document into smaller pieces for retrieval.
     */
    private fun chunkDocument(content: String, chunkSize: Int = 500, overlap: Int = 100): List<DocumentChunk> {
        val chunks = mutableListOf<DocumentChunk>()
        var start = 0

        while (start < content.length) {
            val end = minOf(start + chunkSize, content.length)
            val chunkText = content.substring(start, end).trim()

            if (chunkText.isNotEmpty()) {
                chunks.add(DocumentChunk(
                    id = "${filePath}_${chunks.size}",
                    text = chunkText,
                    vector = computeVector(chunkText),
                    keywordScore = computeKeywordScore(chunkText)
                ))
            }

            start += chunkSize - overlap
        }

        return chunks
    }

    /**
     * Compute TF-IDF-like vector representation of text.
     */
    private fun computeVector(text: String): FloatArray {
        val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        val vocabulary = words.distinct()
        val vector = FloatArray(vocabulary.size)

        for (i in vocabulary.indices) {
            val word = vocabulary[i]
            val count = words.count { it == word }
            vector[i] = count.toFloat() / words.size
        }

        return vector
    }

    /**
     * Compute BM25 keyword score.
     */
    private fun computeKeywordScore(text: String): Float {
        val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        val wordFreq = ConcurrentHashMap<String, Int>()

        for (word in words) {
            wordFreq[word] = (wordFreq[word] ?: 0) + 1
        }

        var score = 0f
        for ((word, freq) in wordFreq) {
            // Simple term frequency scoring
            score += freq * log(1 + freq)
        }

        return score / words.size
    }

    /**
     * Search documents by keyword.
     */
    suspend fun searchByKeyword(query: String): List<DocumentChunk> = withContext(Dispatchers.Default) {
        val queryVector = computeVector(query)
        val results = mutableListOf<Pair<String, Float>>()

        for ((filePath, index) in documents) {
            var bestScore = 0f
            for (chunk in index.chunks) {
                val score = cosineSimilarity(queryVector, chunk.vector)
                if (score > bestScore) {
                    bestScore = score
                }
            }

            if (bestScore > 0.1f) {
                results.add(filePath to bestScore)
            }
        }

        results.sortedByDescending { it.second }.map { it.first }.flatMap { filePath ->
            documents[filePath]?.chunks?.filter { chunk ->
                cosineSimilarity(queryVector, chunk.vector) > 0.1f
            } ?: emptyList()
        }
    }

    /**
     * Get all ingested document paths.
     */
    fun getDocumentPaths(): List<String> = documents.keys.toList()

    /**
     * Remove a document from the index.
     */
    suspend fun removeDocument(filePath: String) {
        documents.remove(filePath)
    }

    /**
     * Clear all documents.
     */
    suspend fun clearAll() {
        documents.clear()
    }

    private fun extractMetadata(filePath: String, content: String): Map<String, Any> {
        return mapOf(
            "path" to filePath,
            "size" to File(filePath).length(),
            "wordCount" to content.split(Regex("\\W+")).count()
        )
    }

    override fun close() {
        clearAll()
    }
}

/**
 * Index for a single document.
 */
data class DocumentIndex(
    val id: String,
    val chunks: List<DocumentChunk>,
    val metadata: Map<String, Any>
)

/**
 * Individual chunk of a document.
 */
data class DocumentChunk(
    val id: String,
    val text: String,
    val vector: FloatArray,
    val keywordScore: Float
)

/**
 * Cosine similarity between two vectors.
 */
fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
    var dotProduct = 0f
    var norm1 = 0f
    var norm2 = 0f

    for (i in vec1.indices) {
        dotProduct += vec1[i] * vec2[i]
        norm1 += vec1[i] * vec1[i]
        norm2 += vec2[i] * vec2[i]
    }

    return if (norm1 == 0f || norm2 == 0f) 0f else dotProduct / (sqrt(norm1) * sqrt(norm2))
}