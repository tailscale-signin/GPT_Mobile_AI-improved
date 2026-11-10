package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditSelectedModelServicesTest {
    @Test fun profileEditWhileCredentialRevisionSuspendsCannotProduceAUsableSnapshot() = runBlocking {
        val settings = mockk<SettingRepository>()
        val vault = mockk<SecretVault>()
        val accepted = PlatformV2(uid = "selected", name = "Selected", model = "model", enabled = true, secretRef = "ref")
        var current = accepted
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { settings.fetchPlatformV2s() } answers { listOf(current) }
        coEvery { vault.recordRevision("ref") } coAnswers {
            entered.complete(Unit)
            release.await()
            "a".repeat(64)
        }
        val pending = async { AppAuditSelectedModelServices(settings, vault).target(accepted.uid) }
        entered.await()
        current = accepted.copy(model = "replacement", enabled = false)
        release.complete(Unit)
        val snapshot = checkNotNull(pending.await())
        assertFalse(snapshot.configured)
        assertNull(snapshot.configurationRevision)
        coVerify(exactly = 0) { vault.read(any()) }
    }

    @Test fun ownerOrCredentialChangeDuringRevisionReadInvalidatesTheSnapshot() = runBlocking {
        for (rotateCredential in listOf(false, true)) {
            val settings = mockk<SettingRepository>()
            val vault = mockk<SecretVault>()
            val profile = PlatformV2(uid = "selected", name = "Selected", model = "model", secretRef = "ref", providerConnectionUid = "owner", apiUrl = "https://selected.invalid/v1")
            var owner = ProviderConnection(uid = "owner", name = "Owner", compatibleType = profile.compatibleType, apiUrl = profile.apiUrl, secretRef = profile.secretRef)
            var revision = "a".repeat(64)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
            coEvery { settings.getProviderConnection("owner") } answers { owner }
            coEvery { vault.recordRevision("ref") } coAnswers {
                val captured = revision
                entered.complete(Unit)
                release.await()
                captured
            }
            val pending = async { AppAuditSelectedModelServices(settings, vault).target(profile.uid) }
            entered.await()
            if (rotateCredential) revision = "b".repeat(64) else owner = owner.copy(apiUrl = "https://changed.invalid/v1")
            release.complete(Unit)
            val snapshot = checkNotNull(pending.await())
            assertFalse(snapshot.configured)
            assertNull(snapshot.configurationRevision)
        }
    }

    @Test
    fun emptySelectionDoesNotReadSavedProfilesOrCredentials() = runBlocking {
        val settings = mockk<SettingRepository>()
        val vault = mockk<SecretVault>()
        assertTrue(AppAuditSelectedModelServices(settings, vault).inventory(emptyList()).isEmpty())
        confirmVerified(settings, vault)
    }

    @Test
    fun onlySelectedProfileIsBoundAndOpaqueCredentialRotationChangesItsRevision() = runBlocking {
        val settings = mockk<SettingRepository>()
        val vault = mockk<SecretVault>()
        val profile = PlatformV2(uid = "selected", name = "PRIVATE_PROFILE", model = "PRIVATE_MODEL", apiUrl = "https://private.invalid/v1", secretRef = "selected_ref")
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile, profile.copy(uid = "other", secretRef = "other_ref"))
        coEvery { vault.recordRevision("selected_ref") } returns "a".repeat(64)
        val services = AppAuditSelectedModelServices(settings, vault)
        val first = services.inventory(listOf("selected", "selected")).single()
        assertEquals("model:selected", first.key)
        assertTrue(first.configured)
        assertFalse(first.toString().contains("PRIVATE_"))
        assertFalse(first.toString().contains("private.invalid"))
        assertEquals(first, services.target("selected"))
        coEvery { vault.recordRevision("selected_ref") } returns "b".repeat(64)
        val rotated = services.target("selected")!!
        assertNotEquals(first.configurationRevision, rotated.configurationRevision)
        coVerify(exactly = 0) { vault.recordRevision("other_ref") }
        coVerify(exactly = 0) { vault.read(any()) }
        coVerify(exactly = 0) { vault.put(any(), any()) }
    }

    @Test
    fun unreadableCredentialAndMissingOwnerDoNotProduceUsableConfigurationBindings() = runBlocking {
        val settings = mockk<SettingRepository>()
        val vault = mockk<SecretVault>()
        val profile = PlatformV2(uid = "selected", name = "Private profile", model = "model", secretRef = "selected_ref")
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
        coEvery { vault.recordRevision("selected_ref") } returns null
        val services = AppAuditSelectedModelServices(settings, vault)
        val unreadable = services.target("selected")!!
        assertFalse(unreadable.configured)
        assertNull(unreadable.configurationRevision)
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile.copy(providerConnectionUid = "removed"))
        coEvery { settings.getProviderConnection("removed") } returns null
        val missingOwner = services.target("selected")!!
        assertFalse(missingOwner.configured)
        assertNull(missingOwner.configurationRevision)
        assertNull(services.target("other"))
        coVerify(exactly = 0) { vault.read(any()) }
    }
}
