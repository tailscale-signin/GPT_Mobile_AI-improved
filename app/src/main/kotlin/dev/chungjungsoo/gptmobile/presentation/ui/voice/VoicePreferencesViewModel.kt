package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelPack
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelVariant
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStore
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeKokoroBucket
import dev.chungjungsoo.gptmobile.data.voice.VoicePauseDuration
import dev.chungjungsoo.gptmobile.data.voice.VoicePerformanceMode
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class VoicePreferences(
    val language: String = java.util.Locale.getDefault().toLanguageTag(),
    val online: Boolean = false,
    val captions: Boolean = true,
    val review: Boolean = false,
    val automatic: Boolean = true,
    val rate: Float = 1.1f,
    val voice: String = "",
    val recognitionBackend: VoiceRecognitionBackend = VoiceRecognitionBackend.LOCAL_MOONSHINE,
    val kokoroVoice: KokoroVoice? = null,
    val spokenReplies: Boolean = true,
    val kokoroThreads: Int = 2,
    val buttonSounds: Boolean = false,
    val pause: VoicePauseDuration = VoicePauseDuration.NATURAL,
    val performance: VoicePerformanceMode = VoicePerformanceMode.AUTO,
    val speakWhileGenerating: Boolean = true,
    val kokoroBucket: MediaPipeKokoroBucket = MediaPipeKokoroBucket.AUTO,
    val incrementalSpeech: Boolean = true,
    val nativeModelVariant: KokoroModelVariant = KokoroModelVariant.INT8,
    val tuning: VoiceTuningPreferences = VoiceTuningPreferences()
)

internal data class VoiceModelStores(val moonshine: LocalVoiceModelStore, val kokoro: LocalVoiceModelStore, val kokoroVariant: KokoroModelVariant = KokoroModelVariant.INT8)

/** No preference open/read/write may block composition or microphone startup. */
@HiltViewModel
internal class VoicePreferencesViewModel @Inject constructor(@ApplicationContext private val context: Context, private val store: VoicePreferenceStore) : ViewModel() {
    private val _modelStores = MutableStateFlow<VoiceModelStores?>(null)
    val modelStores = _modelStores.asStateFlow()
    private var modelInstall: Job? = null
    private val kokoroInstalls = mutableMapOf<KokoroModelVariant, Job>()
    private var kokoroStores: Map<KokoroModelVariant, LocalVoiceModelStore> = emptyMap()
    val preferences = store.preferences
    val saveError = store.saveError

    init {
        viewModelScope.launch {
            // Resolve the saved variant before exposing a runtime. No temporary INT8
            // owner is created merely because preference hydration is asynchronous.
            val selected = preferences.filterNotNull().first().nativeModelVariant
            val stores = withContext(Dispatchers.IO) {
                val directory = File(context.noBackupFilesDir, "voice-models")
                val variants = KokoroModelVariant.entries.associateWith { KokoroModelPack.store(directory, variant = it) }
                kokoroStores = variants
                VoiceModelStores(LocalVoiceModelStore(directory), variants.getValue(selected), selected)
            }
            _modelStores.value = stores
            coroutineScope {
                launch { stores.moonshine.refresh(forceVerification = false) }
                launch { stores.kokoro.refresh(forceVerification = false) }
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
        val selected = _modelStores.value ?: return
        if (kokoroInstalls[selected.kokoroVariant]?.isActive == true) return
        kokoroInstalls[selected.kokoroVariant] = viewModelScope.launch { selected.kokoro.install() }
    }

    fun cancelKokoroInstall() {
        val selected = _modelStores.value ?: return
        selected.kokoro.cancelInstall()
        kokoroInstalls[selected.kokoroVariant]?.cancel()
    }

    fun removeKokoroModel() {
        val selected = _modelStores.value?.kokoro ?: return
        viewModelScope.launch { selected.remove() }
    }

    /** The visible runtime must retire before the caller publishes another model store. */
    fun selectKokoroVariant(variant: KokoroModelVariant) {
        val previous = _modelStores.value ?: return
        val selected = kokoroStores.getValue(variant)
        _modelStores.value = previous.copy(kokoro = selected, kokoroVariant = variant)
        viewModelScope.launch { selected.refresh(forceVerification = false) }
    }

    // ViewModel-owned install coroutines cancel with viewModelScope. The shared
    // model store may instead belong to the durable installer; leaving this screen
    // must not cancel that worker's download.
}
