package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchEvidenceTest {
    @Test fun legacyPartialCoverageAndAbstractsStayConservativeAfterRestore() {
        val partial = ResearchSource("S1", "https://example.org/a", "Partial", "Partially read", "Excerpt", retrievalQuality = "FULL_TEXT")
        val abstract = ResearchSource("S2", "https://example.org/b", "Abstract", "Abstract", "Provider abstract", retrievalQuality = "ABSTRACT")
        val restored = ResearchSnapshot.parse(ResearchSnapshot("Task", sources = listOf(partial, abstract)).json())!!
        assertEquals("PARTIAL_TEXT", restored.sources[0].retrievalQuality)
        assertEquals("Abstract", restored.sources[1].status)
        assertEquals("ABSTRACT", restored.sources[1].retrievalQuality)
        assertFalse(restored.sources[1].readable)
    }

    @Test fun fullGoalSurvivesSnapshotRoundTripIncludingTailConstraints() {
        val goal = "original constraint 東京 😀 ".repeat(1500) + "Final constraint: never publish."
        val original = ResearchSnapshot(goal)
        assertEquals(goal, ResearchSnapshot.parse(original.json())?.task)
    }

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
        assertEquals(300, config.maxPages)
        assertEquals(8, config.maxRounds)
        assertEquals(2, config.linkDepth)
        assertEquals(6, config.concurrency)
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

    @Test fun versionOneSnapshotsMigrateToVersionSixWithoutLosingReadableEvidence() {
        val passage = "A stable passage retained from the old snapshot format."
        val legacy = buildJsonObject {
            put("version", 1)
            put("task", "legacy task")
            put(
                "sources",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "S1")
                            put("url", "https://WWW.Example.org/page?utm_source=search")
                            put("title", "Legacy page")
                            put("status", "Read")
                            put("passage", passage)
                        }
                    )
                }
            )
        }

        val restored = ResearchSnapshot.parse(legacy)!!

        assertTrue(restored.sources.single().readable)
        assertEquals("example.org", restored.sources.single().host)
        assertEquals("https://example.org/page", restored.sources.single().canonicalUrl)
        assertEquals(passage, restored.sources.single().passage)
        assertEquals(0, restored.targetSources)
        assertEquals(6, restored.json()["version"]?.jsonPrimitive?.int)
    }

    @Test fun versionTwoSnapshotsMigrateToVersionSixWithoutLosingEvidence() {
        val source = ResearchSource("S1", "https://example.org/page", "Version two page", "Read", "A readable passage from the version two snapshot.")
        val original = ResearchSnapshot("Version two task", sources = listOf(source), updatedAt = 1234)
        val legacy = buildJsonObject {
            original.json().forEach { (key, value) ->
                if (key != "targetSources") put(key, value)
            }
            put("version", 2)
        }

        val restored = ResearchSnapshot.parse(legacy)!!

        assertEquals(original, restored)
        assertTrue(restored.sources.single().readable)
        assertEquals(0, restored.targetSources)
        assertEquals(6, restored.json()["version"]?.jsonPrimitive?.int)
        assertEquals(restored, ResearchSnapshot.parse(restored.json()))
    }

    @Test fun versionFiveSnapshotsPreserveMaximumResearchTarget() {
        val source = ResearchSource("S1", "https://example.org/page", "Current page", "Read", "A readable passage from the current snapshot.")
        val original = ResearchSnapshot("Maximum research", sources = listOf(source), updatedAt = 1234, targetSources = 300)

        val legacy = JsonObject(original.json() + ("version" to kotlinx.serialization.json.JsonPrimitive(5)))
        val restored = ResearchSnapshot.parse(legacy)!!

        assertEquals(original, restored)
        assertEquals(300, restored.targetSources)
        assertEquals(6, restored.json()["version"]?.jsonPrimitive?.int)
    }

    @Test fun stableSourceIdsAndFailureDetailsSurviveRoundTrip() {
        val source = ResearchSource("S001122334455aabb", "https://example.org/paper", "Paper", status = "Failed", accessUrl = "https://example.org/open.pdf", failureReason = "PAGE_TIMEOUT")
        val event = ResearchToolEvent("query", "reader", outcome = "FAILED", sourceIds = listOf(source.id))
        val saved = ResearchSnapshot("task", sources = listOf(source), toolEvents = listOf(event))
        val restored = ResearchSnapshot.parse(saved.json())!!
        assertEquals(source, restored.sources.single())
        assertEquals(listOf(source.id), restored.toolEvents.single().sourceIds)
    }
}
