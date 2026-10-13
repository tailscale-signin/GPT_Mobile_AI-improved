package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.model.AppFeature
import dev.chungjungsoo.gptmobile.data.model.DebugMetric
import java.util.Locale

internal enum class SettingsSearchDestination {
    ADVANCED,
    APPEARANCE,
    MEMORY,
    LOCAL_MODELS,
    PROFILES,
    TOOLS,
    DELEGATION,
    DEBUG,
    STATISTICS,
    BENCHMARKS,
    BACKUP,
    WORKSPACE,
    ABOUT
}

internal data class SettingsSearchEntry(
    val key: String,
    val title: String,
    val breadcrumb: String,
    val destination: SettingsSearchDestination,
    val focus: String = "",
    val keywords: String = ""
)

/** Stable keys and concrete route/tab targets. Dynamic profiles are supplied by the owner. */
internal object SettingsSearchRegistry {
    fun group(feature: AppFeature): String = when (feature) {
        AppFeature.NEW_CHAT_SCORES, AppFeature.NEW_CHAT_PRICES, AppFeature.NEW_CHAT_PRICE_REFRESH, AppFeature.NEW_CHAT_COMMUNITY, AppFeature.NEW_CHAT_BACKGROUND_PRICES -> "New Chat — Scores & Prices"
        AppFeature.COMPACT_CHAT_LIST, AppFeature.SMOOTH_STREAMING, AppFeature.CENTER_UNREAD, AppFeature.RESPONSE_ANIMATION,
        AppFeature.EDGE_FADES, AppFeature.MESSAGE_TIMESTAMPS, AppFeature.ARCHIVE_OLDER_REPLIES,
        AppFeature.SHOW_REASONING, AppFeature.SMART_SUGGESTIONS, AppFeature.AUTOMATIC_TITLES -> "Reading & Motion"
        AppFeature.BACKGROUND_GENERATION, AppFeature.RESPONSE_NOTIFICATIONS -> "Background & Notifications"
        AppFeature.LOCAL_MODEL_CACHE, AppFeature.LOCAL_ASSIST, AppFeature.LOCAL_NATIVE_METRICS,
        AppFeature.QNN_AUTO_FALLBACK -> "Local Runtime"
        AppFeature.REMOTE_MCP, AppFeature.SHARED_TOOL_CALLS, AppFeature.DEVICE_LOCATION -> "Tools & Permissions"
        AppFeature.DIAGNOSTICS -> "Diagnostics"
        else -> "Research & Efficiency"
    }

