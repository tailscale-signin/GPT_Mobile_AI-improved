package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigurationIdentityTest {
    @Test fun sameCapturedConfigurationIsStableButCredentialAndConnectionChangesAreIsolated() {
        val original = PlatformV2(uid = UUID.randomUUID().toString(), name = "Fixture", compatibleType = ClientType.OPENAI, model = "model", apiUrl = "https://fixture.invalid/v1", token = "fixture-credential-one", providerConnectionUid = "connection-one")
        val first = ProviderConfigurationIdentity.capture(original)
        assertTrue(first.matches(Regex("[a-f0-9]{64}")))
        assertEquals(first, ProviderConfigurationIdentity.capture(original.copy()))
        assertEquals(first, ProviderConfigurationIdentity.capture(original.copy(name = "Renamed fixture")))
        assertNotEquals(ConfiguredDispatchAuthority.profileRevision(original), first)
        for (changed in listOf(
            original.copy(token = "fixture-credential-two"),
            original.copy(providerConnectionUid = "connection-two"),
            original.copy(secretRef = "replacement-credential-record"),
            original.copy(apiUrl = "https://replacement.invalid/v1")
        )) {
            val before = ProviderConfigurationIdentity.capture(original)
            val next = ProviderConfigurationIdentity.capture(changed)
            assertNotEquals(before, next)
            assertEquals(next, ProviderConfigurationIdentity.capture(changed))
            assertNotEquals(next, ProviderConfigurationIdentity.capture(original))
        }
        // Returning to old credentials after a repair does not revive the old group.
        assertNotEquals(first, ProviderConfigurationIdentity.capture(original))
    }
}
