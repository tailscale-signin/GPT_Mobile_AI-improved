package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayVerifiedScopeStoreTest {
    private val profile = PlatformV2(
        uid = "saved-profile",
        name = "Original label",
        compatibleType = ClientType.LLAMA,
        apiUrl = "http://192.168.1.9:8090",
        token = "private-test-credential",
        secretRef = "saved-credential",
        model = "original-model",
        providerConnectionUid = "saved-provider-connection"
    )

    @Test fun verifiedAccountSurvivesANewStoreInstanceWithTheSameSavedCredentialRecord() = runBlocking {
        val vault = vault()
        val store = GatewayVerifiedScopeStore(vault)
        val revision = requireNotNull(store.configurationRevision(profile))
        val saved = requireNotNull(store.remember(profile.uid, revision, "installation", "account"))

        assertTrue(saved.verifiedAtMillis > 0)
        assertEquals(saved, GatewayVerifiedScopeStore(vault).verified(profile))
        assertEquals(revision, GatewayVerifiedScopeStore(vault).configurationRevision(profile))
        assertEquals(1, vault.scopeRecords().size)
    }

    @Test fun credentialReplacementInvalidatesPriorAccountEvenWhenTheTokenTextIsUnchanged() = runBlocking {
        val vault = vault()
        val store = GatewayVerifiedScopeStore(vault)
        val original = requireNotNull(store.configurationRevision(profile))
        store.remember(profile.uid, original, "installation", "first-account")

        vault.replace(requireNotNull(profile.secretRef), requireNotNull(profile.token))
        val replacement = requireNotNull(store.configurationRevision(profile))
        assertNotEquals(original, replacement)
        assertNull(store.verified(profile))
        store.remember(profile.uid, replacement, "installation", "replacement-account")
        assertEquals("replacement-account", GatewayVerifiedScopeStore(vault).verified(profile)?.principalId)
    }

    @Test fun staleHydratedTokenCannotClaimTheReplacementCredentialRevision() = runBlocking {
        val vault = vault()
        val store = GatewayVerifiedScopeStore(vault)
        val original = requireNotNull(store.configurationRevision(profile))
        store.remember(profile.uid, original, "installation", "first-account")
        vault.replace(requireNotNull(profile.secretRef), "replacement-private-credential")

        assertNull(store.configurationRevision(profile))
        assertNull(store.verified(profile))
        val refreshed = profile.copy(token = "replacement-private-credential")
        assertNotEquals(original, store.configurationRevision(refreshed))
        assertNull(store.verified(refreshed))
    }

    @Test fun aCredentialChangeDuringHydrationCannotProduceAVerifiedConfiguration() = runBlocking {
        val vault = vault()
        val store = GatewayVerifiedScopeStore(vault)
        vault.afterRead = { reference ->
            if (reference == profile.secretRef) vault.replace(reference, requireNotNull(profile.token))
        }

        assertNull(store.configurationRevision(profile))
        assertTrue(vault.readBuffers.all { bytes -> bytes.all { it == 0.toByte() } })
        assertTrue(vault.scopeRecords().isEmpty())
    }

    @Test fun missingUnsupportedOrMalformedCredentialProofNeverCreatesCachedAccountAuthority() = runBlocking {
        for (invalid in listOf<String?>(null, "unknown", "A".repeat(64))) {
            val vault = vault().apply { revisions[requireNotNull(profile.secretRef)] = invalid }
            val store = GatewayVerifiedScopeStore(vault)
            assertNull(store.configurationRevision(profile))
            assertNull(store.verified(profile))
            assertNull(store.remember(profile.uid, invalid, "installation", "account"))
            assertTrue(vault.scopeRecords().isEmpty())
        }
        val store = GatewayVerifiedScopeStore(vault())
        assertNull(store.configurationRevision(profile.copy(secretRef = "missing-record")))
        assertNull(store.configurationRevision(profile.copy(secretRef = null)))
        for (endpoint in listOf("invalid endpoint", "https://user:credential@example.com", "https://example.com?token=private", "https://example.com#fragment")) {
            assertNull(store.configurationRevision(profile.copy(apiUrl = endpoint)))
        }
    }

    @Test fun anUnauthenticatedConnectionHasAStableScopeWithoutInventingCredentialProof() = runBlocking {
        val vault = RevisionVault()
        val store = GatewayVerifiedScopeStore(vault)
        val unauthenticated = profile.copy(secretRef = null, token = null)
        val revision = requireNotNull(store.configurationRevision(unauthenticated))
        val saved = store.remember(profile.uid, revision, "installation", "anonymous-account")

        assertEquals(saved, GatewayVerifiedScopeStore(vault).verified(unauthenticated.copy(token = "")))
        assertEquals(revision, store.configurationRevision(unauthenticated.copy(apiUrl = profile.apiUrl + "/")))
        assertTrue(vault.revisionReads.isEmpty())
        assertNull(store.configurationRevision(unauthenticated.copy(token = "new-unpersisted-token")))
    }

    @Test fun newlyVerifiedGatewayOrPrincipalReplacesOnlyThePreviousScopedReceipt() = runBlocking {
        val vault = vault()
        val store = GatewayVerifiedScopeStore(vault)
        val revision = requireNotNull(store.configurationRevision(profile))
        val first = store.remember(profile.uid, revision, "installation", "account")
        assertEquals(1L, store.changes.value)
        assertEquals(first, store.remember(profile.uid, revision, "installation", "account"))
        assertEquals(1L, store.changes.value)

        store.remember(profile.uid, revision, "replacement-installation", "account")
        assertEquals("replacement-installation", store.verified(profile)?.gatewayId)
        store.remember(profile.uid, revision, "replacement-installation", "replacement-account")
        assertEquals("replacement-account", store.verified(profile)?.principalId)
        assertEquals(3L, store.changes.value)
        assertEquals(1, vault.scopeRecords().size)
        assertNull(store.remember(profile.uid, revision, "", "account"))
        assertNull(store.remember(profile.uid, revision, "installation", "invalid\naccount"))
        assertEquals(3L, store.changes.value)
    }

    @Test fun labelsAndModelSelectionDoNotChangeAccountProofButExecutionDestinationDoes() = runBlocking {
        val store = GatewayVerifiedScopeStore(vault())
        val revision = requireNotNull(store.configurationRevision(profile))
        val saved = store.remember(profile.uid, revision, "installation", "account")
        assertEquals(saved, store.verified(profile.copy(name = "Renamed", model = "another-model", temperature = 0.5f)))

        for (changed in listOf(
            profile.copy(uid = "another-profile"),
            profile.copy(apiUrl = "http://192.168.1.10:8090"),
            profile.copy(compatibleType = ClientType.OLLAMA),
            profile.copy(providerConnectionUid = "another-provider-connection")
        )) {
            assertNotEquals(revision, store.configurationRevision(changed))
            assertNull(store.verified(changed))
        }
    }

    @Test fun thePrivateScopeRecordContainsNoTokenOrPlaintextTokenFingerprint() = runBlocking {
        val vault = vault()
        val store = GatewayVerifiedScopeStore(vault)
        val revision = requireNotNull(store.configurationRevision(profile))
        store.remember(profile.uid, revision, "installation", "account")
        val record = vault.scopeRecords().entries.single()
        val encoded = record.value.toString(Charsets.UTF_8)
        val plaintextHash = MessageDigest.getInstance("SHA-256").digest(requireNotNull(profile.token).toByteArray()).joinToString("") { "%02x".format(it) }

        assertEquals(setOf("configurationRevision", "gatewayId", "principalId", "verifiedAtMillis"), Json.parseToJsonElement(encoded).jsonObject.keys)
        assertFalse(encoded.contains(requireNotNull(profile.token)))
        assertFalse(encoded.contains(requireNotNull(profile.secretRef)))
        assertFalse(encoded.contains(plaintextHash))
        assertFalse(record.key.contains(requireNotNull(profile.token)))
        assertFalse(record.key.contains(plaintextHash))
        assertTrue(vault.readBuffers.all { bytes -> bytes.all { it == 0.toByte() } })
        assertTrue(vault.writeBuffers.all { bytes -> bytes.all { it == 0.toByte() } })
        assertEquals(profile.token, vault.value(requireNotNull(profile.secretRef))?.toString(Charsets.UTF_8))
    }

    @Test fun corruptOrOversizedSavedScopeRemainsUnknownAndItsReadBufferIsCleared() = runBlocking {
        val vault = vault()
        val store = GatewayVerifiedScopeStore(vault)
        val revision = requireNotNull(store.configurationRevision(profile))
        store.remember(profile.uid, revision, "installation", "account")
        val reference = vault.scopeRecords().keys.single()
        for (invalid in listOf("not-json", "x".repeat(4097), "{\"configurationRevision\":\"$revision\",\"gatewayId\":\"installation\",\"principalId\":\"account\",\"verifiedAtMillis\":0}")) {
            vault.replace(reference, invalid)
            assertNull(store.verified(profile))
        }
        assertTrue(vault.readBuffers.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test fun cancelledCredentialAccessIsNotConvertedIntoAnOfflineAccountResult() = runBlocking {
        val vault = vault().apply { readFailure = CancellationException("cancelled") }
        val failure = runCatching { GatewayVerifiedScopeStore(vault).configurationRevision(profile) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    private fun vault() = RevisionVault().apply { replace(requireNotNull(profile.secretRef), requireNotNull(profile.token)) }

    private class RevisionVault : SecretVault {
        private val values = linkedMapOf<String, ByteArray>()
        private var nextRevision = 0L
        val revisions = mutableMapOf<String, String?>()
        val revisionReads = mutableListOf<String>()
        val readBuffers = mutableListOf<ByteArray>()
        val writeBuffers = mutableListOf<ByteArray>()
        var afterRead: ((String) -> Unit)? = null
        var readFailure: Exception? = null

        fun replace(reference: String, value: String) {
            values[reference] = value.toByteArray()
            revisions[reference] = (++nextRevision).toString(16).padStart(64, '0')
        }

        fun value(reference: String) = values[reference]?.copyOf()
        fun scopeRecords() = values.filterKeys { it.startsWith("gateway_scope_") }.mapValues { it.value.copyOf() }

        override suspend fun put(secretRef: String, secret: ByteArray) {
            writeBuffers += secret
            values[secretRef] = secret.copyOf()
            revisions[secretRef] = (++nextRevision).toString(16).padStart(64, '0')
        }

        override suspend fun read(secretRef: String): ByteArray? {
            readFailure?.let { throw it }
            val bytes = values[secretRef]?.copyOf()?.also { readBuffers += it }
            afterRead?.invoke(secretRef)
            return bytes
        }

        override suspend fun recordRevision(secretRef: String): String? {
            revisionReads += secretRef
            return revisions[secretRef]
        }

        override suspend fun delete(secretRef: String) {
            values.remove(secretRef)
            revisions.remove(secretRef)
        }
    }
}
