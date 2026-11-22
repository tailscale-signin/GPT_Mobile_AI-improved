package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.voice.VoicePreferenceWriter
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class VoiceBackendPreference(val backend: VoiceSpeechBackend = VoiceSpeechBackend.CPU, val gatewayUid: String = "")
internal data class VoiceBackendPreferenceState(val value: VoiceBackendPreference = VoiceBackendPreference(), val error: String? = null)

/** Application ownership lets a selected backend finish saving after its navigation entry closes. */
@Singleton
internal class VoiceBackendPreferenceStore internal constructor(private val open: () -> SharedPreferences, scope: CoroutineScope) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        { context.getSharedPreferences("voice_speech_backend", Context.MODE_PRIVATE) },
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private val mutable = MutableStateFlow<VoiceBackendPreferenceState?>(null)
    val state = mutable.asStateFlow()
    private val writer = VoicePreferenceWriter<VoiceBackendPreference>(scope) { value ->
        val error = try {
            check(open().edit().putString("backend", value.backend.name).putString("gateway_uid", value.gatewayUid).commit())
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "Speech settings could not be saved. Select the backend again to retry."
        }
        // An older failed write cannot overwrite the outcome of a newer selection.
        mutable.update { current -> if (current?.value == value) current.copy(error = error) else current }
    }

    init {
        scope.launch {
            val loaded = try {
                val saved = open().all
                val backend = VoiceSpeechBackend.entries.firstOrNull { it.name == saved["backend"] }
                val uid = (saved["gateway_uid"] as? String)?.takeIf { it.length <= 256 }.orEmpty()
                VoiceBackendPreferenceState(
                    VoiceBackendPreference(backend ?: VoiceSpeechBackend.CPU, uid),
                    "Saved speech settings were invalid. CPU defaults are in use.".takeIf { saved["backend"] != null && backend == null }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                VoiceBackendPreferenceState(error = "Saved speech settings could not be read. CPU defaults are in use.")
            }
            mutable.compareAndSet(null, loaded)
        }
    }

    fun save(value: VoiceBackendPreference) {
        mutable.value = VoiceBackendPreferenceState(value)
        writer.save(value)
    }
}
