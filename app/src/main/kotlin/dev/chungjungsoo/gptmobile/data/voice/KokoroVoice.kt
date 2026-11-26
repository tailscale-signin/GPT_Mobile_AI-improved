package dev.chungjungsoo.gptmobile.data.voice

/** English speakers in sherpa-onnx's v1.0 voices.bin and their IDs in the LiteRT voice packs. */
internal enum class KokoroVoice(val speakerId: Int, val streamingVoiceId: String) {
    HEART(3, "af_heart"),
    ADAM(11, "am_adam"),
    EMMA(21, "bf_emma"),
    ALLOY(0, "af_alloy"),
    AOEDE(1, "af_aoede"),
    BELLA(2, "af_bella"),
    JESSICA(4, "af_jessica"),
    KORE(5, "af_kore"),
    NICOLE(6, "af_nicole"),
    NOVA(7, "af_nova"),
    RIVER(8, "af_river"),
    SARAH(9, "af_sarah"),
    SKY(10, "af_sky"),
    ECHO(12, "am_echo"),
    ERIC(13, "am_eric"),
    FENRIR(14, "am_fenrir"),
    LIAM(15, "am_liam"),
    MICHAEL(16, "am_michael"),
    ONYX(17, "am_onyx"),
    PUCK(18, "am_puck"),
    SANTA(19, "am_santa"),
    ALICE(20, "bf_alice"),
    ISABELLA(22, "bf_isabella"),
    LILY(23, "bf_lily"),
    DANIEL(24, "bm_daniel"),
    FABLE(25, "bm_fable"),
    GEORGE(26, "bm_george"),
    LEWIS(27, "bm_lewis");

    private val british: Boolean get() = streamingVoiceId.startsWith('b')
    val lexicon: String get() = if (british) KokoroModelPack.GB_LEXICON else KokoroModelPack.US_LEXICON
    val label: String get() = streamingVoiceId.substringAfter('_').replaceFirstChar(Char::uppercaseChar) + if (british) " · UK English" else " · US English"

    companion object {
        fun fromSaved(value: String?) = entries.firstOrNull { it.name == value }
        fun fromStreamingId(id: String) = entries.firstOrNull { it.streamingVoiceId == id }
    }
}
