package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real publication and approved-report projection; provider, clock and encrypted-storage ports are fixtures. */
class GitHubAcceptancePublicationEvidenceTest {
    private val task = "Explain the observed repository check count"
    private val draft = "Private candidate text that must not enter the public report"
    private val repository = "private-owner/private-repository"
    private val now = 1000L

    private data class Fixture(val book: GitHubEvidenceBook, val saved: MutableMap<String, JsonObject>)

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private fun provider(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(*events)
    }

    private fun answer(text: String) = provider(ProviderEvent.TextDelta(text), ProviderEvent.Completed)

    private suspend fun Fixture.read(callId: String, tool: String, action: String, result: String) {
        book.observe(
            ProviderEvent.ToolCall(callId, tool, buildJsonObject { put("action", action) }),
            AgentToolResult(callId, ToolResultContent.Json(json(result)), false)
        )
    }

    private suspend fun fixture(trustedTools: Set<String> = setOf("github_context", "github_ci", "github_write")): Fixture {
        val saved = linkedMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook(
            "private-binding",
            repository,
            "main",
            trustedTools,
            now = { now },
            load = { saved[it] },
            save = { id, value -> saved[id] = value }
        )
        return Fixture(book, saved).also { it.read("private-check-call", "github_context", "checks", """{"checks":1}""") }
    }

    private suspend fun fact(fixture: Fixture): String {
        val id = fixture.book.snapshot().first.entries.first { it.value["call_id"] == JsonPrimitive("private-check-call") }.key
        return """{"claims":[{"kind":"fact","receipt_id":"$id","pointer":"/checks","value":1}]}"""
    }

    private fun session(
        fixture: Fixture,
        published: MutableList<JsonObject>,
        task: String = this.task,
        limit: () -> Int = { 8192 },
        source: AgentProviderSession = answer(draft),
        currentRequest: String = task,
        verifier: suspend (String) -> AgentProviderSession
    ) = GitHubVerifiedSession(
        source, fixture.book, task, limit, verifier, {}, {}, now = { now },
        onPublication = { published += it }, currentRequest = currentRequest
    )

    private fun report(verdict: JsonObject, retained: Boolean = false, extra: Map<String, Any?> = emptyMap()): IssueReport {
        val details = GitHubTaskLifecycle.acceptanceFailureDetails(verdict, retainedReport = retained)
        val capsule = IssueReportIncidentCapsule.fields(
            ReportFeature.CHAT,
            "CHAT_FAILURE",
            "GITHUB_TASK_INCOMPLETE",
            details + extra + mapOf("run_id_hash" to "a".repeat(64))
        )
        return IssueReport(
            "75d84431-c695-4ff4-973b-3a80d63d5993", "incident", "1.0", 1, "abcdef0", "16", 36, "Fixture",
            feature = "chat", errorCode = "GITHUB_TASK_INCOMPLETE", context = IssueReportContext.fromFields("CHAT_FAILURE", capsule, now)
        )
    }

    private fun approvedValues(verdict: JsonObject, retained: Boolean = false): Map<String, JsonElement> {
        val report = report(verdict, retained)
        val payload = report.approvedPayload()
        assertTrue(payload.encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)
        assertFalse(payload.contains(draft))
        assertFalse(payload.contains(repository))
        assertFalse(payload.contains("private-binding"))
        assertFalse(payload.contains("private-check-call"))
        assertFalse(payload.contains("private-provider-error"))
        val restored = IssueReport.json.decodeFromString(IssueReport.serializer(), payload)
        assertEquals(payload, restored.approvedPayload())
        val diagnostics = requireNotNull(restored.context?.diagnostics)
        assertTrue(GitHubAcceptancePublicationEvidence.FEATURE in diagnostics.requiredServiceFeatures())
        return diagnostics.values
    }

    private fun assertObservation(values: Map<String, JsonElement>, source: String, observation: String, draftState: String, receipts: Int) {
        assertEquals(JsonPrimitive(source), values["acceptance_publication_source"])
        assertEquals(JsonPrimitive(observation), values["acceptance_extraction_observation"])
        assertEquals(JsonPrimitive(draftState), values["acceptance_draft_state"])
        assertEquals(JsonPrimitive(receipts), values["acceptance_receipt_snapshot_count"])
    }

    @Test fun observedCatalogCountSurvivesTerminalFailureAndApprovalWhileMissingObservationStaysAbsent() = runBlocking {
        for (observed in listOf(null, 0, 2)) {
            val fixture = fixture()
            val published = mutableListOf<JsonObject>()
            val source = provider(ProviderEvent.RequestConfigured(advertisedToolCount = observed), ProviderEvent.TextDelta(draft), ProviderEvent.Completed)
            val outerTools = (1..5).map { AgentToolDefinition("outer_$it", "Outer catalog entry", json("""{"type":"object"}""")) }
            val events = session(fixture, published, source = source) {
                answer("""{"claims":[{"kind":"not_measured","subject":"The requested source was not read"}]}""")
            }.streamRound(outerTools, emptyList()).toList()
            val terminal = events.last() as ProviderEvent.Failed
            val expected = observed?.let(::JsonPrimitive)
            assertEquals(expected, terminal.failureDetails?.get("acceptance_advertised_tool_count"))
            val verdict = published.single()
            assertEquals(JsonPrimitive(false), verdict["completion_verified"])
            assertEquals(expected, GitHubAcceptancePublicationEvidence.fields(verdict)["acceptance_advertised_tool_count"])
            assertEquals(expected, approvedValues(verdict)["acceptance_advertised_tool_count"])
            assertEquals(expected, approvedValues(verdict, retained = true)["acceptance_advertised_tool_count"])
        }
    }

