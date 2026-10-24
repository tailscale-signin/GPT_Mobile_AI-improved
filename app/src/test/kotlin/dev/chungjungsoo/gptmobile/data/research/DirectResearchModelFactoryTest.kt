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
    @Test fun unboundedHuggingFaceSpaceIsIneligibleForBoundedResearchBeforeGeneration() = runTest {
        val provider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.HUGGING_FACE
        val primary = PlatformV2(uid = "space", name = "Space", compatibleType = ClientType.FREE, apiUrl = provider.apiUrl, model = provider.model)
        val features = AppFeatureSettings()
        var invoked = 0
        val worker = DirectResearchModelFactory.create(primary, listOf(primary), features, ModelDelegationSettings(), "space-bounded", { listOf(primary) }, { features }, { true }, { _, _ -> false }, { _, _, _, _ ->
            invoked++
            error("An unenforceable output cap must not dispatch")
        })
        assertNull(worker.generate(ResearchModelStage.EXTRACT, "source", 512))
        assertEquals(0, invoked)
        assertEquals("SKIPPED", worker.events.single().outcome)
        assertEquals("OUTPUT_LIMIT_UNSUPPORTED", worker.events.single().reason)
        assertNull(worker.events.single().invocationId)
    }

    @Test fun freeProviderHealthBlocksResearchBeforeChildGenerationAndKeepsSkippedReceipts() = runTest {
        val provider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.BLOCKRUN
        val primary = PlatformV2(uid = "free-primary", name = "Free", compatibleType = ClientType.FREE, apiUrl = provider.apiUrl, model = provider.model)
        val features = AppFeatureSettings()
        dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.resetForTest()
        try {
            repeat(2) { dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.recordTerminalFailure(provider, provider.model, "remote generation failed") }
            var invoked = 0
            val worker = DirectResearchModelFactory.create(primary, listOf(primary), features, ModelDelegationSettings(), "free-preflight", { listOf(primary) }, { features }, { true }, { _, _ -> false }, { _, _, _, _ ->
                invoked++
                error("must not dispatch")
            })
            repeat(10) { assertNull(worker.generate(ResearchModelStage.EXTRACT, "source-$it", 512)) }
            assertEquals(0, invoked)
            assertTrue(worker.events.all { it.outcome == "SKIPPED" && it.reason == "PROVIDER_UNAVAILABLE" && it.failureCategory == "ADMISSION" })
        } finally {
            dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.resetForTest()
        }
    }

    @Test fun explicitLocalPermissionIsRequiredAndDenialFallsBackOnlyToPinnedPrimary() = runTest {
        val primary = PlatformV2(uid = "primary", name = "Primary", model = "remote")
        val local = PlatformV2(uid = "local", name = "Local", compatibleType = ClientType.LITERT_LM, model = "local")
        val profiles = listOf(primary, local)
        for (allowed in listOf(false, true)) {
            val policy = LocalAssistPolicy(shareDerivedEvidence = true, profileUid = local.uid, permissionVersion = 1, profilePermissions = mapOf(local.uid to if (allowed) AssistProcessingPermission.ALLOWED else AssistProcessingPermission.DENIED))
            val features = AppFeatureSettings(localAssist = true, localAssistPolicy = policy, executionPolicy = ExecutionPolicy(mode = OptimizationMode.OFF))
            val invoked = mutableListOf<String>()
            val statuses = mutableListOf<dev.chungjungsoo.gptmobile.data.assist.AssistTaskState>()
            val worker = DirectResearchModelFactory.create(primary, profiles, features, ModelDelegationSettings(), java.util.UUID.randomUUID().toString(), { profiles }, { features }, { true }, { _, _ -> true }, { target, _, _, _ ->
                PhysicalAttemptBoundary.dispatch()
                invoked += target.uid
                ResearchModelReply("""{"questions":[],"queries":[]}""", "", "inv")
            }, onAssistStatus = { statuses += it })
            assertNotNull(worker.generate(ResearchModelStage.PLAN, "task", 128))
            assertEquals(listOf(if (allowed) "local" else "primary"), invoked)
            if (allowed) {
                assertEquals(dev.chungjungsoo.gptmobile.data.assist.AssistDelivery.PRODUCED, statuses.last().delivery)
                assertFalse(statuses.any { it.delivery == dev.chungjungsoo.gptmobile.data.assist.AssistDelivery.VALIDATED })
            } else {
                assertTrue(statuses.isEmpty())
            }
        }
    }

    @Test fun preflightFailureDoesNotConsumeTheSharedPhysicalAttempt() = runTest {
        val primary = PlatformV2(uid = "primary", name = "Primary", model = "remote")
        val local = PlatformV2(uid = "local", name = "Local", compatibleType = ClientType.LITERT_LM, model = "local")
        val profiles = listOf(primary, local)
        val turn = java.util.UUID.randomUUID().toString()
        val policy = LocalAssistPolicy(shareDerivedEvidence = true, profileUid = local.uid, maxAttempts = 1, permissionVersion = 1, profilePermissions = mapOf(local.uid to AssistProcessingPermission.ALLOWED))
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

    @Test fun acceptedTemperatureAndModelOverlayDoesNotRevokeRegisteredAuthority() = runTest {
        val registered = PlatformV2(uid = "primary", name = "Primary", model = "saved", temperature = 0.2f)
        val requested = registered.copy(model = "accepted-override", temperature = 0.49f)
        val features = AppFeatureSettings()
        var live = registered
        val dispatched = mutableListOf<PlatformV2>()
        val worker = DirectResearchModelFactory.create(requested, listOf(registered), features, ModelDelegationSettings(), "overlay", { listOf(live) }, { features }, { true }, { _, _ -> false }, { target, _, _, _ ->
            dispatched += target
            ResearchModelReply("""{"questions":[],"queries":[]}""", "", "inv")
        })
        assertNotNull(worker.generate(ResearchModelStage.PLAN, "task", 128))
        assertEquals(listOf(requested), dispatched)
        live = registered.copy(enabled = false)
        assertNull(worker.generate(ResearchModelStage.EXTRACT, "task", 128))
        assertEquals(1, dispatched.size)
    }

    @Test fun displayOnlyChangeDoesNotRevokeAuthorityAndRejectionsStayTyped() = runTest {
        val registered = PlatformV2(uid = "primary", name = "Primary", model = "model")
        var live = registered.copy(name = "Renamed")
        val features = AppFeatureSettings()
        val worker = DirectResearchModelFactory.create(registered, listOf(registered), features, ModelDelegationSettings(), "typed", { listOf(live) }, { features }, { true }, { _, _ -> false }, { _, _, _, _ ->
            ResearchModelReply("""{"questions":[],"queries":[]}""", "model")
        })
        assertNotNull(worker.generate(ResearchModelStage.PLAN, "task", 128))
        live = live.copy(enabled = false)
        assertNull(worker.generate(ResearchModelStage.PLAN, "task", 128))
        assertEquals("PROFILE_DISABLED", worker.events.last().reason)
        live = registered.copy(apiUrl = "https://new.example/v1")
        assertNull(worker.generate(ResearchModelStage.PLAN, "task", 128))
        assertEquals("DESTINATION_CHANGED", worker.events.last().reason)
        live = registered
        assertNotNull(worker.generate(ResearchModelStage.PLAN, "task", 128))
        assertFalse(worker.events.any { it.reason == "MODEL_CIRCUIT_OPEN" })
    }

    @Test fun researchReviewerWorksWithDelegationAndLocalAssistOffAndRevocationStopsIt() = runTest {
        val primary = PlatformV2(uid = "primary", name = "Primary", model = "gpt-4.1")
        val reviewer = PlatformV2(uid = "reviewer", name = "Reviewer", model = "gpt-4o")
        val profiles = listOf(primary, reviewer)
        val features = AppFeatureSettings(localAssist = false, researchFeature = dev.chungjungsoo.gptmobile.data.research.feature.ResearchFeatureSettings(reviewerProfileUid = reviewer.uid))
        var live = features
        val invoked = mutableListOf<String>()
        val worker = DirectResearchModelFactory.create(primary, profiles, features, ModelDelegationSettings(enabled = false, reviewerEnabled = false), "review", { profiles }, { live }, { true }, { _, _ -> false }, { target, _, _, _ ->
            invoked += target.uid
            ResearchModelReply("""{"verdict":"Insufficient","relevance":"Relevant","questionId":"Q1"}""", target.model)
        })
        assertNotNull(worker.generate(ResearchModelStage.REVIEW, "evidence", 128, "author"))
        assertEquals(listOf(reviewer.uid), invoked)
        live = features.copy(researchFeature = features.researchFeature.copy(reviewerProfileUid = null))
        worker.generate(ResearchModelStage.REVIEW, "evidence", 128, "author")
        assertEquals(listOf(reviewer.uid, primary.uid), invoked)
        assertTrue(worker.events.any { it.profileUid == reviewer.uid && it.reason == "PERMISSION_REVOKED" })
    }
}
