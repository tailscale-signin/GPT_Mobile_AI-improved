package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalImportPreviewTest {
    @Test fun fileHeaderMustMatchSupportedFormatAndBuild() {
        val bytes = "LITERTLM".toByteArray()
        val preview = LocalImportPreview.inspect(bytes.inputStream(), "model.litertlm", 20_000_000, false)
        assertEquals("LiteRT-LM", preview.runtime)
        assertEquals(emptyList<String>(), preview.backends)
        assertThrows(IllegalStateException::class.java) { LocalImportPreview.inspect("not real".byteInputStream(), "model.litertlm", 20_000_000, false) }
        assertThrows(IllegalArgumentException::class.java) { LocalImportPreview.inspect(("GGUF".toByteArray() + byteArrayOf(3, 0, 0, 0)).inputStream(), "model.gguf", null, false) }
    }

    @Test fun manifestPreviewReportsDeclaredAssetsAndRejectsUnsafePaths() {
        val manifest = ModelArtifactManifest(familyId = "model", artifactId = "id", runtime = ArtifactRuntime.GENIEX_LLAMA_CPP, format = "gguf", entryFile = "model.gguf", files = listOf(ArtifactFile("model.gguf", 100, "a".repeat(64))), contextTokens = 4096, supportedBackends = setOf("cpu"))
        fun inspect(value: ModelArtifactManifest) = LocalImportPreview.inspect(Json.encodeToString(value).byteInputStream(), "folder", null, true, folder = true)
        val preview = inspect(manifest)
        assertEquals(100L, preview.installedBytes)
        assertEquals(4096, preview.contextTokens)
        assertEquals(listOf("cpu"), preview.backends)
        assertThrows(IllegalArgumentException::class.java) { inspect(manifest.copy(entryFile = "../bad", files = listOf(ArtifactFile("../bad", 100, "a".repeat(64))))) }
        assertThrows(IllegalArgumentException::class.java) { LocalImportPreview.inspect(ByteArray(262_145).inputStream(), "folder", null, true, folder = true) }
    }

    @Test fun ordinaryZipCannotMasqueradeAsModelBundle() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("photo.jpg"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) { LocalImportPreview.inspect(bytes.toByteArray().inputStream(), "model.zip", bytes.size().toLong(), true) }
    }
}
