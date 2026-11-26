package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.os.Build
import dev.chungjungsoo.gptmobile.data.localruntime.QnnEnvironment
import dev.chungjungsoo.gptmobile.data.localruntime.QualcommSocSupport

internal fun prepareMediaPipeSpeechNpu(context: Context) {
    // The speech task runs in the UI process, separate from local LLM inference.
    // Other vendors can let the SDK discover their own delegate.
    if (QualcommSocSupport.htpVersion(Build.SOC_MODEL.orEmpty()) == null) return
    val probe = QnnEnvironment.prepareForExecution(context)
    check(probe.prerequisitesReady) { probe.errorMessage ?: "Qualcomm NPU libraries are unavailable." }
}

internal fun isSm8750SpeechDevice(): Boolean = QualcommSocSupport.canonicalSoc(Build.SOC_MODEL.orEmpty()) == "SM8750"
