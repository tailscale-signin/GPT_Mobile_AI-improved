package dev.chungjungsoo.gptmobile.data.voice

internal data class VoiceStreamAudio(val samples: FloatArray, val cues: List<VoiceStreamCue>)

/** Per-phrase timing map; the limiter itself belongs to the whole playback, including later phrases. */
internal class VoiceStreamPause(private val raw: VoiceStreamFrames, private val limiter: VoiceQuietLimiter?) {
    private val removed = limiter?.let { RemovedFrames(raw.frameLimit) }
    private val pending = ArrayDeque<VoiceStreamCue>()
    var retainedFrames = 0L
        private set

    fun accept(chunk: VoicePcmChunk): VoiceStreamAudio {
        val rawStart = raw.frames.toInt()
        // Reject invalid raw PCM/cues before the shared limiter or retained timeline can change.
        val cues = raw.accept(chunk)
        if (limiter == null) {
            retainedFrames = raw.frames
            return VoiceStreamAudio(chunk.samples, cues)
        }
        val map = requireNotNull(removed)
        val samples = limiter.filter(chunk.samples, onRemoved = { start, end -> map.remove(rawStart + start, rawStart + end) })
        map.observeThrough(raw.frames.toInt())
        retainedFrames += samples.size
        val ready = mutableListOf<VoiceStreamCue>()
        fun drainCompleted() {
            while (pending.firstOrNull()?.let { it.endFrame <= raw.frames } == true) {
                val cue = pending.removeFirst()
                val start = map.retainedAt(cue.startFrame.toInt())
                val end = map.retainedAt(cue.endFrame.toInt())
                if (end > start) ready += cue.copy(startFrame = start.toLong(), endFrame = end.toLong())
            }
        }
        drainCompleted()
        for (cue in cues) {
            if (cue.revision == 0) {
                pending.addLast(cue)
                drainCompleted()
            } else {
                // Keep a revision ahead of later words released by its shortened end.
                // Older future cues may remain pending, but must not delay this revision.
                val start = map.retainedAt(cue.startFrame.toInt())
                val end = map.retainedAt(cue.endFrame.toInt())
                if (end > start) ready += cue.copy(startFrame = start.toLong(), endFrame = end.toLong())
            }
        }
        // Gateway can send every cue with its first PCM chunk. Complete cues use known
        // history; the single cue crossing the raw frontier exposes only its retained prefix.
        pending.firstOrNull()?.takeIf { it.startFrame < raw.frames }?.let { cue ->
            val start = map.retainedAt(cue.startFrame.toInt()).toLong()
            if (retainedFrames > start) ready += cue.copy(startFrame = start, endFrame = retainedFrames, finalEnd = false)
        }
        return VoiceStreamAudio(samples, ready)
    }

    fun finish() {
        raw.finish()
        check(pending.isEmpty()) { "STREAM_INCOMPLETE" }
    }

    /** One bit per raw frame plus rank prefixes, bounded by this stream's declared timeline. */
    private class RemovedFrames(frameLimit: Long) {
        private val maximumWords = ((frameLimit + 63) / 64).toInt()
        private var words = LongArray(minOf(maximumWords, 1_024))
        private var prefixes = IntArray(words.size + 1)
        private var indexedWords = 0

        fun remove(start: Int, end: Int) {
            ensureCapacity(end)
            var frame = start
            while (frame < end) {
                val word = frame / 64
                val offset = frame % 64
                val count = minOf(64 - offset, end - frame)
                val mask = if (count == 64) -1L else ((1L shl count) - 1) shl offset
                words[word] = words[word] or mask
                frame += count
            }
        }

        fun observeThrough(frames: Int) {
            ensureCapacity(frames)
            // A completed word never receives new bits. Partial-word queries mask out
            // later frames, so cues arriving at any later callback retain exact positions.
            while (indexedWords < frames / 64) {
                prefixes[indexedWords + 1] = prefixes[indexedWords] + words[indexedWords].countOneBits()
                indexedWords++
            }
        }

        fun retainedAt(frame: Int): Int {
            val word = frame / 64
            val offset = frame % 64
            val partial = if (offset == 0) 0 else (words[word] and ((1L shl offset) - 1)).countOneBits()
            return frame - prefixes[word] - partial
        }

        private fun ensureCapacity(frames: Int) {
            val required = (frames + 63) / 64
            require(required <= maximumWords)
            if (required <= words.size) return
            val capacity = maxOf(required, words.size * 2).coerceAtMost(maximumWords)
            words = words.copyOf(capacity)
            prefixes = prefixes.copyOf(capacity + 1)
        }
    }
}
