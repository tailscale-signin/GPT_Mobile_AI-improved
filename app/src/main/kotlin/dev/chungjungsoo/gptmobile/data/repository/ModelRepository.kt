package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.domain.model.Model
import dev.chungjungsoo.gptmobile.domain.model.ModelProviderType
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for model data
 */
interface ModelRepository {
    /**
     * Get all models from all providers
     */
    fun getAllModels(): Flow<List<Model>>

    /**
     * Search models by query
     */
    suspend fun searchModels(query: String): List<Model>

    /**
     * Filter models by provider and category
     */
    suspend fun filterModels(
        provider: ModelProviderType? = null,
        category: dev.chungjungsoo.gptmobile.domain.model.ModelCategory? = null
    ): List<Model>

    /**
     * Set active model
     */
    suspend fun setActiveModel(modelId: String): Result<Unit>

    /**
     * Get active model ID
     */
    suspend fun getActiveModelId(): String?

    /**
     * Toggle favorite status for a model
     */
    suspend fun toggleFavorite(modelId: String): Result<Unit>

    /**
     * Check if model is favorite
     */
    suspend fun isFavorite(modelId: String): Boolean
}
