package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditBehaviorProbeSourceTest {
    @Test fun benchmarkAuditRegressionsPassInBothConversationPrivacyModesWithoutChangingPolicy() = runBlocking {
        val failuresFromExport = setOf("budget_precheck_v1", "unknown_receipt_v1", "runner_isolation_v1")
        for (enabled in listOf(false, true)) {
            val privacy = ConversationPrivacyContext(chatId = 319, enabled = enabled)
            withContext(privacy) {
                val source = AppAuditBehaviorProbeSource()
                val entries = source.plan(AppAuditOptions()).filter { it.probeId in failuresFromExport }
                assertEquals(3, entries.size)
                for (entry in entries) {
                    val observed = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) { error("Local behavior fixtures must never use network admission") })
                    assertEquals("${entry.id}; privacy=$enabled", AppAuditOutcome.PASS, observed.outcome)
                    assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, observed.dispatchEvidence)
                    val assertions = checkNotNull(observed.fixtureEvidence)
                    assertTrue(assertions.attempted > 0)
                    assertEquals(assertions.attempted, assertions.passed)
                    assertTrue(assertions.assertions.all { it.passed && !it.blocked })
                    assertEquals(privacy, ConversationPrivacyContext.currentOrNull())
                    assertEquals(enabled, ConversationPrivacyContext.currentOrNull()?.enabled)
                }
            }
        }
    }

    @Test fun actualBudgetAuthorityReceiptAndRunnerContractsPassWithoutExternalDispatch() = runBlocking {
        val source = AppAuditBehaviorProbeSource()
        val plan = source.plan(AppAuditOptions())
        val expectedAssertions = mapOf("budget_precheck_v1" to 4, "authorization_denial_v1" to 2, "unknown_receipt_v1" to 2, "grant_scope_v1" to 5, "runner_isolation_v1" to 5)
        assertEquals(5, plan.size)
        for (entry in plan) {
            val observed = source.execute(entry, AppAuditProbeContext(AppAuditLimits()) { error("No network calls are authorized") })
            assertEquals(entry.id, AppAuditOutcome.PASS, observed.outcome)
            assertEquals(AppAuditObservationSource.FIXTURE, observed.observationSource)
            assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, observed.dispatchEvidence)
            assertTrue(observed.metrics.single().value > 0)
            val evidence = checkNotNull(observed.fixtureEvidence)
            assertEquals(expectedAssertions[entry.probeId], evidence.attempted)
            assertEquals(evidence.attempted, evidence.passed)
            assertTrue(evidence.assertions.none { it.code == AppAuditAssertionCode.FIXTURE_ASSERTION || it.blocked })
            assertEquals((1..evidence.attempted).toList(), evidence.assertions.map { it.ordinal })
        }
    }
}
