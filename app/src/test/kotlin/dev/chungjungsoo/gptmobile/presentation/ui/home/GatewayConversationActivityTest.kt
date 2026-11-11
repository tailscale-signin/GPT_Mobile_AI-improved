package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.github.GatewayConversationSnapshot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayConversationActivityTest {
    private val snapshot = GatewayConversationSnapshot(gatewayId = "gateway", principalId = "principal", conversationId = "conversation", activeJobId = "saved-job")

    @Test fun retainedAndLegacyJobIdsDoNotMeanThatAConversationIsStillWorking() {
        assertFalse(snapshot.isWorking())
        for (state in listOf("COMPLETED", "FAILED", "CANCELLED", "AWAITING_APPROVAL", "BLOCKED", "QUEUED", "UNKNOWN")) {
            assertFalse(state, snapshot.copy(jobState = state).isWorking())
        }
        for (state in listOf("RUNNING", "GENERATING", "IMPLEMENT", "VERIFY")) {
            assertTrue(state, snapshot.copy(jobState = state).isWorking())
        }
        assertFalse(snapshot.copy(activeJobId = null, jobState = "RUNNING").isWorking())
    }

    @Test fun version16StateDecodesWithoutRequiringItFromVersion15() {
        val old = """{"gateway_id":"gateway","principal_id":"principal","conversation_id":"conversation","active_job_id":"saved-job"}"""
        assertFalse(Json.decodeFromString<GatewayConversationSnapshot>(old).isWorking())
        val current = old.dropLast(1) + """, "job_state":"RUNNING"}"""
        assertTrue(Json.decodeFromString<GatewayConversationSnapshot>(current).isWorking())
    }

    @Test fun visibleWorkUsesOneResponsivePollAndFailuresBackOff() {
        assertEquals(10_000L, gatewayConversationRefreshDelay(true, 0))
        assertEquals(120_000L, gatewayConversationRefreshDelay(false, 0))
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 120_000L), (1..4).map { gatewayConversationRefreshDelay(true, it) })
    }
}
