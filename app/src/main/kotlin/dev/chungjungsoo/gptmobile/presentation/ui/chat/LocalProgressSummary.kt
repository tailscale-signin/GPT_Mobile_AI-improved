package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeProgress
import java.util.Locale

internal fun GgufNativeProgress.workSummary(): String = when {
    sampledTokens > 0 -> "Generating · $sampledTokens tokens"
    promptTokens > 0 && processedTokens >= 0 -> buildString {
        append("Processing prompt · $processedTokens / $promptTokens tokens")
        prefillTokensPerSecond?.takeIf { it.isFinite() && it > 0 }?.let { append(" · ${String.format(Locale.US, "%.1f", it)} tok/s") }
    }
    else -> "Preparing local inference"
}

internal fun GgufNativeProgress.runtimeSummary(): String = listOfNotNull(modelName?.takeIf { it.isNotBlank() && it != "manifest.json" }, actualRuntime).joinToString(" · ")
