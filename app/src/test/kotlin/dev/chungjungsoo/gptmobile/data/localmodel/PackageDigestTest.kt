package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageDigestTest {
    @Test
    fun changedBytesInvalidateInstalledVerificationAndRenamingDoesNotChangeIdentity() {
        val dir = kotlin.io.path.createTempDirectory("model-digest").toFile()
        try {
            val file = File(dir, "model.bin").apply { writeText("abc") }
            val digest = PackageDigest.verify(file, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            File(dir, "model.bin.digest").writeText(digest + "\npublisher-digest-verified")
            assertEquals(digest, PackageDigest.validateInstalled(file))
            val renamed = File(dir, "renamed.bin")
            file.copyTo(renamed)
            assertEquals(digest, PackageDigest.sha256(renamed))
            file.writeText("xyz")
            assertThrows(IOException::class.java) { PackageDigest.validateInstalled(file) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