    @Test fun originalTrustFailuresReachOnlyTheirUnresolvedClaimsWithoutChangingProofBytes() = runBlocking {
        val cases = listOf(
            Triple("private-reader", emptySet(), GitHubAcceptancePublicationEvidence.TrustFailure.TOOL_NOT_IN_AUTHORIZED_CATALOG),
            Triple("private-reader", setOf("private-reader"), GitHubAcceptancePublicationEvidence.TrustFailure.UNRECOGNIZED_EVIDENCE_OPERATION),
            Triple("github_context", setOf("github_context"), GitHubAcceptancePublicationEvidence.TrustFailure.TARGET_OUTSIDE_ACCEPTED_SCOPE)
        )
        for ((tool, extraTools, expected) in cases) {
            val fixture = fixture(setOf("github_context") + extraTools)
            val call = ProviderEvent.ToolCall(
                "private-untrusted-call",
                tool,
                json("""{"action":"checks","owner":"foreign-owner","repo":"foreign-repository"}""")
            )
            val result = AgentToolResult(call.callId, ToolResultContent.Json(json("""{"checks":2}""")), false)
            fixture.book.observe(call, result)
            val receipts = fixture.book.snapshot().first
            val (id, receipt) = receipts.entries.single { it.value["call_id"] == JsonPrimitive(call.callId) }
            val original = fixture.saved.getValue(id)
            assertEquals(expected, GitHubAcceptancePublicationEvidence.recordedTrustFailure(original))
            assertEquals(JsonPrimitive(false), receipt["trusted"])
            assertEquals(JsonObject(original - GitHubAcceptancePublicationEvidence.TRUST_KEY), receipt)
            assertFalse(receipts.values.any { GitHubAcceptancePublicationEvidence.TRUST_KEY in it })
            val proofBytes = receipts.values.sumOf { it.toString().encodeToByteArray().size }
            assertEquals(JsonPrimitive(proofBytes), fixture.book.retentionStatus()["review_projection_bytes"])
            assertFalse(GitHubEvidencePolicy.catalog(receipts).toString().contains(GitHubAcceptancePublicationEvidence.TRUST_KEY))

            val bounded = GitHubEvidenceBook(
                "private-binding",
                repository,
                "main",
                setOf("github_context") + extraTools,
                now = { now },
                retentionLimitBytes = receipt.toString().encodeToByteArray().size
            )
            bounded.observe(call, result)
            assertTrue(bounded.complete())
            assertEquals(receipt, bounded.snapshot().first.getValue(id))

            val published = mutableListOf<JsonObject>()
            val events = session(fixture, published) {
                answer("""{"claims":[{"kind":"fact","receipt_id":"$id","pointer":"/checks","value":2}]}""")
            }.streamRound(emptyList(), emptyList()).toList()
            assertTrue(events.last() is ProviderEvent.Failed)
            val verdict = published.single()
            assertEquals(JsonPrimitive(false), verdict["completion_verified"])
            val values = approvedValues(verdict)
            assertEquals(JsonArray(listOf(JsonPrimitive(expected.name))), values["acceptance_claim_trust_failures"])
            assertEquals(JsonPrimitive("COMPLETED_DRAFT"), values["acceptance_publication_entry"])
            val payload = report(verdict).approvedPayload()
            for (privateValue in listOf("private-untrusted-call", "private-reader", "foreign-owner", "foreign-repository", GitHubAcceptancePublicationEvidence.TRUST_KEY)) {
                assertFalse(privateValue, payload.contains(privateValue))
            }
            assertEquals(JsonPrimitive("UNTRUSTED_RECEIPT"), ((verdict["unresolved_claims"] as JsonArray).single() as JsonObject)["reason"])
            assertEquals(
                GitHubExecutionContinuation.prompt(task, task, receipts, JsonObject(verdict - GitHubAcceptancePublicationEvidence.KEY)),
                GitHubExecutionContinuation.prompt(task, task, receipts, verdict)
            )
            val unrelated = JsonObject(verdict + ("unresolved_claims" to JsonArray(emptyList())))
            assertTrue(fixture.book.claimTrustFailures(unrelated, receipts).isEmpty())
            assertNull(GitHubAcceptancePublicationEvidence.fields(unrelated)["acceptance_claim_trust_failures"])
        }

        // Two finite boundary receipts: diagnostic metadata alone must neither
        // trigger chunking nor overflow the existing maximum original size.
        suspend fun storageFixture(padding: Int): Fixture {
            val saved = linkedMapOf<String, JsonObject>()
            val book = GitHubEvidenceBook(
                "private-binding",
                repository,
                "main",
                emptySet(),
                now = { now },
                load = { saved[it] },
                save = { id, value ->
                    assertTrue(value.toString().encodeToByteArray().size <= 1_400_000)
                    saved[id] = value
                }
            )
            val call = ProviderEvent.ToolCall("private-storage-boundary", "private-reader", json("""{"action":"checks"}"""))
            val result = buildJsonObject {
                put("checks", 2)
                put("padding", "x".repeat(padding))
            }
            book.observe(call, AgentToolResult(call.callId, ToolResultContent.Json(result), false))
            return Fixture(book, saved)
        }
        val calibration = storageFixture(0).saved.values.single()
        val originalBytes = JsonObject(calibration - GitHubAcceptancePublicationEvidence.TRUST_KEY).toString().encodeToByteArray().size
        val resultBytes = requireNotNull((calibration["result_bytes"] as? JsonPrimitive)?.intOrNull)
        for (boundary in listOf(1_400_000, 8 * 1024 * 1024)) {
            var padding = boundary - originalBytes
            padding -= (resultBytes + padding).toString().length - resultBytes.toString().length
            val fixture = storageFixture(padding)
            val (id, manifest) = fixture.saved.entries.single { it.key.startsWith("ev_") }
            assertEquals(boundary > 1_400_000, "original_chunks" in manifest)
            val original = StringBuilder()
            var complete = false
            for (page in 0 until 64) {
                val value = requireNotNull(fixture.book.originalPage(id, page))
                original.append((value.getValue("text") as JsonPrimitive).content)
                if (value["complete"] == JsonPrimitive(true)) {
                    complete = true
                    break
                }
            }
            assertTrue(complete)
            assertEquals(boundary, original.toString().encodeToByteArray().size)
            assertFalse(original.contains(GitHubAcceptancePublicationEvidence.TRUST_KEY))
            assertTrue(fixture.book.complete())
            val restored = GitHubEvidenceBook(
                "private-binding",
                repository,
                "main",
                emptySet(),
                now = { now },
                load = { fixture.saved[it] },
                retainedIds = { fixture.saved.keys.filter { it.startsWith("ev_") } }
            )
            restored.restore()
            assertTrue(restored.complete())
            val unresolved = json("""{"unresolved_claims":[{"receipt_id":"$id","status":"NOT_MEASURED","reason":"UNTRUSTED_RECEIPT"}]}""")
            assertTrue(restored.claimTrustFailures(unresolved, restored.snapshot().first).isEmpty())
        }
    }

