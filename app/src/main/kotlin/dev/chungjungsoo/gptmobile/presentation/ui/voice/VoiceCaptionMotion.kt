package dev.chungjungsoo.gptmobile.presentation.ui.voice

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** Settled typography and the entering/leaving edge are functions of the same continuous position. */
internal fun captionLineScale(distance: Float): Float = 2f - abs(distance).coerceAtMost(2f) * .5f

internal fun captionLineAlpha(distance: Float): Float = when (val offset = abs(distance)) {
    in 0f..2f -> 1f - offset * .25f
    else -> (2.5f - offset).coerceIn(0f, .5f)
}

/** Continuous leading covers the scale kink's maximum 1/16-line spacing deficit without a velocity discontinuity. */
internal fun captionLineDisplacement(distance: Float): Float {
    val offset = abs(distance)
    val integratedScale = if (offset <= 2f) 2f * offset - .25f * offset * offset else offset + 1f
    val magnitude = integratedScale + offset / 16f
    return if (distance < 0f) -magnitude else magnitude
}

internal data class VoiceCaptionMotionSample(val position: Float, val velocity: Float)

/** A retargetable Hermite trajectory. Velocity is in lines/second; no row is replaced mid-flight. */
internal class VoiceCaptionMotion(position: Float) {
    private var from = position
    private var velocity = 0f
    private var startedNanos = 0L
    private var durationNanos = 0.0
    private var pausedNanos: Long? = null
    var target: Float = position
        private set

    fun sample(nowNanos: Long): VoiceCaptionMotionSample {
        if (durationNanos == 0.0) return VoiceCaptionMotionSample(target, 0f)
        val elapsed = ((pausedNanos ?: nowNanos) - startedNanos).coerceAtLeast(0L)
        if (elapsed >= durationNanos) return VoiceCaptionMotionSample(target, 0f)
        val t = elapsed.toDouble() / durationNanos
        val seconds = durationNanos / 1_000_000_000.0
        val t2 = t * t
        val t3 = t2 * t
        val position = (2 * t3 - 3 * t2 + 1) * from + (t3 - 2 * t2 + t) * seconds * velocity + (-2 * t3 + 3 * t2) * target
        val speed = ((6 * t2 - 6 * t) * from + (3 * t2 - 4 * t + 1) * seconds * velocity + (-6 * t2 + 6 * t) * target) / seconds
        return VoiceCaptionMotionSample(position.toFloat(), speed.toFloat())
    }

    fun retarget(position: Float, nowNanos: Long, animate: Boolean, durationMs: Int = 320) {
        require(position.isFinite())
        if (!animate) {
            seek(position, nowNanos)
            return
        }
        if (position == target) return
        val current = sample(nowNanos)
        from = current.position
        velocity = current.velocity
        target = position
        startedNanos = pausedNanos ?: nowNanos
        val requestedNanos = durationMs.coerceIn(240, 420) * 1_000_000.0
        val distance = position.toDouble() - current.position
        // With zero arrival velocity, a forward Hermite curve is monotonic when |v| * T <= 3 * |distance|.
        // Shorten the curve instead of changing its inherited velocity. Fractional nanoseconds avoid
        // rounding a very close target's duration above that bound while retaining continuity at t = 0.
        durationNanos = if (distance * current.velocity > 0.0) {
            minOf(requestedNanos, 3.0 * abs(distance) / abs(current.velocity.toDouble()) * 1_000_000_000.0)
        } else {
            requestedNanos
        }
    }

    fun seek(position: Float, nowNanos: Long) {
        require(position.isFinite())
        from = position
        target = position
        velocity = 0f
        durationNanos = 0.0
        startedNanos = pausedNanos ?: nowNanos
    }

    fun playing(playing: Boolean, nowNanos: Long) {
        if (!playing && pausedNanos == null) pausedNanos = nowNanos
        if (playing) {
            pausedNanos?.let { startedNanos += (nowNanos - it).coerceAtLeast(0) }
            pausedNanos = null
        }
    }

    /** Reflow changes the line number of a source anchor, never its position within the viewport. */
    fun reanchor(delta: Float) {
        from += delta
        target += delta
    }

