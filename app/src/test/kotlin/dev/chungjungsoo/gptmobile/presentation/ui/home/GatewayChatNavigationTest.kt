package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayChatNavigationTest {
    @Test fun verifiedActiveProfilesJoinRetainedChatsWithoutDuplicatesOrHistoricalProfiles() {
        val explicit = room(1).copy(conversationMode = ConversationMode.GATEWAY)
        val retained = room(2)
        val selected = room(3)
        val active = room(4).copy(enabledPlatform = listOf("gateway"), activePlatform = listOf("gateway"))
        val historical = room(5).copy(enabledPlatform = listOf("gateway"), activePlatform = listOf("other"))
        val archived = room(6).copy(conversationMode = ConversationMode.GATEWAY, isArchived = true)
        val combined = room(7).copy(conversationMode = ConversationMode.COMBINED, enabledPlatform = listOf("other", "gateway"), activePlatform = listOf("other", "gateway"))
        val rooms = listOf(explicit, retained, selected, active, historical, archived, combined, retained)

        assertEquals(
            listOf(1, 2, 3, 4, 7),
            gatewayNavigationChats(rooms, setOf(1, 2, 6), setOf(3), setOf("gateway")).map { it.id }
        )
    }

    @Test fun oldGatewayProfileChatIsNotLostBehindTwoHundredNewOrdinaryChats() {
        val old = room(1).copy(enabledPlatform = listOf("gateway"), activePlatform = listOf("gateway"))
        val rooms = (2..252).map(::room) + old
        assertEquals(listOf(old), gatewayNavigationChats(rooms, emptySet(), emptySet(), setOf("gateway")))
    }

    @Test fun engineeringBindingRequiresBothTheActiveInferenceProfileAndVerifiedGateway() {
        val room = ChatRoomV2(id = 1, title = "GitHub", enabledPlatform = listOf("inference"))
        val binding = mapOf(room.id to GatewayNavigationBinding("inference", "gateway"))
        assertEquals(listOf(room), gatewayNavigationChats(listOf(room), emptySet(), emptySet(), setOf("gateway"), binding))
        assertEquals(emptyList<ChatRoomV2>(), gatewayNavigationChats(listOf(room), emptySet(), emptySet(), emptySet(), binding))
        assertEquals(emptyList<ChatRoomV2>(), gatewayNavigationChats(listOf(room.copy(activePlatform = listOf("different"))), emptySet(), emptySet(), setOf("gateway"), binding))
    }

    private fun room(id: Int) = ChatRoomV2(id = id, title = "Chat $id")
}
