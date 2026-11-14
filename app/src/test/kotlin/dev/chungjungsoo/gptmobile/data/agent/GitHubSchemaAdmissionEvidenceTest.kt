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
import kotlinx.serialization.json.JsonPrimitive
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
        assertEquals(original.message, bound.message)
        val detail = requireNotNull(providerExceptionDetails(bound, partialOutput = false, nowMillis = 1_800_000_000_000L, monotonicMillis = 1000L))
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
    }

    @Test fun aFailureWithoutTheSameMeasuredPlanningBoundaryCannotInheritVerifiedProvenance() {
        for (failure in listOf(GitHubToolContextBudgetException(2481, 1675), GitHubToolContextBudgetException(2481, 1675, GitHubSchemaBudget(8192, 2485, 512, 256)))) {
            val bound = assertThrows(GitHubToolContextBudgetException::class.java) {
                withGitHubSchemaAdmissionEvidence("profile", "model", 4096, capacity, 5632) { throw failure }
            }
            val fields = requireNotNull(bound.providerFailure().failureDetails)
            assertEquals(JsonPrimitive("UNKNOWN"), fields["admission_capacity_provenance"])
            assertEquals(JsonPrimitive(5632), fields["admission_route_context_tokens"])
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
}
