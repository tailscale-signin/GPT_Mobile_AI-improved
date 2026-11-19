package dev.chungjungsoo.gptmobile.data.privacy

import android.content.Context
import android.content.RestrictionsManager
import android.content.SharedPreferences
import android.os.Bundle
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.every
import io.mockk.mockk
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPersonalSetupTest {
    private val profile = PlatformV2(name = "Privacy account", compatibleType = ClientType.OPENROUTER, apiUrl = "https://openrouter.ai/api/v1", model = "example/model", token = "synthetic-inference-key")

    private fun proof(token: String = profile.token.orEmpty()): ProviderApproval {
        val now = System.currentTimeMillis()
        val digest = OpenRouterIdentity.digest(token)
        return ProviderApproval(
            endpoint = profile.apiUrl + "/chat/completions",
            model = "*",
            credentialSha256 = digest,
            upstream = "*",
            policyUrl = PrivacyProfileEligibility.OPENROUTER_TERMS,
            verifiedAtMillis = now - 1000,
            expiresAtMillis = now + 3_600_000,
            feature = "stateless-text-v1",
            retentionTier = "zdr",
            noTraining = true,
            loggingDisabled = true,
            externalToolsDisabled = true,
            providerDefaultsDisabled = true,
            safetyRetentionExcluded = true,
            providerKeyHash = digest,
            policySha256 = "a".repeat(64),
            scope = "openrouter-personal-v1",
            personalWorkspaceId = "11111111-2222-3333-4444-555555555555",
            setupId = UUID.randomUUID().toString(),
            modelAvailabilityPolicyId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        )
    }

    @Test
    fun personalSetupRequiresCurrentLocalEpochAndStoresOnlyCredentialBoundEvidence() = runBlocking {
        val fixture = Fixture()
        val revision = fixture.source.personalRevision()
        val record = proof()
        fixture.source.savePersonal(record, "management-key", revision)

        assertEquals(listOf(record), fixture.source.records())
        assertNotEquals(revision, fixture.source.personalRevision())
        assertFalse(fixture.source.personalAllowed(revision))
        assertEquals("management-key", fixture.source.managementKey())
        assertTrue(fixture.source.personalSetupSummary(profile)!!.startsWith("Default plugins confirmed by you until "))
        assertTrue(record.covers(profile, record.endpoint, record.policyUrl, System.currentTimeMillis()))
        assertFalse(record.covers(profile.copy(token = "another-account"), record.endpoint, record.policyUrl, System.currentTimeMillis()))
        assertFalse(fixture.preferences.values.values.any { values -> values.values.any { it.toString().contains("management-key") } })
    }

    @Test
    fun anotherInferenceKeyReusesTheSameManagementCredentialWithoutReplacingEarlierEvidence() = runBlocking {
        val fixture = Fixture()
        val first = proof()
        val second = proof("second-inference-key")
        fixture.source.savePersonal(first, "management-key", fixture.source.personalRevision())
        fixture.source.savePersonal(second, "management-key", fixture.source.personalRevision())
        assertEquals(listOf(first, second), fixture.source.records())
        val replacement = proof()
        fixture.source.savePersonal(replacement, "management-key", fixture.source.personalRevision())
        assertEquals(listOf(second, replacement), fixture.source.records())
    }

    @Test
    fun changingManagementAccountInvalidatesOtherKeyApprovals() = runBlocking {
        val fixture = Fixture()
        fixture.source.savePersonal(proof(), "first-management", fixture.source.personalRevision())
        val replacement = proof("another-inference-key")
        fixture.source.savePersonal(replacement, "second-management", fixture.source.personalRevision())
        assertEquals(listOf(replacement), fixture.source.records())
    }

    @Test
    fun managedEmptyAndMalformedConfigurationNeverFallBackToPersonalEvidence() = runBlocking {
        val fixture = Fixture()
        fixture.source.savePersonal(proof(), "management-key", fixture.source.personalRevision())
        fixture.managedPresent = true
        fixture.managedRaw = "[]"
        assertTrue(fixture.source.managedSetup())
        assertTrue(fixture.source.records().isEmpty())
        assertFalse(fixture.source.personalAllowed(fixture.source.personalRevision()))
        fixture.managedRaw = "invalid-json"
        assertThrows(Exception::class.java) { fixture.source.records() }
        fixture.managedRaw = null
        assertTrue(fixture.source.records().isEmpty())
    }

    @Test
    fun personalScopeCannotBeImportedThroughManagedOrSignedAuthority() {
        val fixture = Fixture()
        val record = proof()
        fixture.managedPresent = true
        fixture.managedRaw = Json.encodeToString(listOf(record))
        assertThrows(IllegalArgumentException::class.java) { fixture.source.records() }
        val pair = issuer()
        assertThrows(IllegalArgumentException::class.java) { ProviderApprovalSource.verify(publicKey(pair), signed(pair, listOf(record))) }
    }

    @Test
    fun trustedIssuerRevokesPersonalSetupAndEmptySignedApprovalHasSoleAuthority() = runBlocking {
        val fixture = Fixture()
        fixture.source.savePersonal(proof(), "management-key", fixture.source.personalRevision())
        val revision = fixture.source.personalRevision()
        val pair = issuer()
        fixture.source.trustIssuer(publicKey(pair))
        assertFalse(fixture.source.personalAllowed(revision))
        assertTrue(fixture.source.records().isEmpty())
        assertNull(fixture.source.personalSetupSummary(profile))
        fixture.source.install(signed(pair, emptyList()))
        assertTrue(fixture.source.records().isEmpty())
        assertTrue(runCatching { fixture.source.savePersonal(proof(), "management-key", fixture.source.personalRevision()) }.isFailure)
    }

    @Test
    fun explicitManagementKeyReplacementRevokesPersonalEvidenceEvenForTheSameValue() = runBlocking {
        val fixture = Fixture()
        fixture.source.savePersonal(proof(), "management-key", fixture.source.personalRevision())
        val revision = fixture.source.personalRevision()
        fixture.source.setManagementKey("management-key")
        assertTrue(fixture.source.records().isEmpty())
        assertFalse(fixture.source.personalAllowed(revision))
        assertEquals("management-key", fixture.source.managementKey())
    }

    @Test
    fun stalePreviewAndExpiredConfirmationCannotInstall() = runBlocking {
        val fixture = Fixture()
        val old = fixture.source.personalRevision()
        fixture.source.forget()
        assertTrue(runCatching { fixture.source.savePersonal(proof(), "management-key", old) }.isFailure)
        val expired = proof().copy(expiresAtMillis = System.currentTimeMillis() - 1)
        assertTrue(runCatching { fixture.source.savePersonal(expired, "management-key", fixture.source.personalRevision()) }.isFailure)
        assertTrue(fixture.source.records().isEmpty())
        assertNull(fixture.vault.value)
    }

    @Test
    fun expiredPersonalRecordRemainsVisibleAsExpiredButCannotAuthorizeARequest() = runBlocking {
        val fixture = Fixture()
        val record = proof()
        fixture.source.savePersonal(record, "management-key", fixture.source.personalRevision())
        val expired = record.copy(expiresAtMillis = System.currentTimeMillis() - 1)
        fixture.preferences.values.getValue("privacy_personal_setup_v1")["records"] = Json.encodeToString(listOf(expired))
        assertTrue(fixture.source.personalSetupSummary(profile)!!.contains("expired"))
        assertFalse(fixture.source.records().single().covers(profile, record.endpoint, record.policyUrl, System.currentTimeMillis()))
    }

    @Test
    fun corruptPersonalEvidenceShowsRecoveryGuidanceWithoutAuthorizingTheProfile() {
        val fixture = Fixture()
        fixture.source.personalRevision()
        fixture.preferences.values.getValue("privacy_personal_setup_v1")["records"] = "not-json"
        assertTrue(fixture.source.personalSetupSummary(profile)!!.contains("could not be read"))
        assertThrows(Exception::class.java) { fixture.source.records() }
    }

    @Test
    fun localRevocationDuringSecureStorageWriteCannotReinstallEvidence() = runBlocking {
        val fixture = Fixture()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        fixture.vault.beforeWrite = {
            started.complete(Unit)
            finish.await()
        }
        val revision = fixture.source.personalRevision()
        val saving = async { runCatching { fixture.source.savePersonal(proof(), "management-key", revision) } }
        started.await()
        fixture.source.forget()
        finish.complete(Unit)
        assertTrue(saving.await().isFailure)
        assertTrue(fixture.source.records().isEmpty())
        assertFalse(fixture.source.personalAllowed(revision))
    }

    @Test
    fun failedReplacementDoesNotRestoreEarlierPersonalApproval() = runBlocking {
        val fixture = Fixture()
        fixture.source.savePersonal(proof(), "first-management", fixture.source.personalRevision())
        fixture.vault.failWrite = true
        assertTrue(runCatching { fixture.source.savePersonal(proof(), "second-management", fixture.source.personalRevision()) }.isFailure)
        assertTrue(fixture.source.records().isEmpty())
    }

    @Test
    fun pendingSetupJournalSurvivesForgetRecreationAndAuthorityChanges() {
        val fixture = Fixture()
        val pending = """{"origin":"https://openrouter.ai/api/v1","workspace":"11111111-2222-3333-4444-555555555555","operation":"workspace-privacy","phase":"dispatched"}"""
        assertTrue(fixture.source.beginSetupMutation(pending))
        fixture.source.forget()
        val recreated = ProviderApprovalSource(fixture.preferences.context, fixture.vault)
        assertEquals(pending, recreated.pendingSetupMutation())
        assertFalse(recreated.beginSetupMutation(pending))
        assertFalse(recreated.beginSetupMutation("another-intent"))
        val pair = issuer()
        recreated.trustIssuer(publicKey(pair))
        recreated.install(signed(pair, emptyList()))
        assertEquals(pending, recreated.pendingSetupMutation())
        recreated.confirmSetupMutation("another-intent")
        assertEquals(pending, recreated.pendingSetupMutation())
        recreated.confirmSetupMutation(pending)
        assertNull(recreated.pendingSetupMutation())
        assertTrue(recreated.beginSetupMutation("new-intent"))
    }

    @Test
    fun preferenceClearingGeneratesANewEpochAndCannotReviveOrImportLocalAuthority() = runBlocking {
        val fixture = Fixture()
        val oldRevision = fixture.source.personalRevision()
        val oldRecord = proof()
        fixture.source.savePersonal(oldRecord, "management-key", oldRevision)
        fixture.preferences.values.getValue("privacy_personal_setup_v1").apply {
            clear()
            put("records", Json.encodeToString(listOf(oldRecord)))
        }
        assertNotEquals(oldRevision, fixture.source.personalRevision())
        assertTrue(fixture.source.records().isEmpty())
    }

    private fun issuer(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun publicKey(pair: KeyPair) = Base64.getEncoder().encodeToString(pair.public.encoded)

    private fun signed(pair: KeyPair, records: List<ProviderApproval>): String {
        val payload = Json.encodeToString(records).encodeToByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(payload)
            sign()
        }
        return """{"payload":"${Base64.getEncoder().encodeToString(payload)}","signature":"${Base64.getEncoder().encodeToString(signature)}"}"""
    }

    private class Fixture {
        val preferences = PrivacyPreferencesFixture()
        val vault = PersonalSetupVault()
        var managedPresent = false
        var managedRaw: String? = null
        private val manager = mockk<RestrictionsManager>()
        private val restrictions = mockk<Bundle>()
        val source = ProviderApprovalSource(preferences.context, vault)

        init {
            every { preferences.context.getSystemService(RestrictionsManager::class.java) } returns manager
            every { manager.applicationRestrictions } returns restrictions
            every { restrictions.containsKey("verified_provider_routes") } answers { managedPresent }
            every { restrictions.getString("verified_provider_routes") } answers { managedRaw }
        }
    }
}

