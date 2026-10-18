package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationSelectionGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rowLongPressSelectsAndOnlySelectedRowsExposeOptions() {
        var selected by mutableStateOf(false)
        var opens = 0
        compose.setContent {
            MaterialTheme {
                ChatListItem(
                    chatRoom = ChatRoomV2(id = 1, title = "Conversation"), idx = 0,
                    chatListState = HomeViewModel.ChatListState(chats = listOf(ChatRoomV2(id = 1, title = "Conversation")), isSelectionMode = selected, selectedChatIds = if (selected) setOf(1) else emptySet()),
                    isGenerating = false, hasUnreadResponse = false, usingPlatform = "AI", profileLabels = emptyList(),
                    onItemClick = { opens++ }, onItemLongClick = { selected = true },
                    rowActions = mapOf("Archive selected" to {})
                )
            }
        }
        compose.onNodeWithContentDescription("Conversation actions").assertDoesNotExist()
        compose.onNodeWithText("Conversation").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(0, opens)
        compose.onNodeWithContentDescription("Conversation actions").assertExists()
    }

    @Test fun newChatUsesAccessibleIconWithoutVisibleText() {
        compose.setContent { MaterialTheme { Column { NewChatButton {} } } }
        compose.onNodeWithText("New Chat", ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("New Chat", ignoreCase = true).assertExists()
    }
}
