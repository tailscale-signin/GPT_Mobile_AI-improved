package dev.chungjungsoo.gptmobile.presentation.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import dev.chungjungsoo.gptmobile.data.marketplace.MarketplacePackageStore
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MarketplaceUiState(
    val downloadedIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),
    val removingIds: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val message: String? = null
)

/** Own downloads outside composition so rotation cannot cancel or duplicate them. */
@HiltViewModel
class MarketplaceViewModel @Inject constructor(private val store: MarketplacePackageStore) : ViewModel() {
    private val _uiState = MutableStateFlow(MarketplaceUiState())
    val uiState = _uiState.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val initialization = viewModelScope.launch {
        try {
            val ids = store.downloadedIds()
            _uiState.update { it.copy(downloadedIds = ids) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            showMessage("Unable to check downloaded packages. You can retry a download.")
        }
    }

    fun download(entry: GitHubMarketplacePackage) = runOperation(entry, removing = false)

    fun remove(entry: GitHubMarketplacePackage) = runOperation(entry, removing = true)

    fun cancelDownload(id: String) {
        if (id in _uiState.value.downloadingIds) jobs[id]?.cancel()
    }

    fun showMessage(message: String?) {
        _uiState.update { it.copy(message = message) }
    }

    suspend fun exportBytes(entry: GitHubMarketplacePackage): ByteArray = store.exportBytes(entry)

    private fun runOperation(entry: GitHubMarketplacePackage, removing: Boolean) {
        if (jobs.containsKey(entry.id)) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                // Do not let an older startup scan overwrite a new download/removal.
                initialization.join()
                if (removing) store.remove(entry) else store.download(entry)
                _uiState.update {
                    it.copy(downloadedIds = if (removing) it.downloadedIds - entry.id else it.downloadedIds + entry.id)
                }
            } catch (cancelled: CancellationException) {
                if (!removing) {
                    _uiState.update { it.copy(errors = it.errors + (entry.id to "Download cancelled. Retry when ready.")) }
                }
                throw cancelled
            } catch (_: Exception) {
                val message = if (removing) "Unable to remove the package. Retry when ready." else "Download or integrity check failed. Retry when ready."
                _uiState.update { it.copy(errors = it.errors + (entry.id to message)) }
            }
        }
        jobs[entry.id] = job
        _uiState.update {
            it.copy(
                downloadingIds = if (removing) it.downloadingIds else it.downloadingIds + entry.id,
                removingIds = if (removing) it.removingIds + entry.id else it.removingIds,
                errors = it.errors - entry.id
            )
        }
        // Also runs if cancellation happens before the coroutine body starts.
        job.invokeOnCompletion {
            jobs.remove(entry.id)
            _uiState.update { it.copy(downloadingIds = it.downloadingIds - entry.id, removingIds = it.removingIds - entry.id) }
        }
        job.start()
    }
}
