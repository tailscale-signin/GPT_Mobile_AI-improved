package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditSelectedModelServicesTest {
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
