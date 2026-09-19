package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.model.OllamaModel

/**
 * Repository for fetching and searching Ollama models.
 */
interface OllamaModelRepository {
    suspend fun fetchAllModels(): List<OllamaModel>
    suspend fun searchModels(query: String): List<OllamaModel>
}