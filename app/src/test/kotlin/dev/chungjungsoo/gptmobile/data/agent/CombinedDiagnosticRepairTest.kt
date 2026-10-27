package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.presentation.ui.chat.CombinedContributionQuality
import dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedSynthesisInputs
import dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedSynthesisPrompt
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedDiagnosticRepairTest {
    private fun session(text: String) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
            emit(ProviderEvent.TextDelta(text))
            emit(ProviderEvent.Completed)
        }
    }
    private fun passage(text: String, sources: List<String> = emptyList()) = buildJsonObject {
        put("type", "passage")
        put("text", text)
        put("profiles", JsonArray(emptyList()))
        put("claims", JsonArray(emptyList()))
        put("sources", JsonArray(sources.map(::JsonPrimitive)))
    }.toString() + "\n"
    private fun execution(): CombinedExecutionContext {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("a"), mapOf("a" to "model"), "Rank free model APIs")
        return CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "audit")
    }

    @Test fun endpointExamplesAndDocumentationAreNotEvidenceCitationsButUnknownCitationsFail() {
        val text = "Example base URL: `http://localhost:11434`. See [API documentation](https://docs.example.org/api) and POST https://api.example.org/v1/chat/completions."
        val decoder = CombinedPassageDecoder(emptyList(), CombinedEvidenceLedger(), 10000)
        decoder.accept(passage(text), final = true)
        assertEquals(text, decoder.answer())
        assertTrue(decoder.issues.none { it in COMBINED_FRAMING_FAILURES })
        assertTrue(decoder.issues.contains("UNVERIFIED_API_ENDPOINT_EXAMPLE"))
        assertTrue(decoder.issues.contains("UNVERIFIED_DOCUMENTATION_LINK"))
        val unknown = CombinedPassageDecoder(emptyList(), CombinedEvidenceLedger(), 10000)
        unknown.accept(passage("Claim supported by [1](https://example.org/nonexistent).", listOf("missing-source")), final = true)
        assertTrue(unknown.issues.contains("UNKNOWN_SOURCE_REFERENCE"))
        assertTrue(unknown.issues.contains("UNRESOLVED_CITATION_URL"))
    }

    @Test fun dsmlIsWithheldAtEveryStreamingBoundaryAndNeverBecomesAContributor() = runTest {
        for (markup in listOf("<｜DSML｜tool_calls><｜DSML｜invoke name=\"web_search\">", "<|DSML|invoke name=\"web_search\">")) {
            assertFalse(CombinedContributionQuality.assess("COMPLETED", markup).admitted)
            for (split in 1 until markup.length) {
                val events = flow {
                    emit(ProviderEvent.TextDelta(markup.take(split)))
                    emit(ProviderEvent.TextDelta(markup.drop(split)))
                    emit(ProviderEvent.Completed)
                }.withAssistantTextMarkupGuard().toList()
                assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().isEmpty())
                assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().errorCode == "TOOL_PROTOCOL_INVALID_FRAME")
                assertFalse(events.contains(ProviderEvent.Completed))
            }
        }
    }

    @Test fun nineUsableContributorsSurviveTwoProviderFailuresAndOneCompletedDsmlPayload() {
        val messages = (1..12).map { index ->
            MessageV2(
                platformType = "p$index",
                currentRunId = "p$index",
                content = when (index) {
                    10, 11 -> ""
                    12 -> "Here is my plan. <｜DSML｜tool_calls><｜DSML｜invoke name=\"web_search\">"
                    else -> "Provider $index offers a model API; its current availability needs verification."
                }
            )
        }
        val runs = (1..12).associate { i -> "p$i" to AgentRun("p$i", 1, 1, 2, "p$i", "test", "test", status = if (i in 10..11) "FAILED" else "COMPLETED") }
        val selected = requireNotNull(combinedSynthesisInputs(messages, runs, emptyMap(), emptyMap()))
        assertEquals(9, selected.size)
        assertTrue(selected.none { it.platformUid in setOf("p10", "p11", "p12") })
    }

    @Test fun threeHundredStatementsAreConsolidatedAndUnrelatedClaimsDoNotBecomeRequiredCoverage() {
        val sources = (1..12).map { p -> CombinedModelResponse("p$p", "Profile $p", content = (1..25).joinToString(" ") { "Provider $it offers a free model API for chat applications." } + " Archaeologists discovered ancient pottery near excavation site $p.") }
        val request = "Rank the best free model APIs."
        val rows = CombinedEditorialInventory.select(CombinedClaimSemantics.records(sources), request, CombinedAnswerPolicy.from(request))
        assertEquals(25, rows.size)
        assertTrue(rows.all { it.profileUids.size == 12 })
        assertFalse(rows.any { it.proposition.contains("Archaeologists") })
        assertEquals(25, CombinedContentCoverage(sources, request).units.size)
        val prompt = Json.parseToJsonElement(combinedSynthesisPrompt(request, sources, combined = execution())) as JsonObject
        assertEquals(25, (prompt["claim_inventory"] as JsonArray).size)
        assertFalse(prompt.toString().contains("Archaeologists"))
        assertTrue("Diagnostic metadata must not recreate the duplicated contributor corpus", prompt.toString().length < 80000)
    }

    @Test fun advisoryAttributionFailureKeepsUsableDraftAndDoesNotLaunchAnExpensiveWholeAnswerRepair() = runTest {
        val source = CombinedModelResponse("a", "A", content = "Provider A supports a free model API for chat applications.")
        val wire = passage("Provider A supports a free model API for chat applications. A local endpoint example is http://localhost:11434; current provider terms remain unverified.") +
            """{"type":"decision","claim":"C1.1","disposition":"MERGED","reason":"Combined elsewhere","final_quote":"Text that is absent"}""" + "\n"
        val wrapper = CombinedDeliverySession(session(wire), listOf(source), 10000, validateAccess = {}, structured = true, combined = execution(), originalRequest = "Rank free model APIs") { _, _ -> error("Advisory attribution does not require a rewrite") }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        val report = events.combinedReport()
        assertEquals(ProviderEvent.Completed, events.last())
        assertEquals("COMPLETED_WITH_LIMITS", report.deliveredScopeStatus)
        assertFalse(report.factualAccuracyVerified)
        assertEquals(0, report.repairRounds)
        assertEquals("UNRESOLVED", report.decisions.first().disposition)
        assertTrue("localhost:11434" in events.combinedAnswerText())
    }

    @Test fun malformedDecisionIsReconstructedOnceWithoutLosingCommittedPassage() = runTest {
        val first = """{"type":"passage","seq":1,"id":"P1","text":"Retained paragraph.","claims":[],"contributors":[],"evidence":[]}"""
        val invalid = """{"type":"decision","seq":2,"id":"D1","claim":"C1.1","disposition":"UNRESOLVED","reason":[]} """
        val fixed = """{"type":"decision","seq":2,"id":"D1","claim":"C1.1","disposition":"UNRESOLVED","reason":"Not verified."}"""
        val end = """{"type":"end","seq":3,"id":"END","lastSeq":2}"""
        val protocol = CombinedRecordProtocol(CombinedRecordCheckpoint(snapshotDigest = "snapshot", revisionId = "revision"), setOf("C1.1"), emptySet(), emptySet(), 10000)
        var saved: CombinedRecordCheckpoint? = null
        var attempts = 0
        val recovery = CombinedRecordRecoverySession(session(first + invalid), protocol, 1, validateAccess = {}, save = { saved = it }) { state, context ->
            attempts++
            assertEquals(listOf("P1"), state.records.map { it.id })
            assertTrue(context.contains("INVALID_DELIVERY_RECORD:reason"))
            assertTrue(state.recoveredFragments.single().raw.contains(invalid.trim()))
            session(fixed + end)
        }
        val events = recovery.streamRound(emptyList(), emptyList()).toList()
        assertEquals(ProviderEvent.Completed, events.last())
        assertEquals(1, attempts)
        assertEquals(1, saved!!.recoveryAttempts)
        assertEquals(1, saved!!.malformedRecoveryAttempts)
        assertEquals(listOf("P1", "D1", "END"), saved!!.records.map { it.id })
        assertEquals(1, events.filterIsInstance<ProviderEvent.TextDelta>().count { "Retained paragraph" in it.text })
    }

    @Test fun repairCannotSwapAnUnverifiedClaimForADifferentUnverifiedClaim() = runTest {
        val original = "The experimental treatment improved clinical outcomes in patients."
        val replacement = "The medication reduced mortality in a clinical trial."
        val wrapper = CombinedDeliverySession(session(passage(original)), emptyList(), 10000, validateAccess = {}, structured = true, combined = execution(), revisionId = "original") { _, _ -> session(passage(replacement)) }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        val report = events.combinedReport()
        assertEquals(original, events.combinedAnswerText())
        assertEquals("original", report.revisionId)
        assertEquals(0, report.repairAccepted)
        assertTrue(report.repairRejections.contains("REPLACEMENT_ADDED_VERIFICATION_NEEDS"))
    }

    @Test fun repeatedMalformedRecordsExhaustDurableAllowanceAndRestoreOnlyCommittedPassages() = runTest {
        val first = """{"type":"passage","seq":1,"id":"P1","text":"Keep this answer.","claims":[],"contributors":[],"evidence":[]}"""
        val invalid = """{"type":"decision","seq":2,"id":"D1","claim":"C1.1","disposition":"UNRESOLVED","reason":[]}"""
        fun protocol(checkpoint: CombinedRecordCheckpoint) = CombinedRecordProtocol(checkpoint, setOf("C1.1"), emptySet(), emptySet(), 10000)
        val initial = protocol(CombinedRecordCheckpoint(snapshotDigest = "snapshot", revisionId = "revision"))
        var saved = initial.checkpoint()
        var attempts = 0
        val recovery = CombinedRecordRecoverySession(session(first + invalid), initial, 1, validateAccess = {}, save = { saved = it }) { _, _ ->
            attempts++
            session(invalid)
        }
        val failed = recovery.streamRound(emptyList(), emptyList()).toList()
        assertTrue(failed.last() is ProviderEvent.Failed)
        assertEquals(1, attempts)
        assertEquals(listOf("P1"), saved.records.map { it.id })
        // A crash after saving the reconstruction intent must not reset its allowance.
        val restoring = CombinedRecordRecoverySession(session(""), protocol(saved.copy(failure = null)), 1, restored = true, validateAccess = {}, save = {}) { _, _ -> error("Exhausted allowance must survive restart") }
        val restored = restoring.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, restored.filterIsInstance<ProviderEvent.TextDelta>().size)
        assertTrue(restored.last() is ProviderEvent.Failed)
        assertFalse(restored.contains(ProviderEvent.Completed))
    }

    @Test fun validLongAnswerContinuationsDoNotConsumeMalformedRecordRetryAllowance() = runTest {
        fun part(sequence: Int) = """{"type":"passage","seq":$sequence,"id":"P$sequence","text":"Part $sequence.","claims":[],"contributors":[],"evidence":[]}"""
        val protocol = CombinedRecordProtocol(CombinedRecordCheckpoint(snapshotDigest = "snapshot", revisionId = "long-answer"), emptySet(), emptySet(), emptySet(), 10000)
        val wrapper = CombinedRecordRecoverySession(session(part(1)), protocol, 12, validateAccess = {}, save = {}) { checkpoint, _ ->
            val next = checkpoint.lastSequence + 1
            session(if (next <= 10) part(next) else """{"type":"end","seq":11,"id":"END","lastSeq":10}""")
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals(ProviderEvent.Completed, events.last())
        assertEquals(10, protocol.checkpoint().recoveryAttempts)
        assertEquals(0, protocol.checkpoint().malformedRecoveryAttempts)
        assertEquals(11, protocol.checkpoint().records.size)
    }
}
