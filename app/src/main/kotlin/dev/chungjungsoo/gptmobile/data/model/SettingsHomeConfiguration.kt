package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

/** Portable UI choices only. Account credentials and observed balances never enter preferences. */
@Serializable
data class SettingsHomeConfiguration(
    val selectedItems: List<String> = listOf(WALLET, PROFILES, USAGE),
    val walletConnectionUid: String? = null,
    val hideWalletAmounts: Boolean = false,
    val schemaVersion: Int = 1,
    val selectedProfileUids: List<String> = emptyList(),
    val selectedToolSubjects: List<HomeToolSubject> = emptyList()
) {
    fun normalized(): SettingsHomeConfiguration = copy(
        // Legacy entries must reach migrate() in full, including profiles beyond the old cap.
        selectedItems = selectedItems.map(String::trim).filter(String::isNotEmpty).distinct().let {
            if (schemaVersion < 2) it else it.filter(CATEGORIES::contains).take(MAX_ITEMS)
        },
        selectedProfileUids = selectedProfileUids.map(String::trim).filter(String::isNotEmpty).distinct(),
        selectedToolSubjects = selectedToolSubjects.map { it.copy(ownerUid = it.ownerUid.trim(), toolName = it.toolName?.trim()?.takeIf(String::isNotEmpty)) }
            .filter { it.ownerUid.isNotEmpty() }.distinct(),
        walletConnectionUid = walletConnectionUid?.trim()?.takeIf(String::isNotEmpty)
    )

    /** Run inside the atomic feature-settings mutation with the saved profile inventory. */
    fun migrate(enabledProfileUids: List<String>): SettingsHomeConfiguration {
        val current = normalized()
        if (current.schemaVersion >= 2) return current
        val explicit = current.selectedItems.filter { it.startsWith(PROFILE_PREFIX) }.map { it.removePrefix(PROFILE_PREFIX) }
        val seeded = if (PROFILES in current.selectedItems) enabledProfileUids else emptyList()
        return current.copy(
            schemaVersion = 2,
            selectedItems = current.selectedItems.map { if (it.startsWith(PROFILE_PREFIX)) PROFILES else it },
            selectedProfileUids = current.selectedProfileUids + explicit + seeded
        ).normalized()
    }

    fun withItem(id: String, selected: Boolean): SettingsHomeConfiguration {
        val current = normalized()
        val key = id.trim()
        if (key !in CATEGORIES) return current
        return when {
            !selected -> current.copy(selectedItems = current.selectedItems - key)
            key in current.selectedItems || current.selectedItems.size >= MAX_ITEMS -> current
            else -> current.copy(selectedItems = current.selectedItems + key)
        }
    }

    companion object {
        const val MAX_ITEMS = 5
        const val WALLET = "openrouter_wallet"
        const val PROFILES = "profiles"
        const val USAGE = "usage"
        const val TOOLS = "tools"
        const val PRIVACY = "privacy"
        const val BACKUP = "backup"
        private const val PROFILE_PREFIX = "profile:"
        val CATEGORIES = setOf(WALLET, PROFILES, USAGE, TOOLS, PRIVACY, BACKUP)
        fun profile(uid: String): String = "$PROFILE_PREFIX$uid"
    }
}

/** Display identity only; selecting a subject never grants permission to execute it. */
@Serializable
data class HomeToolSubject(val ownerUid: String, val toolName: String? = null, val kind: HomeToolSubjectKind = HomeToolSubjectKind.CONNECTION)

@Serializable
enum class HomeToolSubjectKind { CONNECTION, INTEGRATED_PLUGIN, NATIVE_PACKAGE }

/** A narrow Room projection: Home never loads prompts, answers, credentials or synthesis snapshots. */
data class SettingsHomeActivity(
    val runId: String,
    val profileUid: String,
    val provider: String,
    val model: String,
    val status: String,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?
)

enum class SettingsHomeTone { POSITIVE, NEGATIVE, ACTIVE, STALE, UNKNOWN }

data class SettingsHomeProfileStatus(val label: String, val detail: String, val tone: SettingsHomeTone)

/** A recent request outcome is evidence about that request, never a claim of current provider uptime. */
fun settingsHomeProfileStatus(
    enabled: Boolean,
    modelConfigured: Boolean,
    last: SettingsHomeActivity?,
    nowSeconds: Long,
    freshSeconds: Long = 600
): SettingsHomeProfileStatus {
    if (!enabled) return SettingsHomeProfileStatus("Disabled", "Disabled in AI Profiles", SettingsHomeTone.NEGATIVE)
    if (!modelConfigured) return SettingsHomeProfileStatus("Needs a model", "Select a model in AI Profiles", SettingsHomeTone.NEGATIVE)
    if (last == null) return SettingsHomeProfileStatus("Not checked", "No recorded request for this model", SettingsHomeTone.UNKNOWN)
    val observedAt = last.completedAt ?: last.startedAt ?: last.createdAt
    val age = nowSeconds - observedAt
    if (age !in 0..freshSeconds) return SettingsHomeProfileStatus("Stale result", "Last update ${settingsHomeAge(observedAt, nowSeconds)}", SettingsHomeTone.STALE)
    val detail = settingsHomeAge(observedAt, nowSeconds)
    return when (last.status) {
        "COMPLETED" -> SettingsHomeProfileStatus("Last request succeeded", detail, SettingsHomeTone.POSITIVE)
        "FAILED" -> SettingsHomeProfileStatus("Last request failed", detail, SettingsHomeTone.NEGATIVE)
        "INTERRUPTED" -> SettingsHomeProfileStatus("Last request interrupted", detail, SettingsHomeTone.NEGATIVE)
        "RUNNING" -> SettingsHomeProfileStatus("Responding", "Recorded as running · ${settingsHomeAge(observedAt, nowSeconds)}", SettingsHomeTone.ACTIVE)
        "QUEUED" -> SettingsHomeProfileStatus("Queued", detail, SettingsHomeTone.ACTIVE)
        "CANCELED" -> SettingsHomeProfileStatus("Last request canceled", detail, SettingsHomeTone.UNKNOWN)
        else -> SettingsHomeProfileStatus("Unknown result", detail, SettingsHomeTone.UNKNOWN)
    }
}

fun settingsHomeAge(observedAt: Long, nowSeconds: Long): String = when (val seconds = nowSeconds - observedAt) {
    in Long.MIN_VALUE..-1 -> "time unavailable"
    in 0..59 -> "just now"
    in 60..3599 -> "${seconds / 60} min ago"
    in 3600..86399 -> "${seconds / 3600} h ago"
    else -> "${seconds / 86400} d ago"
}

/** Odd layouts use one full-width lead tile; no empty weighted placeholders or fixed tile heights. */
fun settingsHomeRows(itemCount: Int, columns: Int): List<IntRange> {
    require(itemCount in 0..SettingsHomeConfiguration.MAX_ITEMS && columns in 1..2)
    if (itemCount == 0) return emptyList()
    if (columns == 1) return (0 until itemCount).map { it..it }
    val firstCount = if (itemCount % 2 == 0) 2 else 1
    return listOf(0 until firstCount) + (firstCount until itemCount step 2).map { it..it + 1 }
}
