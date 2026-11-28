package dev.chungjungsoo.gptmobile.data.voice

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel

/** Explicit accelerator configuration for the pinned LiteRT 2.4.1 runtime. */
internal fun pocketCompiledAccelerationOptions(requested: Accelerator): CompiledModel.Options {
    require(requested == Accelerator.GPU || requested == Accelerator.NPU) { "POCKET_ACCELERATOR_REQUIRED" }
    // LiteRT 2.4.1 inserts CPU for a singleton NPU request. Its public NONE value
    // contributes no native accelerator bits and keeps this request explicit.
    // The pinned-API regression guards this behavior on dependency updates; do not
    // use the library's test-only disableAutoFallbackToCpu() in production.
    // This configures permitted execution; it does not measure kernel placement.
    val options = if (requested == Accelerator.NPU) {
        CompiledModel.Options(Accelerator.NPU, Accelerator.NONE)
    } else {
        CompiledModel.Options(Accelerator.GPU)
    }
    if (requested == Accelerator.NPU) {
        options.qualcommOptions = CompiledModel.QualcommOptions(
            enableJustInTime = true,
            htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.SUSTAINED_HIGH_PERFORMANCE
        )
    }
    return options
}
