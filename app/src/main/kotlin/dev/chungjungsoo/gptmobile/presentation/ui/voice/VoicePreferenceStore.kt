package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelVariant
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeKokoroBucket
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechVoices
import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguages
import dev.chungjungsoo.gptmobile.data.voice.VoiceCpuSchedulingPolicy
import dev.chungjungsoo.gptmobile.data.voice.VoicePauseDuration
import dev.chungjungsoo.gptmobile.data.voice.VoicePerformanceMode
import dev.chungjungsoo.gptmobile.data.voice.VoicePreferenceWriter
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionCompute
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionConfiguration
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionModel
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisPreferences
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationPair
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
internal class VoicePreferenceStore internal constructor(private val open: () -> SharedPreferences, scope: CoroutineScope, private val legacyReplyLanguage: () -> String? = { null }) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        { context.getSharedPreferences("voice_presentation", Context.MODE_PRIVATE) },
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        { legacyStreamingReplyLanguage(context.getSharedPreferences("voice_speech_backend", Context.MODE_PRIVATE).all) }
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
                    .putString("translation_base_language", value.translationPair.baseLanguage)
                    .putString("translation_target_language", value.translationPair.translationLanguage)
                    .putString("translation_base_voice", value.translationPair.baseVoiceId)
                    .putString("translation_target_voice", value.translationPair.translationVoiceId)
                    .putString("translation_source_language", value.translationPair.sourceLanguage)
                    .putStringSet("synthesis_voices", value.synthesis.voices.map { (tag, voice) -> "$tag=$voice" }.toSet())
                    .putString("language", value.language).putBoolean("online", value.online)
                    .putBoolean("captions", value.captions).putBoolean("review", value.review)
                    .putBoolean("automatic", value.automatic).putFloat("rate", value.rate)
                    .putString("voice", value.voice).putString("recognition_backend", value.recognitionBackend.name)
                    .putString("recognition_model", value.recognitionModel.id).putString("recognition_compute", value.recognitionCompute.name)
                    .putInt("recognition_threads", value.recognitionThreads)
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
                    .putString("pronunciation_locales", org.json.JSONObject(value.tuning.pronunciationLocales).toString())
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
                val saved = open().all
                val legacy = if (saved.keys.none { it in TRANSLATION_PAIR_KEYS }) runCatching(legacyReplyLanguage).getOrNull() else null
                readVoicePreferences(saved, legacy)
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
        val normalized = value.copy(kokoroThreads = VoiceCpuSchedulingPolicy.choice(value.kokoroThreads), translationPair = value.translationPair.normalized())
        mutable.value = normalized
        writer.save(normalized)
    }
}

/** Recover each malformed field independently; never infer permission from an invalid value. */
internal fun readVoicePreferences(saved: Map<String, *>, legacyReplyLanguage: String? = null): VoicePreferences {
    val defaults = VoicePreferences()
    val legacyVoice = KokoroVoice.fromSaved(saved["kokoro_voice"] as? String)
    val language = (saved["language"] as? String)?.takeIf { it.length <= 64 && java.util.Locale.forLanguageTag(it).language.isNotBlank() } ?: defaults.language
    val synthesis = VoiceSynthesisPreferences.restore(saved["synthesis_language"] as? String, saved["synthesis_voices"] as? Set<*>, legacyVoice?.streamingVoiceId, defaultTag = language)
    val recognition = VoiceRecognitionConfiguration(
        VoiceRecognitionModel.fromSaved(saved["recognition_model"] as? String),
        VoiceRecognitionCompute.fromSaved(saved["recognition_compute"] as? String),
        (saved["recognition_threads"] as? Int)?.takeIf { it in 0..8 } ?: defaults.recognitionThreads
    ).supported()
    val hasTranslationPair = saved.keys.any { it in TRANSLATION_PAIR_KEYS }
    val legacyTarget = legacyReplyLanguage ?: saved["synthesis_language"] as? String
    val supportedLegacyTarget = VoiceTranslationPair.canonicalLanguage(legacyTarget)
    val pair = VoiceTranslationPair(
        baseLanguage = saved["translation_base_language"] as? String ?: defaults.translationPair.baseLanguage,
        translationLanguage = saved["translation_target_language"] as? String
            ?: supportedLegacyTarget?.takeIf { !hasTranslationPair && it != defaults.translationPair.baseLanguage }
            ?: defaults.translationPair.translationLanguage,
        baseVoiceId = saved["translation_base_voice"] as? String ?: defaults.translationPair.baseVoiceId,
        translationVoiceId = saved["translation_target_voice"] as? String ?: defaults.translationPair.translationVoiceId,
        sourceLanguage = saved["translation_source_language"] as? String
    ).normalized()
    // Preserve an unsupported legacy reply language in synthesis. Do not silently
    // turn an existing translation into a different language when upgrading it.
    val unsupportedLegacyTranslation = !hasTranslationPair && legacyTarget != null && supportedLegacyTarget == null
    val translationMode = saved["translation_mode"] == true && !unsupportedLegacyTranslation
    val requestedRecognition = VoiceRecognitionBackend.fromSaved(saved["recognition_backend"] as? String)
    return VoicePreferences(
        language = language,
        synthesis = synthesis,
        translationMode = translationMode,
        translationPair = pair,
        translationSetupNotice = if (saved["translation_mode"] == true && unsupportedLegacyTranslation) {
            "Your previous translation language is not available in Pocket Multilingual. Choose two supported languages to turn translation on. Your ordinary reply language is unchanged."
        } else {
            null
        },
        online = saved["online"] as? Boolean ?: defaults.online,
        captions = saved["captions"] as? Boolean ?: defaults.captions,
        review = saved["review"] as? Boolean ?: defaults.review,
        automatic = saved["automatic"] as? Boolean ?: defaults.automatic,
        rate = (saved["rate"] as? Float)?.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: defaults.rate,
        voice = (saved["voice"] as? String).orEmpty(),
        // Translation owns a separate source language. Preserve this preference
        // while its effective capture route checks the pair's explicit source.
        recognitionBackend = if (translationMode) requestedRecognition else SpeechLanguages.recognitionBackend(language, requestedRecognition, recognition.model),
        recognitionModel = recognition.model,
        recognitionCompute = recognition.compute,
        recognitionThreads = recognition.threads,
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

private val TRANSLATION_PAIR_KEYS = setOf(
    "translation_base_language",
    "translation_target_language",
    "translation_base_voice",
    "translation_target_voice",
    "translation_source_language"
)

internal fun legacyStreamingReplyLanguage(saved: Map<String, *>): String? {
    val backend = VoiceSpeechBackend.entries.firstOrNull { it.name == saved["backend"] } ?: return null
    if (backend.isCompiledPocket) return "en-US"
    val engine = backend.streamingEngine()?.takeUnless { it.isKokoro } ?: return null
    val voice = MediaPipeSpeechVoices.selected(engine, MediaPipeSpeechVoices.restore(saved["streaming_voices"] as? Set<*>)[engine.name])
    return MediaPipeSpeechVoices.profile(engine, voice)?.tag
}
