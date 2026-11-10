package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStore
import dev.chungjungsoo.gptmobile.data.voice.VoicePreferenceWriter
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
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
    val automatic: Boolean = false,
    val rate: Float = 1f,
    val voice: String = "",
    val recognitionBackend: VoiceRecognitionBackend = VoiceRecognitionBackend.LOCAL_MOONSHINE
)

/** No preference open/read/write may block composition or microphone startup. */
@HiltViewModel
internal class VoicePreferencesViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    val localModels = LocalVoiceModelStore(context)
    val localModelState = localModels.state
    private var modelInstall: Job? = null
    private val _preferences = MutableStateFlow<VoicePreferences?>(null)
    val preferences = _preferences.asStateFlow()
    private val writer = VoicePreferenceWriter<VoicePreferences>(viewModelScope) { value ->
        withContext(Dispatchers.IO) {
            context.getSharedPreferences("voice_presentation", Context.MODE_PRIVATE).edit()
                .putString("language", value.language).putBoolean("online", value.online)
                .putBoolean("captions", value.captions).putBoolean("review", value.review)
                .putBoolean("automatic", value.automatic).putFloat("rate", value.rate)
                .putString("voice", value.voice)
                .putString("recognition_backend", value.recognitionBackend.name).apply()
        }
    }

    init {
        viewModelScope.launch { localModels.refresh() }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val saved = context.getSharedPreferences("voice_presentation", Context.MODE_PRIVATE)
                VoicePreferences(
                    language = saved.getString("language", java.util.Locale.getDefault().toLanguageTag()).orEmpty(),
                    online = saved.getBoolean("online", false),
                    captions = saved.getBoolean("captions", true),
                    review = saved.getBoolean("review", false),
                    automatic = saved.getBoolean("automatic", false),
                    rate = saved.getFloat("rate", 1f).takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f,
                    voice = saved.getString("voice", "").orEmpty(),
                    recognitionBackend = VoiceRecognitionBackend.fromSaved(saved.getString("recognition_backend", null))
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
        modelInstall = viewModelScope.launch { localModels.install() }
    }

    fun cancelLocalModelInstall() {
        localModels.cancelInstall()
        modelInstall?.cancel()
    }

    fun removeLocalModel() {
        viewModelScope.launch { localModels.remove() }
    }

    override fun onCleared() {
        localModels.cancelInstall()
        super.onCleared()
    }
}
