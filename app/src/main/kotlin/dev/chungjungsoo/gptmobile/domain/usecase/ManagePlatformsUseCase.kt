package dev.chungjungsoo.gptmobile.domain.usecase

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.domain.model.SortType
import javax.inject.Inject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ManagePlatformsUseCase @Inject constructor(
    private val settingRepository: SettingRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun toggleFavoritePlatform(platformId: Int, isFavorite: Boolean) {
        val platform = settingRepository.getPlatformV2ById(platformId) ?: return
        settingRepository.updatePlatformV2(platform.copy(isFavorite = isFavorite))
    }

    suspend fun updateLabels(platformId: Int, labels: List<String>) {
        val platform = settingRepository.getPlatformV2ById(platformId) ?: return
        val labelsJson = json.encodeToString(labels)
        settingRepository.updatePlatformV2(platform.copy(labels = labelsJson))
    }

    fun parseLabels(labelsJson: String?): List<String> {
        if (labelsJson.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<String>>(labelsJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun sortPlatforms(platforms: List<PlatformV2>, sortType: SortType): List<PlatformV2> {
        return when (sortType) {
            SortType.ENABLED -> platforms.sortedWith(
                compareByDescending<PlatformV2> { it.enabled }
                    .thenByDescending { it.isFavorite }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
            SortType.FAVORITES -> platforms.sortedWith(
                compareByDescending<PlatformV2> { it.isFavorite }
                    .thenByDescending { it.enabled }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
            SortType.NAME -> platforms.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
        }
    }
}
