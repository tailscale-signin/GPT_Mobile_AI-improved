package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/** Keep changing layout and bindings live inside the retained conversation destination. */
@Composable
internal fun GitHubChatNavigation(
    chatId: Int,
    profileUid: String,
    headerHeight: Dp,
    content: @Composable (Dp) -> Unit
) {
    key(chatId) {
        val controller = rememberNavController()
        val currentHeaderHeight by rememberUpdatedState(headerHeight)
        val currentContent by rememberUpdatedState(content)
        NavHost(controller, startDestination = "conversation/$chatId/$profileUid", modifier = Modifier.fillMaxSize()) {
            composable(
                "conversation/{chatRoomId}/{enabledPlatforms}",
                arguments = listOf(
                    navArgument("chatRoomId") { type = NavType.IntType },
                    navArgument("enabledPlatforms") { type = NavType.StringType },
                    navArgument("conversationMode") { defaultValue = "STANDARD" }
                )
            ) {
                currentContent(currentHeaderHeight)
            }
        }
    }
}
