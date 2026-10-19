package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.database.dao.ToolConnectionDao
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeConfiguration
import dev.chungjungsoo.gptmobile.data.openrouter.account.SettingsHomeWalletState
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsHomeViewModel @Inject constructor(
    private val settings: SettingRepository,
    runDao: AgentRunDao,
    private val toolConnections: ToolConnectionDao,
    private val walletLocal: dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterWalletLocalStore,
    homeTools: dev.chungjungsoo.gptmobile.data.home.HomeToolStatusRepository
) : ViewModel() {
    val activity = runDao.observeHomeActivity(250)
        .map { SettingsHomeActivityState(it, loaded = true) }
        .catch { emit(SettingsHomeActivityState(loaded = true, unavailable = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsHomeActivityState())

    val nowSeconds = flow {
        while (true) {
            emit(System.currentTimeMillis() / 1000)
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis() / 1000)

    val toolGroups = homeTools.observe(settings.observeFeatureSettings(), nowSeconds)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _connections = MutableStateFlow<List<ToolConnection>?>(null)
    val connections = _connections.asStateFlow()
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError = _saveError.asStateFlow()
    val wallet = kotlinx.coroutines.flow.combine(walletLocal.state, settings.observeFeatureSettings(), settings.observeProviderConnections()) { local, features, providers ->
        val connection = homeWalletConnection(features.settingsHome, providers)
        val binding = local.bindings[connection?.uid]
        val observation = local.observations[connection?.uid]?.takeIf { binding?.associated == true && it.bindingRevision == binding.revision && binding.endpoint == connection?.apiUrl?.trimEnd('/') }
        SettingsHomeWalletState(
            snapshot = observation?.snapshot?.copy(connectionName = connection?.name.orEmpty(), connectionRevision = connection?.updatedAt ?: 0),
            failure = observation?.failure,
            accessSaved = binding != null,
            associationValid = binding?.associated == true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsHomeWalletState())

    fun refreshLocalSummary() {
        viewModelScope.launch {
            try {
                _connections.value = toolConnections.listConnections()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _connections.value = null
            }
        }
    }

    fun setItem(id: String, selected: Boolean) = updateConfiguration { it.withItem(id, selected) }

    fun selectToolSubjects(subjects: List<dev.chungjungsoo.gptmobile.data.model.HomeToolSubject>) = updateConfiguration { it.copy(selectedToolSubjects = subjects) }

    fun selectProfiles(uids: List<String>) = updateConfiguration { it.copy(selectedProfileUids = uids) }

    fun selectWalletConnection(uid: String) {
        updateConfiguration { it.copy(walletConnectionUid = uid) }
    }

    fun hideWalletAmounts(hidden: Boolean) = updateConfiguration { it.copy(hideWalletAmounts = hidden) }

    private fun updateConfiguration(transform: (SettingsHomeConfiguration) -> SettingsHomeConfiguration) {
        viewModelScope.launch {
            try {
                settings.mutateFeatureSettings { it.copy(settingsHome = transform(it.settingsHome).normalized()) }
                _saveError.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _saveError.value = "Could not save Home tab choices. Your previous choices are still active."
            }
        }
    }
}
