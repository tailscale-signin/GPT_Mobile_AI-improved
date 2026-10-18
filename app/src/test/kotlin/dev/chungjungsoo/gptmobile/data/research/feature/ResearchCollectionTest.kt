package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.ResearchSession
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResearchCollectionTest {
    @Test fun uncitedRegistryRemainsCompleteAboveCitationLimitAndStableDuringStreaming() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ResearchDatabase::class.java).allowMainThreadQueries().build()
        try {
            val sessions = mockk<ResearchSessionStore>()
            val chats = mockk<ChatDatabaseV2>(relaxed = true)
            coEvery { chats.chatRoomDao().getChatRoomsByIds(listOf(7)) } returns listOf(ChatRoomV2(id = 7, title = "Research"))
            val sources = (1..130).map { i -> ResearchSource("S$i", "https://example.org/paper/$i", "Trial $i", doi = "10.1234/trial-$i", publicationType = "journal-article") }
            coEvery { sessions.exportChat(7) } returns listOf(ResearchSession("run", 7, ResearchSnapshot("Synthetic search", sources = sources), "7:3", "public"))
            sources.forEach { ResearchArchiveIngestor.capture(db, it, "public", true, 7, "run", true, 0, researchHash(""), 0) }
            val resolver = CitationBindingResolver(ResearchArchiveRepository(db, sessions), sessions, chats)
            val message = MessageV2(id = 10, chatId = 7, linkedMessageId = 3, currentRunId = "run", content = "Still preparing", platformType = "profile")
            val streaming = resolver.bind(message, includeCitations = false)
            assertEquals(130, streaming.size)
            assertTrue(streaming.all { it.paragraphId == "collection" })
            resolver.bind(message.copy(content = "More text arrived"), includeCitations = false)
            assertEquals(130, db.research().allBindings().size)
            val complete = resolver.bind(message.copy(content = sources.take(5).joinToString(" ") { "doi:${it.doi}" }))
            assertEquals(130, complete.map { it.workId }.distinct().size)
            assertEquals(130, complete.count { it.paragraphId == "collection" })
            assertEquals(5, complete.count { it.paragraphId != "collection" })
        } finally {
            db.close()
        }
    }
}
