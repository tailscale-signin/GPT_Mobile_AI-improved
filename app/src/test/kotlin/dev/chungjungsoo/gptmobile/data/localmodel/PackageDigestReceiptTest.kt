package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageDigestReceiptTest {
    @Test fun warmValidationAvoidsHashingAndSameSizeReplacementInvalidatesReceipt() {
        val root = Files.createTempDirectory("asset-receipt").toFile()
        try {
            val asset = File(root, "weights.gguf").apply { writeText("original") }
            val expected = PackageDigest.sha256(asset)
            PackageDigest.verifyInstalledAssetCached(asset, expected)
            val before = PackageDigest.bytesHashed.get()
            PackageDigest.verifyInstalledAssetCached(asset, expected)
            // A filesystem without kernel identity correctly falls back to hashing.
            if (runCatching { Files.readAttributes(asset.toPath(), "unix:ctime,ino") }.isSuccess) assertEquals(before, PackageDigest.bytesHashed.get())
            val modified = asset.lastModified()
            asset.writeText("replaced")
            asset.setLastModified(modified)
            assertTrue(runCatching { PackageDigest.verifyInstalledAssetCached(asset, expected) }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }
}
