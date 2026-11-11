package dev.chungjungsoo.gptmobile.data.voice

import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

internal object KokoroModelPack {
    private const val REVISION = "kokoro-int8-multi-lang-v1_0"
    private const val ARCHIVE = "$REVISION.tar.bz2"
    const val DOWNLOAD_BYTES = 132_353_410L
    const val MODEL = "model.int8.onnx"
    const val VOICES = "voices.bin"
    const val TOKENS = "tokens.txt"
    const val US_LEXICON = "lexicon-us-en.txt"
    const val GB_LEXICON = "lexicon-gb-en.txt"

    private val archive = LocalVoiceAsset(
        ARCHIVE,
        DOWNLOAD_BYTES,
        "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653",
        listOf("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$ARCHIVE")
    )

    // Only the English renderer's inputs are installed from the verified release.
    private val files = listOf(
        file(MODEL, 114_202_993, "75635ea3b85a32d00094dcdd61a258aba87c160f7c264c304a7b3ae9d734195e"),
        file(VOICES, 28_200_960, "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29"),
        file(TOKENS, 687, "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425"),
        file(US_LEXICON, 5_956_885, "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14"),
        file(GB_LEXICON, 6_366_635, "c4cbb37316f62210dff52718a7afcaae24f50c032cc75ab47ae67b831d1049e7"),
        file("LICENSE", 11_358, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30")
    )

    fun store(voiceModelsDirectory: File) = LocalVoiceModelStore(
        File(voiceModelsDirectory, REVISION),
        listOf(archive),
        expandedAssets = files,
        expandArchive = ::unpack,
        revision = archive.sha256,
        sourceFactory = ::HttpsVoiceAssetSource
    )

    /** Called only after the archive length and SHA-256 have been checked. */
    private suspend fun unpack(directory: File) {
        val archiveFile = File(directory, ARCHIVE)
        val expected = files.associateBy { "$REVISION/${it.name}" }
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
        check(extracted.size == files.size && archiveFile.delete()) { "Could not finish preparing the Kokoro model." }
    }

    private fun file(name: String, bytes: Long, sha256: String) = LocalVoiceAsset(name, bytes, sha256, emptyList())
}
