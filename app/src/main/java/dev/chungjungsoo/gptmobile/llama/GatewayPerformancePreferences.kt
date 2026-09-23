package dev.chungjungsoo.gptmobile.llama

import android.content.Context
import com.google.gson.Gson

/**
 * Request-scoped Gateway v10 performance controls.
 *
 * These are intentionally transmitted as headers only for the Llama platform.
 * The Gateway remains the execution owner; Android only supplies user preferences.
 */
object GatewayPerformancePreferences {
    private const val PREFS = "llama_settings"
    private const val SETTINGS = "advanced_settings"

    fun headers(context: Context?): Map<String, String> {
        if (context == null) return emptyMap()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SETTINGS, null) ?: return emptyMap()
        val settings = runCatching { Gson().fromJson(raw, AdvancedSettings::class.java) }.getOrNull()
            ?: return emptyMap()

        return buildMap {
            put("X-Gateway-Performance-Profile", settings.gatewayPerformanceProfile)
            put("X-Gateway-Slot-Pinning", settings.gatewaySlotPinning.toString())
            put("X-Gateway-Slot-Count", settings.gatewaySlotCount.coerceIn(1, 64).toString())
            put("X-Gateway-Cache-Prompt", settings.gatewayCachePrompt.toString())
            put("X-Gateway-Cache-Reuse", settings.gatewayCacheReuse.coerceIn(0, 8192).toString())
            put("X-Gateway-Reasoning-Effort", settings.gatewayReasoningEffort)
            put("X-Gateway-Tool-Optimization", settings.gatewayToolOptimization.toString())
            put("X-Gateway-Tool-Surface-Limit", settings.gatewayToolSurfaceLimit.coerceIn(0, 128).toString())
            put("X-Gateway-Stable-Tool-Surface", settings.gatewayStableToolSurface.toString())
            put("X-Gateway-Intermediate-Max-Tokens", settings.gatewayIntermediateMaxTokens.coerceIn(256, 16384).toString())
            put("X-Gateway-Soft-Synthesis-Round", settings.gatewaySoftSynthesisRound.coerceIn(4, 500).toString())
            put("X-Gateway-Result-Char-Limit", settings.gatewayResultCharLimit.coerceIn(4000, 200000).toString())
        }
    }
}
