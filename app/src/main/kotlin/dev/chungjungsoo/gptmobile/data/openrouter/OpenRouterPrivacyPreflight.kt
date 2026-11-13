package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountClient
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray

/** Metadata can reject a known-incompatible route; it never relaxes the serialized privacy policy. */
@Singleton
class OpenRouterPrivacyPreflight @Inject constructor(private val client: OpenRouterAccountClient) {
    private data class Cached(val until: Long, val rows: JsonArray?, val observedAt: Long)
    private val locks = Array(32) { Mutex() }
    private val rejections = PrivacyRejectionCache()
    private val cache = ConcurrentHashMap<String, Cached>()

    private fun rejectionKey(endpoint: String, credential: String?, model: String, policy: OpenRouterProviderRouting): String =
        OpenRouterIdentity.digest("${endpoint.trim().trimEnd('/')}|${credential.orEmpty()}|$model|$policy")

    fun recordRejection(endpoint: String, credential: String?, model: String, policy: OpenRouterProviderRouting, message: String) {
        rejections.record(rejectionKey(endpoint, credential, model, policy), message)
    }

    suspend fun check(endpoint: String, credential: String?, model: String, policy: OpenRouterProviderRouting) {
        if (rejections.blocked(rejectionKey(endpoint, credential, model, policy))) {
            throw PrivacyAdmissionException(
                "POLICY_RESTRICTED: This model was recently rejected under this account and privacy configuration. Choose a compatible model in Settings or retry after five minutes; privacy protections were preserved.",
                model,
                policy,
                PrivacyAdmissionException.Observation.CACHED_REJECTION
            )
        }
        if (dynamicModel(model)) return
        val selectedModel = model
        if (policy.zdr != true || credential.isNullOrBlank() || !selectedModel.matches(Regex("[A-Za-z0-9_.:-]+/[A-Za-z0-9_.:-]+"))) return
        val base = runCatching { OpenRouterIdentity.endpoint(endpoint) }.getOrNull() ?: return
        val identity = OpenRouterIdentity.digest("$base|$credential")
        val catalog = locks[(identity.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val now = System.nanoTime() / 1_000_000
            cache[identity]?.takeIf { it.until > now }?.let { return@withLock it }
            val result = try {
                val root = client.objectResource(base, credential, "endpoints/zdr")
                root["data"] as? JsonArray
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (cache.size >= 64) cache.keys.firstOrNull { it != identity }?.let(cache::remove)
            Cached(now + if (result == null) 30_000 else 300_000, result, now).also { cache[identity] = it }
        }
        val observation = catalog.rows?.let { PrivacyEndpointObservation.evaluate(it, selectedModel, policy) }
        val compatible = observation?.compatible
        AppLogRecorder.record("OpenRouter", "PRIVACY_PREFLIGHT · profileModel=$model · zdr=true · result=${compatible?.let { if (it) "AVAILABLE" else "INCOMPATIBLE" } ?: "UNKNOWN"} · policyUnchanged=true")
        if (compatible == false) {
            throw PrivacyAdmissionException(
                "POLICY_RESTRICTED: No endpoint for $model satisfies the selected zero-data-retention and provider restrictions. Choose a compatible model or provider in Settings; privacy protections were preserved.",
                model,
                policy,
                PrivacyAdmissionException.Observation.NO_COMPATIBLE_ENDPOINT,
                observation?.fields(System.nanoTime() / 1_000_000 - catalog.observedAt)
            )
        }
    }

    companion object {
        // These routes select a concrete model server-side; absence of the alias in
        // an endpoint inventory is not evidence of policy incompatibility.
        private fun dynamicModel(model: String): Boolean = model.startsWith("~") || model in setOf("openrouter/auto", "openrouter/free")

        /** Null means malformed/incomplete metadata, not proof that no provider is available. */
        internal fun compatible(rows: JsonArray, model: String, policy: OpenRouterProviderRouting): Boolean? =
            PrivacyEndpointObservation.evaluate(rows, model, policy)?.compatible
    }
}
