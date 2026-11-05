package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDelegationToolTest {
    private val source = PlatformV2(uid = "source", name = "Main", compatibleType = ClientType.OPENAI)
    private val target = PlatformV2(uid = "target", name = "Local", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.20:8080/v1")
    private val enabled = ModelDelegationSettings(enabled = true, processingOwnership = 50, targetProfileUid = target.uid, localPlatformsOnly = false, maxCallsPerTurn = 1)
    private val task = buildJsonObject { put("task", "Summarize this text") }

    @Test
    fun hardwareFallbackUsesOnlyApprovedDestinationsAndFixedSelectionCanWait() = runTest {
        val local = target.copy(uid = "on-device", compatibleType = ClientType.LITERT_LM, model = "local-fixture")
        val unapproved = target.copy(uid = "unapproved")
        val config = enabled.copy(targetProfileUid = local.uid, approvedWorkerUids = listOf(target.uid))
        val hardware = { dev.chungjungsoo.gptmobile.data.localruntime.DeviceHardwareState(thermalState = dev.chungjungsoo.gptmobile.data.localruntime.DeviceThermalState.SEVERE) }
        val dispatched = mutableListOf<String>()
        val tool = ModelDelegationTool(source, { config }, { listOf(unapproved, local, target) }, hardware) { profile, _, _ ->
            dispatched += profile.uid
            "Done"
        }
        assertFalse(tool.execute("approved-fallback", task).isError)
        assertEquals(listOf(target.uid), dispatched)
        val fixed = ModelDelegationTool(source, { config.copy(fallbackToAnotherProfile = false) }, { listOf(local, target) }, hardware) { _, _, _ -> error("Fixed local destination must wait") }
        assertTrue(fixed.execute("wait", task).isError)
    }

    @Test
    fun gitHubCapabilityRefusalAllowsDifferentEligibleHelper() = runTest {
        val fallback = target.copy(uid = "github-capable")
        val dispatched = mutableListOf<String>()
        val tool = ModelDelegationTool(source, { enabled }, { listOf(target, fallback) }) { profile, _, _ ->
            dispatched += profile.uid
            if (profile.uid == target.uid) {
                "No GitHub integration or remote repository write tools are enabled."
            } else {
                "Draft PR created."
            }
        }
        val arguments = buildJsonObject { put("task", "Create a draft PR in the repository") }
        assertTrue(tool.execute("refusal", arguments).isError)
        assertFalse(tool.execute("fallback", arguments).isError)
        assertEquals(listOf(target.uid, fallback.uid), dispatched)
    }

    @Test
    fun gitHubCapabilityRefusalIsAnErrorAndStopsRepeatedHelperCalls() = runTest {
        var calls = 0
        val tool = ModelDelegationTool(source, { enabled }, { listOf(target) }) { _, _, _ ->
            calls++
            "I cannot do it because **no GitHub integration, Git CLI, shell, or remote repository write tools are enabled**."
        }
        val arguments = buildJsonObject { put("task", "Create a draft PR in the repository") }
        val result = tool.execute("github-refusal", arguments)
        assertTrue(result.isError)
        assertTrue(result.content.toString().contains("primary model's enabled GitHub integration"))
        assertTrue(tool.execute("retry-refusal", arguments).isError)
        assertEquals(1, calls)
    }

    @Test
    fun onDeviceSourceCanDelegateToOllamaAndLlamaWhileThePhoneEngineIsBusy() = runTest {
        for (type in listOf(ClientType.OLLAMA, ClientType.LLAMA)) {
            var called = false
            val tool = ModelDelegationTool(source.copy(compatibleType = ClientType.LITERT_LM), { enabled }, { listOf(target.copy(compatibleType = type)) }) { _, _, _ ->
                called = true
                "Second opinion"
            }
            assertFalse(tool.execute("local-$type", task).isError)
            assertTrue(called)
        }
    }

    @Test
    fun delegatesToConfiguredProfileAndEnforcesPerTurnBudget() = runTest {
        var calls = 0
        val tool = ModelDelegationTool(source, { enabled }, { listOf(target) }) { p, text, tokens ->
            assertEquals(target.uid, p.uid)
            assertEquals("Summarize this text", text)
            assertEquals(512, tokens)
            calls++
            "Summary"
        }
        assertFalse(tool.execute("1", task).isError)
        assertTrue(tool.execute("2", task).isError)
        assertEquals(1, calls)
    }

    @Test
    fun blocksDisabledSelfCloudAndBusyOnDeviceTargets() = runTest {
        suspend fun blocked(s: PlatformV2, p: PlatformV2, config: ModelDelegationSettings) {
            var called = false
            val tool = ModelDelegationTool(s, { config }, { listOf(p) }) { _, _, _ ->
                called = true
                "unexpected"
            }
            assertTrue(tool.execute("1", task).isError)
            assertFalse(called)
        }
        blocked(source, target, enabled.copy(enabled = false))
        blocked(source, source, enabled.copy(targetProfileUid = source.uid))
        blocked(source, target.copy(enabled = false), enabled)
        blocked(source, target.copy(compatibleType = ClientType.GOOGLE, apiUrl = "https://generativelanguage.googleapis.com"), enabled.copy(localPlatformsOnly = true))
        blocked(source, target.copy(apiUrl = "https://public.example.com/v1"), enabled.copy(localPlatformsOnly = true))
        blocked(source.copy(compatibleType = ClientType.LITERT_LM), target.copy(compatibleType = ClientType.LITERT_LM), enabled)
    }

    @Test
    fun permitsNonFreeProviderTypesWhenConfiguredAndRejectsOversizedInput() = runTest {
        for (type in ClientType.entries.filterNot { it == ClientType.FREE }) {
            val tool = ModelDelegationTool(source, { enabled.copy(localPlatformsOnly = false) }, { listOf(target.copy(compatibleType = type)) }) { _, _, _ -> "OK" }
            assertFalse(type.name, tool.execute("1", task).isError)
        }
        val tool = ModelDelegationTool(source, { enabled }, { listOf(target) }) { _, _, _ -> error("Must not run") }
        assertTrue(tool.execute("1", buildJsonObject { put("task", "x".repeat(8001)) }).isError)
    }

    @Test
    fun conversationChoiceNeverSilentlyUsesAnotherHelper() = runTest {
        val config = dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings(true, "missing").applyTo(enabled)
        val tool = ModelDelegationTool(source, { config }, { listOf(target) }) { _, _, _ -> error("A different helper must not receive this task") }
        assertTrue(tool.execute("chat-choice", task).isError)
    }

    @Test
    fun staleConfiguredTargetFallsBackToEligibleHelper() = runTest {
        var selected: String? = null
        val tool = ModelDelegationTool(
            source,
            { enabled.copy(targetProfileUid = "missing") },
            { listOf(target) }
        ) { profile, _, _ ->
            selected = profile.uid
            "Recovered"
        }

        val result = tool.execute("stale-target", task)

        assertFalse(result.isError)
        assertEquals(target.uid, selected)
        assertEquals(JsonPrimitive("missing"), result.executionReceipt?.get("requested_profile_uid"))
        assertEquals(JsonPrimitive(target.uid), result.executionReceipt?.get("actual_profile_uid"))
        assertEquals(JsonPrimitive(true), result.executionReceipt?.get("fallback_used"))
        assertTrue(result.content.toString().contains("Approved fallback helper"))
    }

    @Test
    fun remoteWorkerOptInAllowsRemoteToRemoteDelegation() = runTest {
        val remote = target.copy(compatibleType = ClientType.OPENROUTER, apiUrl = "https://openrouter.ai/api/v1")
        var called = false
        val tool = ModelDelegationTool(
            source.copy(compatibleType = ClientType.OPENAI),
            { enabled.copy(allowRemoteWorkers = true, localPlatformsOnly = true) },
            { listOf(remote) }
        ) { profile, _, _ ->
            called = profile.uid == remote.uid
            "Remote result"
        }
        assertFalse(tool.execute("remote-remote", task).isError)
        assertTrue(called)
    }

    @Test
    fun freeTargetsNeverReceiveDelegatedContextEvenWhenCloudDelegationIsEnabled() = runTest {
        val targets = listOf(
            target.copy(compatibleType = ClientType.FREE),
            target.copy(compatibleType = ClientType.OPENROUTER, model = "model:free"),
            target.copy(compatibleType = ClientType.CUSTOM, model = "kilo-auto/free")
        )
        for (free in targets) {
            val tool = ModelDelegationTool(source, { enabled.copy(localPlatformsOnly = false) }, { listOf(free) }) { _, _, _ -> error("Free targets must not receive delegated context") }
            assertTrue(tool.execute("free", task).isError)
        }
    }

    @Test
    fun timesOutAndPropagatesParentCancellation() = runTest {
        val slow = ModelDelegationTool(
            source,
            {
                enabled.copy(
                    timeoutSeconds = 5,
                    maxDelegateRuntimeSeconds = 5,
                    maxLocalModelCalls = 1
                )
            },
            { listOf(target) }
        ) { _, _, _ ->
            // Make the per-attempt hard runtime explicit. The production coordinator
            // intentionally allows active tool work to continue up to that hard ceiling,
            // so a short legacy timeout alone must not shrink the mandatory retry window.
            delay(600_000)
            "late"
        }
        assertTrue(slow.execute("1", task).isError)
        val canceled = ModelDelegationTool(source, { enabled }, { listOf(target) }) { _, _, _ -> throw CancellationException("Stopped") }
        assertTrue(runCatching { canceled.execute("1", task) }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun failedDelegationRestoresExplicitCallBudgetForRetry() = runTest {
        var attempts = 0
        val tool = ModelDelegationTool(source, { enabled }, { listOf(target) }) { _, _, _ ->
            attempts++
            if (attempts == 1) error("temporary delegate failure")
            "Recovered"
        }

        assertTrue(tool.execute("first", task).isError)
        assertFalse(tool.execute("retry", task).isError)
        assertEquals(2, attempts)
    }

    @Test
    fun terminalNoResultStopsSameTaskRetriesWhileAllowingSmallerSubtasks() = runTest {
        var attempts = 0
        val tool = ModelDelegationTool(
            source,
            { enabled.copy(maxCallsPerTurn = 4) },
            { listOf(target) }
        ) { _, _, _ ->
            attempts++
            error("CANCELED_NO_RESULT: delegated model was unavailable, stalled, or its compute budget was reached.")
        }

        assertTrue(tool.execute("first", task).isError)
        assertTrue(tool.execute("second", task).isError)
        assertEquals(1, attempts)
        assertTrue(tool.execute("smaller", buildJsonObject { put("task", "Read only the missing paragraph") }).isError)
        assertEquals(2, attempts)
    }

    @Test
    fun primaryOnlyRecoveryIsAnErrorHandoffNotSuccessfulDelegation() = runTest {
        val tool = ModelDelegationTool(source, { enabled }, { listOf(target) }) { _, _, _ ->
            "Delegation was canceled. Continue this turn with the primary model only and do not call delegate_to_model again."
        }

        val result = tool.execute("primary-only", task)
        assertTrue(result.isError)
    }

    @Test
    fun localFirstOwnershipRaisesExplicitDelegationAllowance() = runTest {
        var attempts = 0
        val localFirst = enabled.copy(processingOwnership = 0, maxCallsPerTurn = 1)
        val tool = ModelDelegationTool(source, { localFirst }, { listOf(target) }) { _, _, _ ->
            attempts++
            "OK"
        }

        repeat(4) { index ->
            assertFalse(tool.execute("call-$index", task).isError)
        }
        assertTrue(tool.execute("call-5", task).isError)
        assertEquals(4, attempts)
    }

    @Test
    fun runtimeRechecksSettingsBeforeEveryCall() = runTest {
        var config = enabled.copy(maxCallsPerTurn = 3)
        var count = 0
        val tool = ModelDelegationTool(source, { config }, { listOf(target) }) { _, _, _ ->
            count++
            "OK"
        }
        assertFalse(tool.execute("1", task).isError)
        config = config.copy(enabled = false)
        assertTrue(tool.execute("2", task).isError)
        assertEquals(1, count)
    }

    @Test fun invalidArgumentsAndDisabledDelegationAreUndispatchedPrechecks() = runTest {
        var calls = 0
        val tool = ModelDelegationTool(source, { enabled.copy(enabled = false) }, { listOf(target) }) { _, _, _ ->
            calls++
            "unexpected"
        }
        val invalid = tool.execute(
            "invalid",
            buildJsonObject {
                put("task", "Read this")
                put("profile", "another")
            }
        )
        assertEquals("INVALID_ARGUMENTS", invalid.errorCode)
        assertFalse(invalid.dispatched)
        val disabled = tool.execute("disabled", task)
        assertEquals("DELEGATION_PRECHECK_REJECTED", disabled.errorCode)
        assertFalse(disabled.dispatched)
        assertEquals(0, calls)
    }

    @Test fun blankChildrenFailExplicitlyAndCannotLoopWhileOtherSubtasksCanSucceed() = runTest {
        var calls = 0
        val tool = ModelDelegationTool(source, { enabled.copy(maxCallsPerTurn = 3) }, { listOf(target) }) { _, text, _ ->
            calls++
            if (text == "Read the missing paragraph") "Useful evidence" else "   "
        }
        repeat(2) { index ->
            val result = tool.execute("empty-$index", task)
            assertEquals("EMPTY_OUTPUT", result.errorCode)
            assertTrue(result.dispatched)
            assertEquals(JsonPrimitive("FAILED"), result.executionReceipt?.get("status"))
        }
        val blocked = tool.execute("unchanged", task)
        assertEquals("DELEGATION_RETRY_EXHAUSTED", blocked.errorCode)
        assertFalse(blocked.dispatched)
        val useful = tool.execute("missing", buildJsonObject { put("task", "Read the missing paragraph") })
        assertFalse(useful.isError)
        assertTrue(useful.content.toString().contains("Useful evidence"))
        assertEquals(3, calls)
    }

    @Test fun totalAttemptAllowanceCannotBeRenewedByChangingFailedTasks() = runTest {
        var calls = 0
        val tool = ModelDelegationTool(source, { enabled }, { listOf(target) }) { _, _, _ ->
            calls++
            error("temporary failure")
        }
        repeat(2) { index -> assertTrue(tool.execute("attempt-$index", buildJsonObject { put("task", "Task $index") }).dispatched) }
        val blocked = tool.execute("third", buildJsonObject { put("task", "New task") })
        assertEquals("DELEGATION_ATTEMPT_LIMIT", blocked.errorCode)
        assertFalse(blocked.dispatched)
        assertEquals(2, calls)
    }

    @Test fun providerBudgetExhaustionStopsUnchangedRetryAndRetainsPartialEvidence() = runTest {
        var calls = 0
        val tool = ModelDelegationTool(source, { enabled }, { listOf(target) }) { _, _, _ ->
            calls++
            throw DelegateGenerationException(DelegateFailureKind.OUTPUT_LIMIT, "code=completion_repair_budget_exhausted", 20, 128, 148, false, partialText = "Confirmed source fact.")
        }
        val result = tool.execute("limited", task)
        assertEquals("OUTPUT_LIMIT", result.errorCode)
        assertTrue(result.content.toString().contains("Confirmed source fact."))
        assertTrue(result.content.toString().contains("unverified evidence"))
        assertTrue(result.content.toString().contains("unfinished"))
        assertEquals("DELEGATION_RETRY_EXHAUSTED", tool.execute("repeat", task).errorCode)
        assertEquals(1, calls)
    }

    @Test fun failedChildDoesNotSilentlyChangeThePinnedProfile() = runTest {
        val dispatched = mutableListOf<String>()
        val other = target.copy(uid = "other")
        val tool = ModelDelegationTool(source, { enabled.copy(fallbackToAnotherProfile = false) }, { listOf(target, other) }) { profile, _, _ ->
            dispatched += profile.uid
            throw DelegateGenerationException(DelegateFailureKind.AUTH_FAILURE, "HTTP 403", 0, 0, 0, false)
        }
        val failed = tool.execute("failed", task)
        assertEquals("AUTH_FAILURE", failed.errorCode)
        assertEquals(JsonPrimitive(target.uid), failed.executionReceipt?.get("actual_profile_uid"))
        assertEquals(JsonPrimitive(false), failed.executionReceipt?.get("fallback_used"))
        assertFalse(tool.execute("retry", task).dispatched)
        assertEquals(listOf(target.uid), dispatched)
    }

    @Test fun coordinatorRecoveryReportsTheActualApprovedHelperAndHonorsItsOutputCap() = runTest {
        val initial = target.copy(uid = "route-initial", model = "route-initial-model", maxTokens = 64)
        val recovery = target.copy(uid = "route-recovered", name = "Recovered helper", model = "route-recovery-model", maxTokens = 128)
        val policy = enabled.copy(targetProfileUid = initial.uid, researchEnabled = false, automaticResearch = false, reviewerEnabled = false, localRetryLimit = 0, maxLocalModelCalls = 4)
        val dispatches = mutableListOf<Pair<String, Int>>()
        val coordinator = LocalDelegationCoordinator(source, { policy }, { listOf(initial, recovery) }, { profile, _, cap ->
            dispatches += profile.uid to cap
            if (profile.uid == initial.uid) throw java.net.UnknownHostException("fixture unavailable")
            "Retained child evidence"
        })
        val tool = ModelDelegationTool(source, { policy }, { listOf(initial, recovery) }) { profile, text, cap -> coordinator.delegate(profile, text, cap, emptyList(), "route-recovery") }
        val result = tool.execute("route", task)
        assertFalse(result.isError)
        assertEquals(listOf(initial.uid to 64, recovery.uid to 128), dispatches)
        assertEquals(JsonPrimitive(recovery.uid), result.executionReceipt?.get("actual_profile_uid"))
        assertEquals(JsonPrimitive("COORDINATOR_RECOVERY"), result.executionReceipt?.get("routing_reason"))
        assertTrue(result.content.toString().contains("Approved fallback helper Recovered helper"))
        assertTrue(result.content.toString().contains("Retained child evidence"))
    }

    @Test fun unknownRemoteOutcomeStaysAnExplicitReconciliationReceipt() = runTest {
        var calls = 0
        val tool = ModelDelegationTool(source, { enabled }, { listOf(target) }) { _, _, _ ->
            calls++
            throw DelegateGenerationException(DelegateFailureKind.TRANSPORT_FAILURE, "GATEWAY_OUTCOME_UNKNOWN: saved job requires reconciliation", 0, 0, 0, true, partialText = "A child operation returned a receipt.")
        }
        val result = tool.execute("unknown", task)
        assertEquals("RECONCILIATION_REQUIRED", result.errorCode)
        assertEquals(JsonPrimitive("UNKNOWN_OUTCOME"), result.executionReceipt?.get("status"))
        assertTrue(result.content.toString().contains("remote outcome is unknown"))
        assertTrue(result.content.toString().contains("A child operation returned a receipt."))
        assertFalse(tool.execute("retry", task).dispatched)
        assertEquals(1, calls)
    }
}
