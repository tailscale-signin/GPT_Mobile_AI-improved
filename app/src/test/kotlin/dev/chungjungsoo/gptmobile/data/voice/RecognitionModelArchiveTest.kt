package dev.chungjungsoo.gptmobile.data.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecognitionModelArchiveTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun archiveOnlyExtractsPinnedNamesIntoItsInstallationDirectory() = runBlocking {
        val weights = "verified graph".toByteArray()
        val archive = archive("model/encoder.bin" to weights, "../outside.bin" to "unrelated".toByteArray())
        val pack = pack(archive, weights)
        val directory = files.newFolder()
        File(directory, pack.assets.single().name).writeBytes(archive)
        RecognitionModelStore.unpack(directory, pack)
        assertEquals("verified graph", File(directory, "encoder.bin").readText())
        assertFalse(File(directory.parentFile, "outside.bin").exists())
    }

    @Test
    fun duplicateExpectedEntryCannotOverwriteAnAlreadyExtractedGraph() = runBlocking {
        val weights = "verified graph".toByteArray()
        val archive = archive("model/encoder.bin" to weights, "model/encoder.bin" to "changed graph!".toByteArray())
        val pack = pack(archive, weights)
        val directory = files.newFolder()
        File(directory, pack.assets.single().name).writeBytes(archive)
        assertTrue(runCatching { RecognitionModelStore.unpack(directory, pack) }.exceptionOrNull() is IOException)
        assertEquals("verified graph", File(directory, "encoder.bin").readText())
    }

    @Test
    fun missingOrWrongSizedEntryCannotCompleteAnInstallation() = runBlocking {
        val weights = "verified graph".toByteArray()
        for (archive in listOf(archive("model/unexpected.bin" to weights), archive("model/encoder.bin" to byteArrayOf(1)))) {
            val pack = pack(archive, weights)
            val directory = files.newFolder()
            File(directory, pack.assets.single().name).writeBytes(archive)
            assertTrue(runCatching { RecognitionModelStore.unpack(directory, pack) }.exceptionOrNull() is IOException)
            assertFalse(File(directory, "verified").exists())
        }
    }

    private fun pack(archive: ByteArray, weights: ByteArray): VoiceRecognitionModelPack {
        val original = requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionCompute.NPU))
        return original.copy(
            assets = listOf(LocalVoiceAsset("model.tar.bz2", archive.size.toLong(), hash(archive), listOf("https://models.example/model.tar.bz2"))),
            expandedAssets = listOf(LocalVoiceAsset("encoder.bin", weights.size.toLong(), hash(weights), emptyList())),
            archiveDirectory = "model"
        )
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            for ((name, contents) in entries) {
                val entry = TarArchiveEntry(name).apply { size = contents.size.toLong() }
                tar.putArchiveEntry(entry)
                tar.write(contents)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
        return bytes.toByteArray()
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
