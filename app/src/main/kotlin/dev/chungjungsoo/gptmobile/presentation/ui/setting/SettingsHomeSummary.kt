package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.backup.BackupStatus
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeActivity
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeConfiguration
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeTone
import dev.chungjungsoo.gptmobile.data.model.settingsHomeAge
import dev.chungjungsoo.gptmobile.data.model.settingsHomeProfileStatus
import dev.chungjungsoo.gptmobile.data.openrouter.account.AccountFailure
import dev.chungjungsoo.gptmobile.data.openrouter.account.SettingsHomeWalletState
import dev.chungjungsoo.gptmobile.data.openrouter.account.usd

data class SettingsHomeActivityState(val records: List<SettingsHomeActivity> = emptyList(), val loaded: Boolean = false, val unavailable: Boolean = false)

internal data class SettingsHomePoint(
    val id: String,
    val title: String,
    val value: String,
    val detail: String,
    val tone: SettingsHomeTone = SettingsHomeTone.UNKNOWN,
    val secondaryLabel: String? = null,
    val secondaryTone: SettingsHomeTone = SettingsHomeTone.UNKNOWN,
    val subjects: List<SettingsHomePoint> = emptyList()
)

internal fun settingsHomePoints(
    features: AppFeatureSettings,
    profiles: List<PlatformV2>,
    providerConnections: List<ProviderConnection>,
    activity: SettingsHomeActivityState,
    connections: List<ToolConnection>?,
    wallet: SettingsHomeWalletState,
    backup: BackupStatus,
    nowSeconds: Long,
    toolGroups: List<dev.chungjungsoo.gptmobile.data.home.HomeToolGroup> = emptyList()
): List<SettingsHomePoint> {
    val configuration = features.settingsHome.migrate(profiles.filter { it.enabled }.map { it.uid })
    val selectedConnection = homeWalletConnection(configuration, providerConnections)
    return configuration.selectedItems.map { id ->
        when (id) {
            SettingsHomeConfiguration.WALLET -> {
                val snapshot = wallet.snapshot?.takeIf { selectedConnection != null && it.connectionUid == selectedConnection.uid && it.connectionRevision == selectedConnection.updatedAt }
                when {
                    wallet.loading -> SettingsHomePoint(id, "OpenRouter Wallet", "Reading balance…", "Authenticated account request", SettingsHomeTone.ACTIVE)
                    snapshot != null -> SettingsHomePoint(
                        id,
                        "OpenRouter Wallet",
                        if (configuration.hideWalletAmounts) "••••" else usd(snapshot.remainingUsd),
                        "${if (snapshot.isFresh(nowSeconds)) "Updated" else "Stale"} ${settingsHomeAge(snapshot.observedAt, nowSeconds)} · ${snapshot.connectionName}" + (wallet.failure?.let { " · " + walletFailureText(it) } ?: ""),
                        if (!snapshot.isFresh(nowSeconds)) {
                            SettingsHomeTone.STALE
                        } else if (snapshot.remainingUsd.toBigDecimal().signum() <= 0) {
                            SettingsHomeTone.NEGATIVE
                        } else {
                            SettingsHomeTone.POSITIVE
                        }
                    )
                    wallet.failure != null -> SettingsHomePoint(id, "OpenRouter Wallet", "Balance unavailable", walletFailureText(wallet.failure), SettingsHomeTone.UNKNOWN)
                    else -> SettingsHomePoint(
                        id,
                        "OpenRouter Wallet",
                        if (wallet.associationValid) "Available after your next response" else "Manage OpenRouter",
                        if (selectedConnection == null) {
                            "Select an OpenRouter connection in Home tab"
                        } else if (wallet.associationValid) {
                            "Waiting for an eligible remote response"
                        } else {
                            "Save or reassociate management access in provider settings"
                        }
                    )
                }
            }
            SettingsHomeConfiguration.PROFILES -> {
                val subjects = configuration.selectedProfileUids.map { uid ->
                    val profile = profiles.find { it.uid == uid }
                    if (profile == null) {
                        SettingsHomePoint(SettingsHomeConfiguration.profile(uid), "Unavailable profile", "Missing", "Remove this selection in Choose profiles")
                    } else {
                        val provider = providerConnections.find { it.uid == profile.providerConnectionUid }
                        val last = activity.records.filter {
                            it.profileUid == profile.uid &&
                                it.model == profile.model &&
                                it.provider == profile.compatibleType.name &&
                                (provider == null || it.createdAt >= provider.updatedAt)
                        }.maxByOrNull { it.completedAt ?: it.startedAt ?: it.createdAt }
                        val status = settingsHomeProfileStatus(profile.enabled, profile.model.isNotBlank(), last, nowSeconds)
                        SettingsHomePoint(SettingsHomeConfiguration.profile(uid), profile.name, status.label, status.detail, status.tone)
                    }
                }
                SettingsHomePoint(id, "AI Profiles", "${subjects.size} selected", if (subjects.isEmpty()) "Choose profiles" else "Recorded status for selected profiles", subjects = subjects)
            }
            SettingsHomeConfiguration.USAGE -> when {
                !activity.loaded -> SettingsHomePoint(id, "Recent activity", "Loading…", "Reading local request records")
                activity.unavailable -> SettingsHomePoint(id, "Recent activity", "Unavailable", "Local activity could not be read")
                else -> SettingsHomePoint(id, "Recent activity", "${activity.records.size} recorded replies", "${activity.records.count { it.status == "COMPLETED" }} completed · ${activity.records.count { it.status in setOf("FAILED", "INTERRUPTED") }} failed/interrupted · latest 250")
            }
            SettingsHomeConfiguration.TOOLS -> {
                val selected = configuration.selectedToolSubjects
                val subjects = selected.filterNot { parent -> parent.toolName == null && selected.any { it.toolName != null && it.copy(toolName = null) == parent } }.map { subject ->
                    val group = toolGroups.find { it.subject == subject.copy(toolName = null) }
                    val status = dev.chungjungsoo.gptmobile.data.home.homeToolSubjectStatus(subject, group)
                    SettingsHomePoint("${subject.kind}:${subject.ownerUid}:${subject.toolName.orEmpty()}", subject.toolName ?: group?.name ?: "Unavailable plugin", status.label, status.detail.ifBlank { group?.name.orEmpty() }, status.tone)
                }
                val tone = when {
                    subjects.isEmpty() -> SettingsHomeTone.UNKNOWN
                    subjects.any { it.tone == SettingsHomeTone.NEGATIVE } -> SettingsHomeTone.NEGATIVE
                    subjects.all { it.tone == SettingsHomeTone.POSITIVE } -> SettingsHomeTone.POSITIVE
                    else -> SettingsHomeTone.UNKNOWN
                }
                SettingsHomePoint(id, "Plugins & Tools", "${subjects.size} selected", if (subjects.isEmpty()) "Choose plugins and tools" else "Observed status; individual grants still apply", tone, subjects = subjects)
            }
            SettingsHomeConfiguration.PRIVACY -> SettingsHomePoint(id, "Privacy choices", "Diagnostics ${if (features.diagnosticsCollection) "on" else "off"}", "Local Assist ${if (features.localAssist) "on" else "off"} · location tool ${if (features.deviceLocationTool) "allowed" else "off"}")
            SettingsHomeConfiguration.BACKUP -> SettingsHomePoint(id, "Backup & Restore", backup.lastBackupEpochMs?.let { settingsHomeAge(it / 1000, nowSeconds) } ?: "No recorded backup", "${backup.backupCount} backups recorded by this app")
            else -> SettingsHomePoint(id, "Unavailable category", "Unavailable", "Choose Home categories")
        }
    }
}

internal fun homeWalletConnection(configuration: SettingsHomeConfiguration, connections: List<ProviderConnection>): ProviderConnection? {
    val candidates = connections.filter { it.compatibleType == ClientType.OPENROUTER }
    val selected = configuration.walletConnectionUid
    return if (selected == null) candidates.singleOrNull() else candidates.find { it.uid == selected }
}

internal fun walletFailureText(failure: AccountFailure): String = when (failure) {
    AccountFailure.ROLE -> "This key does not have account management access"
    AccountFailure.INVALID, AccountFailure.EXPIRED -> "Management access is invalid or expired"
    AccountFailure.FORBIDDEN -> "Account balance access was denied"
    AccountFailure.BILLING -> "OpenRouter returned a billing restriction"
    AccountFailure.RATE_LIMITED -> "OpenRouter rate limited the request; try again later"
    AccountFailure.MALFORMED -> "OpenRouter did not return a valid wallet balance"
    AccountFailure.ENDPOINT -> "Choose a supported OpenRouter endpoint"
    AccountFailure.NETWORK -> "The account request could not finish"
}
