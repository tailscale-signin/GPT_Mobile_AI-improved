package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A single mutation drains measured output: 1,500 ms for one line, 1,000 ms per line for a block. */
@Composable
internal fun GitHubLineAutoScroller(listState: LazyListState, lines: GitHubRenderedLines, enabled: Boolean, revision: Int, animate: Boolean) {
    val footerHeight = with(LocalDensity.current) { 1.dp.roundToPx().toFloat() }
    LaunchedEffect(listState, lines, enabled, revision, animate, footerHeight) {
        lines.baseline()
        if (!enabled) return@LaunchedEffect
        // Entry/arrow positioning consumes the old backlog. New measured output
        // starts its own budget after that explicit return has completed.
        // Before the first lazy measurement, canScrollForward is false even
        // when the retained turn will overflow. Do not arm that empty layout.
        snapshotFlow {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && info.viewportSize.height > 0
        }.first { it }
        listState.animateToGitHubBottom(animate)
        withFrameNanos { }
        lines.ledger.arm()
        try {
            coroutineScope {
                val geometry = launch {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        Triple(info.totalItemsCount, info.visibleItemsInfo.map { it.index to it.size }, Triple(info.viewportSize, info.beforeContentPadding, info.afterContentPadding))
                    }.distinctUntilChanged().collect { lines.changed.trySend(Unit) }
                }
                try {
                    for (ignored in lines.changed) {
                        withFrameNanos { }
                        val count = lines.ledger.takeLines()
                        if (!listState.canScrollForward) continue
                        if (!animate || currentCoroutineContext()[MotionDurationScale]?.scaleFactor == 0f || count == 0) {
                            // Inset/reflow/counter growth is a correction, not new reading time.
                            listState.animateToGitHubBottom(animate = false)
                            continue
                        }
                        listState.followRenderedLines(lines, count, footerHeight)
                    }
                } finally {
                    geometry.cancel()
                }
            }
        } finally {
            lines.baseline()
        }
    }
}

private suspend fun LazyListState.followRenderedLines(lines: GitHubRenderedLines, firstLines: Int, footerHeight: Float) {
    scroll {
        val layout = LazyLayoutScrollScope(this@followRenderedLines, this)
        fun remaining(): Float {
            val measured = gitHubDistanceToBottom()
            if (measured.isFinite()) return measured
            val info = layoutInfo
            val target = info.totalItemsCount - 1
            if (target < 0) return 0f
            // The shared final sentinel is one dp high. Its preceding turn can be
            // measured even when a long answer puts the sentinel off screen.
            val footerStart = info.visibleItemsInfo.lastOrNull { it.index == target - 1 }
                ?.let { it.offset + it.size } ?: layout.calculateDistanceTo(target)
            return (footerStart + footerHeight + info.afterContentPadding - info.viewportEndOffset).coerceAtLeast(0f)
        }
        val queue = lines.queue
        queue.append(firstLines, remaining())
        var previous = withFrameNanos { it }
        var remainderNanos = 0L
        while (!queue.isEmpty) {
            val now = withFrameNanos { it }
            val distance = remaining()
            val added = lines.ledger.takeLines()
            if (added > 0) {
                queue.append(added, (distance - queue.remainingPixels).coerceAtLeast(0f))
            } else {
                queue.reconcileDistance(distance)
            }
            val elapsed = (now - previous).coerceAtLeast(0L) + remainderNanos
            remainderNanos = elapsed % 1_000_000L
            scrollBy(queue.advance(elapsed / 1_000_000L))
            previous = now
        }
    }
}
