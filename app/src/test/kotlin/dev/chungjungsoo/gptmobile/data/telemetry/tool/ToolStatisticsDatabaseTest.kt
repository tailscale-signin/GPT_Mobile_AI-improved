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

    @Test fun legacyMigrationPreservesUnattributedTotalsAndReplayIdentities() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-statistics-test.db"
        context.deleteDatabase(name)
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        val sql = requireNotNull(javaClass.classLoader?.getResourceAsStream("tool-statistics-v1.sql")).bufferedReader().use { it.readText() }
        sql.lines().filterNot { it.startsWith("--") }.joinToString("\n").split(';').filter { it.isNotBlank() }.forEach(old::execSQL)
        old.execSQL("INSERT INTO tool_rollup VALUES ('tool', -1, 1, 1, 0, 0, 0, 0, 0, 0, 100, 0, 0, '0,0,0,0,0,0,0,0')")
        old.version = 1
        old.close()
        val migrated = Room.databaseBuilder(context, ToolStatisticsDatabase::class.java, name).addMigrations(ToolStatisticsDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            assertEquals(1L, migrated.statistics().rollup("tool", -1, "")?.success)
            assertNull(migrated.statistics().rollup("tool", -1, "new-profile"))
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
