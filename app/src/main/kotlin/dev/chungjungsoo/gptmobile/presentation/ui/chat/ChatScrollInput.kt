package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity

internal class ChatScrollInput {
    var dragging by mutableStateOf(false)
    var pointerActive = false
    var modifier: Modifier = Modifier
}

/** The same deliberate bottom arrival is used by normal, Combined, Gateway and GitHub chat. */
@Composable
internal fun rememberChatScrollInput(
    listState: LazyListState,
    touchSlop: Float,
    roomId: Int = 0,
    bottomTolerancePx: Float = 1f,
    onDetach: () -> Unit,
    onFollow: () -> Unit
): ChatScrollInput {
    val input = remember(listState, touchSlop, roomId) { ChatScrollInput() }
    val gesture = remember(listState, touchSlop, roomId) { GitHubPromptAnchor(touchSlop, GitHubScrollMode.FOLLOW_BOTTOM) }
    val detach by rememberUpdatedState(onDetach)
    val follow by rememberUpdatedState(onFollow)
    fun followIfAtBottom() {
        if (gesture.shouldFollowAtBottom(listState.layoutInfo.totalItemsCount > 0 && listState.gitHubDistanceToBottom() <= bottomTolerancePx)) {
            gesture.followBottom()
            input.dragging = false
            follow()
        }
    }
    val currentFollowIfAtBottom by rememberUpdatedState(::followIfAtBottom)
    val connection = remember(input, gesture, listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    gesture.move(available.y)
                    input.dragging = true
                    if (gesture.mode == GitHubScrollMode.USER_HISTORY) detach()
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Wheels can reach the edge without a pointer release or a fling.
                // A held touch still waits for its release or completed fling.
                if (!input.pointerActive && source == NestedScrollSource.UserInput && (consumed.y < 0f || available.y < 0f)) currentFollowIfAtBottom()
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                gesture.endGesture()
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                currentFollowIfAtBottom()
                gesture.finishScroll()
                input.dragging = false
                return Velocity.Zero
            }
        }
    }
    input.modifier = remember(connection, gesture, input) {
        Modifier.nestedScroll(connection).pointerInput(gesture) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                input.pointerActive = true
                gesture.followBottom()
                var released = false
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    released = true
                } finally {
                    input.pointerActive = false
                    gesture.endGesture(cancelled = !released)
                    input.dragging = false
                    if (released) currentFollowIfAtBottom()
                }
            }
        }
    }
    return input
}
