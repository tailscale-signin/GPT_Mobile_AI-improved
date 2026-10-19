package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountClient
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterManagementAccessRepository
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterWalletLocalStore
import dev.chungjungsoo.gptmobile.data.openrouter.account.retryAfterSeconds
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.coEvery
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterWalletAccessTest {
    @Test fun saveValidatesOnlyManagementRoleAndReassociationUsesTheExistingProtectedSecret() = runBlocking {
        val settings = mockk<SettingRepository>()
        val connection = ProviderConnection(uid = "account", name = "Account", compatibleType = ClientType.OPENROUTER, apiUrl = "https://openrouter.ai/api/v1")
        var inference = "inference-one"
        coEvery { settings.getProviderConnection("account") } returns connection
        coEvery { settings.getProviderCredentials("account") } answers { inference }
        val paths = mutableListOf<String>()
        val client = OpenRouterAccountClient { url ->
            paths += url.path
            FixtureConnection(url, """{"data":{"label":"test","usage":"0","is_free_tier":false,"is_management_key":true}}""")
        }
        val vault = Vault()
        val local = OpenRouterWalletLocalStore(InMemoryRouterPreferences())
        val access = OpenRouterManagementAccessRepository(settings, vault, client, local)
        access.save("account", "synthetic-management")
        val original = requireNotNull(local.snapshot().bindings["account"])
        assertEquals(listOf("/api/v1/key"), paths)
        assertNull(local.snapshot().observations["account"])
        assertEquals("synthetic-management", vault.values[original.secretRef]?.toString(Charsets.UTF_8))
        assertFalse(local.snapshot().toString().contains("synthetic-management"))
        inference = "inference-two"
        local.invalidateConnection("account")
        access.save("account")
        val rebound = requireNotNull(local.snapshot().bindings["account"])
        assertTrue(rebound.associated)
        assertNotEquals(original.revision, rebound.revision)
        assertNotEquals(original.inferenceRevisions, rebound.inferenceRevisions)
        assertEquals(listOf("/api/v1/key", "/api/v1/key"), paths)
        assertEquals(1, vault.values.size)
        access.remove("account")
        assertTrue(vault.values.isEmpty())
        assertNull(local.snapshot().bindings["account"])
    }

    @Test fun retryAfterHonorsBothLongDelaysAndHttpDates() {
        assertEquals(7200L, retryAfterSeconds("7200", 0))
        assertEquals(60L, retryAfterSeconds("Thu, 1 Jan 1970 00:01:00 GMT", 0))
        assertEquals(0L, retryAfterSeconds("Thu, 1 Jan 1970 00:01:00 GMT", 100))
        assertNull(retryAfterSeconds("invalid", 0))
    }

    private class Vault : SecretVault {
        val values = mutableMapOf<String, ByteArray>()
        override suspend fun put(secretRef: String, secret: ByteArray) {
            values[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = values[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            values.remove(secretRef)?.fill(0)
        }
    }

    private class FixtureConnection(url: URL, private val body: String) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    }
}
