package dev.chungjungsoo.gptmobile.data.voice

import java.security.MessageDigest

/** Exact, independently installed weights for one model and execution route. */
internal data class VoiceRecognitionModelPack(
    val id: String,
    val model: VoiceRecognitionModel,
    val compute: VoiceRecognitionCompute,
    val revision: String,
    val assets: List<LocalVoiceAsset>,
    val expandedAssets: List<LocalVoiceAsset> = assets,
    val encoderFile: String? = null,
    val decoderFile: String? = null,
    val joinerFile: String? = null,
    val tokensFile: String? = null,
    val chunkMillis: Int? = null,
    val requiredSocModel: String? = null,
    val archiveDirectory: String? = null,
    val licenseName: String,
    val licenseUrl: String,
    val sourceUrl: String
) {
    val downloadBytes: Long get() = assets.sumOf { it.bytes }
    val totalBytes: Long get() = downloadBytes
    val installedBytes: Long get() = expandedAssets.sumOf { it.bytes }
    val modelIdentity: String = MessageDigest.getInstance("SHA-256").digest(
        expandedAssets.sortedBy { it.name }.joinToString("\n") { "${it.name}:${it.bytes}:${it.sha256}" }.toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it) }
}

/** Published SHA-256s and immutable Hub revisions; selection never starts a download. */
internal object VoiceRecognitionModelCatalog {
    private const val MOONSHINE_REVISION = "e318117203f245fda2ca0142db6b8c057d86671c"
    private const val MOONSHINE_LICENSE = "https://github.com/moonshine-ai/moonshine/blob/d8d8fa29d05ca67441a7719e70099f9f48917a93/LICENSE"
    private const val NEMOTRON_LICENSE = "https://openmdw.ai/license/1-1/"
    private const val NEMOTRON_SOURCE = "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/tree/ea30d66debe3740a08b573244286791d423d6b3e"

    private val tiny = VoiceRecognitionModelPack(
        id = "moonshine-tiny-en-20260821",
        model = VoiceRecognitionModel.MOONSHINE_TINY,
        compute = VoiceRecognitionCompute.CPU,
        revision = LocalVoiceModelStore.MODEL_REVISION,
        assets = LocalVoiceModelStore.MODEL_ASSETS,
        licenseName = "MIT",
        licenseUrl = MOONSHINE_LICENSE,
        sourceUrl = "https://huggingface.co/moonshine-ai/moonshine-voice-assets/tree/0bf2f2e5aff22e6fbba4300b00a4e00bbc4f8aae/model/${LocalVoiceModelStore.MODEL_REVISION}"
    )

