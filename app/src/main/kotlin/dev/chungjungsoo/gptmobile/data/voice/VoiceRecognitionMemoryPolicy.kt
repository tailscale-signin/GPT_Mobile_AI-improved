package dev.chungjungsoo.gptmobile.data.voice

import android.app.ActivityManager
import android.content.Context

/** A conservative admission budget, never a measured RSS or a device performance result. */
internal object VoiceRecognitionMemoryPolicy {
    private const val MIB = 1024L * 1024

    fun estimatedHeadroom(installedBytes: Long, compute: VoiceRecognitionCompute, model: VoiceRecognitionModel? = null): Long {
        require(installedBytes in 1..16L * 1024 * MIB)
        // Embedded Nemotron CPU weights can coexist with deserialized/optimized
        // graph copies during initialization. A constrained host failed below this
        // budget; this is an admission estimate, not measured Android peak RSS.
        if (model == VoiceRecognitionModel.NEMOTRON_3_5 && compute == VoiceRecognitionCompute.CPU) return installedBytes * 3 + 512 * MIB
        // Leave room for session weights, cache tensors and the already running chat/TTS.
        return installedBytes * 2 + if (compute == VoiceRecognitionCompute.GPU) 512 * MIB else 256 * MIB
    }

    fun canLoad(availableBytes: Long?, lowMemory: Boolean, requiredBytes: Long): Boolean =
        !lowMemory && (availableBytes == null || availableBytes >= requiredBytes)

    fun requireCapacity(context: Context, pack: VoiceRecognitionModelPack) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val available = info.availMem.takeIf { it > 0 }
        if (!canLoad(available, info.lowMemory, estimatedHeadroom(pack.installedBytes, pack.compute, pack.model))) {
            throw VoiceRecognitionRuntimeException(
                "ASR_MEMORY_PRESSURE",
                "There is not enough free memory to safely load ${pack.model.title}. Close other heavy apps or choose a smaller recognition model."
            )
        }
    }
}
