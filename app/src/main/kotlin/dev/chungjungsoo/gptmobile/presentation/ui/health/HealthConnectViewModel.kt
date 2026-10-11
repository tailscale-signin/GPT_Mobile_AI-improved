package dev.chungjungsoo.gptmobile.presentation.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.health.HealthConnectConsentStore
import dev.chungjungsoo.gptmobile.data.health.HealthDataReader
import dev.chungjungsoo.gptmobile.data.health.HealthQuery
import dev.chungjungsoo.gptmobile.data.marketplace.MarketplaceMutations
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class HealthConnectScreenState(
    val availability: String = "checking",
    val permissions: Set<String> = emptySet(),
    val busy: Boolean = false,
    val loading: Boolean = false,
    val preview: JsonObject? = null,
    val error: String? = null
)

@HiltViewModel
class HealthConnectViewModel @Inject constructor(
    private val reader: HealthDataReader,
    private val consent: HealthConnectConsentStore,
    private val settings: SettingRepository
) : ViewModel() {
    private val mutable = MutableStateFlow(HealthConnectScreenState())
    val state = mutable.asStateFlow()
    val aiAllowed = consent.allowed
    val features = settings.observeFeatureSettings().stateIn(viewModelScope, SharingStarted.Eagerly, AppFeatureSettings())
    val profiles = settings.observePlatformV2s().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val theme = settings.observeThemes().stateIn(viewModelScope, SharingStarted.Eagerly, ThemeSetting())
    private val mutations = Mutex()
    private var previewJob: Job? = null
    private var accessJob: Job? = null
    private var previewGeneration = 0L

    fun refreshAccess() {
        clearPreview()
        accessJob?.cancel()
        accessJob = viewModelScope.launch {
            try {
                consent.isAllowed()
                val available = reader.availability()
                val permissions = reader.grantedPermissions()
                mutable.update { it.copy(availability = available, permissions = permissions) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(availability = "unavailable", permissions = emptySet(), error = "Health Connect status could not be read. Open system settings and retry.") }
            }
        }
    }

    fun clearPreview() {
        previewGeneration++
        previewJob?.cancel()
        mutable.update { it.copy(preview = null, loading = false) }
    }

    fun preview(days: Int) {
        clearPreview()
        val generation = previewGeneration
        previewJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                val query = HealthQuery.parse(buildJsonObject { put("days", days) }, Instant.now(), ZoneId.systemDefault())
                val result = reader.read("summary", query)
                if (previewGeneration == generation) mutable.update { it.copy(preview = result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (previewGeneration == generation) showError("Could not read health statistics. Keep this screen open, check permissions, and retry.")
            } finally {
                if (previewGeneration == generation) mutable.update { it.copy(loading = false) }
            }
        }
    }

    fun setAiAllowed(allowed: Boolean) = mutate {
        if (!allowed) clearPreview()
        consent.setAllowed(allowed)
    }

    fun setEnabled(enabled: Boolean) = mutate {
        MarketplaceMutations.mutex.withLock {
            settings.updateFeatureSettings(settings.getFeatureSettings().withToolPluginEnabled(ToolPluginId.HEALTH_CONNECT, enabled))
        }
    }

    fun setProfileEnabled(uid: String, enabled: Boolean) = mutate {
        MarketplaceMutations.mutex.withLock {
            settings.updateFeatureSettings(settings.getFeatureSettings().withProfileToolPluginEnabled(uid, ToolPluginId.HEALTH_CONNECT, enabled))
        }
    }

    fun revoke() = mutate {
        clearPreview()
        consent.setAllowed(false)
        MarketplaceMutations.mutex.withLock {
            settings.updateFeatureSettings(settings.getFeatureSettings().withToolPluginEnabled(ToolPluginId.HEALTH_CONNECT, false))
        }
        reader.revokePermissions()
        mutable.update { it.copy(permissions = emptySet()) }
    }

    fun showError(message: String) {
        mutable.update { it.copy(error = message) }
    }

    private fun mutate(action: suspend () -> Unit) {
        viewModelScope.launch {
            mutations.withLock {
                mutable.update { it.copy(busy = true, error = null) }
                try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutable.update { it.copy(error = "The access change could not be completed. Review Android permissions and retry.") }
                } finally {
                    mutable.update { it.copy(busy = false) }
                }
            }
        }
    }
}
