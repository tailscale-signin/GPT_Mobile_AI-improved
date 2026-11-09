package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A remembered gateway is eligible for an offline chat, never evidence that generation is ready. */
@Serializable
internal data class GatewayKnownProfile(
    val configurationRevision: String,
    val instanceId: String,
    val observedAtMillis: Long,
    val conversationSyncSupported: Boolean = false
)

@Singleton
class GatewayKnownProfileStore @Inject constructor(
    private val vault: SecretVault,
    private val scopes: GatewayVerifiedScopeStore
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutation = Mutex()
    private val mutableChanges = MutableStateFlow(0L)
    internal val changes = mutableChanges.asStateFlow()

    internal suspend fun remembered(profile: PlatformV2): Boolean = safely {
        val revision = scopes.configurationRevision(profile) ?: return@safely false
        read(profile.uid)?.let { it.configurationRevision == revision && it.conversationSyncSupported } == true
    } ?: false

    /** Call only after matching capabilities and readiness established this direct endpoint. */
    internal suspend fun remember(profile: PlatformV2, instanceId: String, expectedConfigurationRevision: String? = null, conversationSyncSupported: Boolean = false): Boolean = safely {
        if (!validInstance(instanceId)) return@safely false
        val revision = scopes.configurationRevision(profile) ?: return@safely false
        if (expectedConfigurationRevision != null && revision != expectedConfigurationRevision) return@safely false
        mutation.withLock {
            val previous = read(profile.uid)
            if (previous?.configurationRevision == revision && previous.instanceId == instanceId && previous.conversationSyncSupported == conversationSyncSupported) return@withLock true
            val bytes = json.encodeToString(GatewayKnownProfile(revision, instanceId, System.currentTimeMillis(), conversationSyncSupported)).toByteArray(Charsets.UTF_8)
            try {
                vault.put(reference(profile.uid), bytes)
            } finally {
                bytes.fill(0)
            }
            mutableChanges.update { it + 1 }
            true
        }
    } ?: false

    /** A response for a retired configuration cannot erase a replacement profile's evidence. */
    internal suspend fun forget(profile: PlatformV2, expectedConfigurationRevision: String? = null) {
        safely {
            val revision = scopes.configurationRevision(profile) ?: return@safely false
            if (expectedConfigurationRevision != null && revision != expectedConfigurationRevision) return@safely false
            mutation.withLock {
                if (read(profile.uid)?.configurationRevision != revision) return@withLock false
                vault.delete(reference(profile.uid))
                mutableChanges.update { it + 1 }
                true
            }
        }
    }

    private suspend fun read(profileUid: String): GatewayKnownProfile? = safely {
        val bytes = vault.read(reference(profileUid)) ?: return@safely null
        try {
            if (bytes.size > 4096) return@safely null
            json.decodeFromString<GatewayKnownProfile>(bytes.toString(Charsets.UTF_8)).takeIf {
                it.configurationRevision.length == 64 &&
                    it.configurationRevision.all { character -> character in '0'..'9' || character in 'a'..'f' } &&
                    validInstance(it.instanceId) &&
                    it.observedAtMillis > 0
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun validInstance(value: String): Boolean = value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)
    private fun reference(profileUid: String) = "gateway_known_${researchHash(profileUid)}"

    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
