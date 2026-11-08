package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Account evidence is private, encrypted and tied to saved credential records, not token hashes. */
@Serializable
internal data class GatewayVerifiedScope(
    val configurationRevision: String,
    val gatewayId: String,
    val principalId: String,
    val verifiedAtMillis: Long
)

@Singleton
class GatewayVerifiedScopeStore @Inject constructor(private val vault: SecretVault) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableChanges = MutableStateFlow(0L)
    internal val changes = mutableChanges.asStateFlow()

    /** A stale hydrated token cannot be attributed to a newer encrypted credential record. */
    internal suspend fun configurationRevision(profile: PlatformV2): String? = safely {
        val endpoint = profile.apiUrl.trim().trimEnd('/')
        val uri = URI(endpoint)
        if (profile.uid.isBlank() || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) return@safely null
        val reference = profile.secretRef
        val recordRevision = if (reference == null) {
            if (!profile.token.isNullOrBlank()) return@safely null
            "auth-none"
        } else {
            val before = vault.recordRevision(reference)?.takeIf(::validRevision) ?: return@safely null
            val stored = vault.read(reference) ?: return@safely null
            val resolved = profile.token.orEmpty().toByteArray(Charsets.UTF_8)
            val matches = try {
                MessageDigest.isEqual(stored, resolved)
            } finally {
                stored.fill(0)
                resolved.fill(0)
            }
            if (!matches || vault.recordRevision(reference) != before) return@safely null
            before
        }
        researchHash(
            buildJsonObject {
                put("schema", "gateway-saved-scope-v1")
                put("profile", profile.uid)
                put("providerConnection", profile.providerConnectionUid)
                put("type", profile.compatibleType.name)
                put("endpoint", endpoint)
                put("secretReference", reference)
                put("credentialRecord", recordRevision)
            }.toString()
        )
    }

    internal suspend fun verified(profile: PlatformV2): GatewayVerifiedScope? =
        configurationRevision(profile)?.let { read(profile.uid, it) }

    internal suspend fun read(profileUid: String, configurationRevision: String?): GatewayVerifiedScope? = safely {
        if (configurationRevision?.let(::validRevision) != true) return@safely null
        val bytes = vault.read(reference(profileUid)) ?: return@safely null
        val scope = try {
            if (bytes.size > 4096) return@safely null
            json.decodeFromString<GatewayVerifiedScope>(bytes.toString(Charsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
        requireGatewayIdentity(scope.gatewayId, scope.principalId)
        scope.takeIf { it.configurationRevision == configurationRevision && it.verifiedAtMillis > 0 }
    }

    internal suspend fun remember(profileUid: String, configurationRevision: String?, gatewayId: String, principalId: String): GatewayVerifiedScope? = safely {
        if (configurationRevision?.let(::validRevision) != true) return@safely null
        requireGatewayIdentity(gatewayId, principalId)
        val previous = read(profileUid, configurationRevision)
        if (previous?.gatewayId == gatewayId && previous.principalId == principalId) return@safely previous
        val scope = GatewayVerifiedScope(configurationRevision, gatewayId, principalId, System.currentTimeMillis())
        val bytes = json.encodeToString(scope).toByteArray(Charsets.UTF_8)
        try {
            vault.put(reference(profileUid), bytes)
        } finally {
            bytes.fill(0)
        }
        mutableChanges.update { it + 1 }
        scope
    }

    private fun reference(profileUid: String) = "gateway_scope_${researchHash(profileUid)}"
    private fun validRevision(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
