package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelPack
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStore
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class VoicePreferences(
    val language: String = java.util.Locale.getDefault().toLanguageTag(),
    val online: Boolean = false,
    val captions: Boolean = true,
    val review: Boolean = false,
    val automatic: Boolean = true,
    val rate: Float = 1f,
    val voice: String = "",
    val recognitionBackend: VoiceRecognitionBackend = VoiceRecognitionBackend.LOCAL_MOONSHINE,
    val kokoroVoice: KokoroVoice? = null,
    val spokenReplies: Boolean = true,
    val kokoroThreads: Int = 2
)

internal data class VoiceModelStores(val moonshine: LocalVoiceModelStore, val kokoro: LocalVoiceModelStore)

/** No preference open/read/write may block composition or microphone startup. */
@HiltViewModel
internal class VoicePreferencesViewModel @Inject constructor(@ApplicationContext private val context: Context, private val store: VoicePreferenceStore) : ViewModel() {
    private val _modelStores = MutableStateFlow<VoiceModelStores?>(null)
    val modelStores = _modelStores.asStateFlow()
    private var modelInstall: Job? = null
    private var kokoroInstall: Job? = null
    val preferences = store.preferences
    val saveError = store.saveError

    init {
        viewModelScope.launch {
            val stores = withContext(Dispatchers.IO) {
                val directory = File(context.noBackupFilesDir, "voice-models")
                VoiceModelStores(LocalVoiceModelStore(directory), KokoroModelPack.store(directory))
            }
            _modelStores.value = stores
            coroutineScope {
                launch { stores.moonshine.refresh() }
                launch { stores.kokoro.refresh() }
            }
        }
    }

    fun save(value: VoicePreferences) = store.save(value)

    fun installLocalModel() {
        if (modelInstall?.isActive == true) return
        val localModels = _modelStores.value?.moonshine ?: return
        modelInstall = viewModelScope.launch { localModels.install() }
    }

    fun cancelLocalModelInstall() {
        _modelStores.value?.moonshine?.cancelInstall()
        modelInstall?.cancel()
    }

    fun removeLocalModel() {
        viewModelScope.launch { _modelStores.value?.moonshine?.remove() }
    }

    fun installKokoroModel() {
        if (kokoroInstall?.isActive == true) return
        val kokoroModels = _modelStores.value?.kokoro ?: return
        kokoroInstall = viewModelScope.launch { kokoroModels.install() }
    }

    fun cancelKokoroInstall() {
        _modelStores.value?.kokoro?.cancelInstall()
        kokoroInstall?.cancel()
    }

    fun removeKokoroModel() {
        viewModelScope.launch { _modelStores.value?.kokoro?.remove() }
    }

    override fun onCleared() {
        _modelStores.value?.moonshine?.cancelInstall()
        _modelStores.value?.kokoro?.cancelInstall()
        super.onCleared()
    }
}
