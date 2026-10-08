package dev.chungjungsoo.gptmobile.data.amazon

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class AmazonRequestBudgetTest {
    @get:Rule val folder = TemporaryFolder()
    private val market = AmazonFreeMarket.CANADA
    private val clock = AmazonMutableClock()
    private fun file() = File(folder.root, "budget.json")

    @Test
    fun failedAttemptsCountAcrossAppRestartAndBackwardsClockDoesNotResetAllowance() = runBlocking {
        val first = AmazonRequestBudget(file(), clock)
        val failure = runCatching { first.request(market, 1, { true }) { throw AmazonReadException(AmazonReadError.NETWORK_ERROR, "failed") } }.exceptionOrNull()
        assertEquals(AmazonReadError.NETWORK_ERROR, (failure as AmazonReadException).code)
        clock.advance(6_000)
        var calls = 0
        val restarted = AmazonRequestBudget(file(), clock)
        assertCode(AmazonReadError.QUOTA_EXCEEDED) { restarted.request(market, 1, { true }) { calls++ } }
        clock.advance(-86_400_000)
        assertCode(AmazonReadError.QUOTA_EXCEEDED) { restarted.request(market, 1, { true }) { calls++ } }
        clock.advance(2 * 86_400_000L)
        restarted.request(market, 1, { true }) { calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun rateLimitCooldownPersistsAndIsLimitedToItsMarketplace() = runBlocking {
        val budget = AmazonRequestBudget(file(), clock)
        assertCode(AmazonReadError.RATE_LIMITED) { budget.request(market, 10, { true }) { throw AmazonReadException(AmazonReadError.RATE_LIMITED, "limited", 60_000) } }
        clock.advance(6_000)
        val restarted = AmazonRequestBudget(file(), clock)
        assertCode(AmazonReadError.COOLDOWN_ACTIVE) { restarted.request(market, 10, { true }) { error("must not send") } }
        restarted.request(AmazonFreeMarket.UNITED_STATES, 10, { true }) { clock.advance(6_000) }
        clock.advance(60_000)
        restarted.request(market, 10, { true }) { }
    }

    @Test
    fun disabledRequestsAndCorruptLedgerNeverSendOrResetBudget() = runBlocking {
        val budget = AmazonRequestBudget(file(), clock)
        assertCode(AmazonReadError.PLUGIN_DISABLED) { budget.request(market, 1, { false }) { error("must not send") } }
        assertTrue(!file().exists())
        file().writeText("{broken")
        assertCode(AmazonReadError.NETWORK_ERROR) { AmazonRequestBudget(file(), clock).request(market, 1, { true }) { error("must not send") } }
        assertEquals("{broken", file().readText())
    }

    @Test
    fun htmlChallengeCreatesPersistentDayLongCooldown() = runBlocking {
        AmazonRequestBudget(file(), clock).challenge(market)
        clock.advance(6_000)
        assertCode(AmazonReadError.COOLDOWN_ACTIVE) { AmazonRequestBudget(file(), clock).request(market, 10, { true }) { error("must not send") } }
        clock.advance(86_400_000)
        AmazonRequestBudget(file(), clock).request(market, 10, { true }) { }
    }

    private suspend fun assertCode(code: AmazonReadError, block: suspend () -> Unit) {
        assertEquals(code, (runCatching { block() }.exceptionOrNull() as? AmazonReadException)?.code)
    }
}

internal class AmazonMutableClock(private var now: Instant = Instant.parse("2026-10-08T00:00:00Z")) : Clock() {
    fun advance(millis: Long) {
        now = now.plusMillis(millis)
    }
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock = this
}
