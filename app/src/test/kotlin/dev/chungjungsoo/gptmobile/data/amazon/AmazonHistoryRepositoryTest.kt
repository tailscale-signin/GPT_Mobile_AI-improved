package dev.chungjungsoo.gptmobile.data.amazon

import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class AmazonHistoryRepositoryTest {
    private lateinit var database: ChatDatabaseV2
    private lateinit var repository: AmazonHistoryRepository
    private val clock = AmazonMutableClock()
    private val market = AmazonFreeMarket.CANADA
    private val asin = "B000000001"
    private fun point(amount: String = "90.00", source: String = "product_page") = AmazonProductObservation(asin, market, "Headphones", clock.instant(), source, amount = BigDecimal(amount), currency = "CAD")

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        repository = AmazonHistoryRepository(database, clock)
    }

    @After fun close() = database.close()

    @Test fun duplicateAcquisitionSeparatePageSeriesAndUnknownCurrencyRemainHonest() = runBlocking {
        val result = AmazonFetchResult(listOf(point()))
        repository.record("owner", "request", result, { true })
        repository.record("owner", "request", result, { true })
        repository.record("owner", "search", AmazonFetchResult(listOf(point(source = "search_page"))), { true })
        repository.record("owner", "ambiguous", AmazonFetchResult(listOf(point().copy(currency = null))), { true })
        repository.record("owner", "foreign", AmazonFetchResult(listOf(point().copy(currency = "USD"))), { true })
        val history = repository.history("owner", market, asin)
        assertEquals(2, history.retainedCount)
        assertEquals(2, history.observations.map { it.seriesKey }.distinct().size)
        assertTrue(history.observations.all { it.amount == "90.00" && it.contextQuality == "incomplete_listing" })
        assertEquals(2, history.checks.count { it.outcome == "PRICE_UNAVAILABLE" })
        assertEquals(0, repository.history("another-owner", market, asin).retainedCount)
        assertFalse(history.toJson(market, asin).getValue("comparableOffer").toString().toBoolean())
    }

    @Test fun temporaryAndMissingChatsNeverCaptureButExplicitChecksCanPersist() = runBlocking {
        database.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Temporary", isTemporary = true))
        val result = AmazonFetchResult(listOf(point()))
        repository.record("owner", "temporary", result, { true }, chatId = 1)
        repository.record("owner", "missing", result, { true }, chatId = 2)
        assertEquals(0, repository.history("owner", market, asin).retainedCount)
        database.chatRoomDao().updateTemporary(1, false)
        repository.record("owner", "ordinary", result, { true }, chatId = 1)
        repository.record("owner", "explicit", result, { true })
        assertEquals(2, repository.history("owner", market, asin).retainedCount)
    }

    @Test fun revokedGrantRollsBackPointsChecksAndWatchCreation() = runBlocking {
        var calls = 0
        val failure = runCatching { repository.record("owner", "revoked", AmazonFetchResult(listOf(point())), { ++calls == 1 }) }.exceptionOrNull()
        assertEquals(AmazonReadError.PLUGIN_DISABLED, (failure as AmazonReadException).code)
        val history = repository.history("owner", market, asin)
        assertTrue(history.observations.isEmpty() && history.checks.isEmpty())
        calls = 0
        assertTrue(runCatching { repository.saveWatch("owner", market, asin, "80", { ++calls == 1 }) }.isFailure)
        assertTrue(repository.listWatches("owner").isEmpty())
    }

    @Test fun watchOwnershipGenerationAndDeletionProtectHistory() = runBlocking {
        val watch = repository.saveWatch("owner", market, asin, "80.00", { true })
        assertEquals("AWAITING_MATCHING_PRICE", watch.state)
        assertTrue(runCatching { repository.saveWatch("other", market, asin, "70", { true }, watch.id, watch.generation) }.isFailure)
        repository.pauseWatch("owner", watch.id, watch.generation, true)
        repository.finishCheck(watch, "stale", AmazonFetchResult(listOf(point("79"))), { true })
        assertEquals("PAUSED", repository.listWatches("owner").single().state)
        assertEquals(null, repository.listWatches("owner").single().lastAttemptAt)
        assertTrue(runCatching { repository.deleteWatch("owner", watch.id, watch.generation) }.isFailure)
        repository.record("owner", "manual", AmazonFetchResult(listOf(point())), { true })
        repository.deleteWatch("owner", watch.id, watch.generation + 1)
        assertTrue(repository.listWatches("owner").isEmpty())
        assertEquals(1, repository.history("owner", market, asin).retainedCount)
    }

    @Test fun belowTargetIncompleteOfferNeverBecomesTargetMetAndFailuresKeepLastSuccess() = runBlocking {
        val watch = repository.saveWatch("owner", market, asin, "80", { true })
        repository.finishCheck(watch, "first", AmazonFetchResult(listOf(point("79"))), { true })
        val successful = repository.listWatches("owner").single()
        assertEquals("AWAITING_MATCHING_PRICE", successful.state)
        assertEquals("OFFER_CONTEXT_INCOMPLETE", successful.lastOutcome)
        val lastSuccess = successful.lastSuccessAt
        clock.advance(6_000)
        repository.finishCheck(successful, "blocked", AmazonFetchResult(emptyList(), listOf(AmazonItemFailure(AmazonReadError.CHALLENGE_REQUIRED, "blocked", asin))), { true })
        val blocked = repository.listWatches("owner").single()
        assertEquals(lastSuccess, blocked.lastSuccessAt)
        assertTrue(requireNotNull(blocked.lastAttemptAt) > requireNotNull(lastSuccess))
        assertEquals("CHALLENGE_REQUIRED", blocked.lastOutcome)
        assertEquals("AWAITING_MATCHING_PRICE", blocked.state)
    }

    @Test fun retentionProtectsLatestWatchedPointsAndClearingPreservesBudgetAndTargets() = runBlocking {
        val watch = repository.saveWatch("owner", market, asin, "80", { true })
        repository.record("owner", "old", AmazonFetchResult(listOf(point())), { true })
        clock.advance(366 * 86_400_000L)
        val other = point().copy(asin = "B000000002")
        repository.record("owner", "new", AmazonFetchResult(listOf(other)), { true })
        assertEquals(1, repository.history("owner", market, asin).retainedCount)
        database.amazonDao().trimHistory(1)
        assertEquals(1, repository.history("owner", market, asin).retainedCount)
        assertEquals(0, repository.history("owner", market, other.asin).retainedCount)
        database.amazonDao().saveBudget(AmazonBudgetEntity(stateJson = "preserved operational state"))
        repository.clearHistory("owner")
        assertEquals(0, repository.history("owner", market, asin).retainedCount)
        assertEquals(watch.id, repository.listWatches("owner").single().id)
        assertEquals("preserved operational state", database.amazonDao().budget()?.stateJson)
    }

    @Test fun invalidPricesFutureSourcesAndWatchCapacityAreBounded() = runBlocking {
        for (product in listOf(point("-1"), point("1000000000"), point("1.234"), point().copy(acquiredAt = clock.instant().plusSeconds(301)), point().copy(sourceType = "cached_page"))) {
            repository.record("owner", UUID.randomUUID().toString(), AmazonFetchResult(listOf(product)), { true })
        }
        assertEquals(0, repository.history("owner", market, asin).retainedCount)
        for (i in 1..20) repository.saveWatch("owner", market, asin, i.toString(), { true })
        assertTrue(runCatching { repository.saveWatch("owner", market, asin, "21", { true }) }.isFailure)
        assertTrue(runCatching { repository.saveWatch("other", market, asin, "0", { true }) }.isFailure)
        assertEquals(20, repository.listWatches("owner").size)
    }
}
