package dev.chungjungsoo.gptmobile.data.voice

import java.security.MessageDigest

internal enum class VoiceSpeechBenchmarkWorkload(val label: String) {
    UTTERANCE("Production phrases"),
    NATIVE_PHRASE("Native phrase"),
    VARIED_SHAPES("Changing phrase shapes")
}

/** Only fixed fixture identities and input sizes leave a failed workload; never arbitrary text. */
internal class VoiceSpeechWorkloadFailure(val code: String, val inputBytes: Int, val inputByteLimit: Int = VoiceSpeechWorkload.MAX_PHRASE_BYTES) : IllegalArgumentException(code)

/** Shared with Auto/Recheck. Every native call obeys the production frontend's byte limit. */
internal object VoiceSpeechWorkload {
    const val FIXTURE_VERSION = 2
    const val PROTOCOL = "planned-utterances-v2-alternating-shapes-complete-cost-v2"
    const val MAX_PHRASE_BYTES = 128
    const val CALIBRATION_FIXTURE_COUNT = 6
    const val MAX_CHUNKS = 32

    private val nativeTexts = mapOf(
        VoiceSpeechBenchmarkFixture.SHORT to "the voice is ready for your next question.",
        VoiceSpeechBenchmarkFixture.MEDIUM to "here are the main points. speech should stay clear while the next phrase is prepared, with natural pauses between ideas.",
        VoiceSpeechBenchmarkFixture.LONG to "a clear spoken answer begins with useful details and natural pauses, while the engine prepares the next part for you to hear."
    )

    private val plans by lazy {
        VoiceSpeechBenchmarkWorkload.entries.associateWith { workload -> VoiceSpeechBenchmarkFixture.entries.associateWith { fixture -> createPhrases(fixture, workload) } }
    }
    private val hashes by lazy { VoiceSpeechBenchmarkWorkload.entries.associateWith(::computeFixtureHash) }

    fun phrases(fixture: VoiceSpeechBenchmarkFixture, workload: VoiceSpeechBenchmarkWorkload = VoiceSpeechBenchmarkWorkload.UTTERANCE): List<String> = plans.getValue(workload).getValue(fixture)

    private fun createPhrases(fixture: VoiceSpeechBenchmarkFixture, workload: VoiceSpeechBenchmarkWorkload): List<String> {
        val phrases = when (workload) {
            VoiceSpeechBenchmarkWorkload.UTTERANCE -> KokoroSpeechText.timed(fixture.text) { true }.parts
                ?: throw VoiceSpeechWorkloadFailure("FRONTEND_INPUT_INVALID", fixture.text.toByteArray(Charsets.UTF_8).size)
            VoiceSpeechBenchmarkWorkload.NATIVE_PHRASE -> listOf(nativeTexts.getValue(fixture))
            VoiceSpeechBenchmarkWorkload.VARIED_SHAPES -> listOf(
                nativeTexts.getValue(fixture),
                nativeTexts.getValue(VoiceSpeechBenchmarkFixture.entries[(fixture.ordinal + 1) % VoiceSpeechBenchmarkFixture.entries.size]),
                nativeTexts.getValue(fixture)
            )
        }
        if (phrases.isEmpty() || phrases.size > MAX_CHUNKS) throw VoiceSpeechWorkloadFailure("FRONTEND_INPUT_INVALID", 0)
        phrases.forEach(::validate)
        return phrases
    }

    fun validate(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_PHRASE_BYTES) throw VoiceSpeechWorkloadFailure("FRONTEND_INPUT_TOO_LONG", bytes)
        if (bytes == 0 || text.none { it in 'a'..'z' } || text.any { it !in 'a'..'z' && it !in " ',.:;!?" }) {
            throw VoiceSpeechWorkloadFailure("FRONTEND_INPUT_INVALID", bytes)
        }
    }

    /** Two traversals alternate graph shapes instead of repeating one artificially hot phrase. */
    val calibrationFixtures: List<List<String>> get() = List(2) { VoiceSpeechBenchmarkFixture.entries.map { phrases(it) } }.flatten()
    val calibrationPhrases: List<String> get() = calibrationFixtures.flatten()
    val calibrationFixtureCount: Int get() = CALIBRATION_FIXTURE_COUNT

    fun fixtureHash(workload: VoiceSpeechBenchmarkWorkload): String = hashes.getValue(workload)

    private fun computeFixtureHash(workload: VoiceSpeechBenchmarkWorkload): String = digest(
        buildString {
            append(PROTOCOL).append('|').append(workload.name)
            for (fixture in VoiceSpeechBenchmarkFixture.entries) {
                append('|').append(fixture.name)
                for (phrase in phrases(fixture, workload)) append('|').append(phrase.length).append(':').append(phrase)
            }
        }
    )

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
