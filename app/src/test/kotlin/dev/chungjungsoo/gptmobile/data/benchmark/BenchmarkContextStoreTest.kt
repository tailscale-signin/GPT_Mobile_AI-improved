package dev.chungjungsoo.gptmobile.data.benchmark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BenchmarkContextStoreTest {
    @Before
    fun resetDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("benchmark_v2.db")
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun firstContextFreezePersistsSourceIdentitiesAndArmsAcrossRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (draft, prepared) = contextBatch()
        val store = BenchmarkStore(context)
        try {
            store.checkpoint(draft, event = "MANIFEST_SAVED")
            val accepted = requireNotNull(store.batch(draft.id))
            store.checkpoint(accepted.copy(engineeringPlan = prepared), event = "ENGINEERING_MANIFEST_VERIFIED")
            assertEquals(prepared, store.batch(draft.id)?.engineeringPlan)
        } finally {
            store.close()
        }
        val restored = BenchmarkStore(context)
        try {
            restored.load()
            val saved = requireNotNull(restored.batch(draft.id))
            assertEquals(prepared, saved.engineeringPlan)
            assertEquals(listOf("CURRENT", "TASK"), saved.items.map { it.engineeringContextVariant })
            assertTrue(benchmarkEngineeringQueueMatches(saved, prepared))
        } finally {
            restored.close()
        }
    }

    @Test
    fun laterCheckpointCannotReplaceFrozenContextManifestOrSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (draft, prepared) = contextBatch()
        val store = BenchmarkStore(context)
        try {
            store.checkpoint(draft, event = "MANIFEST_SAVED")
            val accepted = requireNotNull(store.batch(draft.id)).copy(engineeringPlan = prepared)
            store.checkpoint(accepted, event = "ENGINEERING_MANIFEST_VERIFIED")
            val task = prepared.contextManifestDigests.keys.single()
            val changedManifest = prepared.copy(contextManifestDigests = mapOf(task to benchmarkHash("different manifest"))).frozen()
            val changedSource = prepared.copy(
                contextSourceIdentities = mapOf(task to requireNotNull(prepared.contextSourceIdentities[task]).copy(sourceDigest = benchmarkHash("different source")))
            ).frozen()
            for (change in listOf(changedManifest, changedSource)) {
                assertTrue(change.valid())
                for (event in listOf("CHECKPOINT", "ENGINEERING_MANIFEST_VERIFIED")) {
                    assertTrue(runCatching { store.checkpoint(accepted.copy(engineeringPlan = change), event = event) }.isFailure)
                    assertEquals(prepared, store.batch(draft.id)?.engineeringPlan)
                }
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun checkpointCannotChangeAcceptedContextArmBeforeOrAfterFreeze() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (draft, prepared) = contextBatch()
        val store = BenchmarkStore(context)
        try {
            store.checkpoint(draft, event = "MANIFEST_SAVED")
            val accepted = requireNotNull(store.batch(draft.id))
            for (frozen in listOf(false, true)) {
                if (frozen) store.checkpoint(accepted.copy(engineeringPlan = prepared), event = "ENGINEERING_MANIFEST_VERIFIED")
                val before = requireNotNull(store.batch(draft.id))
                val switched = before.copy(items = before.items.mapIndexed { index, item -> if (index == 0) item.copy(engineeringContextVariant = "TASK") else item })
                assertTrue(runCatching { store.checkpoint(switched, event = "CASE_STARTED") }.isFailure)
                assertEquals(before.items, store.batch(draft.id)?.items)
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun preparationGuardRetainsAcceptedVariantBudgetProfileAndSourceScope() {
        val (draft, prepared) = contextBatch()
        val accepted = requireNotNull(draft.engineeringPlan)
        assertTrue(benchmarkPreparedPlanPreservesAcceptedScope(accepted, prepared))
        val changes = listOf(
            prepared.copy(contextExperiment = BenchmarkContextExperiment(listOf("CURRENT", "REFERENCE"))),
            prepared.copy(contextExperiment = requireNotNull(prepared.contextExperiment).copy(budgetBytes = 4096)),
            prepared.copy(budget = prepared.budget.copy(taskDeadlineMs = prepared.budget.taskDeadlineMs + 1000)),
            prepared.copy(profileConfigKeys = prepared.profileConfigKeys.mapValues { benchmarkHash("different model") }),
            prepared.copy(sourceCommit = "f".repeat(40)),
            prepared.copy(permissionsDigest = benchmarkHash("different permissions"))
        )
        changes.forEach { assertTrue(!benchmarkPreparedPlanPreservesAcceptedScope(accepted, it.frozen())) }
        assertTrue(benchmarkPreparedPlanPreservesAcceptedScope(prepared, prepared.copy(externalSetupMs = 200)))
        assertTrue(!benchmarkPreparedPlanPreservesAcceptedScope(prepared, prepared.copy(contextManifestDigests = prepared.contextManifestDigests.mapValues { benchmarkHash("changed context") }).frozen()))
        assertTrue(!benchmarkPreparedPlanPreservesAcceptedScope(prepared, prepared.copy(externalRouteDigests = prepared.externalRouteDigests.mapValues { benchmarkHash("changed route") }).frozen()))
    }

    private fun contextBatch(): Pair<BenchmarkBatch, BenchmarkEngineeringPlan> {
        val fixture = BenchmarkEngineeringTestFixtures(families = 1, repeats = 1)
        val profile = fixture.profiles.first()
        val task = requireNotNull(fixture.cases.single().engineeringTaskId)
        val plan = fixture.plan.copy(
            profileUids = listOf(profile.uid),
            profileConfigKeys = fixture.plan.profileConfigKeys.filterKeys { it == profile.uid },
            externalFixtureDigests = emptyMap(),
            graderDigest = null,
            externalRouteDigests = emptyMap(),
            contextExperiment = BenchmarkContextExperiment(listOf("CURRENT", "TASK"))
        ).frozen()
        val batch = BenchmarkBatch(
            plan.id,
            BenchmarkMode.GITHUB_INTEGRATION,
            1,
            BenchmarkEngineeringRegistry.queue(listOf(profile), plan),
            executionPolicy = plan.executionPolicy,
            caseManifest = fixture.cases,
            engineeringPlan = plan
        )
        val prepared = plan.copy(
            externalFixtureDigests = fixture.plan.externalFixtureDigests,
            graderDigest = fixture.plan.graderDigest,
            externalRouteDigests = fixture.plan.externalRouteDigests.filterKeys { it == profile.uid },
            contextManifestDigests = mapOf(task to benchmarkHash("public manifest")),
            contextSourceIdentities = mapOf(task to BenchmarkContextSourceIdentity(benchmarkHash("public source"), benchmarkHash("public reference"), 1, 2)),
            externalSetupMs = 20,
            externalSetupRequestCount = 2
        ).frozen()
        assertTrue(plan.valid())
        assertTrue(prepared.matches(fixture.cases, requireExternalReceipts = true))
        return batch to prepared
    }
}
