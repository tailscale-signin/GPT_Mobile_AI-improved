package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.chungjungsoo.gptmobile.data.audit.AppAuditFixtureEvidence
import dev.chungjungsoo.gptmobile.data.audit.AppAuditFixtureState
import dev.chungjungsoo.gptmobile.data.audit.AppAuditPrivacyEvidence
import dev.chungjungsoo.gptmobile.data.audit.safeAuditFixtureEvidence
import dev.chungjungsoo.gptmobile.data.audit.safeAuditProbe

@Composable
internal fun AppAuditFixtureDetails(evidence: AppAuditFixtureEvidence?, probeId: String) {
    val fixture = safeAuditFixtureEvidence(evidence) ?: return
    Text("Fixture status: ${fixture.status.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
    Text("Assertion state describes disposable local fixture work. Local tool dispatch and admission do not prove any network request.", style = MaterialTheme.typography.bodySmall)
    fixture.assertions.forEach { assertion ->
        Text(
            "${safeAuditProbe(probeId)}.assertion-${assertion.ordinal} · ${if (assertion.blocked) {
                "blocked"
            } else if (assertion.passed) {
                "passed"
            } else {
                "failed"
            }} · ${assertion.code.name}",
            style = MaterialTheme.typography.labelSmall
        )
        assertion.expected?.let { Text("Expected: ${appAuditFixtureStateText(it)}", style = MaterialTheme.typography.bodySmall) }
        assertion.observed?.let { Text("Observed: ${appAuditFixtureStateText(it)}", style = MaterialTheme.typography.bodySmall) }
    }
    val omitted = fixture.attempted - fixture.assertions.size
    if (omitted > 0) Text("$omitted earlier assertion details omitted by the retention limit; passed and attempted totals include them.", style = MaterialTheme.typography.bodySmall)
}

private fun appAuditFixtureStateText(state: AppAuditFixtureState): String = buildList {
    state.code?.let { add("code ${it.name}") }
    state.dispatched?.let { add("local dispatch $it") }
    state.admission?.let { add("local admission ${it.name}") }
    state.reservedCalls?.let { add("reserved calls $it") }
    state.usedCalls?.let { add("used calls $it") }
    state.remainingCalls?.let { add("remaining calls $it") }
    state.invocationCount?.let { add("invocations $it") }
    state.roundCount?.let { add("rounds $it") }
    state.completedCount?.let { add("completed $it") }
    state.failedCount?.let { add("failed $it") }
}.joinToString(" · ").ifEmpty { "No bounded state recorded" }

@Composable
internal fun AppAuditPrivacyDetails(policy: AppAuditPrivacyEvidence?) {
    if (policy == null) return
    Text(
        "Recorded privacy mode: ${when (policy.enabled) {
            true -> "enabled"
            false -> "disabled"
            null -> "not observed"
        }}",
        style = MaterialTheme.typography.bodySmall
    )
    Text("Privacy policy revision: ${policy.revision?.takeIf { it >= 0 } ?: "not recorded"}", style = MaterialTheme.typography.bodySmall)
    Text(
        when (policy.changedDuringProbe) {
            true -> "Privacy policy changed during this probe. Review the recorded phase and dispatch evidence before repeating it."
            false -> "Privacy policy revision was unchanged across this probe."
            null -> "Privacy policy continuity was not observed across the full probe."
        },
        style = MaterialTheme.typography.bodySmall
    )
}
