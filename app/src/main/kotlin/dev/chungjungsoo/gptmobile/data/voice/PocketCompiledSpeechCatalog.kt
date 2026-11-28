package dev.chungjungsoo.gptmobile.data.voice

/** English Pocket graphs exported by john-rocky/LiteRT-Models; see docs/licenses/pocket-compiled.md. */
object PocketCompiledSpeechCatalog {
    const val TITLE = "Pocket GPU / NPU (experimental)"
    const val MODEL_REVISION = "e0e68d4fb79feb529d7d0f8d0bbfad8d15a18a1e"
    const val TOKENIZER_REVISION = "d29db7978e464fb90cb3359ee0c69a273b9142cc"
    const val RUNTIME_ID = "litert-compiled-pocket-2.4.1"

    /** SHA-256 of sorted UTF-8 name<TAB>bytes<TAB>sha256<LF> manifest records. */
    const val MODEL_SHA256 = "f41281301454d45ff9a4064add84bd9fd680b70b5db78e3b68a4223b6ccb51bf"
    const val DEFAULT_VOICE = "alba"
    val VOICES = listOf("alba", "marius", "javert", "charles", "mary", "eve")
    internal const val LM = "pt_flowlm_fused_fp16.tflite"
    internal const val DECODER_TRANSFORMER = "pt_mimi_dec_tx_fp16.tflite"
    internal const val VOCODER = "pt_mimi_deconly_fp16.tflite"
    internal const val TOKENIZER = "pt_tokenizer.model"

    private fun asset(name: String, bytes: Long, sha256: String, remote: String = name) = LocalVoiceAsset(
        name,
        bytes,
        sha256,
        listOf("https://huggingface.co/mlboydaisuke/Pocket-TTS-LiteRT/resolve/$MODEL_REVISION/$remote")
    )

    internal val assets = listOf(
        asset(LM, 169_234_128, "3e9297e8c997226941b6d21149e7dec96168e08207a07e5a5d972da7d85574a7"),
        asset(DECODER_TRANSFORMER, 17_431_184, "5e4b277385bb7a2e5c7475d7421479a4b77431d6aa0ded535ae8c067c6edfa38"),
        asset(VOCODER, 10_550_208, "ea7bd858b8e2841b30be7599e4bfedce7a751d23a9e1946d14101b0440de4e7e"),
        asset("pt_embed_f16.bin", 8_194_048, "82c06e512863fc5b45b14e55e55a911fa2bede6df50e2a7472206af74fc0ac78"),
        asset("pt_input_linear_f32.bin", 131_072, "5f4738bbdba5b4c75ab64c5dfae1e37b884b5e1c7f93c0a1d487dcd5250f17fb"),
        asset("pt_bos_input_f32.bin", 4_096, "184ae4fee2f3c629cc9908a351c8152204c5c076ea1ceae36c23badf8e7cacc8"),
        asset("pt_neutral_latent_f32.bin", 128, "d5f0df1e55dcfcc01ab1f9e7ec41e799bad751911e3adda7f3833cfb7d0aa596"),
        asset("pt_voice_alba.bin", 3_096_580, "f0f15ebfae5aa690ed3a3f25b478198d1892bd3022f39ca738ced07fcaec8e7b", "voices/pt_voice_alba.bin"),
        asset("pt_voice_marius.bin", 3_096_580, "261dd8eef5a3e1f20ff5244e0fc9342a0e4d2cf47142353dc486ba621f89e86f", "voices/pt_voice_marius.bin"),
        asset("pt_voice_javert.bin", 3_096_580, "d9bc65bad1c86041200d5e6b090802867ed32e8a76e07b52774d86d821b62dfb", "voices/pt_voice_javert.bin"),
        asset("pt_voice_charles.bin", 3_096_580, "1ab35c90e580a4413b7391419c854b1bd644329692e3a0b15caa76c816aec32a", "voices/pt_voice_charles.bin"),
        asset("pt_voice_mary.bin", 3_096_580, "b872ee2e16f04726968c7217575e3986712586345787a65da0494b74c9f21cdd", "voices/pt_voice_mary.bin"),
        asset("pt_voice_eve.bin", 3_268_612, "6a8a1391bbfc12c7b861708f82a3b39946baf178928b05915f359c327332af6f", "voices/pt_voice_eve.bin"),
        LocalVoiceAsset(
            TOKENIZER,
            59_339,
            "d461765ae179566678c93091c5fa6f2984c31bbe990bf1aa62d92c64d91bc3f6",
            listOf("https://huggingface.co/kyutai/pocket-tts-without-voice-cloning/resolve/$TOKENIZER_REVISION/languages/english/tokenizer.model")
        )
    )
    val TOTAL_BYTES: Long = assets.sumOf { it.bytes }
}
