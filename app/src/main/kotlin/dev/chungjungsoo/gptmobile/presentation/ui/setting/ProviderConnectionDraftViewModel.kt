package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Secrets survive configuration changes in memory, never in saved-state bundles. */
internal class ProviderConnectionDraftViewModel : ViewModel() {
    var keys by mutableStateOf(listOf(""))
    var loaded by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    private var loading = false
    private var originalKeys = listOf("")
    val dirty: Boolean get() = loaded && keys != originalKeys

    fun load(read: suspend () -> List<String>) {
        if (loaded || loading) return
        loading = true
        viewModelScope.launch {
            try {
                keys = read()
                originalKeys = keys
                loaded = true
                status = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = "Could not load saved API keys. Reopen this page to try again."
            } finally {
                loading = false
            }
        }
    }

    fun save(write: suspend (List<String>) -> Unit, onSaved: () -> Unit) {
        if (!loaded || saving) return
        saving = true
        val snapshot = keys.toList()
        viewModelScope.launch {
            try {
                write(snapshot)
                originalKeys = snapshot
                status = null
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = "Could not save the connection. Your edits are still here; try again."
            } finally {
                saving = false
            }
        }
    }

    fun discard() {
        keys = emptyList()
        originalKeys = emptyList()
        loaded = false
    }
}
