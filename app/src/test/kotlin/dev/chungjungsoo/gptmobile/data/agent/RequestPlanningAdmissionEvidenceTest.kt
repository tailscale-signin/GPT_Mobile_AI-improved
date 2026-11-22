package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import dev.chungjungsoo.gptmobile.data.context.CapacityProvenance
import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.ContextCostBreakdown
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.ReplayAllowance
import dev.chungjungsoo.gptmobile.data.context.RequestCapacityException
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.context.TokenMeasurementKind
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestPlanningAdmissionEvidenceTest {
    private val known = ProviderRouteCapacity.PrimaryContextDecision(5632, 8192, 5632, 16384, CapacityProvenance.VERIFIED_ROUTE)

    @Test fun conservativeOverflowKeepsExactGuardAndOwningHashesBeforeAnySchemaOrProviderInvocation() {
        val unknown = ProviderRouteCapacity.PrimaryContextDecision(Int.MAX_VALUE, null, null, null, CapacityProvenance.UNKNOWN)
        val settings = ReplayAllowance.conservativeToolPlanningBudget(TokenBudgetSettings(), toolEvidenceRequired = true)
        val turn = ConversationTurn(MessageV2(content = "x".repeat(1506), platformType = null), null, true)
        val system = "s".repeat(12195)
        val failure = assertThrows(RequestCapacityException::class.java) {
            withGitHubSchemaAdmissionEvidence("private-profile", "private-model", 4096, unknown, Int.MAX_VALUE) {
                ContextBudgetService.plan(listOf(turn), system, listOf(AgentToolDefinition("read", "Read", buildJsonObject {})), settings, planningProvenance = CapacityProvenance.CONSERVATIVE_POLICY)
            }
        }
        assertEquals("CONTEXT_CAPACITY_EXCEEDED", failure.code)
        assertEquals(4583L, failure.promptTokens)
        assertEquals(4096L, failure.capacityTokens)
        assertEquals(768L, failure.outputTokens)
        assertEquals(256L, failure.safetyTokens)
        assertEquals(512, failure.planningToolResultReserveTokens)
        assertTrue(failure.cause is RequestCapacityException)
        val fields = requireNotNull(providerExceptionDetails(failure, partialOutput = false)?.failureDetails)
        assertEquals(JsonPrimitive("CONSERVATIVE_POLICY"), fields["admission_capacity_provenance"])
        assertEquals(JsonPrimitive(4065), fields["admission_system_tokens"])
        assertEquals(JsonPrimitive(518), fields["admission_current_request_tokens"])
        assertEquals(JsonPrimitive(researchHash("private-profile")), fields["profile_hash"])
        assertEquals(JsonPrimitive(researchHash("private-model")), fields["model_hash"])
        assertEquals(JsonPrimitive(4096), fields["configured_output_limit"])
        assertEquals(JsonPrimitive(false), fields["dispatched"])
        assertEquals(JsonPrimitive(false), fields["retryable"])
        assertEquals(JsonPrimitive(false), fields["request_replay_allowed"])
        assertEquals(JsonPrimitive("REPLAN_REQUEST_BUDGET"), fields["recovery_action"])
        for (key in listOf("admission_route_context_tokens", "admission_catalog_context_tokens", "admission_configured_context_tokens")) assertFalse(key, key in fields)
        val report = requireNotNull(IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_800_000_000_000L).diagnostics)
        assertEquals(fields["profile_hash"], report.values["profile_hash"])
        assertFalse(report.toString().contains("private-profile"))
        assertFalse(report.toString().contains("private-model"))
        assertTrue(failure.message.orEmpty().contains("conservative planning allowance: 4096"))
        assertTrue(failure.message.orEmpty().contains("not a measured model limit"))
        assertTrue(failure.message.orEmpty().contains("lowering the output limit alone cannot make this input fit"))
    }

    @Test fun knownCapacityAndPartialOutputKeepCapturedSourcesWithoutRelabelingPriorDispatch() {
        val failure = assertThrows(RequestCapacityException::class.java) {
            withGitHubSchemaAdmissionEvidence("profile", "model", 1024, known, 5632) {
                ContextBudgetService.plan(emptyList(), "s".repeat(18000), emptyList(), TokenBudgetSettings(contextTokens = 5632, outputTokens = 512), planningProvenance = CapacityProvenance.VERIFIED_ROUTE)
            }
        }
        val fields = requireNotNull(providerExceptionDetails(failure, partialOutput = true)?.failureDetails)
        assertEquals(JsonPrimitive(5632), fields["admission_capacity_tokens"])
        assertEquals(JsonPrimitive(5632), fields["admission_route_context_tokens"])
        assertEquals(JsonPrimitive(8192), fields["admission_configured_context_tokens"])
        assertEquals(JsonPrimitive(16384), fields["admission_catalog_context_tokens"])
        assertEquals(JsonPrimitive("VERIFIED_ROUTE"), fields["admission_capacity_provenance"])
        assertEquals(JsonPrimitive(true), fields["partial_output_preserved"])
        for (key in listOf("dispatched", "dispatch_state", "remote_acceptance")) assertFalse(key, key in fields)
    }

    @Test fun mismatchedBoundaryCannotBorrowCapacityAndOuterWrappersCannotReplaceTheOwningIdentity() {
        val original = RequestCapacityException(
            "CONTEXT_CAPACITY_EXCEEDED",
            "Retained input",
            4583,
            256,
            768,
            4096,
            TokenMeasurementKind.UTF8_ESTIMATE,
            planningInput = RequestCapacityException.PlanningInput(ContextCostBreakdown(4065, 0, 518, 0, 0), CapacityProvenance.UNKNOWN)
        )
        val mismatched = assertThrows(RequestCapacityException::class.java) {
            withGitHubSchemaAdmissionEvidence("wrong-profile", "wrong-model", 1024, known, 5632) { throw original }
        }
        assertSame(original, mismatched)
        assertEquals(null, mismatched.planningObservation)

        val first = assertThrows(RequestCapacityException::class.java) {
            withGitHubSchemaAdmissionEvidence("first-profile", "first-model", 1024, known, 4096) { throw original }
        }
        val wrapped = assertThrows(RequestCapacityException::class.java) {
            withGitHubSchemaAdmissionEvidence("wrong-profile", "wrong-model", 1024, known, 4096) { throw first }
        }
        assertSame(first, wrapped)
        assertEquals(researchHash("first-profile"), wrapped.planningObservation?.profileHash)
        assertEquals(researchHash("first-model"), wrapped.planningObservation?.modelHash)
        assertEquals(4096L, wrapped.capacityTokens)
    }
}
