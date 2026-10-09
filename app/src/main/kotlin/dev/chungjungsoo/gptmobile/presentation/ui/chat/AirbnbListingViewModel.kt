package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.agent.tool.DeviceLocationProvider
import dev.chungjungsoo.gptmobile.data.agent.tool.MapCoordinate
import dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListing
import dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListings
import dev.chungjungsoo.gptmobile.data.airbnb.AirbnbMediaRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class AirbnbDetailState(val listing: AirbnbListing? = null, val loading: Boolean = false, val notice: String? = null)

@HiltViewModel
class AirbnbListingViewModel @Inject constructor(private val tools: AgentToolResolver, private val media: AirbnbMediaRepository, private val location: DeviceLocationProvider, private val settings: SettingRepository) : ViewModel() {
    private val current = MutableStateFlow(AirbnbDetailState())
    internal val state = current.asStateFlow()
    private var lookup: Job? = null
    private var requestVersion = 0
    private val details = linkedMapOf<List<Any?>, AirbnbListing>()
    private val retainedDetails = MutableStateFlow<Map<List<Any?>, AirbnbListing>>(emptyMap())
    internal val cachedDetails = retainedDetails.asStateFlow()

    internal suspend fun currentPosition(owner: String?): MapCoordinate? {
        val features = settings.getFeatureSettings()
        if (!features.deviceLocationTool || !location.hasPermission()) return null
        val profile = settings.fetchPlatformV2s().firstOrNull { it.uid == owner } ?: return null
        if (!profile.enabled || profile.disableAllTools || profile.disableLocalTools) return null
        val fix = location.getCurrentLocation(timeoutMillis = 10_000) ?: return null
        return MapCoordinate(fix.latitude, fix.longitude).takeIf { it.isValid }
    }

    suspend fun photo(owner: String?, url: String): ByteArray? = try {
        media.photo(owner, url)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    fun open(owner: String?, listing: AirbnbListing, refresh: Boolean = false) {
        val version = ++requestVersion
        lookup?.cancel()
        val key = listOf(owner) + AirbnbListings.stayKey(listing)
        val retained = details[key].takeUnless { refresh }
        current.value = AirbnbDetailState(retained?.let { AirbnbListings.merge(listing, it) } ?: listing, loading = owner != null && retained == null)
        if (owner == null || retained != null) return
        lookup = viewModelScope.launch {
            try {
                val fetched = tools.airbnbListingDetails(owner, listing)
                if (version == requestVersion) {
                    val merged = fetched?.let { AirbnbListings.merge(listing, it) } ?: listing
                    if (fetched != null) {
                        details[key] = merged
                        while (details.size > 30) details.remove(details.keys.first())
                        retainedDetails.value = details.toMap()
                    }
                    current.value = AirbnbDetailState(merged, notice = if (fetched == null) "Additional provider details are unavailable. Open Airbnb to verify photos, availability and the final price." else null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (version == requestVersion) current.value = AirbnbDetailState(listing, notice = "Could not load additional details. Check connectivity and the Airbnb profile toggle, or open the listing.")
            }
        }
    }

    fun close() {
        requestVersion++
        lookup?.cancel()
        current.value = AirbnbDetailState()
    }
}