    private val small = VoiceRecognitionModelPack(
        id = "moonshine-small-en-20260821-attention",
        model = VoiceRecognitionModel.MOONSHINE_SMALL,
        compute = VoiceRecognitionCompute.CPU,
        revision = "small-streaming-en/quantized_26_08_21@$MOONSHINE_REVISION",
        assets = listOf(
            asset("adapter.ort", 2_870_368L, "c665f742364febad597cc9ac1e0b341ffbee0e24a1466e2f3bde95e6e4771762", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/small-streaming-en/quantized_26_08_21/"),
            asset("cross_kv.ort", 5_356_536L, "e2d3417144e9514055ebfefe8dcc4c0a55a55adcb8530435844c75c53e352bf6", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/small-streaming-en/quantized_26_08_21/"),
            asset("decoder_kv.ort", 81_878_600L, "1a05465b1dd955858dfcbee039c0020fb5dd982b0f5094c34e61735d518d771b", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/small-streaming-en/quantized_26_08_21/"),
            asset("decoder_kv_with_attention.ort", 81_766_608L, "19653ca93362cd0445837cbaabc9f36f462410b357dd4a102d9c8ca1c3d13c8c", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/small-streaming-en/quantized_26_08_21/"),
            asset("encoder.ort", 44_148_576L, "2d4d973e91e8aca08c51e7e7efa28a46ab265b63d809d5294d18b86bcd85b993", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/small-streaming-en/quantized_26_08_21/"),
            asset("frontend.model.ort", 26_944L, "09b1210ae30dc5f0f3e45f0ebab914c254741323114f53fbbe5ae62cca35058f", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/small-streaming-en/quantized_26_08_21/"),
            asset("frontend.weights.ort", 7_769_464L, "7ef97521bd4bad3928f5bb6808586f4fcc6e92bd5990394112eed7d4052ec338", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/small-streaming-en/quantized_26_08_21/"),
            asset("streaming_config.json", 512L, "26f02b6afb22d60871a5efd85c3d38e569cc0ddb6c5eb6e93d3260152ae8a47a", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/small-streaming-en/quantized_26_08_21/"),
            asset("tokenizer.bin", 249_974L, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/small-streaming-en/quantized_26_08_21/")
        ),
        licenseName = "MIT",
        licenseUrl = MOONSHINE_LICENSE,
        sourceUrl = "https://huggingface.co/moonshine-ai/moonshine-voice-assets/tree/$MOONSHINE_REVISION/model/small-streaming-en/quantized_26_08_21"
    )

    private val medium = VoiceRecognitionModelPack(
        id = "moonshine-medium-en-20260821-attention",
        model = VoiceRecognitionModel.MOONSHINE_MEDIUM,
        compute = VoiceRecognitionCompute.CPU,
        revision = "medium-streaming-en/quantized_26_08_21@$MOONSHINE_REVISION",
        assets = listOf(
            asset("adapter.ort", 3_651_296L, "3f2a287def57cc094367a0eec3c4f5fc36a32ec420e86764b696920991b20281", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/medium-streaming-en/quantized_26_08_21/"),
            asset("cross_kv.ort", 11_643_776L, "642f6e21cd305be79342207c6f9e6b681d469d55bc48c72b27b84846fb71fd1e", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/medium-streaming-en/quantized_26_08_21/"),
            asset("decoder_kv.ort", 146_972_408L, "193bb366492b74fc4ad338c6778e8d8eb916aaa11b5aa264f9057f4db7759486", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/medium-streaming-en/quantized_26_08_21/"),
            asset("decoder_kv_with_attention.ort", 146_813_344L, "b5de4b5639b4155fb7956161e43196eb27a26db293f11c1d245effaf6b738118", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/medium-streaming-en/quantized_26_08_21/"),
            asset("encoder.ort", 94_705_376L, "12915e76ebac7dd287c5ea63965d06103a53ba1ce242a4a34f318f3958c60c37", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/medium-streaming-en/quantized_26_08_21/"),
            asset("frontend.model.ort", 28_720L, "95768855c70c8251eeecc05fedf69999da1b8ab16f605c9f457fd3354b0ad6b5", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/medium-streaming-en/quantized_26_08_21/"),
            asset("frontend.weights.ort", 11_889_560L, "5ac941f490cbe035b335b99a414cc393d62d4c6f9f2423495b286870d271d709", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/medium-streaming-en/quantized_26_08_21/"),
            asset("streaming_config.json", 513L, "28e83b7a28e91472692a035e0dae3116422ae43aeb2bef5ed822c44ce89b88af", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/medium-streaming-en/quantized_26_08_21/"),
            asset("tokenizer.bin", 249_974L, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d", "https://huggingface.co/moonshine-ai/moonshine-voice-assets/resolve/$MOONSHINE_REVISION/model/medium-streaming-en/quantized_26_08_21/")
        ),
        licenseName = "MIT",
        licenseUrl = MOONSHINE_LICENSE,
        sourceUrl = "https://huggingface.co/moonshine-ai/moonshine-voice-assets/tree/$MOONSHINE_REVISION/model/medium-streaming-en/quantized_26_08_21"
    )

    private val zipformerEn = VoiceRecognitionModelPack(
        id = "zipformer-en-int8",
        model = VoiceRecognitionModel.ZIPFORMER_EN,
        compute = VoiceRecognitionCompute.CPU,
        revision = "d42f2d9f7ca24806fb667456a18a9f1b60f70d16",
        assets = listOf(
            asset("decoder-epoch-99-avg-1.int8.onnx", 539_499L, "21e2a2acd961b3ac72f55be2f10f1a285e1b0b0ba010d7c0b6eab141411b163c", "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/d42f2d9f7ca24806fb667456a18a9f1b60f70d16/"),
            asset("encoder-epoch-99-avg-1.int8.onnx", 42_845_182L, "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3", "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/d42f2d9f7ca24806fb667456a18a9f1b60f70d16/"),
            asset("joiner-epoch-99-avg-1.int8.onnx", 259_572L, "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e", "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/d42f2d9f7ca24806fb667456a18a9f1b60f70d16/"),
            asset("tokens.txt", 5_048L, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb", "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/d42f2d9f7ca24806fb667456a18a9f1b60f70d16/")
        ),
        encoderFile = "encoder-epoch-99-avg-1.int8.onnx",
        decoderFile = "decoder-epoch-99-avg-1.int8.onnx",
        joinerFile = "joiner-epoch-99-avg-1.int8.onnx",
        tokensFile = "tokens.txt",
        licenseName = "Apache-2.0",
        licenseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/blob/d42f2d9f7ca24806fb667456a18a9f1b60f70d16/README.md",
        sourceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/tree/d42f2d9f7ca24806fb667456a18a9f1b60f70d16"
    )

    private val zipformerFr = VoiceRecognitionModelPack(
        id = "zipformer-fr-int8",
        model = VoiceRecognitionModel.ZIPFORMER_FR,
        compute = VoiceRecognitionCompute.CPU,
        revision = "3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73",
        assets = listOf(
            asset("decoder-epoch-29-avg-9-with-averaged-model.int8.onnx", 1_307_157L, "e72b2b9ed36355bd0dd43433f7dd258e7226ab54c9ef42b28c73ebb785805623", "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/resolve/3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73/"),
            asset("encoder-epoch-29-avg-9-with-averaged-model.int8.onnx", 126_655_903L, "47a94a7fdc8dff63d708be4ea0535747640224467f91e238311f1ddbdd09327e", "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/resolve/3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73/"),
            asset("joiner-epoch-29-avg-9-with-averaged-model.int8.onnx", 259_572L, "fc2f3bb851a15a532c6f2422d53eecd1ca949f12b0897e07a852021c30481711", "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/resolve/3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73/"),
            asset("tokens.txt", 4_819L, "37fb3f2a7bcb85e5fff3f1f66be04e6fbb05077a22f56d177fe85704e945fb31", "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/resolve/3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73/")
        ),
        encoderFile = "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
        decoderFile = "decoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
        joinerFile = "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx",
        tokensFile = "tokens.txt",
        licenseName = "Apache-2.0",
        licenseUrl = "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/blob/3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73/README.md",
        sourceUrl = "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/tree/3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73"
    )

    private val nemotronCpu = VoiceRecognitionModelPack(
        id = "nemotron-3.5-560ms-int8",
        model = VoiceRecognitionModel.NEMOTRON_3_5,
        compute = VoiceRecognitionCompute.CPU,
        revision = "ab43d895f5985b1bbab8b6eac8607fcdc05343f3",
        assets = listOf(
            asset("decoder.int8.onnx", 14_978_075L, "19f9c98fc6d0a2c33a65a43b36fdb2e914c26c0aa9764be3aebc502a1e982fb0", "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11/resolve/ab43d895f5985b1bbab8b6eac8607fcdc05343f3/"),
            asset("encoder.int8.onnx", 657_601_403L, "012e9321373af99021415e0b0eb3ec827b4be3153be6f30d9b448fe65e896e68", "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11/resolve/ab43d895f5985b1bbab8b6eac8607fcdc05343f3/"),
            asset("joiner.int8.onnx", 9_504_438L, "4101c7c679a0bc30483794b27a059e34e79232aa2068d78d51231a22c8b0d7ce", "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11/resolve/ab43d895f5985b1bbab8b6eac8607fcdc05343f3/"),
            asset("tokens.txt", 131_440L, "729cc103155bafa785f9cd45746cd41cabe97eab7182fc04d594129587958f8a", "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11/resolve/ab43d895f5985b1bbab8b6eac8607fcdc05343f3/")
        ),
        encoderFile = "encoder.int8.onnx",
        decoderFile = "decoder.int8.onnx",
        joinerFile = "joiner.int8.onnx",
        tokensFile = "tokens.txt",
        chunkMillis = 560,
        licenseName = "OpenMDW-1.1",
        licenseUrl = NEMOTRON_LICENSE,
        sourceUrl = NEMOTRON_SOURCE
    )

    private val nemotronGpu = VoiceRecognitionModelPack(
        id = "nemotron-3.5-560ms-fp32-gpu",
        model = VoiceRecognitionModel.NEMOTRON_3_5,
        compute = VoiceRecognitionCompute.GPU,
        revision = "2072aba9fb2d6ee6202b69e441761599ab7520b8",
        assets = listOf(
            asset("decoder.onnx", 59_764_944L, "f9c59ee6fa130bc2ba349dbcbba7c74a4a960a98d4196295ba4e8e5d6bde6b68", "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-2026-06-11/resolve/2072aba9fb2d6ee6202b69e441761599ab7520b8/"),
            asset("encoder.data", 2_454_405_120L, "7584f85df76bc9ae6fbdfa53aa8d97b07a842525d1c501d536d77fd9e4f57ac7", "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-2026-06-11/resolve/2072aba9fb2d6ee6202b69e441761599ab7520b8/"),
            asset("encoder.onnx", 42_247_484L, "f1e540e03cb2cc57f5c0f6416b893768cfda23e756bfc0a2c4d502a1350325af", "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-2026-06-11/resolve/2072aba9fb2d6ee6202b69e441761599ab7520b8/"),
            asset("joiner.onnx", 37_824_291L, "a6bd74c0a31cbde0da0368c1e29d171752108c7ad1231630e079a7d97ceee6f0", "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-2026-06-11/resolve/2072aba9fb2d6ee6202b69e441761599ab7520b8/"),
            asset("tokens.txt", 131_440L, "729cc103155bafa785f9cd45746cd41cabe97eab7182fc04d594129587958f8a", "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-2026-06-11/resolve/2072aba9fb2d6ee6202b69e441761599ab7520b8/")
        ),
        encoderFile = "encoder.onnx",
        decoderFile = "decoder.onnx",
        joinerFile = "joiner.onnx",
        tokensFile = "tokens.txt",
        chunkMillis = 560,
        licenseName = "OpenMDW-1.1",
        licenseUrl = NEMOTRON_LICENSE,
        sourceUrl = NEMOTRON_SOURCE
    )

    private val nemotronNpu = VoiceRecognitionModelPack(
        id = "nemotron-3.5-320ms-sm8750-htp",
        model = VoiceRecognitionModel.NEMOTRON_3_5,
        compute = VoiceRecognitionCompute.NPU,
        revision = "130c0e6fede54e958b2bfe7e8eb06c024dc189f94b6b3187db1279df66f48aae",
        assets = listOf(
            asset(
                "sherpa-onnx-qnn-SM8750-binary-nemotron-3.5-asr-streaming-0.6b-320ms.tar.bz2",
                442_444_790L,
                "130c0e6fede54e958b2bfe7e8eb06c024dc189f94b6b3187db1279df66f48aae",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn-binary-3/"
            )
        ),
        expandedAssets = listOf(
            asset("joiner.bin", 9_732_096L, "c3d9da3f500cc61706061d60f3ac6c6958c7bfbdc61c063a01d741312d766d36"),
            asset("info.txt", 20L, "6a66ac1da2e474a0af5b493f79c0f7d372dcf94f9e922198c498817ab1b8e728"),
            asset("decoder.bin", 29_978_624L, "4c97678937969d5347b1a1b861c27bc6cde3a0e9b76221c011b6ec19a5fbb72b"),
            asset("encoder.bin", 609_259_520L, "bc1a3fa4a127871b22435698a7e26dc39013f5227a0f90ca4ba24ac5935b17e9"),
            asset("tokens.txt", 131_440L, "729cc103155bafa785f9cd45746cd41cabe97eab7182fc04d594129587958f8a")
        ),
        encoderFile = "encoder.bin",
        decoderFile = "decoder.bin",
        joinerFile = "joiner.bin",
        tokensFile = "tokens.txt",
        chunkMillis = 320,
        requiredSocModel = "SM8750",
        archiveDirectory = "sherpa-onnx-qnn-SM8750-binary-nemotron-3.5-asr-streaming-0.6b-320ms",
        licenseName = "OpenMDW-1.1",
        licenseUrl = NEMOTRON_LICENSE,
        sourceUrl = NEMOTRON_SOURCE
    )

    val packs = listOf(tiny, small, medium, zipformerEn, zipformerFr, nemotronCpu, nemotronGpu, nemotronNpu)

    fun pack(model: VoiceRecognitionModel, compute: VoiceRecognitionCompute = VoiceRecognitionCompute.CPU): VoiceRecognitionModelPack? =
        packs.firstOrNull { it.model == model && it.compute == compute }

    fun computes(model: VoiceRecognitionModel): List<VoiceRecognitionCompute> = packs.filter { it.model == model }.map { it.compute }

    private fun asset(name: String, bytes: Long, sha256: String, baseUrl: String? = null) =
        LocalVoiceAsset(name, bytes, sha256, baseUrl?.let { listOf(it + name) } ?: emptyList())
}
