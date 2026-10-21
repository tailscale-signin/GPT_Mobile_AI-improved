package dev.chungjungsoo.gptmobile.data.research

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32], shadows = [SessionAtomicFileReads::class])
class ResearchSessionStoreHeapTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val directory: File get() = File(context.filesDir, "research-history")

    @Before fun before() {
        directory.deleteRecursively()
        SessionAtomicFileReads.opened.clear()
    }

    @After fun after() {
        directory.deleteRecursively()
        SessionAtomicFileReads.opened.clear()
    }

    @Test fun authorizedTurnMetadataFiltersBeforeAnyUnselectedManifestOrEvidenceRead() = runBlocking {
        seedScopes()
        SessionAtomicFileReads.opened.clear()
        val sessions = ResearchSessionStore(context).exportTurn(7, setOf("selected-turn"), setOf("allowed"))
        assertEquals(listOf("selected"), sessions.map { it.runId })
        assertEquals("Retained selected evidence", sessions.single().snapshot.sources.single().passage)
        val opened = SessionAtomicFileReads.opened.toSet()
        assertTrue(File(directory, researchHash("selected") + ".json").absolutePath in opened)
        for (run in listOf("other-chat", "other-turn", "other-authority")) {
            assertFalse("Unselected manifest must not be decoded: $run", File(directory, researchHash(run) + ".json").absolutePath in opened)
        }
        assertEquals(1, opened.count { it.endsWith(".source") })
        assertEquals(4, directory.listFiles().orEmpty().count { it.extension == "sessionmeta" })
    }

    @Test fun rebuildingMissingMetadataReadsSmallManifestsButSkipsUnselectedEvidenceBodies() = runBlocking {
        seedScopes()
        directory.listFiles().orEmpty().filter { it.extension == "sessionmeta" }.forEach { it.delete() }
        SessionAtomicFileReads.opened.clear()
        val store = ResearchSessionStore(context)
        assertEquals(listOf("selected"), store.exportTurn(7, setOf("selected-turn"), setOf("allowed")).map { it.runId })
        assertEquals(1, SessionAtomicFileReads.opened.count { it.endsWith(".source") })
        assertEquals(4, directory.listFiles().orEmpty().count { it.extension == "sessionmeta" })
        SessionAtomicFileReads.opened.clear()
        store.loadChat(99)
        assertTrue(store.sessions.value.isEmpty())
        assertFalse(SessionAtomicFileReads.opened.any { it.endsWith(".source") || it.endsWith(".json") })
    }

    @Test fun moreThanFortyMiBOfRetainedEvidenceStreamsWithoutLoadingAllSessionsInto256MiBHeap() = runBlocking {
        if (!java.lang.Boolean.getBoolean("gptmobile.heapStress")) return@runBlocking
        assertTrue("This gate must run in the constrained heap worker", Runtime.getRuntime().maxMemory() <= 256L * 1024 * 1024)
        seedLargeCorpus()
        assertTrue(directory.listFiles().orEmpty().filter { it.extension == "source" }.sumOf { it.length() } > 40L * 1024 * 1024)
        SessionAtomicFileReads.opened.clear()
        val store = ResearchSessionStore(context)
        store.loadChat(7)
        assertTrue(store.sessions.value.isNotEmpty())
        assertTrue(store.sessions.value.size <= 24)
        var sessions = 0
        var sourceCount = 0
        var bodyBytes = 0L
        val runIds = mutableSetOf<String>()
        store.streamChat(7) { session ->
            assertTrue(runIds.add(session.runId))
            assertEquals(7, session.chatId)
            assertTrue(session.snapshot.sources.all { it.readable })
            sessions++
            sourceCount += session.snapshot.sources.size
            bodyBytes += session.snapshot.sources.sumOf { it.passage.toByteArray().size.toLong() }
        }
        assertEquals(90, sessions)
        assertEquals(900, sourceCount)
        assertTrue(bodyBytes > 40L * 1024 * 1024)
        assertTrue(store.sessions.value.size <= 24)
        assertFalse(SessionAtomicFileReads.opened.any { it.endsWith(researchHash("unrelated") + ".json") })
    }

    private suspend fun seedScopes() {
        val store = ResearchSessionStore(context)
        for ((run, chat, turn, authority) in listOf(
            Scope("selected", 7, "selected-turn", "allowed"),
            Scope("other-chat", 8, "selected-turn", "allowed"),
            Scope("other-turn", 7, "other-turn", "allowed"),
            Scope("other-authority", 7, "selected-turn", "restricted")
        )) {
            store.journal(run, chat, true, turn, authority).save(ResearchSnapshot("Task $run", sources = listOf(ResearchSource("S1", "https://example.org/$run", run, "Read", "Retained $run evidence"))))
        }
    }

    private suspend fun seedLargeCorpus() {
        val store = ResearchSessionStore(context)
        val body = "Captured source sentence with exact supporting evidence. ".repeat(900).take(48 * 1024)
        repeat(90) { row ->
            val sources = (1..10).map { source -> ResearchSource("S$source", "https://example.org/$row/$source", "Source $row/$source", "Read", "$row/$source $body") }
            store.journal("large-$row", 7, true, "turn-$row", "allowed").save(ResearchSnapshot("Task $row", sources = sources, complete = false, updatedAt = row.toLong()))
        }
        store.journal("unrelated", 8, true, "other", "restricted").save(ResearchSnapshot("Other task", sources = listOf(ResearchSource("other", "https://example.org/other", "Other", "Read", body))))
    }

    private data class Scope(val run: String, val chat: Int, val turn: String, val authority: String)
}

/** Observe actual AtomicFile reads, including metadata scans, without replacing their behavior. */
@Implements(AtomicFile::class)
class SessionAtomicFileReads {
    @RealObject private lateinit var real: AtomicFile

    @Implementation
    protected fun openRead(): FileInputStream {
        opened.add(real.baseFile.absolutePath)
        return Shadow.directlyOn(real, AtomicFile::class.java, "openRead")
    }

    companion object {
        val opened: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
