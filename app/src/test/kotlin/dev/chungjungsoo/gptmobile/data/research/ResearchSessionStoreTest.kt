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

    @Test fun privateAuthorityAndSharingOptOutIsolateCorpora() = runBlocking {
        val store = ResearchSessionStore(context)
        val a = store.journal("a", 1, true, "turn", shareEnabled = false).scoped("policy", true)
        a.save(ResearchSnapshot("private", sources = listOf(ResearchSource("S1", "https://example.org", "private"))))
        val b = store.journal("b", 1, true, "turn").scoped("policy", true)
        assertNull(b.load("sibling"))
        assertNull(store.journal("a", 1, true, "turn").scoped("other-policy", true).load())
        assertNull(store.journal("c", 1, true, "turn").scoped("other-policy", true).load("sibling"))
    }

    @Test fun leasesExcludeSiblingsAndTurnStopPropagates() {
        val store = ResearchSessionStore(context)
        val a = store.journal("a", 1, false, "turn").scoped("same", true)
        val b = store.journal("b", 1, false, "turn").scoped("same", true)
        assertTrue(a.claimWork("source", 10000))
        assertFalse(b.claimWork("source", 10000))
        a.releaseWork("source")
        assertTrue(b.claimWork("source", 10000))
        store.stop("a")
        assertTrue(b.stopRequested())
    }

    @Test fun lateFailureCannotOverwriteReadableSiblingEvidence() = runBlocking {
        val store = ResearchSessionStore(context)
        val a = store.journal("a", 1, true, "turn").scoped("same", true)
        val b = store.journal("b", 1, true, "turn").scoped("same", true)
        val source = ResearchSource(a.sourceId("url", "S1"), "https://example.org", "source", status = "Read", passage = "Retained readable evidence")
        a.save(ResearchSnapshot("one", sources = listOf(source)))
        b.save(ResearchSnapshot("two", sources = listOf(source.copy(status = "Failed", passage = "", retrievalQuality = "EMPTY", retrievedAt = 99))))
        assertTrue(b.load("third")!!.sources.single().readable)
        assertEquals(source.id, b.sourceId("url", "S2"))
    }

    @Test fun recoveryKeepsPrivateOwnerAuthorityAndRejectsAnotherOwner() = runBlocking {
        val first = ResearchSessionStore(context).journal("first", 1, true, "1:original", ownerScope = "profile-a").scoped("policy", false)
        first.save(ResearchSnapshot("Original task", questions = listOf("Question")))
        val restored = ResearchSessionStore(context)
        assertEquals("Original task", restored.journal("continue", 1, true, "1:original", ownerScope = "profile-a").scoped("policy", false).load("Original task")!!.task)
        assertNull(restored.journal("other", 1, true, "1:original", ownerScope = "profile-b").scoped("policy", false).load("Original task"))
    }

    @Test fun newGenerationFencesLateSavesButKeepsCheckpoint() = runBlocking {
        val store = ResearchSessionStore(context)
        val original = store.journal("first", 1, true, "root").scoped("same", true)
        original.save(ResearchSnapshot("Original task", notes = listOf("saved evidence")))
        store.resume(1, "root", 7)
        val resumed = store.journal("second", 1, true, "root").scoped("same", true)
        assertEquals(listOf("saved evidence"), resumed.load("Original task")!!.notes)
        resumed.save(ResearchSnapshot("Original task", notes = listOf("new owner")))
        original.save(ResearchSnapshot("Original task", notes = listOf("late stale write")))
        assertEquals(listOf("new owner"), resumed.load("Original task")!!.notes)
    }

    @Test fun pagedClaimsSurviveManifestLimitRestartAndDeletion() = runBlocking {
        val passage = "Synthetic quotation retained exactly for source verification."
        val source = ResearchSource("S1", "https://example.org", "Fixture", "Read", passage)
        val claims = (1..1500).map { ResearchClaim("Claim $it " + "x".repeat(500), "S1", passage) }
        ResearchSessionStore(context).journal("many", 1, true, "root").save(ResearchSnapshot("Task", sources = listOf(source), claims = claims))
        val store = ResearchSessionStore(context)
        assertEquals(1500, store.journal("new", 1, true, "root").load("Task")!!.claims.size)
        store.deleteChat(1)
        assertTrue(java.io.File(context.filesDir, "research-history").listFiles().orEmpty().none { it.extension == "claim" })
    }

    @Test fun persistedFenceRejectsOldOwnerAfterStoreRestartAndPreservesTemporaryBoundary() = runBlocking {
        val oldStore = ResearchSessionStore(context)
        val old = oldStore.journal("old", 1, true, turnId = "1:1")
        old.save(ResearchSnapshot("original", updatedAt = 1))
        val restarted = ResearchSessionStore(context)
        restarted.resume(1, "1:1", 3)
        val current = restarted.journal("new", 1, true, turnId = "1:1")
        current.save(ResearchSnapshot("current", updatedAt = 3))
        old.save(ResearchSnapshot("stale", updatedAt = 4))
        assertTrue(old.stopRequested())
        assertEquals("current", ResearchSessionStore(context).journal("reader", 1, true, turnId = "1:1").load("current")!!.task)
        restarted.deleteChat(1)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        ResearchSessionStore(context).resume(2, "2:1", 3, persistent = false)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun syntheticIncidentCheckpointReuses207Sources14ClaimsAndSpentBudgetAfterWriterDenial() = runBlocking {
        val store = ResearchSessionStore(context)
        val original = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(id = 1, chatId = 1, content = "Compare synthetic composite panels", platformType = null)
        val root = dev.chungjungsoo.gptmobile.data.agent.rootWorkId(listOf(original), "run-a")
        val journal = store.journal("run-a", 1, true, root, authority = "fixture")
        val budget = journal.budget(100, 1000000)!!
        repeat(24) { assertTrue(budget.tryCharge(dev.chungjungsoo.gptmobile.data.agent.ResearchBudget.Phase.DISCOVERY, physicalRequest = true)) }
        val sources = (1..207).map { index ->
            ResearchSource(
                "S$index",
                "https://example.org/study/$index",
                "Synthetic source $index",
                status = when {
                    index <= 129 -> "Metadata only"
                    index <= 176 -> "Discovered"
                    index <= 195 -> "Failed"
                    index <= 202 -> "Partially read"
                    else -> "Read"
                },
                passage = if (index > 195) "Synthetic panel evidence with explicit uncertainty." else "",
                retrievedAt = System.currentTimeMillis()
            )
        }
        val claims = (1..14).map { index ->
            ResearchClaim(
                "S${196 + (index - 1) % 12}",
                "Synthetic claim $index",
                "Synthetic panel evidence with explicit uncertainty.",
                verdict = when {
                    index <= 5 -> "Supported"
                    index <= 11 -> "Insufficient"
                    else -> "Unreviewed"
                }
            )
        }
        journal.save(ResearchSnapshot(original.content, phase = "Partial useful", sources = sources, claims = claims, attempts = 31, searches = 3, engineResponses = 22, engineFailures = 9, targetSources = 12, notes = listOf("Independent reviewer unavailable", "Evidence saved; final writer HTTP 403, specific cause unavailable")))
        val continueMessage = original.copy(id = 3, content = "Continue")
        val recoveredRoot = dev.chungjungsoo.gptmobile.data.agent.rootWorkId(listOf(original, continueMessage), "run-b")
        assertEquals(root, recoveredRoot)
        val restarted = ResearchSessionStore(context)
        restarted.resume(1, recoveredRoot, continueMessage.id)
        val resumed = restarted.journal("run-b", 1, true, recoveredRoot, authority = "fixture")
        val checkpoint = resumed.load(original.content)!!
        assertEquals(207, checkpoint.sources.size)
        assertEquals(12, checkpoint.sources.count { it.readable })
        assertEquals(mapOf("Supported" to 5, "Insufficient" to 6, "Unreviewed" to 3), checkpoint.claims.groupingBy { it.verdict }.eachCount())
        assertEquals(24, resumed.budget(100, 1000000)!!.snapshot().usedLogical)
        assertFalse(checkpoint.complete)
        assertTrue(checkpoint.notes.any { it.contains("reviewer unavailable") })
    }
}
