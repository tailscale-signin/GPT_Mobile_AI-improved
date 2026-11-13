package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReviewBudgetRecoveryTest {
    @Test fun reviewerRetryCheckpointRetainsNoUsageAttemptEvidenceAfterSuccessfulRecovery() = runTest {
        val details = requireNotNull(dev.chungjungsoo.gptmobile.data.network.structuredProviderFailure("""{"error":{"code":"completion_repair_budget_exhausted","failed_stage":"provider_generation","gateway_recovery":{"schema_version":1,"attempt_receipts":[{"attempt":1,"output_allowance":1536,"finish_reason":"LENGTH","visible_output_bytes":0,"reasoning_output_bytes":11,"terminal_error_code":"completion_repair_budget_exhausted","terminal_status_code":200,"backend_response_observed":true,"status":"COMPLETED","raw":"private reasoning"}]}}}""", 502))
        val rows = mutableMapOf<String, JsonObject>()
        val checkpoint = GitHubReviewCheckpoint("task", { rows[it] }, { key, row -> rows[key] = row })
        var calls = 0
        val output = GitHubReviewedSession(session(ProviderEvent.TextDelta("The recorded check failed."), ProviderEvent.Completed), GitHubReviewEvidence(), "task", 8192, { prompt, _ ->
            calls++
            if (calls == 1) session(exhausted("review-no-usage").copy(failureDetails = details)) else session(ProviderEvent.TextDelta(verdict(prompt)), ProviderEvent.Completed)
        }, {}, checkpoint = checkpoint, outputLimitForPass = ::outputAllowance).streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, calls)
        assertEquals(ProviderEvent.Completed, output.last())
        val retained = rows.getValue("active").getValue("review_attempt_failures").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("review-no-usage"), retained["invocation_id"])
        val recovery = retained.getValue("gateway_recovery").jsonObject
        assertEquals(JsonPrimitive(11), recovery.getValue("attempt_receipts").jsonArray.single().jsonObject["reasoning_output_bytes"])
        assertFalse(recovery.containsKey("attempt_usage"))
        assertFalse(retained.toString().contains("private reasoning"))
        assertTrue(checkpoint.load()?.reviewed == true)
    }

    private fun outputAllowance(role: String) = GitHubStageBudget.resolve(32768, null, role).output

    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
            assertTrue(tools.isEmpty() && exchanges.isEmpty())
            return flowOf(*events)
        }
    }
    private fun verdict(prompt: String): String = buildJsonObject {
        put("schema_version", 1)
        put("verdict", "PASS")
        put("reason", "Supported")
        put("reviewed_ids", JsonArray(Json.parseToJsonElement(prompt).jsonObject.getValue("records").jsonArray.map { it.jsonObject.getValue("id") }))
        put("issues", JsonArray(emptyList()))
        put("verified_claims", JsonArray(listOf(JsonPrimitive("The recorded check failed."))))
    }.toString()
    private fun exhausted(id: String, reconcile: Boolean = false) = ProviderEvent.Failed(
        "The admitted completion repair budget was exhausted",
        errorCode = "COMPLETION_REPAIR_BUDGET_EXHAUSTED",
        invocationId = id,
        failureDetails = buildJsonObject {
            put("failed_stage", "provider_generation")
            put("server_request_id", "request-$id")
            put("requires_reconciliation", reconcile)
            put("gateway_recovery", Json.parseToJsonElement("""{"usage_origin":"PROVIDER_REPORTED","usage_estimated":false,"attempts":1,"output_allowance":1536,"attempt_usage":[{"prompt_tokens":17237,"completion_tokens":1536,"total_tokens":18773}]}"""))
        }
    )

    @Test fun exhaustedVerdictRetriesOnceWithBoundedOutputAndRetainsItsAcceptedFailureReceipt() = runTest {
        val rows = mutableMapOf<String, JsonObject>()
        val checkpoint = GitHubReviewCheckpoint("task", { rows[it] }, { key, row -> rows[key] = row })
        val roles = mutableListOf<String>()
        val output = GitHubReviewedSession(session(ProviderEvent.TextDelta("The recorded check failed."), ProviderEvent.Completed), GitHubReviewEvidence(), "task", 8192, { prompt, kind ->
            roles += kind
            if (roles.size == 1) session(exhausted("review-1")) else session(ProviderEvent.TextDelta(verdict(prompt)), ProviderEvent.Completed)
        }, {}, checkpoint = checkpoint, outputLimitForPass = ::outputAllowance).streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf("github-review", "github-review-output-retry"), roles)
        assertEquals(listOf(1536, 4096), roles.map(::outputAllowance))
        assertEquals(listOf("The recorded check failed."), output.filterIsInstance<ProviderEvent.TextDelta>().map { it.text })
        assertEquals(ProviderEvent.Completed, output.last())
        val receipts = rows.getValue("active").getValue("review_attempt_failures").jsonArray
        assertEquals(1, receipts.size)
        assertEquals(JsonPrimitive("review-1"), receipts.single().jsonObject["invocation_id"])
        assertTrue(receipts.single().jsonObject["gateway_recovery"] != null)
        assertTrue(checkpoint.load()?.reviewed == true)
    }

    @Test fun exhaustedLargerBatchSplitsItsEvidenceWithoutRepeatingPrimaryGeneration() = runTest {
        val evidence = GitHubReviewEvidence()
        repeat(2) { index ->
            evidence.observe(
                ProviderEvent.ToolCall("read-$index", "github_read", JsonObject(emptyMap())),
                AgentToolResult("read-$index", ToolResultContent.Text("Evidence $index: " + "confirmed failure ".repeat(50)), false)
            )
        }
        var reviews = 0
        val output = GitHubReviewedSession(session(ProviderEvent.TextDelta("The recorded check failed."), ProviderEvent.Completed), evidence, "task", 8192, { prompt, _ ->
            reviews++
            if (Json.parseToJsonElement(prompt).jsonObject.getValue("records").jsonArray.size > 1) {
                session(exhausted("review-$reviews"))
            } else {
                session(ProviderEvent.TextDelta(verdict(prompt)), ProviderEvent.Completed)
            }
        }, {}, outputLimitForPass = ::outputAllowance).streamRound(emptyList(), emptyList()).toList()
        assertEquals(4, reviews)
        assertEquals(ProviderEvent.Completed, output.last())
        assertEquals(2, evidence.snapshot().size)
    }

    @Test fun equalOrUnknownOutputAllowanceSplitsImmediatelyWithoutAnotherSameCapAttempt() = runTest {
        for (cap in listOf<Int?>(1536, null)) {
            val evidence = GitHubReviewEvidence().also {
                for (id in listOf("first", "second")) it.observe(ProviderEvent.ToolCall(id, "github_read", JsonObject(emptyMap())), AgentToolResult(id, ToolResultContent.Text("Evidence $id"), false))
            }
            val roles = mutableListOf<String>()
            val acceptedIds = mutableSetOf<String>()
            val output = GitHubReviewedSession(session(ProviderEvent.TextDelta("The recorded check failed."), ProviderEvent.Completed), evidence, "task", 8192, { prompt, kind ->
                roles += kind
                val records = Json.parseToJsonElement(prompt).jsonObject.getValue("records").jsonArray
                if (records.size > 1) {
                    session(exhausted("same-cap"))
                } else {
                    acceptedIds += records.map { it.jsonObject.getValue("id").toString() }
                    session(ProviderEvent.TextDelta(verdict(prompt)), ProviderEvent.Completed)
                }
            }, {}, outputLimitForPass = { role -> cap?.let { GitHubStageBudget.resolve(32768, it, role).output } }).streamRound(emptyList(), emptyList()).toList()
            assertEquals(listOf("github-review", "github-review", "github-review"), roles)
            assertEquals(2, acceptedIds.size)
            assertEquals(ProviderEvent.Completed, output.last())
            assertEquals(2, evidence.snapshot().size)
        }
    }

    @Test fun largerOutputRetryUsesItsSmallerInputAllowanceBeforeClaimingOrDispatching() = runTest {
        val initial = GitHubStageBudget.resolve(32768, null, "github-review")
        val enlarged = GitHubStageBudget.resolve(32768, null, "github-review-output-retry")
        assertTrue(enlarged.output > initial.output && enlarged.input < initial.input)
        val evidence = GitHubReviewEvidence().also {
            for (id in listOf("first", "second")) it.observe(ProviderEvent.ToolCall(id, "github_read", JsonObject(emptyMap())), AgentToolResult(id, ToolResultContent.Text("Evidence $id"), false))
        }
        val rows = mutableMapOf<String, JsonObject>()
        val checkpoint = GitHubReviewCheckpoint("task", { rows[it] }, { key, row -> rows[key] = row })
        fun measured(prompt: String): Int = if (Json.parseToJsonElement(prompt).jsonObject.getValue("records").jsonArray.size > 1) enlarged.input + 1 else 1000
        val roles = mutableListOf<String>()
        val acceptedIds = mutableSetOf<String>()
        val output = GitHubReviewedSession(
            session(ProviderEvent.TextDelta("The recorded check failed."), ProviderEvent.Completed), evidence, "task", initial.input, { prompt, kind ->
                assertTrue("An oversized retry must stop before dispatch", measured(prompt) <= GitHubStageBudget.resolve(32768, null, kind).input)
                roles += kind
                val records = Json.parseToJsonElement(prompt).jsonObject.getValue("records").jsonArray
                if (records.size > 1) {
                    session(exhausted("initial-limit"))
                } else {
                    acceptedIds += records.map { it.jsonObject.getValue("id").toString() }
                    session(ProviderEvent.TextDelta(verdict(prompt)), ProviderEvent.Completed)
                }
            }, {}, measureInput = ::measured, checkpoint = checkpoint,
            inputLimitForPass = { GitHubStageBudget.resolve(32768, null, it).input }, outputLimitForPass = ::outputAllowance
        ).streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf("github-review", "github-review", "github-review"), roles)
        assertEquals(2, acceptedIds.size)
        assertEquals(ProviderEvent.Completed, output.last())
        assertEquals(JsonPrimitive(3), rows.getValue("active")["review_passes"])
        val failed = rows.getValue("active").getValue("review_attempt_failures").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("initial-limit"), failed["invocation_id"])
        assertTrue(failed["gateway_recovery"] != null)
    }

    @Test fun unknownRemoteOutcomeNeverUsesOutputRecoveryOrPublishesTheCandidate() = runTest {
        var reviews = 0
        val evidence = GitHubReviewEvidence()
        val output = GitHubReviewedSession(session(ProviderEvent.TextDelta("Saved draft"), ProviderEvent.Completed), evidence, "task", 8192, { _, _ ->
            reviews++
            session(exhausted("review-unknown", reconcile = true))
        }, {}, outputLimitForPass = ::outputAllowance).streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, reviews)
        assertFalse(output.any { it is ProviderEvent.TextDelta })
        assertEquals("Saved draft", evidence.privateCandidate())
        assertEquals("review-unknown", output.filterIsInstance<ProviderEvent.Failed>().single().invocationId)
    }

    @Test fun capacityOrLengthLabelsCannotAuthorizeReplayOrSalvageAfterAnUnknownOutcome() = runTest {
        for (code in listOf("CONTEXT_CAPACITY_EXCEEDED", "OUTPUT_LIMIT")) {
            var reviews = 0
            val output = GitHubReviewedSession(session(ProviderEvent.TextDelta("Saved draft"), ProviderEvent.Completed), GitHubReviewEvidence(), "task", 8192, { prompt, _ ->
                reviews++
                session(
                    ProviderEvent.TextDelta(verdict(prompt)),
                    ProviderEvent.Usage(finishReason = "length"),
                    ProviderEvent.Failed("Review context or output limit", errorCode = code, invocationId = "uncertain", transportComplete = true, failureDetails = buildJsonObject { put("requires_reconciliation", true) }),
                    ProviderEvent.Completed
                )
            }, {}).streamRound(emptyList(), emptyList()).toList()
            assertEquals(1, reviews)
            assertFalse(output.any { it is ProviderEvent.TextDelta || it is ProviderEvent.Completed })
            val failure = output.filterIsInstance<ProviderEvent.Failed>().single()
            assertEquals(code, failure.errorCode)
            assertEquals(JsonPrimitive(true), failure.failureDetails?.get("requires_reconciliation"))
        }
    }

    @Test fun confirmedPredispatchCapacityFailureCanStillSplitTheRetainedEvidence() = runTest {
        for (thrown in listOf(false, true)) {
            val evidence = GitHubReviewEvidence().also {
                for (id in listOf("first", "second")) it.observe(ProviderEvent.ToolCall(id, "github_read", JsonObject(emptyMap())), AgentToolResult(id, ToolResultContent.Text("Evidence $id"), false))
            }
            var reviews = 0
            val acceptedIds = mutableSetOf<String>()
            val output = GitHubReviewedSession(session(ProviderEvent.TextDelta("Saved draft"), ProviderEvent.Completed), evidence, "task", 8192, { prompt, _ ->
                reviews++
                val records = Json.parseToJsonElement(prompt).jsonObject.getValue("records").jsonArray
                if (records.size > 1) {
                    if (thrown) throw localCapacityFailure()
                    session(
                        ProviderEvent.Failed(
                            "Final request exceeded context",
                            errorCode = "CONTEXT_CAPACITY_EXCEEDED",
                            transportComplete = true,
                            failureDetails = buildJsonObject {
                                put("failed_stage", "provider_admission")
                                put("dispatched", false)
                                put("remote_acceptance", "NO")
                                put("requires_reconciliation", false)
                            }
                        )
                    )
                } else {
                    acceptedIds += records.map { it.jsonObject.getValue("id").toString() }
                    session(ProviderEvent.TextDelta(verdict(prompt)), ProviderEvent.Completed)
                }
            }, {}).streamRound(emptyList(), emptyList()).toList()
            assertTrue(reviews in 3..5)
            assertEquals(2, acceptedIds.size)
            assertEquals(ProviderEvent.Completed, output.last())
            assertEquals(2, evidence.snapshot().size)
        }
    }

    private fun localCapacityFailure() = dev.chungjungsoo.gptmobile.data.context.RequestCapacityException(
        "CONTEXT_CAPACITY_EXCEEDED",
        "Final request exceeded context",
        10000L,
        2500L,
        1536L,
        8192L,
        dev.chungjungsoo.gptmobile.data.context.TokenMeasurementKind.UTF8_ESTIMATE
    )

    @Test fun thrownLocalAdmissionDoesNotOverwriteAnEarlierUnknownAcceptedAttempt() = runTest {
        var reviews = 0
        val output = GitHubReviewedSession(session(ProviderEvent.TextDelta("Saved draft"), ProviderEvent.Completed), GitHubReviewEvidence(), "task", 8192, { _, _ ->
            reviews++
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = kotlinx.coroutines.flow.flow<ProviderEvent> {
                    emit(ProviderEvent.Failed("Prior response lost", errorCode = "GATEWAY_OUTCOME_UNKNOWN", invocationId = "uncertain", failureDetails = buildJsonObject { put("requires_reconciliation", true) }))
                    throw localCapacityFailure()
                }
            }
        }, {}).streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, reviews)
        assertFalse(output.any { it is ProviderEvent.TextDelta || it is ProviderEvent.Completed })
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", output.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
    }

    @Test fun repeatedExhaustionStopsAtABoundedPassCountWithAResumableCheckpoint() = runTest {
        val rows = mutableMapOf<String, JsonObject>()
        val checkpoint = GitHubReviewCheckpoint("task", { rows[it] }, { key, row -> rows[key] = row })
        val evidence = GitHubReviewEvidence().also {
            it.observe(ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap())), AgentToolResult("read", ToolResultContent.Text("e".repeat(20000)), false))
        }
        var requests = 0
        val output = GitHubReviewedSession(session(ProviderEvent.TextDelta("Saved draft"), ProviderEvent.Completed), evidence, "task", 8192, { _, _ ->
            requests++
            session(exhausted("review-$requests"))
        }, {}, checkpoint = checkpoint).streamRound(emptyList(), emptyList()).toList()
        assertTrue(requests in 2..32)
        assertFalse(output.any { it is ProviderEvent.TextDelta })
        assertEquals("Saved draft", checkpoint.load()?.draft)
        assertEquals(evidence.snapshot(), checkpoint.load()?.receipts)
        val failure = output.filterIsInstance<ProviderEvent.Failed>().last()
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("automatic_retry_allowed"))
        assertEquals(JsonPrimitive(true), failure.failureDetails?.get("user_resumable"))
    }

    @Test fun compatibilityCacheIgnoresTaskIdentityButInvalidatesModelTemplatePolicyAndCaps() = runTest {
        fun key(model: String = "model", template: String = "template", policy: String = "policy", cap: Int = 2048) = GitHubReviewCompatibility.revision("route", model, "profile", policy, "permission", "runtime", template, 24000, cap)
        val cache = GitHubReviewCompatibility()
        var probes = 0
        for ((index, revision) in listOf(key(), key(), key(model = "other"), key(template = "changed"), key(policy = "reasoning-on"), key(cap = 512)).withIndex()) {
            GitHubReviewedSession(session(ProviderEvent.TextDelta("The recorded check failed."), ProviderEvent.Completed), GitHubReviewEvidence(), "task-$index", 8192, { prompt, kind ->
                if (kind == "github-review-preflight") {
                    probes++
                    assertEquals(GitHubReviewCompatibility.BOUNDED_RETRY_PROMPT, prompt)
                }
                session(ProviderEvent.TextDelta(verdict(prompt)), ProviderEvent.Completed)
            }, {}, compatibility = cache, reviewerRevision = "task-policy-$index", compatibilityRevision = revision).streamRound(emptyList(), emptyList()).toList()
        }
        assertEquals(5, probes)
    }
}
