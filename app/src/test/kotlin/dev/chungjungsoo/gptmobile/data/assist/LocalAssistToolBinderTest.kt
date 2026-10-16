package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAssistToolBinderTest {
    private val helper = PlatformV2(uid = "helper", name = "Helper", compatibleType = ClientType.LITERT_LM, model = "fixture")

    @Test fun planningRequiresOptInRuntimeAndLiveProcessingPermission() = runTest {
        suspend fun offered(optIn: Boolean = true, permitted: Boolean = true, runtime: Boolean = true, toolsAllowed: Boolean = true, researchOwns: Boolean = false) = LocalAssistToolBinder.bind(
            tools = emptyList(), candidates = listOf(helper), enabled = true,
            policy = LocalAssistPolicy(profileUid = helper.uid, perTurnQueryPlanning = optIn), goal = "goal", turnId = "turn",
            eligible = { true }, profileAllowed = { permitted }, generate = { _, _, _ -> error("Binding must not run inference") },
            notice = {}, stillEnabled = { true }, supportsPrimaryRuntime = runtime, allowPerTurnTool = toolsAllowed, workflowOwnsAssistance = researchOwns
        )
        assertTrue(offered(optIn = false).isEmpty())
        assertTrue(offered(permitted = false).isEmpty())
        assertTrue(offered(runtime = false).isEmpty())
        assertTrue(offered(toolsAllowed = false).isEmpty())
        assertTrue(offered(researchOwns = true).isEmpty())
        assertEquals("local_assist_plan", offered().single().modelToolName)
    }

    @Test fun planningUsesCurrentGoalAndReturnsValidatedProposalsWithoutSearching() = runTest {
        var calls = 0
        var admissions = 0
        val tools = LocalAssistToolBinder.bind(
            tools = emptyList(), candidates = listOf(helper), enabled = true,
            policy = LocalAssistPolicy(profileUid = helper.uid, perTurnQueryPlanning = true), goal = "original complete goal", turnId = java.util.UUID.randomUUID().toString(),
            eligible = { true }, profileAllowed = { true }, generate = { _, prompt, _ ->
                assertTrue(prompt.contains("original complete goal"))
                calls++
                """{"queries":["safe proposal"]}"""
            }, notice = {}, stillEnabled = { true }, allowPerTurnTool = true,
            admitPerTurnTool = {
                admissions++
                it
            }
        )
        val result = tools.single().tool.execute("plan", JsonObject(emptyMap()))
        assertEquals(1, admissions)
        assertEquals(1, calls)
        assertFalse(result.isError)
        assertEquals(1, result.assistMetadata.size)
        assertTrue(result.assistMetadata.single().containsKey("proposedQueries"))
        assertFalse(result.assistMetadata.single().containsKey("assistExtracts"))
    }
}
