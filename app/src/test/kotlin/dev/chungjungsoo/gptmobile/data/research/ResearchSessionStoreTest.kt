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
        val original = store.journal("profile-a", 1, true)
        original.save(ResearchSnapshot("What changed in the latest release?", complete = true))

        val related = store.journal("profile-b", 1, true)
        assertEquals("What changed in the latest release?", related.load("  WHAT changed in the latest   release? ")?.task)
    }

    @Test fun taskScopedResearchLockSerializesSiblingProfiles() = runBlocking {
        val store = ResearchSessionStore(context)
        val first = store.journal("profile-a", 1, true)
        val second = store.journal("profile-b", 1, true)
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
        repeat(50) { store.journal("run$it", 1, true).save(ResearchSnapshot("task", updatedAt = it.toLong())) }
        assertEquals(48, directory.listFiles().orEmpty().count { it.extension == "json" })
        assertEquals(24, store.sessions.value.size)
    }
}
