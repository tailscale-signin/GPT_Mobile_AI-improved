package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchPublicCaptureTransferTest {
    private val task = "quasar calibration"
    private val privateMarker = "restricted-discovery-marker"
    private val publicPassage = "Quasar calibration uses observed reference signals. These public measurements report the method and its limitations."

    @Test fun publicRereadCannotCarryEarlierRestrictedMetadataIntoTheDeliveryPacket() {
        val source = publicReread()
        val transferred = transfer(source)
        val shared = transferred.sources.single()
        val packet = ResearchDeliveryPacket.build(transferred)

        assertFalse("Restricted discovery metadata must not enter the destination packet", packet.json().toString().contains(privateMarker))
        assertFalse(transferred.json().toString().contains(privateMarker))
        assertEquals(listOf(shared.id), packet.studies.map { it.id })
        assertTrue(packet.json().toString().contains(publicPassage))
        assertEquals(source.id, shared.id)
        assertEquals(source.url, shared.url)
        assertEquals(source.url, shared.title)
        assertEquals(source.passage, shared.passage)
        assertEquals(source.retrievalReceipt, shared.retrievalReceipt)
        assertEquals(source.retrievedAt, shared.retrievedAt)
        assertEquals(source.depth, shared.depth)
        assertEquals(researchHash(publicPassage), shared.versionId)
        assertEquals(SourceIdentity.canonicalResearchUrl(source.url), shared.canonicalUrl)
        assertTrue(shared.readable)
        assertTrue(shared.hasPublicCaptureProjection())
        assertEquals("PARTIAL_TEXT", shared.retrievalQuality)
        assertEquals("Partially read", shared.status)
        assertFalse(shared.structuredFullText)
        assertNull(shared.captureTruncated)
        assertNull(shared.publicationYear)
        assertNull(shared.isPreprint)
        assertNull(shared.isRetracted)
        assertTrue(shared.bodyChunks.isEmpty())
        assertTrue(shared.engines.isEmpty())
        assertTrue(shared.providerReceipts.isEmpty())
        assertTrue(shared.readByProfiles.isEmpty())
        assertTrue(shared.discoveredByProfiles.isEmpty())
        assertTrue(shared.sectionNames.isEmpty())
        assertTrue(shared.studyDesigns.isEmpty())
        assertEquals("{}", shared.bibliographyJson)
        assertEquals("", shared.accessUrl)
        assertEquals("", shared.population)
        assertEquals("", shared.intervention)
        assertEquals("", shared.outcomes)
        assertFalse(transferred.complete)
        assertTrue(packet.claims.isEmpty())
        assertNull(transferred.currentReviewRecord())
        assertTrue(transferred.notes.any { "unreviewed excerpt" in it && "omitted" in it })
        val restored = requireNotNull(ResearchSnapshot.parse(transferred.json()))
        assertTrue(restored.sources.single().hasPublicCaptureProjection())
        assertEquals(listOf(shared.id), ResearchDeliveryPacket.build(restored).studies.map { it.id })
        val compact = ResearchDeliveryPacket.build(transferred, tokenBudget = 512)
        assertEquals(listOf(shared.id), compact.studies.map { it.id })
        assertTrue(compact.json().toString().contains("archived full text were omitted"))
    }

    @Test fun projectedPassageRelevanceRequiresTheMatchingPublicReceiptUrlAndBody() {
        val transferred = transfer(publicReread())
        val shared = transferred.sources.single()
        assertEquals(1, ResearchDeliveryPacket.build(transferred).studies.size)

        val changed = listOf(
            shared.copy(publicCapture = false),
            shared.copy(retrievalReceipt = "different-read"),
            shared.copy(url = "https://example.org/another-opaque-capture"),
            shared.copy(passage = "$publicPassage Additional public text."),
            shared.copy(versionId = "different-version"),
            shared.copy(fingerprint = "different-fingerprint"),
            shared.copy(metadataProvenance = emptyList())
        )
        changed.forEach { source ->
            assertFalse(source.hasPublicCaptureProjection())
            assertTrue(ResearchDeliveryPacket.build(transferred.copy(sources = listOf(source))).studies.isEmpty())
        }
        val ordinary = shared.copy(metadataProvenance = emptyList(), title = "Quasar calibration measurements")
        assertEquals(1, ResearchDeliveryPacket.build(transferred.copy(sources = listOf(ordinary))).studies.size)
    }

    @Test fun privateMetadataChangesCannotAlterTheProjectedPublicCaptureIdentity() {
        val source = publicReread()
        val changed = source.copy(
            title = "A different restricted title",
            canonicalUrl = "https://restricted.invalid/different-source",
            titleFingerprint = "different-title-fingerprint",
            contentFingerprint = "different-content-fingerprint",
            equivalenceGroup = "different-equivalence-group",
            versionId = "different-private-version",
            bibliographyJson = """{"authors":["different private author"]}"""
        )
        assertEquals(transfer(source).sources, transfer(changed).sources)
    }

    @Test fun unreadPublicLeadsAbstractsAndDuplicateCapturesDoNotBecomeReadEvidence() {
        val source = publicReread()
        val unread = listOf(
            source.copy(status = "Discovered", passage = "", retrievalQuality = "LEAD"),
            source.copy(status = "Abstract", retrievalQuality = "ABSTRACT"),
            source.copy(status = "Blocked", passage = "Sign in to access this article", retrievalQuality = "BLOCKED"),
            source.copy(originId = "S2")
        )
        unread.forEach { original ->
            assertFalse(original.readable)
            val shared = transfer(original).sources.single()
            assertEquals(original.passage, shared.passage)
            assertFalse(shared.readable)
            assertFalse(shared.retrievalQuality == "FULL_TEXT")
            assertFalse(shared.structuredFullText)
        }
    }

    @Test fun sameAuthorityKeepsItsOriginalMetadataAndCompletion() {
        val source = publicReread()
        val original = ResearchSnapshot(task, sources = listOf(source), complete = true)
        val transferred = authorizedResearchTransfer(listOf(ResearchSession("run", 1, original, authority = "same")), "same").single()
        assertSame(original, transferred)
        assertSame(source, transferred.sources.single())
        assertTrue(transferred.complete)
        assertEquals(source.bibliographyJson, transferred.sources.single().bibliographyJson)
        assertEquals(source.bodyChunks, transferred.sources.single().bodyChunks)
    }

    private fun transfer(source: ResearchSource): ResearchSnapshot = authorizedResearchTransfer(
        listOf(ResearchSession("run", 1, ResearchSnapshot(task, sources = listOf(source), complete = true), authority = "worker")),
        "writer"
    ).single()

    private fun publicReread(): ResearchSource {
        val restrictedDiscovery = ResearchSource(
            "S1", "https://example.org/capture/17", "$task $privateMarker",
            depth = 2,
            publishedAt = privateMarker,
            engines = listOf(privateMarker),
            location = privateMarker,
            doi = privateMarker,
            pmid = privateMarker,
            pmcid = privateMarker,
            canonicalUrl = "https://restricted.invalid/$privateMarker",
            host = privateMarker,
            publicationYear = 2025,
            titleFingerprint = privateMarker,
            contentFingerprint = privateMarker,
            equivalenceGroup = privateMarker,
            paperId = privateMarker,
            versionId = privateMarker,
            providerReceipts = listOf(privateMarker),
            reviewStatus = privateMarker,
            publicationType = privateMarker,
            isPreprint = false,
            isRetracted = false,
            contentLicense = privateMarker,
            accessUrl = "https://restricted.invalid/$privateMarker",
            failureReason = privateMarker,
            bibliographyJson = """{"authors":["$privateMarker"],"journal":"$privateMarker"}""",
            studyDesigns = setOf(privateMarker),
            population = privateMarker,
            intervention = privateMarker,
            outcomes = privateMarker,
            metadataProvenance = listOf(privateMarker),
            discoveredByProfiles = setOf(privateMarker),
            readByProfiles = setOf(privateMarker)
        )
        // Reader paths refresh captured content and its receipt with copy(), which
        // leaves earlier discovery metadata on the same source object.
        return restrictedDiscovery.copy(
            status = "Read",
            passage = publicPassage,
            retrievedAt = 1234,
            fingerprint = researchHash(publicPassage),
            retrievalQuality = "FULL_TEXT",
            bodyChunks = listOf("a".repeat(64) + ".chunk"),
            publicCapture = true,
            retrievalReceipt = "public-reader-1",
            structuredFullText = true,
            sectionNames = setOf("Methods", "Results"),
            captureTruncated = false
        )
    }
}
