package dev.chungjungsoo.gptmobile.presentation.ui.universalmarketplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceRegistry
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.universalmarketplace.MarketplaceContract
import dev.chungjungsoo.gptmobile.data.universalmarketplace.MarketplaceSavesRepository
import dev.chungjungsoo.gptmobile.data.universalmarketplace.MarketplaceSearchRequest
import dev.chungjungsoo.gptmobile.data.universalmarketplace.MarketplaceSearchResult
import dev.chungjungsoo.gptmobile.data.universalmarketplace.UniversalMarketplaceCoordinator
import dev.chungjungsoo.gptmobile.data.universalmarketplace.UniversalMarketplaceSettings
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class UniversalMarketplaceViewModel @Inject constructor(
    private val registry: NativeMarketplaceRegistry,
    private val settings: SettingRepository,
    private val saves: MarketplaceSavesRepository
) : ViewModel() {
    private val entry = requireNotNull(GitHubMarketplaceCatalog.find(MarketplaceContract.INSTALLATION))
    val installation = registry.state
    val searches = saves.searches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bookmarks = saves.bookmarks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _result = MutableStateFlow<MarketplaceSearchResult?>(null)
    val result = _result.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private var searchJob: Job? = null
    private var generation = 0L

    init {
        action { registry.load() }
        viewModelScope.launch {
            combine(registry.state, settings.observeFeatureSettings()) { installed, features ->
                installed[entry.id]?.enabled == true && features.isToolPluginEnabled(entry.id) && features.isToolPluginEnabled(ToolServiceCatalog.forPackage(entry).id)
            }.collect { enabled -> if (!enabled) cancel() }
        }
    }

    private suspend fun permitted() = registry.load()[entry.id]?.enabled == true &&
        settings.getFeatureSettings().let {
            it.isToolPluginEnabled(entry.id) && it.isToolPluginEnabled(ToolServiceCatalog.forPackage(entry).id)
        }

    fun search(request: MarketplaceSearchRequest) {
        cancel()
        val acceptedGeneration = generation
        _result.value = null
        _message.value = null
        _loading.value = true
        searchJob = viewModelScope.launch {
            try {
                val config = registry.configuration(entry).installation.universalMarketplace
                val next = UniversalMarketplaceCoordinator().search(request, config, { permitted() && registry.configuration(entry).installation.universalMarketplace == config })
                if (generation == acceptedGeneration) _result.value = next
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == acceptedGeneration) _message.value = failure.message ?: "Search could not be prepared."
            } finally {
                if (generation == acceptedGeneration) _loading.value = false
            }
        }
    }

    fun cancel() {
        generation++
        searchJob?.cancel()
        searchJob = null
        _loading.value = false
    }
    fun configure(value: UniversalMarketplaceSettings) = action {
        registry.configureUniversal(value)
        _message.value = "Settings saved."
    }
    fun saveSearch(request: MarketplaceSearchRequest) = action {
        saves.saveSearch(request)
        _message.value = "Search saved."
    }
    fun bookmark(provider: String, url: String, note: String) = action {
        saves.saveBookmark(provider, url, note)
        _message.value = "Bookmark saved."
    }
    fun deleteSearch(id: String) = action { saves.deleteSearch(id) }
    fun deleteBookmark(id: String) = action { saves.deleteBookmark(id) }
    fun clearSaved() = action {
        saves.clear()
        _message.value = "Saved searches and bookmarks deleted."
    }
    fun message(value: String) {
        _message.value = value
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "The change could not be saved."
            }
        }
    }
}
