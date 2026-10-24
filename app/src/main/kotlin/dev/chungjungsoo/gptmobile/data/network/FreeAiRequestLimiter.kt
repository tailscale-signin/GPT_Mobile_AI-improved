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
    elapsedMillis: (() -> Long)? = null,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val elapsedMillis: () -> Long = elapsedMillis ?: nowMillis
    private class Bucket {
        val permit = Semaphore(1)
        val requests = ArrayDeque<Long>()
        var cooldown: FreeCooldown? = null
        var nextLocalSlotAt: Long = 0
        var elapsedRetryAt: Long = 0
        val retryAt: Long get() = cooldown?.retryAt ?: 0L
    }

    @kotlinx.serialization.Serializable
    private data class SavedBucket(val cooldown: FreeCooldown? = null, val requests: List<Long> = emptyList(), val nextLocalSlotAt: Long = 0)

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
            storage?.takeIf { it.isFile && it.length() <= 1_048_576 }?.let { file ->
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val saved = json.parseToJsonElement(file.readText()) as? kotlinx.serialization.json.JsonObject ?: return@let
                saved.forEach { (key, value) ->
                    val provider = FreeAiProvider.entries.firstOrNull { it.name == key.substringBefore('|') } ?: return@forEach
                    val snapshot = if (value is kotlinx.serialization.json.JsonObject && listOf("requests", "cooldown", "nextLocalSlotAt").any { it in value }) {
                        runCatching { json.decodeFromString<SavedBucket>(value.toString()) }.getOrNull()
                    } else {
                        val legacy = if (value is kotlinx.serialization.json.JsonPrimitive) value.content.toLongOrNull()?.let { FreeCooldown(it) } else runCatching { json.decodeFromString<FreeCooldown>(value.toString()) }.getOrNull()
                        legacy?.let { SavedBucket(cooldown = it) }
                    } ?: return@forEach
                    val bucket = buckets.computeIfAbsent(provider to key.substringAfter('|', "")) { Bucket() }
                    bucket.cooldown = snapshot.cooldown?.takeIf { it.retryAt > nowMillis() }
                    bucket.requests.addAll(snapshot.requests.filter { it > nowMillis() - HOUR_MS }.sorted())
                    bucket.nextLocalSlotAt = snapshot.nextLocalSlotAt
                    bucket.elapsedRetryAt = elapsedMillis() + ((bucket.cooldown?.retryAt ?: 0) - nowMillis()).coerceAtLeast(0)
                }
            }
        }
    }

    private fun persist() {
        runCatching {
            storage?.let { target ->
                val values = buckets.entries.filter { it.value.retryAt > nowMillis() || it.value.elapsedRetryAt > elapsedMillis() || it.value.nextLocalSlotAt > nowMillis() || it.value.requests.any { at -> at > nowMillis() - HOUR_MS } }.associate { (key, value) -> "${key.first.name}|${key.second}" to SavedBucket(value.cooldown?.let { it.copy(retryAt = maxOf(it.retryAt, nowMillis() + (value.elapsedRetryAt - elapsedMillis()).coerceAtLeast(0))) }, value.requests.filter { it > nowMillis() - HOUR_MS }, value.nextLocalSlotAt) }
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
        persist()
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
        persist()
    }

    @Synchronized
    fun defer(provider: FreeAiProvider, retryAfter: String?, model: String = "", credentialIdentity: String = "unknown", evidence: FreeCooldown? = null, serverDate: String? = null): Long {
        restore()
        val now = nowMillis()
        val seconds = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        val numericDeadline = seconds?.let { runCatching { Math.addExact(now, Math.multiplyExact(it, 1000L)) }.getOrNull() }
        val date = runCatching { ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        val serverNow = runCatching { ZonedDateTime.parse(serverDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        val adjustedDate = date?.let { if (serverNow != null) runCatching { Math.addExact(now, (it - serverNow).coerceAtLeast(0)) }.getOrNull() else it }
        val deadline = numericDeadline ?: adjustedDate?.takeIf { it >= now } ?: (now + MINUTE_MS)
        val scopedIdentity = if (evidence != null && evidence.quotaScope.lowercase() !in setOf("token", "credential", "api_key")) "unknown" else credentialIdentity
        val bucket = bucket(provider, model, scopedIdentity)
        val previousDeadline = savedCooldown(provider, model, scopedIdentity)?.retryAt ?: bucket.retryAt
        if (deadline >= previousDeadline) {
            bucket.elapsedRetryAt = elapsedMillis() + (deadline - now).coerceAtLeast(0)
            bucket.cooldown = (evidence ?: FreeCooldown(deadline)).copy(
                retryAt = deadline,
                observedAt = now,
                credentialIdentity = credentialIdentity,
                deadlineSource = when {
                    numericDeadline != null -> "retry_after_seconds"
                    adjustedDate != null && adjustedDate >= now -> if (serverNow != null) "retry_after_http_date_server_clock" else "retry_after_http_date"
                    else -> "local_estimate"
                }
            )
        }
        persist()
        return (maxOf(bucket.retryAt, previousDeadline) - now + 999) / 1000
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
        return listOf(bucket(provider, model, credentialIdentity), bucket(provider, model)).mapNotNull { bucket ->
            bucket.cooldown?.let { cooldown -> cooldown.copy(retryAt = maxOf(cooldown.retryAt, nowMillis() + (bucket.elapsedRetryAt - elapsedMillis()).coerceAtLeast(0))) }
        }
            .filter { it.retryAt > nowMillis() }.maxByOrNull { it.retryAt }
    }

    companion object {
        val shared = FreeAiRequestLimiter(elapsedMillis = { System.nanoTime() / 1_000_000 })
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
    }
}

class FreeAiRateLimitException(provider: FreeAiProvider, retrySeconds: Long, detail: String? = null, val cooldown: FreeCooldown? = null, retryAt: Long = cooldown?.retryAt ?: System.currentTimeMillis() + retrySeconds * 1000, localBlock: Boolean = true) : IllegalStateException(cooldown?.takeIf { localBlock }?.blockedMessage(provider) ?: "${provider.displayName} is rate limited. Next eligible retry: ${java.time.Instant.ofEpochMilli(retryAt)} (in $retrySeconds seconds). This does not guarantee the provider’s allowance has reset." + ((detail ?: cooldown?.message)?.let { " $it" } ?: ""))
