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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Metadata can reject a known-incompatible route; it never relaxes the serialized privacy policy. */
@Singleton
class OpenRouterPrivacyPreflight @Inject constructor(private val client: OpenRouterAccountClient) {
    private data class Cached(val until: Long, val rows: JsonArray?)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun check(endpoint: String, credential: String?, model: String, policy: OpenRouterProviderRouting) {
        if (policy.zdr != true || credential.isNullOrBlank() || !model.matches(Regex("[A-Za-z0-9_.:-]+/[A-Za-z0-9_.:-]+"))) return
        val base = runCatching { OpenRouterIdentity.endpoint(endpoint) }.getOrNull() ?: return
        val identity = OpenRouterIdentity.digest("$base|$credential")
        val rows = locks.computeIfAbsent(identity) { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            cache[identity]?.takeIf { it.until > now }?.let { return@withLock it.rows }
            val result = try {
                val root = client.objectResource(base, credential, "endpoints/zdr")
                root["data"] as? JsonArray
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (cache.size >= 64) cache.keys.firstOrNull { it != identity }?.let(cache::remove)
            cache[identity] = Cached(now + if (result == null) 30_000 else 300_000, result)
            result
        }
        val compatible = rows?.let { compatible(it, model, policy) }
        AppLogRecorder.record("OpenRouter", "PRIVACY_PREFLIGHT · profileModel=$model · zdr=true · result=${compatible?.let { if (it) "AVAILABLE" else "INCOMPATIBLE" } ?: "UNKNOWN"} · policyUnchanged=true")
        check(compatible != false) { "POLICY_RESTRICTED: No endpoint for $model satisfies the selected zero-data-retention and provider restrictions. Choose a compatible model or provider in Settings; privacy protections were preserved." }
    }

    companion object {
        /** Null means malformed/incomplete metadata, not proof that no provider is available. */
        internal fun compatible(rows: JsonArray, model: String, policy: OpenRouterProviderRouting): Boolean? {
            if (rows.any { it !is JsonObject || (it["model_id"] as? JsonPrimitive)?.content.isNullOrBlank() || (it["tag"] as? JsonPrimitive)?.content.isNullOrBlank() }) return null
            fun matches(choice: String, tag: String) = tag.equals(choice, true) || tag.startsWith("$choice/", true)
            return rows.filterIsInstance<JsonObject>().any { row ->
                val tag = (row["tag"] as JsonPrimitive).content
                (row["model_id"] as JsonPrimitive).content == model &&
                    (policy.only.isNullOrEmpty() || policy.only.any { matches(it, tag) }) &&
                    policy.ignore.orEmpty().none { matches(it, tag) } &&
                    (policy.allowFallbacks != false || policy.order.isNullOrEmpty() || policy.order.any { matches(it, tag) })
                // Other restrictions remain server-enforced; availability is never certification.
            }
        }
    }
}
