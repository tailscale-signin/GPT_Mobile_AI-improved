package dev.chungjungsoo.gptmobile.data.audit

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal class AppAuditAssertions {
    var count: Int = 0
        private set

    fun verify(condition: Boolean) {
        check(count < 2048)
        count++
        check(condition) { "App audit fixture assertion failed" }
    }
}

internal data class AppAuditFixture(
    val id: String,
    val label: String,
    val areas: List<String>,
    val exercise: suspend AppAuditAssertions.() -> Unit
)

/** Runs fixed, disposable inputs through production logic; never presents them as live service execution. */
internal abstract class AppAuditFixtureSource(
    final override val sourceId: String,
    private val fixtures: List<AppAuditFixture>
) : AppAuditProbeSource {
    override suspend fun plan(options: AppAuditOptions): List<AppAuditPlanEntry> = fixtures.map(::definition)

    override suspend fun execute(entry: AppAuditPlanEntry, context: AppAuditProbeContext): AppAuditObservation {
        context.ensureActive()
        val fixture = fixtures.singleOrNull { it.id == entry.probeId && entry == definition(it) }
            ?: return AppAuditObservation(AppAuditOutcome.BLOCKED, "AUDIT_PROBE_CHANGED", stage = "fixture")
        val assertions = AppAuditAssertions()
        return try {
            fixture.exercise(assertions)
            context.ensureActive()
            check(assertions.count > 0)
            observation(AppAuditOutcome.PASS, "LOCAL_CONTRACT_PASSED", assertions.count)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            observation(AppAuditOutcome.FAIL, "LOCAL_CONTRACT_FAILED", assertions.count)
        }
    }

    private fun definition(fixture: AppAuditFixture) = AppAuditPlanEntry(
        id = "$sourceId.${fixture.id}",
        sourceId = sourceId,
        probeId = fixture.id,
        componentId = fixture.areas.first(),
        label = fixture.label,
        sourceAreaIds = fixture.areas,
        evidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT,
        safetyClass = AppAuditSafetyClass.LOCAL_READ,
        fixtureHash = MessageDigest.getInstance("SHA-256").digest("$sourceId|${fixture.id}|fixture-v1".encodeToByteArray()).joinToString("") { "%02x".format(it) }
    )

    private fun observation(outcome: AppAuditOutcome, reason: String, assertions: Int) = AppAuditObservation(
        outcome = outcome,
        reasonCode = reason,
        stage = "fixture",
        evidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT,
        observationSource = AppAuditObservationSource.FIXTURE,
        dispatchEvidence = AppAuditDispatchEvidence.NOT_DISPATCHED,
        metrics = listOf(AppAuditMetric(AppAuditMetricName.CHECK_COUNT, assertions.toDouble()))
    )
}
