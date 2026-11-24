package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkEngineeringPlanTest {
    @Test fun counterbalancedQueuePreservesBothProfilesAcrossEveryRepeat() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val queue = BenchmarkEngineeringRegistry.queue(fixture.profiles, fixture.plan)
        assertEquals(listOf("a", "b", "b", "a", "a", "b"), queue.map { it.profile.uid })
        assertEquals(listOf(0, 0, 1, 1, 2, 2), queue.map { it.engineeringRepeatIndex })
        assertEquals(queue.size, queue.map { it.runId }.distinct().size)
        assertEquals((0..5).toList(), queue.map { it.ordinal })
        assertTrue(queue.all { it.profile.token == null })
        val selectedOrder = fixture.plan.copy(ordering = BenchmarkEngineeringOrdering.SELECTED_ORDER).frozen()
        assertEquals(listOf("a", "b", "a", "b", "a", "b"), BenchmarkEngineeringRegistry.queue(fixture.profiles, selectedOrder).map { it.profile.uid })
    }

    @Test fun frozenIdentityRejectsChangedBudgetsPermissionsAndRoutes() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val plan = fixture.plan
        assertTrue(plan.matches(fixture.cases, requireExternalReceipts = true))
        listOf(
            plan.copy(budget = plan.budget.copy(maxRepairAttempts = 0)),
            plan.copy(permissionsDigest = benchmarkHash("other permission")),
            plan.copy(externalRouteDigests = mapOf("a" to benchmarkHash("other"), "b" to benchmarkHash("route-b"))),
            plan.copy(externalAuthorityDigests = mapOf("a" to benchmarkHash("authority-a"), "b" to benchmarkHash("authority-b"))),
            plan.copy(taskPack = "repository")
        ).forEach {
            assertFalse(it.valid())
            assertFalse(it.frozen().cohortId == plan.cohortId)
        }
        assertEquals(plan.cohortId, plan.copy(externalSetupMs = 123, externalSetupRequestCount = 2).identityDigest())
    }

    @Test fun completeCaseIdentityIncludesSeedSchemaAndBudget() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val first = fixture.cases.first()
        for (changed in listOf(first.copy(seed = 400), first.copy(responseSchemaJson = "{\"type\":\"string\"}"), first.copy(dependsOn = setOf("another-task")), first.copy(engineeringBudget = fixture.budget.copy(outputTokensPerGeneration = 2048)))) {
            assertFalse(fixture.plan.matches(listOf(changed) + fixture.cases.drop(1)))
        }
        assertFalse(fixture.plan.matches(fixture.cases + first))
        val missingBinding = fixture.plan.copy(externalFixtureDigests = emptyMap()).frozen()
        assertTrue(missingBinding.matches(fixture.cases))
        assertFalse(missingBinding.matches(fixture.cases, requireExternalReceipts = true))
    }

    @Test fun executablePresetsDescribeActualIndependentTasks() {
        listOf("quick" to 12, "standard" to 36, "deep" to 100).forEach { (preset, count) ->
            val cases = BenchmarkEngineeringRegistry.cases(BenchmarkEngineeringLane.EXECUTABLE_CODING, preset)
            val preview = BenchmarkEngineeringRegistry.preview(BenchmarkEngineeringLane.EXECUTABLE_CODING, 3, preset)
            assertEquals(count, cases.size)
            assertEquals(cases.size, preview.taskCount)
            assertEquals(count * 3, preview.totalTasksPerModel)
            assertEquals(cases.map { it.engineeringFamilyId }.distinct().size, preview.taskFamilies)
        }
    }

    @Test fun genericModesAndSavedGitHubScopeRemainSeparate() {
        assertEquals(5, BenchmarkSuiteRegistry.cases(BenchmarkMode.QUICK, 0).size)
        assertEquals(50, BenchmarkSuiteRegistry.cases(BenchmarkMode.FULL, 0).size)
        val combined = BenchmarkSuiteRegistry.githubAuditCases(allActions = true)
        val contracts = BenchmarkEngineeringRegistry.cases(BenchmarkEngineeringLane.TOOL_CONTRACT)
        val workflows = BenchmarkEngineeringRegistry.cases(BenchmarkEngineeringLane.WORKFLOW_SIMULATION)
        assertEquals(contracts.size + workflows.size, combined.size)
        assertEquals(combined.map { it.id }, BenchmarkSuiteRegistry.cases(BenchmarkMode.GITHUB_INTEGRATION, 0).map { it.id })
        assertTrue(workflows.all { it.validatorVersion == "benchmark-validator-v8" })
        assertTrue(benchmarkSuiteForVersion(11, BenchmarkMode.GITHUB_INTEGRATION).filter { it.workload == GitHubEngineeringBenchmarkSuite.WORKLOAD }.all { it.validatorVersion == "benchmark-validator-v6" })
        val oldRun = BenchmarkEngineeringTestFixtures().runs().first().copy(engineeringPlan = null, suiteVersion = 10, caseManifest = contracts.take(2))
        assertEquals(contracts.take(2), benchmarkSuiteForRun(oldRun))
    }

    @Test fun frozenEngineeringBudgetIsBoundedWithoutMutatingSavedSamplingSettings() {
        val profile = BenchmarkEngineeringTestFixtures().profiles.first().copy(
            compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.OPENROUTER,
            temperature = .6f,
            stream = true,
            reasoning = true,
            maxTokens = 10000,
            openRouterRouting = "{\"stream\":false,\"max_tokens\":10000,\"temperature\":0.8,\"provider\":{\"sort\":\"latency\"}}"
        )
        val effective = benchmarkProfile(profile, true, "engineering_tools_output_4096", BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1)
        assertEquals(4096, effective.maxTokens)
        assertEquals(.6f, effective.temperature)
        assertTrue(effective.reasoning)
        val routing = Json.parseToJsonElement(effective.openRouterRouting!!).jsonObject
        assertEquals("false", routing.getValue("stream").jsonPrimitive.content)
        assertEquals("4096", routing.getValue("max_tokens").jsonPrimitive.content)
        assertEquals("latency", routing.getValue("provider").jsonObject.getValue("sort").jsonPrimitive.content)
        assertEquals(10000, profile.maxTokens)
        assertNull(benchmarkEngineeringOutputBudget("engineering_tools_output_999999"))
    }

    @Test fun settingsReceiptDistinguishesConfiguredOverrideAdapterReceiptAndObservedDelivery() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val profile = fixture.profiles.first().copy(
            compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.OPENROUTER,
            stream = true,
            openRouterRouting = "{\"stream\":false,\"private_account\":\"never export\"}"
        )
        val sample = fixture.sample(fixture.cases.first(), true).copy(
            deliveryMode = "buffered",
            effectiveSettingsConfirmed = false,
            adapterRequestSettings = mapOf("serializedOutputTokens" to "4096")
        )
        val receipt = benchmarkSettingsReceipt(profile, fixture.cases.first(), BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1, sample)
        assertTrue(receipt.profile.streaming)
        assertEquals("false", receipt.configuredProviderOverrides["stream"])
        assertEquals("4096", receipt.sent!!.fields["serializedOutputTokens"])
        assertNull(receipt.acknowledged)
        assertTrue(receipt.acknowledgedFields.isEmpty())
        assertEquals("buffered", receipt.observed!!.fields["deliveryMode"])
        assertEquals("false", receipt.observed.fields["settingsConfirmed"])
        assertFalse(receipt.configuredProviderOverrides.containsKey("private_account"))
    }

    @Test fun acceptedQueueRejectsAChangedProfileSnapshotOrDuplicateTrial() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val items = BenchmarkEngineeringRegistry.queue(fixture.profiles, fixture.plan)
        val batch = BenchmarkBatch(fixture.plan.id, BenchmarkMode.GITHUB_INTEGRATION, 0, items, executionPolicy = fixture.plan.executionPolicy, engineeringPlan = fixture.plan)
        assertTrue(benchmarkEngineeringQueueMatches(batch, fixture.plan))
        val changed = items.first().copy(profile = items.first().profile.copy(model = "changed-model"))
        assertFalse(benchmarkEngineeringQueueMatches(batch.copy(items = listOf(changed) + items.drop(1)), fixture.plan))
        assertFalse(benchmarkEngineeringQueueMatches(batch.copy(items = items + items.first()), fixture.plan))
        assertFalse(benchmarkEngineeringQueueMatches(batch.copy(items = items.reversed()), fixture.plan))
    }

    @Test fun externalShutdownEvidenceSurvivesTheLocalCleanupHandoff() {
        val fixture = BenchmarkEngineeringTestFixtures()
        val unknown = fixture.sample(fixture.cases.first(), false).copy(cleanupReceipt = "gateway:shutdown_unconfirmed")
        assertEquals("gateway:shutdown_unconfirmed", benchmarkEngineeringCleanupReceipt(true, unknown, "not_required"))
        assertEquals("local_confirmed", benchmarkEngineeringCleanupReceipt(false, unknown, "local_confirmed"))
    }
}
