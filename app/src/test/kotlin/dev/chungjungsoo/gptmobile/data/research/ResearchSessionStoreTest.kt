package dev.chungjungsoo.gptmobile.data.research

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
@Config(manifest = Config.NONE, sdk = [34])
class ResearchSessionStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val directory: File get() = File(context.filesDir, "research-history")

    @Before fun cleanBefore() {
        directory.deleteRecursively()
    }

    @After fun cleanAfter() {
        directory.deleteRecursively()
    }

    @Test fun persistentHistorySurvivesNewStoreInstance() = runBlocking {
        ResearchSessionStore(context).journal("run", 1, true).save(ResearchSnapshot("task"))
        assertEquals("task", ResearchSessionStore(context).journal("run", 1, true).load()?.task)
        assertNull(ResearchSessionStore(context).journal("run", 2, true).load())
    }

    @Test fun relatedProfileRunsReuseFreshResearchForTheSameTask() = runBlocking {
        val store = ResearchSessionStore(context)
        val original = store.journal("profile-a", 1, true, turnId = "1:42")
        original.save(ResearchSnapshot("What changed in the latest release?", complete = true))

        val related = store.journal("profile-b", 1, true, turnId = "1:42")
        assertEquals("What changed in the latest release?", related.load("  WHAT changed in the latest   release? ")?.task)
        assertNull(store.journal("profile-c", 1, true, turnId = "1:43").load("What changed in the latest release?"))
        assertNotNull(related.load("A sibling subquestion shares the same corpus"))
    }

    @Test fun taskScopedResearchLockSerializesSiblingProfiles() = runBlocking {
        val store = ResearchSessionStore(context)
        val first = store.journal("profile-a", 1, true, turnId = "1:7")
        val second = store.journal("profile-b", 1, true, turnId = "1:7")
        val active = AtomicInteger()
        val peak = AtomicInteger()
        coroutineScope {
            listOf(first, second).map { journal ->
                async {
                    journal.withTaskLock("same task") {
                        peak.updateAndGet { maxOf(it, active.incrementAndGet()) }
                        delay(25)
                        active.decrementAndGet()
                    }
                }
            }.forEach { it.await() }
        }
        assertEquals(1, peak.get())
    }

    @Test fun temporaryChatsNeverWriteToDisk() = runBlocking {
        val store = ResearchSessionStore(context)
        store.journal("temporary", 1, false).save(ResearchSnapshot("private task"))
        assertNotNull(store.sessions.value["temporary"])
        assertFalse(directory.exists())
    }

    @Test fun deletionPreventsLateWritesFromAnActiveJob() = runBlocking {
        val store = ResearchSessionStore(context)
        val journal = store.journal("run", 1, true)
        journal.save(ResearchSnapshot("task"))
        store.deleteChat(1)
        journal.save(ResearchSnapshot("late result"))
        assertTrue(journal.stopRequested())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(store.sessions.value.isEmpty())
    }

    @Test fun historyHasADiskAndMemoryCeiling() = runBlocking {
        val store = ResearchSessionStore(context)
        repeat(50) { store.journal("run$it", 1, true).save(ResearchSnapshot("task", updatedAt = it.toLong(), complete = true)) }
        assertEquals(48, directory.listFiles().orEmpty().count { it.extension == "json" })
        assertEquals(24, store.sessions.value.size)
    }

    @Test fun threeHundredSourceBodiesSurviveRestartOutsideTheManifest() = runBlocking {
        val sources = (1..300).map { index -> ResearchSource("S$index", "https://example.org/$index", "Source $index", status = "Read", passage = "Evidence ".repeat(600), retrievalQuality = "FULL_TEXT") }
        ResearchSessionStore(context).journal("large", 1, true, "turn").save(ResearchSnapshot("task", sources = sources, targetSources = 300))
        assertEquals(300, directory.listFiles().orEmpty().count { it.extension == "source" })
        assertTrue(directory.listFiles().orEmpty().single { it.extension == "json" }.length() < 512 * 1024)
        val restored = ResearchSessionStore(context).journal("large", 1, true, "turn").load()!!
        assertEquals(300, restored.sources.count { it.readable })
    }

    @Test fun changingProfileAfterRestartDoesNotCreateAnotherTurnBudget() {
        val first = ResearchSessionStore(context).journal("a", 1, true, "turn").budget(120)
        assertTrue(first!!.tryCharge(dev.chungjungsoo.gptmobile.data.agent.ResearchBudget.Phase.DISCOVERY, true))
        val restored = ResearchSessionStore(context).journal("b", 1, true, "turn").budget(2400)!!
        assertEquals(first.snapshot(), restored.snapshot())
    }

    @Test fun thousandSourcesAndChunkReferencesSurvivePagedManifestRestore() = runBlocking {
        val journal = ResearchSessionStore(context).journal("large-paged", 1, true, "paged-turn")
        val chunks = journal.retainBody("Unique source body ".repeat(6000))
        val sources = (1..1000).map { index -> ResearchSource("S$index", "https://example.org/$index", "Source $index", status = "Read", passage = "Captured evidence $index", bodyChunks = chunks) }
        journal.save(ResearchSnapshot("task", sources = sources, targetSources = 1000))
        val restored = ResearchSessionStore(context).journal("large-paged", 1, true, "paged-turn").load()!!
        assertEquals(1000, restored.sources.count { it.readable })
        assertEquals(chunks, restored.sources.last().bodyChunks)
        assertTrue(directory.listFiles().orEmpty().filter { it.extension == "index" }.all { it.length() <= 64 * 1024 })
        ResearchSessionStore(context).deleteChat(1)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun longLedgerSurvivesRestartAndProfileSwitchWithoutRefund() {
        val plan = dev.chungjungsoo.gptmobile.data.model.effectiveResearchPlan(dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings().forEffort(100))
        val first = ResearchSessionStore(context).journal("a", 1, true, "long-turn").goalBudget(plan)
        assertTrue(first.ledger!!.charge(physical = 3000, downloaded = 3L * 1024 * 1024 * 1024))
        val restored = ResearchSessionStore(context).journal("b", 1, true, "long-turn").goalBudget(plan)
        assertEquals(first.ledger.snapshot(), restored.ledger!!.snapshot())
    }
}
