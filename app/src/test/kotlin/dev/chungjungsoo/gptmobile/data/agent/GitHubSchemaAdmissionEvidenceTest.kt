package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import dev.chungjungsoo.gptmobile.data.context.CapacityProvenance
import dev.chungjungsoo.gptmobile.data.context.ContextCostBreakdown
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.ProviderFailureReceipt
import dev.chungjungsoo.gptmobile.data.diagnostics.ProviderFailureReport
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSchemaAdmissionEvidenceTest {
    private val capacity = ProviderRouteCapacity.PrimaryContextDecision(5632, 8192, 5632, null, CapacityProvenance.VERIFIED_ROUTE)
    private fun rejection() = GitHubToolContextBudgetException(2481, 1675, GitHubSchemaBudget(5632, 2485, 512, 256, 704, ContextCostBreakdown(2000, 0, 485, 0, 0)))

    @Test fun preparationFailureKeepsExactAdmissionAndOwningIdentityThroughTheApprovedReport() {
        val original = rejection()
        val bound = assertThrows(GitHubToolContextBudgetException::class.java) {
            withGitHubSchemaAdmissionEvidence("private-profile", "private-model", 4096, capacity, 5632) { throw original }
        }
        assertSame(original, bound.cause)
        assertFalse(original.message == bound.message)
        assertTrue(bound.message.orEmpty().contains("verified for 5632 context tokens"))
        assertTrue(bound.message.orEmpty().contains("configured allowance is 8192 tokens"))
        val detail = requireNotNull(providerExceptionDetails(bound, partialOutput = false, nowMillis = 1_800_000_000_000L, monotonicMillis = 1000L))
        assertEquals(bound.message, detail.message)
        assertEquals("GITHUB_TOOL_CONTEXT_BUDGET", detail.code)
        val fields = requireNotNull(detail.failureDetails)
        assertEquals(JsonPrimitive(false), fields["dispatched"])
        assertEquals(JsonPrimitive(false), fields["retryable"])
        assertEquals(JsonPrimitive("NO"), fields["remote_acceptance"])
        assertEquals(JsonPrimitive(2481), fields["required_schema_tokens"])
        assertEquals(JsonPrimitive(1675), fields["available_schema_tokens"])
        assertEquals(JsonPrimitive(5632), fields["admission_capacity_tokens"])
        assertEquals(JsonPrimitive(5632), fields["admission_route_context_tokens"])
        assertEquals(JsonPrimitive(8192), fields["admission_configured_context_tokens"])
        assertEquals(JsonPrimitive("VERIFIED_ROUTE"), fields["admission_capacity_provenance"])
        assertEquals(JsonPrimitive(2000), fields["admission_system_tokens"])
        assertEquals(JsonPrimitive(485), fields["admission_current_request_tokens"])
        assertEquals(JsonPrimitive(0), fields["admission_history_tokens"])
        assertEquals(JsonPrimitive(0), fields["admission_attachment_reserve_tokens"])
        assertEquals(JsonPrimitive(researchHash("private-profile")), fields["profile_hash"])
        assertEquals(JsonPrimitive(researchHash("private-model")), fields["model_hash"])
        val diagnostics = requireNotNull(IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_800_000_000_000L).diagnostics)
        assertEquals(JsonPrimitive(4096), diagnostics.values["configured_output_limit"])
        assertEquals(JsonPrimitive(2000), diagnostics.values["admission_system_tokens"])
        assertTrue(diagnostics.requiredServiceFeatures().containsAll(setOf("CAPACITY_ADMISSION_EVIDENCE_V1", "GITHUB_SCHEMA_BUDGET_EVIDENCE_V1")))
        assertFalse(diagnostics.toString().contains("private-profile"))
        assertFalse(diagnostics.toString().contains("private-model"))
        assertFalse(fields.containsKey("invocation_id"))
        assertFalse(fields.containsKey("request_replay_allowed"))
        val profile = PlatformV2(uid = "private-profile", name = "Private", model = "private-model", compatibleType = ClientType.LLAMA)
        val failure = bound.providerFailure()
        val projected = ProviderFailureReport.fields("run", failure, ProviderFailureReceipt.from(profile, "primary", failure))
        for (key in listOf("admission_system_tokens", "admission_current_request_tokens", "admission_history_tokens", "admission_attachment_reserve_tokens", "admission_route_context_tokens", "admission_configured_context_tokens", "admission_capacity_provenance", "required_schema_tokens", "available_schema_tokens")) assertEquals(key, fields[key], projected[key])
    }

    @Test fun aSmallerLocalPlanningLimitDoesNotRelabelItAsTheVerifiedRemoteCapacity() {
        val bound = assertThrows(GitHubToolContextBudgetException::class.java) {
            withGitHubSchemaAdmissionEvidence("profile", "model", null, capacity, 4096) { throw GitHubToolContextBudgetException(2481, 139, GitHubSchemaBudget(4096, 2485, 512, 256, 704)) }
        }
        val fields = requireNotNull(bound.providerFailure().failureDetails)
        assertEquals(JsonPrimitive(4096), fields["admission_configured_context_tokens"])
        assertEquals(JsonPrimitive(5632), fields["admission_route_context_tokens"])
        assertEquals(JsonPrimitive("DECLARED"), fields["admission_capacity_provenance"])
        assertEquals(JsonPrimitive(4096), fields["admission_capacity_tokens"])
        assertTrue(bound.message.orEmpty().contains("context allowance of 4096 tokens"))
        assertFalse(bound.message.orEmpty().contains("route was verified"))
    }

    @Test fun missingAndUnboundedObservationsAreAbsentWhileMeasuredZeroRemainsZero() {
        val unknown = ProviderRouteCapacity.PrimaryContextDecision(Int.MAX_VALUE, null, null, null, CapacityProvenance.UNKNOWN)
        val bound = assertThrows(GitHubToolContextBudgetException::class.java) {
            withGitHubSchemaAdmissionEvidence("", "", 0, unknown, Int.MAX_VALUE) { throw GitHubToolContextBudgetException(5, 0) }
        }
        val fields = requireNotNull(bound.providerFailure().failureDetails)
        for (key in listOf("profile_hash", "model_hash", "admission_route_context_tokens", "admission_catalog_context_tokens", "admission_configured_context_tokens", "admission_system_tokens", "admission_history_tokens")) assertFalse(key, key in fields)
        assertEquals(JsonPrimitive(0), fields["configured_output_limit"])
        assertEquals(JsonPrimitive("UNKNOWN"), fields["admission_capacity_provenance"])
        assertEquals(JsonPrimitive(0), fields["available_schema_tokens"])
    }

    @Test fun aLaterRejectionDoesNotProveEarlierGenerationWasUnsentOrReplayable() {
        val bound = assertThrows(GitHubToolContextBudgetException::class.java) {
            withGitHubSchemaAdmissionEvidence("profile", "model", 4096, capacity, 5632) { throw rejection() }
        }
        val fields = requireNotNull(providerExceptionDetails(bound, partialOutput = true, nowMillis = 1_800_000_000_000L, monotonicMillis = 1000L)?.failureDetails)
        assertEquals(JsonPrimitive(true), fields["partial_output_preserved"])
        assertEquals(JsonPrimitive("NOT_DISPATCHED"), fields["attempt_dispatch_state"])
        for (key in listOf("dispatched", "remote_acceptance", "dispatch_state", "request_replay_allowed", "invocation_id")) assertFalse(key, key in fields)
        assertEquals(JsonPrimitive(5632), fields["admission_route_context_tokens"])
    }

    @Test fun successfulPlansAndUnrelatedFailuresAreReturnedUnchanged() {
        val result = Any()
        assertSame(result, withGitHubSchemaAdmissionEvidence("profile", "model", 4096, capacity, 5632) { result })
        val unrelated = IllegalStateException("private failure")
        assertSame(unrelated, assertThrows(IllegalStateException::class.java) { withGitHubSchemaAdmissionEvidence("profile", "model", 4096, capacity, 5632) { throw unrelated } })
    }

    @Test fun laterCapacityChangesCannotRelabelTheCapturedPlanningDecision() {
        var current = capacity
        val bound = assertThrows(GitHubToolContextBudgetException::class.java) {
            withGitHubSchemaAdmissionEvidence("profile", "model", 4096, current, 5632) {
                current = current.copy(tokens = 32768, configuredTokens = 65536, verifiedRouteTokens = 32768)
                throw rejection()
            }
        }
        val fields = requireNotNull(bound.providerFailure().failureDetails)
        assertEquals(32768, current.tokens)
        assertEquals(JsonPrimitive(5632), fields["admission_route_context_tokens"])
        assertEquals(JsonPrimitive(8192), fields["admission_configured_context_tokens"])
        assertEquals(JsonPrimitive("VERIFIED_ROUTE"), fields["admission_capacity_provenance"])
        assertTrue(bound.message.orEmpty().contains("verified for 5632 context tokens"))
        assertFalse(bound.message.orEmpty().contains("32768"))
        assertFalse(bound.message.orEmpty().contains("65536"))
    }

    @Test fun aFailureWithoutTheSameMeasuredPlanningBoundaryCannotInheritVerifiedProvenance() {
        for (failure in listOf(GitHubToolContextBudgetException(2481, 1675), GitHubToolContextBudgetException(2481, 1675, GitHubSchemaBudget(8192, 2485, 512, 256)))) {
            val bound = assertThrows(GitHubToolContextBudgetException::class.java) {
                withGitHubSchemaAdmissionEvidence("profile", "model", 4096, capacity, 5632) { throw failure }
            }
            val fields = requireNotNull(bound.providerFailure().failureDetails)
            assertEquals(JsonPrimitive("UNKNOWN"), fields["admission_capacity_provenance"])
            assertEquals(JsonPrimitive(5632), fields["admission_route_context_tokens"])
            assertFalse(bound.message.orEmpty().contains("route was verified"))
        }
    }

    @Test fun aChangedOrOversizedObservationCannotInventAVerifiedCapacity() {
        val oversized = capacity.copy(configuredTokens = Int.MAX_VALUE, verifiedRouteTokens = Int.MAX_VALUE, catalogTokens = Int.MAX_VALUE)
        val bound = assertThrows(GitHubToolContextBudgetException::class.java) {
            withGitHubSchemaAdmissionEvidence("profile", "model", Int.MAX_VALUE, oversized, 8192) { throw rejection() }
        }
        val fields = requireNotNull(bound.providerFailure().failureDetails)
        assertEquals(JsonPrimitive("UNKNOWN"), fields["admission_capacity_provenance"])
        for (key in listOf("configured_output_limit", "admission_route_context_tokens", "admission_catalog_context_tokens", "admission_configured_context_tokens")) assertFalse(key, key in fields)
    }

    @Test fun clampedZeroSchemaRoomIncludesTheAlreadyOvercommittedPrompt() {
        val budget = GitHubSchemaBudget(4096, 2743, 768, 256, 512)
        assertEquals(0L, budget.available)
        assertTrue(GitHubToolContextBudgetException(4403, budget.available, budget).message.orEmpty().contains("short by 4586 tokens"))
    }

    @Test fun measuredRouteShortfallExplainsWhyTheAppAllowanceAndReplyAloneCannotRecover() {
        val budget = GitHubSchemaBudget(5632, 2485, 512, 256, 704, ContextCostBreakdown(2394, 0, 91, 0, 0))
        val original = GitHubToolContextBudgetException(2481, budget.available, budget)
        val bound = assertThrows(GitHubToolContextBudgetException::class.java) {
            withGitHubSchemaAdmissionEvidence("private-profile", "private-model", 0, capacity.copy(configuredTokens = 1_048_576), 5632) { throw original }
        }
        val message = bound.providerFailure().message
        assertTrue(message.contains("verified for 5632 context tokens"))
        assertTrue(message.contains("configured allowance is 1048576 tokens"))
        assertTrue(message.contains("2485 for input, 512 for the reply"))
        assertTrue(message.contains("short by 806 tokens"))
        assertTrue(message.contains("reply allowance alone cannot cover"))
        assertTrue(message.contains("start a new request"))
        for (unobserved in listOf("history", "attachments", "6438", "private-profile", "private-model")) assertFalse(unobserved, message.contains(unobserved))
        assertEquals(1675L, budget.available)
        assertEquals(original.requiredSchemaTokens, bound.requiredSchemaTokens)
        assertEquals(original.availableSchemaTokens, bound.availableSchemaTokens)
        for ((key, value) in requireNotNull(original.providerFailure().failureDetails)) assertEquals(key, value, bound.providerFailure().failureDetails?.get(key))
        assertSame(original, bound.cause)
    }

    @Test fun malformedOrUnboundCapacityCannotCreateVerifiedRecoveryAdvice() {
        val evidence = buildJsonObject {
            put("admission_capacity_provenance", "VERIFIED_ROUTE")
            put("admission_route_context_tokens", 5632)
            put("admission_configured_context_tokens", 1_048_576)
        }
        for (invalid in listOf(JsonNull, JsonPrimitive(true), JsonPrimitive("5632"), JsonPrimitive(5632.5), JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive(Int.MAX_VALUE), JsonPrimitive(8192))) {
            val message = rejection().withAdmissionEvidence(JsonObject(evidence + ("admission_route_context_tokens" to invalid))).message.orEmpty()
            assertFalse(invalid.toString(), message.contains("route was verified"))
            assertFalse(message.contains("configured allowance"))
        }
        for (provenance in listOf("UNKNOWN", "DECLARED", "CONSERVATIVE_POLICY")) {
            assertFalse(rejection().withAdmissionEvidence(JsonObject(evidence + ("admission_capacity_provenance" to JsonPrimitive(provenance)))).message.orEmpty().contains("route was verified"))
        }
        assertFalse(GitHubToolContextBudgetException(2481, 1675).withAdmissionEvidence(evidence).message.orEmpty().contains("route was verified"))
        val invalidConfigured = rejection().withAdmissionEvidence(JsonObject(evidence + ("admission_configured_context_tokens" to JsonPrimitive("private-setting"))))
        assertTrue(invalidConfigured.message.orEmpty().contains("verified for 5632 context tokens"))
        assertFalse(invalidConfigured.message.orEmpty().contains("configured allowance"))
        assertFalse(invalidConfigured.message.orEmpty().contains("private-setting"))
    }

    @Test fun recoveryAdviceUsesOnlyIncludedPromptCostsAndTheChargedReplyReserve() {
        val measured = ContextCostBreakdown(10, 0, 20, 30, 40)
        val budget = GitHubSchemaBudget(1000, 100, 100, 10, 0, measured)
        fun rejection(costs: ContextCostBreakdown?) = GitHubToolContextBudgetException(1000, budget.available, budget.copy(promptCosts = costs))
            .withAdmissionEvidence(buildJsonObject { put("configured_output_limit", 4096) })
        val included = rejection(measured)
        assertTrue(included.message.orEmpty().contains("100 for the reply"))
        assertFalse(included.message.orEmpty().contains("4096"))
        assertTrue(included.message.orEmpty().contains("explicitly reduce instructions, request input, included history, attachments"))
        for (unavailable in listOf(null, ContextCostBreakdown(0, 0, 0, 0, 0), measured.copy(attachments = -1), measured.copy(toolSchemas = 1))) {
            val failure = rejection(unavailable)
            assertFalse(failure.message.orEmpty().contains("explicitly reduce"))
            assertFalse(failure.message.orEmpty().contains("history"))
            assertFalse(failure.message.orEmpty().contains("attachments"))
            assertEquals(100L, budget.input)
            assertEquals(JsonPrimitive(100), failure.providerFailure().failureDetails?.get("admission_output_tokens"))
        }
    }
}
