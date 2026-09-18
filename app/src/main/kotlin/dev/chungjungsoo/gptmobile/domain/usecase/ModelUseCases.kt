package dev.chungjungsoo.gptmobile.domain.usecase

import dev.chungjungsoo.gptmobile.domain.model.Model
import dev.chungjungsoo.gptmobile.domain.model.ModelProviderType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for fetching all available models from all providers
 */
@Singleton
class FetchAllModelsUseCase @Inject constructor(
    private val ollamaModelRepository: OllamaModelRepository,
    private val openRouterModelRepository: OpenRouterModelRepository,
    private val localRuntimeModelRepository: LocalRuntimeModelRepository
) {
    suspend fun execute(): Result<List<Model>> {
        val models = mutableListOf<Model>()

        // Fetch from all providers
        val ollamaResult = ollamaModelRepository.fetchModels()
        if (ollamaResult.isSuccess) {
            models.addAll(
                ollamaResult.getOrNull()?.map { item ->
                    Model(
                        id = item.id,
                        name = item.name ?: "Unknown",
                        provider = ModelProviderType.OLLAMA,
                        description = item.description,
                        contextWindow = item.contextWindow,
                        isFavorite = item.isFavorite,
                        isAvailable = item.isAvailable
                    )
                } ?: emptyList()
            )
        }

        val openRouterResult = openRouterModelRepository.fetchModels()
        if (openRouterResult.isSuccess) {
            models.addAll(
                openRouterResult.getOrNull()?.map { item ->
                    Model(
                        id = item.id,
                        name = item.name ?: "Unknown",
                        provider = ModelProviderType.OPENROUTER,
                        description = item.description,
                        contextWindow = item.contextWindow,
                        isFavorite = item.isFavorite,
                        isAvailable = item.isAvailable
                    )
                } ?: emptyList()
            )
        }

        val localResult = localRuntimeModelRepository.fetchModels()
        if (localResult.isSuccess) {
            models.addAll(
                localResult.getOrNull()?.map { item ->
                    Model(
                        id = item.id,
                        name = item.name ?: "Unknown",
                        provider = ModelProviderType.LOCAL_RUNTIME,
                        description = item.description,
                        contextWindow = item.contextWindow,
                        isFavorite = item.isFavorite,
                        isAvailable = item.isAvailable
                    )
                } ?: emptyList()
            )
        }

        return Result.success(models)
    }
}

/**
 * Use case for searching models across all providers
 */
@Singleton
class SearchModelsUseCase @Inject constructor(
    private val ollamaModelRepository: OllamaModelRepository,
    private val openRouterModelRepository: OpenRouterModelRepository,
    private val localRuntimeModelRepository: LocalRuntimeModelRepository
) {
    suspend fun execute(query: String): Result<List<Model>> {
        if (query.isBlank()) {
            return Result.success(emptyList())
        }

        val models = mutableListOf<Model>()

        val ollamaResult = ollamaModelRepository.fetchModels()
        if (ollamaResult.isSuccess) {
            val filtered = ollamaResult.getOrNull()?.filter { item ->
                (item.name?.contains(query, ignoreCase = true) == true) ||
                    item.id.contains(query, ignoreCase = true) ||
                    (item.description?.contains(query, ignoreCase = true) == true)
            } ?: emptyList()
            models.addAll(
                filtered.map { item ->
                    Model(
                        id = item.id,
                        name = item.name ?: "Unknown",
                        provider = ModelProviderType.OLLAMA,
                        description = item.description,
                        contextWindow = item.contextWindow,
                        isFavorite = item.isFavorite,
                        isAvailable = item.isAvailable
                    )
                }
            )
        }

        val openRouterResult = openRouterModelRepository.fetchModels()
        if (openRouterResult.isSuccess) {
            val filtered = openRouterResult.getOrNull()?.filter { item ->
                (item.name?.contains(query, ignoreCase = true) == true) ||
                    item.id.contains(query, ignoreCase = true) ||
                    (item.description?.contains(query, ignoreCase = true) == true)
            } ?: emptyList()
            models.addAll(
                filtered.map { item ->
                    Model(
                        id = item.id,
                        name = item.name ?: "Unknown",
                        provider = ModelProviderType.OPENROUTER,
                        description = item.description,
                        contextWindow = item.contextWindow,
                        isFavorite = item.isFavorite,
                        isAvailable = item.isAvailable
                    )
                }
            )
        }

        val localResult = localRuntimeModelRepository.fetchModels()
        if (localResult.isSuccess) {
            val filtered = localResult.getOrNull()?.filter { item ->
                (item.name?.contains(query, ignoreCase = true) == true) ||
                    item.id.contains(query, ignoreCase = true) ||
                    (item.description?.contains(query, ignoreCase = true) == true)
            } ?: emptyList()
            models.addAll(
                filtered.map { item ->
                    Model(
                        id = item.id,
                        name = item.name ?: "Unknown",
                        provider = ModelProviderType.LOCAL_RUNTIME,
                        description = item.description,
                        contextWindow = item.contextWindow,
                        isFavorite = item.isFavorite,
                        isAvailable = item.isAvailable
                    )
                }
            )
        }

        return Result.success(models)
    }
}

/**
 * Use case for filtering models by provider and category
 */
@Singleton
class FilterModelsUseCase @Inject constructor() {
    suspend fun execute(
        models: List<Model>,
        provider: ModelProviderType? = null,
        category: ModelCategory? = null
    ): List<Model> {
        return models.filter { model ->
            (provider == null || model.provider == provider) &&
                (category == null || model.category == category)
        }
    }
}

/**
 * Use case for setting a model as active
 */
@Singleton
class SetActiveModelUseCase @Inject constructor(
    private val modelRepository: ModelRepository
) {
    suspend fun execute(modelId: String): Result<Unit> {
        return modelRepository.setActiveModel(modelId)
    }
}
