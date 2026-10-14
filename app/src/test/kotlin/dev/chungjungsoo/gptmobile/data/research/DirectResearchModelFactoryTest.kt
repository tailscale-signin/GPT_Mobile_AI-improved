package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.assist.AssistProcessingPermission
import dev.chungjungsoo.gptmobile.data.assist.AssistTurnBudget
import dev.chungjungsoo.gptmobile.data.assist.LocalAssistPolicy
import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.orchestration.ExecutionPolicy
import dev.chungjungsoo.gptmobile.data.orchestration.OptimizationMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectResearchModelFactoryTest {
    @Test fun explicitLocalPermissionIsRequiredAndDenialFallsBackOnlyToPinnedPrimary() = runTest {
        val primary = PlatformV2(uid = "primary", name = "Primary", model = "remote")
        val local = PlatformV2(uid = "local", name = "Local", compatibleType = ClientType.LITERT_LM, model = "local")
        val profiles = listOf(primary, local)
        for (allowed in listOf(false, true)) {
            val policy = LocalAssistPolicy(permissionVersion = 1, profilePermissions = mapOf(local.uid to if (allowed) AssistProcessingPermission.ALLOWED else AssistProcessingPermission.DENIED))
            val features = AppFeatureSettings(localAssist = true, localAssistPolicy = policy, executionPolicy = ExecutionPolicy(mode = OptimizationMode.OFF))
            val invoked = mutableListOf<String>()
            val worker = DirectResearchModelFactory.create(primary, profiles, features, ModelDelegationSettings(), java.util.UUID.randomUUID().toString(), { profiles }, { features }, { true }, { _, _ -> true }, { target, _, _, _ ->
                PhysicalAttemptBoundary.dispatch()
                invoked += target.uid
                ResearchModelReply("""{"questions":[],"queries":[]}""", "", "inv")
            })
            assertNotNull(worker.generate(ResearchModelStage.PLAN, "task", 128))
            assertEquals(listOf(if (allowed) "local" else "primary"), invoked)
        }
    }

    @Test fun preflightFailureDoesNotConsumeTheSharedPhysicalAttempt() = runTest {
        val primary = PlatformV2(uid = "primary", name = "Primary", model = "remote")
        val local = PlatformV2(uid = "local", name = "Local", compatibleType = ClientType.LITERT_LM, model = "local")
        val profiles = listOf(primary, local)
        val turn = java.util.UUID.randomUUID().toString()
        val policy = LocalAssistPolicy(maxAttempts = 1, permissionVersion = 1, profilePermissions = mapOf(local.uid to AssistProcessingPermission.ALLOWED))
        val features = AppFeatureSettings(localAssist = true, localAssistPolicy = policy, executionPolicy = ExecutionPolicy(mode = OptimizationMode.OFF))
        var localRequests = 0
        val invoked = mutableListOf<String>()
        val worker = DirectResearchModelFactory.create(primary, profiles, features, ModelDelegationSettings(), turn, { profiles }, { features }, { true }, { _, _ -> true }, { target, _, _, _ ->
            invoked += target.uid
            if (target.uid == local.uid && localRequests++ == 0) error("preflight rejected before inference")
            PhysicalAttemptBoundary.dispatch()
            ResearchModelReply("""{"questions":[],"queries":[]}""", "", "inv")
        })
        assertNotNull(worker.generate(ResearchModelStage.PLAN, "task", 128))
        assertTrue(AssistTurnBudget.available(turn, 1))
        assertNotNull(worker.generate(ResearchModelStage.PLAN, "task", 128))
        assertFalse(AssistTurnBudget.available(turn, 1))
        assertNotNull(worker.generate(ResearchModelStage.PLAN, "task", 128))
        assertEquals(listOf("local", "primary", "local", "primary"), invoked)
    }

    @Test fun changedDestinationIsNotAuthorizedByItsOldUid() = runTest {
        val primary = PlatformV2(uid = "primary", name = "Primary", model = "remote")
        val features = AppFeatureSettings()
        var invoked = false
        val worker = DirectResearchModelFactory.create(primary, listOf(primary), features, ModelDelegationSettings(), "changed", { listOf(primary.copy(apiUrl = "https://changed.example/v1")) }, { features }, { true }, { _, _ -> false }, { _, _, _, _ ->
            invoked = true
            error("must not dispatch")
        })
        assertNull(worker.generate(ResearchModelStage.PLAN, "task", 128))
        assertFalse(invoked)
    }
}