    @Test fun restoredTrustFailureKeepsItsOriginalDecisionAndLegacyOrMalformedMarkersStayUnknown() = runBlocking {
        val fixture = fixture(setOf("github_context"))
        fixture.read("private-untrusted-call", "github_ci", "checks", """{"checks":2}""")
        val id = fixture.book.snapshot().first.entries.single { it.value["call_id"] == JsonPrimitive("private-untrusted-call") }.key
        val original = fixture.saved.getValue(id)
        val variants = listOf(
            original to true,
            JsonObject(original - GitHubAcceptancePublicationEvidence.TRUST_KEY) to false,
            JsonObject(original + (GitHubAcceptancePublicationEvidence.TRUST_KEY to json("""{"schema":"foreign","first_failure":"TOOL_NOT_IN_AUTHORIZED_CATALOG"}"""))) to false,
            JsonObject(original + (GitHubAcceptancePublicationEvidence.TRUST_KEY to json("""{"schema":"github.receipt-trust.v1","first_failure":true}"""))) to false,
            JsonObject(original + (GitHubAcceptancePublicationEvidence.TRUST_KEY to json("""{"schema":"github.receipt-trust.v1","first_failure":"TARGET_OUTSIDE_ACCEPTED_SCOPE"}"""))) to false
        )
        for ((savedReceipt, observed) in variants) {
            val saved = fixture.saved.toMutableMap().also { it[id] = savedReceipt }
            val restored = GitHubEvidenceBook(
                "private-binding",
                repository,
                "main",
                setOf("github_context", "github_ci"),
                now = { now },
                load = { saved[it] },
                save = { key, value -> saved[key] = value },
                retainedIds = { saved.keys.filter { it.startsWith("ev_") } }
            )
            restored.restore()
            // The tool is authorized now, but that cannot rewrite its original decision.
            assertEquals(JsonPrimitive(false), restored.snapshot().first.getValue(id)["trusted"])
            val published = mutableListOf<JsonObject>()
            session(Fixture(restored, saved), published) {
                answer("""{"claims":[{"kind":"fact","receipt_id":"$id","pointer":"/checks","value":2}]}""")
            }.streamRound(emptyList(), emptyList()).toList()
            val values = approvedValues(published.single(), retained = true)
            assertEquals(
                if (observed) JsonArray(listOf(JsonPrimitive("TOOL_NOT_IN_AUTHORIZED_CATALOG"))) else null,
                values["acceptance_claim_trust_failures"]
            )
            assertEquals(savedReceipt, saved.getValue(id))
        }
        val contradictory = JsonObject(original + ("trusted" to JsonPrimitive(true)))
        assertNull(GitHubAcceptancePublicationEvidence.recordedTrustFailure(contradictory))
    }

