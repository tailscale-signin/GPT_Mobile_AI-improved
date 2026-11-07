package dev.chungjungsoo.gptmobile.data.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchEvidenceTransferTest {
    @Test fun unrelatedCatalogDifferencesDoNotExcludeReceiptedPublicEvidence() {
        val public = ResearchSource("S1", "https://example.org/study", "Study", "Read", "A captured public passage.", publicCapture = true, retrievalReceipt = "native-read-1")
        val private = public.copy(id = "S2", url = "https://example.org/private", publicCapture = false, passage = "Private passage.")
        val snapshots = authorizedResearchTransfer(listOf(ResearchSession("worker-run", 1, ResearchSnapshot("task", sources = listOf(public, private), claims = listOf(ResearchClaim("private claim", "S2", private.passage))), "turn", "worker-catalog")), "writer-catalog")
        assertEquals(listOf("S1"), snapshots.single().sources.map { it.id })
        assertFalse(snapshots.single().claims.any { it.sourceId == "S2" })
    }

    @Test fun publicLookingUrlWithoutRetrievalProvenanceRemainsRestricted() {
        val source = ResearchSource("S1", "https://example.org/private", "Private", "Read", "Restricted captured material.")
        val result = authorizedResearchTransfer(listOf(ResearchSession("run", 1, ResearchSnapshot("task", sources = listOf(source, source.copy(id = "S2", publicCapture = true, retrievalReceipt = " "))), authority = "account-a")), "account-b")
        assertEquals(0, result.single().sources.size)
    }

    @Test fun publicCaptureDoesNotAuthorizeDerivedPrivateContextOrCompletion() {
        val source = ResearchSource("S1", "https://example.org/study", "Study", "Read", "A captured public passage.", publicCapture = true, retrievalReceipt = "native-read-1")
        val privateContext = "restricted-worker-context"
        val snapshot = ResearchSnapshot(
            task = "Accepted shared task",
            phase = privateContext,
            round = 3,
            searches = 8,
            attempts = 12,
            engineResponses = 7,
            engineFailures = 1,
            sources = listOf(source),
            claims = listOf(ResearchClaim(privateContext, source.id, source.passage, rationale = privateContext)),
            questions = listOf(privateContext),
            questionPlan = listOf(ResearchQuestion("Q1", privateContext)),
            queries = listOf(privateContext),
            notes = listOf(privateContext),
            complete = true,
            toolEvents = listOf(ResearchToolEvent(privateContext, "reader", outcome = "SUCCESS", sourceIds = listOf(source.id))),
            modelEvents = listOf(ResearchModelEvent("worker", "PLAN", "worker-profile", "worker-model", false, "SUCCESS", 1, reason = privateContext)),
            frontierCursors = mapOf("next" to privateContext),
            targetSources = 10,
            taskId = "accepted-task",
            rootWorkId = "accepted-turn",
            originatingUserMessageId = 7,
            taskChatId = 2,
            numericalFactBindingRequired = true
        )
        val transferred = authorizedResearchTransfer(listOf(ResearchSession("run", 2, snapshot, authority = "worker-authority")), "writer-authority").single()
        assertEquals(source.id, transferred.sources.single().id)
        assertEquals(source.passage, transferred.sources.single().passage)
        assertEquals(source.retrievalReceipt, transferred.sources.single().retrievalReceipt)
        assertFalse(transferred.json().toString().contains(privateContext))
        assertFalse(transferred.complete)
        assertTrue(transferred.claims.isEmpty())
        assertTrue(transferred.questionPlan.isEmpty())
        assertTrue(transferred.modelEvents.isEmpty())
        assertTrue(transferred.toolEvents.isEmpty())
        assertTrue(transferred.frontierCursors.isEmpty())
        assertEquals(snapshot.task, transferred.task)
        assertEquals(snapshot.taskId, transferred.taskId)
        assertEquals(snapshot.rootWorkId, transferred.rootWorkId)
        assertEquals(snapshot.taskChatId, transferred.taskChatId)
        assertEquals(snapshot.originatingUserMessageId, transferred.originatingUserMessageId)
        assertEquals(snapshot.attempts, transferred.attempts)
        assertEquals(snapshot.round, transferred.round)
        assertEquals(snapshot.searches, transferred.searches)
        assertEquals(snapshot.engineResponses, transferred.engineResponses)
        assertEquals(snapshot.engineFailures, transferred.engineFailures)
        assertEquals(snapshot.targetSources, transferred.targetSources)
        assertTrue(transferred.numericalFactBindingRequired)
    }

    @Test fun sameAuthorityRetainsItsFullSnapshotWithoutReplanning() {
        val snapshot = ResearchSnapshot(
            task = "Accepted task",
            sources = listOf(ResearchSource("S1", "https://example.org/private", "Private", passage = "Authorized private capture")),
            complete = true,
            queries = listOf("Authorized private query"),
            questions = listOf("Authorized private question"),
            frontierCursors = mapOf("next" to "retained-cursor")
        )
        val transferred = authorizedResearchTransfer(listOf(ResearchSession("run", 1, snapshot, authority = "same-authority")), "same-authority").single()
        assertSame(snapshot, transferred)
    }

    @Test fun noPublicCaptureCannotTransferCompletionOrRunHistory() {
        val snapshot = ResearchSnapshot("Accepted task", complete = true, queries = listOf("restricted query"))
        val transferred = authorizedResearchTransfer(listOf(ResearchSession("run", 1, snapshot, authority = "worker-authority")), null).single()
        assertFalse(transferred.complete)
        assertTrue(transferred.sources.isEmpty())
        assertTrue(transferred.queries.isEmpty())
    }
}
