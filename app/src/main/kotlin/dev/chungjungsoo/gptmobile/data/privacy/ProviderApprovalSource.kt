package dev.chungjungsoo.gptmobile.data.privacy

import android.content.Context
import android.content.RestrictionsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class SignedApprovals(val payload: String, val signature: String)

/** An issuer is explicitly trusted out of band. The signed file cannot introduce its own key.
 * Managed configuration, when present, is the sole authority and can revoke all routes with [].
 */
@Singleton
class ProviderApprovalSource @Inject constructor(@ApplicationContext private val context: Context, private val vault: SecretVault) {
    private val preferences = context.getSharedPreferences("privacy_authority", Context.MODE_PRIVATE)
    private val personal by lazy { context.getSharedPreferences("privacy_personal_setup_v1", Context.MODE_PRIVATE) }
    private val setupMutations by lazy { context.getSharedPreferences("privacy_setup_mutations_v1", Context.MODE_PRIVATE) }
    private val managementMutex = Mutex()
    private val manager get() = context.getSystemService(RestrictionsManager::class.java)

    @Synchronized
    internal fun records(): List<ProviderApproval> {
        val managed = manager?.applicationRestrictions
        if (managed?.containsKey("verified_provider_routes") == true) {
            val raw = managed.getString("verified_provider_routes") ?: return emptyList()
            require(raw.length <= 131072)
            return importedRecords(raw)
        }
        val issuer = preferences.getString("issuer", null)
        if (issuer != null) {
            val signed = preferences.getString("signed", null) ?: return emptyList()
            return verify(issuer, signed)
        }
        return personalRecords()
    }

    fun managedSetup(): Boolean = manager?.applicationRestrictions?.containsKey("verified_provider_routes") == true

    @Synchronized
    internal fun personalRevision(): String {
        personal.getString("revision", null)?.takeIf(uuidPattern::matches)?.let { return it }
        // A restored, missing or corrupt generation cannot revive an earlier personal approval.
        return revokePersonal()
    }

    @Synchronized
    internal fun personalAllowed(revision: String): Boolean = !managedSetup() &&
        preferences.getString("issuer", null) == null &&
        personalRevision() == revision

