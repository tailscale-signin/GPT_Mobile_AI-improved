package dev.chungjungsoo.gptmobile.data.voice

/** Different weight sets require separate installation, cache and comparison identities. */
internal enum class KokoroModelVariant(
    val displayName: String,
    val revision: String,
    val modelFile: String,
    val modelBytes: Long,
    val modelSha256: String,
    val downloadBytes: Long,
    val archiveSha256: String
) {
    INT8(
        "Compact INT8",
        "kokoro-int8-multi-lang-v1_0",
        KokoroModelPack.MODEL,
        114_202_993L,
        KokoroModelPack.MODEL_SHA256,
        KokoroModelPack.DOWNLOAD_BYTES,
        "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653"
    ),
    ORIGINAL_FP32(
        "Original FP32",
        "kokoro-multi-lang-v1_0",
        "model.onnx",
        325_560_556L,
        "b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11",
        349_886_476L,
        "60703707bc05ccd48d60e1eb723f38b07d6e474795a0b6a588d7220400e0f7a4"
    );

    val archiveFile: String get() = "$revision.tar.bz2"
    val archiveUrl: String get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$archiveFile"
}
