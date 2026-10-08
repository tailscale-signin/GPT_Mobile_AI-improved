package dev.chungjungsoo.gptmobile.data.amazon

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
internal data class AmazonBudgetState(
    val usageDay: String = "",
    val usageCount: Int = 0,
    val nextRequestAt: Long = 0,
    val marketplaceCooldowns: Map<String, Long> = emptyMap()
)

/** Room-backed operational ledger; request reservations and legacy import are atomic. */
@Singleton
class AmazonRequestBudget internal constructor(private val store: AmazonBudgetStore, private val clock: Clock = Clock.systemUTC()) {
    internal constructor(file: File, clock: Clock = Clock.systemUTC()) : this(AmazonFileBudgetStore(file), clock)

    @Inject constructor(database: ChatDatabaseV2, @ApplicationContext context: Context) : this(AmazonRoomBudgetStore(database, File(context.noBackupFilesDir, "amazon-free-budget-v1.json")))

    private val mutex = Mutex()

    /** Serializes public-page requests, including redirects; failures consume the reserved slot. */
    suspend fun <T> request(market: AmazonFreeMarket, limit: Int, allowed: suspend () -> Boolean, fetch: suspend () -> T): T = mutex.withLock {
        checkAllowed(allowed)
        val current = store.read()
        val now = clock.millis()
        if ((current.marketplaceCooldowns[market.domain] ?: 0) > now) {
            throw AmazonReadException(AmazonReadError.COOLDOWN_ACTIVE, "Amazon is in a marketplace cooldown. Try again after the cooldown ends.")
        }
        val wait = (current.nextRequestAt - now).coerceIn(0, 5_000)
        if (wait > 0) delay(wait)
        checkAllowed(allowed)
        store.update { latest ->
            if ((latest.marketplaceCooldowns[market.domain] ?: 0) > clock.millis()) {
                throw AmazonReadException(AmazonReadError.COOLDOWN_ACTIVE, "Amazon is in a marketplace cooldown. Try again after the cooldown ends.")
            }
            val day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().toString()
            // A backwards clock/date change never resets the persisted allowance.
            val reset = latest.usageDay.isEmpty() || day > latest.usageDay
            val used = if (reset) 0 else latest.usageCount
            if (used >= limit.coerceIn(1, 100)) throw AmazonReadException(AmazonReadError.QUOTA_EXCEEDED, "The native Amazon daily request allowance has been reached. It resets on a later UTC day.")
            latest.copy(usageDay = if (reset) day else latest.usageDay, usageCount = used + 1, nextRequestAt = clock.millis() + 5_000)
        }
        checkAllowed(allowed)
        try {
            fetch()
        } catch (failure: AmazonReadException) {
            val cooldown = when (failure.code) {
                AmazonReadError.CHALLENGE_REQUIRED -> 24 * 60 * 60 * 1000L
                AmazonReadError.RATE_LIMITED -> failure.retryAfterMillis?.coerceIn(60_000, 24 * 60 * 60 * 1000L) ?: 6 * 60 * 60 * 1000L
                else -> null
            }
            // A known server block is operational state, even if the caller revokes/cancels now.
            if (cooldown != null) {
                withContext(NonCancellable) {
                    store.update { latest -> latest.copy(marketplaceCooldowns = latest.marketplaceCooldowns + (market.domain to clock.millis() + cooldown)) }
                }
            }
            currentCoroutineContext().ensureActive()
            throw failure
        }
    }

    suspend fun challenge(market: AmazonFreeMarket) = mutex.withLock {
        store.update { current -> current.copy(marketplaceCooldowns = current.marketplaceCooldowns + (market.domain to clock.millis() + 24 * 60 * 60 * 1000L)) }
        Unit
    }

    private suspend fun checkAllowed(allowed: suspend () -> Boolean) {
        if (!allowed()) throw AmazonReadException(AmazonReadError.PLUGIN_DISABLED, "Amazon Research Free is disabled for this request.")
    }
}
