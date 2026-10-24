package dev.chungjungsoo.gptmobile.data.research

import android.content.ContextWrapper
import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ResearchBudget
import dev.chungjungsoo.gptmobile.data.agent.ResponseRecoveryContext
import dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryPacket
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliverySession
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity
import java.io.File
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ResearchEvidenceManifestTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var context: ContextWrapper
    private val root = "1:42"
    private val task = "Compare synthetic composite panel findings"

    @Before fun setup() {
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = files.root
            override fun getNoBackupFilesDir(): File = files.root
        }
    }

    private suspend fun accepted(store: ResearchSessionStore, taskRoot: String = root) = store.acceptTask(
        1, taskRoot, 42, task, listOf("p1", "p2"), "p1", "accepted-policy", "accepted-source-grant", true
    )

    private fun capturedSnapshot(): ResearchSnapshot {
        val sources = (1..207).map { index ->
            val passage = if (index > 195) {
                "Panel finding $index: the captured test measured stiffness under a specified load. " +
                    "The study reported a modest difference with substantial uncertainty and no established long-term durability."
            } else {
                ""
            }
            ResearchSource(
                "S$index", "https://example.org/panels/$index", "Composite panel evidence $index",
                status = when {
                    index <= 129 -> "Metadata only"
                    index <= 176 -> "Discovered"
                    index <= 195 -> "Failed"
                    index <= 202 -> "Partially read"
                    else -> "Read"
                },
                passage = passage, retrievedAt = System.currentTimeMillis(), versionId = "capture-$index",
                publicCapture = true, retrievalReceipt = "receipt-$index"
            )
        }
        val claims = (1..14).map { index ->
            val source = sources[195 + (index - 1) % 12]
            ResearchClaim(
                "Panel finding $index",
                source.id,
                source.passage,
                verdict = when {
                    index <= 5 -> "Supported"
                    index <= 11 -> "Insufficient"
                    else -> "Unreviewed"
                },
                questionId = "Q1",
                relevance = "Relevant",
                exactQuoteVerified = index <= 11,
                reviewedSourceVersion = if (index <= 11) source.versionId else ""
            )
        }
        val snapshot = ResearchSnapshot(
            task, phase = "Partial useful", sources = sources, claims = claims,
            questionPlan = listOf(ResearchQuestion("Q1", "What do panel tests establish?", "Captured test passages")),
            attempts = 31, searches = 3, engineResponses = 22, engineFailures = 9, targetSources = 12,
            notes = listOf("Independent reviewer unavailable", "Final writer HTTP 403; evidence retained"),
            updatedAt = 100, complete = false
        )
        return snapshot.copy(reviewRecord = ResearchReviewRecord("UNAVAILABLE", snapshot.evidenceRevisionDigest(), researchHash("retained handoff"), "Independent reviewer unavailable"))
    }

    @Test fun restartSelectsRichestAuthorizedRevisionAfterNewerEmptyCheckpointAndPreservesAllSpentWork() = runBlocking {
        val store = ResearchSessionStore(context)
        val accepted = accepted(store)
        val journal = store.journal("collected", 1, true, root, authority = "authorized", ownerScope = "p1")
        val budget = journal.budget(100, 1_000_000)!!
        repeat(24) { assertTrue(budget.tryCharge(ResearchBudget.Phase.DISCOVERY, true)) }
        val original = capturedSnapshot()
        journal.save(original)
        store.journal("late-shell", 1, true, root, authority = "authorized", ownerScope = "p2")
            .save(ResearchSnapshot(task, phase = "Planning", updatedAt = 900))
        val restarted = ResearchSessionStore(context)
        restarted.resumeGeneration(1, root, "retry-writing")
        val recovery = restarted.journal("recovered", 1, true, root, authority = "authorized", ownerScope = "p1")
        val best = requireNotNull(recovery.load(task))
        assertEquals(207, best.sources.size)
        assertEquals(12, best.sources.count { it.readable })
        assertEquals(mapOf("Supported" to 5, "Insufficient" to 6, "Unreviewed" to 3), best.claims.groupingBy { it.verdict }.eachCount())
        assertEquals("UNAVAILABLE", best.currentReviewRecord()?.status)
        assertEquals(original.evidenceRevisionDigest(), best.evidenceRevisionDigest())
        assertEquals(24, recovery.budget(100, 1_000_000)!!.snapshot().usedLogical)
        assertEquals(accepted, restarted.acceptedTask(root))
        assertEquals(task, restarted.acceptedTask(root)?.taskText)
        assertTrue(restarted.evidenceManifest(root)!!.revisions.size >= 2)
        assertFalse(best.complete)
    }

    @Test fun inlineRecoveryPacketKeepsSupportedClaimsAndUnavailableReviewAheadOfTwoHundredSourceMetadataRows() = runBlocking {
        val store = ResearchSessionStore(context)
        accepted(store)
        val original = capturedSnapshot()
        store.journal("captured", 1, true, root, authority = "authorized").save(original)
        store.journal("shell", 1, true, root, authority = "authorized").save(ResearchSnapshot(task, updatedAt = 500))
        val snapshots = ResearchSessionStore(context).exportTurn(1, setOf(root), setOf("authorized")).map { it.snapshot }
        val recovery = ResponseRecoveryContext(emptyList(), emptyList(), emptyList(), snapshots, root)
        val text = recovery.prefix(6000)
        assertTrue(text.length <= 6000)
        assertTrue(text.contains("Panel finding"))
        assertTrue(text.contains("handoffReviewStatus"))
        assertTrue(text.contains("UNAVAILABLE"))
        assertTrue(text.contains("read_recovery_context"))
        assertEquals(original.evidenceRevisionDigest(), recovery.researchSnapshotForDelivery()?.evidenceRevisionDigest())
        val packet = ResearchDeliveryPacket.build(requireNotNull(recovery.researchSnapshotForDelivery()), ResearchReportDetail.BRIEF)
        assertTrue(packet.claims.any { it.text.startsWith("Panel finding") && it.verdict == "Supported" })
    }

    @Test fun authorityFilterRunsBeforeLoadingUnauthorizedCorruptEvidencePages() = runBlocking {
        val store = ResearchSessionStore(context)
        accepted(store)
        store.journal("authorized-run", 1, true, root, authority = "authorized", shareEnabled = false).save(capturedSnapshot())
        store.journal("private-run", 1, true, root, authority = "private:p2", shareEnabled = false)
            .save(ResearchSnapshot(task, notes = listOf("PRIVATE SECRET"), updatedAt = 900))
        val manifest = requireNotNull(store.evidenceManifest(root))
        val privateRevision = manifest.revisions.single { it.authority == "private:p2" }
        File(files.root, "research-history/${researchHash("evidence:${privateRevision.id}")}.evidence").writeText("corrupt private page")
        val authorized = ResearchSessionStore(context).exportTurn(1, setOf(root), setOf("authorized"))
        assertTrue(authorized.isNotEmpty())
        assertTrue(authorized.all { it.authority == "authorized" })
        assertFalse(authorized.any { it.snapshot.notes.contains("PRIVATE SECRET") })
        val direct = ResearchTaskStore(File(files.root, "research-history"))
        assertTrue(runCatching { direct.retainedEvidence(root, setOf("private:p2"), true) }.isFailure)
    }

    @Test fun anExactSparseSubsetKeepsCurrentReviewButChangedCapturedVersionInvalidatesIt() {
        val original = capturedSnapshot()
        val subset = ResearchSnapshot(task, sources = original.sources.take(2), updatedAt = 900)
        val strongest = requireNotNull(richestResearchSnapshot(listOf(subset, original)))
        assertEquals(original, strongest)
        assertEquals("UNAVAILABLE", strongest.currentReviewRecord()?.status)
        val changed = original.copy(sources = original.sources.map { if (it.id == "S203") it.copy(versionId = "new-capture", passage = "The source changed and the original quote is no longer present.") else it })
        assertNull(changed.currentReviewRecord())
        assertNotEquals(original.evidenceRevisionDigest(), changed.evidenceRevisionDigest())
        val packet = ResearchDeliveryPacket.build(changed)
        assertFalse(packet.claims.any { it.sourceId == "S203" })
    }

    @Test fun partialsAndDeliveryReceiptsAreRetainedSeparatelyAndStaleGenerationCannotAcceptAReport() = runBlocking {
        val store = ResearchSessionStore(context)
        accepted(store)
        val old = store.journal("old", 1, true, root, authority = "authorized", ownerScope = "p1")
        old.save(capturedSnapshot())
        val partial = old.retainPartial("EXTRACT", "p1", "{unfinished structured claim")
        assertNotNull(partial)
        val receipt = ResearchDeliveryReceipt(ResearchReportValidity.ACCEPTED, true, "packet", "revision")
        store.resumeGeneration(1, root, "new-writer")
        assertFalse(old.retainDeliveryReceipt(receipt))
        assertNull(old.retainPartial("EXTRACT", "p1", "late private result"))
        val current = store.journal("new", 1, true, root, authority = "authorized", ownerScope = "p1")
        assertTrue(current.retainDeliveryReceipt(receipt))
        val manifest = requireNotNull(store.evidenceManifest(root))
        assertEquals(setOf(partial), manifest.partialCheckpointIds)
        assertEquals(1, manifest.deliveryReceiptIds.size)
        assertEquals(14, current.load(task)?.claims?.size)
        store.deleteChat(1)
        assertFalse(current.retainDeliveryReceipt(receipt))
        assertNull(current.retainPartial("EXTRACT", "p1", "after deletion"))
        val retained = File(files.root, "research-history").listFiles().orEmpty()
        assertEquals(listOf(researchHash("deleted-chat:1") + ".deleted-chat"), retained.map { it.name })
        assertEquals("deleted", retained.single().readText())
        assertNull(ResearchSessionStore(context).acceptedTask(root))
    }

    @Test fun deliveryWrapperCannotEmitAcceptedNoticeOrCompletionWhenCapturedJournalRejectsReceipt() = runBlocking {
        val store = ResearchSessionStore(context)
        accepted(store)
        val journal = store.journal("writer", 1, true, root, authority = "authorized", ownerScope = "p1")
        val passage = "The synthetic panel trial measured stiffness under a specified load; uncertainty remained substantial."
        val source = ResearchSource("S1", "https://example.org/panel", "Synthetic panel trial", "Read", passage, versionId = "v1")
        val packet = ResearchDeliveryPacket.build(ResearchSnapshot(task, sources = listOf(source), complete = false), ResearchReportDetail.BRIEF)
        val report = "Partial coverage. Independent review is not available.\n\n$passage [S1]\n\n## References\n\n[S1] ${source.url}"
        val writer = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta(report))
                store.resumeGeneration(1, root, "superseded-before-publication")
                emit(ProviderEvent.Completed)
            }
        }
        val delivery = ResearchDeliverySession(writer, { packet }, saveReceipt = { receipt ->
            check(journal.retainDeliveryReceipt(receipt)) { "RESEARCH_RECEIPT_REJECTED" }
        }) { _, _ -> writer }
        val events = mutableListOf<ProviderEvent>()
        val failure = runCatching { delivery.streamRound(emptyList(), emptyList()).collect { events += it } }.exceptionOrNull()
        assertEquals("RESEARCH_RECEIPT_REJECTED", failure?.message)
        assertFalse(events.any { it == ProviderEvent.Completed })
        assertFalse(events.filterIsInstance<ProviderEvent.Notice>().any { it.researchDelivery?.accepted == true })
        assertTrue(store.evidenceManifest(root)?.deliveryReceiptIds.orEmpty().isEmpty())
    }
}