internal class PersonalSetupVault : SecretVault {
    var value: String? = null
    var beforeWrite: (suspend () -> Unit)? = null
    var failWrite = false

    override suspend fun put(secretRef: String, secret: ByteArray) {
        beforeWrite?.invoke()
        check(!failWrite) { "Synthetic secure-storage failure" }
        value = secret.decodeToString()
    }

    override suspend fun read(secretRef: String): ByteArray? = value?.encodeToByteArray()

    override suspend fun delete(secretRef: String) {
        value = null
    }
}

internal class PrivacyPreferencesFixture {
    val context = mockk<Context>()
    val values = mutableMapOf<String, MutableMap<String, Any>>()
    private val stores = mutableMapOf<String, SharedPreferences>()

    init {
        every { context.getSharedPreferences(any(), Context.MODE_PRIVATE) } answers {
            val name = firstArg<String>()
            stores.getOrPut(name) { preference(values.getOrPut(name) { mutableMapOf() }) }
        }
        every { context.getSystemService(RestrictionsManager::class.java) } returns null
    }

    private fun preference(values: MutableMap<String, Any>): SharedPreferences {
        val store = mockk<SharedPreferences>()
        every { store.getString(any(), any()) } answers { values[firstArg<String>()] as? String ?: secondArg() }
        every { store.getBoolean(any(), any()) } answers { values[firstArg<String>()] as? Boolean ?: secondArg() }
        every { store.getLong(any(), any()) } answers { values[firstArg<String>()] as? Long ?: secondArg() }
        every { store.all } answers { values.toMap() }
        every { store.edit() } answers {
            var clear = false
            val changes = mutableMapOf<String, Any?>()
            val editor = mockk<SharedPreferences.Editor>()
            every { editor.clear() } answers {
                clear = true
                editor
            }
            every { editor.remove(any()) } answers {
                changes[firstArg()] = null
                editor
            }
            every { editor.putString(any(), any()) } answers {
                changes[firstArg()] = secondArg<String?>()
                editor
            }
            every { editor.putBoolean(any(), any()) } answers {
                changes[firstArg()] = secondArg<Boolean>()
                editor
            }
            every { editor.putLong(any(), any()) } answers {
                changes[firstArg()] = secondArg<Long>()
                editor
            }
            every { editor.putInt(any(), any()) } answers {
                changes[firstArg()] = secondArg<Int>()
                editor
            }
            every { editor.putFloat(any(), any()) } answers {
                changes[firstArg()] = secondArg<Float>()
                editor
            }
            every { editor.putStringSet(any(), any()) } answers {
                changes[firstArg()] = secondArg<Set<String>?>()
                editor
            }
            fun applyChanges() {
                if (clear) values.clear()
                changes.forEach { (name, value) -> if (value == null) values.remove(name) else values[name] = value }
            }
            every { editor.commit() } answers {
                applyChanges()
                true
            }
            every { editor.apply() } answers { applyChanges() }
            editor
        }
        return store
    }
}
