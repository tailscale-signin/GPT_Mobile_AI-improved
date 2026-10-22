package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail
import dev.chungjungsoo.gptmobile.data.research.ResearchClaim
import dev.chungjungsoo.gptmobile.data.research.ResearchClaimFact
import dev.chungjungsoo.gptmobile.data.research.ResearchQuestion
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.assessResearchCoverage
import dev.chungjungsoo.gptmobile.data.research.researchClaimMatchesCapture
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.data.research.text
import dev.chungjungsoo.gptmobile.data.research.validatedResearchClaims
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchClaimFactBindingTest {
    private val adults = "Among adults, training improved memory by 3 points."
    private val rats = "Among rats, exercise improved locomotion by 3 points."
    private val source = ResearchSource("S1", "https://example.org/memory", "Memory and locomotion trial", "Read", "$adults $rats", versionId = "v1", retrievedAt = 1)

    private fun rawFact(population: String = "adults", outcome: String = "memory", intervention: String = "training", quote: String = adults) = buildJsonObject {
        put("value", "3")
        put("unit", "points")
        put("population", population)
        put("intervention", intervention)
        put("outcome", outcome)
        put("quote", quote)
    }
    private fun row(fact: JsonObject = rawFact(), quote: String = adults) = buildJsonObject {
        put("text", "The captured trial reported memory improvement.")
        put("sourceId", "S1")
        put("questionId", "Q1")
        put("quote", quote)
        put("facts", JsonArray(listOf(fact)))
    }
    private fun claim() = validatedResearchClaims(listOf(row()), listOf(source)).single().copy(verdict = "Supported", relevance = "Relevant", exactQuoteVerified = true, reviewedSourceVersion = "v1")
    private fun facts(): List<ResearchClaimFact> = listOf(
        ResearchClaimFact.bind(rawFact(), source, adults)!!,
        ResearchClaimFact.bind(rawFact("rats", "locomotion", "exercise", rats), source, rats)!!
    )

    @Test fun extractionSuppliesExactCaptureLocatorAndRoundTripsWithoutInventingIndependentReview() {
        val extracted = claim()
        val fact = extracted.facts.single()
        assertEquals("v1", extracted.sourceVersion)
        assertEquals(researchHash(source.passage), extracted.passageHash)
        assertEquals("v1", fact.sourceVersion)
        assertEquals(researchHash(source.passage), fact.passageHash)
        assertEquals(adults, source.passage.substring(fact.quoteStart, fact.quoteEnd))
        assertTrue(fact.locator.contains("characters"))
        val restored = ResearchSnapshot.parse(ResearchSnapshot("Memory locomotion", sources = listOf(source), claims = listOf(extracted)).json())!!
        assertEquals(extracted.facts, restored.claims.single().facts)
        assertFalse(restored.claims.single().independentReview)
    }

    @Test fun extractionCannotJoinSameNumberFromDifferentPopulationAndOutcomeSentences() {
        val mixed = row(rawFact("adults", "locomotion", "", source.passage), source.passage)
        assertTrue(validatedResearchClaims(listOf(mixed), listOf(source)).isEmpty())
        assertTrue(validatedResearchClaims(listOf(row(JsonObject(rawFact() + ("sourceVersion" to JsonPrimitive("v0"))))), listOf(source)).isEmpty())
        assertTrue(validatedResearchClaims(listOf(row(JsonObject(rawFact() + ("passageHash" to JsonPrimitive("0".repeat(64)))))), listOf(source)).isEmpty())
    }

    @Test fun sameValueAndUnitDoNotAllowPopulationOutcomeOrInterventionToBeTransferred() {
        assertTrue(ResearchNumericalGrounding.issues("Among adults, training improved locomotion by 3 points.", listOf(source.passage), facts()).contains("NUMERICAL_OUTCOME_MISMATCH"))
        assertTrue(ResearchNumericalGrounding.issues("Among mice, training improved memory by 3 points.", listOf(source.passage), facts()).contains("NUMERICAL_POPULATION_MISMATCH"))
        assertTrue(ResearchNumericalGrounding.issues("Among adults, exercise improved memory by 3 points.", listOf(source.passage), facts()).contains("NUMERICAL_INTERVENTION_MISMATCH"))
        assertTrue(ResearchNumericalGrounding.issues(adults, listOf(source.passage), facts()).isEmpty())
        assertTrue(ResearchNumericalGrounding.issues(rats, listOf(source.passage), facts()).isEmpty())
    }

    @Test fun unknownRelationAttributesStayUnknownAndLegacyClaimsRemainReadable() {
        val partial = ResearchClaimFact.bind(rawFact(population = "", intervention = ""), source, adults)!!
        val issues = ResearchNumericalGrounding.issues("Memory improved by 3 points among adults.", listOf(source.passage), listOf(partial))
        assertTrue(issues.contains("NUMERICAL_RELATION_METADATA_REQUIRED"))
        val legacy = ResearchClaim("Captured memory observation", source.id, adults, "Supported", exactQuoteVerified = true)
        val restored = ResearchSnapshot.parse(ResearchSnapshot("Memory locomotion", sources = listOf(source), claims = listOf(legacy)).json())!!
        assertTrue(restored.claims.single().facts.isEmpty())
        assertEquals("UNKNOWN", restored.claims.single().json().text("relationMetadata"))
        assertTrue(ResearchNumericalGrounding.issues("Memory improved by 3 points among adults.", listOf(source.passage)).isEmpty())
    }

    @Test fun retainedQualifierCannotDisappearFromANumericalParaphrase() {
        val qualified = "Among adults, training improved memory by 3 points, but follow-up was short."
        val capture = source.copy(passage = qualified)
        val fact = ResearchClaimFact.bind(JsonObject(rawFact(quote = qualified) + ("qualifiers" to JsonArray(listOf(JsonPrimitive("follow-up was short"))))), capture, qualified)!!
        assertTrue(ResearchNumericalGrounding.issues("Memory improved by 3 points among adults receiving training.", listOf(qualified), listOf(fact)).contains("NUMERICAL_QUALIFIER_MISSING"))
        assertTrue(ResearchNumericalGrounding.issues(qualified, listOf(qualified), listOf(fact)).isEmpty())
    }

    @Test fun staleFactVersionCannotRebindEvenWhenTheSameQuoteStillExists() {
        val next = source.copy(versionId = "v2", retrievedAt = 2)
        val accepted = claim()
        assertFalse(researchClaimMatchesCapture(accepted, next))
        assertTrue(validatedResearchClaims(listOf(accepted.json()), listOf(next)).isEmpty())
        val forgedOuterBinding = accepted.copy(sourceVersion = "v2", reviewedSourceVersion = "v2")
        assertFalse(researchClaimMatchesCapture(forgedOuterBinding, next))
        val snapshot = ResearchSnapshot("Memory locomotion", sources = listOf(next), claims = listOf(forgedOuterBinding))
        assertTrue(ResearchDeliveryPacket.build(snapshot).claims.isEmpty())
        assertTrue(ResearchSnapshot.parse(snapshot.json())!!.claims.isEmpty())
    }

    @Test fun deliveryKeepsReviewedCaptureAndAttachesOnlyItsVersionedStudyFields() {
        val next = source.copy(versionId = "v2", retrievedAt = 2, retrievalQuality = "FULL_TEXT")
        fun fields(version: String, value: String) = buildJsonObject {
            put("sourceVersion", version)
            put("passageHash", researchHash(source.passage))
            put("fields", JsonArray(listOf(buildJsonObject { put("value", value) })))
        }
        val snapshot = ResearchSnapshot("Memory locomotion", sources = listOf(next, source), claims = listOf(claim()), questionPlan = listOf(ResearchQuestion("Q1", "What changed?")))
        val fields = mapOf("S1" to fields("v2", "99"), "S1:v1" to fields("v1", "3"))
        val packet = ResearchDeliveryPacket.build(snapshot, studyFields = fields)
        assertEquals("v1", packet.studies.single().versionId)
        assertEquals("v1", packet.studyFieldsFor(source).text("sourceVersion"))
        assertFalse(packet.json().toString().contains("\"value\":\"99\""))
        assertTrue(packet.json().toString().contains("\"facts\""))
        assertTrue(assessResearchCoverage(snapshot.questionPlan, snapshot.claims, snapshot.sources).complete)
        assertTrue(ResearchDeliveryPacket.build(snapshot, studyFields = mapOf("S1" to buildJsonObject { put("value", "99") })).studyFields.isEmpty())
    }

    @Test fun deliveryValidatorActuallyConsumesTheCaptureBoundFacts() {
        val ratClaim = validatedResearchClaims(listOf(row(rawFact("rats", "locomotion", "exercise", rats), rats)), listOf(source)).single().copy(verdict = "Supported", exactQuoteVerified = true)
        val packet = ResearchDeliveryPacket.build(ResearchSnapshot("Memory locomotion", sources = listOf(source), claims = listOf(claim(), ratClaim)), ResearchReportDetail.BRIEF)
        val report = "Partial coverage. Independent review is unavailable.\n\nAmong adults, training improved locomotion by 3 points. [S1]\n\n## References\n\n[S1] ${source.url}"
        assertTrue(ResearchDeliveryValidator.validate(report, packet).issues.contains("NUMERICAL_OUTCOME_MISMATCH"))
    }

    @Test fun freshNumericExtractionCannotBypassBindingsByOmittingFacts() {
        val omittedFacts = JsonObject(row().filterKeys { it != "facts" })
        assertTrue(validatedResearchClaims(listOf(omittedFacts), listOf(source), requireNumericalFacts = true).isEmpty())
        val accepted = validatedResearchClaims(listOf(row()), listOf(source), requireNumericalFacts = true).single()
        assertTrue(accepted.numericalFactBindingRequired)
        assertFalse(researchClaimMatchesCapture(accepted.copy(facts = emptyList()), source))
        assertTrue(validatedResearchClaims(listOf(accepted.copy(facts = emptyList()).json()), listOf(source), bindNewCapture = false).isEmpty())
        assertTrue(ResearchNumericalGrounding.issues("Among adults, locomotion improved by 3 points.", listOf(source.passage), requireRelationBindings = true).contains("NUMERICAL_RELATION_METADATA_REQUIRED"))
        assertTrue(ResearchNumericalGrounding.issues(adults, listOf(source.passage), requireRelationBindings = true).isEmpty())
        val packet = ResearchDeliveryPacket.build(ResearchSnapshot("Memory locomotion", sources = listOf(source), numericalFactBindingRequired = true), ResearchReportDetail.BRIEF)
        val report = "Partial coverage. Independent review is unavailable.\n\nAmong adults, locomotion improved by 3 points. [S1]\n\n## References\n\n[S1] ${source.url}"
        assertTrue(ResearchDeliveryValidator.validate(report, packet).issues.contains("NUMERICAL_RELATION_METADATA_REQUIRED"))
    }

    @Test fun combinedCorpusFreezePreservesNewContractAndRejectsStaleFactBindings() {
        val captured = claim().copy(numericalFactBindingRequired = true)
        val original = ResearchSnapshot("Memory locomotion", sources = listOf(source), claims = listOf(captured), numericalFactBindingRequired = true)
        val corpus = dev.chungjungsoo.gptmobile.data.research.ResearchCorpus.freeze(original)
        assertTrue(corpus.numericalFactBindingRequired)
        assertEquals(captured, corpus.claims.single())
        assertEquals("true", corpus.json().text("numericalFactBindingRequired"))
        val noFacts = dev.chungjungsoo.gptmobile.data.research.ResearchCorpus.freeze(original.copy(claims = listOf(captured.copy(facts = emptyList()))))
        assertTrue(noFacts.claims.isEmpty())
        assertEquals("true", noFacts.json().text("numericalFactBindingRequired"))
        val stale = dev.chungjungsoo.gptmobile.data.research.ResearchCorpus.freeze(original.copy(sources = listOf(source.copy(versionId = "v2"))))
        assertTrue(stale.claims.isEmpty())
    }
}
