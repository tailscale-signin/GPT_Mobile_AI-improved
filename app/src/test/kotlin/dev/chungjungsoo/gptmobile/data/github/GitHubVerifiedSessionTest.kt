package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.GitHubTaskRecovery
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubVerifiedSessionTest {
    @Test fun unknownOrUnfinishedExtractorInvocationsDoNotTriggerSchemaRepairAttempts() = runBlocking {
        for (phase in listOf("open", "stream", "collect", "empty", "partial")) {
            var attempts = 0
            var published: JsonObject? = null
            val book = GitHubEvidenceBook("binding", "owner/repo", "main", setOf("github_context"), now = { 1000L })
            book.observe(
                ProviderEvent.ToolCall("saved-read", "github_context", JsonObject(mapOf("action" to JsonPrimitive("status")))),
                AgentToolResult("saved-read", ToolResultContent.Json(JsonObject(mapOf("branch" to JsonPrimitive("main")))), false)
            )
            val gate = GitHubVerifiedSession(
                answer("Private candidate"), book, "Explain the observed repository branch", { 8192 },
                {
                    attempts++
                    if (phase == "open") error("Private exception details must stay private")
                    object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> {
                            if (phase == "stream") error("Private exception details must stay private")
                            return flow {
                                if (phase != "empty") emit(ProviderEvent.TextDelta("Incomplete private extraction"))
                                if (phase == "collect") error("Private exception details must stay private")
                            }
                        }
                    }
                },
                {}, {}, now = { 1000L }, onPublication = { published = it },
                openExecutionContinuation = { error("Unknown extraction outcome must not reopen repository execution") }
            )
            val events = gate.streamRound(emptyList(), emptyList()).toList()
            val terminal = events.last() as ProviderEvent.Failed
            assertEquals(1, attempts)
            assertEquals("GITHUB_EXTRACTION_FAILED", terminal.errorCode)
            assertEquals(JsonPrimitive("GITHUB_EXTRACTION_FAILED"), terminal.failureDetails?.get("reason_code"))
            assertEquals(1, book.snapshot().first.size)
            assertTrue(events.none { it is ProviderEvent.Completed || it is ProviderEvent.ToolCall })
            val diagnostic = requireNotNull(published?.get("verification_failure") as? JsonObject)
            val providerCode = if (phase in setOf("empty", "partial")) "INCOMPLETE_PROVIDER_STREAM" else "PROVIDER_INVOCATION_FAILED"
            assertEquals(JsonPrimitive(providerCode), diagnostic["provider_error_code"])
            assertEquals(JsonPrimitive(false), diagnostic["request_replay_allowed"])
            assertEquals(JsonPrimitive(false), diagnostic["logical_continuation_allowed"])
            assertEquals(JsonPrimitive("UNKNOWN"), diagnostic["remote_acceptance"])
            assertFalse(diagnostic.containsKey("dispatched"))
            assertFalse(diagnostic.containsKey("invocation_id"))
            assertFalse(diagnostic.toString().contains("Private exception details"))
        }
    }

    @Test fun unexpectedExtractionProviderExceptionsNeverBecomeSchemaRepairAttempts() = runBlocking {
        for (synchronous in listOf(false, true)) {
            var attempts = 0
            val error = IllegalStateException("private provider failure with an unknown remote outcome")
            val gate = GitHubVerifiedSession(
                answer("Candidate answer"),
                GitHubEvidenceBook("b", "o/r", "main", emptySet()),
                "Explain the recorded result",
                { 8192 },
                {
                    attempts++
                    if (synchronous) throw error
                    object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                            emit(ProviderEvent.TextDelta("{\"claims\":["))
                            throw error
                        }
                    }
                },
                {},
                {}
            )
            val events = gate.streamRound(emptyList(), emptyList()).toList()
            val failure = events.last() as ProviderEvent.Failed
            assertEquals(1, attempts)
            assertEquals("GITHUB_EXTRACTION_FAILED", failure.errorCode)
            assertFalse(failure.transportComplete)
            assertEquals(JsonPrimitive("claim_extraction"), failure.failureDetails?.get("failed_stage"))
            assertEquals(JsonPrimitive(false), failure.failureDetails?.get("retryable"))
            assertEquals(JsonPrimitive(false), failure.failureDetails?.get("request_replay_allowed"))
            assertEquals(JsonPrimitive(false), failure.failureDetails?.get("logical_continuation_allowed"))
            assertEquals(JsonArray(listOf(JsonPrimitive(IllegalStateException::class.java.name))), failure.failureDetails?.get("cause_types"))
            assertFalse(requireNotNull(failure.failureDetails).containsKey("dispatched"))
            assertEquals(null, failure.invocationId)
            assertFalse(events.any { it is ProviderEvent.Completed })
        }
    }

    @Test fun unfinishedExtractionStreamDoesNotAuthorizeAnotherProviderAttempt() = runBlocking {
        for (mode in listOf("partial", "reported_limit", "oversized", "operation")) {
            var attempts = 0
            val gate = GitHubVerifiedSession(
                answer("Candidate answer"),
                GitHubEvidenceBook("b", "o/r", "main", emptySet()),
                "Explain the recorded result",
                { 8192 },
                {
                    attempts++
                    object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                            emit(ProviderEvent.TextDelta(if (mode == "oversized") "x".repeat(32_769) else "{\"claims\":[]}"))
                            if (mode == "reported_limit") emit(ProviderEvent.Usage(finishReason = "length"))
                            if (mode == "operation") emit(ProviderEvent.ToolCall("unexecuted", "github_write", JsonObject(emptyMap())))
                        }
                    }
                },
                {},
                {}
            )
            val failure = gate.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed
            assertEquals(1, attempts)
            assertEquals("GITHUB_EXTRACTION_FAILED", failure.errorCode)
            assertFalse(failure.transportComplete)
            assertEquals(JsonPrimitive("INCOMPLETE_PROVIDER_STREAM"), failure.failureDetails?.get("provider_error_code"))
            assertEquals(JsonPrimitive(false), failure.failureDetails?.get("request_replay_allowed"))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun extractionDeadlineKeepsCompletedBatchesAndMarksOnlyTheUnfinishedAttempt() = runTest {
        val stored = mutableMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("b", "o/r", "main", emptySet(), now = { 1000 }, load = { stored[it] }, save = { key, value -> stored[key] = value })
        val requests = mutableListOf<Int>()
        var published: JsonObject? = null
        val gate = GitHubVerifiedSession(
            answer("Private candidate"), book,
            "Explain the change\n- Document the source\n- Explain the effect\n- List the remaining work\n- Give the result", { 8192 },
            { prompt ->
                val batch = Json.parseToJsonElement(prompt).jsonObject.getValue("batch").toString().toInt()
                requests += batch
                if (batch == 1) {
                    answer("""{"claims":[{"kind":"not_measured","subject":"Feature acceptance"}]}""")
                } else {
                    object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                            emit(ProviderEvent.TextDelta("Incomplete private extraction"))
                            awaitCancellation()
                        }
                    }
                }
            },
            {}, {}, now = { 1000 }, computationDispatcher = StandardTestDispatcher(testScheduler),
            onPublication = { published = it },
            openExecutionContinuation = { error("An extraction deadline cannot reopen repository execution") }
        )
        val events = gate.streamRound(emptyList(), emptyList()).toList()
        val failure = events.last() as ProviderEvent.Failed
        assertEquals(90_000L, testScheduler.currentTime)
        assertEquals(listOf(1, 2), requests)
        assertEquals("GITHUB_EXTRACTION_TIMEOUT", failure.errorCode)
        assertFalse(failure.transportComplete)
        assertEquals(JsonPrimitive("INSPECT_THE_RECORDED_PROVIDER_FAILURE"), failure.failureDetails?.get("recovery_action"))
        assertEquals(JsonPrimitive("UNKNOWN"), failure.failureDetails?.get("remote_acceptance"))
        for (key in listOf("retryable", "request_replay_allowed", "logical_continuation_allowed")) assertEquals(JsonPrimitive(false), failure.failureDetails?.get(key))
        for (key in listOf("invocation_id", "dispatched", "http_status")) assertFalse(requireNotNull(failure.failureDetails).containsKey(key))
        assertEquals(null, failure.invocationId)
        assertEquals(JsonPrimitive(1), published?.get("extraction_batches_completed"))
        assertEquals(JsonPrimitive(2), published?.get("extraction_batches_total"))
        val checkpoint = requireNotNull(book.retainedClaimExtraction())
        val batches = checkpoint.getValue("batches").jsonArray
        assertEquals(JsonPrimitive(true), checkpoint["pending"])
        assertTrue(batches[0] is JsonObject)
        assertEquals(JsonNull, batches[1])
        assertTrue(events.none { it is ProviderEvent.Completed || it is ProviderEvent.ToolCall })
    }

    @Test fun extractionDeadlineCannotReplaceAnAlreadyRecordedProviderFailure() = runTest {
        var attempts = 0
        val original = ProviderEvent.Failed(
            "Provider rejected the extractor",
            errorCode = "429",
            transportComplete = true,
            invocationId = "recorded-extraction-attempt",
            failureDetails = buildJsonObject {
                put("http_status", 429)
                put("remote_acceptance", "NO")
            }
        )
        val gate = GitHubVerifiedSession(
            answer("Candidate answer"),
            GitHubEvidenceBook("b", "o/r", "main", emptySet()),
            "Explain the recorded result",
            { 8192 },
            {
                attempts++
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                        emit(original)
                        emit(ProviderEvent.Completed)
                        awaitCancellation()
                    }
                }
            },
            {},
            {},
            computationDispatcher = StandardTestDispatcher(testScheduler)
        )
        val failure = gate.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed
        assertEquals(1, attempts)
        assertEquals("429", failure.errorCode)
        assertEquals("recorded-extraction-attempt", failure.invocationId)
        assertEquals(JsonPrimitive(429), failure.failureDetails?.get("http_status"))
        assertEquals(JsonPrimitive("NO"), failure.failureDetails?.get("remote_acceptance"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("automatic_retry_allowed"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun localExtractionDeadlineDoesNotInventAnUnfinishedProviderAttempt() = runTest {
        for (stage in listOf("before_provider", "completed_stream", "completed_checkpoint")) {
            val started = testScheduler.currentTime
            val stored = mutableMapOf<String, JsonObject>()
            var paused = false
            var attempts = 0
            val book = GitHubEvidenceBook(
                "b-$stage",
                "o/r",
                "main",
                emptySet(),
                now = { 1000 },
                load = { stored[it] },
                save = { key, value ->
                    stored[key] = value
                    val batches = value["batches"] as? JsonArray
                    if (!paused &&
                        key.startsWith("claim_extraction_current_") &&
                        (stage == "before_provider" || stage == "completed_checkpoint" && batches?.all { it is JsonObject } == true)
                    ) {
                        paused = true
                        awaitCancellation()
                    }
                }
            )
            val gate = GitHubVerifiedSession(
                answer("Private candidate"), book, "Explain the recorded result", { 8192 },
                {
                    attempts++
                    object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                            emit(ProviderEvent.TextDelta("""{"claims":[{"kind":"not_measured","subject":"Feature acceptance"}]}"""))
                            emit(ProviderEvent.Completed)
                            if (stage == "completed_stream") awaitCancellation()
                        }
                    }
                },
                {}, {}, now = { 1000 }, computationDispatcher = StandardTestDispatcher(testScheduler)
            )
            val failure = gate.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed
            assertEquals(stage, 90_000L, testScheduler.currentTime - started)
            assertEquals(if (stage == "before_provider") 0 else 1, attempts)
            assertEquals("GITHUB_EXTRACTION_TIMEOUT", failure.errorCode)
            assertTrue(failure.transportComplete)
            assertEquals(JsonPrimitive("RESUME_UNFINISHED_EXTRACTION_BATCHES"), failure.failureDetails?.get("recovery_action"))
            assertEquals(null, failure.invocationId)
            for (key in listOf("invocation_id", "dispatched", "provider_error_code", "remote_acceptance")) assertFalse(stage, requireNotNull(failure.failureDetails).containsKey(key))
            if (stage == "completed_checkpoint") {
                val retained = requireNotNull(book.retainedClaimExtraction()).getValue("batches").jsonArray
                assertTrue(retained.all { it is JsonObject })
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun callerCancellationIsNotReclassifiedAsAnExtractionDeadline() = runTest {
        var attempts = 0
        var published: JsonObject? = null
        val events = mutableListOf<ProviderEvent>()
        val gate = GitHubVerifiedSession(
            answer("Private candidate"), GitHubEvidenceBook("b", "o/r", "main", emptySet(), now = { 1000 }), "Explain the recorded result", { 8192 },
            {
                attempts++
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> { awaitCancellation() }
                }
            },
            {}, {}, now = { 1000 }, computationDispatcher = StandardTestDispatcher(testScheduler), onPublication = { published = it }
        )
        val collection = async { gate.streamRound(emptyList(), emptyList()).collect { events += it } }
        testScheduler.runCurrent()
        assertEquals(1, attempts)
        collection.cancel(CancellationException("The owner cancelled this request"))
        assertTrue(runCatching { collection.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(null, published)
        assertTrue(events.none { it is ProviderEvent.Failed || it is ProviderEvent.Completed })
    }

    @Test fun completedMalformedExtractionKeepsItsBoundedSchemaRepair() = runBlocking {
        var attempts = 0
        val gate = GitHubVerifiedSession(
            answer("Candidate answer"),
            GitHubEvidenceBook("b", "o/r", "main", emptySet()),
            "Explain the recorded result",
            { 8192 },
            {
                attempts++
                answer("not structured JSON")
            },
            {},
            {}
        )
        val failure = gate.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed
        assertEquals(4, attempts)
        assertEquals("GITHUB_EXTRACTION_INVALID_RESPONSE", failure.errorCode)
        assertTrue(failure.transportComplete)
    }

    @Test fun typedExtractionLimitCannotOverrideAnUncertainOrNonReplayableAttempt() = runBlocking {
        val limits = listOf(
            "transport" to JsonObject(emptyMap()),
            "retry" to buildJsonObject { put("retryable", false) },
            "automatic_retry" to buildJsonObject { put("automatic_retry_allowed", false) },
            "replay" to buildJsonObject { put("request_replay_allowed", false) },
            "continuation" to buildJsonObject { put("logical_continuation_allowed", false) },
            "reconciliation" to buildJsonObject { put("requires_reconciliation", true) },
            "acceptance" to buildJsonObject { put("remote_acceptance", "UNKNOWN") }
        )
        for ((kind, details) in limits) {
            var attempts = 0
            val gate = GitHubVerifiedSession(
                answer("Candidate answer"),
                GitHubEvidenceBook("b", "o/r", "main", emptySet()),
                "Explain the recorded result",
                { 8192 },
                {
                    attempts++
                    object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(
                            ProviderEvent.Failed("A limit with retained attempt restrictions", errorCode = "GITHUB_EXTRACTION_OUTPUT_LIMIT", transportComplete = kind != "transport", failureDetails = details)
                        )
                    }
                },
                {},
                {}
            )
            val failure = gate.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed
            assertEquals(1, attempts)
            assertEquals("GITHUB_EXTRACTION_OUTPUT_LIMIT", failure.errorCode)
            for ((key, value) in details) assertEquals(value, failure.failureDetails?.get(key))
        }
    }

    @Test fun extractionFailureDeliversOnlyItsOwnProviderAttemptFacts() = runBlocking {
        var attempts = 0
        val providerFailure = ProviderEvent.Failed(
            "private provider detail",
            errorCode = "PROVIDER_RATE_LIMITED",
            transportComplete = true,
            failureDetails = buildJsonObject {
                put("failed_stage", "provider_generation")
                put("http_status", 429)
                put("retry_after_ms", 30156)
                put("dispatched", true)
                put("remote_acceptance", "NO")
                put("retryable", true)
                put("requires_reconciliation", false)
            },
            invocationId = "extraction-attempt-only"
        )
        val gate = GitHubVerifiedSession(
            answer("Candidate answer"),
            GitHubEvidenceBook("b", "o/r", "main", emptySet()),
            "Explain the recorded result",
            { 8192 },
            {
                attempts++
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(providerFailure)
                }
            },
            {},
            {}
        )
        val failure = gate.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed
        assertEquals(1, attempts)
        assertEquals("PROVIDER_RATE_LIMITED", failure.errorCode)
        assertEquals("extraction-attempt-only", failure.invocationId)
        assertEquals(JsonPrimitive("claim_extraction"), failure.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive(429), failure.failureDetails?.get("http_status"))
        assertEquals(JsonPrimitive(30156), failure.failureDetails?.get("retry_after_ms"))
        assertEquals(JsonPrimitive(true), failure.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive("NO"), failure.failureDetails?.get("remote_acceptance"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("requires_reconciliation"))
    }

    @Test fun typedReviewFailuresDeliverReceiptFactsWithoutReopeningExtractionOrPublishingTheCandidate() = runBlocking {
        for (code in listOf("COMPLETION_REPAIR_BUDGET_EXHAUSTED", "CONTEXT_CAPACITY_EXCEEDED")) {
            val stored = linkedMapOf<String, JsonObject>()
            val task = "Inspect repository findings and explain the evidence."
            val candidate = "Private unreviewed candidate must remain available for bounded review recovery."
            val checkpoint = GitHubReviewCheckpoint(task, { stored[it] }, { key, value -> stored[key] = value })
            checkpoint.save(candidate, emptyList(), 8192, "reviewer")
            checkpoint.failed(code)
            val sha = "a".repeat(40)
            val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_context"), now = { 1000L })
            book.observe(
                ProviderEvent.ToolCall("status", "github_context", JsonObject(mapOf("action" to JsonPrimitive("status")))),
                AgentToolResult("status", ToolResultContent.Json(Json.parseToJsonElement("""{"branch":"main","commit_sha":"$sha"}""")), false)
            )
            val failure = ProviderEvent.Failed(
                "Bounded reviewer stopped",
                errorCode = code,
                invocationId = "reviewer",
                transportComplete = true,
                failureDetails = buildJsonObject {
                    put("failed_stage", if (code == "CONTEXT_CAPACITY_EXCEEDED") "provider_admission" else "provider_generation")
                    put("review_stage", "answer_review")
                    put("draft_retained", true)
                    put("automatic_retry_allowed", false)
                    put("user_resumable", true)
                }
            )
            val provider = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(failure)
            }
            var report: JsonObject? = null
            val gate = GitHubVerifiedSession(
                provider, book, task, { 8192 }, { error("Failed review must not dispatch claim extraction") }, {}, {}, now = { 1000L },
                privateReviewCandidate = { candidate }, onPublication = { report = it }, openExecutionContinuation = { error("No tool or generation replay after failed review") }
            )
            val events = gate.streamRound(emptyList(), emptyList()).toList()
            val text = events.filterIsInstance<ProviderEvent.TextDelta>().single()
            assertEquals(OutputOrigin.APPLICATION_REPORT, text.lineage.origin)
            assertTrue(text.text.contains(sha))
            assertFalse(text.text.contains(candidate))
            assertTrue(events.none { it is ProviderEvent.Completed || it is ProviderEvent.ToolCall })
            assertEquals(code, (events.last() as ProviderEvent.Failed).errorCode)
            assertEquals(JsonPrimitive(false), report?.get("completion_verified"))
            assertEquals(JsonPrimitive(true), report?.get("review_candidate_retained"))
            assertEquals(JsonPrimitive("DETERMINISTIC_RECEIPT_PARTIAL"), report?.get("delivery_mode"))
            assertEquals(JsonPrimitive(false), report?.get("additional_verification_model_dispatched"))
            assertEquals(candidate, checkpoint.load()?.draft)
            assertEquals(code, checkpoint.load()?.blockedReason)
        }
    }

    @Test fun reviewOriginPartialDeliveryPreservesUnknownProviderAcceptanceAndTransport() = runBlocking {
        val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_context"), now = { 1000L })
        book.observe(
            ProviderEvent.ToolCall("status", "github_context", JsonObject(mapOf("action" to JsonPrimitive("status")))),
            AgentToolResult("status", ToolResultContent.Json(Json.parseToJsonElement("""{"branch":"main","commit_sha":"${"a".repeat(40)}"}""")), false)
        )
        val failure = ProviderEvent.Failed(
            "Review response was lost",
            errorCode = "GATEWAY_OUTCOME_UNKNOWN",
            invocationId = "reviewer",
            transportComplete = false,
            failureDetails = buildJsonObject {
                put("review_stage", "answer_review")
                put("requires_reconciliation", true)
                put("request_replay_allowed", false)
                put("remote_acceptance", "UNKNOWN")
            }
        )
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(failure)
        }
        val gate = GitHubVerifiedSession(provider, book, "Inspect repository findings and explain the evidence.", { 8192 }, { error("No verification generation while outcome is unknown") }, {}, {}, now = { 1000L })
        val events = gate.streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.any { it is ProviderEvent.TextDelta })
        assertTrue(events.none { it is ProviderEvent.Completed || it is ProviderEvent.ToolCall })
        val terminal = events.last() as ProviderEvent.Failed
        assertEquals(failure.errorCode, terminal.errorCode)
        assertFalse(terminal.transportComplete)
        assertEquals(failure.failureDetails, terminal.failureDetails)
    }

    @Test fun apparentlyCompleteReportRemainsPartialAtTheLiveBoundaryWhenWriteOutcomeIsUnknown() = runBlocking {
        val saved = mutableListOf<JsonObject>()
        var published: JsonObject? = null
        val sha = "a".repeat(40)
        val book = GitHubEvidenceBook("b", "o/r", "feature", setOf("github_context", "github_pr", "github_write"), now = { 1000L }, save = { id, value -> if (id.startsWith("verification_current_")) saved += value })
        book.observe(
            ProviderEvent.ToolCall("lost-write", "github_write", Json.parseToJsonElement("""{"action":"commit","branch":"feature"}""").jsonObject),
            AgentToolResult("lost-write", ToolResultContent.Text("Response lost"), true, errorCode = "TIMEOUT_OUTCOME_UNKNOWN", dispatched = true)
        )
        val sources = listOf(
            Triple("github_context", "status", """{"repository":{"full_name":"o/r"},"branch":"feature","commit_sha":"$sha","latest_workflow":{"head_sha":"$sha","head_branch":"feature","status":"completed","conclusion":"success"}}"""),
            Triple("github_context", "checks", """{"commit_sha":"$sha","checks":0,"statuses":0,"check_details":{"check_runs":[]}}"""),
            Triple("github_pr", "list", """{"pull_requests":[]}"""),
            Triple("github_context", "commits", """{"commit_sha":"$sha","items":[{"sha":"$sha"}]}""")
        )
        for ((tool, action, body) in sources) {
            book.observe(
                ProviderEvent.ToolCall(action, tool, JsonObject(mapOf("action" to JsonPrimitive(action)))),
                AgentToolResult(action, ToolResultContent.Json(JsonObject(mapOf("data" to Json.parseToJsonElement(body)))), false)
            )
        }
        val (receipts, epoch) = book.snapshot()
        assertEquals(JsonPrimitive("COMPLETED"), requireNotNull(GitHubEvidencePolicy.status(receipts, 1000, epoch, "status")).second["task_outcome"])
        val session = GitHubVerifiedSession(answer("unused"), book, "status", { 8192 }, { error("Status is built from receipts") }, {}, {}, now = { 1000L }, onPublication = { published = it })
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.none { it is ProviderEvent.Completed })
        val failure = events.last() as ProviderEvent.Failed
        assertEquals("PARTIAL", failure.taskOutcome)
        assertEquals("RECONCILIATION_REQUIRED", failure.errorCode)
        assertEquals(JsonPrimitive("tool_reconciliation"), failure.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive("RECONCILE_REMOTE_OUTCOME"), failure.failureDetails?.get("recovery_action"))
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("remote outcome unconfirmed"))
        assertEquals(JsonPrimitive("PARTIAL"), published?.get("task_outcome"))
        assertEquals(JsonPrimitive(false), published?.get("completion_verified"))
        assertEquals(JsonPrimitive(true), published?.get("response_delivered"))
        assertEquals(JsonPrimitive(GitHubEvidenceBook.digest(events.filterIsInstance<ProviderEvent.TextDelta>().single().text)), published?.get("response_sha256"))
        assertEquals(JsonPrimitive("PARTIAL"), saved.last()["task_state"])
        assertEquals(JsonPrimitive(true), saved.last()["response_delivered"])
    }

    @Test fun checklistPlanningYieldsToTheComputationDispatcher() = runTest {
        val tools = listOf(AgentToolDefinition("github_context", "Status", JsonObject(emptyMap())))
        val gate = GitHubVerifiedSession(
            answer("unused"), GitHubEvidenceBook("b", "o/r", "main", setOf("github_context")),
            "test all tools", { 8192 }, { error("No model for checklist planning") }, {}, {},
            computationDispatcher = StandardTestDispatcher(testScheduler), authorizedTools = tools
        )
        val result = async(start = CoroutineStart.UNDISPATCHED) { gate.streamRound(tools, emptyList()).toList() }
        assertFalse(result.isCompleted)
        testScheduler.runCurrent()
        assertEquals(JsonPrimitive("status"), result.await().filterIsInstance<ProviderEvent.ToolCall>().single().arguments["action"])
    }

    @Test fun invalidReviewerRecoversReceiptFactsWithoutAnotherExtractionRequest() = runBlocking {
        val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_context"), now = { 1000L })
        val call = ProviderEvent.ToolCall("read", "github_context", Json.parseToJsonElement("""{"action":"checks"}""").jsonObject)
        book.observe(call, AgentToolResult("read", ToolResultContent.Text("""{"checks":3}"""), false))
        val id = book.snapshot().first.keys.single()
        val failure = ProviderEvent.Failed("Reviewer returned invalid JSON", errorCode = "GITHUB_REVIEW_INVALID_VERDICT")
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(failure)
        }
        var extractions = 0
        val session = GitHubVerifiedSession(
            source, book, "Explain the integration issues", { 8192 },
            {
                extractions++
                answer("""{"claims":[{"kind":"fact","receipt_id":"$id","pointer":"/checks","value":3}]}""")
            }, {}, {}, now = { 1000L }, privateReviewCandidate = { "UNVERIFIED: everything is fixed" }
        )
        val events = session.streamRound(emptyList(), emptyList()).toList()
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(text.contains("3"))
        assertFalse(text.contains("everything is fixed"))
        assertEquals(0, extractions)
        assertEquals("PARTIAL", (events.last() as ProviderEvent.Failed).taskOutcome)
        val noEvidence = GitHubVerifiedSession(source, GitHubEvidenceBook("empty", "o/r", "main", emptySet()), "Explain", { 8192 }, { error("No receipts") }, {}, {})
        val noEvidenceEvents = noEvidence.streamRound(emptyList(), emptyList()).toList()
        assertEquals(failure, noEvidenceEvents.last())
        assertTrue(noEvidenceEvents.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("draft remains unverified"))
        assertTrue(noEvidenceEvents.none { it == ProviderEvent.Completed })
    }

    @Test fun assistantFailureQuestionUsesOnlyHistoricalDiagnostics() = runBlocking {
        val book = GitHubEvidenceBook("b", "o/r", "main", emptySet(), latestVerdict = {
            Json.parseToJsonElement("""{"binding":"b","verification_failure":"GITHUB_TASK_INCOMPLETE"}""").jsonObject
        })
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> = error("No unrelated CI investigation")
        }
        for (task in listOf("why are you failing?", "GitHub workspace: o/r\nBranch: main\n\nWhy is your response empty?")) {
            val gate = GitHubVerifiedSession(source, book, task, { 8192 }, { error("No extraction model") }, {}, {})
            val events = gate.streamRound(emptyList(), emptyList()).toList()
            assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("GITHUB_TASK_INCOMPLETE"))
            assertEquals(ProviderEvent.Completed, events.last())
        }
    }

    private fun answer(text: String) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta(text), ProviderEvent.Completed)
    }

    @Test fun greetingsUseNoProviderToolsOrVerification() = runBlocking {
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> = error("No generation for a plain greeting")
        }
        for (task in listOf("hi", "Hello!", " hey ", "GitHub workspace: o/r\nBranch: main\n\nhello")) {
            val gate = GitHubVerifiedSession(source, GitHubEvidenceBook("b", "o/r", "main", emptySet()), task, { 8000 }, { error("No extraction") }, { error("No repository claims") }, {})
            val events = gate.streamRound(listOf(AgentToolDefinition("github_context", "Status", JsonObject(emptyMap()))), emptyList()).toList()
            assertEquals(listOf(ProviderEvent.TextDelta("Hello! How can I help?", OutputLineage(OutputOrigin.APPLICATION_REPORT)), ProviderEvent.Completed), events)
        }
    }

    @Test fun statusAliasesCollectTheSamePinnedFourReads() {
        val tools = listOf("github_context", "github_pr").map { AgentToolDefinition(it, "Read", JsonObject(emptyMap())) }
        for (task in listOf("status", "status of repo", "github status", "Status of the repository?")) {
            assertTrue(GitHubStatusRequest.matches(task))
            val receipts = linkedMapOf<String, JsonObject>()
            val actions = mutableListOf<String>()
            repeat(4) { index ->
                val call = requireNotNull(GitHubStatusRequest.nextCall(tools, receipts, 1000L, task = task))
                actions += call.arguments.getValue("action").toString()
                if (index == 1 || index == 3) assertEquals(JsonPrimitive("a".repeat(40)), call.arguments["commit_sha"])
                receipts[index.toString()] = JsonObject(mapOf("tool" to JsonPrimitive(call.name), "arguments" to call.arguments, "observed_at_ms" to JsonPrimitive(1000L), "trusted" to JsonPrimitive(true), "success" to JsonPrimitive(true), "result" to JsonObject(mapOf("commit_sha" to JsonPrimitive("a".repeat(40))))))
            }
            assertEquals(listOf("\"status\"", "\"checks\"", "\"list\"", "\"commits\""), actions)
            assertEquals(null, GitHubStatusRequest.nextCall(tools, receipts, 1000L, task = task))
            val untrusted = receipts.mapValues { JsonObject(it.value + ("trusted" to JsonPrimitive(false))) }
            assertEquals(JsonPrimitive("status"), GitHubStatusRequest.nextCall(tools, untrusted, 1000L, task = task)?.arguments?.get("action"))
        }
    }

    @Test fun failedContinuationRetainsOriginalCauseAndTaskWithPartialDelivery() = runBlocking {
        val reports = mutableListOf<JsonObject>()
        val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_context"), save = { id, value -> if (id.startsWith("verification_current_")) reports += value })
        book.observe(
            ProviderEvent.ToolCall("checks", "github_context", JsonObject(mapOf("action" to JsonPrimitive("checks")))),
            AgentToolResult("checks", ToolResultContent.Json(JsonObject(mapOf("checks" to JsonPrimitive(1)))), false)
        )
        val gate = GitHubVerifiedSession(answer("unused"), book, "inspect checks and recent changes", { 8192 }, { error("No extraction after failure") }, {}, {})
        val failure = ProviderEvent.Failed("Gateway could not continue after tool result; job=job-7", errorCode = "client_tool_results_mismatch")
        val events = gate.retainedFailureResponse(failure).toList()
        val terminal = events.last() as ProviderEvent.Failed
        assertEquals(failure.message, terminal.message)
        assertEquals(failure.errorCode, terminal.errorCode)
        assertEquals("PARTIAL", terminal.taskOutcome)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains(failure.message))
        val report = reports.last()
        assertEquals(JsonPrimitive(true), report["response_delivered"])
        assertTrue(report.toString().contains("inspect checks and recent changes"))
        assertTrue(report.toString().contains("original_failure"))
    }

    @Test fun absentIntegrationCatalogStopsBeforeProviderDenialCanBeDispatched() = runTest {
        val task = "Test GitHub tools and report."
        val book = GitHubEvidenceBook("b", "o/r", "main", emptySet())
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("No provider request can repair an absent tool catalog")
        }
        val gate = GitHubVerifiedSession(provider, book, task, { 8192 }, { error("No verifier without operation evidence") }, {}, {})
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, emptyList()).toList()
        val visible = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        val terminal = visible.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("BLOCKED", terminal.taskOutcome)
        assertEquals("REQUIRED_TOOL_CAPABILITY_UNAVAILABLE", terminal.errorCode)
        assertEquals(JsonPrimitive(false), terminal.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive(false), terminal.failureDetails?.get("retryable"))
        assertTrue(book.snapshot().first.isEmpty())
        assertTrue(visible.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("Recorded tool receipts: 0; dispatched tool calls: 0"))
        assertFalse(visible.any { it is ProviderEvent.Completed || it is ProviderEvent.ToolCall })
    }

    @Test fun providerDenialRetainsPendingAcceptanceWithoutClaimingPartialVerification() = runTest {
        for (task in listOf("Inspect the repository", "Explain the repository issues")) {
            var rounds = 0
            val savedCheckpoints = mutableListOf<JsonObject>()
            val failure = ProviderEvent.Failed(
                "HTTP 402: provider budget restriction",
                errorCode = "PAYMENT_REQUIRED",
                failureDetails = JsonObject(mapOf("status" to JsonPrimitive(402)))
            )
            val provider = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    rounds++
                    emit(failure)
                }
            }
            val gate = GitHubVerifiedSession(
                provider,
                GitHubEvidenceBook("b", "o/r", "main", emptySet(), save = { id, checkpoint -> if (id.startsWith("verification_current_")) savedCheckpoints += checkpoint }),
                task,
                { 8192 },
                { error("A provider denial must not open a verifier") },
                {},
                {}
            )
            val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, emptyList()).toList()
            val terminal = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.Failed>().single()
            assertEquals(failure, terminal)
            assertEquals(1, rounds)
            assertTrue(savedCheckpoints.isNotEmpty())
            assertTrue(savedCheckpoints.all { it["completion_verified"] == JsonPrimitive(false) && it["response_delivered"] == JsonPrimitive(false) })
            assertEquals(JsonPrimitive("INTERRUPTED"), savedCheckpoints.last()["phase"])
            val visible = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
            val status = visible.filterIsInstance<ProviderEvent.TextDelta>().single().text
            assertTrue(status.contains("Status: BLOCKED"))
            assertTrue(status.contains("PAYMENT_REQUIRED"))
            assertTrue(status.contains("No tool result was delivered"))
            assertFalse(visible.any { it is ProviderEvent.Completed })
        }
    }

    @Test fun integrationRecoveryPreservesAnAlreadyObservedProviderDenial() = runTest {
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("Retained failure delivery must not dispatch a provider request")
        }
        val gate = GitHubVerifiedSession(
            provider,
            GitHubEvidenceBook("b", "o/r", "main", emptySet()),
            "Test GitHub tools and report.",
            { 8192 },
            { error("A provider denial must not open a verifier") },
            {},
            {}
        )
        val failure = ProviderEvent.Failed(
            "HTTP 402: provider budget restriction",
            errorCode = "PAYMENT_REQUIRED",
            failureDetails = JsonObject(mapOf("status" to JsonPrimitive(402))),
            invocationId = "denied-integration-invocation"
        )
        assertEquals(listOf(failure), gate.retainedFailureResponse(failure).toList())
    }

    @Test fun untrustedToolOutputCannotTurnAProviderDenialIntoAVerificationReport() = runBlocking {
        val book = GitHubEvidenceBook("b", "o/r", "main", emptySet())
        book.observe(
            ProviderEvent.ToolCall("untrusted", "web_search", JsonObject(emptyMap())),
            AgentToolResult("untrusted", ToolResultContent.Text("unverified"), false)
        )
        val gate = GitHubVerifiedSession(answer("unused"), book, "Test GitHub tools and report.", { 8192 }, { error("No verifier") }, {}, {})
        val failure = ProviderEvent.Failed("HTTP 401: authentication failed", errorCode = "AUTHENTICATION_ERROR")
        assertEquals(listOf(failure), gate.retainedFailureResponse(failure).toList())
    }

    @Test fun completeStatusSnapshotRequiresAllCurrentObservations() = runBlocking {
        for (prField in listOf("items", "pull_requests")) {
            val sha = "a".repeat(40)
            val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_context", "github_pr"), { 1000 })
            val sources = listOf(
                Triple("github_context", "status", """{"repository":{"full_name":"o/r"},"branch":"main","commit_sha":"$sha","latest_workflow":{"head_sha":"$sha","head_branch":"main","status":"completed","conclusion":"success"}}"""),
                Triple("github_context", "checks", """{"commit_sha":"$sha","checks":0,"statuses":0,"check_details":{"check_runs":[]}}"""),
                Triple("github_pr", "list", """{"$prField":[]}"""),
                Triple("github_context", "commits", """{"commit_sha":"$sha","items":[{"sha":"$sha"}]}""")
            )
            for ((tool, action, body) in sources) {
                val call = ProviderEvent.ToolCall(action, tool, JsonObject(mapOf("action" to JsonPrimitive(action))))
                book.observe(call, AgentToolResult(action, ToolResultContent.Json(JsonObject(mapOf("data" to Json.parseToJsonElement(body)))), false))
            }
            val (receipts, epoch) = book.snapshot()
            val (_, report) = requireNotNull(GitHubEvidencePolicy.status(receipts, 1000, epoch, "status of repo"))
            assertEquals(JsonPrimitive("COMPLETED"), report["task_outcome"])
            assertEquals(JsonPrimitive(true), report["completion_verified"])
            assertEquals(JsonPrimitive(false), report["partial"])
            assertEquals(JsonPrimitive("status of repo"), report["task"])
            val gate = GitHubVerifiedSession(answer("unused"), book, "status", { 8192 }, { error("Status uses receipts directly") }, {}, {}, now = { 1000L })
            val events = gate.streamRound(emptyList(), emptyList()).toList()
            assertTrue(events.last() is ProviderEvent.Completed)
            assertTrue(events.none { it is ProviderEvent.Failed })
            val incomplete = requireNotNull(GitHubEvidencePolicy.status(receipts.filterValues { it["tool"] != JsonPrimitive("github_pr") }, 1000, epoch))
            assertEquals(JsonPrimitive("PARTIAL"), incomplete.second["task_outcome"])
            assertTrue(incomplete.first.contains("Unavailable observations"))
        }
    }

    @Test fun isolatedCheckReceiptDoesNotStopRemainingStatusCollection() = runBlocking {
        val book = GitHubEvidenceBook("binding", "o/r", "main", setOf("github_context"), now = { 1000L })
        val call = ProviderEvent.ToolCall("checks", "github_context", Json.parseToJsonElement("""{"action":"checks"}""") as JsonObject)
        book.observe(call, AgentToolResult("checks", ToolResultContent.Text("""{"state":"PASSED","checks":19}"""), false))
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(
                ProviderEvent.ToolCall("prs", "github_pr", Json.parseToJsonElement("""{"action":"list"}""") as JsonObject),
                ProviderEvent.Completed
            )
        }
        val gate = GitHubVerifiedSession(provider, book, "status", { 8000 }, { error("No final answer yet") }, {}, {}, now = { 1000L })
        val events = gate.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, events.filterIsInstance<ProviderEvent.ToolCall>().size)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().isEmpty())
    }

    @Test fun testingReportsKeepSavedReceiptsWhileBlockingMissingExecutableTools() = runBlocking {
        val book = GitHubEvidenceBook("binding", "o/r", "main", setOf("github_read"), now = { 1000L })
        val call = ProviderEvent.ToolCall("read", "github_read", Json.parseToJsonElement("""{"commit_sha":"${"a".repeat(40)}","ranges":[{"path":"Test.kt","start_line":1,"end_line":5}]}""") as JsonObject)
        book.observe(call, AgentToolResult("read", ToolResultContent.Text("test source"), false))
        val session = GitHubVerifiedSession(
            answer("all tests passed"),
            book,
            "test all functionality",
            { 8000 },
            { error("Deterministic coverage must not call the extraction model") },
            {},
            {},
            now = { 1000L }
        )
        val events = session.streamRound(emptyList(), emptyList()).toList()
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(text.contains("partial acceptance"))
        assertTrue(text.contains("github_read / source read"))
        assertFalse(text.contains("all tests passed"))
        val terminal = events.last() as ProviderEvent.Failed
        assertEquals("BLOCKED", terminal.taskOutcome)
        assertEquals("REQUIRED_TOOL_CAPABILITY_UNAVAILABLE", terminal.errorCode)
        assertEquals(JsonPrimitive(false), terminal.failureDetails?.get("dispatched"))
        assertEquals(1, book.snapshot().first.size)
    }

    @Test fun offlineProviderKeepsItsConnectionErrorInsteadOfBecomingAVerificationError() = kotlinx.coroutines.runBlocking {
        val provider = object : dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession {
            override fun streamRound(tools: List<dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition>, exchanges: List<dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange>) = kotlinx.coroutines.flow.flowOf(dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Completed)
        }
        val gate = GitHubVerifiedSession(provider, GitHubEvidenceBook("b", "o/r", "main", emptySet()), "fix", { 8192 }, { error("must not verify an offline model") }, {}, {})
        val failure = dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Failed("LOCAL_SERVICE_UNREACHABLE: CONNECT_TIMEOUT")
        val events = gate.retainedFailureResponse(failure).toList()
        val terminal = events.single() as dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Failed
        assertEquals("LOCAL_SERVICE_UNREACHABLE", terminal.errorCode)
        assertEquals(failure.message, terminal.message)
    }

    @Test fun statusCollectsChecksWithoutModelPlanningOrVerifierRound() = runTest {
        var rounds = 0
        val sha = "a".repeat(40)
        val calls = mutableListOf<JsonObject>()
        var publicationChecks = 0
        val read = object : AgentTool {
            override val definition = AgentToolDefinition("github_context", "Status", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                calls += arguments
                val data = when (arguments["action"]) {
                    JsonPrimitive("status") -> """{"repository":{"full_name":"o/r","default_branch":"main"},"branch":"main","commit_sha":"$sha","open_pull_request_count":0,"latest_workflow":{"name":"Build","head_sha":"$sha","head_branch":"main","status":"completed","conclusion":"success"}}"""
                    JsonPrimitive("checks") -> """{"commit_sha":"$sha","checks":0,"statuses":0,"check_details":{"total_count":0,"check_runs":[]},"status_details":{"total_count":0,"statuses":[]},"page":1,"has_more":false}"""
                    JsonPrimitive("commits") -> """{"commit_sha":"$sha","items":[{"sha":"$sha","message":"Observed commit"}],"returned":1,"has_more":false}"""
                    else -> error("Unexpected status action: $arguments")
                }
                return AgentToolResult(callId, ToolResultContent.Json(Json.parseToJsonElement("""{"schema":"github.operation.v1","data":$data}""")), false)
            }
        }
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                check(++rounds == 1) { "Status must not drift into source inspection" }
                emit(ProviderEvent.ToolCall("status", "github_context", Json.parseToJsonElement("""{"action":"status"}""") as JsonObject))
                emit(ProviderEvent.Completed)
            }
        }
        val gate = GitHubVerifiedSession(provider, GitHubEvidenceBook("b", "o/r", "main", setOf("github_context"), { 1000 }), "status", { 8192 }, { error("Receipt-based status needs no model formatter") }, { publicationChecks++ }, {}, { 1000 }, StandardTestDispatcher(testScheduler))
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, listOf(read)).toList()
        val published = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        val text = published.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertEquals(0, rounds)
        assertEquals(listOf("status", "checks", "commits").map(::JsonPrimitive), calls.map { it["action"] })
        assertTrue(calls.drop(1).all { it["commit_sha"] == JsonPrimitive(sha) })
        assertEquals(2, publicationChecks)
        assertTrue(text.startsWith("# Repository status"))
        assertTrue(text, text.contains("| Branch main · Open pull requests | 0 |"))
        assertTrue(text, text.contains("| Branch main · Workflow result | success |"))
        assertFalse(text.contains("/data/") || text.contains("ev_") || text.contains("clean state"))
        assertEquals("PARTIAL", (published.last() as ProviderEvent.Failed).taskOutcome)
    }

    @Test fun runnerRetriesTransientFailuresBeforePublishingWithoutRepeatingCompletedTools() = runTest {
        for (persistent in listOf(false, true)) {
            var rounds = 0
            var dispatches = 0
            var extractions = 0
            val read = object : AgentTool {
                override val definition = AgentToolDefinition("github_read", "Read", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    dispatches++
                    return AgentToolResult(callId, ToolResultContent.Json(JsonObject(mapOf("count" to JsonPrimitive(3)))), false)
                }
            }
            val provider = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    rounds++
                    when {
                        rounds == 1 -> {
                            emit(ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap())))
                            emit(ProviderEvent.Completed)
                        }
                        persistent || rounds == 2 -> emit(ProviderEvent.Failed("Connection reset"))
                        else -> {
                            assertTrue(exchanges.isNotEmpty())
                            emit(ProviderEvent.TextDelta("private candidate"))
                            emit(ProviderEvent.Completed)
                        }
                    }
                }
            }
            val gate = GitHubVerifiedSession(provider, GitHubEvidenceBook("b", "o/r", "main", setOf("github_read"), { 1000 }), "fix", { 8192 }, {
                extractions++
                answer("""{"claims":[{"kind":"not_measured","subject":"Feature acceptance"}]}""")
            }, {}, {}, { 1000 }, StandardTestDispatcher(testScheduler))
            val events = AgentRunner(AgentRunLimits(githubTask = true, maxRounds = 2)).run(gate, listOf(read)).toList()
            assertEquals(1, dispatches)
            assertEquals(if (persistent) GitHubTaskRecovery.MAX_RECOVERIES + 2 else 3, rounds)
            assertEquals(if (persistent) 0 else 1, extractions)
            val published = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
            val failure = published.last() as ProviderEvent.Failed
            assertEquals("PARTIAL", failure.taskOutcome)
            val text = published.filterIsInstance<ProviderEvent.TextDelta>().single().text
            assertEquals(persistent, text.startsWith("**Task paused.**"))
            if (persistent) assertEquals("Connection reset", failure.message)
            assertFalse(text.contains("| Count | 3 |"))
            assertFalse(text.contains("private candidate"))
        }
    }

    @Test fun exhaustedToolFrameRepairPublishesRetainedFactsWithoutRepeatingTheRead() = runTest {
        var rounds = 0
        var dispatches = 0
        val read = object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "Read", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                return AgentToolResult(callId, ToolResultContent.Json(JsonObject(mapOf("count" to JsonPrimitive(3)))), false)
            }
        }
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (++rounds == 1) {
                    emit(ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap())))
                    emit(ProviderEvent.Completed)
                } else {
                    emit(ProviderEvent.Failed("Unparsed tool-call markup", errorCode = "TOOL_PROTOCOL_INVALID_FRAME"))
                }
            }
        }
        val gate = GitHubVerifiedSession(source, GitHubEvidenceBook("b", "o/r", "main", setOf("github_read"), { 1000 }), "inspect", { 8192 }, { error("No formatter after exhausted repair") }, {}, {}, { 1000 }, StandardTestDispatcher(testScheduler))
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, listOf(read)).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals(4, rounds)
        assertEquals(1, dispatches)
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(text.startsWith("**Task paused.** Unparsed tool-call markup"))
        assertFalse(text.contains("| Count | 3 |"))
        val failure = events.last() as ProviderEvent.Failed
        assertEquals("TOOL_PROTOCOL_INVALID_FRAME", failure.errorCode)
        assertEquals("PARTIAL", failure.taskOutcome)
    }

    @Test fun terminalFallbackCannotDisclosePrivateDraftAfterRevocation() = runTest {
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta("PRIVATE_DRAFT_FROM_REVOKED_SOURCE"), ProviderEvent.Failed("403 forbidden"))
        }
        val gate = GitHubVerifiedSession(source, GitHubEvidenceBook("b", "o/r", "main", emptySet()), "fix", { 8192 }, { error("no formatter after failure") }, { error("access revoked") }, {})
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, emptyList()).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertTrue(events.last() is ProviderEvent.Failed)
        val status = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(status.contains("Status: BLOCKED"))
        assertFalse(status.contains("PRIVATE_DRAFT_FROM_REVOKED_SOURCE"))
        assertFalse(events.any { it is ProviderEvent.Completed })
    }

    @Test fun unfinishedToolCallCannotBecomePartialCompletionOrDispatch() = runTest {
        var dispatches = 0
        val write = object : AgentTool {
            override val definition = AgentToolDefinition("github_write", "Write", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                return AgentToolResult(callId, ToolResultContent.Text("unexpected"), false)
            }
        }
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(
                ProviderEvent.ToolCall("pending", "github_write", JsonObject(emptyMap())),
                ProviderEvent.Failed("403 forbidden")
            )
        }
        val gate = GitHubVerifiedSession(source, GitHubEvidenceBook("b", "o/r", "main", emptySet()), "fix", { 8192 }, { error("must not extract") }, { error("must not finalize pending calls") }, {})
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, listOf(write)).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals(0, dispatches)
        assertTrue(events.last() is ProviderEvent.Failed)
        val status = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(status.contains("Status: BLOCKED"))
        assertTrue(status.contains("No tool result was delivered"))
        assertFalse(events.any { it is ProviderEvent.Completed })
    }

    @Test fun oversizedTaskStaysIncompleteAndOversizedDraftUsesABoundedExcerpt() = runBlocking {
        for (largeTask in listOf(false, true)) {
            val draft = if (largeTask) "private" else "private".repeat(1000)
            val task = if (largeTask) "task".repeat(1000) else "task"
            val prompts = mutableListOf<String>()
            val gate = GitHubVerifiedSession(answer(draft), GitHubEvidenceBook("b", "o/r", "main", emptySet()), task, { 512 }, { prompt ->
                prompts += prompt
                answer("""{"claims":[{"kind":"not_measured","subject":"Feature acceptance"}]}""")
            }, {}, {})
            val events = gate.streamRound(emptyList(), emptyList()).toList()
            if (largeTask) {
                assertTrue(prompts.isEmpty())
            } else {
                val prompt = prompts.single()
                assertTrue(TextTokenEstimate.estimate(prompt) <= 512)
                val candidate = Json.parseToJsonElement(prompt).jsonObject.getValue("candidate") as JsonPrimitive
                assertTrue(candidate.content.length in 1 until draft.length)
            }
            assertTrue(events.last() is ProviderEvent.Failed)
            assertFalse(events.contains(ProviderEvent.Completed))
            assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().none { it.text.contains("private") })
        }
    }

    @Test fun claimExtractionResumesTheFailedBatchWithoutRegeneratingTheDraft() = runBlocking {
        val stored = mutableMapOf<String, JsonObject>()
        fun book() = GitHubEvidenceBook("b", "o/r", "main", emptySet(), now = { 1000 }, load = { stored[it] }, save = { key, value -> stored[key] = value })
        val task = "Explain the change\n- Document the source\n- Explain the effect\n- List the remaining work\n- Give the result"
        val requests = mutableListOf<Pair<Int, Int>>()
        val response = """{"claims":[{"kind":"not_measured","subject":"Feature acceptance"}]}"""
        fun batch(prompt: String): Int {
            val request = Json.parseToJsonElement(prompt).jsonObject
            val batch = request.getValue("batch").toString().toInt()
            requests += batch to request.getValue("claim_limit").toString().toInt()
            return batch
        }
        val privateDraft = "Private draft for the accepted request ".repeat(2_000)
        assertTrue(privateDraft.length > 64_000)
        val initial = GitHubVerifiedSession(answer(privateDraft), book(), task, { 8192 }, { prompt ->
            if (batch(prompt) == 1) {
                answer(response)
            } else {
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) =
                        flowOf(ProviderEvent.TextDelta("{\"claims\":["), ProviderEvent.Usage(finishReason = "length"), ProviderEvent.Completed)
                }
            }
        }, {}, {}, { 1000 })
        val first = initial.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed
        assertEquals("GITHUB_EXTRACTION_OUTPUT_LIMIT", first.errorCode)
        val firstRequests = listOf(1 to 8, 2 to 8, 2 to 4, 2 to 2, 2 to 1)
        assertEquals(firstRequests, requests)

        val unavailableDraft = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("The saved draft must be reused")
        }
        val resumed = GitHubVerifiedSession(unavailableDraft, book(), task, { 8192 }, { prompt ->
            assertEquals(2, batch(prompt))
            answer(response)
        }, {}, {}, { 1000 }, currentRequest = "Continue")
        val delivered = resumed.streamRound(emptyList(), emptyList()).toList()
        assertEquals(firstRequests + (2 to 8), requests)
        assertTrue(delivered.any { it is ProviderEvent.TextDelta })
        val checkpoint = stored.entries.single { it.key.startsWith("claim_extraction_current_") }.value
        assertEquals(JsonPrimitive(false), checkpoint["pending"])
        assertEquals(2, checkpoint.getValue("batches").jsonArray.size)
    }

    @Test fun extractionProviderFailureKeepsItsOwnedAttemptWithoutAuthorizingPrimaryReplay() = runTest {
        val stored = mutableMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("b", "o/r", "main", emptySet(), now = { 1000 }, load = { stored[it] }, save = { key, value -> stored[key] = value })
        var extractions = 0
        var generations = 0
        val original = ProviderEvent.Failed(
            "HTTP 429: provider rate limit reached",
            errorCode = "429",
            invocationId = "extraction-attempt",
            providerLimit = dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision("OPENROUTER", "selected", "selected-model", 31_156, "selected-scope", "RATE_LIMIT", true),
            failureDetails = buildJsonObject {
                put("failed_stage", "model_invocation")
                put("http_status", 429)
                put("retry_after_ms", 30_156)
                put("dispatched", true)
                put("remote_acceptance", "NO")
                put("retryable", true)
            }
        )
        val primary = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                generations++
                emit(ProviderEvent.TextDelta("Saved draft"))
                emit(ProviderEvent.Completed)
            }
        }
        val gate = GitHubVerifiedSession(primary, book, "Explain the source", { 8192 }, {
            extractions++
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(original)
            }
        }, {}, {}, now = { 1000 })
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, emptyList()).toList()
        val failed = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }.last() as ProviderEvent.Failed
        assertEquals(1, generations)
        assertEquals(1, extractions)
        assertEquals("429", failed.errorCode)
        assertEquals("extraction-attempt", failed.invocationId)
        assertEquals(null, failed.providerLimit)
        assertEquals(JsonPrimitive("claim_extraction"), failed.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive("model_invocation"), failed.failureDetails?.get("provider_failed_stage"))
        assertEquals(JsonPrimitive(429), failed.failureDetails?.get("http_status"))
        assertEquals(JsonPrimitive(false), failed.failureDetails?.get("logical_continuation_allowed"))
        assertEquals(JsonPrimitive(false), failed.failureDetails?.get("automatic_retry_allowed"))
        assertTrue(stored.values.any { it["pending"] == JsonPrimitive(true) && it["draft"] != null })

        val noGeneration = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("Saved report delivery cannot dispatch a model")
        }
        val saved = GitHubVerifiedSession(noGeneration, book, "Explain the source", { 8192 }, { error("No extraction on saved report delivery") }, {}, {}, now = { 1000 }, currentRequest = "show the report")
        val redelivered = saved.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed
        assertEquals(null, redelivered.providerLimit)
        assertEquals(null, redelivered.invocationId)
        for (key in listOf("dispatched", "invocation_id", "http_status", "retry_after_ms", "remote_acceptance")) assertFalse(redelivered.failureDetails.orEmpty().containsKey(key))
    }

    @Test fun providerExtractionCodesCannotBypassTheConfirmedRepairDisposition() = runBlocking {
        val prohibitions = listOf(
            buildJsonObject { put("requires_reconciliation", true) },
            buildJsonObject { put("remote_acceptance", "UNKNOWN") },
            buildJsonObject { put("request_replay_allowed", false) },
            buildJsonObject { put("logical_continuation_allowed", false) },
            buildJsonObject { put("automatic_retry_allowed", false) }
        )
        for (code in listOf("GITHUB_EXTRACTION_OUTPUT_LIMIT", "GITHUB_EXTRACTION_INVALID_RESPONSE")) {
            for (details in prohibitions) {
                var extractions = 0
                val original = ProviderEvent.Failed("Keep the original attempt", errorCode = code, transportComplete = true, invocationId = "original-attempt", failureDetails = details)
                val gate = GitHubVerifiedSession(answer("Saved draft"), GitHubEvidenceBook("b", "o/r", "main", emptySet()), "Explain the source", { 8192 }, {
                    extractions++
                    object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(original, ProviderEvent.Completed)
                    }
                }, {}, {})
                val failure = gate.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed
                assertEquals(1, extractions)
                assertEquals(code, failure.errorCode)
                assertEquals("original-attempt", failure.invocationId)
                details.forEach { (key, value) -> assertEquals(value, failure.failureDetails?.get(key)) }
            }
        }
    }

    @Test fun thrownExtractionTransportFailureDoesNotRepeatGenerationOrSchemaRepair() = runBlocking {
        var extractions = 0
        val gate = GitHubVerifiedSession(answer("Saved draft"), GitHubEvidenceBook("b", "o/r", "main", emptySet()), "Explain the source", { 8192 }, {
            extractions++
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> { throw java.io.IOException("connection reset") }
            }
        }, {}, {})
        val failure = gate.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed
        assertEquals(1, extractions)
        assertEquals("CONNECTION_ABORTED", failure.errorCode)
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("logical_continuation_allowed"))
        assertTrue(failure.failureDetails.toString().contains("java.io.IOException"))
    }

    @Test fun extractionFailureCannotOpenCorrectiveExecutionForResumableAcceptance() = runBlocking {
        val stored = mutableMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_context"), now = { 1000 }, save = { key, value -> stored[key] = value })
        book.observe(
            ProviderEvent.ToolCall("read", "github_context", buildJsonObject { put("action", "checks") }),
            AgentToolResult("read", ToolResultContent.Json(buildJsonObject { put("checks", 1) }), false, dispatched = true)
        )
        var corrections = 0
        var extractions = 0
        val original = ProviderEvent.Failed(
            "The extractor's outcome needs inspection",
            errorCode = "GITHUB_TASK_INCOMPLETE",
            invocationId = "uncertain-extraction",
            failureDetails = buildJsonObject { put("requires_reconciliation", true) }
        )
        val gate = GitHubVerifiedSession(answer("Saved draft"), book, "Fix the source code", { 8192 }, {
            extractions++
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(original)
            }
        }, {}, {}, now = { 1000 }, openExecutionContinuation = {
            corrections++
            error("A failed extractor cannot authorize primary execution")
        })
        val events = gate.streamRound(listOf(AgentToolDefinition("github_context", "Repository context", JsonObject(emptyMap()))), emptyList()).toList()
        val report = stored.entries.single { it.key.startsWith("verification_current_") }.value
        val receipts = book.snapshot().first
        assertTrue(GitHubExecutionContinuation.progressKeys(receipts).isNotEmpty())
        assertTrue(GitHubExecutionContinuation.canResume(report, receipts, null, null, allowVerificationResume = true))
        assertEquals(1, extractions)
        assertEquals(0, corrections)
        val failure = events.last() as ProviderEvent.Failed
        assertEquals("uncertain-extraction", failure.invocationId)
        assertEquals(JsonPrimitive(true), failure.failureDetails?.get("requires_reconciliation"))
    }

    @Test fun confirmedExtractionOutputLimitRetriesWithSmallerClaimsOnly() = runBlocking {
        val limits = mutableListOf<Int>()
        val gate = GitHubVerifiedSession(answer("Saved draft"), GitHubEvidenceBook("b", "o/r", "main", emptySet()), "Explain the source", { 8192 }, { prompt ->
            val limit = Json.parseToJsonElement(prompt).jsonObject.getValue("claim_limit").toString().toInt()
            limits += limit
            if (limit == 8) {
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(
                        ProviderEvent.TextDelta("{\"claims\":["),
                        ProviderEvent.Failed("Output limit", errorCode = "OUTPUT_LIMIT", transportComplete = true)
                    )
                }
            } else {
                answer("""{"claims":[{"kind":"not_measured","subject":"Feature acceptance"}]}""")
            }
        }, {}, {})
        val events = gate.streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf(8, 4), limits)
        assertTrue(events.any { it is ProviderEvent.TextDelta })
        assertTrue((events.last() as ProviderEvent.Failed).errorCode != "GITHUB_EXTRACTION_OUTPUT_LIMIT")
    }

    @Test fun currentSessionReviewReusesVerifiedClaimsAndReextractsOnlyStalePrCiBatch() = runBlocking {
        var clock = 1_000L
        val stored = mutableMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook(
            "b",
            "o/r",
            "main",
            setOf("github_pr", "github_ci"),
            now = { clock },
            load = { stored[it] },
            save = { key, value -> stored[key] = value }
        )
        suspend fun read(callId: String, tool: String, action: String, result: String) {
            val call = ProviderEvent.ToolCall(callId, tool, Json.parseToJsonElement("""{"action":"$action","pull_number":42,"verify_live":true}""").jsonObject)
            book.observe(call, AgentToolResult(callId, ToolResultContent.Json(Json.parseToJsonElement(result).jsonObject), false, dispatched = true))
        }
        read("old-pr", "github_pr", "context", """{"data":{"number":42,"title":"Old title"}}""")
        read("old-ci", "github_ci", "runs", """{"data":{"workflow_runs":[{"conclusion":"failure"}]}}""")

        val task = """Review the saved session capture and report current PR and CI status.
- Explain the original failure.
- Identify the related PR.
- Summarize the observed workflow.
- State the current result.
- Give the next action."""
        val requirements = GitHubRequestCompletion.requirements(task).distinct().chunked(GitHubClaimBatches.REQUIREMENTS_PER_BATCH)
        assertEquals(2, requirements.size)
        val requirementIds = requirements.map { group -> group.map(GitHubRequestCompletion::id).toSet() }
        val oldPrId = book.snapshot().first.entries.single { it.value["call_id"] == JsonPrimitive("old-pr") }.key
        val oldCiId = book.snapshot().first.entries.single { it.value["call_id"] == JsonPrimitive("old-ci") }.key
        fun fact(receipt: String, pointer: String, value: JsonPrimitive) = buildJsonObject {
            put("kind", "fact")
            put("receipt_id", receipt)
            put("pointer", pointer)
            put("value", value)
        }
        val completedBatch = buildJsonObject {
            put("claims", JsonArray(listOf(fact(oldPrId, "/data/number", JsonPrimitive(42)))))
            put("requirement_claims", JsonObject(emptyMap()))
            put("acceptance", JsonObject(emptyMap()))
            put("questions", JsonArray(emptyList()))
        }
        val staleBatch = buildJsonObject {
            put(
                "claims",
                JsonArray(
                    listOf(
                        fact(oldPrId, "/data/title", JsonPrimitive("Old title")),
                        fact(oldCiId, "/data/workflow_runs/0/conclusion", JsonPrimitive("failure"))
                    )
                )
            )
            put("requirement_claims", JsonObject(emptyMap()))
            put("acceptance", JsonObject(emptyMap()))
            put("questions", JsonArray(emptyList()))
        }
        book.retainClaimBatches("old-context", "old-plan", "Private saved draft", listOf(completedBatch, staleBatch), requirementIds, pending = true)

        clock = 200_000L
        read("fresh-pr", "github_pr", "context", """{"data":{"number":42,"title":"Current title"}}""")
        read("fresh-ci", "github_ci", "runs", """{"data":{"workflow_runs":[{"conclusion":"success"}]}}""")
        val fresh = book.snapshot().first
        val freshPrId = fresh.entries.single { it.value["call_id"] == JsonPrimitive("fresh-pr") }.key
        val freshCiId = fresh.entries.single { it.value["call_id"] == JsonPrimitive("fresh-ci") }.key
        val extractedBatches = mutableListOf<Int>()
        val session = GitHubVerifiedSession(
            delegate = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("The retained session draft must be reused")
            },
            evidence = book,
            task = task,
            inputLimit = { 16_384 },
            openVerifier = { prompt ->
                val request = Json.parseToJsonElement(prompt).jsonObject
                extractedBatches += (request["batch"] as JsonPrimitive).content.toInt()
                require(extractedBatches.last() == 2) { "Only the stale PR/CI batch should be extracted again" }
                answer(
                    """{"claims":[{"kind":"fact","receipt_id":"$freshPrId","pointer":"/data/title","value":"Current title"},{"kind":"fact","receipt_id":"$freshCiId","pointer":"/data/workflow_runs/0/conclusion","value":"success"}],"requirement_claims":{},"acceptance":{},"questions":[]}"""
                )
            },
            validatePublication = {},
            progress = {},
            now = { clock },
            resumeClaimExtraction = true,
            currentRequest = "Continue"
        )

        session.streamRound(emptyList(), emptyList()).toList()

        assertEquals(listOf(2), extractedBatches)
        val retained = requireNotNull(book.retainedClaimExtraction())
        val batches = retained.getValue("batches").jsonArray
        assertEquals("/data/number", batches[0].jsonObject.getValue("claims").jsonArray.single().jsonObject.getValue("pointer").toString().trim('"'))
        val refreshed = batches[1].jsonObject.getValue("claims").jsonArray
        assertTrue(refreshed.any { it.jsonObject["receipt_id"] == JsonPrimitive(freshPrId) })
        assertTrue(refreshed.any { it.jsonObject["receipt_id"] == JsonPrimitive(freshCiId) })
    }

    @Test fun evidenceArrivingDuringVerificationCannotPublishAStaleCompletion() = runBlocking {
        var published: JsonObject? = null
        var verifierCalls = 0
        var descendantCompleted = false
        val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_context"), now = { 1000L })
        book.observe(
            ProviderEvent.ToolCall("checks", "github_context", Json.parseToJsonElement("""{"action":"checks"}""").jsonObject),
            AgentToolResult("checks", ToolResultContent.Json(Json.parseToJsonElement("""{"checks":1}""")), false)
        )
        val checksReceipt = book.snapshot().first.keys.single()
        val gate = GitHubVerifiedSession(
            answer("One repository check was observed."),
            book,
            "Explain the observed repository check count",
            { 8192 },
            {
                verifierCalls++
                // Simulate a slower owned operation completing while claim
                // extraction is inspecting the earlier receipt epoch.
                if (!descendantCompleted) {
                    descendantCompleted = true
                    book.observe(
                        ProviderEvent.ToolCall("status", "github_context", Json.parseToJsonElement("""{"action":"status"}""").jsonObject),
                        AgentToolResult("status", ToolResultContent.Json(Json.parseToJsonElement("""{"commit_sha":"${"a".repeat(40)}"}""")), false)
                    )
                }
                answer("""{"claims":[{"kind":"fact","receipt_id":"$checksReceipt","pointer":"/checks","value":1}]}""")
            },
            {},
            {},
            now = { 1000L },
            onPublication = { published = it }
        )

        val events = gate.streamRound(emptyList(), emptyList()).toList()

        assertEquals(2, verifierCalls)
        assertEquals(ProviderEvent.Completed, events.last())
        assertEquals(JsonPrimitive(true), published?.get("completion_verified"))
        assertEquals(JsonPrimitive(1), published?.get("verification_restarts"))
        assertEquals(JsonPrimitive(true), published?.get("owned_descendants_settled"))
        assertFalse(published?.get("verification_failure") == JsonPrimitive("GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION"))
    }

    @Test fun terminalPublicationAwaitsAnOwnedDescendantReceiptBeforeSnapshotting() = runBlocking {
        var published: JsonObject? = null
        var waits = 0
        var verifierCalls = 0
        val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_context"), now = { 1000L })
        book.observe(
            ProviderEvent.ToolCall("checks", "github_context", Json.parseToJsonElement("""{"action":"checks"}""").jsonObject),
            AgentToolResult("checks", ToolResultContent.Json(Json.parseToJsonElement("""{"checks":1}""")), false)
        )
        val checksReceipt = book.snapshot().first.keys.single()
        val gate = GitHubVerifiedSession(
            answer("One repository check was observed."),
            book,
            "Explain the observed repository check count",
            { 8192 },
            {
                verifierCalls++
                answer("""{"claims":[{"kind":"fact","receipt_id":"$checksReceipt","pointer":"/checks","value":1}]}""")
            },
            {},
            {},
            now = { 1000L },
            onPublication = { published = it },
            awaitOwnedDescendants = { limit ->
                waits++
                assertEquals(30_000L, limit)
                // The child becomes terminal only after its final durable receipt
                // is visible to the accepted run.
                book.observe(
                    ProviderEvent.ToolCall("status", "github_context", Json.parseToJsonElement("""{"action":"status"}""").jsonObject),
                    AgentToolResult("status", ToolResultContent.Json(Json.parseToJsonElement("""{"commit_sha":"${"b".repeat(40)}"}""")), false)
                )
                true
            }
        )

        val events = gate.streamRound(emptyList(), emptyList()).toList()

        assertEquals(1, waits)
        assertEquals(1, verifierCalls)
        assertEquals(ProviderEvent.Completed, events.last())
        assertEquals(JsonPrimitive(true), published?.get("owned_descendants_settled"))
        assertEquals(JsonPrimitive(2), (published?.get("execution_ledger") as? JsonObject)?.get("receipt_count"))
    }

    @Test fun extractionBatchLimitCannotSilentlyDropRequirements() = runBlocking {
        val task = (1..(GitHubClaimBatches.REQUIREMENTS_PER_BATCH * GitHubClaimBatches.MAX_BATCHES + 1))
            .joinToString("\n") { "Requirement $it must remain covered" }
        var extractions = 0
        val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_context"))
        book.observe(
            ProviderEvent.ToolCall("context", "github_context", Json.parseToJsonElement("""{"action":"resolve"}""").jsonObject),
            AgentToolResult("context", ToolResultContent.Json(Json.parseToJsonElement("""{"data":{"repository":{"full_name":"o/r"}}}""").jsonObject), false)
        )
        val gate = GitHubVerifiedSession(
            answer("Private draft"),
            book,
            task,
            { 8192 },
            {
                extractions++
                error("An incomplete bounded plan must not be dispatched")
            },
            {},
            {}
        )

        val events = gate.streamRound(emptyList(), emptyList()).toList()
        assertEquals(0, extractions)
        val failure = events.last() as ProviderEvent.Failed
        assertEquals(failure.message, "GITHUB_EXTRACTION_UNAVAILABLE", failure.errorCode)
        assertEquals("PARTIAL", failure.taskOutcome)
    }

    @Test fun claimBatchesRemapCoverageAfterDeduplicatingSharedFacts() {
        fun answer(value: String) = Json.parseToJsonElement(value).jsonObject
        val first = GitHubClaimBatches.validate(
            answer("""{"claims":[{"kind":"fact","receipt_id":"result","pointer":"/number","value":816}],"requirement_claims":{"one":[0]}}"""),
            GitHubClaimBatches.Request("", setOf("one"))
        )
        val second = GitHubClaimBatches.validate(
            answer("""{"claims":[{"kind":"fact","receipt_id":"result","pointer":"/state","value":"open"},{"kind":"fact","receipt_id":"result","pointer":"/number","value":816}],"requirement_claims":{"two":[0,1]}}"""),
            GitHubClaimBatches.Request("", setOf("two"))
        )
        val combined = GitHubClaimBatches.combine(listOf(first, second))
        assertEquals(2, combined.getValue("claims").jsonArray.size)
        assertEquals(answer("""{"one":[0],"two":[1,0]}"""), combined["requirement_claims"])
        assertTrue(runCatching { GitHubClaimBatches.validate(second, GitHubClaimBatches.Request("", setOf("one"))) }.isFailure)
    }

    @Test fun newestReceiptPrefixFitsBudgetAndShrinkingCapacityPreventsDispatch() = runBlocking {
        for (shrink in listOf(false, true)) {
            var limits = 0
            var extractions = 0
            val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_read"), { 1000 })
            repeat(20) { index ->
                book.observe(ProviderEvent.ToolCall("r$index", "github_read", JsonObject(emptyMap())), AgentToolResult("r$index", ToolResultContent.Json(JsonObject(mapOf("count" to JsonPrimitive(index)))), false))
            }
            val gate = GitHubVerifiedSession(answer("private"), book, "task", { if (shrink && ++limits > 1) 1 else 700 }, { prompt ->
                extractions++
                assertTrue(TextTokenEstimate.estimate(prompt) <= 700)
                val catalog = Json.parseToJsonElement(prompt).jsonObject.getValue("catalog").jsonArray
                assertTrue(catalog.size in 1 until 20)
                assertEquals(JsonPrimitive(19), catalog.first().jsonObject.getValue("facts").jsonObject["/count"])
                answer("""{"claims":[{"kind":"not_measured","subject":"Feature acceptance"}]}""")
            }, {}, {}, { 1000 })
            assertEquals("PARTIAL", (gate.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed).taskOutcome)
            assertEquals(if (shrink) 0 else 1, extractions)
        }
    }

    @Test fun downstreamCollectorFailurePropagatesWithoutAnAlternativePublication() = runBlocking {
        val gate = GitHubVerifiedSession(answer("private"), GitHubEvidenceBook("b", "o/r", "main", emptySet()), "fix", { 8192 }, { answer("invalid JSON") }, {}, {})
        val failure = IllegalStateException("collector stopped")
        val result = runCatching { gate.streamRound(emptyList(), emptyList()).collect { throw failure } }
        assertSame(failure, result.exceptionOrNull())
    }

    @Test fun stalledChecklistRendersConcreteBlockersWithoutAnotherModelDraft() = runTest {
        var verdict: JsonObject? = null
        val book = GitHubEvidenceBook("task", "o/r", "main", setOf("github_context", "github_ci"), now = { 1000L }, save = { id, row -> if (id.startsWith("verification_current_")) verdict = row }, latestVerdict = { verdict })
        book.observe(ProviderEvent.ToolCall("status", "github_context", Json.parseToJsonElement("""{"action":"status"}""").jsonObject), AgentToolResult("status", ToolResultContent.Json(Json.parseToJsonElement("""{"commit_sha":"${"a".repeat(40)}"}""")), false))
        val tools = listOf(AgentToolDefinition("github_ci", "logs", Json.parseToJsonElement("""{"properties":{"action":{"const":"logs"}}}""").jsonObject))
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> = error("A coverage gap cannot be repaired by a model draft")
        }
        val session = GitHubVerifiedSession(delegate, book, "test all tools", { 8192 }, { error("No rewrite") }, {}, {}, now = { 1000L }, authorizedTools = tools)
        val first = session.streamRound(tools, emptyList()).toList().filterIsInstance<ProviderEvent.TextDelta>().single().text
        val second = session.streamRound(tools, emptyList()).toList().filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(first != second)
        assertTrue(second.contains("No new test evidence"))
        assertTrue(second.contains("completed job"))
        assertEquals(JsonPrimitive("BLOCKED_UNCHANGED"), verdict?.get("progress_state"))
    }

    @Test fun undeliveredChecklistSurvivesRestartAndIsPublishedBeforeStallSuppression() = runTest {
        val saved = linkedMapOf<String, JsonObject>()
        var verdict: JsonObject? = null
        fun book() = GitHubEvidenceBook(
            "task", "o/r", "main", setOf("github_context", "github_ci"), now = { 1000L },
            load = { saved[it] },
            save = { id, row ->
                saved[id] = row
                if (id.startsWith("verification_current_")) verdict = row
            },
            retainedIds = { saved.keys.filter { it.startsWith("ev_") } }, latestVerdict = { verdict }
        )
        val tools = listOf(AgentToolDefinition("github_ci", "logs", Json.parseToJsonElement("""{"properties":{"action":{"const":"logs"}}}""").jsonObject))
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): kotlinx.coroutines.flow.Flow<ProviderEvent> = error("A saved checklist does not need another model draft")
        }
        fun session(evidence: GitHubEvidenceBook) = GitHubVerifiedSession(delegate, evidence, "test all tools", { 8192 }, { error("No rewrite") }, {}, {}, now = { 1000L }, authorizedTools = tools)
        val original = book()
        original.observe(ProviderEvent.ToolCall("status", "github_context", Json.parseToJsonElement("""{"action":"status"}""").jsonObject), AgentToolResult("status", ToolResultContent.Json(Json.parseToJsonElement("""{"commit_sha":"${"a".repeat(40)}"}""")), false))
        val interrupted = IllegalStateException("Delivery interrupted before acceptance")
        var preparedText: String? = null
        val failed = runCatching {
            session(original).streamRound(tools, emptyList()).collect { event ->
                if (event is ProviderEvent.TextDelta) {
                    preparedText = event.text
                    throw interrupted
                }
            }
        }
        assertSame(interrupted, failed.exceptionOrNull())
        assertEquals(JsonPrimitive(false), verdict?.get("response_delivered"))
        val restored = book()
        restored.restore()
        val resumed = session(restored).streamRound(tools, emptyList()).toList().filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertEquals(preparedText, resumed)
        assertFalse(resumed.contains("No new test evidence"))
        assertEquals(JsonPrimitive(true), verdict?.get("response_delivered"))
        val deliveredAgain = session(restored).streamRound(tools, emptyList()).toList().filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(deliveredAgain.contains("No new test evidence"))
        assertEquals(JsonPrimitive("BLOCKED_UNCHANGED"), verdict?.get("progress_state"))
    }
}
