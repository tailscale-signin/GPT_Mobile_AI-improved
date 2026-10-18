package dev.chungjungsoo.gptmobile.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun settingsAndHomeTabHaveNoCompactBottomNavigation() {
        assertFalse(showsCompactRootNavigation(AppRootDestination.SETTINGS, rail = false))
        assertFalse(showsCompactRootNavigation(AppRootDestination.CHATS, rail = false))
        assertFalse(showsCompactRootNavigation(null, rail = false))
        assertTrue(showsCompactRootNavigation(AppRootDestination.AI, rail = false))
        assertTrue(showsCompactRootNavigation(AppRootDestination.TOOLS, rail = false))
        AppRootDestination.entries.forEach { assertFalse(showsCompactRootNavigation(it, rail = true)) }
    }
}
