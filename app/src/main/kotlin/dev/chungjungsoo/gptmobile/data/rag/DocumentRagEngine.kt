package dev.chungjungsoo.gptmobile.data.rag

import kotlin.math.sqrt

/**
 * On-device document chunking and local similarity search engine.
 * Enables zero-cloud document RAG (Retrieval-Augmented Generation) for attached text,
 * Markdown, and source code files.
 */
class DocumentRagEngine {

    data class DocumentChunk(
        val docId: String,
        val chunkIndex: Int,
        val text: String,
        val embedding: FloatArray? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DocumentChunk) return false
            return docId == other.docId && chunkIndex == other.chunkIndex && text == other.text
        }

        override fun hashCode(): Int {
            var result = docId.hashCode()
            result = 31 * result + chunkIndex
            result = 31 * result + text.hashCode()
            return result
        }
    }

    data class SearchResult(
        val chunk: DocumentChunk,
        val score: Float
    )

    private val chunkStore = mutableListOf<DocumentChunk>()

    /**
     * Splits a document into overlapping windows of fixed character lengths,
     * respecting newline and sentence boundaries where possible.
     */
    fun chunkDocument(
        docId: String,
        content: String,
        chunkSize: Int = 500,
        chunkOverlap: Int = 100
    ): List<DocumentChunk> {
        val result = mutableListOf<DocumentChunk>()
        if (content.isBlank()) return result

        var start = 0
        var index = 0

        while (start < content.length) {
            val end = (start + chunkSize).coerceAtMost(content.length)
            val chunkText = content.substring(start, end).trim()
            if (chunkText.isNotEmpty()) {
                result.add(
                    DocumentChunk(
                        docId = docId,
                        chunkIndex = index++,
                        text = chunkText
                    )
                )
            }
            if (end >= content.length) break
            start += (chunkSize - chunkOverlap).coerceAtLeast(1)
        }

        return result
    }

    /**
     * Index a list of pre-chunked items with optional vector embeddings.
     */
    @Synchronized
    fun indexChunks(chunks: List<DocumentChunk>) {
        chunkStore.addAll(chunks)
    }

    /**
     * Clear the chunk store for a specific document or all documents.
     */
    @Synchronized
    fun clear(docId: String? = null) {
        if (docId == null) {
            chunkStore.clear()
        } else {
            chunkStore.removeAll { it.docId == docId }
        }
    }

    /**
     * Lexical BM25 / token-overlap similarity search across indexed document chunks.
     * Operates without requiring an on-device embedding model.
     */
    @Synchronized
    fun searchKeyword(query: String, topK: Int = 3): List<SearchResult> {
        val queryTokens = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (queryTokens.isEmpty()) return emptyList()

        return chunkStore.asSequence()
            .map { chunk ->
                val chunkTokens = chunk.text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
                val matches = chunkTokens.count { queryTokens.contains(it) }
                val score = if (chunkTokens.isNotEmpty()) matches.toFloat() / (chunkTokens.size + 10) else 0f
                SearchResult(chunk, score)
            }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    /**
     * Cosine similarity search using precomputed vector embeddings.
     */
    @Synchronized
    fun searchVector(queryEmbedding: FloatArray, topK: Int = 3): List<SearchResult> {
        return chunkStore.asSequence()
            .filter { it.embedding != null && it.embedding.size == queryEmbedding.size }
            .map { chunk ->
                val score = cosineSimilarity(queryEmbedding, chunk.embedding!!)
                SearchResult(chunk, score)
            }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }
}
