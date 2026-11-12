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
        }, {}, checkpoint = checkpoint).streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf("github-review", "github-review-output-retry"), roles)
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
        }, {}).streamRound(emptyList(), emptyList()).toList()
        assertEquals(4, reviews)
        assertEquals(ProviderEvent.Completed, output.last())
        assertEquals(2, evidence.snapshot().size)
    }

    @Test fun unknownRemoteOutcomeNeverUsesOutputRecoveryOrPublishesTheCandidate() = runTest {
        var reviews = 0
        val evidence = GitHubReviewEvidence()
        val output = GitHubReviewedSession(session(ProviderEvent.TextDelta("Saved draft"), ProviderEvent.Completed), evidence, "task", 8192, { _, _ ->
            reviews++
            session(exhausted("review-unknown", reconcile = true))
        }, {}).streamRound(emptyList(), emptyList()).toList()
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
                if (kind == "github-review-preflight") probes++
                session(ProviderEvent.TextDelta(verdict(prompt)), ProviderEvent.Completed)
            }, {}, compatibility = cache, reviewerRevision = "task-policy-$index", compatibilityRevision = revision).streamRound(emptyList(), emptyList()).toList()
        }
        assertEquals(5, probes)
    }
}
