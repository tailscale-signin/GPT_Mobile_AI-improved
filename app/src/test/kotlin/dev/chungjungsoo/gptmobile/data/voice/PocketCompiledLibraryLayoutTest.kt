package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PocketCompiledLibraryLayoutTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun privateDispatchReceivesItsVerifiedDependencyClosureWithoutReplacingTheInstalledDispatch() {
        val installed = temporary.newFolder("installed")
        val destination = temporary.newFolder("pocket")
        val originalDispatch = File(installed, "libLiteRtDispatch_Qualcomm.so").apply { writeText("existing LiteRT-LM dispatch") }
        val payload = mapOf(
            "libLiteRtCompilerPlugin_Qualcomm.so" to "compiled compiler",
            "libLiteRtDispatch_Qualcomm.so" to "compiled dispatch",
            "libQnnHtp.so" to "audited backend",
            "libQnnHtpPrepare.so" to "audited compiler support",
            "libQnnSystem.so" to "audited system",
            "libQnnHtpV79Stub.so" to "audited stub",
            "libQnnHtpV79Skel.so" to "audited skeleton"
        )
        val libraries = payload.mapValues { library(it.value.toByteArray()) }
        val result = PocketCompiledLibraryLayout.prepare(destination, libraries, temporary.newFolder("identity")) {}
        assertEquals(destination, result)
        payload.forEach { (name, bytes) -> assertEquals(bytes, File(result, name).readText()) }
        assertEquals("existing LiteRT-LM dispatch", originalDispatch.readText())
        assertEquals(payload.keys, result.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun verifiedReopenDoesNotRecopyLibrariesAndSameLengthCorruptionIsRepaired() {
        val destination = temporary.newFolder("pocket")
        val identity = temporary.newFolder("identity")
        val bytes = "verified runtime".toByteArray()
        var opens = 0
        val libraries = mapOf(
            "libQnnHtp.so" to PocketCompiledLibraryLayout.Library(bytes.size.toLong(), PackageDigest.sha256(bytes)) {
                opens++
                ByteArrayInputStream(bytes)
            }
        )
        repeat(2) { PocketCompiledLibraryLayout.prepare(destination, libraries, identity) {} }
        assertEquals(1, opens)
        val file = File(destination, "libQnnHtp.so")
        assertTrue(file.setWritable(true))
        file.writeBytes(ByteArray(bytes.size) { 'x'.code.toByte() })
        PocketCompiledLibraryLayout.prepare(destination, libraries, identity) {}
        assertEquals(2, opens)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun invalidSourceBytesNeverReplaceTheLastVerifiedFile() {
        val destination = temporary.newFolder("pocket")
        val identity = temporary.newFolder("identity")
        val old = File(destination, "libQnnSystem.so").apply { writeText("last valid") }
        val expected = "new verified".toByteArray()
        for (bad in listOf("bad".toByteArray(), ByteArray(expected.size) { 1 }, ByteArray(expected.size + 1))) {
            val libraries = mapOf("libQnnSystem.so" to PocketCompiledLibraryLayout.Library(expected.size.toLong(), PackageDigest.sha256(expected)) { ByteArrayInputStream(bad) })
            assertThrows(IllegalStateException::class.java) { PocketCompiledLibraryLayout.prepare(destination, libraries, identity) {} }
            assertEquals("last valid", old.readText())
            assertEquals(listOf("libQnnSystem.so"), destination.listFiles()!!.map { it.name })
        }
    }

    @Test fun cancellationDuringCopyDoesNotPublishPartialNativeCode() {
        val destination = temporary.newFolder("pocket")
        val bytes = ByteArray(200_000) { 1 }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            PocketCompiledLibraryLayout.prepare(destination, mapOf("libQnnHtp.so" to library(bytes)), temporary.newFolder("identity")) {
                if (++checks == 4) throw CancellationException()
            }
        }
        assertTrue(destination.listFiles()!!.isEmpty())
    }

    @Test fun destinationSymlinkIsReplacedWithoutModifyingItsTarget() {
        val destination = temporary.newFolder("pocket")
        val original = temporary.newFile("other-runtime").apply { writeText("other runtime") }
        val path = File(destination, "libQnnHtp.so").toPath()
        Files.createSymbolicLink(path, original.toPath())
        val expected = "verified replacement".toByteArray()
        PocketCompiledLibraryLayout.prepare(destination, mapOf("libQnnHtp.so" to library(expected)), temporary.newFolder("identity")) {}
        assertFalse(Files.isSymbolicLink(path))
        assertArrayEquals(expected, path.toFile().readBytes())
        assertEquals("other runtime", original.readText())
    }

    @Test fun invalidInventoriesAndDirectoryLinksAreRejectedBeforeOpeningAnySource() {
        var opened = false
        val descriptor = PocketCompiledLibraryLayout.Library(1, "a".repeat(64)) {
            opened = true
            ByteArrayInputStream(byteArrayOf(1))
        }
        val destination = File(temporary.root, "pocket")
        val identity = File(temporary.root, "identity")
        for (name in listOf("../libQnnHtp.so", "/libQnnHtp.so", "libQnnHtp.so/child")) {
            assertThrows(IllegalArgumentException::class.java) { PocketCompiledLibraryLayout.prepare(destination, mapOf(name to descriptor), identity) {} }
        }
        assertThrows(IllegalArgumentException::class.java) { PocketCompiledLibraryLayout.prepare(destination, emptyMap(), identity) {} }
        Files.createSymbolicLink(destination.toPath(), temporary.newFolder("other").toPath())
        assertThrows(IllegalStateException::class.java) { PocketCompiledLibraryLayout.prepare(destination, mapOf("libQnnHtp.so" to descriptor), identity) {} }
        assertFalse(opened)
    }

    private fun library(bytes: ByteArray) = PocketCompiledLibraryLayout.Library(bytes.size.toLong(), PackageDigest.sha256(bytes)) { ByteArrayInputStream(bytes) }
}
