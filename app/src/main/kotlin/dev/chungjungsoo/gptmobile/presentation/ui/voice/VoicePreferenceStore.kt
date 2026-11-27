package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelVariant
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeKokoroBucket
import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguages
import dev.chungjungsoo.gptmobile.data.voice.VoiceCpuSchedulingPolicy
import dev.chungjungsoo.gptmobile.data.voice.VoicePauseDuration
import dev.chungjungsoo.gptmobile.data.voice.VoicePerformanceMode
import dev.chungjungsoo.gptmobile.data.voice.VoicePreferenceWriter
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisPreferences
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
                    .putString("synthesis_language", value.synthesis.profile.tag)
                    .putBoolean("translation_mode", value.translationMode)
                    .putStringSet("synthesis_voices", value.synthesis.voices.map { (tag, voice) -> "$tag=$voice" }.toSet())
                    .putString("language", value.language).putBoolean("online", value.online)
                    .putBoolean("captions", value.captions).putBoolean("review", value.review)
                    .putBoolean("automatic", value.automatic).putFloat("rate", value.rate)
                    .putString("voice", value.voice).putString("recognition_backend", value.recognitionBackend.name)
                    .putString("kokoro_voice", value.kokoroVoice?.name).putBoolean("spoken_replies", value.spokenReplies)
                    .putInt("native_cpu_thread_choice", value.kokoroThreads).putInt("kokoro_threads", VoiceCpuSchedulingPolicy.initial(value.kokoroThreads)).putBoolean("button_sounds", value.buttonSounds)
                    .putString("pause_duration", value.pause.name).putString("performance_mode", value.performance.name)
                    .putBoolean("speak_while_generating", value.speakWhileGenerating)
                    .putString("litert_kokoro_bucket", value.kokoroBucket.name)
                    .putBoolean("incremental_speech_input", value.incrementalSpeech)
                    .putString("native_kokoro_model_variant", value.nativeModelVariant.name)
                    .putString("listening_style", value.tuning.listeningStyle.name)
                    .putInt("capture_batch_ms", value.tuning.captureBatchMs)
                    .putBoolean("hands_free_interruption", value.tuning.handsFreeInterruption)
                    .putBoolean("spoken_acknowledgments", value.tuning.spokenAcknowledgments)
                    .putString("pronunciation_overrides", org.json.JSONObject(value.tuning.pronunciation).toString())
                    .putString("localized_pronunciations", org.json.JSONObject(value.tuning.localizedPronunciations).toString())
                    .putInt("caption_offset_ms", value.tuning.captionOffsetMs).commit()
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
        val normalized = value.copy(kokoroThreads = VoiceCpuSchedulingPolicy.choice(value.kokoroThreads))
        mutable.value = normalized
        writer.save(normalized)
    }
}

/** Recover each malformed field independently; never infer permission from an invalid value. */
internal fun readVoicePreferences(saved: Map<String, *>): VoicePreferences {
    val defaults = VoicePreferences()
    val legacyVoice = KokoroVoice.fromSaved(saved["kokoro_voice"] as? String)
    val language = (saved["language"] as? String)?.takeIf { it.length <= 64 && java.util.Locale.forLanguageTag(it).language.isNotBlank() } ?: defaults.language
    val synthesis = VoiceSynthesisPreferences.restore(saved["synthesis_language"] as? String, saved["synthesis_voices"] as? Set<*>, legacyVoice?.streamingVoiceId, defaultTag = language)
    return VoicePreferences(
        language = language,
        synthesis = synthesis,
        translationMode = saved["translation_mode"] as? Boolean ?: false,
        online = saved["online"] as? Boolean ?: defaults.online,
        captions = saved["captions"] as? Boolean ?: defaults.captions,
        review = saved["review"] as? Boolean ?: defaults.review,
        automatic = saved["automatic"] as? Boolean ?: defaults.automatic,
        rate = (saved["rate"] as? Float)?.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: defaults.rate,
        voice = (saved["voice"] as? String).orEmpty(),
        recognitionBackend = SpeechLanguages.recognitionBackend(language, VoiceRecognitionBackend.fromSaved(saved["recognition_backend"] as? String)),
        kokoroVoice = synthesis.voices[synthesis.language]?.let(KokoroVoice::fromStreamingId) ?: legacyVoice,
        spokenReplies = saved["spoken_replies"] as? Boolean ?: defaults.spokenReplies,
        kokoroThreads = (saved["native_cpu_thread_choice"] as? Int)?.let(VoiceCpuSchedulingPolicy::choice)
            ?: (saved["kokoro_threads"] as? Int)?.takeUnless { it == 2 && saved["performance_mode"] == VoicePerformanceMode.AUTO.name }
                ?.let(VoiceCpuSchedulingPolicy::forDevice) ?: defaults.kokoroThreads,
        buttonSounds = saved["button_sounds"] as? Boolean ?: defaults.buttonSounds,
        pause = VoicePauseDuration.entries.firstOrNull { it.name == saved["pause_duration"] } ?: defaults.pause,
        performance = VoicePerformanceMode.entries.firstOrNull { it.name == saved["performance_mode"] } ?: defaults.performance,
        speakWhileGenerating = saved["speak_while_generating"] as? Boolean ?: defaults.speakWhileGenerating,
        kokoroBucket = MediaPipeKokoroBucket.entries.firstOrNull { it.name == saved["litert_kokoro_bucket"] } ?: defaults.kokoroBucket,
        incrementalSpeech = saved["incremental_speech_input"] as? Boolean ?: defaults.incrementalSpeech,
        nativeModelVariant = KokoroModelVariant.entries.firstOrNull { it.name == saved["native_kokoro_model_variant"] } ?: if (saved.isEmpty()) KokoroModelVariant.ORIGINAL_FP32 else defaults.nativeModelVariant,
        tuning = readVoiceTuningPreferences(saved)
    )
}
