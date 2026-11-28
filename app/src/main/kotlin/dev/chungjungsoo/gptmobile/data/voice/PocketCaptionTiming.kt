package dev.chungjungsoo.gptmobile.data.voice

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToLong

internal enum class PocketCaptionStatus { DISABLED, BUFFERING, PACK_UNAVAILABLE, ALIGNED, TEXT_MISMATCH, INVALID_TIMING, AUDIO_LIMIT, TEXT_LIMIT, NATIVE_ERROR }

/** These are inferred boundaries from audio, not calibrated confidence or pronunciation proof. */
internal data class PocketCaptionWord(val text: String, val startSeconds: Double, val endSeconds: Double)

internal data class PocketCaptionTranscript(val words: List<PocketCaptionWord>, val complete: Boolean)

internal data class PocketCaptionAlignment(val status: PocketCaptionStatus, val cues: List<VoiceWordCue> = emptyList())

internal data class PocketCaptionPart(val text: String, val startByte: Int)

/** Short phrase buffering is bounded independently of both the TTS graph and the ASR model. */
internal object PocketCaptionPolicy {
    const val MAX_INPUT_CHARACTERS = 2_048
    const val MAX_PHRASE_CHARACTERS = 128
    const val MAX_PHRASE_WORDS = 8
    const val MAX_PHRASE_FRAMES = 8 * VOICE_PCM_SAMPLE_RATE
    const val OUTPUT_PACKET_FRAMES = VOICE_PCM_SAMPLE_RATE / 4
    const val MAX_RECOGNIZED_WORDS = 128
    private val nonWhitespace = Regex("\\S+")
    private const val EDGE_PUNCTUATION = ".,;:!?()[]{}\"'“”‘’«»…—–。，、；：！？"

    /** Preserve every source character and byte offset. Oversized single words bypass alignment. */
    fun parts(text: String): List<PocketCaptionPart>? {
        if (text.length !in 1..MAX_INPUT_CHARACTERS) return null
        val tokens = nonWhitespace.findAll(text).toList()
        if (tokens.isEmpty() || tokens.any { it.value.length > MAX_PHRASE_CHARACTERS }) return null
        val result = ArrayList<PocketCaptionPart>()
        var start = 0
        var firstToken = 0
        var byteOffset = 0
        while (firstToken < tokens.size) {
            var lastToken = firstToken
            while (lastToken + 1 < tokens.size && lastToken - firstToken + 1 < MAX_PHRASE_WORDS) {
                if (tokens[lastToken].value.last() in ".!?。！？") break
                val next = tokens[lastToken + 1]
                if (next.range.last + 1 - start > MAX_PHRASE_CHARACTERS) break
                lastToken++
            }
            val end = if (lastToken == tokens.lastIndex) text.length else tokens[lastToken + 1].range.first
            // Huge separators or an oversized first token must not turn into an unbounded buffer.
            if (end - start > MAX_PHRASE_CHARACTERS) return null
            val part = text.substring(start, end)
            result += PocketCaptionPart(part, byteOffset)
            byteOffset += part.toByteArray(Charsets.UTF_8).size
            start = end
            firstToken = lastToken + 1
        }
        return result
    }

    /** Only canonical spelling, apostrophes, case and surrounding punctuation are equivalent. */
    private fun canonical(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .replace('’', '\'').replace('‘', '\'')
        .trim { it.isWhitespace() || it in EDGE_PUNCTUATION }
        .lowercase(Locale.ROOT)

    fun align(text: String, frames: Int, transcript: PocketCaptionTranscript): PocketCaptionAlignment {
        if (frames !in 1..MAX_PHRASE_FRAMES || !transcript.complete || transcript.words.size > MAX_RECOGNIZED_WORDS) {
            return PocketCaptionAlignment(PocketCaptionStatus.INVALID_TIMING)
        }
        val source = nonWhitespace.findAll(text).mapNotNull { match ->
            canonical(match.value).takeIf(String::isNotEmpty)?.let { Triple(it, match.range.first, match.range.last + 1) }
        }.toList()
        val recognized = transcript.words.filter { canonical(it.text).isNotEmpty() }
        if (source.isEmpty() || source.size != recognized.size || source.indices.any { source[it].first != canonical(recognized[it].text) }) {
            return PocketCaptionAlignment(PocketCaptionStatus.TEXT_MISMATCH)
        }
        val result = ArrayList<VoiceWordCue>(source.size)
        var previousEnd = 0L
        for ((index, word) in recognized.withIndex()) {
            val start = word.startSeconds * VOICE_PCM_SAMPLE_RATE
            val end = word.endSeconds * VOICE_PCM_SAMPLE_RATE
            // Do not repair an unresolved boundary by spreading durations, midpoint guesses or
            // clipping it into the recorded audio. Reject the phrase's timing as one unit.
            if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start || end > frames + .5) {
                return PocketCaptionAlignment(PocketCaptionStatus.INVALID_TIMING)
            }
            val firstFrame = start.roundToLong()
            val lastFrame = end.roundToLong()
            if (firstFrame < previousEnd || lastFrame <= firstFrame || lastFrame > frames) return PocketCaptionAlignment(PocketCaptionStatus.INVALID_TIMING)
            val (_, firstCharacter, lastCharacter) = source[index]
            result += VoiceWordCue(
                text.substring(0, firstCharacter).toByteArray(Charsets.UTF_8).size,
                text.substring(0, lastCharacter).toByteArray(Charsets.UTF_8).size,
                firstFrame,
                lastFrame,
                VoiceWordCueProvenance.ASR_ALIGNMENT
            )
            previousEnd = lastFrame
        }
        return PocketCaptionAlignment(PocketCaptionStatus.ALIGNED, result)
    }
}
