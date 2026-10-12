package dev.chungjungsoo.gptmobile.presentation.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.health.HEALTH_HISTORY_PERMISSION
import dev.chungjungsoo.gptmobile.data.health.HealthConnectConsentStore
import dev.chungjungsoo.gptmobile.data.health.HealthConnectLocalImport
import dev.chungjungsoo.gptmobile.data.health.HealthConnectLocalImportStore
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class HealthConnectScreenState(
    val availability: String = "checking",
    val permissions: Set<String> = emptySet(),
    val historyAvailable: Boolean = false,
    val busy: Boolean = false,
    val loading: Boolean = false,
    val importing: Boolean = false,
    val preview: JsonObject? = null,
    val localImport: HealthConnectLocalImport? = null,
    val error: String? = null
)

@HiltViewModel
class HealthConnectViewModel @Inject constructor(
    private val reader: HealthDataReader,
    private val consent: HealthConnectConsentStore,
    private val localImportStore: HealthConnectLocalImportStore,
    private val settings: SettingRepository
) : ViewModel() {
    private val mutable = MutableStateFlow(HealthConnectScreenState())
    val state = mutable.asStateFlow()
    val aiAllowed = consent.allowed
    val externalStorageAllowed = consent.externalStorageAllowed
    val features = settings.observeFeatureSettings().stateIn(viewModelScope, SharingStarted.Eagerly, AppFeatureSettings())
    val profiles = settings.observePlatformV2s().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val theme = settings.observeThemes().stateIn(viewModelScope, SharingStarted.Eagerly, ThemeSetting())
    private val mutations = Mutex()
    private var previewJob: Job? = null
    private var importJob: Job? = null
    private var accessJob: Job? = null
    private var previewGeneration = 0L

    fun refreshAccess() {
        clearPreview()
        accessJob?.cancel()
        accessJob = viewModelScope.launch {
            try {
                consent.isAllowed()
                val savedImport = localImportStore.read()
                val available = reader.availability()
                val permissions = reader.grantedPermissions()
                val historyAvailable = reader.historyAvailable()
                mutable.update { it.copy(availability = available, permissions = permissions, historyAvailable = historyAvailable, localImport = savedImport) }
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
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (previewGeneration == generation) showError("Health Connect did not respond in time. Keep this screen open and retry.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (previewGeneration == generation) showError("Could not read health statistics. Keep this screen open, check permissions, and retry.")
            } finally {
                if (previewGeneration == generation) mutable.update { it.copy(loading = false) }
            }
        }
    }

    fun importLocally(days: Int) {
        if (mutable.value.importing || days !in 1..3650) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            mutations.withLock {
                mutable.update { it.copy(importing = true, error = null) }
                try {
                    val permissionsBefore = reader.grantedPermissions()
                    check(permissionsBefore.isNotEmpty()) { "Grant at least one Health Connect read permission before importing." }
                    if (days > 30) {
                        check(reader.historyAvailable()) { "This Health Connect version does not support access to history older than 30 days." }
                        check(HEALTH_HISTORY_PERMISSION in permissionsBefore) { "Allow Health Connect history access to import data older than 30 days." }
                    }
                    val now = Instant.now()
                    val zone = ZoneId.systemDefault()
                    val start = now.atZone(zone).toLocalDate().minusDays(days - 1L).atStartOfDay(zone).toInstant()
                    val query = HealthQuery(start, now, zone)
                    val summary = reader.readLocalImport(query)
                    val status = (summary["status"] as? JsonPrimitive)?.content
                    check(status == "available" || status == "partial") {
                        if (status == "no_data") {
                            "No permitted Google Health data was found in that time range."
                        } else {
                            "Health Connect did not return importable data. Check permissions and keep the app open, then retry."
                        }
                    }
                    val metrics = summary["metrics"]?.jsonObject.orEmpty()
                    check(
                        metrics.values.any { metric ->
                            ((metric as? JsonObject)?.get("status") as? JsonPrimitive)?.content == "available"
                        }
                    ) {
                        "No permitted Google Health data was found in that time range."
                    }
                    check(reader.grantedPermissions() == permissionsBefore) { "Health Connect permissions changed during import. Nothing was saved." }
                    val snapshot = HealthConnectLocalImport(Instant.now().toString(), days, summary)
                    localImportStore.save(snapshot)
                    mutable.update { it.copy(localImport = snapshot, error = null) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutable.update { it.copy(error = error.message?.take(240) ?: "Could not import local health data. Retry while the app is open.") }
                } finally {
                    mutable.update { it.copy(importing = false) }
                }
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        mutable.update { it.copy(importing = false) }
    }

    fun clearLocalImport() = mutate {
        localImportStore.clear()
        mutable.update { it.copy(localImport = null) }
    }

    fun setAiAllowed(allowed: Boolean) = mutate {
        if (!allowed) clearPreview()
        consent.setAllowed(allowed)
    }

    fun setExternalStorageAllowed(allowed: Boolean) = mutate { consent.setExternalStorageAllowed(allowed) }

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
        cancelImport()
        consent.setAllowed(false)
        localImportStore.clear()
        MarketplaceMutations.mutex.withLock {
            settings.updateFeatureSettings(settings.getFeatureSettings().withToolPluginEnabled(ToolPluginId.HEALTH_CONNECT, false))
        }
        reader.revokePermissions()
        mutable.update { it.copy(permissions = emptySet(), localImport = null) }
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
