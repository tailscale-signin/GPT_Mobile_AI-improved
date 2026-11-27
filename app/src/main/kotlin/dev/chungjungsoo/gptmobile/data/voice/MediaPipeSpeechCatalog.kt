package dev.chungjungsoo.gptmobile.data.voice

internal enum class MediaPipeSpeechEngine {
    KOKORO,
    KOKORO_GPU,
    KOKORO_SM8750,
    PHONON,
    POCKET,
    POCKET_MULTILINGUAL;

    val isKokoro: Boolean get() = this == KOKORO || this == KOKORO_GPU || this == KOKORO_SM8750
}

/** Immutable publisher identities; a model download is never resolved through a moving branch. */
internal data class MediaPipeSpeechModel(
    val engine: MediaPipeSpeechEngine,
    val id: String,
    val title: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val downloadBytes: Long,
    val sha256: String,
    val defaultVoice: String,
    val license: String,
    val attribution: String
) {
    init {
        require(id.matches(Regex("[a-z0-9-]+")))
        require(repository.matches(Regex("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+")))
        require(revision.matches(Regex("[0-9a-f]{40}")))
        require(fileName.matches(Regex("[a-z0-9_]+\\.litertlm")))
        require(downloadBytes > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
    }

    val sourceUrl: String get() = "https://huggingface.co/$repository/tree/$revision"
    val requiresSm8750: Boolean get() = engine == MediaPipeSpeechEngine.KOKORO_SM8750
    val downloadUrl: String get() = "https://huggingface.co/$repository/resolve/$revision/$fileName"
    internal val asset: LocalVoiceAsset get() = LocalVoiceAsset(fileName, downloadBytes, sha256, if (requiresSm8750) listOf("local-import") else listOf(downloadUrl))
}

internal object MediaPipeSpeechCatalog {
    const val RUNTIME_VERSION = "1.2.3"
    const val RUNTIME_ID = "mediapipe-tasks-audio-$RUNTIME_VERSION"

    val models: List<MediaPipeSpeechModel> = listOf(
        MediaPipeSpeechModel(
            MediaPipeSpeechEngine.KOKORO,
            "kokoro",
            "Kokoro · LiteRT",
            "litert-community/Kokoro-82M",
            "bf1c2405642aa310bd210205c22d3ae1abc253fb",
            // Explicit dequantize operators make this bundle safe for both CPU and GPU delegates.
            "kokoro_int8_fp16.litertlm",
            294_783_298,
            "e7b931ee5d4b7104012a838c9eee240d19ea69cb3ca269041a3274f83dbb7fa1",
            "af_heart",
            "Apache-2.0",
            "Kokoro-82M by hexgrad; quantized and packaged for LiteRT by litert-community."
        ),
        MediaPipeSpeechModel(
            MediaPipeSpeechEngine.KOKORO_GPU,
            "kokoro-gpu",
            "Kokoro · GPU vocoder",
            "litert-community/Kokoro-82M",
            "bf1c2405642aa310bd210205c22d3ae1abc253fb",
            "kokoro_int8_gpu_fp16.litertlm",
            294_750_530,
            "1ef178b127ec48c737daf17eabb4cea5f6b3ffbc510aced270fe5c267bbeac52",
            "af_heart",
            "Apache-2.0",
            "Kokoro by hexgrad, GPU-oriented vocoder by litert-community."
        ),
        MediaPipeSpeechModel(
            MediaPipeSpeechEngine.KOKORO_SM8750,
            "kokoro-sm8750",
            "Kokoro · Snapdragon 8 Elite vocoder",
            "litert-community/Kokoro-82M",
            "bf1c2405642aa310bd210205c22d3ae1abc253fb",
            "kokoro_sm8750.litertlm",
            295_602_498,
            "04ee7076b0c8e5b11239ba5ed8ee7a3540984acb17936441b904196154fc0ebb",
            "af_heart",
            "Apache-2.0",
            "Kokoro by hexgrad, litert-community voice pack with an SM8750-compiled vocoder."
        ),
        MediaPipeSpeechModel(
            MediaPipeSpeechEngine.PHONON,
            "phonon-en",
            "Phonon · English",
            "litert-community/phonon-31m-en",
            "ee374c33f0f6f8ed1d9f761e21cdb97df6accfe8",
            "phonon_int8.litertlm",
            87_205_888,
            "b33a9a0d65acec36762db38a2bff6d899f4aab34b196bb56d89fab5665eabebe",
            "freya",
            "Apache-2.0",
            "Phonon 31M English by Gradium; quantized and packaged for LiteRT by litert-community."
        ),
        MediaPipeSpeechModel(
            MediaPipeSpeechEngine.POCKET,
            "pocket-en",
            "Pocket TTS · English",
            "litert-community/pocket-tts",
            "00c866f2397788f514ab6bbcb931be22afc3b58f",
            "pocket_tts_en_int8.litertlm",
            162_766_856,
            "465e0083cd61188772aed76160b07af0dcf59057497d7659f43f3f2b13a174a1",
            "alba",
            "CC-BY-4.0",
            "Pocket TTS and voice assets by Kyutai; quantized and packaged for LiteRT by litert-community."
        ),
        MediaPipeSpeechModel(
            MediaPipeSpeechEngine.POCKET_MULTILINGUAL,
            "pocket-multilingual",
            "Pocket TTS · 7 languages",
            "litert-community/pocket-tts",
            "00c866f2397788f514ab6bbcb931be22afc3b58f",
            "pocket_tts_int8.litertlm",
            760_061_960,
            "9c5427cf3fe2d6680f8268e1c06537496be52d818ef12233a461bf193f314b3c",
            "alba",
            "CC-BY-4.0",
            "Pocket TTS and 14 voice assets by Kyutai; quantized and packaged for LiteRT by litert-community."
        )
    )

    fun model(engine: MediaPipeSpeechEngine): MediaPipeSpeechModel = models.single { it.engine == engine }
}
