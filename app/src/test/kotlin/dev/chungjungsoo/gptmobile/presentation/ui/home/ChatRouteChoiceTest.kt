package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRouteChoiceTest {
    val remote = PlatformV2(name = "Remote", uid = "r", model = "alpha", reasoning = true)
    val local = PlatformV2(name = "Local", uid = "l", model = "beta", compatibleType = ClientType.LITERT_LM)

    @Test fun choosesAmongEligibleProfilesAndKeepsResearchPrerequisites() {
        val candidates = listOf(remote, local)
        assertEquals(local, recommendedChatProfile(ChatRouteChoice.AUTO, candidates, emptyMap(), emptyMap()) { true })
        assertEquals(remote, recommendedChatProfile(ChatRouteChoice.THINK, candidates, emptyMap(), emptyMap()) { true })
        assertNull(recommendedChatProfile(ChatRouteChoice.RESEARCH, candidates, emptyMap(), emptyMap()) { false })
        val offline = mapOf(remote.uid to GatewayAvailability(GatewayAvailabilityStatus.OFFLINE))
        assertEquals(local, recommendedChatProfile(ChatRouteChoice.THINK, candidates, emptyMap(), offline) { true })
    }
}
