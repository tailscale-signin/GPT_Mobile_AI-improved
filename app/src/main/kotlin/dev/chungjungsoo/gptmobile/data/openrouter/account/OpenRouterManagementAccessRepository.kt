package dev.chungjungsoo.gptmobile.data.openrouter.account

import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class OpenRouterManagementAccessRepository @Inject constructor(
    private val settings: SettingRepository,
    private val vault: SecretVault,
    private val account: OpenRouterAccountClient,
    private val local: OpenRouterWalletLocalStore
) {
    private val mutation = Mutex()
    val state = local.state

    /** Validation is explicit and never calls credits. Reassociation reuses the protected secret. */
    suspend fun save(uid: String, replacement: String? = null) = mutation.withLock {
        val connection = requireNotNull(settings.getProviderConnection(uid))
        require(connection.compatibleType == ClientType.OPENROUTER)
        val endpoint = OpenRouterIdentity.endpoint(connection.apiUrl)
        val inference = settings.getProviderCredentials(uid).orEmpty()
        val old = local.snapshot().bindings[uid]
        val credential = replacement?.let(OpenRouterIdentity::credential) ?: requireNotNull(old?.let { credential(it) })
        val identity = account.key(endpoint, credential)
        if (!identity.isManagement) throw OpenRouterAccountException(AccountFailure.ROLE)
        if (identity.expired) throw OpenRouterAccountException(AccountFailure.EXPIRED)
        check(settings.getProviderConnection(uid)?.apiUrl == connection.apiUrl && settings.getProviderCredentials(uid).orEmpty() == inference) { "Connection changed during validation" }
        val reference = "openrouter-management-${UUID.randomUUID()}"
        val bytes = credential.toByteArray(Charsets.UTF_8)
        try {
            vault.put(reference, bytes)
            val binding = OpenRouterManagementBinding(
                uid,
                reference,
                UUID.randomUUID().toString(),
                endpoint,
                ApiCredentialRotator.parseKeys(inference).map { OpenRouterIdentity.revision(uid, endpoint, it) }.toSet()
            )
            local.update { it.copy(bindings = it.bindings + (uid to binding), observations = it.observations - uid) }
            if (settings.getProviderConnection(uid)?.apiUrl != connection.apiUrl || settings.getProviderCredentials(uid).orEmpty() != inference) {
                local.invalidateConnection(uid)
            }
        } catch (error: Exception) {
            // Do not delete a reference already published by an atomic write that completed at cancellation.
            if (local.snapshot().bindings[uid]?.secretRef != reference) vault.delete(reference)
            throw error
        } finally {
            bytes.fill(0)
        }
        old?.secretRef?.let { vault.delete(it) }
    }

    suspend fun remove(uid: String) = mutation.withLock {
        local.detach(uid)?.let { vault.delete(it) }
        Unit
    }

    suspend fun readActivity(uid: String): kotlinx.serialization.json.JsonArray? {
        val binding = local.snapshot().bindings[uid]?.takeIf { it.associated } ?: return null
        val secret = credential(binding) ?: return null
        val result = account.objectResource(binding.endpoint, secret, "activity")["data"] as? kotlinx.serialization.json.JsonArray
        return result.takeIf { local.snapshot().bindings[uid] == binding }
    }

    internal suspend fun credential(binding: OpenRouterManagementBinding): String? {
        val bytes = vault.read(binding.secretRef) ?: return null
        return try {
            bytes.toString(Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }

    /** Used by the per-account actor and explicit provider action, never by Home observation. */
    internal suspend fun read(binding: OpenRouterManagementBinding): SettingsHomeWalletSnapshot? {
        if (local.snapshot().bindings[binding.connectionUid] != binding || !binding.associated) return null
        val connection = settings.getProviderConnection(binding.connectionUid) ?: return null
        if (connection.apiUrl.trimEnd('/') != binding.endpoint) return null
        try {
            val secret = credential(binding) ?: throw OpenRouterAccountException(AccountFailure.INVALID)
            val data = account.objectResource(binding.endpoint, secret, "credits")["data"] as? kotlinx.serialization.json.JsonObject
                ?: throw OpenRouterAccountException(AccountFailure.MALFORMED)
            val result = SettingsHomeWalletSnapshot.fromCredits(connection.uid, connection.name, connection.updatedAt, data, System.currentTimeMillis() / 1000)
            local.publish(binding, result, null)
            return result.takeIf { local.snapshot().bindings[binding.connectionUid] == binding }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val categorized = failure as? OpenRouterAccountException
            local.publish(
                binding,
                null,
                categorized?.category ?: AccountFailure.NETWORK,
                categorized?.retryAfterSeconds?.let { delay ->
                    val now = System.currentTimeMillis() / 1000
                    now + delay.coerceAtMost(Long.MAX_VALUE - now)
                } ?: 0
            )
            return null
        }
    }
}
