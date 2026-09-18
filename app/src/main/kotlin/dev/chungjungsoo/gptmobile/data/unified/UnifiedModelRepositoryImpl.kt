package dev.chungjungsoo.gptmobile.data.unified

import dev.chungjungsoo.gptmobile.data.database.dao.PlatformV2Dao
import dev.chungjungsoo.gptmobile.data.repository.ModelCatalogRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.domain.unified.UnifiedModel
import dev.chungjungsoo.gptmobile.domain.unified.UnifiedModelProvider
import dev.chungjungsoo.gptmobile.domain.unified.UnifiedModelRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class UnifiedModelRepositoryImpl @Inject constructor(
    private val modelCatalogRepository: ModelCatalogRepository,
    private val platformV2Dao: PlatformV2Dao,
    private val settingRepository: SettingRepository
) : UnifiedModelRepository {

    private var activeModelId: String? = null
    private var cachedModels: List<UnifiedModel> = emptyList()
    private val cacheMutex = Mutex()

    override suspend fun getAllModels(): List<UnifiedModel> = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val platforms = platformV2Dao.getAll()
            val result = mutableListOf<UnifiedModel>()

            for (platform in platforms) {
                val provider = UnifiedModelProvider.fromKey(platform.compatibleType.name)
                val catalogModels = runCatching {
                    modelCatalogRepository.getCatalog(platform).models
                }.getOrDefault(emptyList())

                if (catalogModels.isNotEmpty()) {
                    catalogModels.forEach { catModel ->
                        result.add(
                            UnifiedModel(
                                id = "${platform.uid}::${catModel.id}",
                                name = catModel.name.ifBlank { catModel.id },
                                modelId = catModel.id,
                                provider = provider,
                                platformUid = platform.uid,
                                description = catModel.description,
                                contextWindow = catModel.contextWindow ?: 0,
                                isDefault = platform.model == catModel.id,
                                isActive = (activeModelId == null && platform.model == catModel.id) || activeModelId == "${platform.uid}::${catModel.id}"
                            )
                        )
                    }
                } else if (platform.model.isNotBlank()) {
                    result.add(
                        UnifiedModel(
                            id = "${platform.uid}::${platform.model}",
                            name = platform.model,
                            modelId = platform.model,
                            provider = provider,
                            platformUid = platform.uid,
                            description = "Default model for ${platform.name}",
                            isDefault = true,
                            isActive = (activeModelId == null) || activeModelId == "${platform.uid}::${platform.model}"
                        )
                    )
                }
            }
            cachedModels = result
            result
        }
    }

    override suspend fun getModelById(id: String): UnifiedModel? {
        val models = cacheMutex.withLock { cachedModels }.ifEmpty { getAllModels() }
        return models.firstOrNull { it.id == id }
    }

    override suspend fun getActiveModel(): UnifiedModel? {
        val all = cacheMutex.withLock { cachedModels }.ifEmpty { getAllModels() }
        return all.firstOrNull { it.id == activeModelId }
            ?: all.firstOrNull { it.isDefault }
            ?: all.firstOrNull()
    }

    override suspend fun setActiveModel(modelId: String): Boolean {
        cacheMutex.withLock {
            activeModelId = modelId
            cachedModels = cachedModels.map { it.copy(isActive = it.id == modelId) }
        }
        return true
    }

    override suspend fun setDefaultModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        val parts = modelId.split("::", limit = 2)
        if (parts.size == 2) {
            val platformUid = parts[0]
            val actualModelId = parts[1]
            val platform = platformV2Dao.getByUid(platformUid)
            if (platform != null) {
                platformV2Dao.update(platform.copy(model = actualModelId))
                cacheMutex.withLock {
                    activeModelId = modelId
                    cachedModels = cachedModels.map {
                        it.copy(
                            isDefault = it.id == modelId,
                            isActive = it.id == modelId
                        )
                    }
                }
                return@withContext true
            }
        }
        false
    }

    override suspend fun getModelsByProvider(provider: UnifiedModelProvider): List<UnifiedModel> {
        val all = cacheMutex.withLock { cachedModels }.ifEmpty { getAllModels() }
        return all.filter { it.provider.key == provider.key }
    }

    override suspend fun filterModels(query: String): List<UnifiedModel> {
        val trimmed = query.trim().lowercase()
        val all = cacheMutex.withLock { cachedModels }.ifEmpty { getAllModels() }
        if (trimmed.isEmpty()) return all
        return all.filter {
            it.name.lowercase().contains(trimmed) ||
                it.modelId.lowercase().contains(trimmed) ||
                (it.description?.lowercase()?.contains(trimmed) == true) ||
                it.provider.displayName.lowercase().contains(trimmed)
        }
    }
}
