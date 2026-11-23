package dev.chungjungsoo.gptmobile.data.audit

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal class AppAuditAssertions {
    var count: Int = 0
        private set
    var passed: Int = 0
        private set
    private val observations = ArrayDeque<AppAuditAssertionEvidence>()

    fun verify(condition: Boolean, code: AppAuditAssertionCode = AppAuditAssertionCode.FIXTURE_ASSERTION, expected: AppAuditFixtureState? = null, observed: AppAuditFixtureState? = null) {
        val policyBlocked = observed?.code in setOf(AppAuditFixtureCode.PRIVACY_EXTERNAL_TOOL_BLOCKED, AppAuditFixtureCode.PRIVACY_SERVICE_UNVERIFIED) && expected?.code != observed?.code
        check(count < 2048)
        count++
        if (condition && !policyBlocked) passed++
        if (observations.size == 64) observations.removeFirst()
        observations.addLast(AppAuditAssertionEvidence(count, code, condition && !policyBlocked, expected?.safe(), observed?.safe(), blocked = policyBlocked))
        if (policyBlocked) throw AppAuditFixturePolicyBlocked()
        if (!condition) throw AppAuditFixtureAssertionFailed()
    }

    fun evidence(status: AppAuditFixtureStatus) = AppAuditFixtureEvidence(status, count, passed, observations.toList())
}

private class AppAuditFixtureAssertionFailed : IllegalStateException("App audit fixture assertion failed")
private class AppAuditFixturePolicyBlocked : IllegalStateException("App audit fixture policy prerequisite unavailable")
private class AppAuditFixtureSetupFailed : IllegalStateException("App audit fixture has no assertions")

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
            ?: return AppAuditObservation(AppAuditOutcome.BLOCKED, "AUDIT_PROBE_CHANGED", stage = "fixture", evidenceKind = entry.evidenceKind, configChanged = true)
        val assertions = AppAuditAssertions()
        return try {
            fixture.exercise(assertions)
            context.ensureActive()
            if (assertions.count == 0) throw AppAuditFixtureSetupFailed()
            observation(AppAuditOutcome.PASS, "LOCAL_CONTRACT_PASSED", assertions, AppAuditFixtureStatus.EXECUTED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AppAuditFixtureAssertionFailed) {
            observation(AppAuditOutcome.FAIL, "LOCAL_CONTRACT_FAILED", assertions, AppAuditFixtureStatus.EXECUTED)
        } catch (_: AppAuditFixtureSetupFailed) {
            observation(AppAuditOutcome.BLOCKED, "LOCAL_FIXTURE_SETUP_FAILED", assertions, AppAuditFixtureStatus.SETUP_BLOCKED)
                .copy(stage = "fixture_setup", failurePhase = "fixture_setup", failureCategory = "FIXTURE_SETUP_FAILED", retryable = false)
        } catch (failure: Exception) {
            val policyBlocked = generateSequence<Throwable>(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(8)
                .any { it is AppAuditFixturePolicyBlocked || it is dev.chungjungsoo.gptmobile.data.privacy.UnverifiedServiceTransport }
            if (policyBlocked) {
                observation(AppAuditOutcome.BLOCKED, "LOCAL_FIXTURE_POLICY_BLOCKED", assertions, AppAuditFixtureStatus.SETUP_BLOCKED)
                    .copy(stage = "fixture_setup", failurePhase = "local_validation", failureCategory = "POLICY_BLOCKED", retryable = false)
            } else {
                observation(AppAuditOutcome.FAIL, "LOCAL_FIXTURE_EXECUTION_FAILED", assertions, AppAuditFixtureStatus.EXECUTION_FAILED)
                    .copy(failurePhase = "fixture", failureCategory = "FIXTURE_EXECUTION_FAILED", retryable = false)
            }
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
        // Bump this fixture contract revision when assertion identity or meaning changes.
        fixtureHash = MessageDigest.getInstance("SHA-256").digest("$sourceId|${fixture.id}|fixture-v2".encodeToByteArray()).joinToString("") { "%02x".format(it) }
    )

    private fun observation(outcome: AppAuditOutcome, reason: String, assertions: AppAuditAssertions, status: AppAuditFixtureStatus) = AppAuditObservation(
        outcome = outcome,
        reasonCode = reason,
        stage = "fixture",
        evidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT,
        observationSource = AppAuditObservationSource.FIXTURE,
        dispatchEvidence = AppAuditDispatchEvidence.NOT_DISPATCHED,
        metrics = listOf(AppAuditMetric(AppAuditMetricName.CHECK_COUNT, assertions.count.toDouble())),
        fixtureEvidence = assertions.evidence(status)
    )
}
