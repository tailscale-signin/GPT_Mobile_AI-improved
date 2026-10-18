package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.database.dao.ToolConnectionDao
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeConfiguration
import dev.chungjungsoo.gptmobile.data.openrouter.account.AccountFailure
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountClient
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountException
import dev.chungjungsoo.gptmobile.data.openrouter.account.SettingsHomeWalletState
import dev.chungjungsoo.gptmobile.data.openrouter.account.readHomeWallet
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val account: OpenRouterAccountClient
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

    private val _connections = MutableStateFlow<List<ToolConnection>?>(null)
    val connections = _connections.asStateFlow()
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError = _saveError.asStateFlow()
    private val _wallet = MutableStateFlow(SettingsHomeWalletState())
    val wallet = _wallet.asStateFlow()
    private var walletJob: Job? = null
    private var walletSession = 0L

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

    fun selectWalletConnection(uid: String) {
        clearWalletSession()
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

    /** Uses the same scoped management-key check as OpenRouter's account screen; never stores the key. */
    fun readWallet(connection: ProviderConnection, credential: String) {
        clearWalletSession()
        val session = walletSession
        _wallet.value = SettingsHomeWalletState(loading = true)
        walletJob = viewModelScope.launch {
            try {
                val snapshot = account.readHomeWallet(connection.apiUrl, credential, connection.uid, connection.name, connection.updatedAt)
                val current = settings.fetchProviderConnections().find { it.uid == connection.uid }
                if (session != walletSession) return@launch
                if (current == null || current.updatedAt != connection.updatedAt || current.apiUrl != connection.apiUrl) {
                    clearWalletSession()
                } else {
                    _wallet.value = SettingsHomeWalletState(snapshot = snapshot)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (session == walletSession) _wallet.value = SettingsHomeWalletState(failure = (failure as? OpenRouterAccountException)?.category ?: AccountFailure.NETWORK)
            }
        }
    }

    fun clearWalletSession() {
        walletSession++
        walletJob?.cancel()
        walletJob = null
        _wallet.value = SettingsHomeWalletState()
    }

    override fun onCleared() {
        clearWalletSession()
        super.onCleared()
    }
}
