package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedSynthesisPrompt
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSemanticRegressionTest {
    private fun source(text: String, uid: String = "one") = CombinedModelResponse(uid, uid, content = text)
    private fun ledger(text: String) = CombinedEvidenceLedger(listOf(CombinedEvidenceRecord("E1", "https://example.org/study", passage = text, retrieval = "READABLE_PASSAGE")))
    private fun context(collection: Int = 25, effort: Int = 50) = CombinedExecutionContext(CombinedRequestedPolicy(settings = ConversationCombinedSettings(collectionBias = collection, effort = effort), participantUids = listOf("one", "two")), rootBudgetId = "root-budget", role = CombinedRunRole.SYNTHESIS)
    private fun passage(text: String, sourceId: String? = null, claims: List<String> = listOf("C1.1")) = buildJsonObject {
        put("type", "passage")
        put("text", text)
        put("profiles", JsonArray(listOf(JsonPrimitive("C1"))))
        put("claims", JsonArray(claims.map(::JsonPrimitive)))
        put("sources", JsonArray(listOfNotNull(sourceId).map(::JsonPrimitive)))
    }.toString() + "\n"
    private fun session(text: String, complete: Boolean = true) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
            assertTrue(tools.isEmpty())
            emit(ProviderEvent.TextDelta(text))
            emit(if (complete) ProviderEvent.Completed else ProviderEvent.Failed("Fixture failure"))
        }
    }

    @Test fun theOriginalExportAndCorrectedEvidenceAreDifferentFixtures() {
        val original = Json.parseToJsonElement(javaClass.getResourceAsStream("/combined_audit/original_export_v1.json")!!.bufferedReader().readText()).jsonObject
        val corrected = Json.parseToJsonElement(javaClass.getResourceAsStream("/combined_audit/corrected_evidence_v1.json")!!.bufferedReader().readText()).jsonObject
        assertEquals("ORIGINAL_EXPORT", original.getValue("fixtureMode").jsonPrimitive.content)
        assertEquals(8, original.getValue("turns").jsonArray.size)
        assertTrue(corrected.getValue("fixtureMode").jsonPrimitive.content.contains("SEPARATE"))
        val joke = original.getValue("turns").jsonArray.single { it.jsonObject.getValue("fixtureId").jsonPrimitive.content == "joke_primary_failure" }.jsonObject
        assertEquals("FAILED", joke.getValue("originalFinal").jsonObject.getValue("runStatus").jsonPrimitive.content)
        assertEquals(5, joke.getValue("contributors").jsonArray.size)
    }

    @Test fun citationCannotReverseTheParkinsonNullFinding() {
        val study = "In the small randomized pilot trial, inhaled hydrogen did not significantly improve clinical outcomes in patients with Parkinson disease."
        val optimistic = "In the randomized pilot trial, inhaled hydrogen significantly improved clinical outcomes in patients with Parkinson disease."
        val assessed = CombinedEvidenceVerifier.assess("claim", optimistic, ledger(study), setOf("E1"))
        assertEquals("CONFLICTING_PASSAGES", assessed.result)
        assertTrue(assessed.material)
        assertFalse(CombinedContentCoverage(listOf(source(study))).check(optimistic).accepted)
    }

    @Test fun drinkingWaterCannotSupportAnInhalationClaim() {
        val oral = "A small randomized pilot trial found that drinking hydrogen-rich water improved clinical outcomes in patients with Parkinson disease."
        val inhaled = "A small randomized pilot trial found that inhaled hydrogen improved clinical outcomes in patients with Parkinson disease."
        val assessed = CombinedEvidenceVerifier.assess("route", inhaled, ledger(oral), setOf("E1"))
        assertEquals("PARTIAL_SUPPORT", assessed.result)
        assertTrue(assessed.issues.any { it.startsWith("INTERVENTION_ROUTE_MISMATCH") })
        assertTrue(assessed.material)
    }

    @Test fun animalMechanismCannotBecomeProvenHumanTreatment() {
        val source = "The mechanism may reduce inflammation in mice."
        val answer = "The treatment caused reduced inflammation in human patients."
        assertEquals(CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(source, answer))
        assertTrue(CombinedClaimSemantics.lostQualifiers(CombinedClaimSemantics.qualifiers(source), CombinedClaimSemantics.qualifiers(answer)).contains("POPULATION_MISMATCH"))
    }

    @Test fun sameProfileContradictionIsScanned() {
        val scan = CombinedConflictDetector.scan(listOf(source("The council approved 12 houses in 1930. The council approved 14 houses in 1930.")))
        assertTrue(scan.candidates.any { it.leftUid == it.rightUid && it.kind == "NUMERIC" })
        assertTrue(scan.complete)
    }

    @Test fun opposedReconciliationClaimsWithTheSamePeopleNeedReview() {
        val scan = CombinedConflictDetector.scan(listOf(source("Hogan and Bubba eventually reconciled after many difficult years. Hogan and Bubba never managed to reconcile before his death.")))
        assertTrue(scan.candidates.any { it.kind == "POLARITY" })
    }

    @Test fun differentBiomarkersAreEntitiesRatherThanCompetingNumbers() {
        val first = "The treatment reduced IL-6 levels in patients."
        val second = "The treatment reduced IL-8 levels in patients."
        assertTrue(CombinedClaimMatcher.fingerprint(first).numbers.isEmpty())
        assertTrue(CombinedConflictDetector.scan(listOf(source(first), source(second, "two"))).candidates.isEmpty())
        assertEquals(CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(first, second))
    }

    @Test fun differentTimesAndUnitsDoNotManufactureContradictions() {
        assertTrue(CombinedConflictDetector.scan(listOf(source("The council approved 12 houses in 1930."), source("The council approved 14 houses in 1940.", "two"))).candidates.isEmpty())
        assertTrue(CombinedConflictDetector.scan(listOf(source("The patient received 12 mg of treatment."), source("The patient received 14 ml of treatment.", "two"))).candidates.isEmpty())
    }

    @Test fun equivalentBeneficialRosPhrasingDoesNotCreateAPolarityConflict() {
        val a = "Hydrogen leaves beneficial ROS intact in human patients."
        val b = "Hydrogen acts without disrupting beneficial ROS in human patients."
        assertFalse(CombinedClaimMatcher.possibleConflict(CombinedClaimMatcher.fingerprint(a), CombinedClaimMatcher.fingerprint(b)))
    }

    @Test fun importantMinorityUncertaintySurvivesEverySourceOrder() {
        val caution = "The therapy may reduce inflammation in human patients."
        val definite = "The therapy reduces inflammation in human patients."
        for (rows in listOf(listOf(source(caution), source(definite, "two")), listOf(source(definite, "two"), source(caution)))) {
            val result = CombinedFinalCheck.check(definite, rows, ledger(caution), CombinedAnswerPolicy())
            assertTrue(result.checks.any { it.id.startsWith("MATERIAL_QUALIFIER_UNRESOLVED") && it.material })
            assertTrue(result.correctionRequired)
        }
    }

    @Test fun mergedRequiresAnActualFinalSpanWithItsQualifier() {
        val caution = "The therapy may reduce inflammation in human patients."
        val definite = "The therapy reduces inflammation in human patients."
        val result = CombinedFinalCheck.check(definite, listOf(source(caution)), CombinedEvidenceLedger(), CombinedAnswerPolicy(), declarations = listOf(CombinedClaimReview("C1.1", "MERGED", "In the closing caveat")))
        assertEquals("UNRESOLVED", result.decisions.single().disposition)
        assertTrue(result.decisions.single().finalSpans.isEmpty())
        assertTrue(result.issues.any { it.startsWith("UNTRUTHFUL_MERGED") })
        val retained = CombinedFinalCheck.check(caution, listOf(source(caution)), CombinedEvidenceLedger(), CombinedAnswerPolicy(), declarations = listOf(CombinedClaimReview("C1.1", "MERGED", "In the sentence", finalQuote = caution)))
        assertEquals("MERGED", retained.decisions.single().disposition)
        assertTrue(retained.decisions.single().finalSpans.isNotEmpty())
    }

    @Test fun onePortugueseEventAllowsATruthfulScopeExclusion() {
        val quake = "The Lisbon earthquake struck Portugal in 1755."
        val battle = "The battle of Aljubarrota took place in Portugal in 1385."
        val result = CombinedFinalCheck.check(quake, listOf(source(quake), source(battle, "two")), CombinedEvidenceLedger(), CombinedAnswerPolicy.from("Tell me one random Portuguese historical event"), declarations = listOf(CombinedClaimReview("C2.1", "OMITTED_SCOPE", "One event requested; Lisbon was selected")))
        assertEquals("OMITTED_SCOPE", result.decisions.single { it.claimId == "C2.1" }.disposition)
        assertTrue(result.decisions.single { it.claimId == "C2.1" }.finalSpans.isEmpty())
        assertTrue(result.checks.none { it.result == "FAIL" })
    }

    @Test fun selectedModelCountAndStyleDoNotChangeTheUserContract() {
        val request = "Exactly 500 words, one historical event, no search, in English"
        val sources = listOf(source("The event was reported in 1755."), source("The event may have affected the city.", "two"))
        for (style in listOf(0, 25, 50, 75, 100)) {
            for (effort in listOf(0, 25, 50, 75, 100)) {
                val envelope = Json.parseToJsonElement(combinedSynthesisPrompt(request, sources, combined = context(style, effort))).jsonObject
                val policy = Json.decodeFromJsonElement(CombinedAnswerPolicy.serializer(), envelope.getValue("answer_policy"))
                assertEquals(500, policy.targetWords)
                assertTrue(policy.exactWords && policy.singleExample && policy.noSearch)
                assertEquals(2, envelope.getValue("contributions").jsonArray.size)
                assertTrue(envelope.getValue("contributions").jsonArray.all { "response" !in it.jsonObject })
                assertTrue(envelope.getValue("merge_selection_policy").jsonObject.getValue("evidence_rule").jsonPrimitive.content.contains("Unchanged"))
            }
        }
    }

    @Test fun duplicateClaimsRetainAllProfilesAndSharedAncestryWithoutRepeatedBodies() {
        val text = "The trial reported no improvement in human patients."
        val rows = listOf(source(text).copy(ancestryIds = setOf("earlier-claim")), source(text, "two").copy(ancestryIds = setOf("earlier-claim")))
        val inventory = CombinedClaimInventory.freeze(rows)
        assertEquals(1, inventory.claims.size)
        assertEquals(setOf("one", "two"), inventory.claims.single().profileUids)
        assertEquals(setOf("earlier-claim"), inventory.claims.single().ancestryIds)
        assertEquals(2, inventory.claims.single().occurrences.size)
        assertTrue(inventory.claims.single().occurrences.all { it.sourceSpan != null })
    }

    @Test fun versionedVisibleWordCountExcludesCitationsButCountsHeadingsAndHyphens() {
        val prose = "## Short heading\n\nDon't over-claim evidence [1](https://example.org).\n\n- One item\n\n```kotlin\nval hidden = 500\n```"
        assertEquals(7, CombinedWordCounter.count(prose))
        assertEquals(CombinedWordCounter.count(prose), visibleWordCount(prose))
        assertEquals(CombinedWordCounter.VERSION, CombinedAnswerPolicy.from("500 words").wordCounterVersion)
        assertTrue(CombinedAnswerPolicy.from("Exactly 500 words").violations("word ".repeat(565)).any { it.startsWith("EXACT_WORD_COUNT_UNMET") })
    }

    @Test fun bulletContractRequiresActualListMarkers() {
        val policy = CombinedAnswerPolicy.from("Give three benefits in bullet points")
        assertTrue(policy.violations("First benefit. Second benefit. Third benefit.").contains("BULLET_LIST_REQUIRED"))
        assertTrue(policy.violations("- First benefit\n- Second benefit\n- Third benefit").isEmpty())
    }

    @Test fun titleAndSnippetNeverEstablishAStudyOutcomeEvenWithAMatchingSentence() {
        val claim = "The trial found no improvement in human patients."
        for (retrieval in listOf("TITLE_ONLY", "SEARCH_SNIPPET", "DISCOVERY_ONLY")) {
            val evidence = CombinedEvidenceLedger(listOf(CombinedEvidenceRecord("E1", "https://example.org/study", passage = claim, retrieval = retrieval)))
            val result = CombinedEvidenceVerifier.assess("claim", claim, evidence, setOf("E1"))
            assertEquals("CITED_UNVERIFIED", result.result)
            assertTrue(result.issues.any { it.startsWith("DISCOVERY_CANNOT_SUPPORT_OUTCOME") })
        }
    }

    @Test fun explicitDoiAndPmidCrosswalkDeduplicatesMirrorsButPreservesVersions() {
        fun event(id: String, body: String) = ToolEvent(id, "run", 1, id, null, null, "read_url", "read_url", "{}", body, "JSON", "COMPLETED", false)
        val contents = listOf(
            """{"url":"https://doi.org/10.1007/s10072-021-05489-4","doi":"10.1007/s10072-021-05489-4","pmid":"12345","content":"A retained study passage with a null finding."}""",
            """{"url":"https://pubmed.ncbi.nlm.nih.gov/12345/","pmid":"12345","content":"A retained abstract of the same study."}""",
            """{"url":"https://pmc.ncbi.nlm.nih.gov/articles/PMC8519836/","doi":"10.1007/s10072-021-05489-4","pmcid":"PMC8519836","version":"accepted","content":"The accepted version of the same work."}"""
        )
        val evidence = CombinedEvidenceLedger.freeze(null, contents.mapIndexed { index, body -> event("e$index", body) }, emptyMap())
        assertEquals(3, evidence.records.size)
        assertEquals(1, evidence.records.map { it.work!!.independenceGroup }.distinct().size)
        assertEquals(2, evidence.records.map { it.work!!.id }.distinct().size)
        assertTrue(evidence.records.all { it.work!!.mirrors.size == 3 })
    }

    @Test fun sameTitlesDoNotCollapseIndependentStudies() {
        val first = CombinedEvidenceRecord("E1", "https://example.org/first", title = "Hydrogen trial")
        val second = CombinedEvidenceRecord("E2", "https://example.org/second", title = "Hydrogen trial")
        val merged = CombinedEvidenceLedger(listOf(first)).mergedWith(CombinedEvidenceLedger(listOf(second)))
        assertEquals(2, merged.records.map { it.work!!.independenceGroup }.distinct().size)
    }

    @Test fun incidentalPmcPathOnAnotherHostIsNotAnNlmIdentity() {
        val external = CombinedEvidenceLedger(listOf(CombinedEvidenceRecord("E1", "https://example.org/articles/PMC8519836"))).mergedWith(CombinedEvidenceLedger())
        assertEquals("", external.records.single().work!!.pmcid)
        assertEquals("CANONICAL_URL_ONLY", external.records.single().work!!.identityMethod)
    }

    @Test fun contradictoryIdentifierCrosswalkDoesNotCollapseTwoWorks() {
        val a = CombinedEvidenceRecord("E1", "https://one.example/study", work = CombinedWorkIdentity("a", "https://one.example/study", doi = "10.1000/first", pmid = "123"))
        val b = CombinedEvidenceRecord("E2", "https://two.example/study", work = CombinedWorkIdentity("b", "https://two.example/study", doi = "10.1000/second", pmid = "123"))
        val merged = CombinedEvidenceLedger(listOf(a)).mergedWith(CombinedEvidenceLedger(listOf(b)))
        assertEquals(2, merged.records.map { it.work!!.independenceGroup }.distinct().size)
        assertTrue(merged.records.all { it.work!!.identityMethod == "CONFLICTING_IDENTIFIERS_UNRESOLVED" })
    }

    @Test fun challengePageWithSuccessfulTransportIsRetainedAsUnreadable() {
        val event = ToolEvent("e", "run", 1, "call", null, null, "read_url", "read_url", """{"url":"https://example.org/study"}""", """{"content":"<html><title>Just a moment...</title>Verify you are human</html>"}""", "text", "COMPLETED", false)
        val ledger = CombinedEvidenceLedger.freeze(null, listOf(event), mapOf("run" to "one"))
        assertFalse(ledger.records.single().readable)
        assertEquals("UNREADABLE_RESPONSE", ledger.records.single().retrieval)
        assertTrue(ledger.failedTools.any { it.startsWith("UNREADABLE_RESPONSE") })
    }

    @Test fun unreadCitationStillProducesAnHonestRevisionBoundReceipt() {
        val text = "The study reported an outcome."
        val ledger = CombinedEvidenceLedger(listOf(CombinedEvidenceRecord("E1", "https://example.org/study", title = "Study")))
        val assessment = CombinedEvidenceVerifier.assess("c", text, ledger, setOf("E1"))
        val receipt = CombinedEvidenceVerifier.receipt(text, "r", "c", CombinedCoverageLink(0, text.length, researchHash(text)), assessment)
        assertEquals("CITED_UNVERIFIED", receipt.result)
        assertTrue(CombinedEvidenceVerifier.validateReceipt(text, "r", ledger, receipt))
    }

    @Test fun receiptCannotTransferToAnotherRevisionOrChangedExcerpt() {
        val text = "The council approved 12 houses in 1930."
        val evidence = ledger(text)
        val assessment = CombinedEvidenceVerifier.assess("c", text, evidence)
        val receipt = CombinedEvidenceVerifier.receipt(text, "r1", "c", CombinedCoverageLink(0, text.length, researchHash(text)), assessment)
        assertTrue(CombinedEvidenceVerifier.validateReceipt(text, "r1", evidence, receipt))
        assertFalse(CombinedEvidenceVerifier.validateReceipt(text, "r2", evidence, receipt))
        assertFalse(CombinedEvidenceVerifier.validateReceipt(text, "r1", ledger("The council approved 14 houses in 1930."), receipt))
        assertFalse(CombinedEvidenceVerifier.validateReceipt(text, "r1", evidence, receipt.copy(result = "FACTUALLY_VERIFIED")))
    }

    @Test fun replacementBuilderContainsWholeDraftAndNoContinuationAuthority() {
        val draft = "Beginning. " + "Detail. ".repeat(5000) + "End."
        val prompt = Json.parseToJsonElement(buildCombinedReplacementPrompt("Exactly 500 words", draft, failedChecks = listOf("EXACT_WORD_COUNT_UNMET"))).jsonObject
        assertEquals("REPLACE_DRAFT", prompt.getValue("operation").jsonPrimitive.content)
        assertEquals(draft, prompt.getValue("complete_candidate_draft").jsonPrimitive.content)
        assertTrue(prompt.getValue("instruction").jsonPrimitive.content.contains("Do not append"))
        assertFalse(prompt.toString().contains("Answer already shown (bounded tail)"))
    }

    @Test fun failedRepairOfKnownMaterialErrorCannotRestoreCompletedStatus() = runTest {
        val evidenceText = "The randomized pilot trial of inhaled hydrogen did not improve clinical outcomes in human patients."
        val incorrect = "The randomized pilot trial of inhaled hydrogen improved clinical outcomes in human patients."
        val rows = listOf(source(incorrect))
        val events = CombinedDeliverySession(session(passage(incorrect, "E1")), rows, 10000, validateAccess = {}, evidence = ledger(evidenceText), structured = true, combined = context(), revisionId = "initial") { _, _ -> session("Unfinished correction", false) }.streamRound(emptyList(), emptyList()).toList()
        val quality = events.combinedReport()
        assertTrue(quality.correctionRequired)
        assertEquals("PARTIAL", quality.deliveredScopeStatus)
        assertFalse(quality.complete || quality.factualAccuracyVerified)
        assertTrue(quality.repairRejections.any { it == "REPLACEMENT_INCOMPLETE" })
    }

    @Test fun checkerEvidenceReachesReplacementAndProgressiveTextIsNotAppendedTwice() = runTest {
        val incorrect = "The randomized pilot trial of inhaled hydrogen improved clinical outcomes in human patients."
        val correct = "The randomized pilot trial of inhaled hydrogen did not improve clinical outcomes in human patients."
        var requestedVerification = false
        var committed: CombinedReplacementCandidate? = null
        val rows = listOf(source(incorrect))
        val events = CombinedDeliverySession(
            session(passage(incorrect)), rows, 10000, validateAccess = {}, structured = true, combined = context(), revisionId = "initial",
            verify = {
                requestedVerification = true
                ledger(correct)
            },
            replace = {
                committed = it
                true
            },
            repairDraft = { request ->
                assertTrue(request.evidence.records.any { it.passage == correct })
                session(passage(correct, "E1", emptyList()))
            }
        ) { _, _ -> error("Legacy repair must not be used") }.streamRound(emptyList(), emptyList()).toList()
        assertTrue(requestedVerification)
        assertTrue(committed != null)
        assertEquals("initial", committed!!.previousRevisionId)
        assertTrue(committed!!.revisionId != "initial")
        assertEquals(incorrect, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        val final = events.filterIsInstance<ProviderEvent.CombinedAnswer>().single()
        assertEquals(committed!!.text, events.combinedAnswerText())
        assertEquals(committed!!.text, final.text)
        assertEquals(1, Regex(Regex.escape(correct)).findAll(final.text).count())
        assertTrue(final.text.contains("[1](https://example.org/study)"))
        assertEquals(committed!!.revisionId, final.report.revisionId)
        assertEquals(researchHash(committed!!.text), final.report.answerDigest)
        assertFalse(events.combinedReport().correctionRequired)
    }

    @Test fun explicitLargeResponseIsNotCappedAtSixteenContinuationSegments() {
        val envelope = """{"original_request":"At least 100000 words","contributions":[]}"""
        assertTrue(CombinedResponseBudget.continuationLimit(envelope, 128, 100000) > 16)
    }
}
