package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest

private fun speechDeviceBuild() = PackageDigest.sha256("${Build.FINGERPRINT}|${Build.SUPPORTED_ABIS.joinToString()}".toByteArray())

/** Code compiled by the vendor driver is specific to the phone firmware and installed native runtime. */
internal fun speechAcceleratorEnvironment(context: Context): String {
    val install = context.packageManager.getPackageInfo(context.packageName, 0)
    val identity = "${speechDeviceBuild()}|${BuildConfig.QAIRT_VERSION}|${BuildConfig.LITERT_LM_VERSION}|${install.longVersionCode}|${install.lastUpdateTime}"
    return PackageDigest.sha256(identity.toByteArray())
}

/** Records completed synthesis with validated non-silent PCM. Processor placement and speaker output require separate evidence. */
internal class SpeechVoiceEvidence(private val preferences: SharedPreferences, private val deviceBuild: String, private val acceleratorEnvironment: String = deviceBuild) {
    constructor(context: Context, acceleratorEnvironment: String) : this(
        context.getSharedPreferences("speech_voice_evidence_v1", Context.MODE_PRIVATE),
        speechDeviceBuild(),
        acceleratorEnvironment
    )

    fun has(model: MediaPipeSpeechModel, backend: VoiceSpeechBackend, voice: String) =
        runCatching { preferences.getBoolean(key(model, backend, voice), false) }.getOrDefault(false)

    fun record(model: MediaPipeSpeechModel, backend: VoiceSpeechBackend, voice: String) {
        preferences.edit().putBoolean(key(model, backend, voice), true).apply()
    }

    private fun key(model: MediaPipeSpeechModel, backend: VoiceSpeechBackend, voice: String): String {
        val environment = if (backend.isMediaPipeAccelerator) acceleratorEnvironment else deviceBuild
        return "$environment/${MediaPipeSpeechCatalog.RUNTIME_ID}/${model.sha256}/${backend.name}/$voice"
    }
}
