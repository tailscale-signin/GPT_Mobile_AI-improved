package dev.chungjungsoo.gptmobile.data.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One stateful pitch-preserving processor per SDK stream, including all incremental text arrivals.
 * Inference stays at 1x; output remains 24 kHz mono. Cancellation discards this instance instead of
 * draining stale audio. Native Kokoro continues using its own duration-based synthesis speed.
 */
internal class VoiceTempoProcessor(
    private val speed: Float,
    private val frameLimit: Long = VoiceIncrementalStreamLimits.MAX_FRAMES,
    private val cueLimit: Int = VoiceIncrementalStreamLimits.MAX_CUES
) {
    private val timeline = VoiceTimelineTransform()
    private val pending = ArrayDeque<VoiceWordCue>()
    private val revisions = ArrayDeque<VoiceWordCue>()
    private val suppressedStarts = HashSet<Int>()
    private var prefix: VoiceWordCue? = null
    private var ended = false
    private var inputFrames = 0L
    private var outputFrames = 0L
    private val observed = ArrayList<Pair<Long, Long>>()
    private val buffer = ByteBuffer.allocate(INPUT_FRAMES * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val sonic: Sonic?

    init {
        require(speed.isFinite() && speed in .5f..2f)
        require(frameLimit in 1..VoiceIncrementalStreamLimits.MAX_FRAMES)
        require(cueLimit in 1..VoiceIncrementalStreamLimits.MAX_CUES)
        sonic = if (speed == 1f) {
            null
        } else {
            Sonic(VOICE_PCM_SAMPLE_RATE, 1, speed, 1f, VOICE_PCM_SAMPLE_RATE, true).apply {
                setFrameListener { input, output -> observed.add(input to output) }
            }
        }
    }

    fun accept(chunk: VoicePcmChunk, emit: (VoicePcmChunk) -> Unit) {
        check(!ended) { "TEMPO_ENDED" }
        require(chunk.sampleRate == VOICE_PCM_SAMPLE_RATE && chunk.samples.all(Float::isFinite)) { "TEMPO_PCM_INVALID" }
        require(inputFrames + chunk.samples.size <= inputFrameLimit(speed, frameLimit)) { "TEMPO_INPUT_LIMIT" }
        val processor = sonic
        if (processor == null) {
            inputFrames += chunk.samples.size
            outputFrames = inputFrames
            emit(chunk)
            return
        }
        for (cue in chunk.wordCues) {
            if (cue.revision > 0) {
                revisions.addLast(cue)
            } else if (pending.lastOrNull()?.let { sameWord(it, cue) } == true) {
                pending.removeLast()
                pending.addLast(cue)
            } else {
                pending.addLast(cue)
            }
        }
        require(pending.size + revisions.size <= cueLimit * 2) { "TEMPO_CUE_LIMIT" }
        var offset = 0
        while (offset < chunk.samples.size) {
            val size = minOf(INPUT_FRAMES, chunk.samples.size - offset)
            buffer.clear().limit(size * Float.SIZE_BYTES)
            buffer.asFloatBuffer().put(chunk.samples, offset, size)
            inputFrames += size
            processor.queueInput(buffer)
            drain(processor, false, emit)
            offset += size
        }
        if (chunk.samples.isEmpty()) drain(processor, false, emit)
        timeline.discardBefore(inputFrames - REVISION_FRAMES)
    }

    fun finish(emit: (VoicePcmChunk) -> Unit) {
        check(!ended) { "TEMPO_ENDED" }
        ended = true
        val processor = sonic ?: return
        processor.queueEndOfStream()
        drain(processor, true, emit)
        require(pending.isEmpty()) { "TEMPO_INCOMPLETE_CUE" }
        revisions.clear()
    }

    private fun drain(processor: Sonic, final: Boolean, emit: (VoicePcmChunk) -> Unit) {
        val size = processor.outputSize / Float.SIZE_BYTES
        val nextOutput = outputFrames + size
        require(nextOutput <= frameLimit) { "TEMPO_OUTPUT_LIMIT" }
        // Sonic pads its private input only to drain the tail. Those artificial anchors must not
        // extend captions beyond actual input or the trimmed output returned by queueEndOfStream.
        for ((input, output) in observed) {
            if (input <= inputFrames && output <= nextOutput) timeline.append(input, output)
        }
        observed.clear()
        if (final) timeline.finish(inputFrames, nextOutput)
        val samples = FloatArray(size)
        if (size > 0) {
            val output = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            processor.getOutput(output)
            output.flip()
            output.asFloatBuffer().get(samples)
        }
        outputFrames = nextOutput
        val cues = readyCues()
        if (samples.isNotEmpty() || cues.isNotEmpty()) emit(VoicePcmChunk(samples, wordCues = cues))
    }

    private fun readyCues(): List<VoiceWordCue> {
        val result = ArrayList<VoiceWordCue>()
        while (pending.isNotEmpty()) {
            appendReadyRevisions(result)
            val raw = pending.first()
            // A shortened earlier word can make this word's onset valid. Do not let
            // a new cue overtake the revision while its timeline waits for lookahead.
            if (revisions.firstOrNull()?.let { it.startByte < raw.startByte } == true) break
            val previous = prefix?.takeIf { sameWord(it, raw) }
            val start = previous?.startFrame ?: timeline.map(raw.startFrame)
            if (start == null) {
                if (raw.startFrame < timeline.sourceStart) {
                    // A stale first timestamp cannot be mapped once outside the bounded history.
                    // Keep speech playing without inventing a caption boundary.
                    pending.removeFirst()
                    suppressedStarts.add(raw.startByte)
                    prefix = null
                    continue
                }
                break
            }
            val end = timeline.map(minOf(raw.endFrame, timeline.sourceEnd)) ?: break
            if (end <= start && raw.finalEnd && raw.endFrame <= timeline.sourceEnd && (ended || raw.endFrame < inputFrames)) {
                // At faster rates an extremely short estimated interval may map to zero frames.
                // No audio exists on which to display it; do not invent time or block later words.
                pending.removeFirst()
                suppressedStarts.add(raw.startByte)
                prefix = null
                continue
            }
            if (end <= start || start >= outputFrames) break
            val mapped = raw.copy(
                startFrame = start,
                endFrame = end,
                finalEnd = raw.finalEnd && raw.endFrame <= timeline.sourceEnd && (ended || raw.endFrame < inputFrames)
            )
            if (mapped != previous) result.add(mapped)
            if (mapped.finalEnd) {
                pending.removeFirst()
                prefix = null
            } else {
                prefix = mapped
                break
            }
        }
        appendReadyRevisions(result)
        return result
    }

    /** Preserve the mapper's dependency order: original, its revision, then any released word. */
    private fun appendReadyRevisions(result: MutableList<VoiceWordCue>) {
        while (revisions.isNotEmpty()) {
            val raw = revisions.first()
            if (raw.startByte in suppressedStarts) {
                revisions.removeFirst()
                continue
            }
            // Preserve revision order. A later correction must not overtake an earlier correction
            // whose end still lies in the processor's small input lookahead.
            if (pending.any { sameWord(it, raw) }) break
            // A consumed frontier can still gain a zero-input overlap at EOS. Corrections are
            // finalized intervals too, so wait for the same stable boundary as an original cue.
            if (!ended && raw.endFrame >= inputFrames) break
            val start = timeline.map(raw.startFrame)
            val end = timeline.map(raw.endFrame)
            if (start != null && end != null && end > start) {
                result.add(raw.copy(startFrame = start, endFrame = end))
                revisions.removeFirst()
            } else if (raw.endFrame > timeline.sourceEnd) {
                break
            } else {
                revisions.removeFirst()
                suppressedStarts.add(raw.startByte)
            }
        }
    }

    private fun sameWord(first: VoiceWordCue, second: VoiceWordCue): Boolean = first.startByte == second.startByte && first.endByte == second.endByte

    companion object {
        /** The existing duration limit is in playable output frames, including slow playback. */
        fun inputFrameLimit(speed: Float, outputLimit: Long): Long {
            require(speed.isFinite() && speed in .5f..2f && outputLimit > 0)
            return (outputLimit * minOf(speed, 1f).toDouble()).toLong().coerceAtLeast(1)
        }

        const val IMPLEMENTATION_ID = "sonic-float-8c6678b6-pitch1-timeline-v1"
        private const val INPUT_FRAMES = VOICE_PCM_SAMPLE_RATE / 50
        private const val REVISION_FRAMES = VOICE_PCM_SAMPLE_RATE * 60L
    }
}