    fun personalSetupSummary(profile: PlatformV2): String? = runCatching {
        synchronized(this) {
            if (managedSetup() || preferences.getString("issuer", null) != null) return@synchronized null
            val endpoint = profile.apiUrl.trimEnd('/') + "/chat/completions"
            val digest = OpenRouterIdentity.digest(profile.token.orEmpty())
            val record = personalRecords().singleOrNull { it.endpoint == endpoint && it.credentialSha256 == digest }
                ?: return@synchronized null
            if (record.expiresAtMillis <= System.currentTimeMillis()) {
                "Plugin confirmation expired. Review your OpenRouter defaults to renew setup."
            } else {
                val expiry = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(record.expiresAtMillis))
                "Default plugins confirmed by you until $expiry. Account controls and routes are checked automatically."
            }
        }
    }.getOrElse { "Saved privacy setup could not be read. Check this account again." }

    internal suspend fun savePersonal(proof: ProviderApproval, managementKey: String, expectedRevision: String) = managementMutex.withLock {
        requireManagementKey(managementKey)
        requirePersonalProof(proof)
        check(personalAllowed(expectedRevision)) { "Account setup changed. Check the account again." }
        val previousKey = this@ProviderApprovalSource.managementKey()
        val remaining: List<ProviderApproval>
        val reserved: String
        synchronized(this) {
            check(personalAllowed(expectedRevision)) { "Account setup changed. Check the account again." }
            remaining = if (previousKey == managementKey) {
                personalRecords().filterNot { it.endpoint == proof.endpoint && it.credentialSha256 == proof.credentialSha256 }
            } else {
                emptyList()
            }
            require(remaining.size < 64)
            reserved = UUID.randomUUID().toString()
            // Revoke replaced authority before a possibly interrupted secure-storage operation.
            check(personal.edit().putString("records", Json.encodeToString(remaining)).putString("revision", reserved).commit())
        }
        if (previousKey != managementKey) writeManagementKey(managementKey)
        synchronized(this) {
            check(personalAllowed(reserved)) { "Account setup changed before it could be saved." }
            requirePersonalProof(proof)
            val encoded = Json.encodeToString(remaining + proof)
            require(encoded.length <= 131072)
            val saved = personal.edit().putString("records", encoded).putString("revision", UUID.randomUUID().toString()).commit()
            if (!saved) {
                // SharedPreferences may publish its in-memory value even when disk persistence fails.
                personal.edit().remove("records").putString("revision", UUID.randomUUID().toString()).commit()
            }
            check(saved)
        }
    }

    @Synchronized
    internal fun pendingSetupMutation(): String? = setupMutations.getString("pending", null)?.also { require(it.length in 1..32768) }

    @Synchronized
    internal fun beginSetupMutation(identity: String): Boolean {
        require(identity.isNotBlank() && identity.length <= 32768)
        if (pendingSetupMutation() != null) return false
        return setupMutations.edit().putString("pending", identity).commit()
    }

    @Synchronized
    internal fun confirmSetupMutation(identity: String) {
        if (pendingSetupMutation() == identity) check(setupMutations.edit().remove("pending").commit())
    }

    @Synchronized
    fun trustIssuer(encoded: String) {
        val issuer = encoded.trim()
        check(issuer.length in 1..2048)
        publicKey(issuer)
        revokePersonal()
        check(preferences.edit().putString("issuer", issuer).remove("signed").remove("apply_strict_guardrail").commit())
    }

    @Synchronized
    fun install(signed: String) {
        require(signed.length <= 131072)
        val issuer = checkNotNull(preferences.getString("issuer", null))
        verify(issuer, signed)
        revokePersonal()
        check(preferences.edit().putString("signed", signed).commit())
    }

    @Synchronized
    fun allowGuardrailUpdates() {
        // Renewed permission is not evidence of an earlier mutation's outcome.
        check(preferences.edit().putBoolean("apply_strict_guardrail", true).commit())
    }

    @Synchronized
    internal fun beginGuardrailUpdate(identity: String): Boolean {
        if (preferences.getString("pending_guardrail_update", null) != null) return false
        return preferences.edit().putString("pending_guardrail_update", identity).commit()
    }

    @Synchronized
    internal fun confirmGuardrailUpdate(identity: String) {
        if (preferences.getString("pending_guardrail_update", null) == identity) {
            check(preferences.edit().remove("pending_guardrail_update").commit())
        }
    }
    internal fun mayUpdateGuardrail(): Boolean = preferences.getBoolean("apply_strict_guardrail", false)

    @Synchronized
    fun forget() {
        revokePersonal()
        val pending = preferences.getString("pending_guardrail_update", null)
        check(preferences.edit().clear().apply { pending?.let { putString("pending_guardrail_update", it) } }.commit())
    }

    internal suspend fun managementKey(): String? = vault.read("privacy_openrouter_management")?.let { bytes ->
        try {
            bytes.decodeToString()
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun setManagementKey(key: String) = managementMutex.withLock {
        if (key.isNotBlank()) requireManagementKey(key)
        synchronized(this) { revokePersonal() }
        writeManagementKey(key)
    }

    private suspend fun writeManagementKey(key: String) {
        if (key.isBlank()) {
            vault.delete("privacy_openrouter_management")
        } else {
            requireManagementKey(key)
            val bytes = key.encodeToByteArray()
            try {
                vault.put("privacy_openrouter_management", bytes)
            } finally {
                bytes.fill(0)
            }
        }
    }

    @Synchronized
    private fun revokePersonal(): String = UUID.randomUUID().toString().also { revision ->
        check(personal.edit().remove("records").putString("revision", revision).commit())
    }

    private fun personalRecords(): List<ProviderApproval> {
        personalRevision()
        val raw = personal.getString("records", null) ?: return emptyList()
        require(raw.length <= 131072)
        return Json.decodeFromString<List<ProviderApproval>>(raw).also { records ->
            require(records.size <= 64 && records.all { it.scope == PERSONAL_SCOPE })
        }
    }

    companion object {
        private const val PERSONAL_SCOPE = "openrouter-personal-v1"
        private val uuidPattern = Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")
        private val hashPattern = Regex("[0-9a-f]{64}")

        private fun requireManagementKey(key: String) = require(key.length in 1..4096 && key.none { it.isWhitespace() || it.isISOControl() })

        private fun requirePersonalProof(proof: ProviderApproval) {
            val now = System.currentTimeMillis()
            require(proof.scope == PERSONAL_SCOPE && proof.endpoint in OpenRouterIdentity.endpoints.map { "$it/chat/completions" })
            require(hashPattern.matches(proof.credentialSha256) && proof.providerKeyHash == proof.credentialSha256)
            require(listOf(proof.personalWorkspaceId, proof.setupId, proof.modelAvailabilityPolicyId).all { it?.matches(uuidPattern) == true })
            require(proof.model == "*" && proof.upstream == "*" && proof.feature == "stateless-text-v1" && proof.retentionTier == "zdr")
            require(proof.noTraining && proof.loggingDisabled && proof.externalToolsDisabled && proof.providerDefaultsDisabled && proof.safetyRetentionExcluded)
            require(proof.verifiedAtMillis in (now - 86_400_000L)..now && proof.expiresAtMillis in (now + 1)..(proof.verifiedAtMillis + 86_400_000L))
            require(hashPattern.matches(proof.policySha256) && proof.policyUrl == PrivacyProfileEligibility.OPENROUTER_TERMS && proof.permitsDocument(proof.policyDocument))
        }

        private fun importedRecords(raw: String): List<ProviderApproval> = Json.decodeFromString<List<ProviderApproval>>(raw).also { records ->
            require(records.none { it.scope == PERSONAL_SCOPE }) { "Personal account confirmation cannot be imported." }
        }

        private fun publicKey(encoded: String): ECPublicKey {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(encoded))) as ECPublicKey
            require(key.params.curve.field.fieldSize == 256)
            return key
        }

        internal fun verify(issuer: String, signed: String): List<ProviderApproval> {
            val envelope = Json.decodeFromString<SignedApprovals>(signed)
            val bytes = Base64.getDecoder().decode(envelope.payload)
            require(
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey(issuer))
                    update(bytes)
                    verify(Base64.getDecoder().decode(envelope.signature))
                }
            )
            return importedRecords(bytes.decodeToString())
        }
    }
}
