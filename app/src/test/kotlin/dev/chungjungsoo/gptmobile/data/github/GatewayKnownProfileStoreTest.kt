package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayKnownProfileStoreTest {
    private val profile = PlatformV2(uid = "direct-gateway", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.4:8090", model = "model")

    @Test fun confirmedSupportSurvivesRestartWithoutInventingReadinessOrAnAccount() = runBlocking {
        val vault = MemoryVault()
        val scopes = GatewayVerifiedScopeStore(vault)
        val first = GatewayKnownProfileStore(vault, scopes)
        assertFalse(first.remembered(profile))
        assertTrue(first.remember(profile, "instance-one", conversationSyncSupported = true))

        val restarted = GatewayKnownProfileStore(vault, GatewayVerifiedScopeStore(vault))
        assertTrue(restarted.remembered(profile))
        assertTrue(restarted.remembered(profile.copy(name = "Renamed", model = "another-model")))
        assertEquals(null, scopes.verified(profile))
        assertEquals(1L, first.changes.value)
        assertTrue(first.remember(profile, "instance-one", conversationSyncSupported = true))
        assertEquals(1L, first.changes.value)
    }

    @Test fun changedEndpointProviderOrSavedCredentialNeedsFreshSupportEvidence() = runBlocking {
        val vault = MemoryVault()
        val original = profile.copy(token = "credential", secretRef = "credential-record", providerConnectionUid = "provider")
        vault.put("credential-record", "credential".toByteArray())
        val store = GatewayKnownProfileStore(vault, GatewayVerifiedScopeStore(vault))
        assertTrue(store.remember(original, "instance", conversationSyncSupported = true))
        for (changed in listOf(
            original.copy(apiUrl = "http://192.168.1.5:8090"),
            original.copy(compatibleType = ClientType.OLLAMA),
            original.copy(providerConnectionUid = "replacement-provider"),
            original.copy(uid = "another-profile")
        )) {
            assertFalse(store.remembered(changed))
        }

        vault.put("credential-record", "credential".toByteArray())
        assertFalse(store.remembered(original))
        assertTrue(store.remember(original, "instance", conversationSyncSupported = true))
        assertTrue(store.remembered(original))
        val stored = vault.records.filterKeys { it.startsWith("gateway_known_") }.values.single().decodeToString()
        assertFalse(stored.contains("credential-record"))
        assertFalse(stored.contains("credential\""))
    }

    @Test fun oldConnectionFailureCannotEraseTheReplacementConfigurationsEvidence() = runBlocking {
        val vault = MemoryVault()
        val store = GatewayKnownProfileStore(vault, GatewayVerifiedScopeStore(vault))
        val replacement = profile.copy(apiUrl = "http://192.168.1.5:8090")
        store.remember(profile, "old-instance", conversationSyncSupported = true)
        store.remember(replacement, "replacement-instance", conversationSyncSupported = true)
        store.forget(profile)
        assertTrue(store.remembered(replacement))
        store.forget(replacement)
        assertFalse(store.remembered(replacement))
    }

    @Test fun delayedProbeCannotRememberOrForgetANewerCredentialRecord() = runBlocking {
        val vault = MemoryVault()
        val scopes = GatewayVerifiedScopeStore(vault)
        val store = GatewayKnownProfileStore(vault, scopes)
        val authenticated = profile.copy(secretRef = "credential-record", token = "same-credential")
        vault.put("credential-record", "same-credential".toByteArray())
        val before = requireNotNull(scopes.configurationRevision(authenticated))
        vault.put("credential-record", "same-credential".toByteArray())
        val after = requireNotNull(scopes.configurationRevision(authenticated))

        assertFalse(store.remember(authenticated, "old-probe", before, conversationSyncSupported = true))
        assertFalse(store.remembered(authenticated))
        assertTrue(store.remember(authenticated, "new-probe", after, conversationSyncSupported = true))
        store.forget(authenticated, before)
        assertTrue(store.remembered(authenticated))
        store.forget(authenticated, after)
        assertFalse(store.remembered(authenticated))
    }

    @Test fun malformedHintsStayUnknownAndAConfirmedProbeCanRepairThem() = runBlocking {
        val vault = MemoryVault()
        val store = GatewayKnownProfileStore(vault, GatewayVerifiedScopeStore(vault))
        assertFalse(store.remember(profile, ""))
        assertFalse(store.remember(profile, "invalid\ninstance"))
        store.remember(profile, "instance", conversationSyncSupported = true)
        val key = vault.records.keys.single()
        for (invalid in listOf("not-json", "x".repeat(4097))) {
            vault.records[key] = invalid.toByteArray()
            assertFalse(store.remembered(profile))
            assertTrue(store.remember(profile, "instance", conversationSyncSupported = true))
            assertTrue(store.remembered(profile))
        }
        assertTrue(vault.readBuffers.all { bytes -> bytes.all { it == 0.toByte() } })
        assertTrue(vault.writeBuffers.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test fun cancellationIsNotReportedAsAnUnknownOfflineProfile() = runBlocking {
        val vault = MemoryVault().apply { readFailure = CancellationException("cancelled") }
        val store = GatewayKnownProfileStore(vault, GatewayVerifiedScopeStore(vault))
        assertTrue(runCatching { store.remembered(profile) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { store.remember(profile, "instance", conversationSyncSupported = true) }.exceptionOrNull() is CancellationException)
    }

    @Test fun gatewayWithoutSavedConversationSupportAndLegacyHintsDoNotEnableNewQueuedChats() = runBlocking {
        val vault = MemoryVault()
        val store = GatewayKnownProfileStore(vault, GatewayVerifiedScopeStore(vault))
        assertTrue(store.remember(profile, "instance"))
        assertFalse(store.remembered(profile))
        assertTrue(store.remember(profile, "instance", conversationSyncSupported = true))
        assertTrue(store.remembered(profile))
        val key = vault.records.keys.single()
        val current = kotlinx.serialization.json.Json.parseToJsonElement(vault.records.getValue(key).decodeToString()) as kotlinx.serialization.json.JsonObject
        vault.records[key] = kotlinx.serialization.json.JsonObject(current - "conversationSyncSupported").toString().toByteArray()
        assertFalse(GatewayKnownProfileStore(vault, GatewayVerifiedScopeStore(vault)).remembered(profile))
        assertTrue(store.remember(profile, "instance", conversationSyncSupported = true))
        assertTrue(store.remembered(profile))
        store.remember(profile, "instance", conversationSyncSupported = false)
        assertFalse(store.remembered(profile))
    }

    private class MemoryVault : SecretVault {
        val records = mutableMapOf<String, ByteArray>()
        private val revisions = mutableMapOf<String, String>()
        private var revision = 0L
        val readBuffers = mutableListOf<ByteArray>()
        val writeBuffers = mutableListOf<ByteArray>()
        var readFailure: Exception? = null

        override suspend fun put(secretRef: String, secret: ByteArray) {
            records[secretRef] = secret.copyOf()
            writeBuffers += secret
            revisions[secretRef] = (++revision).toString(16).padStart(64, '0')
        }
        override suspend fun read(secretRef: String): ByteArray? {
            readFailure?.let { throw it }
            return records[secretRef]?.copyOf()?.also(readBuffers::add)
        }
        override suspend fun delete(secretRef: String) {
            records.remove(secretRef)
            revisions.remove(secretRef)
        }
        override suspend fun recordRevision(secretRef: String): String? = revisions[secretRef]
    }
}
