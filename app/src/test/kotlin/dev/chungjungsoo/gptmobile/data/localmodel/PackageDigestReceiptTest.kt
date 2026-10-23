package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageDigestReceiptTest {
    @Test fun durableReceiptAvoidsRehashAfterMemoryCacheIsLost() {
        val root = Files.createTempDirectory("persisted-verification").toFile()
        try {
            PackageDigest.configureReceiptDirectory(File(root, "private-receipts"))
            val asset = File(root, "weights.gguf").apply { writeText("original") }
            val expected = PackageDigest.sha256(asset)
            PackageDigest.verifyInstalledAssetCached(asset, expected)
            val before = PackageDigest.bytesHashed.get()
            PackageDigest.clearMemoryReceipts()
            assertEquals(expected, PackageDigest.verifyInstalledAssetCached(asset, expected))
            if (PackageDigest.installedFileIdentity(asset) != null) assertEquals(before, PackageDigest.bytesHashed.get())
            val timestamp = asset.lastModified()
            asset.writeText("replaced")
            asset.setLastModified(timestamp)
            assertTrue(runCatching { PackageDigest.verifyInstalledAssetCached(asset, expected) }.isFailure)
        } finally {
            PackageDigest.configureReceiptDirectory(null)
            root.deleteRecursively()
        }
    }

    @Test fun standaloneIdentityInvalidatesSameSizeEditWithPreservedModificationTime() {
        val root = kotlin.io.path.createTempDirectory("standalone-identity").toFile()
        try {
            val file = java.io.File(root, "model.bin").apply { writeText("original") }
            val cache = java.io.File(root, "cache")
            val first = PackageDigest.journalIdentity(file, cache)
            val afterFirst = PackageDigest.bytesHashed.get()
            assertEquals(first, PackageDigest.journalIdentity(file, cache))
            if (PackageDigest.installedFileIdentity(file) != null) assertEquals(afterFirst, PackageDigest.bytesHashed.get())
            val timestamp = file.lastModified()
            file.writeText("replaced")
            check(file.setLastModified(timestamp))
            org.junit.Assert.assertNotEquals(first, PackageDigest.journalIdentity(file, cache))
        } finally {
            root.deleteRecursively()
        }
    }

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