    @Test fun noDraftPublicationEntriesDistinguishActualCallersAndRemainStableOnSavedDelivery() = runBlocking {
        val missingReport = fixture()
        val missingPublished = mutableListOf<JsonObject>()
        session(missingReport, missingPublished, source = provider(), currentRequest = "show the report") {
            error("Missing saved report must not invoke extraction")
        }.streamRound(emptyList(), emptyList()).toList()
        val missing = missingPublished.single()
        val missingValues = approvedValues(missing)
        assertObservation(missingValues, "PARTIAL_FALLBACK", "NO_DRAFT", "ABSENT", 1)
        assertEquals(JsonPrimitive("RETAINED_REPORT_UNAVAILABLE"), missingValues["acceptance_publication_entry"])

        val statusFixture = fixture()
        statusFixture.read("private-unusable-status", "github_context", "status", "{}")
        val statusPublished = mutableListOf<JsonObject>()
        session(statusFixture, statusPublished, task = "status", source = provider()) {
            error("Status snapshot publication must not invoke extraction without a draft")
        }.streamRound(emptyList(), emptyList()).toList()
        val statusValues = approvedValues(statusPublished.single())
        assertEquals(JsonPrimitive("NO_DRAFT"), statusValues["acceptance_extraction_observation"])
        assertEquals(JsonPrimitive("STATUS_SNAPSHOT"), statusValues["acceptance_publication_entry"])

        val delivered = mutableListOf<JsonObject>()
        session(missingReport, delivered, source = provider(), currentRequest = "show the report") {
            error("Saved report delivery must not invoke extraction")
        }.streamRound(emptyList(), emptyList()).toList()
        assertEquals(missing[GitHubAcceptancePublicationEvidence.KEY], delivered.single()[GitHubAcceptancePublicationEvidence.KEY])
        assertEquals(missingValues["acceptance_publication_entry"], approvedValues(delivered.single(), retained = true)["acceptance_publication_entry"])
    }

    @Test fun batchLimitPublicationRetainsActualPlanCountWithoutExtractorDispatch() = runBlocking {
        val fixture = fixture()
        val published = mutableListOf<JsonObject>()
        val task = (1..33).joinToString("\n") { "Requirement $it must remain covered" }
        var limits = 0
        val events = session(fixture, published, task, {
            limits++
            8192
        }) { error("An incomplete batch plan must not dispatch") }
            .streamRound(emptyList(), emptyList()).toList()

        assertEquals(1, limits)
        assertEquals("GITHUB_EXTRACTION_UNAVAILABLE", (events.last() as ProviderEvent.Failed).errorCode)
        val verdict = published.single()
        val values = approvedValues(verdict)
        assertObservation(values, "PARTIAL_FALLBACK", "PLAN_BATCH_LIMIT", "PRESENT", 1)
        assertEquals(JsonPrimitive(9), values["acceptance_extraction_planned_batches"])
        assertNull(values["acceptance_extraction_required_tokens"])
        assertNull(values["acceptance_extraction_input_limit_tokens"])
        assertEquals(JsonPrimitive((verdict["claims"] as JsonArray).size), values["acceptance_claim_count"])
    }

    @Test fun initialInputLimitPublicationRetainsExactGuardOperands() = runBlocking {
        val fixture = fixture()
        val published = mutableListOf<JsonObject>()
        var limits = 0
        val events = session(fixture, published, limit = {
            limits++
            64
        }) { error("The rejected payload must not dispatch") }
            .streamRound(emptyList(), emptyList()).toList()

        assertEquals(1, limits)
        assertEquals("GITHUB_EXTRACTION_UNAVAILABLE", (events.last() as ProviderEvent.Failed).errorCode)
        val values = approvedValues(published.single())
        assertObservation(values, "PARTIAL_FALLBACK", "PLAN_INPUT_LIMIT", "PRESENT", 1)
        assertEquals(JsonPrimitive(1), values["acceptance_extraction_planned_batches"])
        assertEquals(JsonPrimitive(64), values["acceptance_extraction_input_limit_tokens"])
        assertTrue(requireNotNull((values["acceptance_extraction_required_tokens"] as JsonPrimitive).intOrNull) > 64)
    }

