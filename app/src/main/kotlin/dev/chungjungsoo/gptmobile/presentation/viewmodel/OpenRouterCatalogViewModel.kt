package dev.chungjungsoo.gptmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterModelRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject

@HiltViewModel
class OpenRouterCatalogViewModel @Inject constructor(val catalog: OpenRouterModelRepository, private val connections: SettingRepository) : ViewModel() {
    suspend fun load(connectionUid: String?, refresh: Boolean): Result<List<dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterModelItem>> {
        if (connectionUid.isNullOrBlank()) return catalog.fetchModels(refresh)
        val connection = connections.getProviderConnection(connectionUid) ?: return Result.failure(IllegalStateException("Connection unavailable"))
        val credential = ApiCredentialRotator.parseKeys(connections.getProviderCredentials(connectionUid)).firstOrNull() ?: return catalog.fetchModels(refresh)
        return catalog.fetchUserModels(connectionUid, connection.apiUrl, credential, refresh).map { it.first }
    }
}
