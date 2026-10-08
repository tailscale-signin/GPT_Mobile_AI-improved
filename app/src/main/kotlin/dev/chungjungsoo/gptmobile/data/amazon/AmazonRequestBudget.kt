package dev.chungjungsoo.gptmobile.data.amazon

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class AmazonBudgetState(
    val usageDay: String = "",
    val usageCount: Int = 0,
    val nextRequestAt: Long = 0,
    val marketplaceCooldowns: Map<String, Long> = emptyMap()
)

/** A no-backup operational ledger. W4 will migrate it to the shared Room budget table. */
@Singleton
class AmazonRequestBudget internal constructor(private val file: File, private val clock: Clock = Clock.systemUTC()) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "amazon-free-budget-v1.json"))

    private val mutex = Mutex()
    private var state: AmazonBudgetState? = null

    /** Serializes public-page requests, including redirects; failures consume the reserved slot. */
    suspend fun <T> request(market: AmazonFreeMarket, limit: Int, allowed: suspend () -> Boolean, fetch: suspend () -> T): T = mutex.withLock {
        var current = withContext(Dispatchers.IO) { load() }
        val now = clock.millis()
        if ((current.marketplaceCooldowns[market.domain] ?: 0) > now) {
            throw AmazonReadException(AmazonReadError.COOLDOWN_ACTIVE, "Amazon is in a marketplace cooldown. Try again after the cooldown ends.")
        }
        val wait = (current.nextRequestAt - now).coerceIn(0, 5_000)
        if (wait > 0) delay(wait)
        checkAllowed(allowed)
        val day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().toString()
        // A backwards clock/date change never resets the persisted allowance.
        val reset = current.usageDay.isEmpty() || day > current.usageDay
        val used = if (reset) 0 else current.usageCount
        if (used >= limit.coerceIn(1, 100)) throw AmazonReadException(AmazonReadError.QUOTA_EXCEEDED, "The native Amazon daily request allowance has been reached. It resets on a later UTC day.")
        current = current.copy(usageDay = if (reset) day else current.usageDay, usageCount = used + 1, nextRequestAt = clock.millis() + 5_000)
        withContext(Dispatchers.IO) { save(current) }
        checkAllowed(allowed)
        try {
            fetch()
        } catch (failure: AmazonReadException) {
            val cooldown = when (failure.code) {
                AmazonReadError.CHALLENGE_REQUIRED -> 24 * 60 * 60 * 1000L
                AmazonReadError.RATE_LIMITED -> failure.retryAfterMillis?.coerceIn(60_000, 24 * 60 * 60 * 1000L) ?: 6 * 60 * 60 * 1000L
                else -> null
            }
            if (cooldown != null) withContext(Dispatchers.IO) { save(current.copy(marketplaceCooldowns = current.marketplaceCooldowns + (market.domain to clock.millis() + cooldown))) }
            throw failure
        }
    }

    /** Parsing a 200 response may still detect a robot-check page. */
    suspend fun challenge(market: AmazonFreeMarket) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = load()
            save(current.copy(marketplaceCooldowns = current.marketplaceCooldowns + (market.domain to clock.millis() + 24 * 60 * 60 * 1000L)))
        }
    }

    private suspend fun checkAllowed(allowed: suspend () -> Boolean) {
        if (!allowed()) throw AmazonReadException(AmazonReadError.PLUGIN_DISABLED, "Amazon Research Free is disabled for this request.")
    }

    private fun load(): AmazonBudgetState {
        state?.let { return it }
        val loaded = if (file.exists() || File(file.path + ".bak").exists()) {
            try {
                AtomicFile(file).openRead().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(1_024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 16_384)
                        output.write(buffer, 0, count)
                    }
                    val bytes = output.toByteArray()
                    Json.decodeFromString<AmazonBudgetState>(bytes.decodeToString()).also { value ->
                        require(value.usageCount in 0..100)
                        require(value.usageDay.isEmpty() || runCatching { LocalDate.parse(value.usageDay) }.isSuccess)
                        require(value.marketplaceCooldowns.keys.all { AmazonFreeMarket.fromDomain(it) != null })
                    }
                }
            } catch (_: Exception) {
                // Corruption must not silently reset a consumed request allowance.
                throw AmazonReadException(AmazonReadError.NETWORK_ERROR, "The local Amazon request ledger could not be loaded. No lookup was sent.")
            }
        } else {
            AmazonBudgetState()
        }
        state = loaded
        return loaded
    }

    private fun save(value: AmazonBudgetState) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(Json.encodeToString(value).encodeToByteArray())
            atomic.finishWrite(stream)
            state = value
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }
}
