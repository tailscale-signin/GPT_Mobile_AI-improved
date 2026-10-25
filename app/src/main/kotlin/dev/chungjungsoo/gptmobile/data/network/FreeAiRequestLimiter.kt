package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
        val requests = ArrayDeque<RequestCharge>()
        var cooldown: FreeCooldown? = null
        var nextLocalSlotAt: Long = 0
        var nextLocalSlotElapsed: Long = 0
        var elapsedRetryAt: Long = 0
        var cooldownNotBefore: Long = 0
        val tokens = mutableListOf<TokenCharge>()
        val retryAt: Long get() = cooldown?.retryAt ?: 0L
    }

    @kotlinx.serialization.Serializable
    private data class SavedBucket(val cooldown: FreeCooldown? = null, val requests: List<Long> = emptyList(), val nextLocalSlotAt: Long = 0, val tokens: List<TokenCharge> = emptyList(), val cooldownNotBefore: Long? = null)

    private data class RequestCharge(val at: Long, val elapsedAt: Long, val id: String? = null, val settled: Boolean = false)

    private data class ClockReading(val wall: Long, val elapsed: Long)

    private fun clock() = ClockReading(nowMillis(), elapsedMillis())
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    @kotlinx.serialization.Serializable
    private data class TokenCharge(val id: String, val at: Long, val reserved: Long, val used: Long? = null, val settled: Boolean = false, @kotlinx.serialization.Transient val elapsedExpiry: Long = 0) {
        val charged: Long get() = used ?: reserved
    }

    data class TokenReservation internal constructor(val id: String, val provider: FreeAiProvider, val model: String, val credentialIdentity: String)

    /** Startup invokes this on IO before provider selectors and dispatch become available. */
    @Synchronized fun loadState() {
        awaitingExplicitLoad = false
        restore()
        _revision.update { it + 1 }
    }

    private var storage: java.io.File? = null
    private var restored = false
    private var awaitingExplicitLoad = false

    @Synchronized fun initialize(file: java.io.File, requireExplicitLoad: Boolean = false) {
        storage = file
        restored = false
        awaitingExplicitLoad = requireExplicitLoad
        buckets.clear()
        _revision.update { it + 1 }
    }

    private fun restore() {
        check(!awaitingExplicitLoad) { "QUOTA_STATE_LOADING: retained provider limits are not ready" }
        if (restored) return
        restored = true
        try {
            storage?.takeIf { it.isFile }?.let { file ->
                check(file.length() <= 1_048_576) { "Quota state exceeds its bounded storage contract" }
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val saved = json.parseToJsonElement(file.readText()) as? kotlinx.serialization.json.JsonObject ?: error("Invalid quota state")
                val clock = clock()
                saved.forEach { (key, value) ->
                    val provider = FreeAiProvider.entries.firstOrNull { it.name == key.substringBefore('|') } ?: return@forEach
                    val snapshot = if (value is kotlinx.serialization.json.JsonObject && listOf("requests", "cooldown", "nextLocalSlotAt", "tokens").any { it in value }) {
                        runCatching { json.decodeFromString<SavedBucket>(value.toString()) }.getOrNull()
                    } else {
                        val legacy = if (value is kotlinx.serialization.json.JsonPrimitive) value.content.toLongOrNull()?.let { FreeCooldown(it) } else runCatching { json.decodeFromString<FreeCooldown>(value.toString()) }.getOrNull()
                        legacy?.let { SavedBucket(cooldown = it) }
                    } ?: error("Invalid quota bucket")
                    check(snapshot.tokens.all { it.id.isNotBlank() && it.at >= 0 && it.reserved >= 0 && (it.used == null || it.used >= 0) } && snapshot.tokens.map { it.id }.distinct().size == snapshot.tokens.size) { "Invalid token reservations" }
                    val oldScope = key.substringAfter('|', "")
                    val scope = if (oldScope.startsWith("credential:") && oldScope.removePrefix("credential:").matches(Regex("[a-f0-9]{64}"))) "credential:" + FreeQuotaIdentity.protectDigest(oldScope.removePrefix("credential:")) else oldScope
                    val bucket = buckets.computeIfAbsent(provider to scope) { Bucket() }
                    bucket.cooldownNotBefore = maxOf(snapshot.cooldownNotBefore ?: 0, snapshot.cooldown?.retryAt ?: 0)
                    bucket.cooldown = snapshot.cooldown?.takeIf { bucket.cooldownNotBefore > clock.wall }?.let { cooldown ->
                        cooldown.copy(credentialIdentity = cooldown.credentialIdentity.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let(FreeQuotaIdentity::protectDigest) ?: cooldown.credentialIdentity)
                    }
                    bucket.requests.addAll(snapshot.requests.filter { it > clock.wall - HOUR_MS }.sorted().map { RequestCharge(it, clock.elapsed - (clock.wall - it).coerceAtLeast(0)) })
                    bucket.nextLocalSlotAt = snapshot.nextLocalSlotAt
                    bucket.nextLocalSlotElapsed = clock.elapsed + (snapshot.nextLocalSlotAt - clock.wall).coerceAtLeast(0)
                    bucket.elapsedRetryAt = clock.elapsed + (bucket.cooldownNotBefore - clock.wall).coerceAtLeast(0)
                    bucket.tokens += snapshot.tokens.filter { it.at > clock.wall - DAY_MS }.map { it.copy(elapsedExpiry = clock.elapsed + (it.at + DAY_MS - clock.wall).coerceAtLeast(0)) }
                }
            }
        } catch (error: Exception) {
            restored = false
            buckets.clear()
            throw IllegalStateException("QUOTA_STATE_UNREADABLE: retained provider limits could not be loaded", error)
        }
    }

    private fun persist() {
        try {
            storage?.let { target ->
                val clock = clock()
                val values = buckets.entries.mapNotNull { (key, value) ->
                    val cooldownDeadline = cooldownDeadline(value, clock)
                    val localSlotDeadline = localSlotDeadline(value, clock)
                    val requests = value.requests.filter { requestDeadline(it, HOUR_MS, clock) > clock.wall }.map { requestTimestamp(it, clock) }
                    val tokens = liveTokens(value, clock).map { it.copy(at = tokenDeadline(it, clock) - DAY_MS) }
                    if (cooldownDeadline <= clock.wall && localSlotDeadline <= clock.wall && requests.isEmpty() && tokens.isEmpty()) return@mapNotNull null
                    "${key.first.name}|${key.second}" to SavedBucket(value.cooldown?.takeIf { cooldownDeadline > clock.wall }, requests, localSlotDeadline, tokens, cooldownDeadline.takeIf { it > clock.wall })
                }.toMap()
                target.parentFile?.mkdirs()
                val temporary = java.io.File(target.path + ".tmp")
                val encoded = kotlinx.serialization.json.Json.encodeToString(values)
                check(encoded.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "Quota state exceeds its bounded storage contract" }
                temporary.writeText(encoded)
                java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
            _revision.update { it + 1 }
        } catch (error: Exception) {
            throw IllegalStateException("QUOTA_STORAGE_UNAVAILABLE: no new request can be sent until quota state is durable", error)
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
        withPermit(provider, model, credentialIdentity) {
            val reservation = reserveAttempt(provider, model, credentialIdentity, 0, 0)
            var notTransmitted = false
            try {
                request()
            } catch (error: Exception) {
                notTransmitted = error is java.net.UnknownHostException || error is java.nio.channels.UnresolvedAddressException
                throw error
            } finally {
                settle(reservation, null, definitelyNotTransmitted = notTransmitted)
            }
        }

    /** Preparation runs within the scope permit, but rejected payloads consume no request allowance. */
    suspend fun <T> withPermit(provider: FreeAiProvider, model: String, credentialIdentity: String = "unknown", request: suspend () -> T): T =
        restoredBucket(provider, model, credentialIdentity).permit.withPermit {
            // Short local spacing is scheduling, not an exhausted provider quota.
            // Keep the permit while waiting so parallel profiles cannot race the next slot.
            val wait = schedulingDelayMillis(provider, model, credentialIdentity)
            if (wait > 0) {
                currentCoroutineContext()[ProviderWaitContext]?.notifyRemaining?.invoke(wait)
                delay(wait)
            }
            try {
                request()
            } finally {
                if (provider == FreeAiProvider.POLLINATIONS) completedClassicRequest(model, credentialIdentity)
            }
        }

    @Synchronized
    private fun restoredBucket(provider: FreeAiProvider, model: String, credentialIdentity: String): Bucket {
        restore()
        return bucket(provider, model, credentialIdentity)
    }

    @Synchronized
    private fun completedClassicRequest(model: String, credentialIdentity: String) {
        // Classic's IP interval begins after the active response finishes, including
        // a cancelled stream. Cancellation releases the permit but not this spacing.
        val clock = clock()
        bucket(FreeAiProvider.POLLINATIONS, model, credentialIdentity).apply {
            nextLocalSlotAt = clock.wall + 30_000
            nextLocalSlotElapsed = clock.elapsed + 30_000
        }
        persist()
    }

    @Synchronized
    private fun schedulingDelayMillis(provider: FreeAiProvider, model: String, credentialIdentity: String): Long {
        restore()
        val now = nowMillis()
        // A server Retry-After is never converted into an automatic local wait.
        if (savedCooldown(provider, model, credentialIdentity) != null) return 0L
        // Exhausted rolling allowances remain visible, durable waits even when
        // their final few seconds resemble ordinary request spacing.
        if (exhaustedAllowanceUntilMillis(provider, model, credentialIdentity) != null) return 0L
        return ((cooldownUntilMillis(provider, model, credentialIdentity) ?: now) - now).takeIf { it in 1..60_000 } ?: 0L
    }

    @Synchronized
    internal fun reserve(provider: FreeAiProvider, model: String = "", credentialIdentity: String = "unknown") {
        reserveAttempt(provider, model, credentialIdentity, 0, 0)
    }

    /** Request and token admission share this monitor and one durable write. */
    @Synchronized
    fun reserveAttempt(provider: FreeAiProvider, model: String, credentialIdentity: String, inputTokens: Long, outputTokens: Long): TokenReservation {
        require(inputTokens >= 0 && outputTokens >= 0)
        restore()
        val bucket = bucket(provider, model, credentialIdentity)
        val clock = clock()
        val now = clock.wall
        bucket.requests.removeAll { requestDeadline(it, HOUR_MS, clock) <= now }
        val minuteRequests = bucket.requests.filter { requestDeadline(it, MINUTE_MS, clock) > now }
        val providerCooldown = activeCooldownBucket(provider, model, credentialIdentity, clock)
        var retryAt = maxOf(providerCooldown?.let { cooldownDeadline(it, clock) } ?: 0L, localSlotDeadline(bucket, clock))
        if (provider == FreeAiProvider.LLM7 && bucket.requests.isNotEmpty()) {
            retryAt = maxOf(retryAt, requestDeadline(bucket.requests.last, 1000, clock))
        }
        if (provider.requestsPerMinute > 0 && minuteRequests.size >= provider.requestsPerMinute) {
            retryAt = maxOf(retryAt, minuteRequests.minOf { requestDeadline(it, MINUTE_MS, clock) })
        }
        if (provider.requestsPerHour > 0 && bucket.requests.size >= provider.requestsPerHour) {
            retryAt = maxOf(retryAt, bucket.requests.minOf { requestDeadline(it, HOUR_MS, clock) })
        }
        val estimated = Math.addExact(inputTokens, outputTokens)
        val tokenRetry = if (provider == FreeAiProvider.LLM7) tokenDeadlineFor(bucket, estimated, clock) else null
        retryAt = maxOf(retryAt, tokenRetry ?: 0)
        if (retryAt > now) {
            // Local windows are scheduling state, not a persisted provider quota.
            val tokenCooldown = tokenRetry?.let { FreeCooldown(it, now, category = "daily_tokens", deadlineSource = "local_token_window", quotaScope = "credential") }
            throw FreeAiRateLimitException(provider, (retryAt - now + 999) / 1000, cooldown = tokenCooldown ?: providerCooldown?.cooldown, retryAt = retryAt)
        }
        val reservation = TokenReservation(java.util.UUID.randomUUID().toString(), provider, model, credentialIdentity)
        val requestCharge = RequestCharge(now, clock.elapsed, reservation.id)
        bucket.requests.addLast(requestCharge)
        if (provider == FreeAiProvider.LLM7 && estimated > 0) {
            bucket.tokens += TokenCharge(reservation.id, now, estimated, elapsedExpiry = clock.elapsed + DAY_MS)
        }
        val previousLocalSlot = bucket.nextLocalSlotAt
        val previousLocalElapsed = bucket.nextLocalSlotElapsed
        if (provider == FreeAiProvider.POLLINATIONS) {
            bucket.nextLocalSlotAt = now + 30_000
            bucket.nextLocalSlotElapsed = clock.elapsed + 30_000
        }
        try {
            persist()
        } catch (error: Exception) {
            // Nothing can be transmitted before this reservation returns. A failed
            // durable write must not leave an unreachable in-memory reservation.
            bucket.requests.removeLastOccurrence(requestCharge)
            bucket.tokens.removeAll { it.id == reservation.id }
            bucket.nextLocalSlotAt = previousLocalSlot
            bucket.nextLocalSlotElapsed = previousLocalElapsed
            throw error
        }
        return reservation
    }

    /** Unknown/ambiguous outcomes retain the reservation. Duplicate settlements cannot refund twice. */
    @Synchronized
    fun settle(reservation: TokenReservation?, reportedTokens: Long?, definitelyNotTransmitted: Boolean = false) {
        if (reservation == null) return
        restore()
        val bucket = bucket(reservation.provider, reservation.model, reservation.credentialIdentity)
        val index = bucket.tokens.indexOfFirst { it.id == reservation.id }
        val charge = bucket.tokens.getOrNull(index)
        val requestCharge = bucket.requests.firstOrNull { it.id == reservation.id }
        if (charge?.settled == true || requestCharge?.settled == true || (charge == null && requestCharge == null)) return
        val previousRequests = bucket.requests.toList()
        val previousTokens = bucket.tokens.toList()
        if (definitelyNotTransmitted) {
            requestCharge?.let(bucket.requests::remove)
            if (charge != null) bucket.tokens.removeAt(index)
        } else {
            if (requestCharge != null) {
                bucket.requests.clear()
                bucket.requests.addAll(previousRequests.map { if (it.id == reservation.id) it.copy(settled = true) else it })
            }
            if (charge != null) bucket.tokens[index] = charge.copy(used = reportedTokens?.takeIf { it > 0 }, settled = true)
        }
        try {
            persist()
        } catch (error: Exception) {
            bucket.tokens.clear()
            bucket.tokens.addAll(previousTokens)
            bucket.requests.clear()
            bucket.requests.addAll(previousRequests)
            throw error
        }
    }

    private fun requestTimestamp(charge: RequestCharge, clock: ClockReading): Long = maxOf(charge.at, clock.wall - (clock.elapsed - charge.elapsedAt).coerceAtLeast(0))

    private fun requestDeadline(charge: RequestCharge, window: Long, clock: ClockReading): Long = requestTimestamp(charge, clock) + window

    private fun localSlotDeadline(bucket: Bucket, clock: ClockReading): Long = maxOf(bucket.nextLocalSlotAt, clock.wall + (bucket.nextLocalSlotElapsed - clock.elapsed).coerceAtLeast(0))

    private fun tokenDeadline(charge: TokenCharge, clock: ClockReading): Long = maxOf(charge.at + DAY_MS, clock.wall + (charge.elapsedExpiry - clock.elapsed).coerceAtLeast(0))
    private fun liveTokens(bucket: Bucket, clock: ClockReading = clock()): List<TokenCharge> {
        bucket.tokens.removeAll { tokenDeadline(it, clock) <= clock.wall }
        return bucket.tokens.filter { it.charged > 0 }
    }

    private fun tokenDeadlineFor(bucket: Bucket, requested: Long, clock: ClockReading): Long? {
        check(requested <= LLM7_DAILY_TOKENS) { "DAILY_TOKEN_REQUEST_TOO_LARGE: reduce this request's input or output allowance" }
        val charges = liveTokens(bucket, clock).sortedBy { tokenDeadline(it, clock) }
        var charged = charges.sumOf { it.charged }
        if (charged + requested <= LLM7_DAILY_TOKENS && (requested > 0 || charged < LLM7_DAILY_TOKENS)) return null
        for (charge in charges) {
            charged -= charge.charged
            if (charged + requested <= LLM7_DAILY_TOKENS && (requested > 0 || charged < LLM7_DAILY_TOKENS)) return tokenDeadline(charge, clock)
        }
        return null
    }

    @Synchronized
    fun reservedTokens(provider: FreeAiProvider, model: String = "", credentialIdentity: String = "unknown"): Long {
        restore()
        return liveTokens(bucket(provider, model, credentialIdentity)).sumOf { it.charged }
    }

    @Synchronized
    fun defer(provider: FreeAiProvider, retryAfter: String?, model: String = "", credentialIdentity: String = "unknown", evidence: FreeCooldown? = null, serverDate: String? = null): Long {
        restore()
        val clock = clock()
        val now = clock.wall
        val seconds = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        val numericDeadline = seconds?.let { runCatching { Math.addExact(now, Math.multiplyExact(it, 1000L)) }.getOrNull() }
        val date = runCatching { ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        val serverNow = runCatching { ZonedDateTime.parse(serverDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        val adjustedDate = date?.let { if (serverNow != null) runCatching { Math.addExact(now, (it - serverNow).coerceAtLeast(0)) }.getOrNull() else it }
        val deadline = numericDeadline ?: adjustedDate?.takeIf { it >= now } ?: (now + MINUTE_MS)
        val scopedIdentity = if (evidence != null && evidence.quotaScope.lowercase() !in setOf("token", "credential", "api_key")) "unknown" else credentialIdentity
        val bucket = if (evidence?.quotaScope?.lowercase() in setOf("model", "model_only")) {
            modelCooldownBucket(provider, model)
        } else {
            bucket(provider, model, scopedIdentity)
        }
        val previousDeadline = activeCooldownBucket(provider, model, scopedIdentity, clock)?.let { cooldownDeadline(it, clock) } ?: 0
        if (deadline >= previousDeadline) {
            bucket.elapsedRetryAt = clock.elapsed + (deadline - now).coerceAtLeast(0)
            bucket.cooldownNotBefore = deadline
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
        val clock = clock()
        val now = clock.wall
        val bucket = bucket(provider, model, credentialIdentity)
        bucket.requests.removeAll { requestDeadline(it, HOUR_MS, clock) <= now }
        val minuteRequests = bucket.requests.filter { requestDeadline(it, MINUTE_MS, clock) > now }
        var retryAt = maxOf(activeCooldownBucket(provider, model, credentialIdentity, clock)?.let { cooldownDeadline(it, clock) } ?: 0L, localSlotDeadline(bucket, clock))
        if (provider == FreeAiProvider.LLM7 && bucket.requests.isNotEmpty()) {
            retryAt = maxOf(retryAt, requestDeadline(bucket.requests.last, 1000, clock))
        }
        if (provider.requestsPerMinute > 0 && minuteRequests.size >= provider.requestsPerMinute) {
            retryAt = maxOf(retryAt, minuteRequests.minOf { requestDeadline(it, MINUTE_MS, clock) })
        }
        if (provider.requestsPerHour > 0 && bucket.requests.size >= provider.requestsPerHour) {
            retryAt = maxOf(retryAt, bucket.requests.minOf { requestDeadline(it, HOUR_MS, clock) })
        }
        if (provider == FreeAiProvider.LLM7) retryAt = maxOf(retryAt, tokenDeadlineFor(bucket, 0, clock) ?: 0)
        return retryAt.takeIf { it > now }
    }

    /** Exhausted allowances exclude normal per-request spacing and server evidence. */
    @Synchronized
    fun exhaustedAllowanceUntilMillis(provider: FreeAiProvider, model: String = "", credentialIdentity: String = "unknown"): Long? {
        restore()
        val clock = clock()
        val bucket = bucket(provider, model, credentialIdentity)
        bucket.requests.removeAll { requestDeadline(it, HOUR_MS, clock) <= clock.wall }
        val minuteRequests = bucket.requests.filter { requestDeadline(it, MINUTE_MS, clock) > clock.wall }
        var deadline = 0L
        if (provider.requestsPerMinute > 0 && minuteRequests.size >= provider.requestsPerMinute) {
            deadline = maxOf(deadline, minuteRequests.minOf { requestDeadline(it, MINUTE_MS, clock) })
        }
        if (provider.requestsPerHour > 0 && bucket.requests.size >= provider.requestsPerHour) {
            deadline = maxOf(deadline, bucket.requests.minOf { requestDeadline(it, HOUR_MS, clock) })
        }
        if (provider == FreeAiProvider.LLM7) deadline = maxOf(deadline, tokenDeadlineFor(bucket, 0, clock) ?: 0L)
        return deadline.takeIf { it > clock.wall }
    }

    @Synchronized
    fun savedCooldown(provider: FreeAiProvider, model: String = "", credentialIdentity: String = "unknown"): FreeCooldown? {
        restore()
        // Evidence has one immutable deadline. Scheduling uses a separate wall/
        // monotonic projection, which must never rewrite the originating record.
        return activeCooldownBucket(provider, model, credentialIdentity, clock())?.cooldown
    }

    private fun cooldownDeadline(bucket: Bucket, clock: ClockReading): Long = if (bucket.cooldown == null) 0 else maxOf(bucket.cooldownNotBefore, clock.wall + (bucket.elapsedRetryAt - clock.elapsed).coerceAtLeast(0))

    private fun modelCooldownBucket(provider: FreeAiProvider, model: String): Bucket = buckets.computeIfAbsent(provider to "model:${model.length}:$model") { Bucket() }

    /** Opaque scheduling identity; profiles sharing the observed restriction share one worker. */
    @Synchronized
    fun activeQuotaScopeKey(provider: FreeAiProvider, model: String = "", credentialIdentity: String = "unknown"): String {
        restore()
        val selected = activeCooldownBucket(provider, model, credentialIdentity, clock()) ?: bucket(provider, model, credentialIdentity)
        val scope = buckets.entries.first { it.value === selected }.key
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("${scope.first.name}|${scope.second}".toByteArray(Charsets.UTF_8))
        return "free-quota:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun activeCooldownBucket(provider: FreeAiProvider, model: String, credentialIdentity: String, clock: ClockReading): Bucket? {
        // Old deadlines have unknown scope. Preserve them across credential changes rather
        // than claiming that a new key bypasses an IP/account-wide quota.
        return listOf(bucket(provider, model, credentialIdentity), bucket(provider, model), modelCooldownBucket(provider, model))
            .filter { cooldownDeadline(it, clock) > clock.wall }.maxByOrNull { cooldownDeadline(it, clock) }
    }

    companion object {
        // Cooldowns are real elapsed deadlines: deep sleep must count toward the wait.
        // Persisted wall deadlines reconstruct a new elapsed origin after process death/reboot.
        val shared = FreeAiRequestLimiter(elapsedMillis = ProviderElapsedClock::now)
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L

        // https://docs.llm7.io/limits, reviewed 2026-10-24. Server evidence may tighten this ceiling.
        private const val LLM7_DAILY_TOKENS = 100_000L
    }
}

class FreeAiRateLimitException(provider: FreeAiProvider, retrySeconds: Long, detail: String? = null, val cooldown: FreeCooldown? = null, val retryAt: Long = cooldown?.retryAt ?: System.currentTimeMillis() + retrySeconds * 1000, val localBlock: Boolean = true) : IllegalStateException(cooldown?.takeIf { localBlock }?.copy(retryAt = retryAt)?.blockedMessage(provider) ?: "${provider.displayName} is rate limited. Next eligible retry: ${java.time.Instant.ofEpochMilli(retryAt)} (in $retrySeconds seconds). This does not guarantee the provider’s allowance has reset." + ((detail ?: cooldown?.message)?.let { " $it" } ?: ""))
