package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
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
    val voice: String = ""
)

/** No preference open/read/write may block composition or microphone startup. */
@HiltViewModel
internal class VoicePreferencesViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    private val _preferences = MutableStateFlow<VoicePreferences?>(null)
    val preferences = _preferences.asStateFlow()

    init {
        viewModelScope.launch {
            _preferences.value = withContext(Dispatchers.IO) {
                val saved = context.getSharedPreferences("voice_presentation", Context.MODE_PRIVATE)
                VoicePreferences(
                    language = saved.getString("language", java.util.Locale.getDefault().toLanguageTag()).orEmpty(),
                    online = saved.getBoolean("online", false),
                    captions = saved.getBoolean("captions", true),
                    review = saved.getBoolean("review", false),
                    automatic = saved.getBoolean("automatic", false),
                    rate = saved.getFloat("rate", 1f).takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f,
                    voice = saved.getString("voice", "").orEmpty()
                )
            }
        }
    }

    fun save(value: VoicePreferences) {
        _preferences.value = value
        viewModelScope.launch(Dispatchers.IO) {
            context.getSharedPreferences("voice_presentation", Context.MODE_PRIVATE).edit()
                .putString("language", value.language).putBoolean("online", value.online)
                .putBoolean("captions", value.captions).putBoolean("review", value.review)
                .putBoolean("automatic", value.automatic).putFloat("rate", value.rate)
                .putString("voice", value.voice).apply()
        }
    }
}
