package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkQueueItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditModelBenchmarkBindingTest {
    @Test fun queueSerializationRetainsExactOriginWithoutChangingHistoricalEvidence() = runBlocking {
        val fixture = appAuditModelFixture()
        val original = Json.encodeToString(fixture.run)
        val batch = BenchmarkBatch("qualification", BenchmarkMode.QUICK, 10, listOf(BenchmarkQueueItem(0, fixture.profile, "run", "config", auditModelBinding = fixture.binding)))
        val restored = Json.decodeFromString<BenchmarkBatch>(Json.encodeToString(batch))
        assertEquals(fixture.binding, restored.items.single().auditModelBinding)
        assertTrue(fixture.binding.matches(fixture.run, fixture.profile))
        assertFalse(fixture.binding.matches(fixture.run.copy(id = "another-audit"), fixture.profile))
        assertFalse(fixture.binding.matches(fixture.run, fixture.profile.copy(model = "substitute")))
        assertEquals(original, Json.encodeToString(fixture.run))
        assertEquals(AppAuditOutcome.UNTESTED, fixture.run.results.last().outcome)
        assertEquals(0, appAuditScore(fixture.run).passedWeight)
    }

    @Test fun sameUidWithChangedCredentialsProfileOrPermissionCannotQualify() = runBlocking {
        val fixture = appAuditModelFixture()
        fun issue(current: AppAuditSelectedModelTarget?) = with(fixture.run) {
            appAuditModelBenchmarkIssue(fixture.binding, fixture.profile, this, current, sourceCommit, buildVersion, buildVariant, sourceInventoryDigest)
        }
        assertNull(issue(fixture.target))
        listOf(
            null, fixture.target.copy(configurationRevision = "b".repeat(64)), fixture.target.copy(configurationRevision = null),
            fixture.target.copy(fingerprint = "changed"), fixture.target.copy(profileSnapshotHash = "b".repeat(64)), fixture.target.copy(profileSnapshotHash = null), fixture.target.copy(enabled = false), fixture.target.copy(configured = false),
            fixture.target.copy(key = "model:replacement")
        ).forEach { assertEquals("AUDIT_MODEL_CONFIGURATION_CHANGED", issue(it)) }
    }

    @Test fun alteredPlanUnavailableConfigurationOrDifferentBuildCannotReuseBinding() = runBlocking {
        val fixture = appAuditModelFixture()
        val tampered = fixture.run.copy(plan = fixture.run.plan.map { it.copy(fixtureHash = "b".repeat(64)) })
        assertNull(appAuditModelBenchmarkBinding(tampered, tampered.plan.last(), fixture.profile))
        assertNull(appAuditModelBenchmarkBinding(fixture.run.copy(results = emptyList()), fixture.entry, fixture.profile))
        assertNull(appAuditModelBenchmarkBinding(fixture.run, fixture.entry.copy(selected = false), fixture.profile))
        assertEquals(
            "AUDIT_MODEL_BUILD_CHANGED",
            with(fixture.run) { appAuditModelBenchmarkIssue(fixture.binding, fixture.profile, this, fixture.target, "f".repeat(40), buildVersion, buildVariant, sourceInventoryDigest) }
        )
        assertEquals(
            "AUDIT_MODEL_BINDING_UNAVAILABLE",
            with(fixture.run) { appAuditModelBenchmarkIssue(fixture.binding, fixture.profile, null, fixture.target, sourceCommit, buildVersion, buildVariant, sourceInventoryDigest) }
        )
    }
}