    @Test fun changedInputLimitUsesTheFirstActualRejectedPayloadWithoutRedispatch() = runBlocking {
        val control = fixture()
        var actualPayload: String? = null
        val controlPublished = mutableListOf<JsonObject>()
        session(control, controlPublished) { prompt ->
            actualPayload = prompt
            answer(fact(control))
        }
            .streamRound(emptyList(), emptyList()).toList()
        val expectedTokens = TextTokenEstimate.estimate(requireNotNull(actualPayload))
        assertTrue(expectedTokens > 1)

        val fixture = fixture()
        val published = mutableListOf<JsonObject>()
        var limits = 0
        val events = session(fixture, published, limit = { if (++limits == 1) 8192 else 1 }) { error("Capacity changed before dispatch") }
            .streamRound(emptyList(), emptyList()).toList()

        assertEquals(2, limits)
        assertEquals("GITHUB_EXTRACTION_UNAVAILABLE", (events.last() as ProviderEvent.Failed).errorCode)
        val values = approvedValues(published.single())
        assertObservation(values, "PARTIAL_FALLBACK", "PLAN_LIMIT_CHANGED", "PRESENT", 1)
        assertEquals(JsonPrimitive(expectedTokens), values["acceptance_extraction_required_tokens"])
        assertEquals(JsonPrimitive(1), values["acceptance_extraction_input_limit_tokens"])
    }

