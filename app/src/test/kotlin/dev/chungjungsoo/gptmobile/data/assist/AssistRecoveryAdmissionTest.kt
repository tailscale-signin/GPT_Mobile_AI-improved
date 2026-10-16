package dev.chungjungsoo.gptmobile.data.assist

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistRecoveryAdmissionTest {
    @Test fun onlyAutomaticIdlePreparationMayReplaceAMismatchedEngine() {
        assertTrue(canReplaceAssistEngine(AssistPreparation.PREPARE_AUTOMATICALLY, false, false))
        assertFalse(canReplaceAssistEngine(AssistPreparation.PREPARE_AUTOMATICALLY, true, false))
        assertFalse(canReplaceAssistEngine(AssistPreparation.PREPARE_AUTOMATICALLY, false, true))
        assertFalse(canReplaceAssistEngine(AssistPreparation.WARM_ONLY, false, false))
    }

    @Test fun workerAndOptionalResponseDeadlinesAreIndependent() {
        val policy = LocalAssistPolicy(taskTimeoutSeconds = 60, optionalWaitSeconds = 1).validated()
        assertEquals(60, policy.taskTimeoutSeconds)
        assertEquals(1, policy.optionalWaitSeconds)
        assertEquals(90, policy.copy(taskTimeoutSeconds = 1000).validated().taskTimeoutSeconds)
        assertFalse(policy.perTurnQueryPlanning)
    }

    @Test fun turnPlanningRejectsArgumentsAndDoesNotPretendToRetrieveEvidence() = runTest {
        val tool = AssistTurnPlanningTool("Original authorized question")
        assertTrue(tool.execute("bad", JsonObject(mapOf("override" to JsonPrimitive("different task")))).isError)
        val result = tool.execute("good", JsonObject(emptyMap()))
        assertFalse(result.isError)
        val data = (result.content as dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json).value as JsonObject
        assertEquals(JsonPrimitive("Original authorized question"), data["currentUserGoal"])
        assertTrue(data["limitation"].toString().contains("No source retrieval"))
        assertTrue(result.assistMetadata.isEmpty())
    }

    @Test fun telemetryExcludesFreeTextAndEvidence() {
        val line = AssistTelemetry.transition("run", AssistTaskState(taskId = "task", model = "private model", reason = "failed with /private/model.bin", selectedSpanIds = listOf("source text")))
        assertTrue(line.contains("reason=UNSPECIFIED"))
        assertFalse(line.contains("private"))
        assertFalse(line.contains("source text"))
        assertTrue(line.contains("selected=1"))
    }
}
