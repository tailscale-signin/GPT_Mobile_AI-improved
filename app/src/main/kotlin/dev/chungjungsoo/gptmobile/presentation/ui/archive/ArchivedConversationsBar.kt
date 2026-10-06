package dev.chungjungsoo.gptmobile.presentation.ui.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.collectReusableProfileLabels
import dev.chungjungsoo.gptmobile.presentation.common.FadingModalBottomSheet
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.ui.home.FancySwipeChatCard
import dev.chungjungsoo.gptmobile.presentation.ui.home.HomeViewModel.ChatListState
import dev.chungjungsoo.gptmobile.util.getPlatformName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedConversationsBar(
    archivedChats: List<ChatRoomV2>,
    platformState: List<PlatformV2>,
    onUnarchiveChat: (ChatRoomV2) -> Unit,
    onDeleteChat: (ChatRoomV2) -> Unit,
    onChatClick: (ChatRoomV2) -> Unit,
    modifier: Modifier = Modifier
) {
    if (archivedChats.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(64.dp)) {
            Icon(Icons.Rounded.Archive, stringResource(R.string.archived_chats), modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (expanded) {
        val halfHeight = LocalConfiguration.current.screenHeightDp.dp * 0.5f
        val backFade = dev.chungjungsoo.gptmobile.presentation.common.rememberBackFade()
        FadingModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = Modifier.heightIn(max = halfHeight)
        ) {
            Column(Modifier.then(backFade.modifier).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Archived conversations", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Swipe right to restore · swipe left to delete", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { backFade.fade { expanded = false } }) { Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = MaterialTheme.colorScheme.primary) }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    items(archivedChats, key = { it.id }) { room ->
                        val state = rememberSwipeToDismissBoxState(
                            positionalThreshold = { it * 0.38f },
                            confirmValueChange = { value ->
                                when (value) {
                                    SwipeToDismissBoxValue.StartToEnd -> onUnarchiveChat(room)
                                    SwipeToDismissBoxValue.EndToStart -> onDeleteChat(room)
                                    SwipeToDismissBoxValue.Settled -> Unit
                                }
                                true
                            }
                        )
                        val profiles = platformState.filter { it.uid in room.enabledPlatform }
                        FancySwipeChatCard(
                            chatRoom = room,
                            chatListState = ChatListState(chats = archivedChats),
                            idx = 0,
                            usingPlatform = profiles.joinToString(", ") { it.name }.ifBlank { room.enabledPlatform.joinToString(", ") { platformState.getPlatformName(it) } },
                            isGenerating = false,
                            hasUnreadResponse = false,
                            dismissState = state,
                            profileLabels = collectReusableProfileLabels(profiles.map { it.labels }),
                            onItemClick = { onChatClick(room) },
                            onItemLongClick = {},
                            isArchived = true
                        )
                    }
                }
            }
        }
    }
}