    @Test fun successfulExtractionRetainsExactObservationAcrossSavedReportDelivery() = runBlocking {
        val fixture = fixture()
        val published = mutableListOf<JsonObject>()
        var extractions = 0
        val events = session(fixture, published) {
            extractions++
            answer(fact(fixture))
        }
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, extractions)
        assertEquals(ProviderEvent.Completed, events.last())
        val verdict = published.single()
        val values = approvedValues(verdict)
        assertObservation(values, "CLAIM_EXTRACTION", "COMPLETED", "PRESENT", 1)
        assertEquals(JsonPrimitive(1), values["acceptance_extraction_planned_batches"])
        assertEquals(JsonPrimitive((verdict["claims"] as JsonArray).size), values["acceptance_claim_count"])
        assertNull(values["acceptance_extraction_required_tokens"])
        val restoredBook = GitHubEvidenceBook(
            "private-binding",
            repository,
            "main",
            setOf("github_context", "github_ci", "github_write"),
            now = { now },
            load = { fixture.saved[it] },
            save = { id, value -> fixture.saved[id] = value },
            retainedIds = { fixture.saved.keys.filter { it.startsWith("ev_") } }
        )
        restoredBook.restore()
        val saved = requireNotNull(restoredBook.retainedAnswer()).second
        assertEquals(verdict[GitHubAcceptancePublicationEvidence.KEY], saved[GitHubAcceptancePublicationEvidence.KEY])
        val delivered = mutableListOf<JsonObject>()
        session(
            Fixture(restoredBook, fixture.saved),
            delivered,
            currentRequest = "show the report",
            source = provider(ProviderEvent.Failed("Saved delivery must not run the provider"))
        ) { error("Saved delivery must not run extraction") }.streamRound(emptyList(), emptyList()).toList()
        val retainedValues = approvedValues(delivered.single(), retained = true)
        for (key in GitHubAcceptancePublicationEvidence.fields) assertEquals(key, values[key], retainedValues[key])
        assertEquals(JsonPrimitive("RETAINED_VERDICT"), retainedValues["acceptance_evidence_scope"])
    }

    @Test fun emptyDraftIsAnObservedEmptyValueRatherThanAnAbsentDraft() = runBlocking {
        val fixture = fixture()
        val published = mutableListOf<JsonObject>()
        session(fixture, published, source = answer("")) { answer(fact(fixture)) }.streamRound(emptyList(), emptyList()).toList()
        assertObservation(approvedValues(published.single()), "CLAIM_EXTRACTION", "COMPLETED", "EMPTY", 1)
    }

    @Test fun extractorFailurePreservesItsActualAttemptAndFailedObservation() = runBlocking {
        val fixture = fixture()
        val published = mutableListOf<JsonObject>()
        var extractions = 0
        val failure = ProviderEvent.Failed(
            "private-provider-error",
            errorCode = "PROVIDER_RATE_LIMITED",
            transportComplete = true,
            invocationId = "private-extractor-attempt",
            failureDetails = buildJsonObject {
                put("failed_stage", "provider_generation")
                put("http_status", 429)
                put("retry_after_ms", 30156)
                put("dispatched", true)
                put("remote_acceptance", "NO")
                put("retryable", true)
                put("requires_reconciliation", false)
            }
        )
        val events = session(fixture, published) {
            extractions++
            provider(failure)
        }.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, extractions)
        val terminal = events.last() as ProviderEvent.Failed
        assertEquals(failure.errorCode, terminal.errorCode)
        assertEquals(failure.invocationId, terminal.invocationId)
        assertEquals(JsonPrimitive("claim_extraction"), terminal.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive(429), terminal.failureDetails?.get("http_status"))
        assertEquals(JsonPrimitive(30156), terminal.failureDetails?.get("retry_after_ms"))
        assertEquals(JsonPrimitive(true), terminal.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive("NO"), terminal.failureDetails?.get("remote_acceptance"))
        assertObservation(approvedValues(published.single()), "CLAIM_EXTRACTION", "FAILED", "PRESENT", 1)
        val savedFields = GitHubTaskLifecycle.acceptanceFailureDetails(published.single(), retainedReport = true)
        assertNull(savedFields["dispatched"])
        assertNull(savedFields["invocation_id"])
    }

    @Test fun missingDraftPreservesPrimaryFailureAndPriorUnknownOutcomePrecedence() = runBlocking {
        for (unknownWrite in listOf(false, true)) {
            val fixture = fixture()
            if (unknownWrite) {
                fixture.book.observe(
                    ProviderEvent.ToolCall("private-uncertain-write", "github_write", JsonObject(emptyMap())),
                    AgentToolResult("private-uncertain-write", ToolResultContent.Text("private write transport detail"), true, errorCode = "TIMEOUT_OUTCOME_UNKNOWN", dispatched = true)
                )
            }
            val published = mutableListOf<JsonObject>()
            val primary = ProviderEvent.Failed(
                "private-provider-error",
                errorCode = "PROVIDER_RATE_LIMITED",
                invocationId = "private-primary-attempt",
                failureDetails = buildJsonObject {
                    put("failed_stage", "provider_generation")
                    put("http_status", 429)
                }
            )
            val events = session(fixture, published) { error("No draft must not start extraction") }.retainedFailureResponse(primary).toList()
            val terminal = events.last() as ProviderEvent.Failed
            assertEquals(if (unknownWrite) "RECONCILIATION_REQUIRED" else primary.errorCode, terminal.errorCode)
            assertEquals(primary.invocationId, terminal.invocationId)
            val verdict = published.single()
            val details = GitHubTaskLifecycle.acceptanceFailureDetails(verdict)
            assertEquals(JsonPrimitive("provider_generation"), details["failed_stage"])
            assertNull(details["reason_code"])
            if (unknownWrite) {
                assertEquals(JsonPrimitive(1), (verdict["execution_ledger"] as JsonObject)["unresolved_remote_outcomes"])
                assertEquals(JsonPrimitive("RECONCILE_REMOTE_OUTCOME"), details["recovery_action"])
            }
            val values = approvedValues(verdict)
            assertObservation(values, "PARTIAL_FALLBACK", "NO_DRAFT", "ABSENT", if (unknownWrite) 2 else 1)
            assertEquals(JsonPrimitive("RETAINED_FAILURE"), values["acceptance_publication_entry"])
            assertNull(values["acceptance_extraction_planned_batches"])
            assertNull(values["acceptance_extraction_required_tokens"])
        }
    }

    @Test fun deterministicStatusCoverageAndCiReportsDoNotInventExtraction() = runBlocking {
        for ((task, expectedSource) in listOf("status" to "STATUS", "test all functionality" to "COVERAGE", "Diagnose CI failures" to "CI_OBSERVATION")) {
            val fixture = fixture()
            val head = "a".repeat(40)
            fixture.read("private-status", "github_context", "status", """{"repository":{"full_name":"$repository"},"commit_sha":"$head","branch":"main"}""")
            if (expectedSource == "CI_OBSERVATION") {
                fixture.read("private-runs", "github_ci", "runs", """{"data":{"workflow_runs":[{"id":7,"run_attempt":1,"head_sha":"$head","status":"completed","conclusion":"success"}],"returned":1,"has_more":false}}""")
            }
            val published = mutableListOf<JsonObject>()
            session(fixture, published, task, limit = { error("A deterministic report must not plan extraction") }) { error("No extraction needed") }
                .streamRound(emptyList(), emptyList()).toList()
            val values = approvedValues(published.single())
            assertObservation(values, expectedSource, "NOT_NEEDED", "ABSENT", if (expectedSource == "CI_OBSERVATION") 3 else 2)
            assertNull(values["acceptance_extraction_planned_batches"])
            assertNull(values["acceptance_extraction_required_tokens"])
            assertNull(values["acceptance_extraction_input_limit_tokens"])
        }
    }

    @Test fun evidenceAdvancedAtTheRestartBoundDiscardsStaleExtractionObservation() = runBlocking {
        val fixture = fixture()
        fixture.read("private-initial-head", "github_context", "status", """{"commit_sha":"${"0".repeat(40)}"}""")
        val published = mutableListOf<JsonObject>()
        var extractions = 0
        val events = session(fixture, published) {
            extractions++
            // Each observed revision advances the real book epoch, so a saved
            // claim from the previous snapshot cannot suppress this restart.
            fixture.read("private-late-$extractions", "github_context", "status", """{"commit_sha":"${extractions.toString().repeat(40)}"}""")
            answer(fact(fixture))
        }.streamRound(emptyList(), emptyList()).toList()

        assertEquals(3, extractions)
        assertTrue(events.last() is ProviderEvent.Failed)
        val verdict = published.single()
        assertEquals(JsonPrimitive(2), verdict["verification_restarts"])
        assertEquals(JsonPrimitive("GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION"), verdict["verification_failure"])
        val values = approvedValues(verdict)
        assertEquals(JsonPrimitive("EVIDENCE_ADVANCED"), values["acceptance_publication_source"])
        assertEquals(JsonPrimitive(5), values["acceptance_receipt_snapshot_count"])
        assertEquals(JsonPrimitive((verdict["claims"] as JsonArray).size), values["acceptance_claim_count"])
        for (key in GitHubAcceptancePublicationEvidence.fields - setOf("acceptance_publication_source", "acceptance_receipt_snapshot_count", "acceptance_claim_count")) {
            assertNull(key, values[key])
        }
    }

    @Test fun cancellationCannotPublishAnExtractionFailureObservation() = runBlocking {
        val fixture = fixture()
        val published = mutableListOf<JsonObject>()
        val cancelled = CancellationException("private cancellation")
        val verifier = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> { throw cancelled }
        }
        val caught = requireNotNull(runCatching { session(fixture, published) { verifier }.streamRound(emptyList(), emptyList()).toList() }.exceptionOrNull())
        assertEquals(cancelled.javaClass, caught.javaClass)
        assertEquals(cancelled.message, caught.message)
        // Coroutine stack-trace recovery may copy the cancellation while keeping
        // the originally thrown exception as its cause.
        assertTrue(generateSequence(caught) { error -> error.cause?.takeUnless { it === error } }.take(16).any { it === cancelled })
        assertTrue(published.isEmpty())
        assertNull(fixture.book.retainedAnswer())
    }

    @Test fun legacyOrMalformedPublicationMarkersDoNotReconstructMissingObservations() = runBlocking {
        val fixture = fixture()
        val published = mutableListOf<JsonObject>()
        session(fixture, published) { answer(fact(fixture)) }.streamRound(emptyList(), emptyList()).toList()
        val actual = published.single()
        val untrusted = json("""{"status":"NOT_MEASURED","reason":"UNTRUSTED_RECEIPT","receipt_id":"private-check-call"}""")
        val observation = actual.getValue(GitHubAcceptancePublicationEvidence.KEY) as JsonObject
        for (source in listOf("STATUS", "COVERAGE", "EVIDENCE_ADVANCED")) {
            val unrelated = JsonObject(
                actual + mapOf(
                    "unresolved_claims" to JsonArray(listOf(untrusted)),
                    GitHubAcceptancePublicationEvidence.KEY to JsonObject(
                        observation + mapOf(
                            "acceptance_publication_source" to JsonPrimitive(source),
                            "acceptance_claim_trust_failures" to JsonArray(listOf(JsonPrimitive("TOOL_NOT_IN_AUTHORIZED_CATALOG")))
                        )
                    )
                )
            )
            assertNull(GitHubAcceptancePublicationEvidence.fields(unrelated)["acceptance_claim_trust_failures"])
            if (source == "EVIDENCE_ADVANCED") assertNull(GitHubAcceptancePublicationEvidence.fields(unrelated)["acceptance_publication_entry"])
        }
        val legacy = JsonObject(actual - GitHubAcceptancePublicationEvidence.KEY)
        val looksLikeNewFailure = JsonObject(legacy + ("failure_code" to JsonPrimitive("PLAN_INPUT_LIMIT")))
        for (verdict in listOf(legacy, looksLikeNewFailure) + listOf(JsonNull, JsonPrimitive("private marker"), json("""{"schema":"unknown"}""")).map {
            JsonObject(actual + (GitHubAcceptancePublicationEvidence.KEY to it))
        }) {
            val fields = GitHubTaskLifecycle.acceptanceFailureDetails(verdict)
            assertTrue(GitHubAcceptancePublicationEvidence.fields.none { it in fields })
            val report = report(verdict)
            assertTrue(GitHubAcceptancePublicationEvidence.fields.none { it in requireNotNull(report.context?.diagnostics).values })
            assertFalse(GitHubAcceptancePublicationEvidence.FEATURE in requireNotNull(report.context?.diagnostics).requiredServiceFeatures())
            report.approvedPayload()
        }
        val noClaims = JsonObject(actual - "claims")
        assertNull(GitHubAcceptanceReportDiagnostics.fields(noClaims, false)["acceptance_claim_count"])
        val (receipts, epoch) = fixture.book.snapshot()
        val absentOrUnrelated: List<JsonElement?> = listOf(
            null, JsonNull, JsonPrimitive(true), JsonPrimitive(1), JsonArray(emptyList()), JsonPrimitive("private extraction state"),
            JsonPrimitive("NOT_NEEDED"), JsonPrimitive("NO_DRAFT"), JsonPrimitive("PLAN_BATCH_LIMIT"), JsonPrimitive("COMPLETED"), JsonPrimitive("FAILED")
        )
        for (observation in absentOrUnrelated) {
            val marker = buildJsonObject {
                (actual.getValue(GitHubAcceptancePublicationEvidence.KEY) as JsonObject).forEach { (key, value) ->
                    if (key != "acceptance_extraction_observation") put(key, value)
                }
                put("acceptance_publication_source", "STATUS")
                observation?.let { put("acceptance_extraction_observation", it) }
                put("acceptance_extraction_required_tokens", 17)
                put("acceptance_extraction_input_limit_tokens", 8)
            }
            val malformed = JsonObject(actual + (GitHubAcceptancePublicationEvidence.KEY to marker))
            assertTrue(fixture.book.retainAnswerIfCurrent("Saved report", malformed, epoch, receipts.keys))
            val restored = GitHubEvidenceBook(
                "private-binding",
                repository,
                "main",
                setOf("github_context", "github_ci", "github_write"),
                now = { now },
                load = { fixture.saved[it] },
                retainedIds = { fixture.saved.keys.filter { it.startsWith("ev_") } }
            )
            restored.restore()
            val values = approvedValues(requireNotNull(restored.retainedAnswer()).second, retained = true)
            assertEquals(JsonPrimitive("STATUS"), values["acceptance_publication_source"])
            assertNull("$observation", values["acceptance_extraction_required_tokens"])
            assertNull("$observation", values["acceptance_extraction_input_limit_tokens"])
        }
    }

    @Test fun publicationEnumsAndNumbersAreStrictlyValidatedBeforeApproval() {
        val enumSamples = mapOf(
            "acceptance_publication_source" to "PARTIAL_FALLBACK",
            "acceptance_extraction_observation" to "PLAN_INPUT_LIMIT",
            "acceptance_draft_state" to "PRESENT",
            "acceptance_publication_entry" to "COMPLETED_DRAFT"
        )
        val countFields = setOf("acceptance_extraction_planned_batches", "acceptance_receipt_snapshot_count", "acceptance_claim_count", "acceptance_advertised_tool_count")
        for (key in GitHubAcceptancePublicationEvidence.fields) {
            val maximum = if (key in countFields) 16_000_000L else Int.MAX_VALUE.toLong()
            val trustList = key == "acceptance_claim_trust_failures"
            val valid = if (trustList) {
                listOf(JsonArray(GitHubAcceptancePublicationEvidence.TrustFailure.entries.map { JsonPrimitive(it.name) }))
            } else {
                enumSamples[key]?.let { listOf(JsonPrimitive(it)) } ?: listOf(JsonPrimitive(0), JsonPrimitive(maximum))
            }
            val invalid = listOf(JsonNull, JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())) +
                if (trustList) {
                    listOf(JsonPrimitive("TOOL_NOT_IN_AUTHORIZED_CATALOG"), json("""{"first_failure":"TOOL_NOT_IN_AUTHORIZED_CATALOG"}"""), JsonArray(listOf(JsonPrimitive("private"))), JsonArray(List(2) { JsonPrimitive("TOOL_NOT_IN_AUTHORIZED_CATALOG") }))
                } else if (key in enumSamples) {
                    listOf(JsonPrimitive("private enum"), JsonPrimitive(0))
                } else {
                    listOf(JsonPrimitive("1"), JsonPrimitive(-1), JsonPrimitive(1.5), JsonPrimitive(maximum + 1))
                }
            for (value in valid) {
                val diagnostics = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "ACCEPTANCE", key to value), now))
                assertEquals(key, value, diagnostics.values[key])
                assertNotNull(diagnostics.validated())
                val feature = if (key in GitHubAcceptancePublicationEvidence.observationFields) GitHubAcceptancePublicationEvidence.OBSERVATION_FEATURE else GitHubAcceptancePublicationEvidence.FEATURE
                assertTrue(feature in diagnostics.requiredServiceFeatures())
            }
            for (value in invalid) {
                val diagnostics = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "ACCEPTANCE", key to value), now))
                assertNull("$key: $value", diagnostics.values[key])
                assertNull(diagnostics.copy(values = diagnostics.values + (key to value)).validated())
            }
        }
    }

    @Test fun actualPublicationOperandsSurviveCrowdedApprovalWithoutPrivateInputs() = runBlocking {
        val fixture = fixture()
        val published = mutableListOf<JsonObject>()
        session(fixture, published, limit = { 64 }) { error("No extraction after rejection") }.streamRound(emptyList(), emptyList()).toList()
        val verdict = published.single()
        val hash = "b".repeat(64)
        val crowded = report(
            verdict,
            extra = mapOf(
                "invocation_id_hash" to hash, "call_id_hash" to hash, "job_id_hash" to hash, "profile_hash" to hash, "model_hash" to hash,
                "unmet_criterion_id_hashes" to List(8) { hash }, "unmet_criterion_reason_hashes" to List(8) { hash },
                "unmet_criterion_categories" to List(8) { "BUILD_REVISION_BINDING" }, "unmet_criterion_states" to List(8) { "NOT_TESTED" },
                "unmet_criterion_blockers" to List(8) { "WORKFLOW_RUN_OR_JOB_REQUIRED" }, "checkpoint_hash" to hash,
                "unmet_criterion_count" to 8, "unmet_criterion_omitted_count" to 0
            )
        ).copy(title = "T".repeat(120), description = "D".repeat(1200))
        val payload = crowded.approvedPayload()
        assertTrue(payload.encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)
        assertFalse(payload.contains(draft))
        assertFalse(payload.contains(repository))
        val restored = IssueReport.json.decodeFromString(IssueReport.serializer(), payload)
        assertEquals(payload, restored.approvedPayload())
        val values = requireNotNull(restored.context?.diagnostics).values
        val original = GitHubAcceptancePublicationEvidence.fields(verdict)
        for ((key, value) in original) assertEquals(key, value, values[key])
        assertEquals(JsonPrimitive("claim_extraction"), values["incident_phase"])
        assertNotNull(values["causal_provenance"])
    }
}
