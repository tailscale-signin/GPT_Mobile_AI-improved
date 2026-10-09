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
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGraphFtsAvailabilityTest {
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
    fun `registered FTS5 works without the compile option function`() = runBlocking {
        every { sql.query("PRAGMA module_list") } returns cursor(listOf("fts3", "fts5"))
        every { sql.query("SELECT name FROM sqlite_master WHERE name = 'memory_graph_fts'") } returns cursor(listOf("memory_graph_fts"))
        every { sql.query(any<SupportSQLiteQuery>()) } returns cursor(emptyList())
        val repository = MemoryGraphRepository(database)

        assertTrue(repository.searchNodes("astronomy", null).isEmpty())
        assertTrue(repository.searchNodes("astronomy", null).isEmpty())

        verify(exactly = 1) { sql.query("PRAGMA module_list") }
        verify(exactly = 0) { sql.query("SELECT sqlite_compileoption_used('ENABLE_FTS5')") }
        verify(exactly = 1) { sql.execSQL(match { it.startsWith("CREATE VIRTUAL TABLE") }) }
        verify(exactly = 2) { sql.query(any<SupportSQLiteQuery>()) }
    }

    @Test
    fun `missing module uses and caches the portable fallback`() = runBlocking {
        for (modules in listOf(emptyList(), listOf("fts3", "fts4"))) {
            every { sql.query("PRAGMA module_list") } returns cursor(modules)
            val repository = MemoryGraphRepository(database)
            assertTrue(repository.searchNodes("astronomy", null).isEmpty())
            assertTrue(repository.searchNodes("astronomy", null).isEmpty())
        }

        verify(exactly = 2) { sql.query("PRAGMA module_list") }
        verify(exactly = 0) { sql.execSQL(any<String>()) }
        coVerify(exactly = 4) { dao.searchLike("personal", "%astronomy%", 20) }
    }

    @Test
    fun `module inspection errors preserve the portable fallback`() = runBlocking {
        every { sql.query("PRAGMA module_list") } throws IllegalStateException("Module inspection unavailable")
        val repository = MemoryGraphRepository(database)
        assertTrue(repository.searchNodes("astronomy", null).isEmpty())
        assertTrue(repository.searchNodes("astronomy", null).isEmpty())

        verify(exactly = 1) { sql.query("PRAGMA module_list") }
        verify(exactly = 0) { sql.execSQL(any<String>()) }
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
