package dev.chungjungsoo.gptmobile.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppNavigationShellTest {
    @Test
    fun rootControlsAppearOnlyOnRootScreens() {
        assertEquals(AppRootDestination.CHATS, rootDestinationForRoute("chat_list"))
        assertEquals(AppRootDestination.AI, rootDestinationForRoute("ai_platforms"))
        assertEquals(AppRootDestination.TOOLS, rootDestinationForRoute("tool_connections"))
        assertEquals(AppRootDestination.SETTINGS, rootDestinationForRoute("settings?focus=theme"))
        listOf(null, "chat_room/42", "platform_settings/profile-uid", "tool_connections/edit/uid", "appearance", "activity").forEach {
            assertNull(rootDestinationForRoute(it))
        }
    }
}
