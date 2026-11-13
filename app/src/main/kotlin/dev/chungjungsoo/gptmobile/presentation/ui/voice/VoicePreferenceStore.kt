package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.VoicePreferenceWriter
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One application-owned writer; navigation cannot cancel a queued settings change. */
@Singleton
internal class VoicePreferenceStore internal constructor(private val open: () -> SharedPreferences, scope: CoroutineScope) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        { context.getSharedPreferences("voice_presentation", Context.MODE_PRIVATE) },
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private val mutable = MutableStateFlow<VoicePreferences?>(null)
    val preferences = mutable.asStateFlow()
    private val error = MutableStateFlow<String?>(null)
    val saveError = error.asStateFlow()
    private val writer = VoicePreferenceWriter<VoicePreferences>(scope) { value ->
        try {
            check(
                open().edit()
                    .putString("language", value.language).putBoolean("online", value.online)
                    .putBoolean("captions", value.captions).putBoolean("review", value.review)
                    .putBoolean("automatic", value.automatic).putFloat("rate", value.rate)
                    .putString("voice", value.voice).putString("recognition_backend", value.recognitionBackend.name)
                    .putString("kokoro_voice", value.kokoroVoice?.name).putBoolean("spoken_replies", value.spokenReplies)
                    .putInt("kokoro_threads", value.kokoroThreads).putBoolean("button_sounds", value.buttonSounds).commit()
            )
            error.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error.value = "Voice settings could not be saved. Your current session keeps them; try saving again."
        }
    }

    init {
        scope.launch {
            val loaded = try {
                readVoicePreferences(open().all)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = "Saved Voice settings could not be read. Safe defaults are in use."
                VoicePreferences()
            }
            mutable.compareAndSet(null, loaded)
        }
    }

    fun save(value: VoicePreferences) {
        mutable.value = value
        writer.save(value)
    }
}

/** Recover each malformed field independently; never infer permission from an invalid value. */
internal fun readVoicePreferences(saved: Map<String, *>): VoicePreferences {
    val defaults = VoicePreferences()
    return VoicePreferences(
        language = (saved["language"] as? String)?.takeIf { it.isNotBlank() && it.length <= 64 && java.util.Locale.forLanguageTag(it).language.isNotBlank() } ?: defaults.language,
        online = saved["online"] as? Boolean ?: defaults.online,
        captions = saved["captions"] as? Boolean ?: defaults.captions,
        review = saved["review"] as? Boolean ?: defaults.review,
        automatic = saved["automatic"] as? Boolean ?: defaults.automatic,
        rate = (saved["rate"] as? Float)?.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: defaults.rate,
        voice = (saved["voice"] as? String).orEmpty(),
        recognitionBackend = VoiceRecognitionBackend.fromSaved(saved["recognition_backend"] as? String),
        kokoroVoice = KokoroVoice.fromSaved(saved["kokoro_voice"] as? String),
        spokenReplies = saved["spoken_replies"] as? Boolean ?: defaults.spokenReplies,
        kokoroThreads = (saved["kokoro_threads"] as? Int)?.coerceIn(2, 4) ?: defaults.kokoroThreads,
        buttonSounds = saved["button_sounds"] as? Boolean ?: defaults.buttonSounds
    )
}
