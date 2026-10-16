package dev.chungjungsoo.gptmobile.data.nvidia

import dev.chungjungsoo.gptmobile.data.ModelConstants
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.ProfileModelCatalog
import dev.chungjungsoo.gptmobile.data.repository.ProfileModelOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory-only, credential-isolated /models discovery; never store keys or model access claims. */
@Singleton
class NvidiaModelRepository @Inject constructor() {
    private data class Cached(val fetchedAt: Long, val models: List<ProfileModelOption>)
    private val catalog = ProfileModelCatalog()
    private val mutex = Mutex()
    private val cache = linkedMapOf<String, Cached>()

    suspend fun load(apiUrl: String, apiKey: String, forceRefresh: Boolean = false): List<ProfileModelOption> {
        require(apiKey.isNotBlank()) { "Add an NVIDIA API key to browse live models." }
        val endpoint = apiUrl.trim().ifBlank { ModelConstants.NVIDIA_API_URL }.trimEnd('/')
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((endpoint + "\u0000" + apiKey).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return mutex.withLock {
            val now = System.currentTimeMillis()
            val cached = cache[digest]
            if (!forceRefresh && cached != null && now - cached.fetchedAt in 0..600_000L) {
                return@withLock cached.models
            }
            val profile = PlatformV2(
                name = "NVIDIA",
                compatibleType = ClientType.NVIDIA,
                apiUrl = endpoint,
                token = apiKey
            )
            val models = catalog.load(profile)
            if (cache.size >= 16) cache.remove(cache.keys.first())
            cache[digest] = Cached(now, models)
            models
        }
    }
}
