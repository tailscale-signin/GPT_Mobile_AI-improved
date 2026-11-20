package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.flow.distinctUntilChanged

/** Explicit downward user motion, not viewport reflow, authorizes returning to bottom. */
internal class ChatBottomReturnIntent {
    private var returning = false

    fun recordMotion(delta: Float) {
        if (delta.isFinite() && delta != 0f) returning = delta < 0f
    }

    fun shouldFollow(atBottom: Boolean): Boolean = returning && atBottom

    fun finish() {
        returning = false
    }
}

internal class ChatScrollInput {
    var dragging by mutableStateOf(false)
    var pointerActive = false
    var userFlingActive by mutableStateOf(false)
        private set
    var modifier: Modifier = Modifier
    private val readingGate = ChatReadingScrollGate()
    private val bottomIntent = ChatBottomReturnIntent()
    var scrollingFasterThanReading by mutableStateOf(false)
        private set

    fun recordUserDelta(deltaDp: Float) {
        bottomIntent.recordMotion(deltaDp)
        scrollingFasterThanReading = readingGate.recordDelta(deltaDp, SystemClock.uptimeMillis())
    }

    fun recordFling(velocityDpPerSecond: Float) {
        userFlingActive = velocityDpPerSecond.isFinite() && velocityDpPerSecond != 0f
        bottomIntent.recordMotion(velocityDpPerSecond)
        scrollingFasterThanReading = readingGate.recordFling(velocityDpPerSecond)
    }

    fun shouldFollowAtBottom(atBottom: Boolean): Boolean = bottomIntent.shouldFollow(atBottom)

    fun finishUserScroll() {
        bottomIntent.finish()
    }

    fun stopScroll() {
        userFlingActive = false
        readingGate.stop()
        scrollingFasterThanReading = false
    }
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
    val density = LocalDensity.current.density.coerceAtLeast(0.01f)
    val gesture = remember(listState, touchSlop, roomId) { GitHubPromptAnchor(touchSlop, GitHubScrollMode.FOLLOW_BOTTOM) }
    val detach by rememberUpdatedState(onDetach)
    val follow by rememberUpdatedState(onFollow)
    fun followIfAtBottom() {
        if (input.shouldFollowAtBottom(listState.layoutInfo.totalItemsCount > 0 && (!listState.canScrollForward || listState.gitHubDistanceToBottom() <= bottomTolerancePx))) {
            gesture.followBottom()
            input.dragging = false
            input.finishUserScroll()
            follow()
        }
    }
    val currentFollowIfAtBottom by rememberUpdatedState(::followIfAtBottom)
    // A user fling can settle at the sentinel after nested-scroll's last callback.
    // Never reattach for viewport reflow: the gesture's downward return intent
    // is mandatory, just as it is for the arrow and a completed swipe.
    LaunchedEffect(listState, gesture, input) {
        snapshotFlow {
            listState.isAtGitHubBottom() && !listState.isScrollInProgress && !input.dragging
        }.distinctUntilChanged().collect { atRestAtBottom ->
            if (atRestAtBottom) currentFollowIfAtBottom()
        }
    }
    LaunchedEffect(listState, input) {
        snapshotFlow { listState.isScrollInProgress }.distinctUntilChanged().collect { moving ->
            if (!moving) {
                if (!input.pointerActive) {
                    // A fling may reach the real footer after the last drag event.
                    currentFollowIfAtBottom()
                    // Wheel gestures have no pointer-up callback. Release the
                    // dragging latch before the next explicit follow request.
                    gesture.finishScroll()
                    input.dragging = false
                    // Prevent IME/header geometry changes from restoring follow later.
                    input.finishUserScroll()
                }
                input.stopScroll()
            }
        }
    }
    val connection = remember(input, gesture, listState, density) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    val crossedHistoryThreshold = gesture.move(available.y)
                    input.recordUserDelta(available.y / density)
                    input.dragging = true
                    if (crossedHistoryThreshold) detach()
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Engage as soon as a real drag or its fling reaches the footer.
                if (consumed.y < 0f || available.y < 0f) currentFollowIfAtBottom()
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                input.recordFling(available.y / density)
                gesture.endGesture()
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                currentFollowIfAtBottom()
                gesture.finishScroll()
                input.dragging = false
                input.finishUserScroll()
                input.stopScroll()
                return Velocity.Zero
            }
        }
    }
    input.modifier = remember(connection, gesture, input) {
        Modifier.nestedScroll(connection).pointerInput(gesture) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                input.pointerActive = true
                gesture.endGesture(cancelled = true)
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
                    if (!listState.isScrollInProgress) input.finishUserScroll()
                }
            }
        }
    }
    return input
}
