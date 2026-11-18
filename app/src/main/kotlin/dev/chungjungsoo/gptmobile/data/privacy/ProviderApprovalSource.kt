package dev.chungjungsoo.gptmobile.data.privacy

import android.content.Context
import android.content.RestrictionsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
private data class SignedApprovals(val payload: String, val signature: String)

/** An issuer is explicitly trusted out of band. The signed file cannot introduce its own key.
 * Managed configuration, when present, is the sole authority and can revoke all routes with [].
 */
@Singleton
class ProviderApprovalSource @Inject constructor(@ApplicationContext private val context: Context, private val vault: SecretVault) {
    private val preferences = context.getSharedPreferences("privacy_authority", Context.MODE_PRIVATE)
    private val manager get() = context.getSystemService(RestrictionsManager::class.java)

    internal fun records(): List<ProviderApproval> {
        val managed = manager?.applicationRestrictions
        if (managed?.containsKey("verified_provider_routes") == true) {
            val raw = managed.getString("verified_provider_routes") ?: return emptyList()
            require(raw.length <= 131072)
            return Json.decodeFromString(raw)
        }
        val issuer = preferences.getString("issuer", null) ?: return emptyList()
        val signed = preferences.getString("signed", null) ?: return emptyList()
        return verify(issuer, signed)
    }

    fun trustIssuer(encoded: String) {
        val issuer = encoded.trim()
        check(issuer.length in 1..2048)
        publicKey(issuer)
        check(preferences.edit().putString("issuer", issuer).remove("signed").remove("apply_strict_guardrail").commit())
    }

    fun install(signed: String) {
        require(signed.length <= 131072)
        val issuer = checkNotNull(preferences.getString("issuer", null))
        verify(issuer, signed)
        check(preferences.edit().putString("signed", signed).commit())
    }

    fun allowGuardrailUpdates() {
        check(preferences.edit().putBoolean("apply_strict_guardrail", true).commit())
    }
    internal fun mayUpdateGuardrail(): Boolean = preferences.getBoolean("apply_strict_guardrail", false)

    fun forget() {
        check(preferences.edit().clear().commit())
    }

    internal suspend fun managementKey(): String? = vault.read("privacy_openrouter_management")?.let { bytes ->
        try {
            bytes.decodeToString()
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun setManagementKey(key: String) {
        if (key.isBlank()) {
            vault.delete("privacy_openrouter_management")
        } else {
            require(key.length in 1..4096 && key.none(Char::isWhitespace))
            val bytes = key.encodeToByteArray()
            try {
                vault.put("privacy_openrouter_management", bytes)
            } finally {
                bytes.fill(0)
            }
        }
    }

    companion object {
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
            return Json.decodeFromString(bytes.decodeToString())
        }
    }
}
