package dev.chungjungsoo.gptmobile.data.telemetry.tool

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolStatisticsDatabaseTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ToolStatisticsDatabase::class.java).allowMainThreadQueries().build()

    @Test fun profileAndStartHourScopesNeverDoubleCountLifetimeBuckets() = runBlocking {
        val db = database()
        try {
            val dao = db.statistics()
            dao.put(ToolSubject("tool", "plugin", "native", "search", "epoch", 0))
            for (profile in listOf("a", "b")) {
                for (hour in listOf(-1L, 100L, 101L)) dao.put(ToolRollup("tool", hour, profile = profile).transition(null, ToolOutcome.SUCCESS))
            }
            assertEquals(2L, dao.summary("plugin").first().sumOf { it.total })
            assertEquals(1L, dao.summary("plugin", "a").first().sumOf { it.total })
            assertEquals(1L, dao.summary("plugin", "b", 100, 101).first().sumOf { it.total })
            assertEquals(4L, dao.chart("plugin", 100).first().sumOf { it.total })
        } finally {
            db.close()
        }
    }

    @Test fun outcomeFilterPrecedesPaginationAndResetRetainsReplayReceipt() = runBlocking {
        val db = database()
        try {
            val dao = db.statistics()
            dao.put(ToolSubject("tool", "plugin", "native", "search", "epoch", 0))
            dao.put(ToolStartReceipt("old", "tool", "epoch"))
            dao.put(ToolInvocation("old", "tool", "epoch", "p", "root", null, "CLIENT", 1, outcome = "FAILURE"))
            dao.put(ToolInvocation("new", "tool", "epoch", "p", "root", null, "CLIENT", 2, outcome = "SUCCESS"))
            assertEquals("old", dao.activity("plugin", 1, outcome = "FAILURE").first().single().id)
            dao.clearInvocations("tool")
            assertNull(dao.invocation("old"))
            assertNotNull(dao.receipt("old"))
        } finally {
            db.close()
        }
    }

    @Test fun failedTransactionRollsBackBothStartAndCounters() = runBlocking {
        val db = database()
        try {
            val dao = db.statistics()
            try {
                db.withTransaction {
                    dao.put(ToolStartReceipt("id", "tool", "epoch"))
                    dao.put(ToolRollup("tool").transition(null, ToolOutcome.IN_PROGRESS))
                    error("simulate failed commit")
                }
            } catch (_: IllegalStateException) { }
            assertNull(dao.receipt("id"))
            assertNull(dao.rollup("tool", -1))
        } finally {
            db.close()
        }
    }
}
