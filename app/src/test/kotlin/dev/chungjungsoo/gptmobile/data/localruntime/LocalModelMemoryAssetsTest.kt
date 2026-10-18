package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelMemoryAssetsTest {
    @Test fun tinyManifestAccountsForFourGiBWeightAsset() {
        val root = Files.createTempDirectory("manifest-memory").toFile()
        try {
            val weights = File(root, "weights.gguf")
            val size = 4L * 1024 * 1024 * 1024
            RandomAccessFile(weights, "rw").use { it.setLength(size) }
            val manifest = File(root, "manifest.json")
            manifest.writeText("""{"familyId":"f","artifactId":"a","runtime":"LLAMA_CPP","format":"gguf","entryFile":"weights.gguf","files":[{"path":"weights.gguf","sizeBytes":$size,"sha256":"${"0".repeat(64)}"}],"contextTokens":1024,"supportedBackends":["cpu"]}""")
            assertTrue(manifest.length() < 1024)
            assertEquals(size, localModelAssetBytes(manifest))
            weights.delete()
            assertTrue(runCatching { localModelAssetBytes(manifest) }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }
}
