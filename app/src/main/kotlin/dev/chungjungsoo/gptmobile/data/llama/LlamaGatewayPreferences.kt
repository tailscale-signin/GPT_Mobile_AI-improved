package dev.chungjungsoo.gptmobile.data.llama

import android.content.Context
import com.google.gson.Gson
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.llama.AdvancedSettings
import java.security.MessageDigest

/**
 * Bridges the Llama Advanced Settings UI to the Python Gateway.
 *
 * Settings are stored per platform UID. The legacy global preference is read
 * as a fallback so existing users keep their current values after upgrading.
 */
object LlamaGatewayPreferences {
    private const val PREFS_NAME = "llama_settings"
    private const val LEGACY_KEY = "advanced_settings"
    private const val PLATFORM_KEY_PREFIX = "advanced_settings:"

    private val gson = Gson()

    fun load(context: Context, platformUid: String): AdvancedSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val platformJson = prefs.getString(platformKey(platformUid), null)
        val legacyJson = prefs.getString(LEGACY_KEY, null)
        val source = platformJson ?: legacyJson
        return source
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { gson.fromJson(it, AdvancedSettings::class.java) }.getOrNull() }
            ?: AdvancedSettings()
    }

    fun save(context: Context, platformUid: String, settings: AdvancedSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(platformKey(platformUid), gson.toJson(settings))
            .apply()
    }

    fun reset(context: Context, platformUid: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(platformKey(platformUid))
            .apply()
    }

    fun headers(context: Context, platformUid: String, chatId: Int): Map<String, String> =
        headers(load(context, platformUid), platformUid, chatId)

    internal fun headers(
        settings: AdvancedSettings,
        platformUid: String,
        chatId: Int,
        clientVersion: String = BuildConfig.VERSION_NAME
    ): Map<String, String> {
        if (!settings.gatewayIntegrationEnabled) return emptyMap()

        val profile = settings.gatewayPerformanceProfile.takeIf {
            it in setOf("balanced", "low_latency", "high_performance", "max_throughput")
        } ?: "high_performance"
        val routing = settings.gatewayToolRouting.takeIf { it in setOf("auto", "local", "remote") } ?: "auto"
        val progressDetail = settings.gatewayProgressDetail.takeIf { it in setOf("compact", "normal", "debug") } ?: "normal"

        val headers = linkedMapOf(
            "X-Gateway-Client" to "gpt-mobile-android",
            "X-Gateway-Client-Version" to clientVersion,
            "X-Gateway-Performance-Profile" to profile,
            "X-Gateway-Affinity-Key" to affinityKey(platformUid, chatId),
            "X-Gateway-Cache-Prompt" to settings.gatewayCachePrompt.toString(),
            "X-Gateway-Cache-Reuse" to settings.gatewayCacheReuse.coerceIn(0, 8192).toString(),
            "X-Gateway-Slot-Affinity" to settings.gatewaySlotAffinity.toString(),
            "X-Gateway-Max-Rounds" to settings.gatewayMaxRounds.coerceIn(4, 500).toString(),
            "X-Gateway-Adaptive-Reasoning" to settings.gatewayAdaptiveReasoning.toString(),
            "X-Gateway-Thinking-Budget" to settings.gatewayThinkingBudget.coerceIn(-1, 131072).toString(),
            "X-Gateway-Stream-Heartbeat" to settings.gatewayStreamHeartbeatSeconds.coerceIn(1, 60).toString(),
            "X-Gateway-Progress-Poll-Ms" to settings.gatewayProgressPollMs.coerceIn(100, 5000).toString(),
            "X-Gateway-Model-Wait-After" to settings.gatewayModelWaitAfterSeconds.coerceIn(3, 600).toString(),
            "X-Gateway-Model-Wait-Interval" to settings.gatewayModelWaitIntervalSeconds.coerceIn(3, 600).toString(),
            "X-Gateway-Job-Timeout" to settings.gatewayJobTimeoutSeconds.coerceIn(60, 14400).toString(),
            "X-Gateway-Tool-Routing" to routing,
            "X-Gateway-Progress-Detail" to progressDetail
        )
        if (settings.gatewaySlotCount > 0) {
            headers["X-Gateway-Slot-Count"] = settings.gatewaySlotCount.coerceIn(1, 64).toString()
        }
        return headers
    }

    internal fun affinityKey(platformUid: String, chatId: Int): String {
        val source = "$platformUid:$chatId".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(source)
        return digest.take(16).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun platformKey(platformUid: String): String =
        PLATFORM_KEY_PREFIX + platformUid.ifBlank { "default" }
}
