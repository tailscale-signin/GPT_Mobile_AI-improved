package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchAttempt
import dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchStatisticsEpoch
import dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchTelemetryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResearchDurableStatisticsTest {
    private lateinit var database: ResearchDatabase

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ResearchDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun close() {
        database.close()
    }

    @Test fun durableReceiptsOutliveRawRetentionAndRepeatedImportsDoNotMultiplyTotals() {
        val dao = database.statistics()
        val events = (1..100001).map { ResearchAttempt("event-$it", "engine", "search", it.toLong(), durationMs = 10, outcome = "SUCCESS", schemaVersion = 2) }
        dao.recordBatch(events)
        dao.recordBatch(events.take(100))
        assertEquals(100001, dao.rollup("engine", 0).successes)
        val directory = kotlin.io.path.createTempDirectory("research-raw-pruned").toFile()
        try {
            ResearchTelemetryStore.attach(directory, dao)
            val store = ResearchTelemetryStore(directory)
            assertEquals(100001, store.rollup("engine").attempts)
            store.record(events.first())
            assertEquals(100001, store.rollup("engine").attempts)
            assertTrue(directory.listFiles().isNullOrEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun cancellationsCachesTransportAndEmptyResultsHaveDifferentDenominators() {
        val dao = database.statistics()
        fun event(id: String, result: String) = ResearchAttempt(id, "engine", "read", 100, durationMs = 50, outcome = result, schemaVersion = 2)
        dao.recordBatch(listOf(event("useful", "SUCCESS"), event("empty", "EMPTY"), event("failure", "FAILED"), event("canceled", "CANCELLED"), event("http", "TRANSPORT_SUCCESS"), event("cache", "SUCCESS").copy(cacheHit = true), event("skip", "FAILED").copy(dispatched = false)))
        val result = dao.rollup("engine", 0)
        assertEquals(3, result.attempts)
        assertEquals(1, result.successes)
        assertEquals(1, result.empty)
        assertEquals(1, result.failures)
        assertEquals(1, result.canceled)
        assertEquals(1, result.transportSuccesses)
        assertEquals(1, result.cached)
        assertEquals(1, result.skipped)
        assertEquals(50L, dao.latencyAt("engine", 0, 0))
        dao.clearEpoch(ResearchStatisticsEpoch(clearedAt = 200))
        dao.record(event("useful", "SUCCESS"))
        assertEquals(0, dao.rollup("engine", 200).attempts)
        assertEquals(-1L, dao.record(event("other-old", "SUCCESS")))
    }
}
