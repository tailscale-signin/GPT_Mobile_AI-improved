package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.presentation.ui.setting.BenchmarkExportArtifact
import dev.chungjungsoo.gptmobile.presentation.ui.setting.BenchmarkExportArtifactStore
import dev.chungjungsoo.gptmobile.presentation.ui.setting.BenchmarkExportContent
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Only a small receipt identifier enters saved state. Encoding and copies outlive rotation. */
internal class VoiceDiagnosticExportViewModel(
    private val content: BenchmarkExportContent,
    private val artifacts: BenchmarkExportArtifactStore,
    private val savedState: SavedStateHandle,
    private val openDestination: (String) -> OutputStream?
) : ViewModel() {
    private val mutable = MutableStateFlow(VoiceDiagnosticExportState())
    val state = mutable.asStateFlow()
    private val restoring = viewModelScope.launch {
        try {
            val retainedId = savedState.get<String>(RECEIPT_ID)
            val identified = try {
                retainedId?.let { artifacts.find(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val retained = identified ?: artifacts.latest()
            val matching = retained?.takeIf { it.content == content && !it.detailed && !it.includesPrivateProfileDetails }
            savedState[RECEIPT_ID] = matching?.id
            mutable.update { it.copy(loaded = true, retained = matching) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutable.update { it.copy(loaded = true, notice = "Saved export recovery could not be checked. Try again.") }
        }
    }

    fun request(write: suspend (OutputStream) -> Unit) {
        val current = mutable.value
        if (!current.loaded || current.busy || current.choosingDestination || current.destinationRequest != null) return
        if (current.retained != null) {
            mutable.update { it.copy(destinationRequest = current.retained.id, notice = null) }
            return
        }
        mutable.update { it.copy(preparing = true, notice = null) }
        viewModelScope.launch {
            try {
                val complete = artifacts.prepare(detailed = false, contentKind = content, write = write)
                savedState[RECEIPT_ID] = complete.id
                mutable.update { it.copy(retained = complete, destinationRequest = complete.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(notice = "Export preparation failed. Free some space and try again.") }
            } finally {
                mutable.update { it.copy(preparing = false) }
            }
        }
    }

    fun takeDestinationRequest(id: String): BenchmarkExportArtifact? {
        val current = mutable.value
        if (current.destinationRequest != id || current.retained?.id != id || current.busy) return null
        mutable.update { it.copy(destinationRequest = null, choosingDestination = true) }
        return current.retained
    }

    fun destinationFailed() {
        mutable.update { it.copy(choosingDestination = false, notice = "The file picker could not be opened. Your complete export is retained; try another files app.") }
    }

    fun destinationSelected(destination: String?) {
        mutable.update { it.copy(choosingDestination = false, destinationRequest = null) }
        if (destination == null) {
            mutable.update { it.copy(notice = "Save canceled. Your complete export is retained for another attempt.") }
            return
        }
        if (mutable.value.busy) return
        mutable.update { it.copy(saving = true, notice = null) }
        viewModelScope.launch {
            try {
                restoring.join()
                val retained = checkNotNull(mutable.value.retained)
                artifacts.saveTo(retained) { openDestination(destination) }
                savedState[RECEIPT_ID] = null
                mutable.update { it.copy(retained = null, notice = "Export saved.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(notice = "Saving failed. Your complete export is retained; choose another destination and try again.") }
            } finally {
                mutable.update { it.copy(saving = false) }
            }
        }
    }

    fun discard() {
        val current = mutable.value
        val retained = current.retained ?: return
        if (current.busy || current.choosingDestination || current.destinationRequest != null) return
        mutable.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                artifacts.complete(retained)
                savedState[RECEIPT_ID] = null
                mutable.update { it.copy(retained = null, notice = "Retained export discarded. You can export the latest measurements.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(notice = "The retained export could not be discarded.") }
            } finally {
                mutable.update { it.copy(saving = false) }
            }
        }
    }

    companion object {
        internal const val RECEIPT_ID = "voice_export_receipt_id"
    }
}

internal data class VoiceDiagnosticExportState(
    val loaded: Boolean = false,
    val preparing: Boolean = false,
    val saving: Boolean = false,
    val retained: BenchmarkExportArtifact? = null,
    val destinationRequest: String? = null,
    val choosingDestination: Boolean = false,
    val notice: String? = null
) {
    val busy: Boolean get() = preparing || saving
}
