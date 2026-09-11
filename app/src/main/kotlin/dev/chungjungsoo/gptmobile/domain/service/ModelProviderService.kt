package dev.chungjungsoo.gptmobile.domain.service

import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterModelItem
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterModelRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelProviderService @Inject constructor(
    private val openRouterModelRepository: OpenRouterModelRepository
) {
    suspend fun fetchOpenRouterModels(forceRefresh: Boolean = false): Result<List<OpenRouterModelItem>> {
        return openRouterModelRepository.fetchModels(forceRefresh)
    }

    suspend fun searchOpenRouterModels(query: String): Result<List<OpenRouterModelItem>> {
        val result = openRouterModelRepository.fetchModels(forceRefresh = false)
        return result.map { list ->
            if (query.isBlank()) {
                list
            } else {
                list.filter { item ->
                    (item.name?.contains(query, ignoreCase = true) == true) ||
                        item.id.contains(query, ignoreCase = true) ||
                        (item.description?.contains(query, ignoreCase = true) == true)
                }
            }
        }
    }
}
