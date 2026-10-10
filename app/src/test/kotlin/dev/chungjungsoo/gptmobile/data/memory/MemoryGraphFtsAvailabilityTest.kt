package dev.chungjungsoo.gptmobile.data.memory

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGraphFtsAvailabilityTest {
    @Test
    fun `prefix expressions use the syntax of the detected index engine`() {
        val repository = MemoryGraphRepository(database)
        assertEquals("\"fren\"* AND \"revol\"*", repository.ftsQuery("fren revol"))
        assertEquals("\"fren*\" AND \"revol*\"", repository.ftsQuery("fren revol", fts4 = true))
    }
    private val sql = mockk<SupportSQLiteDatabase>(relaxed = true)
    private val helper = mockk<SupportSQLiteOpenHelper>()
    private val dao = mockk<MemoryGraphDao>()
    private val database = mockk<ChatDatabaseV2>()

    init {
        every { helper.readableDatabase } returns sql
        every { helper.writableDatabase } returns sql
        every { database.openHelper } returns helper
        every { database.memoryGraphDao() } returns dao
        coEvery { dao.searchLike(any(), any(), any()) } returns emptyList()
    }

    @Test
    fun `new index tries actual FTS5 capability without compile option diagnostics`() = runBlocking {
        every { sql.query("SELECT sql FROM sqlite_master WHERE name = 'memory_graph_fts'") } returns cursor(emptyList())
        every { sql.query(any<SupportSQLiteQuery>()) } returns cursor(emptyList())
        val repository = MemoryGraphRepository(database)
        repeat(2) { assertTrue(repository.searchNodes("astronomy", null).isEmpty()) }
        verify(exactly = 0) { sql.query("PRAGMA module_list") }
        verify(exactly = 1) { sql.execSQL(match { it.contains("USING fts5") }) }
        verify(exactly = 2) { sql.query(any<SupportSQLiteQuery>()) }
    }

    @Test
    fun `FTS4 is used when the device has no FTS5 module`() = runBlocking {
        every { sql.query("SELECT sql FROM sqlite_master WHERE name = 'memory_graph_fts'") } returns cursor(emptyList())
        every { sql.execSQL(match { it.contains("USING fts5") }) } throws IllegalStateException("no such module: fts5")
        every { sql.query(any<SupportSQLiteQuery>()) } returns cursor(emptyList())
        val repository = MemoryGraphRepository(database)
        repeat(2) { assertTrue(repository.searchNodes("astronomy", null).isEmpty()) }
        verify(exactly = 1) { sql.execSQL(match { it.contains("USING fts5") }) }
        verify(exactly = 1) { sql.execSQL(match { it.contains("USING fts4") }) }
        verify(exactly = 2) { sql.query(any<SupportSQLiteQuery>()) }
    }

    @Test
    fun `restored FTS4 index is reused without claiming FTS5 or recreating it`() = runBlocking {
        every { sql.query("SELECT sql FROM sqlite_master WHERE name = 'memory_graph_fts'") } returns cursor(listOf("CREATE VIRTUAL TABLE memory_graph_fts USING fts4(entity_id, scope, text)"))
        every { sql.query("SELECT count(*) FROM memory_graph_fts") } returns cursor(listOf("2"))
        every { sql.query(any<SupportSQLiteQuery>()) } returns cursor(emptyList())
        val repository = MemoryGraphRepository(database)
        repeat(2) { assertTrue(repository.searchNodes("astronomy", null).isEmpty()) }
        verify(exactly = 0) { sql.execSQL(any<String>()) }
        verify(exactly = 2) { sql.query(any<SupportSQLiteQuery>()) }
    }

    @Test
    fun `unavailable FTS modules cache the portable fallback`() = runBlocking {
        every { sql.query("SELECT sql FROM sqlite_master WHERE name = 'memory_graph_fts'") } returns cursor(emptyList())
        every { sql.execSQL(match { it.startsWith("CREATE VIRTUAL TABLE") }) } throws IllegalStateException("no such module")
        val repository = MemoryGraphRepository(database)
        repeat(2) { assertTrue(repository.searchNodes("astronomy", null).isEmpty()) }
        verify(exactly = 2) { sql.execSQL(any<String>()) }
        coVerify(exactly = 2) { dao.searchLike("personal", "%astronomy%", 20) }
    }

    private fun cursor(values: List<String>): Cursor {
        var index = -1
        return mockk<Cursor>(relaxed = true).also { cursor ->
            every { cursor.moveToNext() } answers { ++index < values.size }
            every { cursor.moveToFirst() } answers {
                index = 0
                values.isNotEmpty()
            }
            every { cursor.getString(0) } answers { values[index] }
        }
    }
}
