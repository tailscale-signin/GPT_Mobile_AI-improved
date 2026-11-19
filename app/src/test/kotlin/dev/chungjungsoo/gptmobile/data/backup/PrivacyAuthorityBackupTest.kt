package dev.chungjungsoo.gptmobile.data.backup

import android.content.pm.ApplicationInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.chungjungsoo.gptmobile.data.permissions.ProviderActivationStore
import dev.chungjungsoo.gptmobile.data.privacy.PersonalSetupVault
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyPreferencesFixture
import dev.chungjungsoo.gptmobile.data.privacy.ProviderApprovalSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyAuthorityBackupTest {
    @Test
    fun completeBackupDoesNotExportPersonalConfirmationAuthorityOrPendingWrites() {
        withFixture { preferences, backup, directory ->
            val shared = directory.resolve("shared_prefs").apply { mkdirs() }
            for (name in listOf("privacy_authority", "privacy_personal_setup_v1", "privacy_setup_mutations_v1", "ordinary_settings")) {
                shared.resolve("$name.xml").writeText("<map />")
                preferences.values[name] = mutableMapOf("marker" to name)
            }

            val exported = backup.readShared()

            assertFalse(exported.containsKey("privacy_authority"))
            assertFalse(exported.containsKey("privacy_personal_setup_v1"))
            assertFalse(exported.containsKey("privacy_setup_mutations_v1"))
            assertEquals(BackupValue("string", "ordinary_settings"), exported.getValue("ordinary_settings")["marker"])
        }
    }

    @Test
    fun craftedRestoreCannotInstallPrivacyEvidenceOrEraseUncertainProviderWrites() = runBlocking {
        withFixture { preferences, backup, _ ->
            val source = ProviderApprovalSource(preferences.context, PersonalSetupVault())
            val oldRevision = source.personalRevision()
            preferences.values.getValue("privacy_personal_setup_v1")["records"] = "local-personal-record"
            preferences.values.getValue("privacy_authority").apply {
                put("issuer", "local-trust-anchor")
                put("signed", "local-signed-evidence")
                put("pending_guardrail_update", "local-guardrail-intent")
            }
            val pending = """{"operation":"policy-creation","phase":"unknown"}"""
            assertTrue(source.beginSetupMutation(pending))
            val incoming = mapOf(
                "privacy_personal_setup_v1" to mapOf("revision" to BackupValue("string", oldRevision), "records" to BackupValue("string", "injected-personal-proof")),
                "privacy_authority" to mapOf("issuer" to BackupValue("string", "imported-trust-anchor"), "signed" to BackupValue("string", "imported-signed-evidence")),
                "privacy_setup_mutations_v1" to emptyMap(),
                "ordinary_settings" to mapOf("choice" to BackupValue("string", "restored"))
            )
            mockkConstructor(ProviderActivationStore::class)
            try {
                coEvery { anyConstructed<ProviderActivationStore>().clear() } returns Unit
                backup.replace(emptyMap(), incoming)
            } finally {
                unmockkConstructor(ProviderActivationStore::class)
            }

            assertFalse(preferences.values.getValue("privacy_personal_setup_v1").containsKey("records"))
            assertNotEquals(oldRevision, source.personalRevision())
            assertEquals("local-trust-anchor", preferences.values.getValue("privacy_authority")["issuer"])
            assertEquals("local-signed-evidence", preferences.values.getValue("privacy_authority")["signed"])
            assertEquals("local-guardrail-intent", preferences.values.getValue("privacy_authority")["pending_guardrail_update"])
            assertEquals(pending, source.pendingSetupMutation())
            assertEquals("restored", preferences.values.getValue("ordinary_settings")["choice"])
        }
    }

    private inline fun withFixture(action: (PrivacyPreferencesFixture, CompleteBackupPreferences, java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("privacy-backup-test-").toFile()
        try {
            val preferences = PrivacyPreferencesFixture()
            val info = mockk<ApplicationInfo>()
            info.dataDir = directory.path
            every { preferences.context.applicationInfo } returns info
            val store = mockk<DataStore<Preferences>>()
            var current = emptyPreferences()
            every { store.data } answers { flowOf(current) }
            coEvery { store.updateData(any()) } coAnswers {
                current = firstArg<suspend (Preferences) -> Preferences>().invoke(current)
                current
            }
            action(preferences, CompleteBackupPreferences(preferences.context, store), directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
