package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.backup.BackupStatus
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeActivity
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeConfiguration
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeTone
import dev.chungjungsoo.gptmobile.data.openrouter.account.AccountFailure
import dev.chungjungsoo.gptmobile.data.openrouter.account.SettingsHomeWalletSnapshot
import dev.chungjungsoo.gptmobile.data.openrouter.account.SettingsHomeWalletState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHomeSummaryTest {
    private val profile = PlatformV2(uid = "profile", name = "Selected AI", model = "model", compatibleType = ClientType.OPENROUTER, providerConnectionUid = "connection")
    private val connection = ProviderConnection(uid = "connection", name = "Account", compatibleType = ClientType.OPENROUTER, apiUrl = "https://openrouter.ai/api/v1", updatedAt = 10)
    private val record = SettingsHomeActivity("run", "profile", "OPENROUTER", "model", "COMPLETED", 1_000, null, 1_000, null, null, null)
    private val snapshot = SettingsHomeWalletSnapshot("connection", "Account", 10, "6.99999999", "10.00000001", "3.00000002", 1_000)

    @Test fun selectedProfileUsesObservedStatusAndDoesNotTreatEnabledAsOnline() {
        val enabled = points(SettingsHomeConfiguration.profile("profile")).single().subjects.single()
        assertEquals("Not checked", enabled.value)
        assertEquals(SettingsHomeTone.UNKNOWN, enabled.tone)
        val disabled = points(SettingsHomeConfiguration.profile("profile"), profiles = listOf(profile.copy(enabled = false))).single().subjects.single()
        assertEquals("Disabled", disabled.value)
        assertEquals(SettingsHomeTone.NEGATIVE, disabled.tone)
    }

    @Test fun staleAndFailedRequestsHaveDistinctStatesInsideOneCategory() {
        val stale = points(SettingsHomeConfiguration.profile("profile"), records = listOf(record), now = 1_601).single().subjects.single()
        assertEquals(SettingsHomeTone.STALE, stale.tone)
        val failed = points(SettingsHomeConfiguration.profile("profile"), records = listOf(record.copy(status = "FAILED"))).single().subjects.single()
        assertEquals("Last request failed", failed.value)
        assertEquals(SettingsHomeTone.NEGATIVE, failed.tone)
    }

    @Test fun providerChangesDoNotReuseAnEarlierSuccessfulHealthResult() {
        val changed = points(SettingsHomeConfiguration.profile("profile"), records = listOf(record), connections = listOf(connection.copy(updatedAt = 1_001))).single().subjects.single()
        assertEquals("Not checked", changed.value)
        val wrongModel = points(SettingsHomeConfiguration.profile("profile"), records = listOf(record.copy(model = "old"))).single().subjects.single()
        assertEquals("Not checked", wrongModel.value)
        val wrongProvider = points(SettingsHomeConfiguration.profile("profile"), records = listOf(record.copy(provider = "OPENAI"))).single().subjects.single()
        assertEquals("Not checked", wrongProvider.value)
    }

    @Test fun walletDisplaysConnectLoadingUnavailableLiveHiddenAndStaleSeparately() {
        assertEquals("Manage OpenRouter", points(SettingsHomeConfiguration.WALLET).single().value)
        assertEquals("Available after your next response", points(SettingsHomeConfiguration.WALLET, wallet = SettingsHomeWalletState(associationValid = true)).single().value)
        assertEquals(SettingsHomeTone.ACTIVE, points(SettingsHomeConfiguration.WALLET, wallet = SettingsHomeWalletState(loading = true)).single().tone)
        val unavailable = points(SettingsHomeConfiguration.WALLET, wallet = SettingsHomeWalletState(failure = AccountFailure.NETWORK)).single()
        assertEquals("Balance unavailable", unavailable.value)
        assertFalse(unavailable.value.contains("0"))
        val live = points(SettingsHomeConfiguration.WALLET, wallet = SettingsHomeWalletState(snapshot)).single()
        assertTrue(live.value.endsWith("USD"))
        assertEquals(SettingsHomeTone.POSITIVE, live.tone)
        assertEquals("••••", points(SettingsHomeConfiguration.WALLET, wallet = SettingsHomeWalletState(snapshot), hidden = true).single().value)
        val stale = points(SettingsHomeConfiguration.WALLET, wallet = SettingsHomeWalletState(snapshot), now = 1_601).single()
        assertEquals(SettingsHomeTone.STALE, stale.tone)
        assertTrue(stale.detail.startsWith("Stale"))
    }

    @Test fun deletedWalletConnectionNeverSilentlySelectsAnotherAccount() {
        val configuration = SettingsHomeConfiguration(walletConnectionUid = "deleted")
        assertEquals(null, homeWalletConnection(configuration, listOf(connection)))
        val wrong = points(SettingsHomeConfiguration.WALLET, wallet = SettingsHomeWalletState(snapshot.copy(connectionUid = "other"))).single()
        assertEquals("Manage OpenRouter", wrong.value)
    }

    @Test fun missingItemAndUnavailableLocalDataRemainExplicit() {
        assertEquals("Unavailable profile", points("profile:deleted").single().subjects.single().title)
        val features = AppFeatureSettings(settingsHome = SettingsHomeConfiguration(listOf(SettingsHomeConfiguration.USAGE)))
        val result = settingsHomePoints(features, emptyList(), emptyList(), SettingsHomeActivityState(loaded = true, unavailable = true), null, SettingsHomeWalletState(), BackupStatus(), 1_000).single()
        assertEquals("Unavailable", result.value)
    }

    private fun points(
        id: String,
        profiles: List<PlatformV2> = listOf(profile),
        records: List<SettingsHomeActivity> = emptyList(),
        connections: List<ProviderConnection> = listOf(connection),
        wallet: SettingsHomeWalletState = SettingsHomeWalletState(),
        now: Long = 1_000,
        hidden: Boolean = false
    ) = settingsHomePoints(AppFeatureSettings(settingsHome = SettingsHomeConfiguration(listOf(id), "connection", hidden)), profiles, connections, SettingsHomeActivityState(records, loaded = true), emptyList(), wallet, BackupStatus(), now)
}
