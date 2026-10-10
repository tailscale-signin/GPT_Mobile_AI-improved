package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchEvidenceTest {
    @Test fun quotesMustExistInReadPages() {
        val quote = "The device supports exactly two parallel requests."
        val row = buildJsonObject {
            put("sourceId", "S1")
            put("text", "Two requests are supported.")
            put("quote", quote)
        }
        val source = ResearchSource("S1", "https://example.org", "Documentation", "Read", quote)
        assertEquals(1, validatedResearchClaims(listOf(row), listOf(source)).size)
        assertTrue(validatedResearchClaims(listOf(row), listOf(source.copy(status = "Discovered"))).isEmpty())
        assertTrue(validatedResearchClaims(listOf(row), listOf(source.copy(passage = "This source does not contain the requested passage."))).isEmpty())
    }

    @Test fun domainBoundariesAndExclusionsAreEnforced() {
        assertTrue(researchDomainAllowed("https://docs.example.org/a", listOf("example.org"), emptyList()))
        assertFalse(researchDomainAllowed("https://example.org.evil.net/a", listOf("example.org"), emptyList()))
        assertFalse(researchDomainAllowed("https://private.example.org/a", listOf("example.org"), listOf("private.example.org")))
    }

    @Test fun phoneLimitsClampUntrustedBackups() {
        val config = DeepResearchSettings(maxPages = 9999, maxRounds = 99, linkDepth = 99, concurrency = 999).normalized()
        assertEquals(30, config.maxPages)
        assertEquals(3, config.maxRounds)
        assertEquals(2, config.linkDepth)
        assertEquals(3, config.concurrency)
    }

    @Test fun snapshotRoundTripPreservesEvidenceAndProgress() {
        val source = ResearchSource("S1", "https://example.org", "Title", "Partially read", "Exact passage with enough context.", retrievedAt = 1234, engines = listOf("A", "B"))
        val original = ResearchSnapshot("Task", sources = listOf(source), attempts = 2, queries = listOf("query"), claims = listOf(ResearchClaim("Claim", "S1", source.passage, "Insufficient")))
        assertEquals(original, ResearchSnapshot.parse(original.json()))
    }

    @Test fun restoredHistoryRejectsInvalidCitationsAndDuplicateSources() {
        val source = ResearchSource("S1", "https://example.org", "Title", "Read", "The documented request limit is two.")
        val snapshot = ResearchSnapshot(
            "Task",
            attempts = -5,
            sources = listOf(source, source.copy(url = "https://other.org"), source.copy(id = "S2", url = "file:///private/document")),
            claims = listOf(
                ResearchClaim("Supported claim", "S1", source.passage, "Supported"),
                ResearchClaim("Invented", "S1", "This quote never appeared in the source.", "Supported"),
                ResearchClaim("Missing source", "S9", source.passage, "Supported")
            )
        )
        val restored = ResearchSnapshot.parse(snapshot.json())!!
        assertEquals(listOf(source), restored.sources)
        assertEquals(listOf(snapshot.claims.first()), restored.claims)
        assertEquals(0, restored.attempts)
    }

    @Test fun versionOneSnapshotsMigrateToVersionTwoWithoutLosingReadableEvidence() {
        val passage = "A stable passage retained from the old snapshot format."
        val legacy = buildJsonObject {
            put("version", 1)
            put("task", "legacy task")
            put("sources", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("id", "S1")
                    put("url", "https://WWW.Example.org/page?utm_source=search")
                    put("title", "Legacy page")
                    put("status", "Read")
                    put("passage", passage)
                })
            })
        }

        val restored = ResearchSnapshot.parse(legacy)!!

        assertTrue(restored.sources.single().readable)
        assertEquals("example.org", restored.sources.single().host)
        assertEquals("https://example.org/page", restored.sources.single().canonicalUrl)
        assertEquals(2, restored.json()["version"]?.jsonPrimitive?.int)
    }
}
