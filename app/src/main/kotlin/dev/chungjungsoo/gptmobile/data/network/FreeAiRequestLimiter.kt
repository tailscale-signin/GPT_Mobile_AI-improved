package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString

/** Shared across profiles and tool rounds. An IP's other apps can still consume its quota. */
class FreeAiRequestLimiter(
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private class Bucket {
        val permit = Semaphore(1)
        val requests = ArrayDeque<Long>()
        var cooldown: FreeCooldown? = null
        var nextLocalSlotAt: Long = 0
        val retryAt: Long get() = cooldown?.retryAt ?: 0L
    }

    private var storage: java.io.File? = null
    private var restored = false

    @Synchronized fun initialize(file: java.io.File) {
        storage = file
        restored = false
        buckets.clear()
    }

    private fun restore() {
        if (restored) return
        restored = true
        runCatching {
            storage?.takeIf { it.isFile && it.length() <= 64_000 }?.let { file ->
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val saved = json.parseToJsonElement(file.readText()) as? kotlinx.serialization.json.JsonObject ?: return@let
                saved.forEach { (key, value) ->
                    val cooldown = if (value is kotlinx.serialization.json.JsonPrimitive) {
                        value.content.toLongOrNull()?.let { FreeCooldown(it) }
                    } else {
                        runCatching { json.decodeFromString<FreeCooldown>(value.toString()) }.getOrNull()
                    }
                    if (cooldown == null) return@forEach
                    val provider = FreeAiProvider.entries.firstOrNull { it.name == key.substringBefore('|') } ?: return@forEach
                    if (cooldown.retryAt > nowMillis()) buckets.computeIfAbsent(provider to key.substringAfter('|', "")) { Bucket() }.cooldown = cooldown
                }
            }
        }
    }

    private fun persist() {
        runCatching {
            storage?.let { target ->
                val values = buckets.entries.filter { it.value.retryAt > nowMillis() }.associate { (key, value) -> "${key.first.name}|${key.second}" to value.cooldown!! }
                target.parentFile?.mkdirs()
                val temporary = java.io.File(target.path + ".tmp")
                temporary.writeText(kotlinx.serialization.json.Json.encodeToString(values))
                java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }

    private val buckets = java.util.concurrent.ConcurrentHashMap<Pair<FreeAiProvider, String>, Bucket>()
    private fun bucket(provider: FreeAiProvider, model: String = "", credentialIdentity: String = "unknown"): Bucket = buckets.computeIfAbsent(
        provider to when {
            provider == FreeAiProvider.OVHCLOUD -> model
            provider.acceptsUserToken && credentialIdentity !in setOf("unknown", "anonymous") -> "credential:$credentialIdentity"
            else -> ""
        }
    ) { Bucket() }

    suspend fun <T> withRequest(provider: FreeAiProvider, request: suspend () -> T): T = withRequest(provider, "", request = request)

    suspend fun <T> withRequest(provider: FreeAiProvider, model: String, credentialIdentity: String = "unknown", request: suspend () -> T): T =
        bucket(provider, model, credentialIdentity).permit.withPermit {
            // Short local spacing is scheduling, not an exhausted provider quota.
            // Keep the permit while waiting so parallel profiles cannot race the next slot.
            val wait = schedulingDelayMillis(provider, model, credentialIdentity)
            if (wait > 0) {
                currentCoroutineContext()[ProviderWaitContext]?.notifyRemaining?.invoke(wait)
                delay(wait)
            }
            reserve(provider, model, credentialIdentity)
            try {
                request()
            } finally {
                if (provider == FreeAiProvider.POLLINATIONS) completedClassicRequest(model, credentialIdentity)
            }
        }

    @Synchronized
    private fun completedClassicRequest(model: String, credentialIdentity: String) {
        // Classic's IP interval begins after the active response finishes, including
        // a cancelled stream. Cancellation releases the permit but not this spacing.
        bucket(FreeAiProvider.POLLINATIONS, model, credentialIdentity).nextLocalSlotAt = nowMillis() + 30_000
    }

    @Synchronized
    private fun schedulingDelayMillis(provider: FreeAiProvider, model: String, credentialIdentity: String): Long {
        restore()
        val now = nowMillis()
        // A server Retry-After is never converted into an automatic local wait.
        if (savedCooldown(provider, model, credentialIdentity) != null) return 0L
        return ((cooldownUntilMillis(provider, model, credentialIdentity) ?: now) - now).takeIf { it in 1..60_000 } ?: 0L
    }

    @Synchronized
    internal fun reserve(provider: FreeAiProvider, model: String = "", credentialIdentity: String = "unknown") {
        restore()
        val bucket = bucket(provider, model, credentialIdentity)
        val now = nowMillis()
        while (bucket.requests.isNotEmpty() && bucket.requests.first <= now - HOUR_MS) bucket.requests.removeFirst()
        val minuteRequests = bucket.requests.filter { it > now - MINUTE_MS }
        var retryAt = maxOf(savedCooldown(provider, model, credentialIdentity)?.retryAt ?: 0L, bucket.nextLocalSlotAt)
        if (provider == FreeAiProvider.LLM7 && bucket.requests.isNotEmpty()) {
            retryAt = maxOf(retryAt, bucket.requests.last + 1000)
        }
        if (provider.requestsPerMinute > 0 && minuteRequests.size >= provider.requestsPerMinute) {
            retryAt = maxOf(retryAt, minuteRequests.first() + MINUTE_MS)
        }
        if (provider.requestsPerHour > 0 && bucket.requests.size >= provider.requestsPerHour) {
            retryAt = maxOf(retryAt, bucket.requests.first + HOUR_MS)
        }
        if (retryAt > now) {
            // Local windows are scheduling state, not a persisted provider quota.
            throw FreeAiRateLimitException(provider, (retryAt - now + 999) / 1000, cooldown = savedCooldown(provider, model, credentialIdentity), retryAt = retryAt)
        }
        bucket.requests.addLast(now)
        if (provider == FreeAiProvider.POLLINATIONS) bucket.nextLocalSlotAt = now + 30_000
    }

    @Synchronized
    fun defer(provider: FreeAiProvider, retryAfter: String?, model: String = "", credentialIdentity: String = "unknown", evidence: FreeCooldown? = null): Long {
        restore()
        val now = nowMillis()
        val seconds = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        val numericDeadline = seconds?.let { runCatching { Math.addExact(now, Math.multiplyExact(it, 1000L)) }.getOrNull() }
        val date = runCatching { ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        val deadline = numericDeadline ?: date?.takeIf { it >= now } ?: (now + MINUTE_MS)
        val scopedIdentity = if (evidence != null && evidence.quotaScope.lowercase() !in setOf("token", "credential", "api_key")) "unknown" else credentialIdentity
        val bucket = bucket(provider, model, scopedIdentity)
        if (deadline >= bucket.retryAt) {
            bucket.cooldown = (evidence ?: FreeCooldown(deadline)).copy(
                retryAt = deadline,
                observedAt = now,
                credentialIdentity = credentialIdentity,
                deadlineSource = when {
                    numericDeadline != null -> "retry_after_seconds"
                    date != null && date >= now -> "retry_after_http_date"
                    else -> "local_estimate"
                }
            )
        }
        persist()
        return (bucket.retryAt - now + 999) / 1000
    }

    /** Return the known provider/local quota cooldown without reserving a request. */
    @Synchronized
    fun cooldownUntilMillis(provider: FreeAiProvider, model: String = "", credentialIdentity: String = "unknown"): Long? {
        restore()
        val now = nowMillis()
        val bucket = bucket(provider, model, credentialIdentity)
        while (bucket.requests.isNotEmpty() && bucket.requests.first <= now - HOUR_MS) bucket.requests.removeFirst()
        val minuteRequests = bucket.requests.filter { it > now - MINUTE_MS }
        var retryAt = maxOf(savedCooldown(provider, model, credentialIdentity)?.retryAt ?: 0L, bucket.nextLocalSlotAt)
        if (provider == FreeAiProvider.LLM7 && bucket.requests.isNotEmpty()) {
            retryAt = maxOf(retryAt, bucket.requests.last + 1000)
        }
        if (provider.requestsPerMinute > 0 && minuteRequests.size >= provider.requestsPerMinute) {
            retryAt = maxOf(retryAt, minuteRequests.first() + MINUTE_MS)
        }
        if (provider.requestsPerHour > 0 && bucket.requests.size >= provider.requestsPerHour) {
            retryAt = maxOf(retryAt, bucket.requests.first + HOUR_MS)
        }
        return retryAt.takeIf { it > now }
    }

    @Synchronized
    fun savedCooldown(provider: FreeAiProvider, model: String = "", credentialIdentity: String = "unknown"): FreeCooldown? {
        restore()
        // Old deadlines have unknown scope. Preserve them across credential changes rather
        // than claiming that a new key bypasses an IP/account-wide quota.
        return listOfNotNull(bucket(provider, model, credentialIdentity).cooldown, bucket(provider, model).cooldown)
            .filter { it.retryAt > nowMillis() }.maxByOrNull { it.retryAt }
    }

    companion object {
        val shared = FreeAiRequestLimiter()
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
    }
}

class FreeAiRateLimitException(provider: FreeAiProvider, retrySeconds: Long, detail: String? = null, val cooldown: FreeCooldown? = null, retryAt: Long = cooldown?.retryAt ?: System.currentTimeMillis() + retrySeconds * 1000, localBlock: Boolean = true) : IllegalStateException(cooldown?.takeIf { localBlock }?.blockedMessage(provider) ?: "${provider.displayName} is rate limited. Next eligible retry: ${java.time.Instant.ofEpochMilli(retryAt)} (in $retrySeconds seconds). This does not guarantee the provider’s allowance has reset." + ((detail ?: cooldown?.message)?.let { " $it" } ?: ""))
