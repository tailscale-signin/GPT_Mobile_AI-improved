package dev.chungjungsoo.gptmobile.data.voice

import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

internal object KokoroModelPack {
    const val DOWNLOAD_BYTES = 132_353_410L
    const val MODEL = "model.int8.onnx"
    const val MODEL_SHA256 = "75635ea3b85a32d00094dcdd61a258aba87c160f7c264c304a7b3ae9d734195e"
    const val VOICES = "voices.bin"
    const val TOKENS = "tokens.txt"
    const val US_LEXICON = "lexicon-us-en.txt"
    const val GB_LEXICON = "lexicon-gb-en.txt"

    // Only the English renderer's inputs are installed from the verified release.
    // Both releases contain byte-identical frontend, voice and licence assets.
    private fun files(variant: KokoroModelVariant) = listOf(
        file(variant.modelFile, variant.modelBytes, variant.modelSha256),
        file(VOICES, 28_200_960, "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29"),
        file(TOKENS, 687, "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425"),
        file(US_LEXICON, 5_956_885, "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14"),
        file(GB_LEXICON, 6_366_635, "c4cbb37316f62210dff52718a7afcaae24f50c032cc75ab47ae67b831d1049e7"),
        file("LICENSE", 11_358, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30")
    )

    fun store(
        voiceModelsDirectory: File,
        sourceFactory: () -> LocalVoiceAssetSource = { HttpsVoiceAssetSource() },
        variant: KokoroModelVariant = KokoroModelVariant.INT8
    ): LocalVoiceModelStore {
        val archive = LocalVoiceAsset(variant.archiveFile, variant.downloadBytes, variant.archiveSha256, listOf(variant.archiveUrl))
        return LocalVoiceModelStore(
            File(voiceModelsDirectory, variant.revision),
            listOf(archive),
            expandedAssets = files(variant),
            expandArchive = { directory -> unpack(directory, variant) },
            revision = archive.sha256,
            sourceFactory = sourceFactory
        )
    }

    /** Called only after the archive length and SHA-256 have been checked. */
    private suspend fun unpack(directory: File, variant: KokoroModelVariant) {
        val archiveFile = File(directory, variant.archiveFile)
        val expected = files(variant).associateBy { "${variant.revision}/${it.name}" }
        val extracted = mutableSetOf<String>()
        val buffer = ByteArray(64 * 1024)
        archiveFile.inputStream().buffered().use { source ->
            TarArchiveInputStream(BZip2CompressorInputStream(source)).use { tar ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: break
                    val asset = expected[entry.name] ?: continue
                    if (!entry.isFile || entry.isLink || entry.isSymbolicLink || entry.isSparse || entry.size != asset.bytes || !extracted.add(asset.name)) {
                        throw IOException("The Kokoro package has an invalid model file.")
                    }
                    // Destination paths come only from the pinned list above.
                    File(directory, asset.name).outputStream().use { output ->
                        var remaining = asset.bytes
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count <= 0) throw IOException("The Kokoro package ended before all model files were extracted.")
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                        output.fd.sync()
                    }
                }
            }
        }
        check(extracted.size == expected.size) { "Could not finish preparing the Kokoro model." }
    }

    private fun file(name: String, bytes: Long, sha256: String) = LocalVoiceAsset(name, bytes, sha256, emptyList())
}