    fun moving(nowNanos: Long): Boolean = pausedNanos == null && durationNanos > 0 && nowNanos - startedNanos < durationNanos
}

internal fun captionReflowPosition(previous: List<VoiceCaptionLine>, current: List<VoiceCaptionLine>, position: Float): Float {
    if (previous.isEmpty() || current.isEmpty()) return position.coerceAtLeast(0f)
    val index = floor(position).toInt().coerceIn(previous.indices)
    val fraction = (position - index).coerceIn(0f, 1f)
    return (current.lineForOffset(previous[index].start) + fraction).coerceIn(0f, current.lastIndex.toFloat())
}

internal data class VoiceCaptionHighlight(val range: IntRange, val active: Boolean, val colour: Float)

/** Colour envelopes are cosmetic. Only the exact current speech range owns bold emphasis. */
internal class VoiceCaptionHighlights {
    private data class Envelope(val range: IntRange, val onset: Long, var release: Long? = null)
    private val envelopes = linkedMapOf<IntRange, Envelope>()
    private var active: IntRange? = null
    private var animated = true

    fun update(range: IntRange?, onsetNanos: Long, nowNanos: Long, animate: Boolean) {
        val next = range?.takeUnless(IntRange::isEmpty)
        animated = animate
        if (!animate) {
            envelopes.clear()
            active = next
            next?.let { envelopes[it] = Envelope(it, onsetNanos.coerceAtMost(nowNanos)) }
            return
        }
        if (next != active) {
            active?.let { previous ->
                envelopes[previous]?.let { it.release = max(nowNanos, it.onset + ATTACK_NANOS) }
            }
            active = next
            next?.let { envelopes[it] = Envelope(it, onsetNanos.coerceAtMost(nowNanos)) }
        }
        prune(nowNanos)
    }

    fun visible(nowNanos: Long): List<VoiceCaptionHighlight> = envelopes.values.mapNotNull {
        val attack = if (animated) ((nowNanos - it.onset).toDouble() / ATTACK_NANOS).coerceIn(0.0, 1.0).toFloat() else 1f
        val release = it.release?.let { ended -> (1.0 - (nowNanos - ended).coerceAtLeast(0).toDouble() / RELEASE_NANOS).coerceIn(0.0, 1.0).toFloat() } ?: 1f
        val colour = attack * release
        if (it.range == active || colour > 0f) VoiceCaptionHighlight(it.range, it.range == active, colour) else null
    }

    fun animating(nowNanos: Long): Boolean = animated &&
        envelopes.values.any {
            nowNanos < it.onset + ATTACK_NANOS || it.release?.let { ended -> nowNanos < ended + RELEASE_NANOS } == true
        }

    private fun prune(nowNanos: Long) {
        envelopes.entries.removeAll { (_, value) -> value.release?.let { nowNanos >= it + RELEASE_NANOS } == true }
        // Even an unusually dense callback burst cannot retain an unbounded transcript.
        while (envelopes.size > 12) envelopes.remove(envelopes.keys.first { it != active })
    }

    companion object {
        const val ATTACK_NANOS = 250_000_000L
        const val RELEASE_NANOS = 120_000_000L
    }
}

/** Interpolate only inside measured, buffered word audio. A UI frame cannot invent future speech. */
internal fun captionWordOnsetNanos(startFrame: Long, endFrame: Long, presentedFrame: Long, bufferedEndFrame: Long, sampleRate: Int, observedAtNanos: Long, nowNanos: Long, playing: Boolean): Long {
    if (sampleRate <= 0 || startFrame < 0 || endFrame <= startFrame || observedAtNanos <= 0) return nowNanos
    val available = minOf(endFrame, bufferedEndFrame).coerceAtLeast(startFrame)
    val elapsed = if (playing) (nowNanos - observedAtNanos).coerceIn(0, 500_000_000L) else 0L
    val extraFrames = elapsed * sampleRate / 1_000_000_000L
    val presented = (presentedFrame.coerceAtLeast(0) + extraFrames).coerceIn(startFrame, available)
    return nowNanos - (presented - startFrame) * 1_000_000_000L / sampleRate
}
