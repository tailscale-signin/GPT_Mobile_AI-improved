package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** FIFO distance budgets. Appending output never changes a running segment's deadline. */
internal class GitHubLinePacingQueue {
    private data class Segment(var pixels: Float, var millis: Long)
    private val segments = ArrayDeque<Segment>()

    val remainingMillis: Long get() = segments.sumOf { it.millis }
    val remainingPixels: Float get() = segments.sumOf { it.pixels.toDouble() }.toFloat()
    val isEmpty: Boolean get() = segments.isEmpty()

    fun append(renderedLines: Int, pixels: Float) {
        if (renderedLines <= 0 || !pixels.isFinite() || pixels <= 0f) return
        val duration = if (renderedLines == 1) GITHUB_BOTTOM_FOLLOW_MILLIS.toLong() else renderedLines.toLong() * 1000L
        segments.addLast(Segment(pixels, duration))
    }

    /** A width, font, inset or non-text change may change distance, never reading time. */
    fun reconcileDistance(pixels: Float) {
        if (segments.isEmpty() || !pixels.isFinite()) return
        val old = remainingPixels
        if (old <= 0f) return
        val scale = pixels.coerceAtLeast(0f) / old
        segments.forEach { it.pixels *= scale }
    }

    fun advance(elapsedMillis: Long): Float {
        var remaining = elapsedMillis.coerceAtLeast(0L)
        var pixels = 0f
        while (remaining > 0 && segments.isNotEmpty()) {
            val segment = segments.first()
            val time = minOf(remaining, segment.millis)
            val step = segment.pixels * (time.toDouble() / segment.millis).toFloat()
            pixels += step
            segment.pixels -= step
            segment.millis -= time
            remaining -= time
            if (segment.millis == 0L) segments.removeFirst()
        }
        return pixels
    }

    fun clear() = segments.clear()
}
