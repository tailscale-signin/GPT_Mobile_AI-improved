package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedScopedClaimsTest {
    private fun source(uid: String, text: String, partial: Boolean = false) = CombinedModelResponse(uid, uid, content = text, attemptId = "attempt-$uid", claimExtractionVersion = 2, contentCompleteness = if (partial) "PARTIAL" else "COMPLETE")
    private fun passage(text: String, claims: List<String>, profiles: List<String>) = buildJsonObject {
        put("type", "passage")
        put("text", text)
        put("claims", JsonArray(claims.map(::JsonPrimitive)))
        put("profiles", JsonArray(profiles.map(::JsonPrimitive)))
        put("sources", JsonArray(emptyList()))
    }.toString()

    @Test fun canonicalAliasesRetainEveryActualOwnerAndIgnoreUnrelatedDeclarations() {
        val fact = "The council approved twelve houses beside the railway."
        val sources = listOf(source("alpha", fact), source("beta", "The village planted apple trees along the road."), source("gamma", fact))
        val claim = CombinedClaimSemantics.records(sources).single { it.profileUids.size == 2 }
        assertEquals(setOf("C1.1", "C3.1"), claim.candidateIds)
        for (id in claim.candidateIds + claim.canonicalId) {
            val decoder = CombinedPassageDecoder(sources, CombinedEvidenceLedger(), 10000)
            decoder.accept(passage(fact, listOf(id), listOf("C1", "C2", "C3")), true)
            assertEquals(setOf("alpha", "gamma"), decoder.passages.single().profileUids)
            assertEquals(setOf("alpha", "gamma"), decoder.passages.single().links.single().profileUids)
        }
    }

    @Test fun canonicalAndOccurrenceIdentitySurviveContributorReordering() {
        val sources = listOf(source("alpha", "The council approved twelve houses near the station."), source("beta", "The council approved twelve houses near the station."))
        val first = CombinedClaimSemantics.records(sources).single()
        val reordered = CombinedClaimSemantics.records(sources.reversed()).single()
        assertEquals(first.canonicalId, reordered.canonicalId)
        assertEquals(first.occurrences.associate { it.profileUid to it.occurrenceId }, reordered.occurrences.associate { it.profileUid to it.occurrenceId })
        assertTrue(first.occurrences.all { it.occurrenceId.isNotBlank() })
    }

    @Test fun qualificationTravelsWithTheClaimAndItsExactRawSpan() {
        val text = "Nicotine may improve attention in adults. However, evidence remains limited in small studies."
        val sources = listOf(source("alpha", text))
        val claim = CombinedClaimSemantics.records(sources).single()
        assertEquals(text, claim.proposition)
        val span = claim.occurrences.single().sourceSpan!!
        assertEquals(text, text.substring(span.startUtf16, span.endUtf16))
        val decoder = CombinedPassageDecoder(sources, CombinedEvidenceLedger(), 10000)
        decoder.accept(passage("Nicotine improves attention in adults.", listOf(claim.id), listOf("C1")), true)
        assertTrue(decoder.passages.single().links.isEmpty())
        val checked = CombinedFinalCheck.check(text, sources, CombinedEvidenceLedger(), CombinedAnswerPolicy.from("Explain nicotine"))
        assertTrue(checked.decisions.any { it.disposition == "INCLUDED" && it.finalSpans.single().textDigest == researchHash(text) })
    }

    @Test fun tableRowsKeepHeadersQuantitiesAndEscapedCellAddresses() {
        val table = "| Topic | Main point | Qualification |\n| --- | --- | --- |\n| Basic \\| extended | Adults received 12 mg orally. | Evidence remains preliminary. |\n| Pilot | Patients received 2 mg orally. | No significant benefit was found. |"
        val units = CombinedSourceClaims.units(table)
        assertEquals(2, units.size)
        assertTrue(units.first().text.contains("Topic: Basic \\| extended"))
        assertEquals(listOf("Topic", "Main point", "Qualification"), units.first().cells.map { it.header })
        assertEquals("Basic \\| extended", units.first().cells.first().let { table.substring(it.startUtf16, it.endUtf16) })
        val records = CombinedClaimSemantics.records(listOf(source("alpha", table)))
        assertEquals(2, records.size)
        assertTrue("mg" in records.first().qualifiers.units)
        assertTrue("ADULT" in records.first().qualifiers.populations)
        assertTrue("ORAL_WATER" in records.first().qualifiers.routes)
        assertEquals("NULL_EFFECT", records.last().qualifiers.outcome)
    }

    @Test fun completeTablesProduceDigestBoundCellLinksAtEveryChunkBoundary() {
        val table = "| Topic | Main point |\n| --- | --- |\n| Rail | The council built twelve houses near the station. |"
        val sources = listOf(source("alpha", table))
        val wire = passage(table, listOf("C1.1"), listOf("C1"))
        for (size in 1..wire.length) {
            val decoder = CombinedPassageDecoder(sources, CombinedEvidenceLedger(), 10000)
            wire.chunked(size).forEach { decoder.accept(it) }
            decoder.accept("", true)
            assertEquals(table, decoder.answer())
            assertEquals(2, decoder.passages.single().links.size)
            assertTrue(decoder.passages.single().links.all { it.matches(table) })
            assertFalse(decoder.passages.single().links.any { table.substring(it.start, it.end).contains("---") })
        }
    }

    @Test fun clippedTailCannotBecomeAClaimButEarlierCompleteClaimSurvives() {
        val text = "The council approved twelve houses near the station. The houses were designed for"
        val claims = CombinedClaimSemantics.records(listOf(source("alpha", text, partial = true)))
        assertEquals(1, claims.size)
        assertEquals("The council approved twelve houses near the station.", claims.single().proposition)
    }

    @Test fun genericOverlapAndGreetingProduceNoMaterialClaimCredit() {
        listOf("improve", "attention", "different term, please", "Hello! How can I help you today?").forEach { text ->
            val sources = listOf(source("alpha", text), source("beta", text))
            assertTrue(CombinedClaimSemantics.records(sources).isEmpty())
            assertTrue(combinedWordingSpans(text, sources).isEmpty())
        }
        assertEquals(0, CombinedContentCoverage(listOf(source("alpha", "Hello!")), "hi").units.size)
    }

    @Test fun typoIsInterpretedOnceAndUnrelatedDrugMaterialIsNotAContribution() {
        val task = CombinedTaskContract.from("benefits of carrets")
        assertEquals("benefits of carrets", task.request)
        assertEquals(mapOf("carrets" to "carrots"), task.interpretedTerms)
        assertTrue(task.subjectMatches("Carrots contain dietary fiber."))
        assertFalse(task.subjectMatches("Cocaine and cigarettes have stimulating effects."))
        assertTrue(task.violations("Carrots contain fiber. Cigarettes stimulate attention.").isNotEmpty())
    }
}
