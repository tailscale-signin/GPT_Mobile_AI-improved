package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResearchArchiveTest {
    private lateinit var database: ResearchDatabase

    @Before fun create() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ResearchDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun close() {
        database.close()
    }
    private suspend fun capture(index: Int, epoch: Long = 0, public: Boolean = true, authority: String = "native") = ResearchArchiveIngestor.capture(database, ResearchSource("S$index", "https://example.org/paper/$index", "Evidence $index", retrievedAt = 1000, doi = "10.1234/paper-$index"), authority, public, 7, "turn", true, epoch, researchHash("body-$index"), 12)

    @Test fun fifoCapIsTransactionalAndReuseDoesNotBecomeLru() = runBlocking {
        val first = capture(0)
        val original = database.research().membership(first.workId)!!
        coroutineScope { (1..1000).chunked(100).map { batch -> async { batch.forEach { capture(it) } } }.awaitAll() }
        assertEquals(1000, database.research().count())
        assertNull(database.research().membership(first.workId))
        val retained = capture(500)
        val sequence = database.research().membership(retained.workId)!!.archiveSequence
        capture(500)
        assertEquals(sequence, database.research().membership(retained.workId)!!.archiveSequence)
        assertEquals(1000, database.research().count())
        assertTrue(original.archiveSequence < sequence)
        assertNotNull(database.research().version(first.versionId))
        assertEquals(ArticleCoverage.METADATA_ONLY, first.coverage)
    }

    @Test fun clearEpochBlocksLateWorkAndPrivateSourcesNeverJoinGlobalArchive() = runBlocking {
        val first = capture(1)
        database.research().clearArchive(2000)
        capture(2, epoch = 0)
        assertEquals(0, database.research().count())
        capture(3, epoch = 1, public = false, authority = "account-a")
        assertEquals(0, database.research().count())
        capture(4, epoch = 1)
        assertEquals(1, database.research().count())
        assertNotNull(database.research().version(first.versionId))
    }

    @Test fun versionsIdentitiesAndChatDeletionKeepAuthorityBoundaries() = runBlocking {
        val first = capture(1, public = false, authority = "account-a")
        val other = capture(1, public = false, authority = "account-b")
        assertFalse(first.workId == other.workId)
        val dao = database.research()
        dao.tombstone(ResearchDeletionTombstone("chat:7", 20))
        assertTrue(runCatching { capture(2) }.isFailure)
        assertFalse(ResearchArchiveIngestor.publicUrl("https://example.org/paper?token=private"))
        assertFalse(ResearchArchiveIngestor.publicUrl("http://127.0.0.1/paper"))
        assertFalse(ResearchArchiveIngestor.publicUrl("https://person:secret@example.org/paper"))
    }
}
