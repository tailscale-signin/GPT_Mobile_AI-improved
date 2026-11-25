package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.security.MessageDigest

/** Records completed synthesis with validated non-silent PCM. Processor placement and speaker output require separate evidence. */
internal class SpeechVoiceEvidence(private val preferences: SharedPreferences, private val deviceBuild: String) {
    constructor(context: Context) : this(
        context.getSharedPreferences("speech_voice_evidence_v1", Context.MODE_PRIVATE),
        MessageDigest.getInstance("SHA-256").digest("${Build.FINGERPRINT}|${Build.SUPPORTED_ABIS.joinToString()}".toByteArray()).toHexString()
    )

    fun has(model: MediaPipeSpeechModel, backend: VoiceSpeechBackend, voice: String) =
        runCatching { preferences.getBoolean(key(model, backend, voice), false) }.getOrDefault(false)

    fun record(model: MediaPipeSpeechModel, backend: VoiceSpeechBackend, voice: String) {
        preferences.edit().putBoolean(key(model, backend, voice), true).apply()
    }

    private fun key(model: MediaPipeSpeechModel, backend: VoiceSpeechBackend, voice: String) =
        "$deviceBuild/${MediaPipeSpeechCatalog.RUNTIME_ID}/${model.sha256}/${backend.name}/$voice"
}
