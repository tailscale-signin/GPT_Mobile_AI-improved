package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

/** Inventory reports only public Android device type integers. Device names and addresses stay private. */
internal fun voiceBenchAudioRoutes(context: Context): List<VoiceBenchObservation> = buildList {
    val manager = context.getSystemService(AudioManager::class.java)
    for ((name, flag) in listOf("inputs" to AudioManager.GET_DEVICES_INPUTS, "outputs" to AudioManager.GET_DEVICES_OUTPUTS)) {
        val devices = manager?.getDevices(flag)
        if (devices.isNullOrEmpty()) {
            add(VoiceBenchObservation.unavailable(name, "NO_CONNECTED_AUDIO_ROUTE"))
        } else {
            add(
                VoiceBenchObservation(name, VoiceBenchStatus.MEASURED, metrics = devices.groupingBy { it.type }.eachCount().mapKeys { "androidDeviceType_${it.key}" }.mapValues { it.value.toDouble() })
            )
        }
    }
    add(
        VoiceBenchObservation(
            "capture-effects",
            VoiceBenchStatus.MEASURED,
            metrics = mapOf(
                "echoCancelerAvailable" to if (AcousticEchoCanceler.isAvailable()) 1.0 else 0.0,
                "noiseSuppressorAvailable" to if (NoiseSuppressor.isAvailable()) 1.0 else 0.0
            )
        )
    )
    @Suppress("DEPRECATION")
    val service = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES).services.orEmpty().singleOrNull { it.name.endsWith(".VoicePlaybackService") }
    val media = service != null && service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0
    val survivesTask = service != null && service.flags and ServiceInfo.FLAG_STOP_WITH_TASK == 0
    add(VoiceBenchObservation("background-playback-service-declaration", if (media && survivesTask && service?.enabled == true) VoiceBenchStatus.MEASURED else VoiceBenchStatus.FAILED, metrics = mapOf("mediaPlaybackType" to if (media) 1.0 else 0.0, "stopWithTaskDisabled" to if (survivesTask) 1.0 else 0.0)))
}
