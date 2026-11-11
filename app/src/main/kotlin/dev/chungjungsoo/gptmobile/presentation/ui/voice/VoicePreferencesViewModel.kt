package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelPack
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStore
import dev.chungjungsoo.gptmobile.data.voice.VoicePreferenceWriter
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
internal class VoicePreferencesViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    private val _modelStores = MutableStateFlow<VoiceModelStores?>(null)
    val modelStores = _modelStores.asStateFlow()
    private var modelInstall: Job? = null
    private var kokoroInstall: Job? = null
    private val _preferences = MutableStateFlow<VoicePreferences?>(null)
    val preferences = _preferences.asStateFlow()
    private val writer = VoicePreferenceWriter<VoicePreferences>(viewModelScope) { value ->
        withContext(Dispatchers.IO) {
            context.getSharedPreferences("voice_presentation", Context.MODE_PRIVATE).edit()
                .putString("language", value.language).putBoolean("online", value.online)
                .putBoolean("captions", value.captions).putBoolean("review", value.review)
                .putBoolean("automatic", value.automatic).putFloat("rate", value.rate)
                .putString("voice", value.voice)
                .putString("recognition_backend", value.recognitionBackend.name)
                .putString("kokoro_voice", value.kokoroVoice?.name)
                .putBoolean("spoken_replies", value.spokenReplies)
                .putInt("kokoro_threads", value.kokoroThreads).apply()
        }
    }

    init {
        viewModelScope.launch {
            val stores = withContext(Dispatchers.IO) {
                val directory = File(context.noBackupFilesDir, "voice-models")
                VoiceModelStores(LocalVoiceModelStore(directory), KokoroModelPack.store(directory))
            }
            _modelStores.value = stores
            stores.moonshine.refresh()
        }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val saved = context.getSharedPreferences("voice_presentation", Context.MODE_PRIVATE)
                VoicePreferences(
                    language = saved.getString("language", java.util.Locale.getDefault().toLanguageTag()).orEmpty(),
                    online = saved.getBoolean("online", false),
                    captions = saved.getBoolean("captions", true),
                    review = saved.getBoolean("review", false),
                    automatic = saved.getBoolean("automatic", true),
                    rate = saved.getFloat("rate", 1f).takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f,
                    voice = saved.getString("voice", "").orEmpty(),
                    recognitionBackend = VoiceRecognitionBackend.fromSaved(saved.getString("recognition_backend", null)),
                    kokoroVoice = KokoroVoice.fromSaved(saved.getString("kokoro_voice", null)),
                    spokenReplies = saved.getBoolean("spoken_replies", true),
                    kokoroThreads = saved.getInt("kokoro_threads", 2).coerceIn(2, 4)
                )
            }
            _preferences.compareAndSet(null, loaded)
        }
    }

    fun save(value: VoicePreferences) {
        _preferences.value = value
        writer.save(value)
    }

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