    val entries: List<SettingsSearchEntry> = AppFeature.entries.map {
        SettingsSearchEntry("feature.${it.name}", it.title, "Advanced Settings › ${group(it)}", SettingsSearchDestination.ADVANCED, it.name, it.description)
    } + DebugMetric.entries.map {
        SettingsSearchEntry("debug.${it.name}", it.title, "Debug › Display", SettingsSearchDestination.DEBUG, it.name, it.description)
    } + listOf(
        SettingsSearchEntry("research", "Research", "Plugins & Tools › Research", SettingsSearchDestination.TOOLS, "research", "archive article examine evidence appraisal papers study statistics"),
        SettingsSearchEntry("execution", "Execution & efficiency", "AI › Execution & efficiency", SettingsSearchDestination.LOCAL_MODELS, "execution", "optimization balanced savings fast local assist budgets shared usage handoff diagnostics"),
        SettingsSearchEntry("appearance.presets", "Theme Styles", "Appearance › Style", SettingsSearchDestination.APPEARANCE, "0", "theme colours colors graphite cyan midnight"),
        SettingsSearchEntry("appearance.custom", "Custom Colours", "Appearance › Colours", SettingsSearchDestination.APPEARANCE, "1", "palette contrast accent secondary background cards hex save theme"),
        SettingsSearchEntry("appearance.saved", "Saved Themes", "Appearance › Saved", SettingsSearchDestination.APPEARANCE, "3", "saved theme rename duplicate delete backup colours colors"),
        SettingsSearchEntry("appearance.display", "Display", "Appearance › Display", SettingsSearchDestination.APPEARANCE, "2", "dark light system wallpaper dynamic colours"),
        SettingsSearchEntry("memory.facts", "Saved Memories", "Memory › Facts", SettingsSearchDestination.MEMORY, "Memories", "saved facts recall edit delete pin private memory vault"),
        SettingsSearchEntry("memory.documents", "Document Indexing", "Memory › Documents", SettingsSearchDestination.MEMORY, "Documents", "attachments indexing import retry remove"),
        SettingsSearchEntry("memory.controls", "Memory Controls", "Memory › Controls", SettingsSearchDestination.MEMORY, "Controls", "confidence sensitivity semantic angurvadal recall cloud"),
        SettingsSearchEntry("local.library", "Local Models", "AI › Local Models › Library", SettingsSearchDestination.LOCAL_MODELS, "library", "downloaded on device models litert qnn genie"),
        SettingsSearchEntry("local.runtime", "Runtime Settings", "AI › Local Models › Settings", SettingsSearchDestination.LOCAL_MODELS, "runtime", "threads cache cpu gpu npu fallback speculative idle"),
        SettingsSearchEntry("profiles", "AI Profiles", "AI › Profiles", SettingsSearchDestination.PROFILES, keywords = "platform providers connections remote free models labels"),
        SettingsSearchEntry("tools", "Plugins & Tools", "Plugins & Tools", SettingsSearchDestination.TOOLS, keywords = "tool connections marketplace mcp endpoint permissions revoke data access"),
        SettingsSearchEntry("delegation", "Model Delegation", "Memory & Research › Delegation", SettingsSearchDestination.DELEGATION, keywords = "reviewer local assist worker budget research cloud recall"),
        SettingsSearchEntry("privacy", "Privacy & Storage", "Advanced Settings › Privacy & Storage", SettingsSearchDestination.ADVANCED, "privacy", "biometric device unlock screenshots cleanup storage"),
        SettingsSearchEntry("health", "Connection & Runtime Health", "Diagnostics › Health", SettingsSearchDestination.DEBUG, "health", "connection online offline provider local backend accelerator fallback"),
        SettingsSearchEntry("debug.live", "Live Diagnostics", "Debug › Live", SettingsSearchDestination.DEBUG, "live", "health runtime connectivity hardware logs tools"),
        SettingsSearchEntry("debug.runs", "Recent Runs", "Debug › Runs", SettingsSearchDestination.DEBUG, "runs", "failed interrupted cancelled canceled requests"),
        SettingsSearchEntry("debug.logs", "Diagnostic Logs", "Debug › Logs", SettingsSearchDestination.DEBUG, "logs", "redacted export events"),
        SettingsSearchEntry("statistics", "Usage Statistics", "Diagnostics › Statistics", SettingsSearchDestination.STATISTICS, keywords = "tokens cost spending accounting"),
        SettingsSearchEntry("benchmarks", "Benchmarks", "Diagnostics › Benchmarks", SettingsSearchDestination.BENCHMARKS, keywords = "scores ranking cohort local remote throughput speed delegation history"),
        SettingsSearchEntry("backup", "Backup & Restore", "Data › Backup & Restore", SettingsSearchDestination.BACKUP, keywords = "encryption recovery password statistics plugins export import"),
        SettingsSearchEntry("workspace", "GitHub Workspace", "Plugins & Tools › GitHub Workspace", SettingsSearchDestination.WORKSPACE, keywords = "repository code branch diff pull request"),
        SettingsSearchEntry("about", "About", "Settings › About", SettingsSearchDestination.ABOUT, keywords = "version licence license")
    )

    fun search(query: String, candidates: List<SettingsSearchEntry> = entries): List<SettingsSearchEntry> {
        val words = query.lowercase(Locale.ROOT).trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        return candidates.filter { entry ->
            val text = "${entry.title} ${entry.breadcrumb} ${entry.keywords}".lowercase(Locale.ROOT)
            words.all { it in text }
        }.sortedWith(compareByDescending<SettingsSearchEntry> { it.title.equals(query.trim(), true) }.thenBy { it.title })
    }
}
