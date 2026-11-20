package dev.chungjungsoo.gptmobile.data.voice

import kotlin.math.abs

internal typealias SpokenRangeObserver = (Int, String, IntRange) -> Unit

internal data class VoicePcmCue(val frame: Long, val segment: Int, val text: String, val range: IntRange)

/** Synthesis and playback run on different threads. Only consumed PCM can advance the caption. */
internal class VoicePcmCaptions {
    private val pending = ArrayDeque<VoicePcmCue>()
    private var produced = 0L
    private var activeWord: IntRange? = null
    private var lastAudibleEnd = 0L

    fun beginWord() {
        activeWord = null
    }

    @Synchronized fun append(segment: Int, text: String, range: IntRange, samples: FloatArray) {
        val last = samples.indexOfLast { abs(it) > .002f }
        if (last >= 0) {
            if (activeWord == null) {
                val first = samples.indexOfFirst { abs(it) > .002f }
                pending.addLast(VoicePcmCue(produced + first, segment, text, range))
                activeWord = range
            }
            lastAudibleEnd = produced + last + 1
        }
        produced += samples.size
    }

    @Synchronized fun endWord(segment: Int, text: String) {
        if (activeWord != null) pending.addLast(VoicePcmCue(lastAudibleEnd, segment, text, IntRange.EMPTY))
    }

    @Synchronized fun consumed(frames: Long): VoicePcmCue? {
        var cue: VoicePcmCue? = null
        while (pending.isNotEmpty() && pending.first().frame < frames) cue = pending.removeFirst()
        return cue
    }
}

internal data class KokoroCaptionPart(val speech: String, val range: IntRange)

private val spokenToken = Regex("""[vV]?[+\-]?\p{N}+(?:[.,]\p{N}+)*|[\p{L}\p{M}]+(?:['’][\p{L}\p{M}]+)*|[^\s]""")

/** The local synthesizer returns PCM without word offsets. Give each source token its own measured audio span. */
internal fun kokoroCaptionParts(text: String, isKnown: (String) -> Boolean): List<KokoroCaptionPart> = buildList {
    for (token in spokenToken.findAll(text)) {
        if (token.value.length == 1 && token.value[0] in ".,!?;:") {
            if (isNotEmpty()) {
                val prior = removeAt(lastIndex)
                add(prior.copy(speech = prior.speech + token.value))
            }
        } else {
            val parts = KokoroSpeechText.projectedParts(token.value, isKnown).orEmpty()
            for (part in parts) add(KokoroCaptionPart(part, token.range))
        }
    }
}
